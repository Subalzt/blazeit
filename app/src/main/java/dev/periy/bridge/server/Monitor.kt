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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What bytes are for, so the monitor can show who is using the channel. */
enum class Lane { FILES, MUSIC, TEST }

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
    /** The speed the laptop's USB tethering adapter reports while the helper uses the cable; 0 otherwise. */
    val usbMbps: Int = 0,
    /**
     * The laptop's network adapter that reaches the phone, and its byte counters so far: all of
     * its traffic, Localhost 8787's and everything else's (the laptop's internet through the phone, say).
     */
    val iface: String = "",
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
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
    /** Both directions together, per use. */
    val filesBps: Long = 0,
    val musicBps: Long = 0,
    val testBps: Long = 0,
    /**
     * Traffic on the same link that is not Localhost 8787's, both directions: the laptop's internet
     * through the phone's hotspot or cable, other apps on the laptop. Needs the laptop helper.
     */
    val otherBps: Long = 0,
    /** Which of the phone's links the computer is on: usb, direct, hotspot, wifi, or "" before anyone asked. */
    val via: String = "",
    /** What the link in use can carry, in bytes per second; 0 when it is not known yet. */
    val capacityBps: Long = 0,
    /** Where that figure comes from, for the reader: "USB 3 cable", "the phone's hotspot, a 1201 Mbps Wi-Fi link"... */
    val capacityLabel: String = "",
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
    private val lanes = Array(Lane.entries.size) { AtomicLong() }

    private val _snapshot = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    @Volatile private var laptop: LaptopLink? = null
    /** The link the page or the helper last came in on. */
    @Volatile private var via = ""
    @Volatile private var otherBps = 0L
    @Volatile private var otherAt = 0L
    /** The previous helper report and Localhost 8787's own byte count at that moment, to tell the rest apart. */
    private var prevReport: LaptopLink? = null
    private var prevOwnBytes = 0L
    private var job: Job? = null
    private var startedAt = 0L

    fun addIn(n: Int, lane: Lane = Lane.FILES) {
        if (n > 0) { bytesIn.addAndGet(n.toLong()); lanes[lane.ordinal].addAndGet(n.toLong()) }
    }
    fun addOut(n: Int, lane: Lane = Lane.FILES) {
        if (n > 0) { bytesOut.addAndGet(n.toLong()); lanes[lane.ordinal].addAndGet(n.toLong()) }
    }

    // ---- who is watching
    //
    // Sampling wakes the CPU every second and asks the Wi-Fi service for the link, so it
    // only runs while someone looks: the phone's monitor on screen, or a page (or the
    // helper) that asked within the last few seconds. The byte counts above are plain
    // atomic adds and cost nothing when nobody reads them.
    private val uiWatchers = MutableStateFlow(0)
    private val webSeenAt = MutableStateFlow(0L)

    /** The phone's monitor appeared (true) or went away (false). */
    fun watchUi(on: Boolean) { uiWatchers.update { (it + if (on) 1 else -1).coerceAtLeast(0) } }

    /** A page or the helper asked for the monitor just now. */
    fun touchWeb() { webSeenAt.value = System.currentTimeMillis() }

    fun watched(now: Long = System.currentTimeMillis()): Boolean =
        uiWatchers.value > 0 || now - webSeenAt.value < WATCH_GRACE_MS
    fun requestStarted() { inflight.incrementAndGet() }
    fun requestEnded() { inflight.decrementAndGet() }

    /** A page or the helper came in on this link (a LinkKind name, lower case). */
    fun noteVia(kind: String) { via = kind }

    @Synchronized
    fun reportLaptop(link: LaptopLink) {
        val now = System.currentTimeMillis()
        val stamped = link.copy(at = now)
        laptop = stamped
        if (link.rttMs >= 0) reportRtt(link.rttMs)

        // Everything the laptop's adapter moved, less what Localhost 8787 moved, is someone else's use
        // of the same link. Headers and acknowledgements make the adapter count a few per cent
        // more than the payload, so Localhost 8787's share is scaled up by that much before subtracting.
        val own = bytesIn.get() + bytesOut.get()
        val prev = prevReport
        if (prev != null && prev.iface == link.iface && link.iface.isNotEmpty() &&
            link.rxBytes >= prev.rxBytes && link.txBytes >= prev.txBytes
        ) {
            val dt = now - prev.at
            if (dt in 500..20_000) {
                val all = (link.rxBytes - prev.rxBytes) + (link.txBytes - prev.txBytes)
                val rest = all - (own - prevOwnBytes) * 105 / 100
                otherBps = (rest.coerceAtLeast(0) * 1000 / dt).let { if (it < OTHER_FLOOR) 0 else it }
                otherAt = now
            }
        }
        prevReport = stamped
        prevOwnBytes = own
    }

    /** The cable speed from the helper's latest report; 0 when it is not on a cable or has gone quiet. */
    fun laptopUsbMbps(now: Long = System.currentTimeMillis()): Int =
        laptop?.takeIf { now - it.at < 30_000 }?.usbMbps ?: 0

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
            val lastLane = LongArray(Lane.entries.size) { lanes[it].get() }
            while (isActive) {
                if (!watched()) {
                    // Nobody is looking: sleep until someone is, then start the counts afresh
                    // so the first second back does not show everything since as one burst.
                    combine(uiWatchers, webSeenAt) { u, w -> u > 0 || System.currentTimeMillis() - w < WATCH_GRACE_MS }
                        .first { it }
                    lastIn = bytesIn.get(); lastOut = bytesOut.get(); lastAt = System.currentTimeMillis()
                    for (k in lastLane.indices) lastLane[k] = lanes[k].get()
                    samples.clear()
                }
                delay(1000)
                val now = System.currentTimeMillis()
                val i = bytesIn.get(); val o = bytesOut.get()
                // Per second of real time: the delay above can run long, and dividing by the
                // nominal second would count a late tick's extra bytes as extra speed.
                val dt = (now - lastAt).coerceAtLeast(1)
                val din = (i - lastIn) * 1000 / dt; val dout = (o - lastOut) * 1000 / dt
                lastIn = i; lastOut = o; lastAt = now
                val laneBps = LongArray(lastLane.size) { k ->
                    val v = lanes[k].get(); val d = (v - lastLane[k]) * 1000 / dt; lastLane[k] = v; d
                }
                samples.addLast(MonitorSample(now, din, dout))
                while (samples.size > WINDOW) samples.removeFirst()
                peakIn = maxOf(peakIn, din); peakOut = maxOf(peakOut, dout)

                val active = Transfers.flow.value.count { it.state == TransferState.ACTIVE }
                if (active > 0 && din == 0L && dout == 0L) { gaps++; lastGapAt = now }

                // A laptop report older than ten seconds means the helper has gone quiet.
                val lap = laptop?.takeIf { now - it.at < 10_000 }
                val phone = phoneLink(app)
                val (capacity, capacityLabel) = linkCapacity(via, phone, lap)
                _snapshot.value = MonitorSnapshot(
                    samples = samples.toList(),
                    inBps = din, outBps = dout,
                    peakInBps = peakIn, peakOutBps = peakOut,
                    totalIn = i, totalOut = o,
                    gaps = gaps, lastGapAt = lastGapAt,
                    requests = inflight.get().coerceAtLeast(0),
                    activeTransfers = active,
                    uptimeSec = (now - startedAt) / 1000,
                    phone = phone,
                    laptop = lap,
                    rttMs = if (now - rttAt < 10_000) rtt else -1,
                    filesBps = laneBps[Lane.FILES.ordinal],
                    musicBps = laneBps[Lane.MUSIC.ordinal],
                    testBps = laneBps[Lane.TEST.ordinal],
                    otherBps = if (now - otherAt < 6_000) otherBps else 0,
                    via = via,
                    capacityBps = capacity,
                    capacityLabel = capacityLabel,
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

    /**
     * What the link the computer is actually on can carry, and how that was worked out. Never
     * the fastest second seen: that is the peak, shown on its own, and a fullness measured
     * against it says nothing about the link.
     *
     * - A USB cable is its USB generation: measured 224-271 MB/s over USB 3, about 40 over USB 2
     *   (the helper tells them apart by the speed the tethering adapter reports).
     * - A Wi-Fi hop carries about half its link rate as real TCP (measured: 72 MB/s over a
     *   1201 Mbps Wi-Fi 6 link). On the phone's hotspot or direct link the laptop's link is the
     *   one hop. Through a router it is two hops, the slower one decides, and when both devices
     *   sit on the same channel every byte crosses the air twice, which halves it again.
     */
    private fun linkCapacity(via: String, phone: PhoneLink?, laptop: LaptopLink?): Pair<Long, String> {
        val lap = laptop?.let { minOf(it.rxMbps, it.txMbps) }?.takeIf { it > 0 }
        val ph = phone?.let { minOf(it.rxMbps, it.txMbps).takeIf { r -> r > 0 } ?: it.linkMbps }
        fun usable(mbps: Int) = mbps * 1_000_000L / 8 / 2
        return when (via) {
            "usb" -> {
                val usb = laptop?.usbMbps ?: 0
                when {
                    usb in 1 until 600 -> USB2_BPS to "USB 2 cable"
                    usb >= 600 -> USB3_BPS to "USB 3 cable"
                    // Without the helper nothing says which; the fast one is the usual cable here.
                    else -> USB3_BPS to "USB cable, taken as USB 3"
                }
            }
            "hotspot", "direct" ->
                if (lap == null) 0L to ""
                else usable(lap) to (if (via == "hotspot") "the phone's hotspot" else "the direct link") +
                    ", a $lap Mbps Wi-Fi link"
            else -> {
                val slowest = listOfNotNull(ph, lap).minOrNull() ?: return 0L to ""
                val phoneCh = phone?.let { channelOf(it.frequencyMhz) }
                val shared = phoneCh != null && laptop?.channel?.trim()?.toIntOrNull() == phoneCh
                if (shared) usable(slowest) / 2 to "Wi-Fi through the router, both on channel $phoneCh"
                else usable(slowest) to "Wi-Fi through the router, a $slowest Mbps link"
            }
        }
    }

    private fun channelOf(mhz: Int): Int? = when (mhz) {
        in 2412..2472 -> (mhz - 2407) / 5
        2484 -> 14
        in 5160..5885 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    /** Best measured over each cable here (network only): see the README's speed table. */
    private const val USB3_BPS = 271_000_000L
    private const val USB2_BPS = 42_000_000L

    /** Below this, "other" traffic is keep-alives and rounding, not worth a colour. */
    private const val OTHER_FLOOR = 64L * 1024

    private const val WINDOW = 60
    private const val WATCH_GRACE_MS = 5_000L
}
