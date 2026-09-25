package dev.periy.bridge.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import dev.periy.bridge.server.EventBus
import java.io.BufferedInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A video from the laptop, in picture-in-picture on this phone.
 *
 * "Play on phone" (a bookmark clicked on YouTube, say) has the laptop's browser hand over the
 * video itself: its decoded frames and sound, recorded by the browser, repackaged by the laptop
 * helper and sent here as one stream (H.264 and AAC in MPEG-TS). Media3 plays it with the phone's
 * hardware decoder, sound and picture in step. It opens straight into picture-in-picture; tap it
 * for full screen. Its buttons pause the video on the laptop and move the sound between phone and
 * laptop; closing it hands the video back to the laptop.
 */
@UnstableApi
class VideoPipActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var note: TextView
    private var player: ExoPlayer? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closing = false
    private var aspect = Rational(16, 9)
    private var stopped = false
    private var enteredPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surface = SurfaceView(this)
        note = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt()); textSize = 14f; gravity = Gravity.CENTER
            text = "Waiting for the laptop's video..."
        }
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        val filter = IntentFilter().apply { addAction(ACTION_PLAY_PAUSE); addAction(ACTION_SOUND) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(actions, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(actions, filter)
        setPictureInPictureParams(pipParams())

        // Listen first, then tell the laptop where: it connects and starts sending.
        Thread({
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = ss
                runOnUiThread {
                    if (closing) return@runOnUiThread
                    startPlayer(ss)
                    EventBus.emit("video", "start $PORT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot listen", e)
                runOnUiThread { finish() }
            }
        }, "video-listen").start()
    }

    /** Asked for again while open (a new video on the laptop): say again that it is listening. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (server != null && !closing) {
            if (player?.playbackState != Player.STATE_BUFFERING) again()
            EventBus.emit("video", "start $PORT")
        }
    }

    private fun startPlayer(ss: ServerSocket) {
        // Little buffer: the laptop sends as it plays, so there is nothing to read ahead, and
        // sound and picture arrive together, so a short start costs nothing in sync.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(300, 2000, 150, 300).build()
        val p = ExoPlayer.Builder(this).setLoadControl(load).build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) fit(size.width, size.height)
            }
            override fun onRenderedFirstFrame() { note.visibility = View.GONE }
            override fun onIsPlayingChanged(playing: Boolean) {
                // A stream that follows another (reusing this player) may not report a "first frame" again.
                if (playing) note.visibility = View.GONE
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) again()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback", error)
                again()
            }
        })
        val source = ProgressiveMediaSource.Factory({ SocketDataSource(ss) }, DefaultExtractorsFactory())
            .createMediaSource(MediaItem.fromUri(Uri.parse("tcp://laptop/video")))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    /** The stream ended or broke (the laptop's window was resized, say): wait for the next. */
    private fun again() {
        if (closing) return
        val p = player ?: return
        note.text = "Waiting for the laptop's video..."
        note.visibility = View.VISIBLE
        p.prepare()
        p.playWhenReady = true
    }

    /** Sizes the picture to the window without stretching it, and the picture-in-picture window to the picture. */
    private fun fit(w: Int, h: Int) {
        val g = gcd(w, h)
        aspect = Rational((w / g).coerceAtLeast(1), (h / g).coerceAtLeast(1))
        runCatching { setPictureInPictureParams(pipParams()) }
        val root = surface.parent as View
        val sw = root.width.toFloat(); val sh = root.height.toFloat()
        if (sw <= 0 || sh <= 0) { root.post { fit(w, h) }; return }
        val k = minOf(sw / w, sh / h)
        surface.layoutParams = FrameLayout.LayoutParams((w * k).toInt(), (h * k).toInt(), Gravity.CENTER)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) maxOf(a, 1) else gcd(b, a % b)

    private fun pipParams(): PictureInPictureParams {
        // Android allows shapes between 1:2.39 and 2.39:1.
        val r = aspect.toFloat()
        val shape = when {
            r > 2.39f -> Rational(239, 100)
            r < 1 / 2.39f -> Rational(100, 239)
            else -> aspect
        }
        fun action(icon: Int, title: String, what: String, action: String, code: Int) = RemoteAction(
            Icon.createWithResource(this, icon), title, what,
            PendingIntent.getBroadcast(
                this, code, Intent(action).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val playPause = action(android.R.drawable.ic_media_pause, "Play or pause", "Play or pause on the laptop", ACTION_PLAY_PAUSE, 1)
        val sound = if (phoneSound) action(android.R.drawable.ic_lock_silent_mode_off, "Sound on the laptop", "Play the sound on the laptop instead", ACTION_SOUND, 2)
            else action(android.R.drawable.ic_lock_silent_mode, "Sound here", "Play the sound on this phone", ACTION_SOUND, 2)
        return PictureInPictureParams.Builder().setAspectRatio(shape).setActions(listOf(playPause, sound)).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(true); setSeamlessResizeEnabled(true) }
        }.build()
    }

    /** Whether this phone plays the sound; off, the laptop keeps it (and runs a moment ahead of the picture). */
    private var phoneSound = true

    /**
     * The picture-in-picture window's buttons. Play/pause goes to the laptop's page, which
     * pauses the video itself; the sound button silences this phone and has the page play the
     * sound on the laptop, or the other way round.
     */
    private val actions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> EventBus.emit("video", "playpause")
                ACTION_SOUND -> {
                    phoneSound = !phoneSound
                    player?.volume = if (phoneSound) 1f else 0f
                    EventBus.emit("video", if (phoneSound) "sound on" else "sound off")
                    runCatching { setPictureInPictureParams(pipParams()) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Straight into picture-in-picture, once: after that, tapping it for full screen stays so.
        if (!enteredPip && !isInPictureInPictureMode) {
            enteredPip = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        }
    }

    override fun onStart() { super.onStart(); stopped = false }

    override fun onStop() {
        super.onStop()
        stopped = true
        // Closed from picture-in-picture (its X, or dragged away): that ends it.
        if (!isInPictureInPictureMode) finish()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) enteredPip = true
        else if (stopped) finish()
        surface.post { player?.videoSize?.let { if (it.width > 0) fit(it.width, it.height) } }
    }

    override fun onDestroy() {
        closing = true
        if (current?.get() === this) current = null
        runCatching { unregisterReceiver(actions) }
        // The laptop takes the video back: its window and its speakers.
        EventBus.emit("video", "stop")
        player?.release()
        player = null
        runCatching { server?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VideoPip"
        const val PORT = 8792
        private const val ACTION_PLAY_PAUSE = "dev.periy.bridge.VIDEO_PLAY_PAUSE"
        private const val ACTION_SOUND = "dev.periy.bridge.VIDEO_SOUND"
        @Volatile private var current: WeakReference<VideoPipActivity>? = null

        /** The laptop's picture-in-picture window was closed there: close this too. */
        fun close() {
            current?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }
}

/** Media3 reading the laptop's stream straight off a socket: each open waits for its next connection. */
@UnstableApi
private class SocketDataSource(private val server: ServerSocket) : BaseDataSource(true) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val s = server.accept()
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        socket = s
        input = BufferedInputStream(s.getInputStream(), 256 * 1024)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = input?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { input?.close() }
        runCatching { socket?.close() }
        input = null; socket = null
        if (opened) { opened = false; transferEnded() }
    }
}
