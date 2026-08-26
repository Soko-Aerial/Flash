package com.transfer.flash.pairing

import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.ui.chat.FlashPairingPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure engine → UI pairing mapping seam ([PairingUiMapper]).
 * No Android runtime needed — this is enum + arithmetic mapping only.
 */
class PairingUiMapperTest {

    @Test
    fun corePhaseToUi_mapsEveryEnginePhase() {
        assertEquals(FlashPairingPhase.Idle, PairingUiMapper.corePhaseToUi(PairingPhase.Idle))
        assertEquals(FlashPairingPhase.RequestReceived, PairingUiMapper.corePhaseToUi(PairingPhase.RequestReceived))
        // Both responder pre-accept sub-states collapse to the actionable consent card.
        assertEquals(FlashPairingPhase.RequestReceived, PairingUiMapper.corePhaseToUi(PairingPhase.AwaitingLocalDecision))
        assertEquals(FlashPairingPhase.AwaitingPeerConfirmation, PairingUiMapper.corePhaseToUi(PairingPhase.AwaitingPeerConfirmation))
        assertEquals(FlashPairingPhase.Paired, PairingUiMapper.corePhaseToUi(PairingPhase.Confirmed))
        assertEquals(FlashPairingPhase.Declined, PairingUiMapper.corePhaseToUi(PairingPhase.DeclinedByPeer))
        assertEquals(FlashPairingPhase.Expired, PairingUiMapper.corePhaseToUi(PairingPhase.Expired))
        // No dedicated failure card exists; a protocol failure reads as a decline (docs mapping table).
        assertEquals(FlashPairingPhase.Declined, PairingUiMapper.corePhaseToUi(PairingPhase.Failed))
    }

    @Test
    fun corePhaseToUi_isTotalOverAllPhases() {
        // Guards against a new PairingPhase silently defaulting: every enum entry must map.
        PairingPhase.entries.forEach { phase ->
            // Throws (non-exhaustive when) at compile time if a case is missing; here we just
            // assert the call succeeds for every value.
            PairingUiMapper.corePhaseToUi(phase)
        }
    }

    @Test
    fun secondsLeft_nullDeadlineIsZero() {
        assertEquals(0, PairingUiMapper.secondsLeft(expiresAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun secondsLeft_flooredWholeSeconds() {
        // 4500ms remaining floors to 4 whole seconds.
        assertEquals(4, PairingUiMapper.secondsLeft(expiresAtMs = 5_500L, nowMs = 1_000L))
    }

    @Test
    fun secondsLeft_clampsNegativeToZero() {
        assertEquals(0, PairingUiMapper.secondsLeft(expiresAtMs = 1_000L, nowMs = 9_000L))
    }

    @Test
    fun secondsLeft_exactBoundary() {
        assertEquals(30, PairingUiMapper.secondsLeft(expiresAtMs = 30_000L, nowMs = 0L))
    }
}
