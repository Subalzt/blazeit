package dev.periy.bridge.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import dev.periy.bridge.BuildConfig
import java.security.SecureRandom

/**
 * Small typed wrapper over SharedPreferences. Not encrypted: the values here (the
 * session HMAC key) are only as valuable as access to the device itself, and pulling in
 * Jetpack Security for a personal LAN tool buys very little. If this ever handles other
 * people's devices, that changes.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = sp.getString(K_TREE, null)?.let(Uri::parse)
        set(v) = sp.edit { if (v == null) remove(K_TREE) else putString(K_TREE, v.toString()) }

    var port: Int
        get() = sp.getInt(K_PORT, BuildConfig.DEFAULT_PORT)
        set(v) = sp.edit { putInt(K_PORT, v) }

    /** Advertised Tus-Max-Size. Configurable, as asked. */
    var tusMaxSize: Long
        get() = sp.getLong(K_MAX, BuildConfig.DEFAULT_TUS_MAX_SIZE)
        set(v) = sp.edit { putLong(K_MAX, v) }

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(K_AUTOSTART, false)
        set(v) = sp.edit { putBoolean(K_AUTOSTART, v) }

    /** Forces the staged-copy storage path even when the direct-seek probe passes. */
    var forceStagedCopy: Boolean
        get() = sp.getBoolean(K_FORCE_STAGE, false)
        set(v) = sp.edit { putBoolean(K_FORCE_STAGE, v) }

    /**
     * Parallel connections per transfer.
     *
     * Four is the default because it captures most of the available gain on Wi-Fi while
     * keeping the interleaved write pattern tame. Two is enough on a wired USB link,
     * where a single stream already runs close to capacity; eight helps on a busy or
     * distant Wi-Fi link where any one connection spends most of its time in recovery.
     */
    /** OLED black instead of the colourful backdrop; shared by the app and every connected page. */
    var oled: Boolean
        get() = sp.getBoolean(K_OLED, false)
        set(v) = sp.edit { putBoolean(K_OLED, v) }

    /** Whether the live monitor floats over the app's screens. */
    var showMonitor: Boolean
        get() = sp.getBoolean(K_MONITOR, false)
        set(v) = sp.edit { putBoolean(K_MONITOR, v) }

    var uploadStreams: Int
        get() = sp.getInt(K_STREAMS, 4).coerceIn(1, 8)
        set(v) = sp.edit { putInt(K_STREAMS, v.coerceIn(1, 8)) }

    /**
     * HMAC key for session cookies. Rotating it invalidates every browser that has
     * already paired, which is exactly what "unpair everything" should mean.
     */
    fun sessionKey(): ByteArray {
        sp.getString(K_KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        return rotateSessionKey()
    }

    fun rotateSessionKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit { putString(K_KEY, Base64.encodeToString(key, Base64.NO_WRAP)) }
        return key
    }

    private companion object {
        const val K_TREE = "tree_uri"
        const val K_PORT = "port"
        const val K_MAX = "tus_max_size"
        const val K_KEY = "session_key"
        const val K_AUTOSTART = "autostart"
        const val K_FORCE_STAGE = "force_staged_copy"
        const val K_STREAMS = "upload_streams"
        const val K_OLED = "oled"
        const val K_MONITOR = "show_monitor"
    }
}
