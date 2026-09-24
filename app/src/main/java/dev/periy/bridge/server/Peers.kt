package dev.periy.bridge.server

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Another phone running BlazeIt, seen on the local network. */
data class NearbyPhone(val name: String, val host: String, val port: Int)

/** A phone this one has been allowed into. */
@Serializable
data class Peer(val name: String, val host: String, val port: Int, val cookie: String)

/** Where a connection attempt stands, for the screen. */
sealed interface PeerStatus {
    data class Waiting(val code: String) : PeerStatus
    data class Failed(val message: String) : PeerStatus
}

/**
 * Phone to phone.
 *
 * Every phone runs the same server, so another phone is simply one more client: it asks to
 * connect exactly as a browser does, the owner approves it with a code, and from then on it
 * sends files through the same resumable, parallel upload path. Nothing here is special to
 * phones except finding each other, which uses the network's own service discovery
 * (mDNS, "_blazeit._tcp").
 */
class PeerManager(
    ctx: Context,
    private val deviceName: () -> String,
    private val streams: () -> Int,
    private val direct: dev.periy.bridge.net.DirectLink,
    /** Whether sends to another phone set up a direct link between the two first. */
    private val useDirect: () -> Boolean,
) {

    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(app.filesDir, "peers.json")

    private val _nearby = MutableStateFlow<List<NearbyPhone>>(emptyList())
    val nearby: StateFlow<List<NearbyPhone>> = _nearby.asStateFlow()

    private val _peers = MutableStateFlow(load())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _route = MutableStateFlow<Map<String, String>>(emptyMap())
    /** How a send to each phone is going out right now, by phone name, for the screen. */
    val route: StateFlow<Map<String, String>> = _route.asStateFlow()

    private val _status = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    /** Connection attempts in progress or failed, by host. */
    val status: StateFlow<Map<String, PeerStatus>> = _status.asStateFlow()

    private var advertised: NsdManager.RegistrationListener? = null
    private var ownName: String? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, NearbyPhone>()

    // ------------------------------------------------------------------ being found

    fun advertise(port: Int) {
        if (advertised != null || nsd == null) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { ownName = s.serviceName }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "Advertise failed: $code"); advertised = null }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        advertised = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { advertised = null }
    }

    fun stopAdvertising() {
        advertised?.let { l -> runCatching { nsd?.unregisterService(l) } }
        advertised = null
    }

    // ------------------------------------------------------------------ finding others

    fun startDiscovery() {
        if (discovery != null || nsd == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName == ownName) return
                resolveQueue.trySend(s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                found.remove(s.serviceName)
                publishNearby()
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
    }

    // Android resolves one service at a time; asking for a second while one is running
    // fails, so resolutions go through a queue.
    private val resolveQueue = kotlinx.coroutines.channels.Channel<NsdServiceInfo>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch {
            for (s in resolveQueue) {
                val resolved = kotlinx.coroutines.suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    @Suppress("DEPRECATION")
                    runCatching {
                        nsd?.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) {} }
                            override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) {} }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) {} }
                }
                @Suppress("DEPRECATION")
                val host = resolved?.host as? Inet4Address ?: continue
                if (host.hostAddress in ownAddresses()) continue
                found[s.serviceName] = NearbyPhone(s.serviceName, host.hostAddress ?: continue, resolved.port)
                publishNearby()
            }
        }
    }

    private fun publishNearby() {
        _nearby.value = found.values.sortedBy { it.name }
        // A known phone that moved to a new address (a hotspot restart does that) is
        // followed there rather than forgotten.
        val moved = _peers.value.map { p -> found[p.name]?.let { n -> p.copy(host = n.host, port = n.port) } ?: p }
        if (moved != _peers.value) { _peers.value = moved; save() }
    }

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { (it as? Inet4Address)?.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    // ------------------------------------------------------------------ pairing

    /** Asks [phone] to let this one in. The other phone shows a code to compare. */
    fun connect(phone: NearbyPhone) {
        scope.launch {
            val host = phone.host
            try {
                // A phone added by address is known only by its address until it says its name.
                val name = runCatching {
                    json.parseToJsonElement(request("GET", phone, "/api/ping", null, null).body)
                        .jsonObject["device"]!!.jsonPrimitive.content
                }.getOrDefault(phone.name)
                val start = request("POST", phone, "/api/pair", null, null)
                if (start.code !in 200..299) error(message(start.body) ?: "The other phone refused (${start.code})")
                val body = json.parseToJsonElement(start.body).jsonObject
                val id = body["id"]!!.jsonPrimitive.content
                val code = body["code"]!!.jsonPrimitive.content
                setStatus(host, PeerStatus.Waiting(code))
                repeat(125) {
                    delay(1000)
                    val poll = request("GET", phone, "/api/pair/$id", null, null)
                    val state = runCatching {
                        json.parseToJsonElement(poll.body).jsonObject["state"]!!.jsonPrimitive.content
                    }.getOrDefault("")
                    when (state) {
                        "APPROVED" -> {
                            val cookie = poll.setCookie ?: error("Approved, but no session came back")
                            val peer = Peer(name, phone.host, phone.port, cookie)
                            _peers.value = _peers.value.filterNot { it.name == peer.name } + peer
                            save()
                            setStatus(host, null)
                            return@launch
                        }
                        "DENIED" -> error("The other phone said no")
                        "EXPIRED" -> error("Nobody answered on the other phone")
                    }
                }
                error("Nobody answered on the other phone")
            } catch (t: Throwable) {
                Log.w(TAG, "Connect to $host failed", t)
                setStatus(host, PeerStatus.Failed(t.message ?: "Could not reach that phone"))
            }
        }
    }

    fun forget(peer: Peer) {
        _peers.value = _peers.value.filterNot { it.name == peer.name }
        save()
    }

    private fun setStatus(host: String, s: PeerStatus?) {
        _status.value = if (s == null) _status.value - host else _status.value + (host to s)
    }

    // ------------------------------------------------------------------ sending

    fun sendText(peer: Peer, text: String, done: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                val body = json.encodeToString(ClipboardRequest(text)).toByteArray()
                request("POST", peer.asTarget(), "/api/clipboard", peer.cookie, body, "application/json").code in 200..299
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    /**
     * Sends files one after another; each file goes over several connections when large.
     *
     * First, when allowed, the two phones get a network of their own: the other phone starts
     * its direct link and this one joins it (Android asks once, with its own prompt). That
     * is one hop instead of two through a router, and nobody else is on the channel. If any
     * step fails the files go the ordinary way instead.
     */
    fun sendFiles(peer: Peer, uris: List<Uri>) {
        scope.launch {
            val route = if (useDirect()) runCatching { directRoute(peer) }.getOrNull() else null
            setRoute(peer, if (route != null) "Direct link" else "Wi-Fi")
            try {
                for (uri in uris) runCatching { sendOne(peer, uri, route) }.onFailure { Log.w(TAG, "Send failed", it) }
            } finally {
                route?.close()
                setRoute(peer, null)
            }
        }
    }

    /** A private network to the other phone, and its address on it. */
    private inner class Route(
        val target: NearbyPhone,
        val joined: dev.periy.bridge.net.DirectLink.Joined,
        /** Stop the other phone's link afterwards: it was off before this send started it. */
        val stopAfter: Boolean,
        val cookie: String,
    ) : AutoCloseable {
        val network get() = joined.network
        override fun close() {
            if (stopAfter) runCatching { request("POST", target, "/api/direct/stop", cookie, null, network = network) }
            joined.close()
        }
    }

    private suspend fun directRoute(peer: Peer): Route? {
        setRoute(peer, "Setting up a direct link")
        val was = runCatching {
            json.parseToJsonElement(request("GET", peer.asTarget(), "/api/direct", peer.cookie, null).body)
                .jsonObject["state"]!!.jsonPrimitive.content
        }.getOrNull() ?: return null
        val started = request("POST", peer.asTarget(), "/api/direct/start?for=phone", peer.cookie, null)
        if (started.code !in 200..299) return null
        val dto = json.decodeFromString<DirectDto>(started.body)
        val info = dto.info?.takeIf { dto.state == "on" && it.host.isNotEmpty() } ?: return null
        setRoute(peer, "Joining the direct link of " + peer.name)
        val joined = direct.join(info) ?: run {
            if (was != "on") runCatching { request("POST", peer.asTarget(), "/api/direct/stop", peer.cookie, null) }
            return null
        }
        return Route(NearbyPhone(peer.name, info.host, info.port), joined, stopAfter = was != "on", cookie = peer.cookie)
    }

    private fun setRoute(peer: Peer, note: String?) {
        _route.value = if (note == null) _route.value - peer.name else _route.value + (peer.name to note)
    }

    private suspend fun sendOne(peer: Peer, uri: Uri, route: Route?) {
        val (name, size) = describe(uri)
        val id = UUID.randomUUID().toString()
        val to = route?.target ?: peer.asTarget()
        val net = route?.network
        Transfers.begin(id, name + "  →  " + peer.name, Direction.OUTBOUND, size)
        try {
            val meta = "filename " + b64(name) + ",filetype " + b64(app.contentResolver.getType(uri) ?: "application/octet-stream")
            val n = if (size >= PARALLEL_THRESHOLD) streams().coerceIn(1, 8) else 1
            val windows: List<Triple<String, Long, Long>> = if (n > 1) {
                val r = request("POST", to, "/tus/parallel", peer.cookie, null, null,
                    mapOf("Tus-Resumable" to "1.0.0", "Upload-Length" to "$size", "Upload-Metadata" to meta, "Bridge-Streams" to "$n"), net)
                if (r.code !in 200..299) error(message(r.body) ?: "Refused (${r.code})")
                json.parseToJsonElement(r.body).jsonObject["streams"]!!.jsonArray.map {
                    val o = it.jsonObject
                    Triple(o.str("url"), o["base"]!!.jsonPrimitive.long, o["length"]!!.jsonPrimitive.long)
                }
            } else {
                val r = request("POST", to, "/tus", peer.cookie, null, null,
                    mapOf("Tus-Resumable" to "1.0.0", "Upload-Length" to "$size", "Upload-Metadata" to meta), net)
                if (r.code !in 200..299) error(message(r.body) ?: "Refused (${r.code})")
                listOf(Triple(r.location ?: error("No upload address came back"), 0L, size))
            }
            val sent = AtomicLong()
            coroutineScopeAll(windows) { (url, base, length) -> sendWindow(peer, to, net, uri, url, base, length, id, sent) }
            Transfers.progress(id, size)
            Transfers.finish(id, ok = true)
        } catch (t: Throwable) {
            Transfers.finish(id, ok = false)
            throw t
        }
    }

    private suspend fun <T> coroutineScopeAll(items: List<T>, block: suspend (T) -> Unit) =
        kotlinx.coroutines.coroutineScope { items.map { async(Dispatchers.IO) { block(it) } }.awaitAll() }

    /** One window of the file, streamed from its own descriptor in 64 MB requests. */
    private fun sendWindow(
        peer: Peer, to: NearbyPhone, net: android.net.Network?, uri: Uri,
        url: String, base: Long, length: Long, id: String, sent: AtomicLong,
    ) {
        app.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val buf = ByteBuffer.allocate(1 shl 20)
            var offset = 0L
            while (offset < length) {
                val len = minOf(CHUNK, length - offset)
                val conn = open("PATCH", to, url, peer.cookie, net)
                conn.setRequestProperty("Tus-Resumable", "1.0.0")
                conn.setRequestProperty("Content-Type", "application/offset+octet-stream")
                conn.setRequestProperty("Upload-Offset", "$offset")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(len)
                ch.position(base + offset)
                conn.outputStream.use { out ->
                    var left = len
                    while (left > 0) {
                        buf.clear()
                        if (left < buf.capacity()) buf.limit(left.toInt())
                        val r = ch.read(buf)
                        if (r <= 0) error("The file ended early")
                        out.write(buf.array(), 0, r)
                        left -= r
                        Monitor.addOut(r)
                        Transfers.progress(id, sent.addAndGet(r.toLong()))
                    }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code != 204) error("The other phone stopped the upload ($code)")
                offset += len
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "file") to c.getLong(1)
        }
        error("Cannot read that file")
    }

    // ------------------------------------------------------------------ http

    private class Response(val code: Int, val body: String, val setCookie: String?, val location: String?)

    private fun Peer.asTarget() = NearbyPhone(name, host, port)

    private fun open(method: String, to: NearbyPhone, path: String, cookie: String?, network: android.net.Network? = null): HttpURLConnection {
        val url = URL("http://${to.host}:${to.port}$path")
        // On a direct link the connection has to go out over that network specifically.
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        // Android's HttpURLConnection accepts PATCH; if a build ever refuses it, fall back to
        // tus's standard override, which the server also understands.
        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "BlazeItPhone/1 (${deviceName()})")
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun request(
        method: String, to: NearbyPhone, path: String, cookie: String?, body: ByteArray?,
        type: String? = null, headers: Map<String, String> = emptyMap(),
        network: android.net.Network? = null,
    ): Response {
        val conn = open(method, to, path, cookie, network)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            type?.let { conn.setRequestProperty("Content-Type", it) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        } else if (method == "POST") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
        }
        val code = conn.responseCode
        val text = runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val cookie = conn.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith(SESSION_COOKIE + "=") }?.substringBefore(';')
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        return Response(code, text, cookie, location)
    }

    private fun message(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun load(): List<Peer> = runCatching {
        if (file.exists()) json.decodeFromString<List<Peer>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() = runCatching { file.writeText(json.encodeToString(_peers.value)) }

    companion object {
        private const val TAG = "Peers"
        const val SERVICE_TYPE = "_blazeit._tcp."
        private const val PARALLEL_THRESHOLD = 16L * 1024 * 1024
        private const val CHUNK = 64L * 1024 * 1024
    }
}
