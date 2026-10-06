package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot

/**
 * State of a connected peer for a specific content root (§4b).
 */
internal class PeerContentState(
    val root: ContentRoot,
    pieceCount: Int,
) {
    val bitfield: Bitfield = Bitfield(pieceCount)
    var servingEnabled: Boolean = true
    var backoffUntilMs: Long = 0L
    var ewmaSpeedBps: Double = 1_000_000.0 // Default 1 MB/s baseline
    var lastHaveSentMs: Long = 0L
    val pendingHavePieces = LinkedHashSet<Int>()

    /**
     * Updates EWMA speed after a piece arrival.
     * EWMA: speed = 0.8 * old + 0.2 * new.
     */
    fun updateSpeed(pieceBytes: Int, elapsedMs: Long) {
        if (elapsedMs <= 0) return
        val currentSpeedBps = (pieceBytes.toDouble() * 1000.0) / elapsedMs
        ewmaSpeedBps = (0.8 * ewmaSpeedBps) + (0.2 * currentSpeedBps)
    }
}

/**
 * Connection and capability state for a network peer (§4b).
 */
internal class PeerConnectionState(
    val peerId: String,
    var features: Set<String>,
    var isConnected: Boolean = true,
) {
    val hasSw1Feature: Boolean get() = "sw1" in features

    // (groupId) -> Boolean
    val allowedGroups = LinkedHashMap<String, Boolean>()

    // (root) -> PeerContentState
    val contentStates = LinkedHashMap<ContentRoot, PeerContentState>()

    fun getOrCreateContent(root: ContentRoot, pieceCount: Int): PeerContentState {
        return contentStates.getOrPut(root) { PeerContentState(root, pieceCount) }
    }
}
