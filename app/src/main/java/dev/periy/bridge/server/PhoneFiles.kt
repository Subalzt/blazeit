package dev.periy.bridge.server

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.util.Size
import android.webkit.MimeTypeMap
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One row of a folder listing. */
@Serializable
data class FsEntry(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String = "",
    /** Items inside, for a folder; -1 when it could not be read. */
    val items: Int = -1,
)

@Serializable
data class FsListDto(
    /** Whether the phone's owner has allowed browsing (Android's "All files access"). */
    val granted: Boolean,
    val path: String = "",
    val entries: List<FsEntry> = emptyList(),
    val message: String? = null,
)

/**
 * The phone's shared storage, read-only, for the laptop to browse: the folder with DCIM,
 * Download, Documents and the rest.
 *
 * Nothing is reachable until the owner turns on Android's "All files access" for BlazeIt,
 * and every path is resolved against the storage root and refused if it would climb out.
 * Nothing here writes, renames or deletes.
 */
class PhoneFiles {

    val root: File = Environment.getExternalStorageDirectory()
    private val rootPath: String = root.canonicalPath

    fun granted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** The file for a path relative to the storage root, or null if it is outside it. */
    fun resolve(rel: String?): File? {
        val clean = rel.orEmpty().replace('\\', '/').trim('/')
        val f = runCatching { File(root, clean).canonicalFile }.getOrNull() ?: return null
        return if (f.path == rootPath || f.path.startsWith("$rootPath/")) f else null
    }

    fun relative(f: File): String = f.canonicalPath.removePrefix(rootPath).trimStart('/')

    fun list(rel: String?): FsListDto {
        if (!granted()) return FsListDto(false, message = "Allow browsing on the phone: Settings, Laptop access")
        val dir = resolve(rel) ?: return FsListDto(true, message = "That folder is outside the phone's storage")
        if (!dir.isDirectory) return FsListDto(true, relative(dir), message = "Not a folder")
        val kids = dir.listFiles() ?: return FsListDto(true, relative(dir), message = "Android does not let apps read this folder")
        val entries = kids
            .filter { !it.name.startsWith(".") }
            .map { f ->
                if (f.isDirectory) FsEntry(f.name, true, 0, f.lastModified(), items = f.list()?.count { !it.startsWith(".") } ?: -1)
                else FsEntry(f.name, false, f.length(), f.lastModified(), mimeOf(f.name))
            }
            .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        return FsListDto(true, relative(dir), entries)
    }

    /** A small JPEG for a photo or video, or null for anything else. */
    fun thumbnail(f: File, px: Int): ByteArray? {
        val mime = mimeOf(f.name)
        val bmp: Bitmap = runCatching {
            when {
                mime.startsWith("image/") -> ThumbnailUtils.createImageThumbnail(f, Size(px, px), null)
                mime.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
                else -> null
            }
        }.getOrNull() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /**
     * Writes [dir] as a zip, stored rather than compressed: photos and videos are already
     * compressed, and packing them again would only cost the phone's CPU and slow the link.
     */
    fun zip(dir: File, out: OutputStream) {
        val base = dir.parentFile ?: dir
        ZipOutputStream(out.buffered(DOWNLOAD_BUFFER)).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            val buf = ByteArray(DOWNLOAD_BUFFER)
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                val name = f.canonicalPath.removePrefix(base.canonicalPath).trimStart('/')
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        z.write(buf, 0, n)
                        Monitor.addOut(n)
                    }
                }
                z.closeEntry()
            }
        }
    }

    companion object {
        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}

/**
 * A file on the phone as a range-capable response, so the page can fetch it over several
 * connections at once and resume where it stopped.
 */
class FileRangeContent(private val file: File, private val type: ContentType) : OutgoingContent.ReadChannelContent() {
    private val length = file.length()
    override val contentType: ContentType get() = type
    override val contentLength: Long get() = length

    override fun readFrom(): ByteReadChannel = readFrom(0L until length)

    override fun readFrom(range: LongRange): ByteReadChannel {
        val first = range.first.coerceAtLeast(0)
        val count = (range.last.coerceAtMost(length - 1) - first + 1).coerceAtLeast(0)
        return CoroutineScope(Dispatchers.IO).writer(autoFlush = false) {
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(first)
                    val buf = ByteArray(DOWNLOAD_BUFFER)
                    var sent = 0L
                    while (sent < count) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), count - sent).toInt())
                        if (n <= 0) break
                        channel.writeFully(buf, 0, n)
                        Monitor.addOut(n)
                        sent += n
                    }
                }
            }
        }.channel
    }
}
