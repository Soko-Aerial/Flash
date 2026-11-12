package com.transfer.flash.pairing

import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.ui.chat.FlashPairingPhase

/**
 * Pure mappers from the engine-owned pairing state ([PairingPhase] + expiry) to the UI dialog
 * model ([FlashPairingPhase], seconds remaining) consumed by `FlashPairingDialog` (UI-032).
 *
 * The engine machine has eight phases; the demo dialog has seven. The two "in-flight" engine
 * sub-states collapse into the single UI [FlashPairingPhase.RequestReceived], and a protocol
 * [PairingPhase.Failed] maps to [FlashPairingPhase.Failed] so failures are never misrepresented
 * as a peer decline.
 */
object PairingUiMapper {

    fun corePhaseToUi(phase: PairingPhase): FlashPairingPhase = when (phase) {
        PairingPhase.Idle -> FlashPairingPhase.Idle
        // v2 (ADR-042): neither side has a code before both nonces are known. The responder waits for
        // the initiator's reveal; the initiator waits for the responder's nonce. Rendering
        // AwaitingPeerNonce as "Waiting for <peer> to confirm" showed an empty code and no Accept
        // action, which reads as a broken/absent consent card (ERROR-1010). Keep both code-less
        // phases off the dialog; the consent card appears at AwaitingLocalDecision with the real code.
        PairingPhase.AwaitingPeerReveal,
        PairingPhase.AwaitingPeerNonce -> FlashPairingPhase.Idle
        // Both devices show the actionable consent card before they accept: the responder in RequestReceived /
        // AwaitingLocalDecision, the initiator in AwaitingLocalDecision (SEC-D03-01).
        PairingPhase.RequestReceived,
        PairingPhase.AwaitingLocalDecision -> FlashPairingPhase.RequestReceived
        PairingPhase.AwaitingPeerConfirmation -> FlashPairingPhase.AwaitingPeerConfirmation
        PairingPhase.Confirmed -> FlashPairingPhase.Paired
        PairingPhase.DeclinedByPeer -> FlashPairingPhase.Declined
        PairingPhase.Expired -> FlashPairingPhase.Expired
        PairingPhase.Failed -> FlashPairingPhase.Failed
    }

    /** Whole seconds left until [expiresAtMs], clamped to 0; 0 when there is no active deadline. */
    fun secondsLeft(expiresAtMs: Long?, nowMs: Long): Int {
        expiresAtMs ?: return 0
        return ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()
    }
}
