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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.net.Reach
import dev.periy.bridge.server.Direction
import dev.periy.bridge.server.FileEntry
import dev.periy.bridge.server.PairRequest
import dev.periy.bridge.server.PairedDevice
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.SystemClipboard
import dev.periy.bridge.server.Transfer
import dev.periy.bridge.server.TransferState
import dev.periy.bridge.server.Transfers
import dev.periy.bridge.service.BridgeService
import dev.periy.bridge.service.formatBytes
import dev.periy.bridge.service.formatRate

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The masthead is black, so the status bar needs light icons whatever the phone's
        // own theme is. The default auto style follows the system theme and would paint
        // dark icons onto black.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        handleShare(intent)
        setContent { XooshUi(vm) }
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
                val uris = IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                if (!uris.isNullOrEmpty()) vm.acceptSharedFiles(uris)
            }

            else -> return
        }
        // Sharing into the app implies wanting the computer to be able to fetch it.
        BridgeService.start(this)
        intent.action = null
    }
}

@Composable
private fun XooshUi(vm: MainViewModel) {
    val ctx = LocalContext.current
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

    var tab by remember { mutableIntStateOf(0) }
    var showOem by remember { mutableStateOf(false) }
    val oemSteps = remember { OemBatterySetup.steps(ctx) }

    // A computer asking to connect is waiting on you, so jump to where the answer is.
    LaunchedEffect(requests.size) { if (requests.isNotEmpty()) { tab = 0; showOem = false } }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.offerPickedFiles(uris) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::setDestination) }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh() }

    val openSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh() }

    val requestMusic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refresh() }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val glass by vm.glass.collectAsStateWithLifecycle()

    // Glass has a dark backdrop, so the navigation bar needs light icons too; classic is
    // white at the bottom and needs dark ones. The status bar sits on the black masthead
    // in both, so it stays light.
    val activity = ctx as? ComponentActivity
    LaunchedEffect(glass) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (glass) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }

    CompositionLocalProvider(LocalPalette provides if (glass) GlassPalette else ClassicPalette) {
    Box(Modifier.fillMaxSize()) {
    Backdrop()
    Column(Modifier.fillMaxSize()) {
        Masthead(running)
        HazardStripe()

        if (showOem) {
            OemScreen(oemSteps, bottomInset, onOpen = { intent ->
                runCatching { openSettings.launch(intent) }.onFailure {
                    Toast.makeText(ctx, "This device would not open that screen", Toast.LENGTH_SHORT).show()
                }
            }, onDone = { showOem = false })
            return@Column
        }

        // The trackpad wants every pixel, so the big status block steps aside for it.
        if (tab != TAB_CONTROL) {
            Hero(state, running) {
                if (running) BridgeService.stop(ctx) else BridgeService.start(ctx)
            }
        }

        SegmentedRow(listOf("Home", "Send", "Control", "Setup"), tab) { tab = it }

        if (tab == TAB_CONTROL) {
            ControlPane(
                running = running,
                onStart = { BridgeService.start(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 32.dp),
        ) {
            when (tab) {
                0 -> homeTab(state, transfers, shared, clipStatus, requests, devices, live, vm, ctx)
                1 -> sendTab(files, sendStatus, vm) { pickFiles.launch(arrayOf("*/*")) }
                else -> setupTab(
                    state, vm, glass,
                    pickFolder = { pickFolder.launch(null) },
                    requestNotifications = {
                        requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                    requestMusic = { requestMusic.launch(musicPermission()) },
                    openSettings = { openSettings.launch(it) },
                    showOem = { showOem = true },
                )
            }
        }
    }
    }
    }
}

/** The permission that covers reading the music library on this Android version. */
private fun musicPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

// ---------------------------------------------------------------------- chrome

@Composable
private fun Masthead(running: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Bridge.Bar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mark = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
        Text("XOO", style = mark, color = Bridge.OnBar)
        Text("SH", style = mark, color = Bridge.Yellow)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(10.dp).background(if (running) Bridge.Yellow else Bridge.Muted, BlockShape))
        Spacer(Modifier.width(8.dp))
        Text(
            if (running) "LIVE" else "OFF",
            style = LabelStyle,
            color = if (running) Bridge.Yellow else Bridge.Muted,
        )
    }
}

/**
 * The band that answers "what do I do right now", showing exactly one thing at a time in
 * priority order. Showing every state at once is what makes a setup screen read as a form
 * rather than an instruction.
 */
@Composable
private fun Hero(state: UiState, running: Boolean, onToggle: () -> Unit) {
    val head = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black)
    Column(Modifier.fillMaxWidth().background(Bridge.HeroBg).padding(horizontal = 14.dp, vertical = 16.dp)) {
        when {
            !running -> {
                Text("XOOSH IS OFF", style = head, color = Bridge.OnYellow)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Bridge.OnYellow, BlockShape)
                        .clickable(onClick = onToggle)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("START", style = LabelStyle, color = Bridge.Yellow) }
            }

            state.storageMode == Storage.Mode.NO_DESTINATION -> {
                Text("CHOOSE A FOLDER", style = head, color = Bridge.OnYellow)
                Spacer(Modifier.height(4.dp))
                Text("Files from the computer need somewhere to land. Open Setup.", style = BodyStyle, color = Bridge.OnYellowSoft)
            }

            state.primaryUrl == null -> {
                Text("NO NETWORK", style = head, color = Bridge.OnYellow)
                Spacer(Modifier.height(4.dp))
                Text("Join Wi-Fi, or plug in USB and turn on tethering.", style = BodyStyle, color = Bridge.OnYellowSoft)
            }

            state.onlyCellular -> {
                Text("USE THE USB CABLE", style = head, color = Bridge.OnYellow)
                Spacer(Modifier.height(4.dp))
                Text(
                    "On mobile data your carrier hides this phone behind a shared address, " +
                        "so nothing can reach it over the network. A cable sidesteps that " +
                        "entirely. Or turn on this phone's hotspot and join the computer to it -- " +
                        "on 5 GHz that is the fastest wireless option, and the transfer itself " +
                        "uses none of your mobile data.",
                    style = BodyStyle, color = Bridge.OnYellowSoft,
                )
            }

            else -> {
                Text("OPEN ON YOUR COMPUTER", style = LabelStyle, color = Bridge.OnYellowSoft)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.primaryUrl.orEmpty(),
                    style = TextStyle(fontSize = 21.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black),
                    color = Bridge.OnYellow,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "A new computer will ask first. You approve it here.",
                        style = BodyStyle, color = Bridge.OnYellowSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .background(Bridge.OnYellow, BlockShape)
                            .clickable(onClick = onToggle)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) { Text("STOP", style = LabelStyle, color = Bridge.Yellow) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- tab: home

private fun LazyListScope.homeTab(
    state: UiState,
    transfers: List<Transfer>,
    shared: String,
    clipStatus: String,
    requests: List<PairRequest>,
    devices: List<PairedDevice>,
    live: Map<String, Int>,
    vm: MainViewModel,
    ctx: android.content.Context,
) {
    // Someone is asking to connect. This goes first and goes yellow: it is the one thing
    // on screen that is waiting on you.
    if (requests.isNotEmpty()) {
        item { SectionBar("Wants to connect") }
        items(requests, key = { it.id }) { req ->
            BridgeRow(title = req.name, glyph = "?", meta = req.ip, accent = true) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CODE", style = LabelStyle, color = Bridge.OnYellowSoft)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        req.code,
                        style = TextStyle(
                            fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black, letterSpacing = 5.sp,
                        ),
                        color = Bridge.OnYellow,
                    )
                }
                RowNote("Only allow it if the computer shows the same code.", color = Bridge.OnYellow)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(Bridge.OnYellow, BlockShape)
                            .clickable { vm.approve(req.id) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("ALLOW", style = LabelStyle, color = Bridge.Yellow) }
                    GhostButton("Deny", Modifier.weight(1f), danger = true) { vm.deny(req.id) }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    item {
        SectionBar("Connected computers") {
            val n = live.size
            if (n > 0) Text("$n LIVE", style = LabelStyle, color = Bridge.OnBar)
        }
    }
    if (devices.isEmpty()) {
        item { Blank("None yet. Open the address above on a computer and it will ask to connect.") }
    } else {
        items(devices, key = { it.id }) { d ->
            val isLive = (live[d.id] ?: 0) > 0
            BridgeRow(
                title = d.name,
                glyph = if (isLive) "●" else "○",
                meta = if (isLive) "LIVE" else lastSeen(d.lastSeenAt),
            ) {
                RowNote(d.lastIp)
                Spacer(Modifier.height(8.dp))
                GhostButton("Remove", danger = true) { vm.removeDevice(d.id) }
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    item { SectionBar("Shared text") }
    item { ClipboardPanel(shared, clipStatus, vm) }

    item { SectionBar("Connection") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            state.fasterLink?.let { usb ->
                BridgeRow("USB is plugged in - use it", glyph = "!", accent = true) {
                    RowNote(usb.url(state.port), color = Bridge.OnYellow)
                }
                Spacer(Modifier.height(10.dp))
            }

            val url = state.primaryUrl
            if (url != null) {
                val qr = remember(url) { QrCode.render(url, 520) }
                if (qr != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Pairing QR code",
                            modifier = Modifier.size(180.dp).background(androidx.compose.ui.graphics.Color.White).padding(6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                GhostButton("Copy address") {
                    SystemClipboard.write(ctx, url)
                    Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
                }
            }

            if (state.addresses.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text("OTHER ADDRESSES", style = LabelStyle, color = Bridge.Muted)
                state.addresses.drop(1).forEach { a ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.kind.label + "   " + a.url(state.port),
                        style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        color = Bridge.Muted,
                    )
                    // An address that cannot work is worse than no address, because it
                    // looks exactly as legitimate as one that can.
                    Text(
                        when (a.reach) {
                            Reach.LAN_ONLY -> "same Wi-Fi or cable only"
                            Reach.CARRIER_NAT -> "unreachable - carrier NAT"
                            Reach.PUBLIC -> "public address"
                        },
                        style = BodyStyle,
                        color = if (a.reach == Reach.CARRIER_NAT) Bridge.Danger else Bridge.Muted,
                    )
                }
            }
        }
    }

    item {
        SectionBar("Transfers") {
            if (transfers.isNotEmpty()) {
                Text("CLEAR", style = LabelStyle, color = Bridge.OnBar,
                    modifier = Modifier.clickable { Transfers.clearFinished() })
            }
        }
    }
    if (transfers.isEmpty()) {
        item { Blank("Nothing moving right now.") }
    } else {
        items(transfers, key = { it.id }) { TransferRow(it) }
    }
}

@Composable
private fun ClipboardPanel(shared: String, status: String, vm: MainViewModel) {
    var draft by remember { mutableStateOf(shared) }
    LaunchedEffect(shared) { if (shared != draft) draft = shared }

    Column(Modifier.panel().padding(14.dp)) {
        BridgeTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Type here, or tap PASTE to grab what you last copied.",
            minHeight = 104.dp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BridgeButton("Send to PC", Modifier.weight(1f)) { vm.sendClipboard(draft) }
            GhostButton("Paste", Modifier.weight(1f)) { vm.pasteFromDevice() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Copy to phone", Modifier.weight(1f)) { vm.copyToDevice() }
            GhostButton("Clear", Modifier.weight(1f), danger = true) { vm.clearClipboard(); draft = "" }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = LabelStyle, color = Bridge.Good)
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
            TransferState.STALLED -> "PAUSED"
            TransferState.DONE -> "DONE"
            TransferState.FAILED -> "FAILED"
        },
    ) {
        Spacer(Modifier.height(8.dp))
        BlockProgress(t.fraction)
        Spacer(Modifier.height(6.dp))
        Text(
            formatBytes(t.transferred) + " of " + formatBytes(t.total),
            style = BodyStyle,
            color = when (t.state) {
                TransferState.FAILED -> Bridge.Danger
                TransferState.DONE -> Bridge.Good
                else -> Bridge.Muted
            },
        )
    }
    Spacer(Modifier.height(2.dp))
}

// ---------------------------------------------------------------------- tab: send

private fun LazyListScope.sendTab(
    files: List<FileEntry>,
    status: String,
    vm: MainViewModel,
    pickFiles: () -> Unit,
) {
    item { SectionBar("Send to the computer") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            BridgeButton("Choose files", Modifier.fillMaxWidth(), onClick = pickFiles)
            Spacer(Modifier.height(8.dp))
            Text(
                "Or share into Xoosh from any app - Gallery, Files, a browser. " +
                    "Chosen files are offered where they already are; shared files are " +
                    "copied first, because a share only grants access for a moment.",
                style = BodyStyle, color = Bridge.Muted,
            )
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = LabelStyle, color = Bridge.Good)
            }
        }
    }

    item { SectionBar("Available on the computer") }
    if (files.isEmpty()) {
        item { Blank("Nothing offered yet.") }
    } else {
        items(files, key = { it.id }) { f ->
            BridgeRow(
                title = f.name,
                glyph = when {
                    f.mime.startsWith("image/") -> "▣"
                    f.mime.startsWith("video/") -> "▶"
                    else -> "▬"
                },
                meta = formatBytes(f.size),
            ) {
                RowNote(
                    (if (f.origin == "PHONE") "from this phone" else "from the computer") +
                        (if (!f.owned) "  -  original file, not a copy" else "")
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(if (f.owned) "Delete" else "Remove from list", danger = true) {
                    vm.removeFile(f.id)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ---------------------------------------------------------------------- tab: setup

private fun LazyListScope.setupTab(
    state: UiState,
    vm: MainViewModel,
    glass: Boolean,
    pickFolder: () -> Unit,
    requestNotifications: () -> Unit,
    requestMusic: () -> Unit,
    openSettings: (Intent) -> Unit,
    showOem: () -> Unit,
) {
    item { SectionBar("Appearance") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glass", style = TitleStyle, color = Bridge.Text)
                    Text(
                        "Frosted, see-through panels on a dark glow. Also changes the page " +
                            "on every connected computer.",
                        style = BodyStyle, color = Bridge.Muted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Toggle(glass) { vm.setGlass(it) }
            }
        }
    }

    item { SectionBar("Music") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            if (state.musicGranted) {
                Text(
                    if (state.musicTracks > 0) "${state.musicTracks} tracks ready to stream"
                    else "No music found on this phone",
                    style = TitleStyle, color = Bridge.Text,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open the Music tab on your computer. Songs play straight from the phone -- " +
                        "nothing is copied, and the next tracks are loaded ahead so skipping is instant.",
                    style = BodyStyle, color = Bridge.Muted,
                )
            } else {
                Text("Let the computer play your music", style = TitleStyle, color = Bridge.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Xoosh needs permission to read your music library. It only reads audio -- " +
                        "not photos, not documents, not the rest of your storage.",
                    style = BodyStyle, color = Bridge.Muted,
                )
                Spacer(Modifier.height(10.dp))
                BridgeButton("Allow music access", Modifier.fillMaxWidth(), onClick = requestMusic)
            }
        }
    }

    item { SectionBar("Checklist") }
    item {
        Column(Modifier.panel()) {
            CheckRow(state.storageMode != Storage.Mode.NO_DESTINATION, "Destination folder chosen")
            CheckRow(state.notificationsGranted, "Notifications allowed")
            CheckRow(state.batteryExempt, "Battery optimisation off")
        }
    }

    item { SectionBar("Destination folder") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            Text(
                state.destination ?: "Not chosen yet",
                style = TitleStyle,
                color = if (state.destination == null) Bridge.Danger else Bridge.Text,
            )
            Text(
                when (state.storageMode) {
                    Storage.Mode.DIRECT_SEEK -> "Fast path" +
                        (if (state.freeSpace > 0) "  -  " + formatBytes(state.freeSpace) + " free" else "")
                    Storage.Mode.STAGED_COPY -> "Slow path - this folder needs a copy at the end. " +
                        "A folder on internal storage usually avoids it."
                    Storage.Mode.NO_DESTINATION -> "Pick a folder to receive files."
                },
                style = BodyStyle,
                color = if (state.storageMode == Storage.Mode.STAGED_COPY) Bridge.Danger else Bridge.Muted,
            )
            Spacer(Modifier.height(10.dp))
            BridgeButton(if (state.destination == null) "Choose folder" else "Change folder", onClick = pickFolder)
        }
    }

    item { SectionBar("Work offline") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            if (state.hotspotActive) {
                Text("Hotspot is on", style = TitleStyle, color = Bridge.Good)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Join the computer to this phone's hotspot, then open the Hotspot " +
                        "address shown on the Home tab.",
                    style = BodyStyle, color = Bridge.Muted,
                )
            } else {
                Text("Turn the phone into the network", style = TitleStyle, color = Bridge.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The hotspot is a real Wi-Fi access point. It needs no SIM, no Wi-Fi and " +
                        "no internet -- switch it on, join the computer to it, and transfers " +
                        "run entirely offline. Choose the 5 GHz band for several times the " +
                        "speed of 2.4 GHz.",
                    style = BodyStyle, color = Bridge.Muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            BridgeButton("Open hotspot settings", Modifier.fillMaxWidth()) {
                val intent = vm.tetherSettingsIntent()
                if (intent != null) openSettings(intent)
            }
        }
    }

    item { SectionBar("Staying alive") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationsGranted) {
                BridgeButton("Allow notifications", Modifier.fillMaxWidth(), onClick = requestNotifications)
                Spacer(Modifier.height(10.dp))
            }
            if (!state.batteryExempt) {
                BridgeButton("Turn off battery optimisation", Modifier.fillMaxWidth()) {
                    val intent = vm.batteryOptimizationIntent()
                    if (intent != null) openSettings(intent) else showOem()
                }
                Spacer(Modifier.height(10.dp))
            }
            GhostButton("Manufacturer settings", Modifier.fillMaxWidth(), onClick = showOem)
            Spacer(Modifier.height(8.dp))
            Text(
                "Many phones run a second process killer above Android's own. A long " +
                    "transfer is exactly what it ends.",
                style = BodyStyle, color = Bridge.Muted,
            )
        }
    }

    item { SectionBar("Speed") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            Text("Parallel connections", style = TitleStyle, color = Bridge.Text)
            Spacer(Modifier.height(8.dp))
            Chooser(listOf(1, 2, 4, 8), state.uploadStreams, { "$it" }) { vm.setUploadStreams(it) }

            Spacer(Modifier.height(16.dp))
            Text("Largest upload accepted", style = TitleStyle, color = Bridge.Text)
            Spacer(Modifier.height(8.dp))
            Chooser(
                listOf(4L, 12L, 32L, 64L),
                state.maxUploadSize / (1024L * 1024 * 1024),
                { it.toString() + " GB" },
            ) { vm.setMaxUploadSize(it * 1024 * 1024 * 1024) }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always stage in app storage", style = TitleStyle, color = Bridge.Text)
                    Text("Slower but always safe. Only if files arrive corrupted.", style = BodyStyle, color = Bridge.Muted)
                }
                Spacer(Modifier.width(10.dp))
                Toggle(state.forceStagedCopy) { vm.setForceStagedCopy(it) }
            }
        }
    }

    item { SectionBar("Pairing") }
    item {
        Column(Modifier.panel().padding(14.dp)) {
            GhostButton("Unpair all browsers", danger = true) { vm.unpairAll() }
            Spacer(Modifier.height(8.dp))
            Text("Every computer will have to ask again, and you will have to allow it again.", style = BodyStyle, color = Bridge.Muted)
        }
    }
}

@Composable
private fun <T> Chooser(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            Box(
                Modifier
                    .weight(1f)
                    .background(if (opt == selected) Bridge.Yellow else Bridge.RowBg, BlockShape)
                    .clickable { onPick(opt) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(opt), style = LabelStyle, color = if (opt == selected) Bridge.OnYellow else Bridge.Text) }
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String) {
    BridgeRow(title = title, glyph = if (ok) "✓" else "!", accent = !ok)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(56.dp)
            .height(30.dp)
            .background(if (on) Bridge.Yellow else Bridge.Chip, BlockShape)
            .clickable { onChange(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).background(if (on) Bridge.OnYellow else Bridge.Text, BlockShape)) }
}

@Composable
private fun Blank(text: String) {
    Text(
        text,
        style = BodyStyle,
        color = Bridge.Muted,
        modifier = Modifier.fillMaxWidth().panel().padding(14.dp),
    )
}

// ---------------------------------------------------------------------- oem screen

@Composable
private fun OemScreen(
    steps: List<OemBatterySetup.Step>,
    bottomInset: Dp,
    onOpen: (Intent) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 32.dp),
    ) {
        item { SectionBar("Keep Xoosh alive") }
        item {
            Blank(
                "Only the screens this phone actually has are listed. Each one belongs to " +
                    "the manufacturer rather than to Android, so the wording differs by device."
            )
        }
        items(steps) { step ->
            BridgeRow(step.title, glyph = "›") {
                RowNote(step.detail)
                Spacer(Modifier.height(10.dp))
                BridgeButton("Open") { onOpen(step.intent) }
            }
            Spacer(Modifier.height(2.dp))
        }
        item {
            Box(Modifier.fillMaxWidth().padding(14.dp)) {
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

/** Index of the trackpad tab in the main tab row. */
private const val TAB_CONTROL = 2
