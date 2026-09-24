package dev.periy.bridge.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * A Wi-Fi Direct group with this phone as its owner: the other way to host a private
 * network. Unlike the local-only hotspot, the app picks the band (6 GHz included on
 * Android 16), the name and the password, and it runs through the Wi-Fi Direct part of the
 * driver rather than the hotspot part. A laptop joins it as an ordinary Wi-Fi network.
 */
class P2pLink(ctx: Context) {

    @Serializable
    data class Info(
        val ssid: String,
        val passphrase: String,
        val host: String,
        val frequencyMhz: Int,
        val iface: String,
    )

    private val app = ctx.applicationContext
    private val mgr = app.getSystemService(WifiP2pManager::class.java)
    private val channel by lazy { mgr?.initialize(app, Looper.getMainLooper(), null) }
    private val rng = SecureRandom()

    /** [band] is one of WifiP2pConfig.GROUP_OWNER_BAND_*. Returns the group, or an error text. */
    @SuppressLint("MissingPermission")
    suspend fun start(band: Int, freqMhz: Int = 0): Pair<Info?, String?> {
        val m = mgr ?: return null to "No Wi-Fi Direct on this phone"
        val ch = channel ?: return null to "Wi-Fi Direct would not start"
        stop()
        val pass = (1..16).map { "abcdefghjkmnpqrstuvwxyz23456789"[rng.nextInt(31)] }.joinToString("")
        val config = WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-bz-BlazeIt")
            .setPassphrase(pass)
            .apply { if (freqMhz > 0) setGroupOperatingFrequency(freqMhz) else setGroupOperatingBand(band) }
            .enablePersistentMode(false)
            .build()
        val err = suspendCancellableCoroutine<String?> { cont ->
            runCatching {
                m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(null) }
                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(
                            when (reason) {
                                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                                WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
                                else -> "Wi-Fi Direct refused the group (reason $reason)"
                            }
                        )
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(it.message ?: "createGroup failed") }
        }
        if (err != null) return null to err
        repeat(40) {
            val g = group()
            if (g != null && g.frequency > 0) {
                val host = address(g.`interface`)
                if (host != null) {
                    val info = Info(g.networkName, g.passphrase ?: pass, host, g.frequency, g.`interface` ?: "")
                    Log.i(TAG, "Wi-Fi Direct group up: $info")
                    return info to null
                }
            }
            delay(250)
        }
        return null to "The group did not come up"
    }

    @SuppressLint("MissingPermission")
    private suspend fun group(): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        val m = mgr; val ch = channel
        if (m == null || ch == null) { cont.resume(null); return@suspendCancellableCoroutine }
        runCatching { m.requestGroupInfo(ch) { g -> if (cont.isActive) cont.resume(g) } }
            .onFailure { if (cont.isActive) cont.resume(null) }
    }

    suspend fun stop() {
        val m = mgr ?: return
        val ch = channel ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            runCatching {
                m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
                })
            }.onFailure { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun address(iface: String?): String? = runCatching {
        NetworkInterface.getByName(iface ?: return null)?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    }.getOrNull()

    private companion object { const val TAG = "P2pLink" }
}
