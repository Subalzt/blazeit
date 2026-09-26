package dev.periy.bridge.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Covers for albums that have none of their own, found in Apple's public iTunes catalogue.
 *
 * Only the album's name and artist leave the phone, as a search, and only for albums with
 * no art in the files or the folder. Searches go one at a time, a few seconds apart,
 * because the catalogue turns away anyone asking faster than about twenty a minute. A cover
 * found is kept in the app's own storage, so each album is looked up once; an album the
 * catalogue does not have is not asked about again for a week. A network failure marks
 * nothing, so the album is tried again next time it is shown.
 */
class CoverFinder(
    private val dir: File,
    private val enabled: () -> Boolean,
    private val onFound: (Long) -> Unit,
) {
    private data class Ask(val albumId: Long, val artist: String, val album: String)

    private val queue = LinkedBlockingQueue<Ask>()
    private val asked = ConcurrentHashMap.newKeySet<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    private fun found(albumId: Long) = File(dir, "$albumId.jpg")
    private fun missing(albumId: Long) = File(dir, "$albumId.none")

    /** A cover found earlier, or null. */
    fun cached(albumId: Long): ByteArray? = found(albumId).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Looks the album up in the background, unless it is switched off, unknown, or done already. */
    fun request(albumId: Long, artist: String, album: String) {
        if (!enabled() || album.isBlank() || album == "Unknown album") return
        val none = missing(albumId)
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return
        if (!asked.add(albumId)) return
        queue.add(Ask(albumId, artist, album))
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (running) return
        running = true
        scope.launch {
            try {
                while (true) {
                    val ask = queue.poll() ?: break
                    if (!enabled()) { asked.remove(ask.albumId); continue }
                    // Unreachable: nothing is recorded, and the album may be asked for again.
                    val bytes = try { lookup(ask) } catch (e: Exception) { asked.remove(ask.albumId); null }
                    when {
                        bytes == null -> {}
                        bytes.isEmpty() -> runCatching { dir.mkdirs(); missing(ask.albumId).writeText("") }
                        else -> {
                            runCatching { dir.mkdirs(); found(ask.albumId).writeBytes(bytes) }
                            onFound(ask.albumId)
                        }
                    }
                    delay(SPACING_MS)
                }
            } finally {
                running = false
                if (queue.isNotEmpty()) startWorker()
            }
        }
    }

    /**
     * The cover as a JPEG, [EMPTY] when the catalogue has no such album, or an exception when
     * the catalogue could not be reached (so nothing is recorded).
     */
    private fun lookup(ask: Ask): ByteArray {
        val artist = ask.artist.takeUnless { it == "Unknown artist" }.orEmpty()
        val term = URLEncoder.encode("$artist ${plain(ask.album)}".trim(), "UTF-8")
        val body = get("https://itunes.apple.com/search?media=music&entity=album&limit=10&term=$term").decodeToString()
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return EMPTY
        val want = key(ask.album)
        val wantArtist = key(artist)
        // The album's own name has to match; among those, one by the same artist first.
        val best = results.map { it.jsonObject }.filter { r ->
            val name = key(r["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty())
            name.isNotEmpty() && (name == want || name.startsWith(want) || want.startsWith(name))
        }.maxByOrNull { r ->
            val by = key(r["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val sameArtist = wantArtist.isNotEmpty() && (by.contains(wantArtist) || wantArtist.contains(by))
            val exact = key(r["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty()) == want
            (if (sameArtist) 2 else 0) + (if (exact) 1 else 0)
        } ?: return EMPTY
        val small = best["artworkUrl100"]?.jsonPrimitive?.contentOrNull ?: return EMPTY
        val image = get(small.replace("100x100bb", "${COVER_PX}x${COVER_PX}bb"))
        val bmp = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return EMPTY
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Localhost8787/1")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** "Name (Deluxe Edition) [Remastered]" -> "Name": what the catalogue is searched for. */
    private fun plain(s: String) = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim()

    /** Letters and digits only, lower case, without the brackets: for comparing names. */
    private fun key(s: String) = plain(s).lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        val EMPTY = ByteArray(0)
        const val SPACING_MS = 3_200L
        const val RETRY_MS = 7L * 24 * 3600 * 1000
        const val COVER_PX = 600
    }
}
