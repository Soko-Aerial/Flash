package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Honest pre-boot stand-in for [FlashTransferRepository].
 * Emits an empty [activeTransfers] list and returns [FlashResult.Failure] for every operation that needs a running
 * engine until the real transfer repository is assembled. Nothing here silently succeeds: [acceptIncoming] and
 * [declineIncoming] used to inherit the interface default `Success(Unit)`, which told a caller that an offer it
 * could not possibly hold had been answered. [clearFinishedHistory] stays a (default) no-op: there is no history.
 *
 * A host that must keep working across the boot should not capture this object: `DesktopEngine` hands out a
 * forwarding proxy until its real repository exists.
 */
public object EmptyFlashTransferRepository : FlashTransferRepository {
    private val emptyListFlow = MutableStateFlow<List<FlashTransfer>>(emptyList())
    override val activeTransfers: StateFlow<List<FlashTransfer>> = emptyListFlow.asStateFlow()

    override suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit> = notStarted()

    override suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit> = notStarted()

    override suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId> =
        notStarted()

    override suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit> =
        notStarted()

    override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> =
        notStarted()

    override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> =
        notStarted()

    private fun notStarted(): FlashResult.Failure =
        FlashResult.Failure(FlashError.Unknown(NOT_STARTED_MESSAGE))

    /** The sentence every refused call carries. */
    public const val NOT_STARTED_MESSAGE: String = "Transfer engine is not started yet (or has been closed)"
}
