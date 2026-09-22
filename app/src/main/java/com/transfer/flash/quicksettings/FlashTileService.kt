package com.transfer.flash.quicksettings

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.debug.FlashBackgroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Quick Settings Tile allowing users to toggle discoverability and cycle
 * discovery modes (Discoverable -> Hidden -> Eco -> Off) directly from the
 * Quick Settings notification shade without leaving their current task.
 *
 * Long-pressing the tile opens Flash directly to the Nearby sharing screen via
 * the system [android.service.quicksettings.action.QS_TILE_PREFERENCES] intent filter.
 */
class FlashTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = DiscoveryEngineHolder.isRunning()
        if (!isRunning) {
            // Off -> Start in Discoverable (Standard)
            FlashBackgroundService.start(applicationContext)
            serviceScope.launch {
                DiscoveryEngineHolder.setDiscoveryMode(FlashDiscoveryMode.STANDARD, applicationContext)
            }
            updateTileState(mode = FlashDiscoveryMode.STANDARD, running = true)
        } else {
            when (DiscoveryEngineHolder.currentDiscoveryMode()) {
                FlashDiscoveryMode.STANDARD -> {
                    // Standard -> Ghost (Hidden / Browse only)
                    serviceScope.launch {
                        DiscoveryEngineHolder.setDiscoveryMode(FlashDiscoveryMode.GHOST, applicationContext)
                    }
                    updateTileState(mode = FlashDiscoveryMode.GHOST, running = true)
                }
                FlashDiscoveryMode.GHOST -> {
                    // Ghost -> Eco (Battery saver)
                    serviceScope.launch {
                        DiscoveryEngineHolder.setDiscoveryMode(FlashDiscoveryMode.ECO, applicationContext)
                    }
                    updateTileState(mode = FlashDiscoveryMode.ECO, running = true)
                }
                FlashDiscoveryMode.ECO,
                FlashDiscoveryMode.BOOST,
                FlashDiscoveryMode.RECEIVE_KIOSK -> {
                    // Eco/Boost/Kiosk -> Off
                    FlashBackgroundService.stop(applicationContext)
                    serviceScope.launch {
                        DiscoveryEngineHolder.stopAll()
                    }
                    updateTileState(mode = FlashDiscoveryMode.STANDARD, running = false)
                }
            }
        }
    }

    private fun updateTileState(
        mode: FlashDiscoveryMode = DiscoveryEngineHolder.currentDiscoveryMode(),
        running: Boolean = DiscoveryEngineHolder.isRunning(),
    ) {
        val tile = qsTile ?: return
        if (!running) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Flash Share"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Off (Tap to start)"
            }
        } else {
            when (mode) {
                FlashDiscoveryMode.STANDARD -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Flash Share"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Discoverable"
                    }
                }
                FlashDiscoveryMode.GHOST -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Flash Share"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Hidden (Ghost)"
                    }
                }
                FlashDiscoveryMode.ECO -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Flash Share"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Eco (Battery)"
                    }
                }
                FlashDiscoveryMode.BOOST -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Flash Share"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Boost"
                    }
                }
                FlashDiscoveryMode.RECEIVE_KIOSK -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Flash Share"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Kiosk"
                    }
                }
            }
        }
        tile.updateTile()
    }
}
