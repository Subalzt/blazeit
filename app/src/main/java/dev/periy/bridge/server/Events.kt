package dev.periy.bridge.server

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** One server-sent event. `name` maps to the SSE `event:` field. */
data class BridgeEvent(val name: String, val data: String)

object EventBus {
    private val _events = MutableSharedFlow<BridgeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        // A browser tab that has stalled must never be able to block a file transfer.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BridgeEvent> = _events

    fun emit(name: String, data: String) {
        _events.tryEmit(BridgeEvent(name, data))
    }
}

/**
 * The clipboard slot the two sides read and write.
 *
 * This is the app's own buffer, not the Android system clipboard. On API 29+ an app
 * cannot read the system clipboard unless it has focus, so a background read is simply
 * not available -- Phase 2's quick-settings tile is what bridges that gap deliberately.
 * Writing to the system clipboard is unrestricted, so pushing text from the PC does put
 * it where you expect.
 */
class ClipboardStore(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("clipboard", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(sp.getString(KEY, "").orEmpty())

    /**
     * Live view of the shared slot, so the phone UI updates the instant the PC sends
     * something and vice versa. The browser gets the same change over SSE; this is the
     * on-device half of the same broadcast.
     */
    val flow: StateFlow<String> = _flow

    val text: String get() = _flow.value

    /** Returns false when the text was rejected for size. */
    fun set(value: String): Boolean {
        if (value.length > MAX_CHARS) return false
        if (value == _flow.value) return true
        _flow.value = value
        sp.edit { putString(KEY, value) }
        EventBus.emit("clipboard", value)
        return true
    }

    companion object {
        /**
         * 256k characters. Large enough for any realistic paste, small enough that it
         * cannot be used to push the app into an OOM through the text lane -- files have
         * their own path, and it streams.
         */
        const val MAX_CHARS = 256 * 1024
        private const val KEY = "text"
    }
}
