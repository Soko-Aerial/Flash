package com.transfer.flash.pairing

import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.ui.chat.FlashPairingPhase

/**
 * Pure mappers from the engine-owned pairing state ([PairingPhase] + expiry) to the UI dialog
 * model ([FlashPairingPhase], seconds remaining) consumed by `FlashPairingDialog` (UI-032).
 *
 * The engine machine has eight phases; the demo dialog has six. The two "in-flight" engine
 * sub-states collapse into the single UI [FlashPairingPhase.RequestReceived], and a protocol
 * [PairingPhase.Failed] surfaces as [FlashPairingPhase.Declined] because the dialog has no
 * dedicated failure card (the docs mapping table calls this out).
 */
object PairingUiMapper {

    fun corePhaseToUi(phase: PairingPhase): FlashPairingPhase = when (phase) {
        PairingPhase.Idle -> FlashPairingPhase.Idle
        // v2 (ADR-042): the responder has no code until the initiator opens its commitment, which is
        // milliseconds away, so no dialog yet. The initiator is simply "waiting for the other device".
        PairingPhase.AwaitingPeerReveal -> FlashPairingPhase.Idle
        PairingPhase.AwaitingPeerNonce -> FlashPairingPhase.AwaitingPeerConfirmation
        // Responder sub-states before it accepts both show the actionable consent card.
        PairingPhase.RequestReceived,
        PairingPhase.AwaitingLocalDecision -> FlashPairingPhase.RequestReceived
        PairingPhase.AwaitingPeerConfirmation -> FlashPairingPhase.AwaitingPeerConfirmation
        PairingPhase.Confirmed -> FlashPairingPhase.Paired
        PairingPhase.DeclinedByPeer -> FlashPairingPhase.Declined
        PairingPhase.Expired -> FlashPairingPhase.Expired
        // No failure card exists (UI-032); a protocol failure reads as a decline to the user.
        PairingPhase.Failed -> FlashPairingPhase.Declined
    }

    /** Whole seconds left until [expiresAtMs], clamped to 0; 0 when there is no active deadline. */
    fun secondsLeft(expiresAtMs: Long?, nowMs: Long): Int {
        expiresAtMs ?: return 0
        return ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()
    }
}
