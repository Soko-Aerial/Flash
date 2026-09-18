package com.transfer.flash.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.transfer.flash.MainActivity
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.debug.FlashBackgroundService

/**
 * Android Quick Settings Tile allowing users to quickly toggle Flash discoverability
 * or jump straight into the Nearby sharing screen.
 */
class FlashTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = DiscoveryEngineHolder.isRunning()
        if (!isRunning) {
            FlashBackgroundService.start(applicationContext)
            updateTileState(active = true)
        }
        openNearbyScreen()
    }

    private fun updateTileState(active: Boolean = DiscoveryEngineHolder.isRunning()) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Flash Share"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Discoverable" else "Tap to share"
        }
        tile.updateTile()
    }

    private fun openNearbyScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.transfer.flash.action.SHORTCUT_NEARBY"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
