package com.transfer.flash

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection as DomainDirection
import com.transfer.flash.core.transfer.model.FlashTransferState as DomainState
import com.transfer.flash.ui.transfers.FlashTransferDirection as UiDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState as UiState
import com.transfer.flash.ui.transfers.TransfersUiState

/**
 * Phase 3.3 mapping seam: domain [FlashTransfer] (core:transfer) → UI [FlashTransferItemUi] /
 * [TransfersUiState] (ui:chat). It lives in :app because that is the only module that sees both
 * sides — ui:chat deliberately does not depend on core:transfer, so the screen stays domain-
 * agnostic. Pure data mapping (no Android types) → unit-testable on the JVM.
 */
fun FlashTransfer.toUiItem(): FlashTransferItemUi = FlashTransferItemUi(
    id = id.value,
    fileName = fileName,
    direction = when (direction) {
        DomainDirection.Sending -> UiDirection.Send
        DomainDirection.Receiving -> UiDirection.Receive
    },
    peerName = peerName,
    bytesTotal = bytesTotal,
    bytesDone = bytesDone,
    state = when (state) {
        // #5: an inbound OFFER awaiting the user's Accept/Decline gets its own Offers section.
        DomainState.Offered -> UiState.Offered
        DomainState.Queued -> UiState.Queued
        // Verifying is post-transfer hashing: still in flight, so it stays in the Active section.
        DomainState.Transferring, DomainState.Verifying -> UiState.Active
        DomainState.Paused -> UiState.Paused
        DomainState.Completed -> UiState.Completed
        // The UI has no Cancelled bucket; surface it in Failed with a label rather than vanish it.
        DomainState.Failed, DomainState.Cancelled -> UiState.Failed
    },
    speedBytesPerSec = speedBytesPerSec,
    // The UI treats null / non-positive ETA as "no estimate"; normalise 0 → null at the boundary.
    etaSeconds = etaSeconds.takeIf { it > 0L },
    errorMessage = errorMessage ?: if (state == DomainState.Cancelled) "Cancelled" else null,
    verified = state == DomainState.Completed,
    // Received file path when inbound; the source URI when outbound. Enables open & share.
    localPath = localPath ?: sourceUri,
    // A cancelled/declined transfer shares the Failed section but cannot be resumed: both sides
    // tore the session down, and resumeTransfer returns without doing anything. Flagging it here
    // keeps the screen from rendering a Retry button that could only ever look broken.
    retryable = state != DomainState.Cancelled,
)

/**
 * Maps a live domain transfer list into the sectioned [TransfersUiState] the screen renders,
 * reusing [TransfersUiState.fromItems] for the Active / Failed / History bucketing.
 *
 * [isLoading] and [isError] are the screen's boot state and are required, not defaulted
 * (ERROR-034). The transfers page has had Loading and Error branches all along and both were
 * unreachable, because every caller mapped a list and left the flags false — so before the engine
 * booted the tab asserted "No transfers yet", which is a claim about this device's history made by
 * code that had not yet been given access to it. Making the parameters mandatory means a new call
 * site has to state what it knows.
 *
 * Note that an empty list from a *booted* repository is a real answer: `activeTransfers` is
 * in-memory and starts genuinely empty, so unlike the chat list there is no first-emission gap to
 * cover here — pre-boot is the whole of it.
 */
fun TransfersUiState.Companion.fromDomain(
    transfers: List<FlashTransfer>,
    isLoading: Boolean,
    isError: Boolean,
): TransfersUiState = TransfersUiState.fromItems(transfers.map { it.toUiItem() })
    .copy(isLoading = isLoading, isError = isError)
