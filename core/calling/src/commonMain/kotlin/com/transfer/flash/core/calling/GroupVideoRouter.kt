package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashParticipantVideo
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.calling.protocol.VideoDenyReason
import com.transfer.flash.core.common.perf.FlashNetworkBand
import com.transfer.flash.core.common.perf.FlashPerformanceMode

/**
 * How many videos a group participant receives and sends, and at what height
 * (`docs/calling/GROUP-VIDEO-PLAN.md` §4.2, owner decisions Q3/Q4/Q8).
 *
 * - [receive]: videos this device asks for at once.
 * - [send]: on a fast band, copies of its own video it sends at once. On 2.4 GHz ([splitBudget])
 *   it is a budget counted in 540p copies: a 360p copy costs half, so the sender serves up to
 *   twice as many watchers by stepping every copy down to 360p (G4).
 * - [quality]: the height this device asks senders for.
 * - [maxSendHeight]: the tallest copy it sends (the camera profile may cap it lower).
 * - [acceptNew]: false while the device is too hot (G6); new requests are turned down.
 * - [smallerForMany]: the user's opt-in "Send smaller video in groups" (ADR-053, off by default):
 *   the more copies this device sends, the smaller each one ([heightForCopies]).
 *
 * "Fast" is 5 GHz, 6 GHz or Ethernet. An unknown band keeps today's behaviour for receiving
 * (everything) and the table's default for sending.
 */
internal data class GroupVideoLimits(
    val receive: Int,
    val send: Int,
    val quality: Int,
    val splitBudget: Boolean = false,
    val maxSendHeight: Int = HEIGHT_720,
    val acceptNew: Boolean = true,
    val smallerForMany: Boolean = false,
) {
    /** Most watchers this device serves at once (twice [send] at 360p under the split budget). */
    val capacity: Int get() = if (splitBudget) send * 2 else send

    companion object {
        const val HEIGHT_720 = 720
        const val HEIGHT_540 = 540
        const val HEIGHT_360 = 360

        /**
         * @param struggling the device is hot or overloaded (G6): a LOW device then asks for
         *   360p instead of 540p (owner decision Q4).
         * @param receiveCap an upper bound on [receive] (G6: "Show fewer", or a severe thermal state).
         * @param acceptNew see [GroupVideoLimits.acceptNew].
         * @param smallerForMany see [GroupVideoLimits.smallerForMany].
         */
        fun of(
            tier: FlashPerformanceMode,
            band: FlashNetworkBand?,
            struggling: Boolean = false,
            receiveCap: Int? = null,
            acceptNew: Boolean = true,
            smallerForMany: Boolean = false,
        ): GroupVideoLimits {
            val slow = band == FlashNetworkBand.WIFI_2_4GHZ
            val fast = band == FlashNetworkBand.ETHERNET || band == FlashNetworkBand.WIFI_5GHZ ||
                band == FlashNetworkBand.WIFI_6GHZ
            val tall = if (slow) HEIGHT_540 else HEIGHT_720
            val base = when (tier) {
                FlashPerformanceMode.LOW -> GroupVideoLimits(
                    receive = 1,
                    send = 1,
                    quality = if (struggling) HEIGHT_360 else HEIGHT_540,
                    maxSendHeight = HEIGHT_540,
                )
                FlashPerformanceMode.MEDIUM -> GroupVideoLimits(receive = 2, send = 2, quality = tall, maxSendHeight = tall)
                FlashPerformanceMode.HIGH -> GroupVideoLimits(
                    receive = if (slow) 3 else 5,
                    send = when {
                        slow -> 3
                        fast -> 5
                        else -> 4
                    },
                    quality = tall,
                    maxSendHeight = tall,
                )
            }
            return base.copy(
                receive = receiveCap?.let { minOf(it, base.receive) } ?: base.receive,
                splitBudget = slow,
                acceptNew = acceptNew,
                smallerForMany = smallerForMany,
            )
        }

        /**
         * The tallest copy under [smallerForMany] when this device sends [copies] copies of its
         * video (ADR-053): one keeps the full height, two go at 540p, three or more at 360p. The
         * mesh runs one encoder per copy, so this cuts the encode work (the largest share of a
         * desktop call's CPU, ERROR-078) roughly in proportion to the pixels.
         */
        fun heightForCopies(copies: Int): Int = when {
            copies >= 3 -> HEIGHT_360
            copies == 2 -> HEIGHT_540
            else -> HEIGHT_720
        }

        /**
         * The bitrate ceiling for one copy of [height] (VP8, plan §4.2 estimates; G0 replaces
         * them): 720p 1.8 Mbps, 540p 0.9 Mbps, 360p 0.45 Mbps.
         */
        fun maxBitrateKbps(height: Int): Int = when {
            height >= HEIGHT_720 -> 1_800
            height >= HEIGHT_540 -> 900
            else -> 450
        }
    }
}

/**
 * The G3 request protocol for one device in a group video call: which participants' video it
 * asks for (receiver side) and whom it sends its own video to (sender side). Pure and
 * single-threaded: the session serializes every call and carries out the returned [Effect]s.
 *
 * **Receiver.** It wants, in order: the pinned participant ([setFocus]), the followed speaker
 * (a speaker must hold the floor for [speakerHoldMs] before the view moves, owner decision Q1),
 * then everyone else in call order, up to the receive limit. It skips a participant that turned
 * it down until that participant announces free capacity, so the slot goes to the next one.
 *
 * **Sender.** It sends to a peer only after that peer asks (R1), up to its capacity. A new
 * request at capacity is denied, unless this device is talking: then its least recently
 * requested unpinned watcher makes room (owner decision Q5). Every copy is sent at one height
 * (G4): the lower of what the watcher asked for and the sender's level. Under the 2.4 GHz split
 * budget the level drops to 360p as soon as the watchers no longer fit at 540p, and climbs back
 * only after they have fit for [stepUpMs], so it does not flap. A peer that did not announce the
 * protocol (`vr=1`) is an old client and is sent video exactly as before G3; a peer that has
 * not announced anything yet is sent nothing until it does.
 *
 * **Sequence numbers.** Every request and release this device sends carries a number larger
 * than any before it (at least the wall clock), and a sender ignores anything not newer than
 * the last number it saw from that peer. So a late request cannot undo a release, and a
 * rejoined session is never mistaken for an old one. A grant or deny counts only if it echoes
 * the receiver's current number.
 *
 * **A dropped connection is not a release.** Grants survive a leg being rebuilt; only
 * [onPeerLeft] (hang-up or signaling timeout) or a release ends them. The two ends notice a
 * reconnect at different times, so releasing on it would race the re-request.
 */
internal class GroupVideoRouter(
    private val callId: String,
    private val localId: String,
    private val limits: () -> GroupVideoLimits,
    /** Participants that count (joining, connected, or briefly disconnected), in call order. */
    private val participants: () -> List<String>,
    private val clock: () -> Long,
    private val speakerHoldMs: Long = 2_000L,
    private val stepUpMs: Long = 5_000L,
) {
    sealed interface Effect {
        data class Send(val frame: CallWireFrame, val peerId: String) : Effect

        /**
         * Switch the encoding of the connection to [peerId] on or off. [height] is the copy's
         * height when on; null means "as before G3" (an old client: the full camera profile).
         */
        data class Sending(val peerId: String, val on: Boolean, val height: Int? = null) : Effect

        /** Tell [peerId] (it was turned down) that there is room now: a presence with `vfree`. */
        data class Announce(val peerId: String) : Effect
    }

    private enum class Capability { UNKNOWN, REQUESTS, LEGACY }

    private class Watcher(val seq: Long, val quality: Int, val focus: Boolean, val requestedAt: Long)

    private class Asked(val seq: Long, val quality: Int, val focus: Boolean, var granted: Boolean = false)

    private val capability = HashMap<String, Capability>()

    // Sender side.
    private val watchers = LinkedHashMap<String, Watcher>()
    private val lastSeqFrom = HashMap<String, Long>()
    private val turnedDown = LinkedHashSet<String>()
    /** What each leg's encoding was last switched to: [OFF], [FULL] or a height. */
    private val applied = HashMap<String, Int>()
    private var localSpeaking = false
    private var cameraOff = false
    private var splitLevel = GroupVideoLimits.HEIGHT_540
    private var stepUpAt: Long? = null

    // Receiver side.
    private var receiving = false
    private val asked = LinkedHashMap<String, Asked>()
    private val blocked = HashMap<String, VideoDenyReason>()
    private var lastSeq = 0L
    private var pinned: String? = null
    private var followed: String? = null
    private var candidate: String? = null
    private var candidateSince = 0L

    /** Whether the connection to [peerId] should carry this device's video. */
    fun isSending(peerId: String): Boolean =
        capability[peerId] == Capability.LEGACY || peerId in watchers

    /** How many more watchers this device would take now; the `vfree` it announces. */
    fun freeSlots(): Int {
        val limit = limits()
        if (cameraOff || !limit.acceptNew) return 0
        return (limit.capacity - watchers.size).coerceAtLeast(0)
    }

    /** The height of the copy sent to [peerId]: null for an old client (full profile), 0 when off. */
    fun sendHeight(peerId: String): Int? = when {
        capability[peerId] == Capability.LEGACY -> null
        else -> watchers[peerId]?.let { minOf(it.quality, level()) } ?: 0
    }

    /**
     * The one height all of this device's copies are sent at now (before each watcher's own ask).
     * With [GroupVideoLimits.smallerForMany] on it also follows how many watchers there are; the
     * step is immediate both ways, since it changes only with a watcher arriving or leaving.
     */
    fun level(): Int {
        val limit = limits()
        val level = if (limit.splitBudget) minOf(splitLevel, limit.maxSendHeight) else limit.maxSendHeight
        return if (limit.smallerForMany) minOf(level, GroupVideoLimits.heightForCopies(watchers.size)) else level
    }

    /** The participant the view follows while nothing is pinned (for the LOW layout, G5). */
    val followedPeer: String? get() = followed

    /** What this device's view of [peerId]'s video is, for the participant list. */
    fun receiveState(peerId: String): FlashParticipantVideo {
        if (capability[peerId] == Capability.LEGACY) return FlashParticipantVideo.UNMANAGED
        asked[peerId]?.let { return if (it.granted) FlashParticipantVideo.RECEIVING else FlashParticipantVideo.REQUESTED }
        return when (blocked[peerId]) {
            null -> FlashParticipantVideo.OFF
            VideoDenyReason.CAMERA_OFF -> FlashParticipantVideo.CAMERA_OFF
            VideoDenyReason.SENDER_AT_CAPACITY, VideoDenyReason.THERMAL -> FlashParticipantVideo.BUSY
        }
    }

    val pinnedPeer: String? get() = pinned

    /**
     * This device has joined the call (its media is up): from now on it asks for video. Until
     * then it only records what peers announce, so a phone that is still ringing asks nobody.
     */
    fun startReceiving(): List<Effect> {
        receiving = true
        return reconcile()
    }

    /**
     * A frame the peer sent itself (never a relayed one) said whether it speaks the protocol.
     * An old client never says so, and from then on is sent video as before G3.
     */
    fun onAnnouncement(peerId: String, videoRequests: Boolean, videoFree: Int? = null): List<Effect> {
        if (peerId == localId) return emptyList()
        val before = capability[peerId] ?: Capability.UNKNOWN
        // Never downgrade: a peer that once spoke the protocol keeps it.
        val after = if (videoRequests || before == Capability.REQUESTS) Capability.REQUESTS else Capability.LEGACY
        capability[peerId] = after
        if (videoFree != null && videoFree > 0) blocked.remove(peerId)
        return reconcile()
    }

    fun onRequest(peerId: String, frame: CallWireFrame.VideoRequest): List<Effect> {
        if (!isNewer(peerId, frame.seq)) return emptyList()
        capability[peerId] = Capability.REQUESTS
        val out = mutableListOf<Effect>()
        val now = clock()
        val existing = watchers[peerId]
        when {
            existing != null -> watchers[peerId] = Watcher(frame.seq, frame.quality, frame.focus, now)
            cameraOff -> {
                turnedDown += peerId
                out += deny(peerId, frame.seq, VideoDenyReason.CAMERA_OFF)
                return out + sendingChanges()
            }
            !limits().acceptNew -> {
                turnedDown += peerId
                out += deny(peerId, frame.seq, VideoDenyReason.THERMAL)
                return out + sendingChanges()
            }
            watchers.size < limits().capacity -> watchers[peerId] = Watcher(frame.seq, frame.quality, frame.focus, now)
            else -> {
                val evict = if (localSpeaking) {
                    watchers.entries.filter { !it.value.focus }.minByOrNull { it.value.requestedAt }?.key
                } else {
                    null
                }
                if (evict == null) {
                    turnedDown += peerId
                    out += deny(peerId, frame.seq, VideoDenyReason.SENDER_AT_CAPACITY)
                    return out + sendingChanges()
                }
                val gone = watchers.remove(evict)!!
                turnedDown += evict
                out += deny(evict, gone.seq, VideoDenyReason.SENDER_AT_CAPACITY)
                watchers[peerId] = Watcher(frame.seq, frame.quality, frame.focus, now)
            }
        }
        turnedDown -= peerId
        adjustLevel(now)
        out += Effect.Send(CallWireFrame.VideoGrant(callId, localId, frame.seq, minOf(frame.quality, level())), peerId)
        return out + sendingChanges()
    }

    fun onRelease(peerId: String, frame: CallWireFrame.VideoRelease): List<Effect> {
        if (!isNewer(peerId, frame.seq)) return emptyList()
        if (watchers.remove(peerId) == null) return emptyList()
        adjustLevel(clock())
        return sendingChanges() + roomAnnouncements()
    }

    fun onGrant(peerId: String, frame: CallWireFrame.VideoGrant): List<Effect> {
        val current = asked[peerId]
        if (current == null || current.seq != frame.seq) return emptyList()
        current.granted = true
        return emptyList()
    }

    fun onDeny(peerId: String, frame: CallWireFrame.VideoDeny): List<Effect> {
        val current = asked[peerId]
        if (current == null || current.seq != frame.seq) return emptyList()
        asked.remove(peerId)
        blocked[peerId] = frame.reason
        return reconcile()
    }

    /** The peer hung up or timed out: everything between us ends. */
    fun onPeerLeft(peerId: String): List<Effect> {
        if (watchers.remove(peerId) != null) adjustLevel(clock())
        turnedDown.remove(peerId)
        asked.remove(peerId)
        blocked.remove(peerId)
        val stop = if ((applied.remove(peerId) ?: OFF) != OFF) listOf(Effect.Sending(peerId, on = false)) else emptyList()
        if (pinned == peerId) pinned = null
        if (followed == peerId) followed = null
        if (candidate == peerId) candidate = null
        return stop + roomAnnouncements() + reconcile()
    }

    fun setCameraOff(off: Boolean): List<Effect> {
        cameraOff = off
        return if (off) emptyList() else roomAnnouncements()
    }

    fun setLocalSpeaking(speaking: Boolean) {
        localSpeaking = speaking
    }

    /** Pins [peerId]'s video (a tap), or returns to following the speaker with null. */
    fun setFocus(peerId: String?): List<Effect> {
        pinned = peerId?.takeIf { it != localId }
        if (pinned != null) blocked.remove(pinned)
        return reconcile()
    }

    /**
     * The remote participants heard speaking in the latest stats sample. The view follows a new
     * speaker only once the same one has been chosen for [speakerHoldMs]; silence keeps the
     * current one.
     */
    fun onSpeakers(speaking: Set<String>, now: Long = clock()): List<Effect> {
        val current = followed
        if (current != null && current in speaking) {
            candidate = null
            return emptyList()
        }
        val next = participants().firstOrNull { it in speaking }
        if (next == null) {
            candidate = null
            return emptyList()
        }
        if (candidate != next) {
            candidate = next
            candidateSince = now
            return emptyList()
        }
        if (now - candidateSince < speakerHoldMs) return emptyList()
        followed = next
        candidate = null
        return reconcile()
    }

    /**
     * The periodic step (every stats sample): climbs back to 540p once the split budget has fit
     * for [stepUpMs], and re-checks the requests, since the limits can change with the band,
     * the heat (G6) or the user's "Show fewer".
     */
    fun tick(now: Long = clock()): List<Effect> {
        // The band may have changed since the watchers last did.
        adjustLevel(now)
        val limit = limits()
        val at = stepUpAt
        if (limit.splitBudget && at != null && now >= at && watchers.size <= limit.send) {
            splitLevel = GroupVideoLimits.HEIGHT_540
            stepUpAt = null
        }
        return reconcile()
    }

    /**
     * Brings the requests in line with what this device wants now: releases what it no longer
     * wants, asks for what it newly wants, and re-asks when the pin or the height changed.
     */
    fun reconcile(): List<Effect> {
        if (!receiving) return sendingChanges()
        val limit = limits()
        val present = participants().filter { it != localId }
        val eligible = present.filter { capability[it] == Capability.REQUESTS && it !in blocked }
        val ordered = (listOfNotNull(pinned, followed).filter { it in eligible } + eligible).distinct()
        val want = ordered.take(limit.receive.coerceAtLeast(0))
        val out = mutableListOf<Effect>()
        asked.keys.toList().filter { it !in want }.forEach { peerId ->
            asked.remove(peerId)
            out += Effect.Send(CallWireFrame.VideoRelease(callId, localId, nextSeq()), peerId)
        }
        want.forEach { peerId ->
            val focus = peerId == pinned
            val current = asked[peerId]
            if (current == null || current.focus != focus || current.quality != limit.quality) {
                val seq = nextSeq()
                asked[peerId] = Asked(seq, limit.quality, focus)
                out += Effect.Send(CallWireFrame.VideoRequest(callId, localId, seq, limit.quality, focus), peerId)
            }
        }
        return out + sendingChanges()
    }

    /**
     * Keeps the split-budget level honest after the watchers changed: down to 360p at once when
     * they no longer fit at 540p, and a step-up timer when they fit again.
     */
    private fun adjustLevel(now: Long) {
        val limit = limits()
        if (!limit.splitBudget) {
            splitLevel = GroupVideoLimits.HEIGHT_540
            stepUpAt = null
            return
        }
        if (watchers.size > limit.send) {
            splitLevel = GroupVideoLimits.HEIGHT_360
            stepUpAt = null
        } else if (splitLevel < GroupVideoLimits.HEIGHT_540 && stepUpAt == null) {
            stepUpAt = now + stepUpMs
        }
    }

    private fun isNewer(peerId: String, seq: Long): Boolean {
        val last = lastSeqFrom[peerId]
        if (last != null && seq <= last) return false
        lastSeqFrom[peerId] = seq
        return true
    }

    private fun nextSeq(): Long {
        lastSeq = maxOf(lastSeq + 1, clock())
        return lastSeq
    }

    private fun deny(peerId: String, seq: Long, reason: VideoDenyReason): Effect =
        Effect.Send(CallWireFrame.VideoDeny(callId, localId, seq, reason), peerId)

    private fun roomAnnouncements(): List<Effect> {
        if (freeSlots() <= 0 || turnedDown.isEmpty()) return emptyList()
        val out = turnedDown.map { Effect.Announce(it) }
        turnedDown.clear()
        return out
    }

    /** The encodings whose on/off state or height has to change, in a stable order. */
    private fun sendingChanges(): List<Effect> {
        val peers = LinkedHashSet<String>().apply {
            addAll(applied.keys)
            addAll(watchers.keys)
            capability.filterValues { it == Capability.LEGACY }.keys.forEach { add(it) }
        }
        return peers.mapNotNull { peerId ->
            val height = sendHeight(peerId)
            val want = height?.takeIf { it > 0 } ?: if (height == null) FULL else OFF
            if ((applied[peerId] ?: OFF) == want) return@mapNotNull null
            applied[peerId] = want
            Effect.Sending(peerId, on = want != OFF, height = height?.takeIf { it > 0 })
        }
    }

    private companion object {
        const val OFF = 0
        const val FULL = -1
    }
}
