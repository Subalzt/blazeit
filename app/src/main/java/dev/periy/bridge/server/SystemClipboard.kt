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
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Xoosh", text))
        }
    }

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
        clip.getItemAt(0).coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
