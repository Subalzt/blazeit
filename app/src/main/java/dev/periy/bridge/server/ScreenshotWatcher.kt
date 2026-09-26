package dev.periy.bridge.server

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log

/**
 * Puts every new screenshot on the shared clipboard, so it is on the laptop's a moment after
 * you take it, Localhost 8787 open or not.
 *
 * Android lets an app read the clipboard only while it is on screen, so a screenshot copied in
 * the background cannot be picked up that way. It does not have to be: a keyboard's "recent
 * screenshot" suggestion comes from watching the photo library for new screenshots, and this
 * does the same, from the server's foreground service. Reading the library needs "All files
 * access" (already asked for by browsing) or the photos permission.
 */
class ScreenshotWatcher(
    private val ctx: Context,
    private val clipboard: ClipboardStore,
    private val enabled: () -> Boolean,
    /** Called with the new item, to put it on the phone's own clipboard too. */
    private val onNew: () -> Unit,
) {
    private val thread = HandlerThread("screenshots").apply { start() }
    private val handler = Handler(thread.looper)
    /** The newest screenshot already handled, so an edit or a rescan never sends it twice. */
    private var lastId = -1L
    private var tries = 0

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // A screenshot is written in steps and announced several times: act once it settles.
            handler.removeCallbacks(grab)
            tries = 0
            handler.postDelayed(grab, SETTLE_MS)
        }
    }

    private val grab = Runnable { runCatching { check() }.onFailure { Log.w(TAG, "screenshot", it) } }

    fun start() {
        handler.post { lastId = newest()?.id ?: -1L }
        runCatching {
            ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
    }

    fun stop() {
        runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
        thread.quitSafely()
    }

    private class Shot(val id: Long, val name: String, val mime: String, val size: Long, val pending: Boolean, val addedSec: Long)

    /** The most recent screenshot in the library, whatever folder the phone keeps them in. */
    private fun newest(): Shot? {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.DATE_ADDED,
        )
        val where = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf("%Screenshot%", "Screenshot%")
        return ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where, args, "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) null
            else Shot(c.getLong(0), c.getString(1).orEmpty(), c.getString(2) ?: "image/png", c.getLong(3), c.getInt(4) == 1, c.getLong(5))
        }
    }

    private fun check() {
        if (!enabled()) return
        val s = newest() ?: return
        if (s.id <= lastId) return
        // Still being written: look again shortly, for a few seconds at most.
        if (s.pending || s.size <= 0) {
            if (++tries < 12) handler.postDelayed(grab, SETTLE_MS)
            return
        }
        lastId = s.id
        // Only one taken just now; a screenshot copied in from elsewhere is not "new".
        if (System.currentTimeMillis() / 1000 - s.addedSec > FRESH_SEC) return
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, s.id)
        val meta = ctx.contentResolver.openInputStream(uri)?.use { clipboard.setBlob(s.name, s.mime, it) }
        if (meta != null) onNew()
    }

    private companion object {
        const val TAG = "ScreenshotWatcher"
        const val SETTLE_MS = 600L
        const val FRESH_SEC = 60L
    }
}

/** Whether Localhost 8787 may read the photo library: "All files access", or the photos permission. */
fun canReadPhotos(ctx: Context): Boolean =
    (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) ||
        ctx.checkSelfPermission(
            if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
