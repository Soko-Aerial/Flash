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

    /** Bytes of the content this peer is known to hold (from its HAVE / HAVE_ALL), at its last announcement. */
    var heldBytes: Long = 0L

    /** Smoothed rate at which this peer's holdings grow, bytes per second. Zero until two samples exist. */
    var holdRateBps: Double = 0.0

    /** Time of the last announcement that increased [heldBytes]; 0 when none has. */
    var lastGrowthAtMs: Long = 0L

    private var anchorMs: Long = 0L
    private var anchorBytes: Long = 0L

    /**
     * Records that the peer now holds [bytes] (sender-side "who has how much" view). The first call only sets a
     * baseline: a peer that announces a large existing holding must not look like a burst. Later samples are folded
     * into [holdRateBps] at most about once per second, because HAVE frames arrive in batches (>= 1 s or >= 4 pieces).
     */
    fun noteHolding(bytes: Long, nowMs: Long) {
        if (bytes > heldBytes) lastGrowthAtMs = nowMs
        heldBytes = bytes
        if (anchorMs == 0L) {
            anchorMs = nowMs
            anchorBytes = bytes
            return
        }
        val elapsed = nowMs - anchorMs
        if (elapsed < RATE_SAMPLE_MIN_MS) return
        val instant = (bytes - anchorBytes).coerceAtLeast(0L) * 1000.0 / elapsed
        holdRateBps = if (holdRateBps == 0.0) instant else (0.6 * holdRateBps) + (0.4 * instant)
        anchorMs = nowMs
        anchorBytes = bytes
    }

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

private const val RATE_SAMPLE_MIN_MS = 800L

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

    /**
     * Roots this peer has listed in a Summary during the current session. The first listing of a root is the moment the peer
     * learns of that content, so it is the moment it needs our state (ERROR-121); [contentStates] cannot say this because
     * the Tick creates a state for every peer and content whether or not the peer ever heard of the root.
     */
    val summaryListedRoots = LinkedHashSet<ContentRoot>()

    fun getOrCreateContent(root: ContentRoot, pieceCount: Int): PeerContentState {
        return contentStates.getOrPut(root) { PeerContentState(root, pieceCount) }
    }
}
