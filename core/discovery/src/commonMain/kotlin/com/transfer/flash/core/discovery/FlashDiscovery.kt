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
}
