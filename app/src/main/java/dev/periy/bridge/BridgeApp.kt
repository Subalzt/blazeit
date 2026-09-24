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
    val tus = TusStore(app, storage, index) { prefs.tusMaxSize }
    val devices = DeviceRegistry(app)
    val pairing = PairingManager(app, devices)
    val music = MusicLibrary(app)
    val peers = PeerManager(app, ::deviceName) { prefs.uploadStreams }

    private val _oled = MutableStateFlow(prefs.oled)

    /** The shared appearance: OLED black, or the colourful backdrop. The app and every page follow it. */
    val oled: StateFlow<Boolean> = _oled

    fun setOled(on: Boolean) {
        prefs.oled = on
        _oled.value = on
        EventBus.emit("theme", if (on) "oled" else "aurora")
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
            oled = { _oled.value },
            setOled = ::setOled,
            deviceName = deviceName(),
        )
        return BridgeServer(app, config, storage, tus, index, clipboard, devices, pairing, music)
            .also { server = it }
    }

    fun stopServer() {
        server?.stop()
        server = null
    }
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
