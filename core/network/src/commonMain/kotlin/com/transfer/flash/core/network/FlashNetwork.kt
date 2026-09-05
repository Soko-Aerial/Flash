package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Public network contract for managing socket servers, peer connections, and sessions.
 */
public interface FlashNetwork {
    public val networkState: StateFlow<FlashNetworkState>
    public val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>>

    /**
     * Aggregated connection health for UI-030/UI-044 (plan C4.7). Additive —
     * implementations wire it to [ReconnectEngine]/session signals in P4.
     */
    public val connectionHealth: StateFlow<FlashConnectionHealth>

    public suspend fun start(listenPort: Int = 0): FlashResult<Int>
    public suspend fun stop(): FlashResult<Unit>
    public suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
    public suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
    public suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>

    /**
     * Manual reconnect trigger wired to the connection banner Retry button
     * (plan C4.7). Returns false when there is nothing to retry (no recent
     * failure / no candidate endpoints).
     */
    public fun retryConnection(): Boolean = false
}
