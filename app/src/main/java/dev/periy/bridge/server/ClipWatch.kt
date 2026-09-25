package dev.periy.bridge.server

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Sends what you copy in any app to the laptop, with BlazeIt in the background.
 *
 * Android 10 and later let only the keyboard and the app on screen read the clipboard, and do
 * not even tell a background app that it changed. They do write it down, though: every time a
 * copy is made, the system tries to tell each listener and logs "Denying clipboard access to
 * <app>" for those in the background. So this listens (to be one of those told), watches the
 * system log for that line about BlazeIt, and at that moment opens a tiny invisible window for a
 * blink, which gives BlazeIt the focus it needs to read the copy and send it. This is how KDE
 * Connect does it.
 *
 * It needs two permissions an app cannot give itself: reading the system log (granted once over
 * USB: `adb shell pm grant <app> android.permission.READ_LOGS`) and "Display over other apps",
 * which lets the window open from the background. Android 13+ also asks once, with BlazeIt on
 * screen, to allow it to read the logs; the watch starts then.
 */
object ClipWatch {
    private const val TAG = "ClipWatch"

    @Volatile private var process: Process? = null
    @Volatile private var lastOwnWrite = 0L
    @Volatile private var lastLaunch = 0L
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val main = Handler(Looper.getMainLooper())

    /** BlazeIt itself just put something on the clipboard (from the laptop): that is no copy to send. */
    fun ownWrite() { lastOwnWrite = SystemClock.elapsedRealtime() }

    fun canReadLogs(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun ready(ctx: Context) = canReadLogs(ctx) && canOverlay(ctx)

    val running: Boolean get() = process?.isAlive == true

    /** Starts watching, if allowed and not already watching. Call with BlazeIt on screen the first time. */
    @Synchronized
    fun ensure(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        if (!enabled || !ready(app)) {
            Log.i(TAG, "not watching: sync $enabled, logs ${canReadLogs(app)}, overlay ${canOverlay(app)}")
            stop(app); return
        }
        if (running) return
        main.post {
            if (listener == null) {
                val cm = app.getSystemService(ClipboardManager::class.java)
                // Nothing to do here: being a listener is what makes the system log the copy.
                listener = ClipboardManager.OnPrimaryClipChangedListener { }.also { runCatching { cm?.addPrimaryClipChangedListener(it) } }
            }
        }
        val p = runCatching {
            // From now on only (-T 1), just the clipboard service's complaints.
            ProcessBuilder("logcat", "-T", "1", "-v", "brief", "ClipboardService:E", "*:S").redirectErrorStream(true).start()
        }.onFailure { Log.w(TAG, "could not start the log watch", it) }.getOrNull() ?: return
        process = p
        Log.i(TAG, "watching for copies")
        val needle = "Denying clipboard access to " + app.packageName
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.contains(needle)) onCopied(app)
                }
            }
            Log.i(TAG, "log watch ended")
        }, "clipwatch").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop(ctx: Context) {
        process?.destroy()
        process = null
        val l = listener ?: return
        listener = null
        main.post { runCatching { ctx.applicationContext.getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(l) } }
    }

    private fun onCopied(app: Context) {
        val now = SystemClock.elapsedRealtime()
        // Our own write from the laptop, or the same copy announced twice: nothing new.
        if (now - lastOwnWrite < 2_000 || now - lastLaunch < 700) return
        lastLaunch = now
        main.post {
            runCatching {
                app.startActivity(
                    Intent().setClassName(app, "dev.periy.bridge.ui.ClipSendActivity")
                        .putExtra("quiet", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            }.onFailure { Log.w(TAG, "could not open the reader", it) }
        }
    }
}
