package com.transfer.flash.core.calling

/**
 * Remote video tracks of a group call, one per participant (G1, `docs/calling/GROUP-VIDEO-PLAN.md`).
 *
 * Keyed by device id. A participant keeps its place when its track is replaced (renegotiation, a
 * rejoin), so the grid does not reorder; [newest] is the participant whose video arrived last, the
 * group session's answer for callers that still show only one remote video.
 *
 * Not thread-safe: the group session touches it only on the call media thread.
 */
internal class PeerTrackTable<T : Any> {

    private val tracks = LinkedHashMap<String, T>()
    private val arrival = ArrayList<String>()

    /** Sets [peerId]'s track; true when it changed. */
    fun put(peerId: String, track: T): Boolean {
        if (tracks[peerId] === track) return false
        tracks[peerId] = track
        arrival.remove(peerId)
        arrival.add(peerId)
        return true
    }

    /**
     * Removes [peerId]'s track, but only if it is still [expected] (when given): a leg torn down
     * late must not remove the track its replacement already published. True when it changed.
     */
    fun remove(peerId: String, expected: T? = null): Boolean {
        val current = tracks[peerId] ?: return false
        if (expected != null && current !== expected) return false
        tracks.remove(peerId)
        arrival.remove(peerId)
        return true
    }

    fun clear() {
        tracks.clear()
        arrival.clear()
    }

    /** An immutable copy in participant order (first arrival first). */
    fun snapshot(): Map<String, T> = LinkedHashMap(tracks)

    val newest: T? get() = arrival.lastOrNull()?.let(tracks::get)
}
