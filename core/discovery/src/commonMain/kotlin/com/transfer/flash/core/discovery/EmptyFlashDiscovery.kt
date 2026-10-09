package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Honest pre-boot stand-in for [FlashDiscovery].
 *
 * Starting discovery or advertising used to "succeed" here although nothing was browsing or announcing; both now
 * return [FlashResult.Failure]. The `stop*` calls stay successes: stopping something that is not running is
 * idempotent.
 *
 * A host that must keep working across the boot should not capture this object: `DesktopEngine` hands out a
 * forwarding proxy until its real discovery exists.
 */
public object EmptyFlashDiscovery : FlashDiscovery {
    private val emptyStateFlow = MutableStateFlow(FlashDiscoveryState())
    private val emptyEndpointsFlow = MutableStateFlow<List<FlashDiscoveredEndpoint>>(emptyList())

    override val state: StateFlow<FlashDiscoveryState> = emptyStateFlow.asStateFlow()
    override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> = emptyEndpointsFlow.asStateFlow()

    override suspend fun startDiscovery(): FlashResult<Unit> = notStarted()
    override suspend fun stopDiscovery(): FlashResult<Unit> = FlashResult.Success(Unit)
    override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> = notStarted()
    override suspend fun stopAdvertising(): FlashResult<Unit> = FlashResult.Success(Unit)
    override suspend fun stopAll(): FlashResult<Unit> = FlashResult.Success(Unit)

    private fun notStarted(): FlashResult.Failure = FlashResult.Failure(FlashError.Unknown(NOT_STARTED_MESSAGE))

    /** The sentence every refused call carries. */
    public const val NOT_STARTED_MESSAGE: String = "Discovery is not started yet (or has been closed)"
}
