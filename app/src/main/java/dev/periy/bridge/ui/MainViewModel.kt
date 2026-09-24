package dev.periy.bridge.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.periy.bridge.container
import dev.periy.bridge.net.Address
import dev.periy.bridge.net.LinkEstimate
import dev.periy.bridge.net.NetInfo
import dev.periy.bridge.server.PairRequest
import dev.periy.bridge.server.PairedDevice
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.SystemClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val addresses: List<Address> = emptyList(),
    val port: Int = 8787,
    val storageMode: Storage.Mode = Storage.Mode.NO_DESTINATION,
    val destination: String? = null,
    val freeSpace: Long = -1,
    val maxUploadSize: Long = 0,
    val forceStagedCopy: Boolean = false,
    val notificationsGranted: Boolean = true,
    val batteryExempt: Boolean = false,
    val deviceName: String = "",
    val uploadStreams: Int = 4,
    val estimate: LinkEstimate? = null,
    /** A USB link is present but is not the address being advertised. */
    val fasterLink: Address? = null,
    /** The phone is running its own access point, so a computer can join it directly. */
    val hotspotActive: Boolean = false,
    /** Mobile data is the only route out, so nothing can reach this phone. */
    val onlyCellular: Boolean = false,
    /** Music permission granted, and how many tracks the library holds. */
    val musicGranted: Boolean = false,
    val musicTracks: Int = 0,
) {
    val primaryUrl: String? get() = addresses.firstOrNull()?.url(port)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAddresses()
        override fun onLost(network: Network) = refreshAddresses()
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        refresh()
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    /** Full refresh. Called on resume, because permissions can change while we are away. */
    fun refresh() {
        val app = getApplication<Application>()
        val prefs = app.container.prefs
        viewModelScope.launch {
            val storageInfo = withContext(Dispatchers.IO) {
                app.container.storage.refresh()
                Triple(
                    app.container.storage.mode,
                    app.container.storage.destinationLabel,
                    app.container.storage.freeSpaceBytes(),
                )
            }
            val addrs = withContext(Dispatchers.IO) { NetInfo.addresses() }
            _state.value = _state.value.copy(
                addresses = addrs,
                port = prefs.port,
                storageMode = storageInfo.first,
                destination = storageInfo.second,
                freeSpace = storageInfo.third,
                maxUploadSize = prefs.tusMaxSize,
                forceStagedCopy = prefs.forceStagedCopy,
                notificationsGranted = notificationsGranted(),
                batteryExempt = OemBatterySetup.isIgnoringBatteryOptimizations(app),
                deviceName = app.container.deviceName(),
                uploadStreams = prefs.uploadStreams,
                estimate = withContext(Dispatchers.IO) { NetInfo.estimate(app) },
                fasterLink = NetInfo.fasterLinkAvailable(addrs.firstOrNull()),
                onlyCellular = NetInfo.onlyCellular(),
                hotspotActive = NetInfo.hotspotActive(),
                musicGranted = app.container.music.granted(),
                musicTracks = withContext(Dispatchers.IO) { app.container.music.tracks(refresh = true).size },
            )
        }
    }

    private fun refreshAddresses() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val addrs = withContext(Dispatchers.IO) { NetInfo.addresses() }
            _state.value = _state.value.copy(
                addresses = addrs,
                estimate = withContext(Dispatchers.IO) { NetInfo.estimate(app) },
                fasterLink = NetInfo.fasterLinkAvailable(addrs.firstOrNull()),
                onlyCellular = NetInfo.onlyCellular(),
                hotspotActive = NetInfo.hotspotActive(),
            )
        }
    }

    private fun notificationsGranted(): Boolean {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------ actions

    /**
     * Persists the folder the user picked, and takes the permission across reboots.
     *
     * Without takePersistableUriPermission the grant dies with the activity, and the
     * failure mode is silent: the first upload after a restart fails at finalisation,
     * having already spent an hour moving bytes.
     */
    fun setDestination(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        app.container.prefs.treeUri = uri
        refresh()
    }

    // ------------------------------------------------------------------ connected computers

    /** Computers waiting for a tap on this phone. */
    val pairRequests: StateFlow<List<PairRequest>>
        get() = getApplication<Application>().container.pairing.pending

    /** The shared appearance: the app and every connected page follow it. */
    val glass: StateFlow<Boolean>
        get() = getApplication<Application>().container.glass

    fun setGlass(on: Boolean) = getApplication<Application>().container.setGlass(on)

    /** Every computer that has been allowed in. */
    val devices: StateFlow<List<PairedDevice>>
        get() = getApplication<Application>().container.devices.devices

    /** Device id -> open connections; present means the page is open right now. */
    val liveDevices: StateFlow<Map<String, Int>>
        get() = getApplication<Application>().container.devices.live

    fun approve(requestId: String) = getApplication<Application>().container.pairing.approve(requestId)

    fun deny(requestId: String) = getApplication<Application>().container.pairing.deny(requestId)

    /** Locks one computer out, effective on its very next request. */
    fun removeDevice(id: String) = getApplication<Application>().container.devices.remove(id)

    /**
     * Locks every computer out. Clearing the list is enough on its own; rotating the key as
     * well means even a cookie copied off a computer before today is worthless.
     */
    fun unpairAll() {
        val app = getApplication<Application>()
        app.container.devices.clear()
        app.container.prefs.rotateSessionKey()
        refresh()
    }

    fun setMaxUploadSize(bytes: Long) {
        getApplication<Application>().container.prefs.tusMaxSize = bytes
        refresh()
    }

    fun setForceStagedCopy(force: Boolean) {
        getApplication<Application>().container.prefs.forceStagedCopy = force
        refresh()
    }

    /**
     * Default parallel connection count offered to the browser. The page may override it
     * locally; this is what a freshly opened tab starts from.
     */
    fun setUploadStreams(n: Int) {
        getApplication<Application>().container.prefs.uploadStreams = n
        refresh()
    }

    /**
     * The direct battery-exemption prompt, or null when this ROM will not surface it.
     * Exposed from here because `getApplication()` is protected and the UI has no other
     * handle on an Application context.
     */
    fun batteryOptimizationIntent(): Intent? =
        OemBatterySetup.requestIgnoreBatteryOptimizations(getApplication())

    /**
     * Opens the hotspot / tethering settings screen.
     *
     * There is no public action for this, and vendors move it, so several candidates are
     * tried in order and each is resolved before use -- the same approach the OEM
     * battery walkthrough takes, for the same reason: launching a vendor activity that
     * no longer exists is an instant crash.
     */
    fun tetherSettingsIntent(): Intent? {
        val app = getApplication<Application>()
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$TetherSettingsActivity",
            ),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        return candidates.firstOrNull { intent ->
            runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------ phone to PC

    /** Files the computer can download, newest first. */
    val files: StateFlow<List<dev.periy.bridge.server.FileEntry>>
        get() = getApplication<Application>().container.index.flow

    private val _sendStatus = MutableStateFlow("")
    val sendStatus: StateFlow<String> = _sendStatus

    /**
     * Publishes files the user picked with the system document picker.
     *
     * Nothing is copied. The picker is the one source that grants a permission we can
     * keep, so the file is served straight from where it already lives -- which is why
     * offering a 4 GB video costs nothing and is instant.
     */
    fun offerPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            var added = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        app.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    runCatching {
                        app.container.index.add(app.container.storage.adopt(uri))
                        added++
                    }
                }
            }
            _sendStatus.value = if (added == 0) "Could not read those files"
            else "$added file${if (added == 1) "" else "s"} ready on the computer"
            clearSendStatusLater()
        }
    }

    /**
     * Takes files handed over by another app through the share sheet.
     *
     * These have to be copied, unlike picked files. A share grant is scoped to the task
     * that received it and Android revokes it afterwards, so serving the original later
     * would fail silently once the grant expired. The copy happens while the grant is
     * still alive.
     */
    fun acceptSharedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        if (!app.container.storage.hasDestination()) {
            _sendStatus.value = "Choose a destination folder first"
            clearSendStatusLater()
            return
        }
        viewModelScope.launch {
            var added = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        val id = "share-" + System.nanoTime()
                        app.container.index.add(app.container.storage.importCopy(uri, id))
                        added++
                    }.onFailure { android.util.Log.w("BlazeIt", "Share import failed", it) }
                }
            }
            _sendStatus.value = if (added == 0) "Could not copy those files"
            else "$added file${if (added == 1) "" else "s"} ready on the computer"
            clearSendStatusLater()
        }
    }

    fun acceptSharedText(text: String) {
        getApplication<Application>().container.clipboard.set(text)
        _sendStatus.value = "Text sent to the computer"
        clearSendStatusLater()
    }

    fun removeFile(id: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val gone = app.container.index.remove(id)
                // Only delete files this app created. A picked file is the user's own.
                if (gone != null && gone.owned) app.container.storage.delete(gone)
            }
        }
    }

    private fun clearSendStatusLater() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _sendStatus.value = ""
        }
    }

    // ------------------------------------------------------------------ clipboard

    /** The shared text slot, live in both directions. */
    val clipboard: StateFlow<String>
        get() = getApplication<Application>().container.clipboard.flow

    private val _clipStatus = MutableStateFlow("")
    val clipStatus: StateFlow<String> = _clipStatus

    /** Publishes text to the PC. The browser sees it over SSE within a frame or two. */
    fun sendClipboard(text: String) {
        val ok = getApplication<Application>().container.clipboard.set(text)
        flashClip(if (ok) "Sent to the computer" else "Too long to send")
    }

    /**
     * Copies the phone's system clipboard into the shared slot.
     *
     * This is the phone-to-PC direction, and it has to be a button. Android 10 and later
     * refuse a clipboard read to any app that is not in the foreground, so the read is
     * only legal at the moment the user taps -- see [dev.periy.bridge.server.SystemClipboard].
     * Making that a visible, deliberate action is the honest design, not a workaround.
     */
    fun pasteFromDevice() {
        val app = getApplication<Application>()
        val text = SystemClipboard.read(app)
        if (text.isNullOrEmpty()) {
            flashClip("Nothing in the phone clipboard")
            return
        }
        val ok = app.container.clipboard.set(text)
        flashClip(if (ok) "Clipboard sent to the computer" else "Too long to send")
    }

    /** Puts the shared text into the phone's system clipboard, ready to paste anywhere. */
    fun copyToDevice() {
        val app = getApplication<Application>()
        val text = app.container.clipboard.text
        if (text.isEmpty()) {
            flashClip("Nothing to copy")
            return
        }
        SystemClipboard.write(app, text)
        flashClip("Copied to the phone clipboard")
    }

    fun clearClipboard() {
        getApplication<Application>().container.clipboard.set("")
        flashClip("Cleared")
    }

    private fun flashClip(msg: String) {
        _clipStatus.value = msg
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            if (_clipStatus.value == msg) _clipStatus.value = ""
        }
    }
}
