package dev.periy.bridge.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One second of traffic. */
@Serializable
data class MonitorSample(val t: Long, val inBps: Long, val outBps: Long)

/** The phone's own Wi-Fi connection, when it is joined to a network. */
@Serializable
data class PhoneLink(
    val linkMbps: Int,
    val txMbps: Int,
    val rxMbps: Int,
    val rssi: Int,
    val frequencyMhz: Int,
    val standard: String,
)

/** What the laptop helper reports about its side of the link. */
@Serializable
data class LaptopLink(
    val ssid: String = "",
    val signalPercent: Int = 0,
    val rxMbps: Int = 0,
    val txMbps: Int = 0,
    val channel: String = "",
    val band: String = "",
    val radio: String = "",
    val rttMs: Int = -1,
    val at: Long = 0,
)

@Serializable
data class MonitorSnapshot(
    val samples: List<MonitorSample> = emptyList(),
    val inBps: Long = 0,
    val outBps: Long = 0,
    val peakInBps: Long = 0,
    val peakOutBps: Long = 0,
    val totalIn: Long = 0,
    val totalOut: Long = 0,
    /** Seconds where a transfer was running but no bytes moved. */
    val gaps: Int = 0,
    val lastGapAt: Long = 0,
    /** HTTP requests being served right now. */
    val requests: Int = 0,
    val activeTransfers: Int = 0,
    val uptimeSec: Long = 0,
    val phone: PhoneLink? = null,
    val laptop: LaptopLink? = null,
    /** Round trip to a computer, from whichever measured it last (helper or browser); -1 if none lately. */
    val rttMs: Int = -1,
)

/**
 * What is going on, second by second.
 *
 * Every byte the server receives or sends is counted here; once a second the counts turn
 * into a sample, and a minute of samples is kept for the graphs on the phone and in the
 * browser. A "gap" is a second in which a transfer was running but nothing moved, which is
 * exactly what a stall looks like from the outside.
 */
object Monitor {
    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private val inflight = AtomicInteger()

    private val _snapshot = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    @Volatile private var laptop: LaptopLink? = null
    private var job: Job? = null
    private var startedAt = 0L

    fun addIn(n: Int) { if (n > 0) bytesIn.addAndGet(n.toLong()) }
    fun addOut(n: Int) { if (n > 0) bytesOut.addAndGet(n.toLong()) }
    fun requestStarted() { inflight.incrementAndGet() }
    fun requestEnded() { inflight.decrementAndGet() }

    fun reportLaptop(link: LaptopLink) {
        laptop = link.copy(at = System.currentTimeMillis())
        if (link.rttMs >= 0) reportRtt(link.rttMs)
    }

    @Volatile private var rtt = -1
    @Volatile private var rttAt = 0L

    /** A browser or the helper measured the round trip to this phone. */
    fun reportRtt(ms: Int) { rtt = ms; rttAt = System.currentTimeMillis() }

    fun start(ctx: Context) {
        if (job != null) return
        startedAt = System.currentTimeMillis()
        val app = ctx.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var lastIn = bytesIn.get()
            var lastOut = bytesOut.get()
            var lastAt = System.currentTimeMillis()
            val samples = ArrayDeque<MonitorSample>()
            var gaps = 0
            var lastGapAt = 0L
            var peakIn = 0L
            var peakOut = 0L
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val i = bytesIn.get(); val o = bytesOut.get()
                // Per second of real time: the delay above can run long, and dividing by the
                // nominal second would count a late tick's extra bytes as extra speed.
                val dt = (now - lastAt).coerceAtLeast(1)
                val din = (i - lastIn) * 1000 / dt; val dout = (o - lastOut) * 1000 / dt
                lastIn = i; lastOut = o; lastAt = now
                samples.addLast(MonitorSample(now, din, dout))
                while (samples.size > WINDOW) samples.removeFirst()
                peakIn = maxOf(peakIn, din); peakOut = maxOf(peakOut, dout)

                val active = Transfers.flow.value.count { it.state == TransferState.ACTIVE }
                if (active > 0 && din == 0L && dout == 0L) { gaps++; lastGapAt = now }

                // A laptop report older than ten seconds means the helper has gone quiet.
                val lap = laptop?.takeIf { now - it.at < 10_000 }
                _snapshot.value = MonitorSnapshot(
                    samples = samples.toList(),
                    inBps = din, outBps = dout,
                    peakInBps = peakIn, peakOutBps = peakOut,
                    totalIn = i, totalOut = o,
                    gaps = gaps, lastGapAt = lastGapAt,
                    requests = inflight.get().coerceAtLeast(0),
                    activeTransfers = active,
                    uptimeSec = (now - startedAt) / 1000,
                    phone = phoneLink(app),
                    laptop = lap,
                    rttMs = if (now - rttAt < 10_000) rtt else -1,
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("DEPRECATION")
    private fun phoneLink(ctx: Context): PhoneLink? = runCatching {
        val info = ctx.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        // networkId is hidden from apps without location access on Android 12+, so the
        // link speed is the test for "connected".
        if (info.linkSpeed <= 0) return null
        PhoneLink(
            linkMbps = info.linkSpeed,
            txMbps = if (Build.VERSION.SDK_INT >= 29) info.txLinkSpeedMbps else info.linkSpeed,
            rxMbps = if (Build.VERSION.SDK_INT >= 29) info.rxLinkSpeedMbps else info.linkSpeed,
            rssi = info.rssi,
            frequencyMhz = info.frequency,
            standard = if (Build.VERSION.SDK_INT >= 30) when (info.wifiStandard) {
                8 -> "Wi-Fi 7"; 6 -> "Wi-Fi 6"; 5 -> "Wi-Fi 5"; 4 -> "Wi-Fi 4"; else -> "Wi-Fi"
            } else "Wi-Fi",
        )
    }.getOrNull()

    private const val WINDOW = 60
}
