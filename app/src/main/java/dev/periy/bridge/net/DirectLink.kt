package dev.periy.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseIntArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * A private, offline Wi-Fi network hosted by this phone, just for moving files.
 *
 * This is Android's local-only hotspot: the app starts it itself, with no trip to Settings,
 * and it shares no internet, so only the laptop or the other phone is on it. One hop, no
 * router taking turns, nobody else on the network.
 *
 * Measured between this Xiaomi 15 and a Wi-Fi 7 laptop: about 60 MB/s each way, against
 * 3 MB/s through the home router the two were sharing.
 *
 * What was tried and left out, so nobody tries it again blindly:
 *
 *  - **Pinning a channel.** The public API allows it, but a pinned channel comes up at
 *    40 MHz, half the width. Leaving the channel to the phone gives 80 MHz.
 *  - **160 MHz, 6 GHz, Wi-Fi 7.** Not reachable from an app. Asking for 160 MHz
 *    (SoftApConfiguration.setMaxChannelBandwidth, reached by reflection) is accepted and then
 *    discarded: the system logs mMaxChannelBandwidth = -1 and the phone picks 80 MHz. 6 GHz
 *    has no channels for this country (hotspot and Wi-Fi Direct alike), and Wi-Fi 7 hosting
 *    is off in the vendor's configuration. So 80 MHz Wi-Fi 6, about 120 MB/s at best, is the
 *    ceiling for any phone-hosted link here; a USB-C cable is the way past it.
 *  - **A second link the other way** (each side also joining the other's hotspot). Each
 *    radio puts its hotspot on the channel it is already connected on, so both links share
 *    one channel and take turns: measured 61 MB/s on one link, 65 MB/s on both.
 *
 * The phone's normal Wi-Fi stays connected alongside, and the hotspot follows its channel.
 */
class DirectLink(ctx: Context) {

    @Serializable
    data class Info(
        val ssid: String,
        val passphrase: String,
        /** The phone's own address on the network it hosts. */
        val host: String,
        val port: Int,
        /** WPA2 or WPA3; the laptop helper builds its profile from this. */
        val security: String,
    ) {
        /** The standard Wi-Fi QR payload; a phone camera joins from it. */
        val qr: String get() = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

        private fun escape(s: String) = buildString {
            for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) }
        }
    }

    sealed interface State {
        data object Off : State
        data object Starting : State
        data class On(val info: Info) : State
        data class Failed(val reason: String) : State
    }

    private val app = ctx.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var port = 8787

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    val info: Info? get() = (_state.value as? State.On)?.info

    /**
     * Whether a laptop should join. False when the link was started only for a phone to
     * phone send, so a laptop helper nearby does not hop onto a network meant for a phone.
     */
    @Volatile
    var forLaptop: Boolean = true
        private set

    /** Every change goes out to open pages, which then ask for the details. */
    private fun set(next: State) {
        if (next == _state.value) return
        _state.value = next
        dev.periy.bridge.server.EventBus.emit("direct", when (next) {
            is State.On -> "on"; State.Starting -> "starting"; State.Off -> "off"; is State.Failed -> "failed"
        })
    }

    /**
     * Starts the network. Needs NEARBY_WIFI_DEVICES (Android 13+) or fine location (older);
     * the screen asks for it first. Safe to call from any thread and when already on.
     */
    fun start(port: Int, laptop: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(port, laptop) }; return }
        this.port = port
        if (_state.value is State.On || _state.value is State.Starting) {
            // Already up for a phone; now a laptop wants it too.
            if (laptop && !forLaptop) { forLaptop = true; dev.periy.bridge.server.EventBus.emit("direct", "on") }
            return
        }
        forLaptop = laptop
        val wm = wifi ?: run { set(State.Failed("This phone has no Wi-Fi")); return }
        set(State.Starting)
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                publish(r)
                // The interface gets its address a moment after the callback.
                for (ms in longArrayOf(700, 1500, 3000, 6000)) main.postDelayed({ reservation?.let(::publish) }, ms)
            }

            override fun onStopped() {
                reservation = null
                NetInfo.directHost = null
                set(State.Off)
            }

            override fun onFailed(reason: Int) {
                reservation = null
                set(State.Failed(
                    when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "Hotspots are blocked on this phone by its settings or admin"
                        ERROR_INCOMPATIBLE_MODE -> "The phone's own hotspot is on. Turn it off and try again"
                        ERROR_NO_CHANNEL -> "No free channel right now. Try again in a moment"
                        else -> "The phone could not start the link. Is Wi-Fi turned on?"
                    }
                ))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                // 5 GHz, channel left to the phone (0): that is what gets the full 80 MHz.
                val channels = SparseIntArray().apply { put(SoftApConfiguration.BAND_5GHZ, 0) }
                val config = SoftApConfiguration.Builder().setChannels(channels).build()
                runCatching { wm.startLocalOnlyHotspotWithConfiguration(config, app.mainExecutor, callback) }
                    .onFailure {
                        Log.w(TAG, "5 GHz request refused; using the default band", it)
                        @Suppress("DEPRECATION")
                        wm.startLocalOnlyHotspot(callback, main)
                    }
            } else {
                @Suppress("DEPRECATION")
                wm.startLocalOnlyHotspot(callback, main)
            }
        } catch (e: SecurityException) {
            set(State.Failed("Allow BlazeIt to find nearby devices, then try again"))
        } catch (t: Throwable) {
            Log.w(TAG, "Local-only hotspot failed to start", t)
            set(State.Failed(t.message ?: "The phone could not start the link"))
        }
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stop() }; return }
        runCatching { reservation?.close() }
        reservation = null
        NetInfo.directHost = null
        set(State.Off)
    }

    private fun publish(r: WifiManager.LocalOnlyHotspotReservation) {
        val (ssid, pass, security) = if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            Triple(
                c.wifiSsid?.toString()?.removeSurrounding("\"") ?: c.ssid.orEmpty(),
                c.passphrase.orEmpty(),
                if (c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) "WPA3" else "WPA2",
            )
        } else {
            @Suppress("DEPRECATION")
            val c = r.wifiConfiguration
            @Suppress("DEPRECATION")
            Triple(c?.SSID.orEmpty().removeSurrounding("\""), c?.preSharedKey.orEmpty().removeSurrounding("\""), "WPA2")
        }
        val host = hostAddress() ?: info?.host.orEmpty()
        NetInfo.directHost = host.ifEmpty { null }
        val next = State.On(Info(ssid, pass, host, port, security))
        if (next != _state.value) {
            set(next)
            Log.i(TAG, "Direct link up: $ssid at $host")
        }
    }

    /** The address on the interface the hotspot runs on. */
    private fun hostAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && NetInfo.isHotspotInterface(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------------ joining another phone's

    /**
     * Joins another phone's direct link, for phone to phone. Android shows its own
     * "connect to this device?" prompt; the network stays private to this app and is let
     * go with [Joined.close]. Null if it could not connect within [timeoutMs].
     */
    suspend fun join(target: Info, timeoutMs: Long = 45_000): Joined? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val spec = WifiNetworkSpecifier.Builder().setSsid(target.ssid).apply {
            if (target.security == "WPA3") setWpa3Passphrase(target.passphrase) else setWpa2Passphrase(target.passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        val network = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network?> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                    override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
                }
                callback = cb
                runCatching { cm.requestNetwork(request, cb) }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        if (network == null) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            return null
        }
        return Joined(network) { callback?.let { runCatching { cm.unregisterNetworkCallback(it) } } }
    }

    /** Another phone's network, held for as long as this is open. */
    class Joined(val network: Network, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    private companion object { const val TAG = "DirectLink" }
}
