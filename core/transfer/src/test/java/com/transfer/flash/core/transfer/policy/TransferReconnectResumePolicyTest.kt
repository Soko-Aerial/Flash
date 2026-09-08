package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the auto-resume rule that turned a roam-killed send from "Failed until a human taps retry"
 * into a resumed one (ERROR-035).
 *
 * The assertions worth reading are the two bounds: [aBrokenSourceStopsReOfferingAfterTheCap] is the
 * battery/flood guard, and [anAttemptThatMovedBytesEarnsTheNextOne] is what stops that guard from
 * abandoning a big file on a link that roams more than three times.
 */
class TransferReconnectResumePolicyTest {

    private fun transfer(
        id: String,
        peer: String = PEER,
        direction: FlashTransferDirection = FlashTransferDirection.Sending,
        state: FlashTransferState = FlashTransferState.Failed,
        bytesDone: Long = 0L,
        sourceUri: String? = "content://media/external/file/42",
    ) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = "Peer $peer",
        fileName = "clip.mp4",
        direction = direction,
        bytesDone = bytesDone,
        bytesTotal = 2_000_000_000L,
        state = state,
        peerDeviceId = peer,
        sourceUri = sourceUri,
        wireFileId = "wire-$id",
    )

    @Test
    fun `a failed send to the returning peer is resumed`() {
        val policy = TransferReconnectResumePolicy()

        val resumed = policy.onPeerSessionUp(PEER, listOf(transfer("t1")))

        assertEquals(listOf(FlashTransferId("t1")), resumed)
        assertEquals(1, policy.attemptsFor(FlashTransferId("t1")))
    }

    @Test
    fun `transfers to a peer whose session is still down are left alone`() {
        // Resuming them would only fail — there is no session to carry the re-offer — and would spend
        // an attempt from a budget that exists to protect the peer that IS reachable.
        val policy = TransferReconnectResumePolicy()

        val resumed = policy.onPeerSessionUp(PEER, listOf(transfer("mine"), transfer("theirs", peer = OTHER)))

        assertEquals(listOf(FlashTransferId("mine")), resumed)
        assertEquals(0, policy.attemptsFor(FlashTransferId("theirs")))
    }

    @Test
    fun `only failed sends are eligible`() {
        val policy = TransferReconnectResumePolicy()
        val ineligible = listOf(
            // Paused is a user decision. A network hiccup must not override it.
            transfer("paused", state = FlashTransferState.Paused),
            // Cancelled is also a user decision, and a completed transfer has nothing left to send.
            transfer("cancelled", state = FlashTransferState.Cancelled),
            transfer("completed", state = FlashTransferState.Completed),
            // These three are still live as far as the repository is concerned; re-offering underneath
            // a running send would duplicate the session.
            transfer("offered", state = FlashTransferState.Offered),
            transfer("queued", state = FlashTransferState.Queued),
            transfer("transferring", state = FlashTransferState.Transferring),
            transfer("verifying", state = FlashTransferState.Verifying),
            // The receiver cannot restart a session; the sender re-offers and the receiver resumes.
            transfer("inbound", direction = FlashTransferDirection.Receiving),
            // relaunchSend has nothing to read without a source. fileName is a label, not a source.
            transfer("no-source", sourceUri = null),
            transfer("blank-source", sourceUri = "   "),
        )

        assertEquals(emptyList<FlashTransferId>(), policy.onPeerSessionUp(PEER, ineligible))
    }

    @Test
    fun aBrokenSourceStopsReOfferingAfterTheCap() {
        // The file was deleted, or the content URI permission lapsed. Every resume fails immediately
        // and lands back in Failed, and a flapping link supplies session-up edges indefinitely.
        val policy = TransferReconnectResumePolicy(maxAttemptsPerTransfer = 3)
        val stuck = listOf(transfer("gone", bytesDone = 0L))

        repeat(3) { attempt ->
            assertEquals("attempt ${attempt + 1} should still be allowed", 1, policy.onPeerSessionUp(PEER, stuck).size)
        }
        repeat(5) {
            assertEquals(emptyList<FlashTransferId>(), policy.onPeerSessionUp(PEER, stuck))
        }
        assertEquals(3, policy.attemptsFor(FlashTransferId("gone")))
    }

    @Test
    fun anAttemptThatMovedBytesEarnsTheNextOne() {
        // A 2 GB file crossing a mesh with more APs than the cap has attempts. Each resume ships some
        // bytes before the next roam kills it, so the budget must not run out.
        val policy = TransferReconnectResumePolicy(maxAttemptsPerTransfer = 3)
        var bytesDone = 0L

        repeat(10) { roam ->
            val resumed = policy.onPeerSessionUp(PEER, listOf(transfer("big", bytesDone = bytesDone)))
            assertEquals("roam $roam should resume", listOf(FlashTransferId("big")), resumed)
            bytesDone += 100_000_000L
        }
        assertEquals(1, policy.attemptsFor(FlashTransferId("big")))
    }

    @Test
    fun `progress is measured against the last attempt not against zero`() {
        // The subtle failure this guards: comparing against the *first* seen value would let a transfer
        // that moved once early keep its budget reset forever, and comparing against the current value
        // would never reset it at all.
        val policy = TransferReconnectResumePolicy(maxAttemptsPerTransfer = 2)

        assertEquals(1, policy.onPeerSessionUp(PEER, listOf(transfer("t", bytesDone = 0L))).size)
        assertEquals(1, policy.onPeerSessionUp(PEER, listOf(transfer("t", bytesDone = 4_096L))).size)
        // Two consecutive attempts from 4096 that achieved nothing: the first spends the budget, the
        // second is refused.
        assertEquals(1, policy.onPeerSessionUp(PEER, listOf(transfer("t", bytesDone = 4_096L))).size)
        assertEquals(emptyList<FlashTransferId>(), policy.onPeerSessionUp(PEER, listOf(transfer("t", bytesDone = 4_096L))))
    }

    @Test
    fun `bookkeeping for transfers the repository has dropped is pruned`() {
        // Called with the same snapshot as onPeerSessionUp, so the attempt map cannot outgrow the
        // repository's list over a long-lived process.
        val policy = TransferReconnectResumePolicy(maxAttemptsPerTransfer = 1)

        policy.onPeerSessionUp(PEER, listOf(transfer("kept"), transfer("dropped")))
        assertEquals(1, policy.attemptsFor(FlashTransferId("kept")))
        assertEquals(1, policy.attemptsFor(FlashTransferId("dropped")))

        policy.retainOnly(setOf(FlashTransferId("kept")))

        assertEquals(1, policy.attemptsFor(FlashTransferId("kept")))
        assertEquals(0, policy.attemptsFor(FlashTransferId("dropped")))
        // Pruning is not a budget refill for anything still live.
        assertTrue(policy.onPeerSessionUp(PEER, listOf(transfer("kept"))).isEmpty())
    }

    @Test
    fun `every eligible transfer to the peer is returned, in list order`() {
        // A peer can have several sends killed by one roam; returning only the first would leave the
        // rest waiting for the next network event, which on a stable link never comes.
        val policy = TransferReconnectResumePolicy()

        val resumed = policy.onPeerSessionUp(
            PEER,
            listOf(transfer("a"), transfer("skip", peer = OTHER), transfer("b"), transfer("c")),
        )

        assertEquals(listOf("a", "b", "c").map(::FlashTransferId), resumed)
    }

    @Test
    fun `budgets are tracked per transfer, not per peer`() {
        val policy = TransferReconnectResumePolicy(maxAttemptsPerTransfer = 1)

        assertEquals(1, policy.onPeerSessionUp(PEER, listOf(transfer("first"))).size)
        // A second, different transfer to the same peer has its own untouched budget.
        assertEquals(1, policy.onPeerSessionUp(PEER, listOf(transfer("second"))).size)
        assertTrue(policy.onPeerSessionUp(PEER, listOf(transfer("first"))).isEmpty())
    }

    private companion object {
        const val PEER = "device-peer"
        const val OTHER = "device-other"
    }
}
