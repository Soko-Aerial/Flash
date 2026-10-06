package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.ContentRoot

/**
 * Tracks peer strikes and bans for swarm content (§4l, §8.2 E-13, E-15, E-40).
 * 3 strikes ban that peer for that specific root.
 * Bans are held in-memory and reset across fresh engine starts.
 */
public class StrikeBook(
    public val maxStrikes: Int = 3,
) {
    // Insertion-ordered map to maintain strict determinism (INV-11)
    private val strikesByPeerAndRoot = LinkedHashMap<Pair<String, ContentRoot>, Int>()

    /**
     * Records a strike against [peerId] for [root]. Returns the new strike count.
     */
    public fun recordStrike(peerId: String, root: ContentRoot): Int {
        val key = peerId to root
        val current = strikesByPeerAndRoot[key] ?: 0
        val updated = current + 1
        strikesByPeerAndRoot[key] = updated
        return updated
    }

    /**
     * Checks if [peerId] has reached or exceeded [maxStrikes] for [root].
     */
    public fun isBanned(peerId: String, root: ContentRoot): Boolean {
        val count = strikesByPeerAndRoot[peerId to root] ?: 0
        return count >= maxStrikes
    }

    /**
     * Gets current strike count for [peerId] and [root].
     */
    public fun getStrikes(peerId: String, root: ContentRoot): Int {
        return strikesByPeerAndRoot[peerId to root] ?: 0
    }

    /**
     * Resets strikes for all peers and roots.
     */
    public fun clear() {
        strikesByPeerAndRoot.clear()
    }
}
