@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.EmptyFlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.network.EmptyFlashNetwork
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.transfer.EmptyFlashTransferRepository
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Pre-boot proxies for the desktop facade (ADR-010 shared engine, "Empty stand-ins are singletons" audit item).
 *
 * `DesktopEngine.transfers` / `.network` / `.discovery` used to return the `Empty*` singleton until the stack was
 * assembled, so a consumer that captured the reference before boot (a ViewModel, a `FlashDesktop.create().transfers`)
 * kept the empty object for ever. These proxies are what the engine hands out *until* the real implementation exists:
 * every call and every flow is forwarded to whatever [Switchable.current] is, so a reference captured before boot
 * starts working the moment the real object is installed, and flows re-emit from the real source.
 */
internal class Switchable<D : Any>(initial: D) {
    private val state = MutableStateFlow(initial)

    /** The implementation calls are forwarded to right now. */
    val current: D get() = state.value

    /** Swaps the forwarding target; every [flow] derived from this holder re-subscribes. */
    fun install(delegate: D) {
        state.value = delegate
    }

    /** A StateFlow whose `value` and collection follow the current delegate's [select]ed flow. */
    fun <T> flow(select: (D) -> StateFlow<T>): StateFlow<T> = SwitchingStateFlow(state, select)
}

/** A [StateFlow] that reads and collects whichever source [select] picks from the current [holder] value. */
internal class SwitchingStateFlow<D : Any, T>(
    private val holder: StateFlow<D>,
    private val select: (D) -> StateFlow<T>,
) : StateFlow<T> {
    override val value: T get() = select(holder.value).value
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        holder.flatMapLatest { select(it) }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** [FlashTransferRepository] that forwards to the installed repository, [EmptyFlashTransferRepository] before. */
internal class SwitchingTransferRepository : FlashTransferRepository {
    private val target = Switchable<FlashTransferRepository>(EmptyFlashTransferRepository)

    fun install(real: FlashTransferRepository?) = target.install(real ?: EmptyFlashTransferRepository)

    override val activeTransfers: StateFlow<List<FlashTransfer>> = target.flow { it.activeTransfers }

    override suspend fun sendFile(targetDevice: FlashDevice, fileUri: String, displayName: String, fileSize: Long) =
        target.current.sendFile(targetDevice, fileUri, displayName, fileSize)

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        wireFileId: String?,
    ) = target.current.sendFile(targetDevice, fileUri, displayName, fileSize, wireFileId)

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        transferId: String,
        wireFileId: String,
    ) = target.current.sendFile(targetDevice, fileUri, displayName, fileSize, transferId, wireFileId)

    override fun clearFinishedHistory() = target.current.clearFinishedHistory()
    override suspend fun pauseTransfer(transferId: FlashTransferId) = target.current.pauseTransfer(transferId)
    override suspend fun pauseForSystem(transferId: FlashTransferId, reason: String) =
        target.current.pauseForSystem(transferId, reason)
    override suspend fun resumeTransfer(transferId: FlashTransferId) = target.current.resumeTransfer(transferId)
    override suspend fun cancelTransfer(transferId: FlashTransferId) = target.current.cancelTransfer(transferId)
    override fun isExternalRow(transferId: String) = target.current.isExternalRow(transferId)
    override suspend fun acceptIncoming(transferId: FlashTransferId) = target.current.acceptIncoming(transferId)
    override suspend fun declineIncoming(transferId: FlashTransferId) = target.current.declineIncoming(transferId)
    override fun onInboundFrame(bytes: ByteArray) = target.current.onInboundFrame(bytes)

    override fun onIncomingOffered(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String?,
    ) = target.current.onIncomingOffered(transferId, fileId, fileName, totalBytes, peerName, peerDeviceId)

    override fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String?,
        localPath: String?,
    ) = target.current.onIncomingStarted(transferId, fileId, fileName, totalBytes, peerName, peerDeviceId, localPath)

    override fun onIncomingProgress(transferId: String, bytesDone: Long) =
        target.current.onIncomingProgress(transferId, bytesDone)

    override fun onIncomingCompleted(transferId: String, verified: Boolean, localPath: String?) =
        target.current.onIncomingCompleted(transferId, verified, localPath)

    override fun onIncomingFailed(transferId: String, reason: String) =
        target.current.onIncomingFailed(transferId, reason)

    override fun isResumableInboundRetry(transferId: String) = target.current.isResumableInboundRetry(transferId)
}

/** [FlashNetwork] that forwards to the installed transport, [EmptyFlashNetwork] before. */
internal class SwitchingNetwork : FlashNetwork {
    private val target = Switchable<FlashNetwork>(EmptyFlashNetwork)

    fun install(real: FlashNetwork?) = target.install(real ?: EmptyFlashNetwork)

    override val networkState: StateFlow<FlashNetworkState> = target.flow { it.networkState }
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = target.flow { it.activeSessions }
    override val connectionHealth: StateFlow<FlashConnectionHealth> = target.flow { it.connectionHealth }

    override suspend fun start(listenPort: Int): FlashResult<Int> = target.current.start(listenPort)
    override suspend fun stop(): FlashResult<Unit> = target.current.stop()
    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> = target.current.connect(device)
    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> =
        target.current.connectManual(host, port)
    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = target.current.disconnect(deviceId)
    override fun retryConnection(): Boolean = target.current.retryConnection()
}

/** [FlashDiscovery] that forwards to the installed discovery, [EmptyFlashDiscovery] before. */
internal class SwitchingDiscovery : FlashDiscovery {
    private val target = Switchable<FlashDiscovery>(EmptyFlashDiscovery)

    fun install(real: FlashDiscovery?) = target.install(real ?: EmptyFlashDiscovery)

    override val state: StateFlow<FlashDiscoveryState> = target.flow { it.state }
    override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> = target.flow { it.discoveredEndpoints }

    override suspend fun startDiscovery(): FlashResult<Unit> = target.current.startDiscovery()
    override suspend fun stopDiscovery(): FlashResult<Unit> = target.current.stopDiscovery()
    override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> = target.current.startAdvertising(listenPort)
    override suspend fun stopAdvertising(): FlashResult<Unit> = target.current.stopAdvertising()
    override suspend fun stopAll(): FlashResult<Unit> = target.current.stopAll()
}

/**
 * Whether an inbound file offer is accepted without asking the user (AGENTS.md section 19: a new peer needs user approval).
 *
 * Only a **paired** peer can ever be auto-accepted: [autoAcceptIncoming] (the `FlashConfig` switch) and the per-kind
 * auto-download setting both *narrow* what a trusted peer may do silently, neither one extends it to a stranger.
 */
internal fun shouldAutoAcceptOffer(
    peerTrusted: Boolean,
    perKindAutoDownload: Boolean,
    autoAcceptIncoming: Boolean,
): Boolean = peerTrusted && (perKindAutoDownload || autoAcceptIncoming)
