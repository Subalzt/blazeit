package dev.periy.bridge.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The Android system clipboard.
 *
 * The two directions are not symmetric, and that asymmetry is the whole reason the
 * clipboard feature is shaped the way it is.
 *
 * **Writing is unrestricted.** Text pushed from the PC really does land in the system
 * clipboard, ready to paste into any app.
 *
 * **Reading requires focus.** Since Android 10, `getPrimaryClip` returns null unless the
 * calling app is the foreground input method or currently has window focus. There is no
 * permission that lifts this and no background exemption short of an accessibility
 * service, which this project has ruled out. So [read] only works when called from a
 * visible Activity -- which is exactly why the UI exposes it as a button the user taps
 * rather than something that silently syncs. Tapping it *is* the focus.
 */
object SystemClipboard {

    fun write(ctx: Context, text: String) {
        ClipWatch.ownWrite()
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("BlazeIt", text))
        }
    }

    /**
     * Puts a picture or file on the phone's clipboard, as a content link other apps can paste
     * from (Gallery, WhatsApp, a notes app...). The file stays in BlazeIt's clipboard folder,
     * shared read-only through its FileProvider.
     */
    fun writeFile(ctx: Context, file: java.io.File, name: String, mime: String) {
        ClipWatch.ownWrite()
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".clip", file)
            val clip = ClipData(name, arrayOf(mime), ClipData.Item(uri))
            cm.setPrimaryClip(clip)
        }
    }

    /** Empties the phone's clipboard, when the shared one is cleared from anywhere. */
    fun clear(ctx: Context) {
        ClipWatch.ownWrite()
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** A picture or file on the phone's clipboard: where to read it, what it is, and its name. */
    data class Copied(val uri: android.net.Uri, val mime: String, val name: String)

    /**
     * The picture or file on the system clipboard, if it holds one rather than text (a photo
     * copied in Gallery, say). Needs focus, like [read].
     */
    fun readFile(ctx: Context): Copied? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val mime = ctx.contentResolver.getType(uri) ?: clip.description.getMimeType(0) ?: "application/octet-stream"
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Picture"
        Copied(uri, mime, name)
    }.getOrNull()

    /**
     * Reads the system clipboard. Returns null when it is empty, holds no text, or when
     * the caller does not have focus -- the three cases are indistinguishable from here,
     * so the UI reports them as one "nothing to paste".
     */
    fun read(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        // A picture or file is read by [readFile]; its link is not text to send.
        if (item.text == null && item.uri != null) return null
        item.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
