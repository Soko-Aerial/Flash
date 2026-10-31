package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Public discovery interface for peer advertising and scanning across local network mediums.
 */
public interface FlashDiscovery {
    public val state: StateFlow<FlashDiscoveryState>
    public val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>

    public suspend fun startDiscovery(): FlashResult<Unit>
    public suspend fun stopDiscovery(): FlashResult<Unit>
    public suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
    public suspend fun stopAdvertising(): FlashResult<Unit>
    public suspend fun stopAll(): FlashResult<Unit>

    /**
     * OPT-O10-01: Informs discovery of display power state. When screen turns off and no transfer
     * or call is in flight, discovery automatically downscales to ECO mode to conserve battery
     * and release continuous Wi-Fi radio multicast locks. Restores user-selected mode when screen turns on.
     */
    public fun setScreenActive(active: Boolean) {}
}
