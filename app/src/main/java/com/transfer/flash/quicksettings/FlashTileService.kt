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
 * Android Quick Settings Tile allowing users to toggle Flash discoverability
 * cleanly between Active (Discoverable) and Off from the Quick Settings shade.
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
        when (FlashTileMath.tapAction(DiscoveryEngineHolder.isRunning())) {
            FlashTileTapAction.START_DISCOVERABLE -> {
                FlashBackgroundService.start(applicationContext)
                serviceScope.launch {
                    DiscoveryEngineHolder.setDiscoveryMode(FlashDiscoveryMode.STANDARD, applicationContext)
                }
                updateTileState(mode = FlashDiscoveryMode.STANDARD, running = true)
            }
            FlashTileTapAction.STOP -> {
                FlashBackgroundService.stop(applicationContext)
                serviceScope.launch {
                    DiscoveryEngineHolder.stopAll()
                }
                updateTileState(mode = FlashDiscoveryMode.STANDARD, running = false)
            }
        }
    }

    private fun updateTileState(
        mode: FlashDiscoveryMode = DiscoveryEngineHolder.currentDiscoveryMode(),
        running: Boolean = DiscoveryEngineHolder.isRunning(),
    ) {
        val tile = qsTile ?: return
        val spec = FlashTileMath.tileSpec(running = running, mode = mode)
        tile.state = spec.tileState()
        tile.label = spec.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = spec.subtitle
        }
        tile.updateTile()
    }
}

internal enum class FlashTileTapAction {
    START_DISCOVERABLE,
    STOP,
}

internal data class FlashTileUiSpec(
    val isActive: Boolean,
    val label: String,
    val subtitle: String,
) {
    fun tileState(): Int = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
}

internal object FlashTileMath {
    fun tapAction(isRunning: Boolean): FlashTileTapAction =
        if (isRunning) FlashTileTapAction.STOP else FlashTileTapAction.START_DISCOVERABLE

    fun tileSpec(running: Boolean, mode: FlashDiscoveryMode): FlashTileUiSpec {
        if (!running) {
            return FlashTileUiSpec(
                isActive = false,
                label = "Flash Share",
                subtitle = "Off (Tap to start)",
            )
        }
        val subtitle = when (mode) {
            FlashDiscoveryMode.STANDARD -> "Discoverable"
            FlashDiscoveryMode.GHOST -> "Hidden"
            FlashDiscoveryMode.ECO -> "Battery saver"
            FlashDiscoveryMode.BOOST -> "Fast discovery"
            FlashDiscoveryMode.RECEIVE_KIOSK -> "Receive ready"
        }
        return FlashTileUiSpec(
            isActive = true,
            label = "Flash Share",
            subtitle = subtitle,
        )
    }
}
