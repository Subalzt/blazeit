package dev.periy.bridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import dev.periy.bridge.net.Reach
import dev.periy.bridge.server.Direction
import dev.periy.bridge.server.FileEntry
import dev.periy.bridge.server.Monitor
import dev.periy.bridge.server.NearbyPhone
import dev.periy.bridge.server.PairRequest
import dev.periy.bridge.server.PairedDevice
import dev.periy.bridge.server.Peer
import dev.periy.bridge.server.PeerStatus
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.SystemClipboard
import dev.periy.bridge.server.Transfer
import dev.periy.bridge.server.TransferState
import dev.periy.bridge.server.Transfers
import dev.periy.bridge.service.BridgeService
import dev.periy.bridge.service.formatBytes
import dev.periy.bridge.service.formatRate
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark glass everywhere, so light system-bar icons everywhere.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleShare(intent)
        setContent { BlazeItUi(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /**
     * Accepts content handed over by the share sheet.
     *
     * The action is cleared afterwards so a configuration change or a return to the task
     * cannot import the same files a second time.
     */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> vm.acceptSharedFiles(listOf(uri))
                    !text.isNullOrEmpty() -> vm.acceptSharedText(text)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) vm.acceptSharedFiles(uris)
            }

            else -> return
        }
        // Sharing into the app implies wanting the computer to be able to fetch it.
        BridgeService.start(this)
        intent.action = null
    }
}

private val TABS = listOf(
    "Home" to Icons.Rounded.Home,
    "Phones" to Icons.Rounded.Phone,
    "Control" to BlazeIcons.Trackpad,
    "Setup" to Icons.Rounded.Settings,
)
private const val TAB_PHONES = 1
private const val TAB_CONTROL = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlazeItUi(vm: MainViewModel) {
    val ctx = LocalContext.current
    val peers = ctx.container.peers
    val state by vm.state.collectAsStateWithLifecycle()
    val running by BridgeService.running.collectAsStateWithLifecycle()
    val transfers by Transfers.flow.collectAsStateWithLifecycle()
    val shared by vm.clipboard.collectAsStateWithLifecycle()
    val clipStatus by vm.clipStatus.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val sendStatus by vm.sendStatus.collectAsStateWithLifecycle()
    val requests by vm.pairRequests.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val live by vm.liveDevices.collectAsStateWithLifecycle()
    val monitor by Monitor.snapshot.collectAsStateWithLifecycle()
    val nearby by peers.nearby.collectAsStateWithLifecycle()
    val paired by peers.peers.collectAsStateWithLifecycle()
    val peerStatus by peers.status.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var showOem by remember { mutableStateOf(false) }
    val oemSteps = remember { OemBatterySetup.steps(ctx) }
    var sendTarget by remember { mutableStateOf<Peer?>(null) }
    var showMonitor by remember { mutableStateOf(ctx.container.prefs.showMonitor) }
    val setMonitor = { on: Boolean -> showMonitor = on; ctx.container.prefs.showMonitor = on }
    val haze = rememberHazeState()

    // A computer asking to connect is waiting on you, so jump to where the answer is.
    LaunchedEffect(requests.size) { if (requests.isNotEmpty()) { tab = 0; showOem = false } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.offerPickedFiles(uris)
    }
    val pickForPhone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val to = sendTarget
        if (to != null && uris.isNotEmpty()) {
            peers.sendFiles(to, uris)
            Toast.makeText(ctx, "Sending ${uris.size} to ${to.name}", Toast.LENGTH_SHORT).show()
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setDestination)
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val requestMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    // Other phones are looked for only while the Phones tab is open.
    if (tab == TAB_PHONES) {
        DisposableEffect(Unit) {
            peers.startDiscovery()
            onDispose { peers.stopDiscovery() }
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBarSpace = 84.dp + bottomInset
    val imeUp = WindowInsets.isImeVisible

    CompositionLocalProvider(LocalPalette provides GlassPalette, LocalHaze provides haze) {
        Box(Modifier.fillMaxSize()) {
          // Everything the floating glass (tab bar, monitor) blurs as it passes over.
          Box(Modifier.fillMaxSize().hazeSource(haze)) {
            Backdrop()
            Column(Modifier.fillMaxSize()) {
                Header(if (tab == 0) "BlazeIt" else TABS[tab].first, running, showMonitor) { setMonitor(!showMonitor) }
                // Room for the monitor capsule, so by default it covers nothing.
                if (showMonitor) Spacer(Modifier.height(46.dp))

                if (showOem) {
                    OemScreen(oemSteps, tabBarSpace, onOpen = { intent ->
                        runCatching { openSettings.launch(intent) }.onFailure {
                            Toast.makeText(ctx, "This phone would not open that screen", Toast.LENGTH_SHORT).show()
                        }
                    }, onDone = { showOem = false })
                    return@Column
                }

                if (tab == TAB_CONTROL) {
                    ControlPane(
                        running = running,
                        onStart = { BridgeService.start(ctx) },
                        modifier = Modifier.fillMaxSize().padding(bottom = if (imeUp) 0.dp else tabBarSpace - bottomInset),
                    )
                    return@Column
                }

                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = tabBarSpace + 16.dp)) {
                    when (tab) {
                        0 -> homeTab(
                            state, running, shared, clipStatus, requests, devices, live, vm,
                            transfers, files, sendStatus,
                            pickFiles = { pickFiles.launch(arrayOf("*/*")) },
                        ) {
                            if (running) BridgeService.stop(ctx) else BridgeService.start(ctx)
                        }
                        TAB_PHONES -> phonesTab(
                            running, transfers, nearby, paired, peerStatus,
                            connect = peers::connect,
                            forget = peers::forget,
                            sendFilesTo = { sendTarget = it; pickForPhone.launch(arrayOf("*/*")) },
                            sendTextTo = { p ->
                                if (shared.isBlank()) {
                                    Toast.makeText(ctx, "Type something in Clipboard on Home first", Toast.LENGTH_SHORT).show()
                                } else peers.sendText(p, shared) { ok ->
                                    Toast.makeText(ctx, if (ok) "Sent to ${p.name}" else "Could not reach ${p.name}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        else -> setupTab(
                            state, vm,
                            pickFolder = { pickFolder.launch(null) },
                            requestNotifications = { requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                            requestMusic = { requestMusic.launch(musicPermission()) },
                            openSettings = { openSettings.launch(it) },
                            showOem = { showOem = true },
                        )
                    }
                }
            }
          }

            if (!imeUp && !showOem) {
                GlassTabBar(
                    TABS, tab,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 10.dp),
                ) { tab = it }
            }

            if (showMonitor) MonitorOverlay(monitor, running) { setMonitor(false) }
        }
    }
}

/** The permission that covers reading the music library on this Android version. */
private fun musicPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

// ---------------------------------------------------------------------- chrome

/** Large title, as on iOS, with the monitor switch and a small on/off pill on the right. */
@Composable
private fun Header(title: String, running: Boolean, monitorOn: Boolean, toggleMonitor: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = LargeTitleStyle, color = Bridge.Text, modifier = Modifier.weight(1f))
        IconChip(BlazeIcons.Pulse, if (monitorOn) "Hide monitor" else "Show monitor", tint = if (monitorOn) Bridge.Yellow else Bridge.Text, onClick = toggleMonitor)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.glass(ButtonShape).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) Bridge.Good else Bridge.Faint))
            Spacer(Modifier.width(6.dp))
            Text(if (running) "On" else "Off", style = LabelStyle, color = Bridge.Text)
        }
    }
}

/**
 * The server, and everything about reaching it, in one card: a real on/off switch, the
 * address (tap to copy), and the QR code and other addresses folded away until wanted.
 */
@Composable
private fun ServerCard(state: UiState, running: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    val url = state.primaryUrl
    val copy = {
        if (url != null) {
            SystemClipboard.write(ctx, url)
            Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp).panel().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (running) "BlazeIt is on" else "BlazeIt is off", style = TitleStyle.copy(fontSize = 17.sp), color = Bridge.Text)
                Text(
                    when {
                        !running -> "Turn on to connect a computer or a phone."
                        state.storageMode == Storage.Mode.NO_DESTINATION -> "Choose where files go, in Setup."
                        url == null -> "Join Wi-Fi or turn on the hotspot."
                        state.onlyCellular -> "Mobile data can't be reached. Use the hotspot or USB."
                        else -> "Open this on your computer:"
                    },
                    style = BodyStyle, color = Bridge.Muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            IosSwitch(running) { onToggle() }
        }

        if (running && url != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    url.removePrefix("http://").removeSuffix("/"),
                    style = TextStyle(fontSize = 17.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = Bridge.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = copy),
                )
                Spacer(Modifier.width(8.dp))
                TextPill(if (showQr) "Hide QR" else "QR") { showQr = !showQr }
                Spacer(Modifier.width(6.dp))
                TextPill("Copy", onClick = copy)
            }
            state.fasterLink?.let { usb ->
                Spacer(Modifier.height(6.dp))
                Text("USB is plugged in and faster: " + usb.url(state.port), style = BodyStyle, color = Bridge.Yellow)
            }
            if (showQr) {
                val qr = remember(url) { QrCode.render(url, 520) }
                if (qr != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = qr,
                            contentDescription = "QR code for the address",
                            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp),
                        )
                    }
                }
                state.addresses.drop(1).forEach { a ->
                    Spacer(Modifier.height(10.dp))
                    Text(a.kind.label + "  " + a.url(state.port), style = MonoStyle.copy(fontSize = 13.sp), color = Bridge.Text)
                    Text(
                        when (a.reach) {
                            Reach.LAN_ONLY -> "same Wi-Fi or cable only"
                            Reach.CARRIER_NAT -> "unreachable - carrier NAT"
                            Reach.PUBLIC -> "public address"
                        },
                        style = BodyStyle.copy(fontSize = 12.sp), color = Bridge.Muted,
                    )
                }
            }
        }
    }
}

/** A small, quiet text button. */
@Composable
private fun TextPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = LabelStyle,
        color = Bridge.Text,
        modifier = Modifier
            .glass(ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

// ---------------------------------------------------------------------- tab: home

private fun LazyListScope.homeTab(
    state: UiState,
    running: Boolean,
    shared: String,
    clipStatus: String,
    requests: List<PairRequest>,
    devices: List<PairedDevice>,
    live: Map<String, Int>,
    vm: MainViewModel,
    transfers: List<Transfer>,
    files: List<FileEntry>,
    sendStatus: String,
    pickFiles: () -> Unit,
    onToggle: () -> Unit,
) {
    item { ServerCard(state, running, onToggle) }

    // Someone is asking to connect. This goes in the accent colour: it is the one thing on
    // screen that is waiting on you.
    if (requests.isNotEmpty()) {
        item { SectionBar("Wants to connect") }
        items(requests, key = { it.id }) { req ->
            BridgeRow(title = req.name, glyph = "?", meta = req.ip, accent = true) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Code", style = LabelStyle, color = Bridge.OnYellowSoft)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        req.code,
                        style = TextStyle(fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 5.sp),
                        color = Bridge.OnYellow,
                    )
                }
                RowNote("Only allow it if the other screen shows the same code.", color = Bridge.OnYellowSoft)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f).clip(ButtonShape).background(Bridge.OnYellow).clickable { vm.approve(req.id) }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Allow", style = TitleStyle.copy(fontSize = 15.sp), color = Bridge.Yellow) }
                    Box(
                        Modifier.weight(1f).clip(ButtonShape).background(Color(0x26000000)).clickable { vm.deny(req.id) }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Deny", style = TitleStyle.copy(fontSize = 15.sp), color = Bridge.OnYellow) }
                }
            }
        }
    }

    item { SectionBar("Clipboard") }
    item { ClipboardPanel(shared, clipStatus, vm) }

    transfersSection(transfers)

    item { SectionBar("Files") }
    item {
        Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
            BridgeButton("Send files to the computer", Modifier.fillMaxWidth(), onClick = pickFiles)
            Spacer(Modifier.height(8.dp))
            Text(
                sendStatus.ifEmpty { "Or share into BlazeIt from any app. Files from the computer land here too." },
                style = BodyStyle, color = if (sendStatus.isEmpty()) Bridge.Muted else Bridge.Good,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    if (files.isNotEmpty()) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            GroupCard {
                files.forEachIndexed { i, f ->
                    SettingRow(
                        f.name,
                        formatBytes(f.size) + " · " + (if (f.origin == "PHONE") "sent from this phone" else "received") +
                            (if (!f.owned) " · original" else ""),
                        first = i == 0,
                    ) {
                        IconChip(Icons.Rounded.Close, "Delete ${f.name}", tint = Bridge.Danger) { vm.removeFile(f.id) }
                    }
                }
            }
        }
    }

    item {
        SectionBar("Connected") {
            if (live.isNotEmpty()) Text("${live.size} live", style = LabelStyle, color = Bridge.Good)
        }
    }
    if (devices.isEmpty()) {
        item { Blank("Nothing yet. Open the address above on a computer, or connect a phone from Phones.") }
    } else {
        item {
            GroupCard {
                devices.forEachIndexed { i, d ->
                    val isLive = (live[d.id] ?: 0) > 0
                    SettingRow(d.name, (if (isLive) "Live · " else lastSeen(d.lastSeenAt) + " · ") + d.lastIp, first = i == 0) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isLive) Bridge.Good else Bridge.Faint))
                        Spacer(Modifier.width(12.dp))
                        IconChip(Icons.Rounded.Close, "Remove ${d.name}", tint = Bridge.Muted) { vm.removeDevice(d.id) }
                    }
                }
            }
        }
    }
}

/** Anything moving right now, either way, with progress. */
private fun LazyListScope.transfersSection(transfers: List<Transfer>) {
    if (transfers.isEmpty()) return
    item {
        SectionBar("Moving") {
            Text("Clear", style = LabelStyle, color = Bridge.Yellow, modifier = Modifier.clickable { Transfers.clearFinished() })
        }
    }
    items(transfers, key = { "t-" + it.id }) { TransferRow(it) }
}

@Composable
private fun ClipboardPanel(shared: String, status: String, vm: MainViewModel) {
    var draft by remember { mutableStateOf(shared) }
    LaunchedEffect(shared) { if (shared != draft) draft = shared }

    Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
        BridgeTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Type here, or tap Paste to grab what you last copied.",
            minHeight = 96.dp,
        )
        Spacer(Modifier.height(10.dp))
        BridgeButton("Send to computer", Modifier.fillMaxWidth()) { vm.sendClipboard(draft) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Paste", Modifier.weight(1f)) { vm.pasteFromDevice() }
            GhostButton("Copy", Modifier.weight(1f)) { vm.copyToDevice() }
            GhostButton("Clear", Modifier.weight(1f), danger = true) { vm.clearClipboard(); draft = "" }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = LabelStyle, color = Bridge.Good)
        }
    }
}

// ---------------------------------------------------------------------- tab: phones

private fun LazyListScope.phonesTab(
    running: Boolean,
    transfers: List<Transfer>,
    nearby: List<NearbyPhone>,
    paired: List<Peer>,
    peerStatus: Map<String, PeerStatus>,
    connect: (NearbyPhone) -> Unit,
    forget: (Peer) -> Unit,
    sendFilesTo: (Peer) -> Unit,
    sendTextTo: (Peer) -> Unit,
) {
    item { Spacer(Modifier.height(4.dp)) }
    item { SectionBar("Phones with BlazeIt") }
    val pairedNames = paired.map { it.name }.toSet()
    val unpaired = nearby.filter { it.name !in pairedNames }
    if (paired.isEmpty() && unpaired.isEmpty()) {
        item {
            Blank(
                if (!running) "Turn BlazeIt on (switch on Home) so other phones can find this one, and it can find them."
                else "Looking for phones running BlazeIt on this network. Both need BlazeIt on, on the same Wi-Fi or one phone's hotspot."
            )
        }
    } else {
        item {
            GroupCard {
                var first = true
                paired.forEach { p ->
                    val here = nearby.any { it.name == p.name }
                    SettingRow(p.name, if (here) "Connected · nearby" else "Connected · not seen right now", first = first) {
                        IconChip(Icons.Rounded.Share, "Send text to ${p.name}") { sendTextTo(p) }
                        Spacer(Modifier.width(8.dp))
                        TextPill("Send files") { sendFilesTo(p) }
                        Spacer(Modifier.width(8.dp))
                        IconChip(Icons.Rounded.Close, "Forget ${p.name}", tint = Bridge.Muted) { forget(p) }
                    }
                    first = false
                }
                unpaired.forEach { n ->
                    val s = peerStatus[n.host]
                    SettingRow(
                        n.name,
                        when (s) {
                            is PeerStatus.Waiting -> "Allow it on ${n.name}. Code ${s.code}"
                            is PeerStatus.Failed -> s.message
                            null -> n.host
                        },
                        first = first,
                    ) {
                        if (s !is PeerStatus.Waiting) TextPill("Connect") { connect(n) }
                    }
                    first = false
                }
            }
        }
    }

    // Attempts made by address are not in the discovered list, so their progress shows here.
    val listed = unpaired.map { it.host }.toSet()
    peerStatus.filterKeys { it !in listed }.forEach { (host, st) ->
        item {
            Text(
                when (st) {
                    is PeerStatus.Waiting -> "$host: allow it on the other phone. Code ${st.code}"
                    is PeerStatus.Failed -> "$host: ${st.message}"
                },
                style = BodyStyle,
                color = if (st is PeerStatus.Failed) Bridge.Danger else Bridge.Yellow,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
    }
    item { ConnectByAddress(connect) }

    transfersSection(transfers)

}

/**
 * For networks where phones cannot see each other (some routers block discovery): type the
 * address the other phone shows on its Home screen.
 */
@Composable
private fun ConnectByAddress(connect: (NearbyPhone) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).padding(horizontal = 16.dp)) {
        if (!open) {
            Text(
                "Not listed? Connect by address",
                style = LabelStyle, color = Bridge.Yellow,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { open = true },
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BridgeTextField(text, { text = it }, Modifier.weight(1f), placeholder = "192.168.1.20", minHeight = 44.dp, mono = true)
            Spacer(Modifier.width(8.dp))
            TextPill("Connect") {
                val m = Regex("""(\d{1,3}(?:\.\d{1,3}){3}|localhost)(?::(\d+))?""").find(text.trim())
                if (m != null) {
                    val host = m.groupValues[1]
                    connect(NearbyPhone(host, host, m.groupValues[2].toIntOrNull() ?: 8787))
                    open = false; text = ""
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    BridgeRow(
        title = t.name,
        glyph = if (t.direction == Direction.INBOUND) "↓" else "↑",
        meta = when (t.state) {
            TransferState.ACTIVE -> formatRate(t.bytesPerSec)
            TransferState.STALLED -> "Paused"
            TransferState.DONE -> "Done"
            TransferState.FAILED -> "Failed"
        },
    ) {
        Spacer(Modifier.height(10.dp))
        BlockProgress(t.fraction)
        Spacer(Modifier.height(6.dp))
        Text(
            formatBytes(t.transferred) + " of " + formatBytes(t.total),
            style = BodyStyle.copy(fontSize = 12.sp),
            color = when (t.state) {
                TransferState.FAILED -> Bridge.Danger
                TransferState.DONE -> Bridge.Good
                else -> Bridge.Muted
            },
        )
    }
}

// ---------------------------------------------------------------------- tab: setup

private fun LazyListScope.setupTab(
    state: UiState,
    vm: MainViewModel,
    pickFolder: () -> Unit,
    requestNotifications: () -> Unit,
    requestMusic: () -> Unit,
    openSettings: (Intent) -> Unit,
    showOem: () -> Unit,
) {
    item { SectionBar("Receiving") }
    item {
        GroupCard {
            SettingRow(
                "Save files to",
                (state.destination ?: "Not chosen yet") + when (state.storageMode) {
                    Storage.Mode.DIRECT_SEEK -> if (state.freeSpace > 0) " · " + formatBytes(state.freeSpace) + " free" else ""
                    Storage.Mode.STAGED_COPY -> " · slow path, copies at the end"
                    Storage.Mode.NO_DESTINATION -> ""
                },
                first = true,
                onClick = pickFolder,
            ) { Text("Change", style = LabelStyle, color = Bridge.Yellow) }
            SettingRow("Largest file accepted") {}
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                SegmentedRow(listOf("4 GB", "12 GB", "32 GB", "64 GB"), listOf(4L, 12L, 32L, 64L).indexOf(state.maxUploadSize / (1024L * 1024 * 1024))) {
                    vm.setMaxUploadSize(listOf(4L, 12L, 32L, 64L)[it] * 1024 * 1024 * 1024)
                }
            }
            SettingRow("Always stage in app storage", "Slower but always safe. Only if files arrive damaged.") {
                IosSwitch(state.forceStagedCopy) { vm.setForceStagedCopy(it) }
            }
        }
    }

    item { SectionBar("Speed") }
    item {
        GroupCard {
            SettingRow("Connections per file", "More connections keep Wi-Fi busy; 4 suits most links.", first = true) {}
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                SegmentedRow(listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(state.uploadStreams)) {
                    vm.setUploadStreams(listOf(1, 2, 4, 8)[it])
                }
            }
            SettingRow(
                if (state.hotspotActive) "Hotspot is on" else "Work offline with the hotspot",
                if (state.hotspotActive) "Join the computer to it for the fastest link."
                else "A direct link, no internet needed, and faster than going through a router.",
                onClick = { vm.tetherSettingsIntent()?.let(openSettings) },
            ) { Text("Open", style = LabelStyle, color = Bridge.Yellow) }
        }
    }

    item { SectionBar("Music") }
    item {
        GroupCard {
            if (state.musicGranted) {
                SettingRow(
                    if (state.musicTracks > 0) "${state.musicTracks} songs ready to stream" else "No music found",
                    "Play them in the Music tab on your computer.",
                    first = true,
                ) {}
            } else {
                SettingRow("Allow music access", "Audio only - not photos or other files.", first = true, onClick = requestMusic) {
                    Text("Allow", style = LabelStyle, color = Bridge.Yellow)
                }
            }
        }
    }

    item { SectionBar("Keep running") }
    item {
        GroupCard {
            SettingRow(
                "Notifications", if (state.notificationsGranted) "Allowed" else "Needed to stay running",
                first = true,
                onClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationsGranted) requestNotifications else null,
            ) { Check(state.notificationsGranted) }
            SettingRow(
                "Battery optimisation", if (state.batteryExempt) "Off - good" else "Tap to turn off",
                onClick = if (!state.batteryExempt) ({ vm.batteryOptimizationIntent()?.let(openSettings) ?: showOem() }) else null,
            ) { Check(state.batteryExempt) }
            SettingRow("Manufacturer settings", "Some phones stop apps on their own. Fix it here.", onClick = showOem) {
                Text("Open", style = LabelStyle, color = Bridge.Yellow)
            }
        }
    }

    item { SectionBar("Pairing") }
    item {
        GroupCard {
            SettingRow("Unpair everything", "Every computer and phone will have to ask again.", first = true, titleColor = Bridge.Danger, onClick = { vm.unpairAll() }) {}
        }
    }
}

@Composable
private fun Check(ok: Boolean) {
    Text(if (ok) "✓" else "!", style = TitleStyle, color = if (ok) Bridge.Good else Bridge.Yellow)
}

@Composable
private fun Blank(text: String) {
    Text(text, style = BodyStyle, color = Bridge.Muted, modifier = Modifier.fillMaxWidth().panel().padding(16.dp))
}

// ---------------------------------------------------------------------- oem screen

@Composable
private fun OemScreen(
    steps: List<OemBatterySetup.Step>,
    bottomSpace: Dp,
    onOpen: (Intent) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomSpace)) {
        item { SectionBar("Keep BlazeIt running") }
        item {
            Blank(
                "Only the screens this phone actually has are listed. Each one belongs to " +
                    "the manufacturer rather than to Android, so the wording differs by phone."
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
        items(steps) { step ->
            BridgeRow(step.title, glyph = "›") {
                RowNote(step.detail)
                Spacer(Modifier.height(10.dp))
                BridgeButton("Open") { onOpen(step.intent) }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                BridgeButton("Done", Modifier.fillMaxWidth(), onClick = onDone)
            }
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago" -- coarse on purpose; it is a glance. */
private fun lastSeen(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
