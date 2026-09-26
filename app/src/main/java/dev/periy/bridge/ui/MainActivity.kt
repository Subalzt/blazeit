package dev.periy.bridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import dev.periy.bridge.net.DirectLink
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import dev.periy.bridge.net.LinkKind
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

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val look by vm.look.collectAsStateWithLifecycle()
            BlazeTheme(theme, look) { BlazeItUi(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        // On screen now, so Android can ask (once) to let Localhost 8787 read the log the watch needs.
        if (BridgeService.running.value) dev.periy.bridge.server.ClipWatch.ensure(this, container.prefs.clipSync)
    }

    /** With clipboard sync on, opening Localhost 8787 sends what was last copied on the phone. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val said = ClipSync.sendFromPhone(this)
        if (said.endsWith("sent to the laptop") || said == "Sent to the laptop") {
            Toast.makeText(this, if (said == "Sent to the laptop") "Clipboard sent to the laptop" else said, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Accepts content handed over by the share sheet.
     *
     * The action is cleared afterwards so a configuration change or a return to the task
     * cannot import the same files a second time.
     */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        // Debug builds: `adb shell am start ... --ez serve true` starts the server for testing.
        if (dev.periy.bridge.BuildConfig.DEBUG && intent.getBooleanExtra("serve", false)) BridgeService.start(this)
        // Debug builds: `--el synctest <trackId>` runs the phone's synced player silently from the
        // start of that song (see SyncPlay's log), `--ez syncstop true` stops it.
        if (dev.periy.bridge.BuildConfig.DEBUG) {
            val id = intent.getLongExtra("synctest", -1)
            if (id >= 0) dev.periy.bridge.server.SyncPlay.phone?.let {
                it.silent = true
                it.apply(dev.periy.bridge.server.SyncState(on = true, members = listOf("phone"), trackId = id, playing = true,
                    anchorMs = System.currentTimeMillis() + 1500, anchorPos = 0.0))
            }
            if (intent.getBooleanExtra("syncstop", false)) dev.periy.bridge.server.SyncPlay.phone?.release()
        }
        // Debug builds: `--es style theatre` and `--es accent blue` switch the look, for screenshots.
        if (dev.periy.bridge.BuildConfig.DEBUG) {
            intent.getStringExtra("style")?.let { vm.setStyle(it) }
            intent.getStringExtra("accent")?.let { vm.setAccent(it) }
            intent.getIntExtra("tab", -1).let { if (it >= 0) debugTab.value = it }
            // `--ez directtest true|false` starts or stops the direct link without a laptop joining it.
            if (intent.hasExtra("directtest")) (application as dev.periy.bridge.BridgeApp).container.direct.let {
                if (intent.getBooleanExtra("directtest", false)) it.start(8787, laptop = false) else it.stop()
            }
        }
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
    "Home" to BlazeIcons.Home,
    "Phones" to BlazeIcons.Phones,
    "Control" to BlazeIcons.Trackpad,
    "Settings" to BlazeIcons.Sliders,
)
private const val TAB_HOME = 0
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
    val theme by vm.theme.collectAsStateWithLifecycle()
    val look by vm.look.collectAsStateWithLifecycle()
    val direct by vm.direct.collectAsStateWithLifecycle()
    val phoneDirect by vm.phoneDirect.collectAsStateWithLifecycle()
    val laptopLink by vm.laptopLink.collectAsStateWithLifecycle()
    val nearby by peers.nearby.collectAsStateWithLifecycle()
    val paired by peers.peers.collectAsStateWithLifecycle()
    val peerStatus by peers.status.collectAsStateWithLifecycle()
    val routes by peers.route.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(TAB_HOME) }
    var showOem by remember { mutableStateOf(false) }
    val oemSteps = remember { OemBatterySetup.steps(ctx) }
    var sendTarget by remember { mutableStateOf<Peer?>(null) }
    var showMonitor by remember { mutableStateOf(ctx.container.prefs.showMonitor) }
    val setMonitor = { on: Boolean -> showMonitor = on; ctx.container.prefs.showMonitor = on }

    val style = Bridge.Style
    val tv = style == Style.THEATRE
    val bottomTabs = style != Style.THEATRE
    // The Theatre style's home runs its hero under the status bar, and the hero is always dark.
    // By the page mostly on screen, so the header changes look halfway through a swipe, not at its start.
    val heroUnderBar = tv && kotlin.math.round(pagePos).toInt() == TAB_HOME && !showOem
    // The header is drawn for the dark hero only while the hero is still under it; scrolled
    // past, it takes the page's own colours, or its tabs would vanish on light cards.
    val headerBottom = with(androidx.compose.ui.platform.LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TheatreHeaderHeight).toPx()
    }
    val homeList = lists[TAB_HOME]
    val heroShowing by remember(headerBottom) {
        derivedStateOf {
            val first = homeList.layoutInfo.visibleItemsInfo.firstOrNull()
            first == null || (first.key == "hero" && first.offset + first.size > headerBottom)
        }
    }
    val overHero = heroUnderBar && heroShowing

    // Status and navigation bar icons follow the palette, whichever way it was chosen.
    val dark = Bridge.Dark
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark && !overHero
            isAppearanceLightNavigationBars = !dark
        }
    }

    // A computer asking to connect is waiting on you, so jump to where the answer is.
    LaunchedEffect(requests.size) { if (requests.isNotEmpty()) { tab = TAB_HOME; showOem = false } }

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
    val requestPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setScreenshotClip(true)
        vm.refresh()
    }
    // The direct link needs "nearby devices" (Android 13+) or location (older) to start a hotspot.
    val requestNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startDirect() else Toast.makeText(ctx, "Localhost 8787 needs Nearby devices to start the direct link", Toast.LENGTH_LONG).show()
    }
    // In hotspot mode only the phone's own settings can switch the hotspot on or off.
    val openHotspot = {
        vm.tetherSettingsIntent()?.let { runCatching { openSettings.launch(it) } }
        Unit
    }
    val toggleDirect = {
        if (laptopLink.mode == "hotspot" && direct !is DirectLink.State.On) openHotspot()
        else when (direct) {
            is DirectLink.State.On, DirectLink.State.Starting -> vm.stopDirect()
            else -> {
                val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES
                else android.Manifest.permission.ACCESS_FINE_LOCATION
                if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startDirect()
                else requestNearby.launch(perm)
            }
        }
    }
    val toggleServer = { if (running) BridgeService.stop(ctx) else BridgeService.start(ctx) }

    // Other phones are looked for only while the Phones tab is open.
    if (tab == TAB_PHONES) {
        DisposableEffect(Unit) {
            peers.startDiscovery()
            onDispose { peers.stopDiscovery() }
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeUp = WindowInsets.isImeVisible
    // Glass: the content runs under a floating tab bar, which shows it through; plain: the bar
    // is docked below the content.
    val glass = LocalGlass.current
    val backdrop = rememberBackdrop()
    val barOver = glass && !imeUp && !showOem
    val underBar = if (barOver) GlassTabBarSpace + bottomInset else 0.dp

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Box(Modifier.fillMaxSize().background(Bridge.Bg)) {
        Column(Modifier.fillMaxSize().then(if (glass) Modifier.backdropSource(backdrop).background(Bridge.Bg) else Modifier)) {
            Header(if (tab == TAB_HOME) "BlazeIt" else TABS[tab].first, running, showMonitor) { setMonitor(!showMonitor) }
            // Room for the monitor pill, so by default it covers nothing.
            if (showMonitor) Spacer(Modifier.height(48.dp))

            Box(Modifier.weight(1f).imePadding()) {
                when {
                    showOem -> OemScreen(oemSteps, onOpen = { intent ->
                        runCatching { openSettings.launch(intent) }.onFailure {
                            Toast.makeText(ctx, "This phone would not open that screen", Toast.LENGTH_SHORT).show()
                        }
                    }, onDone = { showOem = false })

                    tab == TAB_CONTROL -> ControlPane(
                        running = running,
                        onStart = { BridgeService.start(ctx) },
                        modifier = Modifier.fillMaxSize().padding(bottom = underBar),
                    )

                    // Each tab keeps its own scroll position.
                    else -> key(tab) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + underBar)) {
                        when (tab) {
                            TAB_HOME -> homeTab(
                                state, running, direct, laptopLink, shared, clipStatus, requests, devices, live, vm,
                                transfers, files, sendStatus,
                                toggleDirect = toggleDirect,
                                pickFiles = { pickFiles.launch(arrayOf("*/*")) },
                                openTether = openHotspot,
                            ) {
                                if (running) BridgeService.stop(ctx) else BridgeService.start(ctx)
                            }
                            TAB_PHONES -> phonesTab(
                                running, transfers, nearby, paired, peerStatus, routes, phoneDirect,
                                setPhoneDirect = vm::setPhoneDirect,
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
                            else -> settingsTab(
                                state, vm, theme, look, laptopLink,
                                pickFolder = { pickFolder.launch(null) },
                                requestNotifications = { requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                                requestMusic = { requestMusic.launch(musicPermission()) },
                                requestPhotos = {
                                    requestPhotos.launch(
                                        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                                        else android.Manifest.permission.READ_EXTERNAL_STORAGE
                                    )
                                },
                                openSettings = { openSettings.launch(it) },
                                showOem = { showOem = true },
                            )
                        }
                    } }
                }
            }

            if (!glass && !imeUp && !showOem) TabBar(TABS, tab, bottomInset) { tab = it }
            else if (!imeUp && !barOver) Spacer(Modifier.height(bottomInset))
        }

        if (barOver) TabBar(TABS, tab, bottomInset, Modifier.align(Alignment.BottomCenter)) { tab = it }
        if (showMonitor) MonitorOverlay(monitor, running) { setMonitor(false) }
    }
    }
}

/** The permission that covers reading the music library on this Android version. */
private fun musicPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

// ---------------------------------------------------------------------- chrome

/** A large title, as Apple's apps begin a screen, and on the right the live monitor switch. */
@Composable
private fun Header(title: String, monitorOn: Boolean, toggleMonitor: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = LargeTitleStyle, color = Bridge.Text, modifier = Modifier.weight(1f))
        MonitorButton(monitorOn, toggleMonitor)
    }
}

@Composable
private fun MonitorButton(on: Boolean, toggle: () -> Unit) {
    val music = Bridge.Style != Style.THEATRE
    IconChip(
        BlazeIcons.Pulse, if (on) "Hide monitor" else "Show monitor",
        tint = when {
            on -> Bridge.OnAccent
            music -> Bridge.Accent
            else -> Bridge.Text
        },
        bg = if (on) Bridge.Accent else Bridge.Chip,
        size = 36.dp,
        onClick = toggle,
    )
}

/**
 * The Theatre style's header, floating over the top of the screen: the tabs as pills, the monitor
 * on the right. Over the home hero it is drawn in the dark palette, as the hero is dark; on
 * the other tabs it fades the content out under it.
 */
@Composable
private fun TheatreHeader(tab: Int, position: Float, overHero: Boolean, monitorOn: Boolean, onMonitor: () -> Unit, onSelect: (Int) -> Unit) {
    val palette = if (overHero) TheatreDark else LocalPalette.current
    CompositionLocalProvider(LocalPalette provides palette) {
        val bg = Bridge.Bg
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (overHero) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                    else Brush.verticalGradient(0.75f to bg, 1f to bg.copy(alpha = 0f))
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(TheatreHeaderHeight)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopTabs(TABS, tab, solid = !overHero, position = position, onSelect = onSelect)
            Spacer(Modifier.weight(1f))
            MonitorButton(monitorOn, onMonitor)
        }
    }
}

// ---------------------------------------------------------------------- tab: home

private fun LazyListScope.homeTab(
    state: UiState,
    running: Boolean,
    direct: DirectLink.State,
    laptopLink: MainViewModel.LaptopLink,
    shared: String,
    clipStatus: String,
    requests: List<PairRequest>,
    devices: List<PairedDevice>,
    live: Map<String, Int>,
    vm: MainViewModel,
    transfers: List<Transfer>,
    files: List<FileEntry>,
    sendStatus: String,
    toggleDirect: () -> Unit,
    pickFiles: () -> Unit,
    openTether: () -> Unit,
    onToggle: () -> Unit,
) {
    // Someone is asking to connect. It goes first: it is the one thing waiting on you.
    items(requests, key = { it.id }) { req -> RequestCard(req, vm) }

    item { ServerCard(state, running, onToggle, openTether) }

    item {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tile = Modifier.weight(1f).fillMaxHeight().heightIn(min = 124.dp)
            if (laptopLink.mode == "hotspot" && direct !is DirectLink.State.On) {
                Tile(
                    BlazeIcons.Hotspot, Bridge.Purple, "Hotspot",
                    when {
                        state.hotspotActive -> "On"
                        laptopLink.ssid.isBlank() -> "Set up in Settings"
                        else -> "Off"
                    },
                    tile,
                    active = state.hotspotActive,
                    onClick = toggleDirect,
                )
            } else Tile(
                BlazeIcons.Bolt, Bridge.Blue, "Direct link",
                when (direct) {
                    DirectLink.State.Off -> "Fastest, offline"
                    DirectLink.State.Starting -> "Starting…"
                    is DirectLink.State.On -> direct.info.ssid
                    is DirectLink.State.Failed -> direct.reason
                },
                tile,
                active = direct is DirectLink.State.On || direct == DirectLink.State.Starting,
                onClick = toggleDirect,
            )
            Tile(BlazeIcons.Upload, Bridge.Good, "Send files", sendStatus.ifEmpty { null }, tile, onClick = pickFiles)
        }
    }

    // The hotspot's details live in the phone's own settings, a tap on its tile away.
    if (direct is DirectLink.State.On) item { DirectCard(direct.info, toggleDirect) }

    item { Column { ClipboardPanel(shared, clipStatus, vm) } }

    transfersSection(transfers)

    item { SectionBar("On the phone") }
    item {
        GroupCard {
            if (files.isEmpty()) SettingRow("Nothing yet", first = true, titleColor = Bridge.Muted)
            files.forEachIndexed { i, f ->
                SettingRow(
                    f.name,
                    formatBytes(f.size) + " · " + (if (f.origin == "PHONE") "from this phone" else "received") +
                        (if (!f.owned) " · original" else ""),
                    first = i == 0,
                    icon = if (f.origin == "PHONE") BlazeIcons.Upload else BlazeIcons.Download,
                    iconColor = if (f.origin == "PHONE") Bridge.Good else Bridge.Orange,
                )
            }
        }
    }

    item {
        SectionBar("Connected") {
            if (live.isNotEmpty()) Text("${live.size} live", style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Lit)
        }
    }
    item {
        GroupCard {
            if (devices.isEmpty()) SettingRow("None yet", first = true, titleColor = Bridge.Muted)
            devices.forEachIndexed { i, d ->
                val isLive = (live[d.id] ?: 0) > 0
                val phone = d.name.startsWith("BlazeItPhone") || d.name.contains("phone", ignoreCase = true)
                MediaRow(
                    d.name, (if (isLive) "Live now · " else lastSeen(d.lastSeenAt) + " · ") + d.lastIp,
                    icon = if (phone) BlazeIcons.Phones else BlazeIcons.Laptop,
                    color = Color(0xFF5E5CE6),
                    dim = !isLive,
                    first = i == 0,
                ) {
                    IconChip(BlazeIcons.Close, "Remove ${d.name}", tint = Bridge.Muted, size = 32.dp) { vm.removeDevice(d.id) }
                }
            }
        }
    }
}

private data class Quick(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val detail: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

/**
 * The four things done most, as a shelf drawn the style's way: square artwork (Studio) or wide
 * cards (Theatre), each glowing a little in its own colour.
 */
@Composable
private fun QuickActions(quick: List<Quick>) {
    val tv = Bridge.Style == Style.THEATRE
    SectionBar("Quick actions")
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 14.dp),
    ) {
        items(quick, key = { it.title }) { q ->
            Tile(q.icon, q.color, q.title, q.detail, Modifier.width(if (tv) 208.dp else 128.dp), active = q.active, onClick = q.onClick)
        }
    }
}

/** A computer or phone asking to connect: who, the code to compare, Allow or Deny. */
@Composable
private fun RequestCard(req: PairRequest, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).panel().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(BlazeIcons.Laptop, Bridge.Purple)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${req.name} wants to connect", style = TitleStyle, color = Bridge.Text)
                Text(req.ip, style = CaptionStyle, color = Bridge.Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            req.code,
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 10.sp, fontFeatureSettings = "tnum"),
            color = Bridge.Text,
        )
        Text("Allow only if the other screen shows this code.", style = BodyStyle, color = Bridge.Muted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BridgeButton("Allow", Modifier.weight(1f)) { vm.approve(req.id) }
            BridgeButton("Deny", Modifier.weight(1f), color = Bridge.Chip, textColor = Bridge.Text) { vm.deny(req.id) }
        }
    }
}

/**
 * BlazeIt's state at a glance, the way a home-screen widget shows it: bright when on, with
 * the address to open in large type, and the switch right there.
 */
@Composable
private fun ServerCard(state: UiState, running: Boolean, onToggle: () -> Unit, openTether: () -> Unit) {
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    val url = state.primaryUrl
    val copy = {
        if (url != null) {
            SystemClipboard.write(ctx, url)
            Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
        }
    }
    val on = running
    val fg = if (on) Bridge.OnYellow else Bridge.Text
    val soft = if (on) Bridge.OnYellow.copy(alpha = 0.66f) else Bridge.Muted

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(horizontal = 16.dp)
            .card(TileShape, if (on) Bridge.Yellow else Bridge.Surface)
            .animateContentSize(tween(180))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !on -> "BlazeIt is off"
                    state.storageMode == Storage.Mode.NO_DESTINATION -> "Choose a folder"
                    url == null -> "No network"
                    else -> "Open on your computer"
                },
                style = TitleStyle.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), color = fg,
                modifier = Modifier.weight(1f),
            )
            Toggle(on, color = Color(0xFF15120A)) { onToggle() }
        }

        if (!on) return@Column
        when {
            state.storageMode == Storage.Mode.NO_DESTINATION -> RowNote("Set it in Settings, Receiving.", soft)
            url == null -> RowNote("Join Wi-Fi or start the direct link.", soft)
            state.onlyCellular -> RowNote("Mobile data can't be reached. Use the direct link or USB.", soft)
        }
        // The cable is in, but it carries nothing until USB tethering is on; Android lets only
        // the phone's own settings switch that.
        if (state.cableNoTether) {
            RowNote("Cable connected. Turn on USB tethering for full speed.", fg)
            Spacer(Modifier.height(10.dp))
            OnYellowPill("USB tethering", BlazeIcons.Bolt, openTether)
        }
        if (url == null) return@Column

        Spacer(Modifier.height(14.dp))
        Text(
            url.removePrefix("http://").removeSuffix("/"),
            style = DisplayStyle, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = copy),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnYellowPill("Copy", BlazeIcons.Copy, copy)
            OnYellowPill(if (showQr) "Hide code" else "QR code", BlazeIcons.Qr) { showQr = !showQr }
        }
        state.fasterLink?.let { usb ->
            RowNote("Faster over USB: " + usb.url(state.port).removePrefix("http://").removeSuffix("/"), fg)
        }
        if (showQr) {
            val qr = remember(url) { QrCode.render(url, 520) }
            if (qr != null) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qr,
                        contentDescription = "QR code for the address",
                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp),
                    )
                }
            }
            state.addresses.drop(1).filter { it.kind != dev.periy.bridge.net.LinkKind.DIRECT }.forEach { a ->
                Spacer(Modifier.height(10.dp))
                Text(a.kind.label + "  " + a.url(state.port), style = MonoStyle.copy(fontSize = 13.sp), color = Bridge.Text)
                Text(
                    when (a.reach) {
                        Reach.LAN_ONLY -> "same Wi-Fi or cable only"
                        Reach.CARRIER_NAT -> "unreachable: carrier NAT"
                        Reach.PUBLIC -> "public address"
                    },
                    style = BodyStyle.copy(fontSize = 12.sp), color = Bridge.Muted,
                )
            }
        }
    }
}

/**
 * The direct link while it is on: the network's name and password, the code a phone camera
 * joins from, and what the laptop does by itself.
 */
@Composable
private fun DirectCard(info: DirectLink.Info, stop: () -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).panel().animateContentSize(tween(180)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(BlazeIcons.Bolt, Bridge.Blue)
            Spacer(Modifier.width(12.dp))
            Text("Direct link", style = TitleStyle, color = Bridge.Text, modifier = Modifier.weight(1f))
            SoftButton("Stop", onClick = stop)
        }
        Spacer(Modifier.height(16.dp))
        CopyField("Network", info.ssid)
        Spacer(Modifier.height(8.dp))
        CopyField("Password", info.passphrase, shown = groupsOfFour(info.passphrase))
        if (info.host.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CopyField("Then open", "${info.host}:${info.port}")
        }
        Spacer(Modifier.height(12.dp))
        SoftButton(if (showQr) "Hide code" else "QR code", icon = BlazeIcons.Qr) { showQr = !showQr }
        if (showQr) {
            val qr = remember(info.qr) { QrCode.render(info.qr, 520) }
            if (qr != null) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qr, contentDescription = "QR code to join the direct link",
                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Android makes up the direct link's password and an app cannot pick a shorter one, so it
 * is at least shown in groups of four: "exrb a9cy rsny ab8" is easy to read out and type.
 */
private fun groupsOfFour(s: String): String = s.chunked(4).joinToString(" ")

/** A label and a value on a well; tap to copy the value (as it is, without display spacing). */
@Composable
private fun CopyField(label: String, value: String, shown: String = value) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Bridge.Chip)
            .clickable {
                SystemClipboard.write(ctx, value)
                Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LabelStyle, color = Bridge.Muted, modifier = Modifier.width(84.dp))
        Text(shown, style = MonoStyle.copy(fontSize = 15.sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(BlazeIcons.Copy, "Copy", tint = Bridge.Faint, modifier = Modifier.size(17.dp))
    }
}

/** Anything moving right now, either way, with progress: rows like songs, with a scrubber under each. */
private fun LazyListScope.transfersSection(transfers: List<Transfer>) {
    if (transfers.isEmpty()) return
    item {
        SectionBar("Moving") {
            Text(
                "Clear finished", style = LabelStyle.copy(fontSize = 15.sp),
                color = if (Bridge.Style == Style.STUDIO) Bridge.Accent else Bridge.Blue,
                modifier = Modifier.clip(ButtonShape).clickable { Transfers.clearFinished() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    item { GroupCard { transfers.forEachIndexed { i, t -> TransferRow(t, first = i == 0) } } }
}

/** A picture decoded no larger than [px] on its long side: a phone photo is too big to hold whole. */
private fun decodeSampled(path: String, px: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/**
 * The shared clipboard: one thing at a time, the same on the phone and the computer. Typing
 * here reaches the computer by itself a moment after you stop; a picture or file copied on
 * either side shows here too, and what the computer copies is on this phone's own clipboard
 * already, so there are no Paste and Copy buttons. Clear empties it everywhere; the clock opens
 * the history of recent items, to put one back or remove it.
 */
@Composable
private fun ClipboardPanel(shared: String, status: String, vm: MainViewModel) {
    val meta by vm.clipMeta.collectAsStateWithLifecycle()
    val history by vm.clipHistory.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(shared) }
    // True between a keystroke and the moment it is published; incoming text waits till then.
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(shared) { if (!pending) draft = shared }
    LaunchedEffect(draft, pending) {
        if (!pending) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        vm.sendClipboard(draft)
        pending = false
    }
    val picture by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, meta.v, meta.kind) {
        value = if (meta.kind != "image") null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.clipFile()?.let { f -> decodeSampled(f.path, 1080)?.asImageBitmap() }
        }
    }

    // History and Clear sit by the title and the history opens under it, as on the laptop page.
    SectionBar("Clipboard") {
        HeaderAction(BlazeIcons.History, "History", lit = showHistory) { showHistory = !showHistory }
        Spacer(Modifier.width(8.dp))
        HeaderAction(BlazeIcons.Trash, "Clear", tint = Bridge.Danger) { vm.clearClipboard(); draft = ""; pending = false }
    }
    Column(Modifier.fillMaxWidth().panel().padding(16.dp)) {
        if (showHistory) {
            ClipHistory(history, current = meta.v, vm = vm)
            Spacer(Modifier.height(12.dp))
        }
        when (meta.kind) {
            "image", "file" -> Column(Modifier.fillMaxWidth()) {
                picture?.let {
                    Image(
                        it, meta.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (picture == null) {
                        AppIcon(if (meta.kind == "image") BlazeIcons.Image else BlazeIcons.File, if (meta.kind == "image") Bridge.Purple else Bridge.Orange)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(meta.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (meta.kind == "image") "Picture" else "File") + " · " + formatBytes(meta.size),
                            style = CaptionStyle, color = Bridge.Muted)
                    }
                }
            }
            else -> Box(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(horizontal = 4.dp, vertical = 2.dp)) {
                if (draft.isEmpty()) Text("Type or paste anything", style = BodyStyle.copy(fontSize = 16.sp), color = Bridge.Faint)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; pending = true },
                    textStyle = BodyStyle.copy(fontSize = 16.sp, lineHeight = 22.sp, color = Bridge.Text),
                    cursorBrush = SolidColor(if (Bridge.Style == Style.STUDIO) Bridge.Accent else Bridge.Blue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = LabelStyle, color = Bridge.Good, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * Recent clipboard items, newest first, like a keyboard's clipboard: tap one to put it back
 * (on the computer too), the cross to remove it.
 */
@Composable
private fun ClipHistory(items: List<dev.periy.bridge.server.ClipMeta>, current: Long, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (items.isEmpty()) {
            Text("Nothing copied yet.", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(4.dp))
            return@Column
        }
        items.forEach { m ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (m.v == current) Bridge.Accent.copy(alpha = 0.16f) else Bridge.Chip)
                    .clickable { vm.reuseClip(m.v) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (m.kind) {
                    "image" -> ClipThumb(vm, m)
                    "file" -> AppIcon(BlazeIcons.File, Bridge.Orange, size = 34.dp)
                    else -> {}
                }
                if (m.kind != "text") Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m.kind == "text") m.text.trim().replace(Regex("\\s+"), " ") else m.name,
                        style = BodyStyle.copy(fontSize = 14.sp), color = Bridge.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (m.kind == "text") "" else formatBytes(m.size) + " · ") + ago(m.at) + (if (m.v == current) " · now on the clipboard" else ""),
                        style = BodyStyle.copy(fontSize = 12.sp), color = Bridge.Muted,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).clickable { vm.forgetClip(m.v) }, contentAlignment = Alignment.Center) {
                    Icon(BlazeIcons.Close, "Remove", tint = Bridge.Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            "Clear history", style = LabelStyle, color = Bridge.Danger,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.forgetAllClips() }.padding(4.dp),
        )
    }
}

/** A small square of a picture in the history. */
@Composable
private fun ClipThumb(vm: MainViewModel, m: dev.periy.bridge.server.ClipMeta) {
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.v) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.historyFile(m.v)?.let { f -> decodeSampled(f.path, 160)?.asImageBitmap() }
        }
    }
    val b = bmp
    if (b == null) AppIcon(BlazeIcons.Image, Bridge.Purple, size = 34.dp)
    else Image(b, m.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
}

/** "just now", "5 min ago", "3 h ago", or the date. */
private fun ago(at: Long): String {
    if (at <= 0) return ""
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))
    }
}

// ---------------------------------------------------------------------- tab: phones

private fun LazyListScope.phonesTab(
    running: Boolean,
    transfers: List<Transfer>,
    nearby: List<NearbyPhone>,
    paired: List<Peer>,
    peerStatus: Map<String, PeerStatus>,
    routes: Map<String, String>,
    phoneDirect: Boolean,
    setPhoneDirect: (Boolean) -> Unit,
    connect: (NearbyPhone) -> Unit,
    forget: (Peer) -> Unit,
    sendFilesTo: (Peer) -> Unit,
    sendTextTo: (Peer) -> Unit,
) {
    val pairedNames = paired.map { it.name }.toSet()
    val unpaired = nearby.filter { it.name !in pairedNames }
    item { Searching(running, found = paired.size + unpaired.size) }

    item {
        GroupCard(Modifier.padding(top = 12.dp)) {
            SettingRow(
                "Send over a direct link", "Phone to phone, many times faster",
                first = true, icon = BlazeIcons.Bolt, iconColor = Bridge.Blue,
            ) { Toggle(phoneDirect) { setPhoneDirect(it) } }
        }
    }

    if (paired.isNotEmpty()) {
        item { SectionBar("My phones") }
        item {
            GroupCard {
                paired.forEachIndexed { i, p ->
                    val here = nearby.any { it.name == p.name }
                    MediaRow(p.name, routes[p.name] ?: if (here) "Nearby · ready" else "Not seen right now", BlazeIcons.Phones, Color(0xFF30D158), first = i == 0, dim = !here) {
                        IconChip(BlazeIcons.Upload, "Send files to ${p.name}", tint = Bridge.OnAccent, bg = Bridge.Accent, size = 36.dp) { sendFilesTo(p) }
                        Spacer(Modifier.width(8.dp))
                        IconChip(BlazeIcons.Message, "Send text to ${p.name}", size = 36.dp) { sendTextTo(p) }
                        Spacer(Modifier.width(8.dp))
                        IconChip(BlazeIcons.Close, "Forget ${p.name}", tint = Bridge.Muted, size = 36.dp) { forget(p) }
                    }
                }
            }
        }
    }
    if (unpaired.isNotEmpty()) {
        item { SectionBar("Nearby") }
        item {
            GroupCard {
                unpaired.forEachIndexed { i, n ->
                    val s = peerStatus[n.host]
                    MediaRow(
                        n.name,
                        when (s) {
                            is PeerStatus.Waiting -> "Allow it on ${n.name} · code ${s.code}"
                            is PeerStatus.Failed -> s.message
                            null -> n.host
                        },
                        BlazeIcons.Phones, Color(0xFF30D158),
                        first = i == 0,
                    ) {
                        if (s !is PeerStatus.Waiting) SoftButton("Connect") { connect(n) }
                    }
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
                color = if (st is PeerStatus.Failed) Bridge.Danger else Bridge.Text,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }
    }
    item { Spacer(Modifier.height(12.dp)) }
    item { ConnectByAddress(connect) }

    transfersSection(transfers)
}

/** The top of the Phones tab: what is going on, with a soft pulse while it looks. */
@Composable
private fun Searching(running: Boolean, found: Int) {
    val pulse by rememberInfiniteTransition(label = "look").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).panel().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(BlazeIcons.Phones, Color(0xFF30D158), Modifier.size(56.dp), radius = 12.dp, glyph = 28.dp, center = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !running -> "Localhost 8787 is off"
                    found == 0 -> "Looking for phones"
                    found == 1 -> "1 phone"
                    else -> "$found phones"
                },
                style = HeadlineStyle.copy(fontSize = 20.sp), color = Bridge.Text,
            )
            Text(
                if (!running) "Turn it on from Home" else "On this Wi-Fi",
                style = CaptionStyle, color = Bridge.Muted,
            )
        }
        if (running) Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(Bridge.Lit))
    }
}

/**
 * For networks where phones cannot see each other (some routers block discovery): type the
 * address the other phone shows on its Home screen.
 */
@Composable
private fun ConnectByAddress(connect: (NearbyPhone) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    GroupCard {
        if (!open) {
            SettingRow(
                "Connect by address", first = true, icon = BlazeIcons.Link, iconColor = Bridge.Orange, onClick = { open = true },
            ) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
            return@GroupCard
        }
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BridgeTextField(text, { text = it }, Modifier.weight(1f), placeholder = "192.168.1.20", minHeight = 46.dp, mono = true)
            Spacer(Modifier.width(8.dp))
            BridgeButton("Connect") {
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
private fun TransferRow(t: Transfer, first: Boolean) {
    val inbound = t.direction == Direction.INBOUND
    MediaRow(
        title = t.name,
        subtitle = null,
        icon = if (inbound) BlazeIcons.Download else BlazeIcons.Upload,
        color = if (inbound) Color(0xFFFF9F0A) else Color(0xFF30D158),
        first = first,
        below = {
            Spacer(Modifier.height(6.dp))
            BlockProgress(
                t.fraction,
                color = when (t.state) {
                    TransferState.DONE -> Bridge.Good
                    TransferState.FAILED -> Bridge.Danger
                    TransferState.STALLED -> Bridge.Faint
                    TransferState.ACTIVE -> if (Bridge.Style == Style.STUDIO) Bridge.Accent else Bridge.Text
                },
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    formatBytes(t.transferred) + " of " + formatBytes(t.total),
                    style = BodyStyle.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                    color = if (t.state == TransferState.FAILED) Bridge.Danger else Bridge.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (t.state) {
                        TransferState.ACTIVE -> formatRate(t.bytesPerSec)
                        TransferState.STALLED -> "Paused"
                        TransferState.DONE -> "Done"
                        TransferState.FAILED -> "Failed"
                    },
                    style = BodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = if (t.state == TransferState.DONE) Bridge.Good else Bridge.Muted,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------- tab: settings

/**
 * The direct link's switch, under the choice in Settings: whether it is on, and why not when
 * it failed. Android runs one hotspot at a time, so while the phone's own is on, this says so
 * and offers the setting that turns it off.
 */
@Composable
private fun DirectRow(direct: DirectLink.State, hotspotOn: Boolean, toggle: () -> Unit, openHotspot: () -> Unit) {
    val blocked = hotspotOn && direct !is DirectLink.State.On && direct != DirectLink.State.Starting
    SettingRow(
        when (direct) {
            is DirectLink.State.On -> "Direct link on"
            DirectLink.State.Starting -> "Starting the direct link…"
            else -> "Direct link off"
        },
        when {
            blocked -> "The phone's hotspot is on. Turn it off first: Android runs one at a time."
            direct is DirectLink.State.Failed -> direct.reason
            direct is DirectLink.State.On -> direct.info.ssid + " · the laptop helper joins by itself"
            else -> "The laptop helper joins it by itself; the laptop is offline meanwhile"
        },
        titleColor = if (direct is DirectLink.State.On) Bridge.Lit else Bridge.Text,
        onClick = if (blocked) openHotspot else toggle,
    ) {
        Action(
            when {
                blocked -> "Hotspot"
                direct is DirectLink.State.On || direct == DirectLink.State.Starting -> "Stop"
                direct is DirectLink.State.Failed -> "Retry"
                else -> "Start"
            }
        )
    }
}

private fun LazyListScope.settingsTab(
    state: UiState,
    vm: MainViewModel,
    theme: String,
    look: dev.periy.bridge.Look,
    laptopLink: MainViewModel.LaptopLink,
    pickFolder: () -> Unit,
    requestNotifications: () -> Unit,
    requestMusic: () -> Unit,
    requestPhotos: () -> Unit,
    openSettings: (Intent) -> Unit,
    showOem: () -> Unit,
) {
    item { SectionBar("Appearance") }
    item {
        // Shared with every open page.
        StylePicker(Style.of(look.style)) { vm.setStyle(it) }
    }
    item { ColourPicker(look.accent) { vm.setAccent(it) } }
    item {
        Box(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            val themes = listOf("system", "light", "dark")
            SegmentedRow(listOf("Automatic", "Light", "Dark"), themes.indexOf(theme)) { vm.setTheme(themes[it]) }
        }
    }

    item { SectionBar("Receiving") }
    item {
        GroupCard {
            SettingRow(
                "Save files to",
                (state.destination ?: "Not chosen yet") + when (state.storageMode) {
                    Storage.Mode.DIRECT_SEEK -> if (state.freeSpace > 0) " · " + formatBytes(state.freeSpace) + " free" else ""
                    Storage.Mode.STAGED_COPY -> " · staged"
                    Storage.Mode.NO_DESTINATION -> ""
                },
                first = true, icon = BlazeIcons.File, iconColor = Bridge.Orange,
                onClick = pickFolder,
            ) { Action("Change") }
            SettingRow("Always stage in app storage", "Slower; only if files arrive damaged") {
                Toggle(state.forceStagedCopy) { vm.setForceStagedCopy(it) }
            }
        }
    }

    item { SectionBar("Speed") }
    item {
        GroupCard {
            SettingRow(
                "Laptop link",
                if (laptopLink.mode == "hotspot") "Keeps the laptop's internet · 44–66 MB/s"
                else "Fastest, offline · 55–115 MB/s",
                first = true,
                icon = if (laptopLink.mode == "hotspot") BlazeIcons.Hotspot else BlazeIcons.Bolt,
                iconColor = if (laptopLink.mode == "hotspot") Bridge.Purple else Bridge.Blue,
            )
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                val modes = listOf("direct", "hotspot")
                SegmentedRow(listOf("Direct link", "Hotspot"), modes.indexOf(laptopLink.mode)) { vm.setLaptopLink(mode = modes[it]) }
            }
            if (laptopLink.mode == "hotspot") HotspotFields(laptopLink, vm) { vm.tetherSettingsIntent()?.let(openSettings) }
            SettingRow("Connections per file")
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                SegmentedRow(listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(state.uploadStreams)) {
                    vm.setUploadStreams(listOf(1, 2, 4, 8)[it])
                }
            }
            SettingRow(
                "USB-C cable", "Needs USB tethering on",
                onClick = { vm.tetherSettingsIntent()?.let(openSettings) },
            ) { Action("Set up") }
        }
    }

    item { SectionBar("Laptop access") }
    item {
        GroupCard {
            SettingRow(
                "Browse this phone",
                if (Build.VERSION.SDK_INT < 30) "Needs Android 11" else "Read-only",
                first = true, icon = BlazeIcons.File, iconColor = Bridge.Orange,
                onClick = { vm.allFilesIntent()?.let(openSettings) },
            ) { if (state.browsable) Check(true) else Action("Allow") }
            SettingRow("Sync clipboard", icon = BlazeIcons.Paste, iconColor = Bridge.Teal) {
                Toggle(state.clipSync) { vm.setClipSync(it) }
            }
            SettingRow(
                "Laptop videos here", "From the Play on phone bookmark",
                icon = BlazeIcons.PlayPause, iconColor = Bridge.Danger,
            ) { Toggle(state.videoPip) { vm.setVideoPip(it) } }
            SettingRow(
                "Notifications on the laptop",
                if (state.notifAccess) null else "Needs notification access",
                icon = BlazeIcons.Message, iconColor = Bridge.Danger,
                onClick = { openSettings(vm.notifAccessIntent()) },
            ) { if (state.notifAccess) Check(true) else Action("Allow") }
            SettingRow(
                "Copies from any app, at once",
                when {
                    !state.watchLogs -> "One-time, over USB: adb shell pm grant ${LocalContext.current.packageName} android.permission.READ_LOGS"
                    !state.watchOverlay -> "Needs Display over other apps"
                    else -> null
                },
                icon = BlazeIcons.Copy, iconColor = Bridge.Blue,
                onClick = if (state.watchLogs && !state.watchOverlay) ({ openSettings(vm.overlayIntent()) }) else null,
            ) {
                when {
                    state.watchLogs && state.watchOverlay -> Check(true)
                    state.watchLogs -> Action("Allow")
                    else -> Text("USB", style = LabelStyle, color = Bridge.Muted)
                }
            }
            SettingRow(
                "Screenshots to the laptop",
                if (state.screenshotClip && !state.canReadPhotos) "Needs photo access" else null,
                icon = BlazeIcons.Image, iconColor = Bridge.Purple,
            ) {
                Toggle(state.clipSync && state.screenshotClip && state.canReadPhotos) { on ->
                    if (on && !state.canReadPhotos) requestPhotos() else vm.setScreenshotClip(on)
                }
            }
        }
    }

    item { SectionBar("Music") }
    item {
        GroupCard {
            if (state.musicGranted) {
                SettingRow(
                    if (state.musicTracks > 0) "${state.musicTracks} songs" else "No music found",
                    first = true, icon = BlazeIcons.Pulse, iconColor = Bridge.Pink,
                )
            } else {
                SettingRow(
                    "Music library", "Audio only",
                    first = true, icon = BlazeIcons.Pulse, iconColor = Bridge.Pink, onClick = requestMusic,
                ) { Action("Allow") }
            }
            // Albums without a cover of their own get one from Apple's catalogue; only the
            // album and artist names are sent.
            val prefs = LocalContext.current.container.prefs
            var lookup by remember { mutableStateOf(prefs.coverLookup) }
            SettingRow("Find missing covers", "Looks up album and artist in Apple's catalogue", icon = BlazeIcons.Image, iconColor = Bridge.Purple) {
                Toggle(lookup) { lookup = it; prefs.coverLookup = it }
            }
        }
    }

    item { SectionBar("Keep running") }
    item {
        GroupCard {
            SettingRow(
                "Notifications", if (state.notificationsGranted) null else "Needed to stay running",
                first = true,
                onClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationsGranted) requestNotifications else null,
            ) { Check(state.notificationsGranted) }
            SettingRow(
                "Battery optimisation", if (state.batteryExempt) "Off" else "Tap to turn off",
                onClick = if (!state.batteryExempt) ({ vm.batteryOptimizationIntent()?.let(openSettings) ?: showOem() }) else null,
            ) { Check(state.batteryExempt) }
            SettingRow("Manufacturer settings", "If Localhost 8787 gets stopped", onClick = showOem) {
                Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
            }
        }
    }

    item { SectionBar("Pairing") }
    item {
        GroupCard {
            SettingRow("Unpair everything", first = true, titleColor = Bridge.Danger, onClick = { vm.unpairAll() })
        }
    }
}

/** A word on the right of a setting that does something: in the accent (Studio) or blue. */
@Composable
private fun Action(label: String) {
    Text(label, style = TextStyle(fontSize = 16.sp), color = if (Bridge.Style == Style.THEATRE) Bridge.Blue else Bridge.Accent)
}

/**
 * The two styles side by side, each drawn as a small phone showing it, in the light or dark
 * of the moment, with a round check under the one in use: the way iOS picks Light or Dark.
 */
@Composable
private fun StylePicker(current: Style, onPick: (String) -> Unit) {
    val dark = Bridge.Dark
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Style.STUDIO to "Studio", Style.THEATRE to "Theatre").forEach { (s, label) ->
            val on = s == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(s.name.lowercase()) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ring = if (on) (if (Bridge.Style == Style.THEATRE) Bridge.Blue else Bridge.Accent) else Bridge.Outline
                Box(Modifier.fillMaxWidth().aspectRatio(0.56f).clip(RoundedCornerShape(16.dp)).border(if (on) 2.5.dp else 1.dp, ring, RoundedCornerShape(16.dp)).padding(if (on) 4.dp else 1.dp)) {
                    StylePreview(s, dark)
                }
                Spacer(Modifier.height(8.dp))
                Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Bridge.Text)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (on) ring else Color.Transparent)
                        .border(1.5.dp, if (on) ring else Bridge.Faint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(BlazeIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

/**
 * The colour: a row of round swatches, the one in use ringed. The first is the style's own
 * colour (Studio's pink-red, Theatre in black and white), drawn half and half.
 */
@Composable
private fun ColourPicker(current: String, onPick: (String) -> Unit) {
    val name = if (current == "auto") "Automatic" else current.replaceFirstChar { it.uppercase() }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Colour", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, modifier = Modifier.weight(1f))
            Text(name, style = TextStyle(fontSize = 15.sp), color = Bridge.Muted)
        }
        Spacer(Modifier.height(10.dp))
        // All on one line, sharing the width: Automatic (black and white, as Theatre), then the colours.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(
                Brush.linearGradient(0.5f to Color.White, 0.5f to Color(0xFF1D1D1F)),
                "Automatic", current == "auto", Modifier.weight(1f),
            ) { onPick("auto") }
            ACCENTS.forEach { (id, c) ->
                Swatch(SolidColor(c), id, current == id, Modifier.weight(1f)) { onPick(id) }
            }
        }
    }
}

@Composable
private fun Swatch(fill: androidx.compose.ui.graphics.Brush, description: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.widthIn(max = 44.dp).aspectRatio(1f).clip(CircleShape)
            .border(if (on) 2.5.dp else 0.dp, if (on) Bridge.Text else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(if (on) 5.dp else 2.dp)
            .clip(CircleShape)
            .background(fill)
            // Half black, half white: a fine ring keeps its edge on either background.
            .then(if (description == "Automatic") Modifier.border(1.dp, Color(0x5A8E8E93), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(
            BlazeIcons.Check, description, modifier = Modifier.size(16.dp),
            // Over the white half of Automatic, and over yellow, a white tick would not show.
            tint = if (description == "Automatic" || description == "yellow") Color(0xFF1D1D1F) else Color.White,
        )
    }
}

/** A tiny phone screen in a style: enough of its shapes to recognise it. */
@Composable
private fun StylePreview(style: Style, dark: Boolean) {
    // The colour in use, read before this preview puts its own palette in place: Studio's card shows it.
    val chosen = Bridge.Accent
    val card = if (chosen == Color.White || chosen == Color(0xFF1D1D1F)) Color(0xFF15428C) else chosen
    val p = paletteFor(style, dark)
    CompositionLocalProvider(LocalPalette provides p, LocalStyle provides style) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            StyleBackground()
            val bar = @Composable { w: Float, h: Dp, c: Color -> Box(Modifier.fillMaxWidth(w).height(h).clip(RoundedCornerShape(2.dp)).background(c)) }
            when (style) {
                Style.STUDIO -> Column(Modifier.fillMaxSize().padding(7.dp)) {
                    Spacer(Modifier.height(6.dp))
                    bar(0.55f, 7.dp, p.text)
                    Spacer(Modifier.height(7.dp))
                    Artwork(BlazeIcons.Bolt, card, Modifier.fillMaxWidth().weight(1f), radius = 5.dp, glyph = 0.dp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A84FF)))
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF30D158)))
                    }
                    Spacer(Modifier.height(6.dp))
                    bar(1f, 3.dp, p.surface2)
                    Spacer(Modifier.height(4.dp))
                    bar(0.8f, 3.dp, p.surface2)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(p.surface)) {
                        Box(Modifier.padding(3.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(4) { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == 0) p.accent else p.faint)) }
                    }
                }

                Style.THEATRE -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1.1f)
                            .background(Brush.linearGradient(listOf(Color(0xFF1E6BD6), Color(0xFF0B2F66), Color(0xFF05070D))))
                    ) {
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.width(16.dp).height(6.dp).clip(ButtonShape).background(Color.White))
                            Box(Modifier.width(12.dp).height(6.dp).clip(ButtonShape).background(Color.White.copy(alpha = 0.25f)))
                            Box(Modifier.width(12.dp).height(6.dp).clip(ButtonShape).background(Color.White.copy(alpha = 0.25f)))
                        }
                        Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                            Box(Modifier.width(40.dp).height(6.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.width(26.dp).height(9.dp).clip(ButtonShape).background(Color.White))
                        }
                    }
                    Column(Modifier.padding(7.dp).weight(1f)) {
                        bar(0.4f, 4.dp, p.text)
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(3.dp)).background(Color(0xFF0A84FF)))
                            Box(Modifier.weight(0.5f).aspectRatio(8f / 9f).clip(RoundedCornerShape(3.dp)).background(Color(0xFF30D158)))
                        }
                        Spacer(Modifier.height(6.dp))
                        bar(0.4f, 4.dp, p.text)
                        Spacer(Modifier.height(5.dp))
                        bar(1f, 14.dp, p.surface)
                    }
                }

            }
        }
    }
}

/**
 * The hotspot's name and password, as set in the phone's hotspot settings. Android keeps
 * them from apps, so they are typed here once; the laptop helper needs them to join.
 */
@Composable
private fun HotspotFields(link: MainViewModel.LaptopLink, vm: MainViewModel, openHotspot: () -> Unit) {
    var ssid by remember { mutableStateOf(link.ssid) }
    var pass by remember { mutableStateOf(link.pass) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        BridgeTextField(ssid, { ssid = it; vm.setLaptopLink(ssid = it) }, placeholder = "Hotspot name", minHeight = 46.dp, mono = true)
        Spacer(Modifier.height(8.dp))
        BridgeTextField(pass, { pass = it; vm.setLaptopLink(pass = it) }, placeholder = "Password", minHeight = 46.dp, mono = true)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "As in the hotspot settings",
                style = CaptionStyle, color = Bridge.Muted, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            SoftButton("Hotspot settings", onClick = openHotspot)
        }
    }
}

@Composable
private fun Check(ok: Boolean) {
    if (ok) Icon(BlazeIcons.Check, "Done", tint = Bridge.Good, modifier = Modifier.size(20.dp))
    else Text("!", style = TitleStyle, color = Bridge.Orange)
}

// ---------------------------------------------------------------------- oem screen

@Composable
private fun OemScreen(
    steps: List<OemBatterySetup.Step>,
    modifier: Modifier,
    onOpen: (Intent) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionBar("Keep Localhost 8787 running") }
        items(steps) { step ->
            BridgeRow(step.title) {
                RowNote(step.detail)
                Spacer(Modifier.height(10.dp))
                SoftButton("Open") { onOpen(step.intent) }
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
