package dev.periy.bridge

import android.app.Application
import android.content.Context
import android.os.Build
import dev.periy.bridge.server.BridgeServer
import dev.periy.bridge.server.ClipboardStore
import dev.periy.bridge.server.DeviceRegistry
import dev.periy.bridge.server.EventBus
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.FileIndex
import dev.periy.bridge.server.PairingManager
import dev.periy.bridge.server.PeerManager
import dev.periy.bridge.server.ServerConfig
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.TusStore
import dev.periy.bridge.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand-rolled singleton graph. A DI framework would earn its keep at ten times this size;
 * at this size it would only add a build step and an annotation processor.
 */
class Container(ctx: Context) {
    private val app = ctx.applicationContext

    val prefs = Prefs(app)
    val storage = Storage(app, prefs)
    val index = FileIndex(app)
    val clipboard = ClipboardStore(app)
    val tus = TusStore(app, storage, index)
    val devices = DeviceRegistry(app)
    val pairing = PairingManager(app, devices)
    val music = MusicLibrary(app)
    val direct = dev.periy.bridge.net.DirectLink(app)
    val peers = PeerManager(app, ::deviceName, { prefs.uploadStreams }, direct) { prefs.phoneDirect }

    private val _theme = MutableStateFlow(prefs.theme)

    /** The shared appearance: "system", "light" or "dark". The app and every page follow it. */
    val theme: StateFlow<String> = _theme

    fun setTheme(value: String) {
        val v = value.takeIf { it in THEMES } ?: return
        prefs.theme = v
        _theme.value = v
        EventBus.emit("theme", v)
    }

    private val _look = MutableStateFlow(Look(prefs.glass, prefs.oled))

    /** Glass on the floating bars, and dark as pure black: shared like the theme. */
    val look: StateFlow<Look> = _look

    fun setLook(glass: Boolean? = null, oled: Boolean? = null) {
        val v = Look(glass ?: _look.value.glass, oled ?: _look.value.oled)
        prefs.glass = v.glass
        prefs.oled = v.oled
        _look.value = v
        EventBus.emit("look", v.json())
    }

    @Volatile
    var server: BridgeServer? = null
        private set

    fun deviceName(): String = listOfNotNull(
        Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
        Build.MODEL,
    ).distinct().joinToString(" ").ifBlank { "Android device" }

    /** Builds a server against the current port and key. Stops any previous one. */
    fun newServer(): BridgeServer {
        server?.stop()
        val config = ServerConfig(
            port = prefs.port,
            sessionKey = { prefs.sessionKey() },
            uploadStreams = { prefs.uploadStreams },
            theme = { _theme.value },
            setTheme = ::setTheme,
            look = { _look.value },
            setLook = { g, o -> setLook(g, o) },
            laptopLink = { prefs.laptopLink },
            hotspot = { prefs.hotspotSsid to prefs.hotspotPass },
            clipSync = { prefs.clipSync },
            videoPip = { prefs.videoPip },
            deviceName = deviceName(),
        )
        return BridgeServer(app, config, storage, tus, index, clipboard, devices, pairing, music, direct)
            .also { server = it }
    }

    fun stopServer() {
        server?.stop()
        server = null
    }
}

val THEMES = setOf("system", "light", "dark")

/** How the app and the pages look beyond light and dark. */
data class Look(val glass: Boolean = false, val oled: Boolean = false) {
    fun json() = """{"glass":$glass,"oled":$oled}"""
}

class BridgeApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

val Context.container: Container
    get() = (applicationContext as BridgeApp).container
