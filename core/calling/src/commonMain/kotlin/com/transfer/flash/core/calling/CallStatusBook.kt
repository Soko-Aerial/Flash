package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallReaction
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the other participants' controls say (ADR-067), and the rules for the one-shot reaction.
 *
 * Callers are the signaling path, UI callbacks and a timer, on different threads, so the whole book is one immutable
 * [Book] swapped by compare-and-set: no lock, and a mutation is never half applied. Every update lambda is a pure
 * function of the previous book (it may run again on contention), and the values it hands back are computed inside it.
 *
 * The book trusts nothing it is not told: a participant that never sent a status (an older client) reads as mic on,
 * camera on, hand down, wanting video, which is what the call looked like before this frame existed.
 *
 * **Reactions.** A reaction is shown at most once per sequence number per sender, and a sender is held to
 * [MIN_REACTION_GAP_MS] between two of them, on both ends: the sender's own limit stops a button held down, and the
 * receiver's stops a client that ignores it from flooding the screen. The list kept for the UI is capped at
 * [MAX_REACTIONS] and entries leave after [REACTION_LIFETIME_MS].
 */
internal class CallStatusBook(
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
) {
    /** One participant's last stated controls. */
    data class Peer(
        val micOn: Boolean = true,
        val cameraOn: Boolean = true,
        val handRaised: Boolean = false,
        val receiveVideo: Boolean = true,
        /** ADR-102: presenting its screen. False until it says `ss=1`; an older client never does. */
        val sharing: Boolean = false,
        /** ADR-102: the presenter's start counter (see [ShareArbiter]); 0 when not presenting or not stated. */
        val shareStartedAt: Long = 0L,
    )

    private data class Book(
        val peers: Map<String, Peer> = emptyMap(),
        val lastSeq: Map<String, Long> = emptyMap(),
        val lastAt: Map<String, Long> = emptyMap(),
        val localSeq: Long = 0L,
        val localAt: Long = 0L,
        val nextId: Long = 1L,
        val reactions: List<FlashCallReaction> = emptyList(),
    )

    private val book = MutableStateFlow(Book())

    fun peer(peerId: String): Peer = book.value.peers[peerId] ?: Peer()

    /** Whether any video should go to [peerId]: false only after it said `rv=0`. */
    fun wantsVideo(peerId: String): Boolean = peer(peerId).receiveVideo

    /**
     * Folds [status] (from [peerId]) in. Returns the reaction it carried when that reaction was accepted and is now in
     * [activeReactions], else null.
     */
    fun apply(peerId: String, status: CallWireFrame.Status): FlashCallReaction? {
        var shown: FlashCallReaction? = null
        book.update { cur ->
            shown = null
            val before = cur.peers[peerId] ?: Peer()
            var next = cur.copy(
                peers = cur.peers + (
                    peerId to Peer(
                        micOn = status.micOn ?: before.micOn,
                        cameraOn = status.cameraOn ?: before.cameraOn,
                        handRaised = status.handRaised ?: before.handRaised,
                        receiveVideo = status.receiveVideo ?: before.receiveVideo,
                        sharing = status.sharing ?: before.sharing,
                        shareStartedAt = when (status.sharing) {
                            true -> status.shareStartedAt ?: before.shareStartedAt
                            false -> 0L
                            null -> before.shareStartedAt
                        },
                    )
                    ),
            )
            val kind = status.reaction
            if (kind != null && status.reactionSeq > (cur.lastSeq[peerId] ?: 0L)) {
                next = next.copy(lastSeq = next.lastSeq + (peerId to status.reactionSeq))
                val now = clock()
                val last = cur.lastAt[peerId]
                if (last == null || now - last >= MIN_REACTION_GAP_MS) {
                    val withReaction = next.addReaction(peerId, kind, now)
                    shown = withReaction.reactions.last()
                    next = withReaction.copy(lastAt = withReaction.lastAt + (peerId to now))
                }
            }
            next
        }
        return shown
    }

    /**
     * The sequence number for a reaction this device sends now, or null while it is inside [MIN_REACTION_GAP_MS] of its
     * last one. Wall-clock based and strictly growing, so a rejoined session is never taken for an old one.
     */
    fun nextLocalSeq(): Long? {
        var seq: Long? = null
        book.update { cur ->
            seq = null
            val now = clock()
            // A clock that stepped backwards must not hold the button for the size of the step.
            if (cur.localAt != 0L && now >= cur.localAt && now - cur.localAt < MIN_REACTION_GAP_MS) {
                cur
            } else {
                val next = maxOf(cur.localSeq + 1, now)
                seq = next
                cur.copy(localSeq = next, localAt = now)
            }
        }
        return seq
    }

    /** Records this device's own reaction so it shows on its own screen too. */
    fun addLocal(localId: String, kind: FlashCallReactionKind): FlashCallReaction {
        var added: FlashCallReaction? = null
        book.update { cur ->
            val next = cur.addReaction(localId, kind, clock())
            added = next.reactions.last()
            next
        }
        return added!!
    }

    /** Reactions still on screen now, oldest first. Drops the expired ones. */
    fun activeReactions(): List<FlashCallReaction> {
        val cutoff = clock() - REACTION_LIFETIME_MS
        return book.updateAndGetReactions(cutoff)
    }

    /** Forgets a participant (left the call). */
    fun forget(peerId: String) {
        book.update { it.copy(peers = it.peers - peerId, lastSeq = it.lastSeq - peerId, lastAt = it.lastAt - peerId) }
    }

    private fun Book.addReaction(peerId: String, kind: FlashCallReactionKind, now: Long): Book {
        val live = reactions.filter { it.atMs > now - REACTION_LIFETIME_MS }
        val reaction = FlashCallReaction(id = nextId, peerId = peerId, kind = kind, atMs = now)
        return copy(nextId = nextId + 1, reactions = (live + reaction).takeLast(MAX_REACTIONS))
    }

    private fun MutableStateFlow<Book>.updateAndGetReactions(cutoff: Long): List<FlashCallReaction> {
        update { cur ->
            if (cur.reactions.any { it.atMs <= cutoff }) cur.copy(reactions = cur.reactions.filter { it.atMs > cutoff }) else cur
        }
        return value.reactions
    }

    companion object {
        const val MIN_REACTION_GAP_MS = 400L
        const val REACTION_LIFETIME_MS = 3_500L
        const val MAX_REACTIONS = 8
    }
}
