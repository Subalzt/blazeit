package dev.periy.bridge.server

import android.content.Context
import android.util.Base64
import android.util.Log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BridgeTus"

/**
 * Persisted state of one upload stream.
 *
 * A stream is a contiguous window `[baseOffset, baseOffset + uploadLength)` of one
 * destination file. A single-stream upload is a group with one window covering the whole
 * file, so there is exactly one code path for both cases.
 *
 * `offset` is the load-bearing field: it is the number of bytes *within this window*
 * that the server is willing to swear are on durable storage. It only ever advances
 * after a successful fdatasync. See [TusStore.append].
 */
@Serializable
data class TusInfo(
    val id: String,
    val groupId: String,
    val baseOffset: Long,
    val uploadLength: Long,
    val offset: Long,
    val createdAt: Long,
    val completed: Boolean = false,
)

/** One destination file, and the streams filling it. */
@Serializable
data class TusGroup(
    val id: String,
    val slotRef: String,
    val total: Long,
    val metadata: Map<String, String>,
    val streamIds: List<String>,
    val createdAt: Long,
    val preallocated: Boolean = false,
    val finalized: Boolean = false,
) {
    val filename: String get() = metadata["filename"]?.takeIf { it.isNotBlank() } ?: "upload-$id"
    val mime: String get() = metadata["filetype"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    /** Subfolders to put the file in, when it came from an uploaded folder. */
    val folder: List<String> get() = folderSegments(metadata["relativepath"])
    /** How the transfer is named on the phone: its path inside the folder, if any. */
    val displayName: String get() = (folder + sanitizeFilename(filename)).joinToString("/")
}

/**
 * Turns the browser's relative path ("Photos/2024") into safe folder names. Empty, "." and
 * ".." segments are dropped, so no path can climb out of the destination folder, and each
 * name gets the same cleaning as a file name.
 */
fun folderSegments(raw: String?): List<String> =
    raw.orEmpty()
        .split('/', '\\')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .map(::sanitizeFilename)
        .take(MAX_FOLDER_DEPTH)

private const val MAX_FOLDER_DEPTH = 32

/**
 * tus 1.0.0 core + Creation, plus parallel streaming, backed by [Storage].
 *
 * ## Why parallel streams exist
 *
 * A single TCP connection over Wi-Fi does not saturate the link. Loss and jitter shrink
 * the congestion window, and a stream spends much of its life recovering rather than
 * sending; in practice one connection gets roughly half of what the radio can carry.
 * Several connections lose their windows independently and at different moments, so the
 * aggregate stays near capacity. This is the single largest throughput win available
 * here, and it is worth more than every buffer-tuning change combined.
 *
 * The usual way to do this in tus is the `concatenation` extension: upload N independent
 * partial uploads, then ask the server to concatenate them. That would mean writing
 * 10 GB and then *reading and rewriting all of it* to stitch the parts together, which
 * on a phone costs more than the parallelism saves.
 *
 * So the streams here share one preallocated destination file and write to disjoint
 * offset windows of it. Concatenation becomes free because the bytes were never apart.
 * Each window is still an ordinary tus upload -- HEAD and PATCH behave exactly as the
 * spec says, and each resumes independently -- so the single-stream path stays plain
 * tus and any conformant client can still talk to it.
 */
class TusStore(
    ctx: Context,
    private val storage: Storage,
    private val index: FileIndex,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(ctx.applicationContext.filesDir, "tus").apply { mkdirs() }
    private val groupDir = File(dir, "groups").apply { mkdirs() }

    /** One in-flight PATCH per stream. Concurrent PATCHes to one window are undefined. */
    private val busy = ConcurrentHashMap.newKeySet<String>()

    /** Groups already claimed for finalisation, so N finishing streams finalise once. */
    private val finalizing = ConcurrentHashMap.newKeySet<String>()

    /** Live byte counts per stream, for aggregate progress without hitting the disk. */
    private val runtimes = ConcurrentHashMap<String, GroupRuntime>()

    private class GroupRuntime(val total: Long) {
        val positions = ConcurrentHashMap<String, Long>()
        @Volatile var lastPublishedAt = 0L
        fun sum(): Long = positions.values.sum()
    }

    // ---------------------------------------------------------------- creation

    /**
     * Creates an upload split across `streams` windows.
     *
     * Preallocation happens here, once, before any byte arrives -- see
     * [Storage.Slot.preallocate] for why that matters so much.
     */
    fun createGroup(total: Long, metadata: Map<String, String>, streams: Int): Pair<TusGroup, List<TusInfo>> {
        val groupId = newId()
        val slot = storage.newSlot(groupId)
        val preallocated = slot.preallocate(total)

        val n = streams.coerceIn(1, MAX_STREAMS)
        val windows = splitWindows(total, n)
        val infos = windows.map { (base, len) ->
            TusInfo(
                id = newId(),
                groupId = groupId,
                baseOffset = base,
                uploadLength = len,
                offset = 0,
                createdAt = System.currentTimeMillis(),
            )
        }
        val group = TusGroup(
            id = groupId,
            slotRef = slot.ref,
            total = total,
            metadata = metadata,
            streamIds = infos.map { it.id },
            createdAt = System.currentTimeMillis(),
            preallocated = preallocated,
        )
        persistGroup(group)
        infos.forEach(::persist)

        Log.i(TAG, "Created ${group.filename}: $total bytes over $n stream(s), preallocated=$preallocated")
        return group to infos
    }

    /**
     * Splits a length into `n` windows.
     *
     * Windows are aligned to 1 MB where the size allows. Unaligned boundaries would have
     * two streams writing into the same filesystem block at the seam, turning one write
     * into a read-modify-write on both sides of it.
     */
    private fun splitWindows(total: Long, n: Int): List<Pair<Long, Long>> {
        if (n <= 1 || total < n * MIN_WINDOW) return listOf(0L to total)
        val align = 1024L * 1024
        val rough = total / n
        val step = (rough / align).coerceAtLeast(1) * align
        val out = ArrayList<Pair<Long, Long>>(n)
        var base = 0L
        for (i in 0 until n) {
            val len = if (i == n - 1) total - base else step
            if (len <= 0) break
            out += base to len
            base += len
        }
        return out
    }

    fun get(id: String): TusInfo? = readJson(infoFile(id))
    fun group(id: String): TusGroup? = readJson(groupFile(id))

    private inline fun <reified T> readJson(f: File): T? = runCatching {
        if (!f.exists()) null else json.decodeFromString<T>(f.readText())
    }.getOrNull()

    // ---------------------------------------------------------------- teardown

    /** Cancels a whole upload. Callers may pass any stream id belonging to it. */
    fun terminate(idOrStreamId: String) {
        val group = group(idOrStreamId) ?: get(idOrStreamId)?.let { group(it.groupId) } ?: return
        // Cancelling only ever throws away an unfinished upload; a finished file stays.
        if (group.finalized) return
        group.streamIds.forEach { infoFile(it).delete() }
        storage.slotFromRef(group.slotRef)?.discard()
        groupFile(group.id).delete()
        runtimes.remove(group.id)
        Transfers.forget(group.id)
    }

    fun activeRefs(): Set<String> =
        groupDir.listFiles()?.mapNotNull { readJson<TusGroup>(it)?.slotRef }?.toSet().orEmpty()

    /** Drops uploads nobody came back for, and any orphaned staging files. */
    fun sweep(maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        groupDir.listFiles()?.forEach { f ->
            val group = readJson<TusGroup>(f)
            if (group == null || (group.createdAt < cutoff && !group.finalized)) {
                group?.let { g ->
                    g.streamIds.forEach { infoFile(it).delete() }
                    storage.slotFromRef(g.slotRef)?.discard()
                }
                f.delete()
            }
        }
        // Stream records whose group is gone.
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) return@forEach
            val info = readJson<TusInfo>(f)
            if (info == null || !groupFile(info.groupId).exists()) f.delete()
        }
        storage.sweepStaging(activeRefs())
    }

    // ---------------------------------------------------------------- the write path

    sealed interface AppendResult {
        data class Ok(val offset: Long, val entry: FileEntry?) : AppendResult
        data class Conflict(val offset: Long) : AppendResult
        data object Busy : AppendResult
    }

    /**
     * Streams `body` into one window at `offset` (window-relative).
     *
     * ### The durability rule
     *
     *     bytes are written  ->  fdatasync  ->  *then* the offset is persisted
     *
     * The offset always trails the data, never leads it. If the phone dies mid-transfer,
     * resume replays bytes that were already on disk, which is harmless because a write
     * at a fixed offset is idempotent. The opposite ordering would hand the client an
     * offset covering bytes the page cache had not yet flushed, and a crash would leave a
     * hole in the middle of the file that nothing -- not the client, not the server, not
     * the user -- would ever detect.
     *
     * ### Why the sync is on another coroutine
     *
     * The obvious implementation syncs inline every few megabytes. That is correct and
     * it is slow, for a reason that does not show up in a disk benchmark: while the write
     * loop is blocked in fdatasync it is not draining the socket. The receive buffer
     * fills, the window closes, the sender stalls, and throughput turns into a sawtooth
     * whose average is far below the link. On a phone, where fdatasync can take tens of
     * milliseconds under load, this alone can cost a third of the achievable rate.
     *
     * So the sync runs on its own coroutine. It snapshots how far the writer has got,
     * syncs, and only then publishes that snapshot as the committed offset. Publishing
     * the *pre-sync* snapshot is what keeps it honest: fdatasync flushes everything
     * issued before the call, so the snapshot is always a lower bound on what is durable.
     * The write loop never blocks on durability at all.
     */
    suspend fun append(streamId: String, offset: Long, body: ByteReadChannel): AppendResult = coroutineScope {
        val info = get(streamId) ?: return@coroutineScope AppendResult.Conflict(-1)
        if (info.completed) return@coroutineScope AppendResult.Conflict(info.uploadLength)
        if (offset != info.offset) return@coroutineScope AppendResult.Conflict(info.offset)
        if (!busy.add(streamId)) return@coroutineScope AppendResult.Busy

        val group = group(info.groupId)
        val slot = group?.let { storage.slotFromRef(it.slotRef) }
        if (group == null || slot == null) {
            busy.remove(streamId)
            return@coroutineScope AppendResult.Conflict(info.offset)
        }

        val runtime = runtime(group)
        // written: what the write loop has handed to the kernel.
        // committed: what an fdatasync has since made durable.
        val written = AtomicLong(offset)
        val committed = AtomicLong(offset)

        try {
            withContext(Dispatchers.IO) {
                slot.writer(info.baseOffset + offset).use { writer ->
                    val syncer = launch {
                        while (isActive) {
                            delay(SYNC_INTERVAL_MS)
                            val snapshot = written.get()
                            if (snapshot > committed.get()) {
                                writer.sync()
                                committed.set(snapshot)
                                persist(info.copy(offset = snapshot))
                            }
                        }
                    }
                    try {
                        val buf = ByteArray(UPLOAD_BUFFER)
                        var position = offset
                        var ended = false
                        while (!ended) {
                            val room = info.uploadLength - position
                            if (room <= 0) break
                            val want = minOf(buf.size.toLong(), room).toInt()
                            // Fill the whole buffer before writing. The network hands data
                            // over a few kilobytes at a time, and writing each piece as it
                            // came meant hundreds of thousands of small writes per file;
                            // one write per half-megabyte costs a fraction of that.
                            var n = 0
                            while (n < want) {
                                val got = body.readAvailable(buf, n, want - n)
                                if (got < 0) { ended = true; break }
                                n += got
                            }
                            if (n == 0) continue

                            writer.write(buf, 0, n)
                            Monitor.addIn(n)
                            position += n
                            written.set(position)
                            runtime.positions[streamId] = position
                            publishProgress(group, runtime)
                        }
                    } finally {
                        // cancelAndJoin, not cancel: the final sync and persist below must
                        // not run concurrently with the syncer's, or two writers race on
                        // the same temp file and the offset record can end up mangled.
                        // NonCancellable because this has to complete even when the whole
                        // call is being torn down.
                        withContext(NonCancellable) { syncer.cancelAndJoin() }
                    }

                    // Final commit for whatever the syncer did not reach.
                    writer.sync()
                    committed.set(written.get())
                    persist(info.copy(offset = committed.get()))
                }
            }
        } catch (t: Throwable) {
            // A dropped link lands here. `committed` is durable and persisted, so this
            // is a pause, not a loss.
            Log.i(TAG, "Stream $streamId interrupted at ${committed.get()} of ${info.uploadLength}: ${t.message}")
            busy.remove(streamId)
            runtime.positions[streamId] = committed.get()
            Transfers.progress(group.id, runtime.sum())
            // Only call the whole file stalled once nothing is still moving. With four
            // parallel windows, one dropping while its siblings keep going is routine,
            // and flagging it would make a healthy transfer look broken.
            val siblingsMoving = group.streamIds.any { it != streamId && busy.contains(it) }
            if (!siblingsMoving && runtime.sum() < group.total) Transfers.stall(group.id)
            throw t
        }

        busy.remove(streamId)
        val reached = committed.get()
        runtime.positions[streamId] = reached
        Transfers.progress(group.id, runtime.sum())

        if (reached < info.uploadLength) return@coroutineScope AppendResult.Ok(reached, null)

        persist(info.copy(offset = reached, completed = true))
        val entry = maybeFinalize(group, slot)
        AppendResult.Ok(reached, entry)
    }

    /**
     * Completes the file once every window is full.
     *
     * Whichever stream finishes last does the work, and [finalizing] makes sure that is
     * exactly one of them even when several land at the same instant.
     */
    private fun maybeFinalize(group: TusGroup, slot: Storage.Slot): FileEntry? {
        val all = group.streamIds.map { get(it) }
        if (all.any { it == null || !it.completed }) return null
        if (!finalizing.add(group.id)) return null

        return try {
            val entry = slot.finish(sanitizeFilename(group.filename), group.mime, Origin.PC, group.folder)
            persistGroup(group.copy(finalized = true))
            index.add(entry)
            Transfers.progress(group.id, group.total)
            Transfers.finish(group.id, ok = true)
            group.streamIds.forEach { infoFile(it).delete() }
            groupFile(group.id).delete()
            runtimes.remove(group.id)
            Log.i(TAG, "Completed ${entry.name} (${entry.size} bytes)")
            entry
        } catch (t: Throwable) {
            Log.e(TAG, "Finalising ${group.id} failed", t)
            Transfers.finish(group.id, ok = false)
            finalizing.remove(group.id)
            throw t
        }
    }

    /**
     * The per-group live counters, created on the first PATCH rather than at creation
     * time.
     *
     * This is also where the transfer becomes visible on the phone, and it has to be
     * here rather than in [createGroup]: after the app is killed and restarted, a resumed
     * upload never passes through creation again, so a row registered there would simply
     * not exist and the phone would show nothing while gigabytes moved. Seeding from the
     * persisted offsets means the row appears at the right percentage, not at zero.
     */
    private fun runtime(group: TusGroup): GroupRuntime =
        runtimes.getOrPut(group.id) {
            val rt = GroupRuntime(group.total)
            group.streamIds.forEach { id -> rt.positions[id] = get(id)?.offset ?: 0L }
            Transfers.begin(
                id = group.id,
                name = group.displayName,
                direction = Direction.INBOUND,
                total = group.total,
                alreadyDone = rt.sum(),
            )
            rt
        }

    private fun publishProgress(group: TusGroup, runtime: GroupRuntime) {
        val now = System.currentTimeMillis()
        if (now - runtime.lastPublishedAt < PROGRESS_EVERY_MS) return
        runtime.lastPublishedAt = now
        Transfers.progress(group.id, runtime.sum())
    }

    // ---------------------------------------------------------------- persistence

    private fun newId() = UUID.randomUUID().toString().replace("-", "")
    private fun infoFile(id: String) = File(dir, "$id.json")
    private fun groupFile(id: String) = File(groupDir, "$id.json")

    private fun persist(info: TusInfo) = writeAtomic(infoFile(info.id), json.encodeToString(info))
    private fun persistGroup(group: TusGroup) = writeAtomic(groupFile(group.id), json.encodeToString(group))

    /**
     * Write-then-rename, then fsync the directory entry.
     *
     * This file is the only record of how far the upload got. Skipping the sync would
     * make the whole durability argument above pointless -- durable bytes with a lost
     * offset are just as unresumable as lost bytes. It is a few hundred bytes every two
     * seconds, so it costs nothing worth measuring.
     */
    private fun writeAtomic(target: File, contents: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(contents.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(contents)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not persist ${target.name}: ${it.message}") }
    }

    private companion object {
        /**
         * How often durability catches up with the writer.
         *
         * Time-based rather than byte-based, which makes it self-scaling: at 100 MB/s it
         * commits ~200 MB at a time, at 1 MB/s it commits 2 MB, and in both cases an
         * interruption costs the same two seconds of retransmission. A byte-based
         * interval has to be tuned for one speed and is wrong at the other.
         */
        const val SYNC_INTERVAL_MS = 2_000L
        const val PROGRESS_EVERY_MS = 400L

        /**
         * Past about eight connections the aggregate stops improving and the interleaved
         * write pattern starts to cost more than the extra parallelism returns.
         */
        const val MAX_STREAMS = 8

        /** Below this, splitting costs more in round trips than it recovers. */
        const val MIN_WINDOW = 8L * 1024 * 1024
    }
}

/** Parses the tus `Upload-Metadata` header: `key b64value,key2 b64value2`. */
fun parseTusMetadata(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    return header.split(',').mapNotNull { pair ->
        val trimmed = pair.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val space = trimmed.indexOf(' ')
        if (space < 0) return@mapNotNull trimmed to ""
        val key = trimmed.substring(0, space)
        val value = runCatching {
            String(Base64.decode(trimmed.substring(space + 1), Base64.DEFAULT))
        }.getOrDefault("")
        key to value
    }.toMap()
}

/**
 * Characters SAF and the underlying filesystems reject in a display name, as code
 * points so this file stays free of escape sequences:
 * 92 backslash, 47 slash, 58 colon, 42 asterisk, 63 question mark,
 * 34 double quote, 60 less-than, 62 greater-than, 124 pipe.
 */
private val ILLEGAL_NAME_CODES = intArrayOf(92, 47, 58, 42, 63, 34, 60, 62, 124)

/**
 * Makes an arbitrary client-supplied name safe to hand to SAF.
 *
 * Spaces and hyphens are deliberately kept -- turning "Holiday 2026 - raw.mp4" into
 * underscores is a worse outcome than the problem it would be solving. Leading dots are
 * stripped so a hostile name cannot hide the file, and the result is length-capped
 * because several filesystems cap a single path component at 255 bytes.
 */
fun sanitizeFilename(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        val bad = ch.code < 0x20 || ch.code == 0x7f || ch.code in ILLEGAL_NAME_CODES
        sb.append(if (bad) '_' else ch)
    }
    val cleaned = sb.toString().trim().trim('.')
    return cleaned.ifEmpty { "file" }.take(200)
}
