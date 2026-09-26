package dev.periy.bridge.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.PowerManager
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/**
 * What a group of devices playing together agrees on. At server time [anchorMs] (this
 * phone's clock, in milliseconds) the song [trackId] is at [anchorPos] seconds; while
 * [playing], everyone is at anchorPos plus the time since. The anchor is usually a moment
 * ahead, so every member makes a change at the same instant rather than as it hears of it.
 * [nextId] is the song after, which members load ahead. [members] are the pages (by the id
 * each makes for itself) and "phone" for this phone's own speaker. [leader] is the page
 * whose queue it is.
 */
@Serializable
data class SyncState(
    val on: Boolean = false,
    val members: List<String> = emptyList(),
    val trackId: Long = -1,
    val nextId: Long = -1,
    val playing: Boolean = false,
    val anchorMs: Long = 0,
    val anchorPos: Double = 0.0,
    val leader: String = "",
    val seq: Long = 0,
)

/** An open page that can play along: its own id, and who and where it is. */
@Serializable
data class SyncPage(val id: String, val name: String, val ip: String)

@Serializable
data class SyncInfo(val state: SyncState, val pages: List<SyncPage>, val phone: String, val now: Long)

@Serializable data class SyncTime(val now: Long)
@Serializable data class SyncHello(val id: String = "")
@Serializable data class SyncCmd(val cmd: String = "", val from: String = "")

/**
 * Music played on several devices at once, in step: the laptop playing it, other laptops
 * with the page open, and this phone's speaker.
 *
 * Each device loads the whole song into memory and plays it by its own audio hardware's
 * clock; this phone keeps the time they all agree on. Pages measure how far their clock is
 * from it (GET /api/sync/time, as NTP does: several round trips, the quickest trusted). The
 * group shares one [SyncState], and this channel only carries its changes: play, pause,
 * where, which song.
 */
object SyncPlay {
    private val json = Json { encodeDefaults = true }
    private val pages = ConcurrentHashMap<String, Pair<SyncPage, Long>>()

    @Volatile var state = SyncState()
        private set

    /** The phone's own speaker, when it is one of the members. */
    var phone: PhoneSyncPlayer? = null

    /** A page saying it is open. Pages not heard from for a while are dropped. */
    fun hello(id: String, name: String, ip: String) {
        if (id.isBlank()) return
        val fresh = pages.put(id, SyncPage(id, name, ip) to System.currentTimeMillis()) == null
        if (sweep() || fresh) EventBus.emit("syncpages", pagesJson())
    }

    fun bye(id: String) {
        if (pages.remove(id) != null) {
            dropMember(id)
            EventBus.emit("syncpages", pagesJson())
        }
    }

    fun info(phoneName: String) = SyncInfo(state, livePages(), phoneName, System.currentTimeMillis())

    fun set(s: SyncState) {
        val members = s.members.distinct().filter { it == "phone" || pages.containsKey(it) }
        val next = s.copy(members = members, on = s.on && members.size >= 2, seq = state.seq + 1)
        state = next
        EventBus.emit("sync", json.encodeToString(next))
        phone?.apply(next)
    }

    /** A member other than the leader pressed next or previous: the leader, who has the queue, does it. */
    fun command(c: SyncCmd) {
        EventBus.emit("synccmd", json.encodeToString(c))
    }

    private fun livePages() = pages.values.map { it.first }.sortedBy { it.name }

    private fun pagesJson() = json.encodeToString(livePages())

    /** Drops pages gone quiet (closed without saying so). True when any went. */
    private fun sweep(): Boolean {
        val cutoff = System.currentTimeMillis() - PAGE_TIMEOUT_MS
        val gone = pages.filterValues { it.second < cutoff }.keys
        gone.forEach { pages.remove(it); dropMember(it) }
        return gone.isNotEmpty()
    }

    private fun dropMember(id: String) {
        if (id in state.members) set(state.copy(members = state.members - id))
    }

    private const val PAGE_TIMEOUT_MS = 16_000L
}

/**
 * This phone's speaker as a member.
 *
 * The song is decoded from where it already lives on the phone into memory (the song after
 * it too, ahead of time), and played by writing the samples to the audio output directly.
 * The output reports which sample it is sounding at which instant, so for every block
 * written, the player knows exactly when it will be heard, and takes the samples the group
 * says belong at that instant. While in step, a difference is taken up a sample or two at a
 * time, far too little to hear; nothing is skipped and nothing is sped up. Only a large
 * difference (the start, a seek) makes it jump, faded in.
 */
class PhoneSyncPlayer(private val ctx: Context, private val music: MusicLibrary) {

    /** A song decoded to 16-bit samples, interleaved. Filled while it decodes; readable as it fills. */
    private class Pcm(val id: Long) {
        @Volatile var rate = 44_100
        @Volatile var channels = 2
        @Volatile var data = ShortArray(0)
        /** Frames decoded so far. */
        @Volatile var frames = 0L
        @Volatile var done = false
        @Volatile var failed = false
        @Volatile var cancelled = false
    }

    @Volatile private var st = SyncState()
    /** Debug builds' test: everything runs, clock and all, but only silence is heard. */
    @Volatile var silent = false
    @Volatile private var current: Pcm? = null
    @Volatile private var upcoming: Pcm? = null
    @Volatile private var running = false
    /** Which writer is the live one: a writer from before a quick stop and start ends itself. */
    @Volatile private var generation = 0
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blazeit:syncplay").apply { setReferenceCounted(false) }

    fun apply(s: SyncState) {
        synchronized(this) {
            st = s
            val wanted = s.on && "phone" in s.members && s.trackId >= 0
            if (!wanted) { stop(); return }
            current = pcmFor(s.trackId)
            upcoming = if (s.nextId >= 0 && s.nextId != s.trackId) pcmFor(s.nextId) else upcoming
            // Anything else decoded is let go.
            if (!running) start()
        }
    }

    fun release() = synchronized(this) { stop() }

    /** The decoded song, from what is already in memory or decoded now. */
    private fun pcmFor(id: Long): Pcm {
        current?.takeIf { it.id == id && !it.failed }?.let { return it }
        upcoming?.takeIf { it.id == id && !it.failed }?.let { return it }
        listOfNotNull(current, upcoming).forEach { if (it.id != st.trackId && it.id != st.nextId) it.cancelled = true }
        return Pcm(id).also { decode(it) }
    }

    private fun decode(p: Pcm) = thread(name = "SyncDecode-${p.id}", isDaemon = true) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, music.uri(p.id), null)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            p.rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            p.channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 600_000_000L
            p.data = ShortArray(((durUs / 1_000_000.0 + 1) * p.rate * p.channels).toLong().coerceIn(1L shl 16, MAX_SAMPLES).toInt())
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var float = false
            var pos = 0
            while (!p.cancelled) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    p.rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    p.channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val ob = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val n = if (float) info.size / 4 else info.size / 2
                    if (pos + n > p.data.size) p.data = p.data.copyOf(min(MAX_SAMPLES, (p.data.size * 3L / 2 + n)).toInt())
                    val room = min(n, p.data.size - pos)
                    if (float) {
                        val fb = ob.asFloatBuffer()
                        for (k in 0 until room) p.data[pos + k] = (fb.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    } else ob.asShortBuffer().get(p.data, pos, room)
                    pos += room
                    p.frames = (pos / p.channels).toLong()
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || room < n) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decode ${p.id}", e)
            p.failed = true
        } finally {
            Log.i(TAG, "decoded ${p.id}: ${p.frames} frames at ${p.rate} Hz, ${p.channels} ch${if (p.failed) ", failed" else ""}")
            p.done = true
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun start() {
        running = true
        focus()
        wake.acquire(6 * 3600_000L)
        val g = ++generation
        thread(name = "SyncPlay", isDaemon = true, priority = Thread.MAX_PRIORITY) { playLoop(g) }
    }

    private fun stop() {
        running = false
        generation++
        current?.cancelled = true
        upcoming?.cancelled = true
        current = null
        upcoming = null
        focus?.let { audio.abandonAudioFocusRequest(it); focus = null }
        if (wake.isHeld) wake.release()
    }

    /**
     * Writes the song to the output, block by block. Each block goes where the group says
     * the song is at the instant that block will be heard.
     */
    private fun playLoop(g: Int) {
        var track: AudioTrack? = null
        var trackRate = 0
        var trackCh = 0
        var written = 0L
        var src = -1L
        var fadeIn = 0
        var wasPlaying = false
        val ts = AudioTimestamp()
        var out = ShortArray(BLOCK * 2)
        var blocks = 0L
        var worst = 0L
        var errAvg = 0.0
        try {
            while (running && generation == g) {
                val s = st
                val p = current?.takeIf { it.id == s.trackId }
                val rate = p?.rate ?: 44_100
                val outCh = min(p?.channels ?: 2, 2)
                if (track == null || rate != trackRate || outCh != trackCh) {
                    track?.release()
                    track = newTrack(rate, outCh)
                    Log.i(TAG, "output opened: $rate Hz, $outCh ch, state ${track.playState}")
                    trackRate = rate; trackCh = outCh
                    written = 0; src = -1
                }
                val out0 = track!!
                if (out.size < BLOCK * outCh) out = ShortArray(BLOCK * outCh)
                var frames = BLOCK
                val playing = s.playing && p != null && !p.failed
                var sounding = false
                if (playing) {
                    // When will the next sample written be heard? From the output's own report
                    // once it has one; before that, from how much is queued.
                    val presentNs = if (out0.getTimestamp(ts) && ts.framePosition > 0) {
                        ts.nanoTime + (written - ts.framePosition) * 1_000_000_000L / rate
                    } else {
                        System.nanoTime() + (written - out0.playbackHeadPosition.toLong()) * 1_000_000_000L / rate + LATENCY_GUESS_NS
                    }
                    val serverMs = System.currentTimeMillis() + (presentNs - System.nanoTime()) / 1_000_000.0
                    val want = ((s.anchorPos + (serverMs - s.anchorMs) / 1000.0) * rate).toLong()
                    when {
                        want < 0 -> { frames = min(BLOCK.toLong(), -want).toInt().coerceAtLeast(1); src = -1 }
                        else -> {
                            if (src >= 0) worst = maxOf(worst, abs(want - src))
                            if (src < 0 || abs(want - src) > rate / 20) { src = want; fadeIn = FADE; errAvg = 0.0 }
                            else {
                                // In step: the output's timestamp jitters by a millisecond or so, so the
                                // difference is averaged, and only one that holds above 2 ms is taken
                                // up, a sample at a time.
                                errAvg = errAvg * 0.97 + (want - src) * 0.03
                                if (errAvg > rate / 500.0) { src += SLIP; errAvg -= SLIP }
                                else if (errAvg < -rate / 500.0) { src -= SLIP; errAvg += SLIP }
                            }
                            if (p.done && src >= p.frames) src = p.frames
                            else if (src + BLOCK <= p.frames) {
                                copy(p, src, out, outCh, BLOCK, fadeIn)
                                fadeIn = (fadeIn - BLOCK).coerceAtLeast(0)
                                src += BLOCK
                                sounding = true
                            } else if (!p.done) src = -1 // decoding is behind: silence, and find the place again
                        }
                    }
                }
                if (!sounding) {
                    if (wasPlaying && p != null && src in 0 until p.frames - BLOCK) {
                        // Stopping: fade the next few milliseconds out rather than cut.
                        copy(p, src, out, outCh, BLOCK, 0, fadeOut = true)
                    } else java.util.Arrays.fill(out, 0, frames * outCh, 0)
                }
                wasPlaying = sounding
                if (silent) java.util.Arrays.fill(out, 0, frames * outCh, 0)
                if (++blocks % 800 == 0L && playing) {
                    val hasTs = out0.getTimestamp(ts)
                    Log.i(TAG, "playing=$playing sounding=$sounding src=$src; in step: worst ${worst * 1000.0 / rate} ms over the last ${800 * BLOCK * 1000L / rate} ms, " +
                        "output clock ${if (hasTs) "reported" else "estimated"}, decoded ${p?.frames}/${if (p?.done == true) "all" else "so far"}")
                    worst = 0
                }
                out0.write(out, 0, frames * outCh, AudioTrack.WRITE_BLOCKING)
                written += frames
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback stopped", e)
        } finally {
            runCatching { track?.stop() }
            track?.release()
        }
    }

    private fun copy(p: Pcm, from: Long, out: ShortArray, outCh: Int, frames: Int, fadeIn: Int, fadeOut: Boolean = false) {
        val data = p.data
        val ch = p.channels
        var at = (from * ch).toInt()
        for (f in 0 until frames) {
            val gain = when {
                fadeOut -> 1f - f.toFloat() / frames
                fadeIn > 0 -> ((FADE - fadeIn + f).toFloat() / FADE).coerceIn(0f, 1f)
                else -> 1f
            }
            for (c in 0 until outCh) {
                val v = data[at + min(c, ch - 1)]
                out[f * outCh + c] = if (gain >= 1f) v else (v * gain).toInt().toShort()
            }
            at += ch
        }
    }

    private fun newTrack(rate: Int, ch: Int): AudioTrack {
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
            // About 125 ms, in whole frames (the output refuses a size that splits one).
            .setBufferSizeInBytes(maxOf(min, rate / 8 * ch * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun focus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private companion object {
        const val TAG = "SyncPlay"
        /** Frames written at a time: about 6 ms at 44.1 kHz. */
        const val BLOCK = 256
        /** At most this many frames taken up per block while in step: about 4 ms a second, inaudible. */
        const val SLIP = 1L
        /** Frames a jump fades in over. */
        const val FADE = 1024
        const val LATENCY_GUESS_NS = 40_000_000L
        /** About 20 minutes of 48 kHz stereo; longer songs play what fits. */
        const val MAX_SAMPLES = 120_000_000L
    }
}
