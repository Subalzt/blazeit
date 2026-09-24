package dev.periy.bridge.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.periy.bridge.container
import dev.periy.bridge.server.SystemClipboard

/**
 * Sends the phone's latest copy to the laptop.
 *
 * Android lets an app read the clipboard only while it has the screen, so this is a tiny
 * invisible activity: it opens, reads, hands the text to the shared slot (which the laptop
 * helper puts straight into the Windows clipboard) and closes, all in a blink.
 */
class ClipSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        Toast.makeText(this, ClipSync.sendFromPhone(this, always = true), Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

/** The quick-settings tile: one tap from the shade sends what you just copied. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Send clipboard"
            if (Build.VERSION.SDK_INT >= 29) subtitle = "To the laptop"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

object ClipSync {
    /**
     * Reads the phone's clipboard (the caller must have the screen) and publishes it when it
     * is new. [always] is for an explicit tap, which sends even with automatic sync off.
     * Returns a short line for a toast.
     */
    fun sendFromPhone(ctx: Context, always: Boolean = false): String {
        val c = ctx.container
        if (!always && !c.prefs.clipSync) return ""
        val text = SystemClipboard.read(ctx)
        if (text.isNullOrEmpty()) return "Nothing copied on the phone"
        if (text == c.clipboard.text) return "Already on the laptop"
        return if (c.clipboard.set(text)) "Sent to the laptop" else "Too long to send"
    }
}
