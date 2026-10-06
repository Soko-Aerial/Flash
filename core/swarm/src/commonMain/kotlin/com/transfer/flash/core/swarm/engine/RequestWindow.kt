package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.ContentRoot

/**
 * Manages AIMD peer request windows and global in-flight byte budgets (§4e, INV-12).
 */
public class RequestWindow(
    public val budgetBytes: Long,
    public val initialWindow: Int = 4,
    public val maxWindow: Int = 64,
    public val minWindow: Int = 1,
) {
    private val windows = LinkedHashMap<Pair<String, ContentRoot>, Int>()
    private var inFlightBytes: Long = 0L
    private var isCallActive: Boolean = false

    public val currentInFlightBytes: Long get() = inFlightBytes

    /**
     * Gets the current window size for [peerId] and [root].
     */
    public fun getWindow(peerId: String, root: ContentRoot): Int {
        val base = windows[peerId to root] ?: initialWindow
        return if (isCallActive) maxOf(minWindow, base / 2) else base
    }

    /**
     * Called when a piece arrives from [peerId]: increases window by 1 up to [maxWindow] (Additive Increase).
     */
    public fun onPieceCompleted(peerId: String, root: ContentRoot) {
        val key = peerId to root
        val current = windows[key] ?: initialWindow
        if (current < maxWindow) {
            windows[key] = current + 1
        }
    }

    /**
     * Called on BUSY rejection or request timeout: halves the window down to [minWindow] (Multiplicative Decrease).
     */
    public fun onCongestion(peerId: String, root: ContentRoot) {
        val key = peerId to root
        val current = windows[key] ?: initialWindow
        windows[key] = maxOf(minWindow, current / 2)
    }

    /**
     * Checks if requesting [additionalBytes] stays within the global profile budget.
     */
    public fun canRequestBytes(additionalBytes: Long): Boolean {
        return inFlightBytes + additionalBytes <= budgetBytes
    }

    /**
     * Records additional bytes currently in-flight across the network.
     */
    public fun addInFlightBytes(bytes: Long) {
        require(bytes >= 0) { "bytes must be >= 0" }
        inFlightBytes += bytes
    }

    /**
     * Deducts completed or cancelled bytes from the in-flight budget.
     */
    public fun removeInFlightBytes(bytes: Long) {
        require(bytes >= 0) { "bytes must be >= 0" }
        inFlightBytes = maxOf(0L, inFlightBytes - bytes)
    }

    /**
     * Adjusts window scaling during active voice/video calls (§4n, ripple 19).
     */
    public fun setCallActive(active: Boolean) {
        isCallActive = active
    }

    /**
     * Clears window state for disconnected or removed peer.
     */
    public fun removePeer(peerId: String, root: ContentRoot) {
        windows.remove(peerId to root)
    }

    /**
     * Clears all state.
     */
    public fun clear() {
        windows.clear()
        inFlightBytes = 0L
    }
}
