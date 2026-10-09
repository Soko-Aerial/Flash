package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Honest pre-boot stand-in for [FlashNetwork].
 *
 * Nothing here pretends to work: [start] used to return `Success(0)`, a port that does not exist, and [disconnect]
 * reported success for a peer it had never held. Every operation that needs a running transport now returns
 * [FlashResult.Failure]; only [stop] stays a success, because stopping something that is not running is idempotent.
 *
 * A host that must keep working across the boot should not capture this object: `DesktopEngine` hands out a
 * forwarding proxy until its real transport exists.
 */
public object EmptyFlashNetwork : FlashNetwork {
    private val emptyStateFlow = MutableStateFlow(FlashNetworkState())
    private val emptySessionsFlow = MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    private val healthFlow = MutableStateFlow(FlashConnectionHealth.Offline)

    override val networkState: StateFlow<FlashNetworkState> = emptyStateFlow.asStateFlow()
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = emptySessionsFlow.asStateFlow()
    override val connectionHealth: StateFlow<FlashConnectionHealth> = healthFlow.asStateFlow()

    override suspend fun start(listenPort: Int): FlashResult<Int> = notStarted()
    override suspend fun stop(): FlashResult<Unit> = FlashResult.Success(Unit)
    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> = notStarted()
    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> = notStarted()
    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = notStarted()

    private fun notStarted(): FlashResult.Failure = FlashResult.Failure(FlashError.Unknown(NOT_STARTED_MESSAGE))

    /** The sentence every refused call carries. */
    public const val NOT_STARTED_MESSAGE: String = "Network engine is not started yet (or has been closed)"
}
