package com.transfer.flash.core.network.presence

/**
 * Timings and limits for presence sharing, one per connection mode (plan §3.4; PC5 added [ECO] and
 * [BOOST]).
 *
 * The receiver's rate limit is the same in every mode (burst 10, one more per 250 ms), because it
 * guards against a sender in any mode, including a BOOST sender whose deltas may come every 250 ms.
 * How long a report is held is decided by the receiver from its own [maxAgeMs] **and** the sender's
 * announced refresh ([PresenceState.holdFor]), so an ECO reporter's 60 s refresh never makes its
 * reports flicker out on a STANDARD or BOOST receiver.
 */
public data class PresenceConfig(
    /** Digest and hello cadence per session. */
    val refreshMs: Long = 30_000L,
    /** A report older than this (reporter's age plus our holding time) is dropped. */
    val maxAgeMs: Long = 45_000L,
    /** Deltas to one peer are coalesced to at most one frame per this gap. */
    val minFrameGapMs: Long = 1_000L,
    /** Per-sender token bucket: burst size, and one token back per [rateRefillMs]. */
    val rateCapacity: Int = 10,
    val rateRefillMs: Long = 250L,
    /** Consecutive failed endpoint tips after which a sender is ignored for the session. */
    val failedTipsToIgnore: Int = 3,
) {
    public companion object {
        public val STANDARD: PresenceConfig = PresenceConfig()

        /** Battery first: half as many presence frames, reports held accordingly longer. */
        public val ECO: PresenceConfig = PresenceConfig(refreshMs = 60_000L, maxAgeMs = 90_000L)

        /** Status within about a second: fast refresh, near-instant deltas, short holding. */
        public val BOOST: PresenceConfig = PresenceConfig(refreshMs = 10_000L, maxAgeMs = 20_000L, minFrameGapMs = 250L)

        /**
         * Extra holding for a relayed (2-hop) report on top of the relayer's hold. A relayed age can
         * reach the origin reporter's refresh interval before the relayer's next report replaces
         * it, and the longest refresh is [PresenceCodec.MAX_REFRESH_MS] (ECO), so 1.5x that.
         */
        public const val RELAY_ALLOWANCE_MS: Long = PresenceCodec.MAX_REFRESH_MS * 3 / 2
    }
}

/**
 * What this device knows right now, gathered by the host once per step.
 *
 * @property ghost this device is in Ghost mode: it reports nothing and asks peers not to report it.
 * @property sessions every registered session, keyed by peer id. The value identifies the session
 *   object, so a drop and reconnect between two steps is still seen as a new session.
 * @property live the peers whose session has carried traffic recently (ERROR-031). Only these are
 *   reported as [PresenceReportState.Connected].
 * @property seen discovery's current sightings, with the endpoint discovery resolved.
 * @property trusted paired peers.
 * @property pinned peers whose TLS key is pinned. Only these may be dialed from a tip, so a forged
 *   tip can never make trust-on-first-use record the wrong key.
 * @property rosters the member ids of every active group this device belongs to.
 */
public class PresenceLocalView(
    public val ghost: Boolean,
    public val sessions: Map<String, Any>,
    public val live: Set<String>,
    public val seen: Map<String, PresenceEndpoint?>,
    public val trusted: Set<String>,
    public val pinned: Set<String>,
    public val rosters: List<Set<String>>,
) {
    /** A discovery sighting as the host sees it. */
    public data class Sighting(val deviceId: String, val host: String, val port: Int)

    public companion object {
        /**
         * The one way the hosts build a view. Only the peers that can matter ([trusted] and
         * roster members) are checked with [hasPin], and a sighting whose address could not be
         * dialed from a tip (a name, loopback, a bad port) is shared without an endpoint rather than
         * rejected whole by the receiver.
         */
        public fun of(
            ghost: Boolean,
            sessions: Map<String, Any>,
            isLive: (String) -> Boolean,
            sightings: List<Sighting>,
            trusted: Set<String>,
            hasPin: (String) -> Boolean,
            rosters: List<Set<String>>,
        ): PresenceLocalView {
            val contacts = HashSet(trusted).apply { rosters.forEach { addAll(it) } }
            return PresenceLocalView(
                ghost = ghost,
                sessions = sessions,
                live = sessions.keys.filterTo(HashSet(), isLive),
                seen = sightings.associate { s ->
                    s.deviceId to PresenceEndpoint(s.host, s.port)
                        .takeIf { s.port in 1..65_535 && PresenceCodec.isDialableHost(s.host) }
                },
                trusted = trusted,
                pinned = contacts.filterTo(HashSet(), hasPin),
                rosters = rosters,
            )
        }
    }
}

/** An endpoint a peer reported for [deviceId], which discovery does not see, from [source]. */
public data class PresenceTip(
    val deviceId: String,
    val endpoint: PresenceEndpoint,
    val source: String,
)

/**
 * The whole presence-sharing protocol as a deterministic state machine (PC4, plan §3.2, ADR-046).
 * Not thread-safe: [PresenceExchange] drives it from one coroutine. Every method takes the time and
 * the [PresenceLocalView], so tests control both.
 *
 * ## Who may learn about whom
 * A peer R is told about a device S only when all of these hold:
 * - R is **eligible**: paired with this device, or a fellow member of one of its groups. Frames from
 *   anyone else are dropped and nothing is sent to them.
 * - S is a **mutual contact**: R and S share one of this device's groups, or R's `want` list
 *   contains `H(salt, S)` under the salt this device sent R. R only hashes its own contacts, so
 *   this device learns nothing about contacts it does not know already.
 * - S **opted in**. For S observed directly, S's own hello to this device said `share=1`. Default
 *   deny: an old client, or a device whose hello has not arrived, is never reported. For S relayed
 *   from another report, S has not told this device `share=0`.
 * - S is not R, not this device, and not learned from R (split horizon).
 *
 * ## Safety of what is received
 * Reports never touch trust. An entry is kept only when its subject is one of this device's
 * contacts, and it expires at [holdFor] (a relayed one [PresenceConfig.RELAY_ALLOWANCE_MS] later).
 * Frames are rate-limited per sender. A tip is
 * offered for dialing only for a pinned subject that discovery does not see, and a sender whose tips
 * fail [PresenceConfig.failedTipsToIgnore] times in a row is ignored until its session reopens.
 */
internal class PresenceState(
    private val localDeviceId: String,
    /** Replaced by [PresenceExchange] when the connection mode changes (PC5). */
    var config: PresenceConfig,
    private val sha256: (ByteArray) -> ByteArray,
    private val randomSalt: () -> ByteArray,
) {
    data class Outgoing(val peerId: String, val frame: PresenceFrame)

    data class Step(val out: List<Outgoing>, val nextInMs: Long)

    /** @property limitMs the age after which this report is dropped, fixed when it was received. */
    private class Stored(val entry: PresenceEntry, val receivedAtMs: Long, val limitMs: Long)

    private inner class Peer(var token: Any?, val createdAtMs: Long) {
        val salt: String = PresenceCodec.bytesToHex(randomSalt().copyOf(PresenceCodec.SALT_BYTES))
        var helloSentAtMs: Long? = null
        var helloShareSent: Boolean? = null
        var helloRefreshSent: Long? = null
        /** The refresh interval the peer's hello announced (PC5), or null for a PC4 peer. */
        var theirRefreshMs: Long? = null
        var theirSalt: String? = null
        var theirWant: Set<String> = emptySet()
        var wantSent: Set<String>? = null
        var lastSent: Map<String, PresenceEntry>? = null
        var lastDigestAtMs: Long? = null
        var lastFrameAtMs: Long = Long.MIN_VALUE / 2
        var tokens: Int = config.rateCapacity
        var tokensAtMs: Long = createdAtMs
        var tipFailures: Int = 0
        var ignored: Boolean = false
        val hashCache = HashMap<String, String>()

        fun hashOf(subject: String): String =
            hashCache.getOrPut(subject) { PresenceCodec.matchHash(sha256, salt, subject) }
    }

    private val peers = HashMap<String, Peer>()

    /** Each device's own latest answer to "may others report you" (from its hello). */
    private val shareFlags = HashMap<String, Boolean>()

    /** sender -> subject -> report. */
    private val reports = HashMap<String, HashMap<String, Stored>>()

    // ---------------------------------------------------------------- inbound

    /** Applies one frame from [from]. Replies go out on the next [step]. */
    fun onFrame(nowMs: Long, from: String, frame: PresenceFrame, view: PresenceLocalView): Boolean {
        if (from == localDeviceId || !isEligible(from, view)) return false
        val peer = peerFor(from, view.sessions[from], nowMs)
        if (peer.ignored || !takeToken(peer, nowMs)) return false
        when (frame) {
            is PresenceFrame.Hello -> {
                shareFlags[from] = frame.share
                peer.theirRefreshMs = frame.refreshMs
                if (frame.salt != peer.theirSalt) {
                    peer.theirSalt = frame.salt
                    peer.wantSent = null
                }
            }
            is PresenceFrame.Want -> {
                peer.theirWant = frame.hashes
                peer.lastDigestAtMs = null
            }
            is PresenceFrame.Report -> {
                val contacts = contactsOf(view)
                val board = if (frame.full) HashMap() else reports.getOrPut(from) { HashMap() }
                val hold = holdFor(peer)
                for (e in frame.entries) {
                    if (e.deviceId == localDeviceId || e.deviceId == from || e.deviceId !in contacts) continue
                    val limit = if (e.hops >= 2) hold + PresenceConfig.RELAY_ALLOWANCE_MS else hold
                    if (e.ageMs > limit) continue
                    if (e.state == PresenceReportState.Gone) {
                        board.remove(e.deviceId)
                    } else {
                        board[e.deviceId] = Stored(e, nowMs, limit)
                    }
                }
                if (board.isEmpty()) reports.remove(from) else reports[from] = board
            }
        }
        return true
    }

    /** Result of dialing [tip]; repeated failures silence its source for the rest of its session. */
    fun onTipResult(tip: PresenceTip, success: Boolean) {
        val source = tip.source
        val peer = peers[source] ?: return
        if (success) {
            peer.tipFailures = 0
            return
        }
        peer.tipFailures++
        if (peer.tipFailures >= config.failedTipsToIgnore) {
            peer.ignored = true
            reports.remove(source)
        }
    }

    // ---------------------------------------------------------------- periodic

    /** Syncs sessions, expires reports, and returns the frames due now plus when to step again. */
    fun step(nowMs: Long, view: PresenceLocalView): Step {
        syncSessions(nowMs, view)
        var next = config.refreshMs
        next = minOf(next, expireReports(nowMs) ?: next)

        val out = ArrayList<Outgoing>()
        val contacts = contactsOf(view)
        for ((peerId, peer) in peers) {
            if (peer.token == null || !isEligible(peerId, view)) continue

            // Hello: on session open, every refresh, when Ghost mode flips, and when the connection
            // mode changes our refresh interval (PC5).
            val share = !view.ghost
            val helloAt = peer.helloSentAtMs
            if (helloAt == null || nowMs - helloAt >= config.refreshMs || peer.helloShareSent != share ||
                peer.helloRefreshSent != config.refreshMs
            ) {
                out += Outgoing(peerId, PresenceFrame.Hello(share, if (share) peer.salt else null, config.refreshMs))
                peer.helloSentAtMs = nowMs
                peer.helloShareSent = share
                peer.helloRefreshSent = config.refreshMs
            }
            next = minOf(next, (peer.helloSentAtMs ?: nowMs) + config.refreshMs - nowMs)

            // Want: whenever their salt is new or our contacts changed.
            val theirSalt = peer.theirSalt
            if (theirSalt != null) {
                val want = contacts.asSequence()
                    .filter { it != peerId }
                    .sorted()
                    .take(PresenceCodec.MAX_WANT)
                    .mapTo(HashSet()) { PresenceCodec.matchHash(sha256, theirSalt, it) }
                if (want != peer.wantSent) {
                    out += Outgoing(peerId, PresenceFrame.Want(want))
                    peer.wantSent = want
                }
            }

            // Reports: none in Ghost mode; withdraw what the peer holds from before.
            if (view.ghost) {
                if (!peer.lastSent.isNullOrEmpty()) {
                    out += Outgoing(peerId, PresenceFrame.Report(full = true, entries = emptyList()))
                    peer.lastFrameAtMs = nowMs
                }
                peer.lastSent = emptyMap()
                peer.lastDigestAtMs = nowMs
                continue
            }
            val entries = reportFor(peerId, peer, nowMs, view)
            val lastSent = peer.lastSent
            val digestAt = peer.lastDigestAtMs
            if (lastSent == null || digestAt == null || nowMs - digestAt >= config.refreshMs) {
                if (entries.isNotEmpty() || !lastSent.isNullOrEmpty()) {
                    out += Outgoing(peerId, PresenceFrame.Report(full = true, entries = entries.values.toList()))
                    peer.lastFrameAtMs = nowMs
                }
                peer.lastSent = entries
                peer.lastDigestAtMs = nowMs
                next = minOf(next, config.refreshMs)
            } else {
                next = minOf(next, digestAt + config.refreshMs - nowMs)
                val delta = deltaOf(lastSent, entries)
                if (delta.isNotEmpty()) {
                    val dueAt = peer.lastFrameAtMs + config.minFrameGapMs
                    if (nowMs >= dueAt) {
                        out += Outgoing(peerId, PresenceFrame.Report(full = false, entries = delta))
                        peer.lastSent = entries
                        peer.lastFrameAtMs = nowMs
                    } else {
                        next = minOf(next, dueAt - nowMs)
                    }
                }
            }
        }
        return Step(out, next.coerceAtLeast(1L))
    }

    // ---------------------------------------------------------------- outputs

    /** Contacts some eligible peer currently reports, excluding silenced senders. */
    fun reachable(nowMs: Long, view: PresenceLocalView): Set<String> {
        val contacts = contactsOf(view)
        val out = HashSet<String>()
        for ((sender, board) in reports) {
            if (peers[sender]?.ignored == true) continue
            for ((subject, stored) in board) {
                if (subject in contacts && effectiveAge(stored, nowMs) <= stored.limitMs) out += subject
            }
        }
        return out
    }

    /**
     * Endpoints worth dialing: pinned subjects with no session that discovery does not see, from the
     * report with the fewest hops, then the freshest.
     */
    fun tips(nowMs: Long, view: PresenceLocalView): Map<String, PresenceTip> {
        val reachable = reachable(nowMs, view)
        val best = HashMap<String, Pair<Stored, String>>()
        for ((sender, board) in reports) {
            if (peers[sender]?.ignored == true) continue
            for ((subject, stored) in board) {
                if (subject !in reachable || stored.entry.endpoint == null) continue
                if (subject !in view.pinned || subject in view.sessions || subject in view.seen) continue
                val current = best[subject]
                if (current == null || isBetter(stored, current.first, nowMs)) best[subject] = stored to sender
            }
        }
        return best.mapValues { (subject, pair) ->
            PresenceTip(subject, pair.first.entry.endpoint!!, pair.second)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun reportFor(
        receiver: String,
        peer: Peer,
        nowMs: Long,
        view: PresenceLocalView,
    ): Map<String, PresenceEntry> {
        val out = LinkedHashMap<String, PresenceEntry>()
        val known = HashSet<String>()
        // Hop 1: our own observations. Direct observation always wins over any relay.
        for (subject in view.live) {
            known += subject
            if (mayShare(receiver, peer, subject, direct = true, view)) {
                out[subject] = PresenceEntry(subject, 0L, PresenceReportState.Connected, 1, view.seen[subject])
            }
        }
        for ((subject, endpoint) in view.seen) {
            if (!known.add(subject)) continue
            if (mayShare(receiver, peer, subject, direct = true, view)) {
                out[subject] = PresenceEntry(subject, 0L, PresenceReportState.Seen, 1, endpoint)
            }
        }
        // Hop 2: relay first-hand reports from other senders, freshest per subject.
        val relays = HashMap<String, PresenceEntry>()
        for ((sender, board) in reports) {
            if (sender == receiver || peers[sender]?.ignored == true) continue
            for ((subject, stored) in board) {
                if (stored.entry.hops != 1 || subject in known) continue
                val age = effectiveAge(stored, nowMs)
                if (age > stored.limitMs) continue
                if (!mayShare(receiver, peer, subject, direct = false, view)) continue
                val current = relays[subject]
                if (current == null || age < current.ageMs) {
                    relays[subject] = stored.entry.copy(ageMs = age, hops = 2)
                }
            }
        }
        out.putAll(relays)
        return if (out.size <= PresenceCodec.MAX_ENTRIES) out else out.entries.take(PresenceCodec.MAX_ENTRIES)
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    private fun mayShare(receiver: String, peer: Peer, subject: String, direct: Boolean, view: PresenceLocalView): Boolean {
        if (subject == receiver || subject == localDeviceId) return false
        val flag = shareFlags[subject]
        if (if (direct) flag != true else flag == false) return false
        if (view.rosters.any { receiver in it && subject in it }) return true
        return peer.theirWant.isNotEmpty() && peer.hashOf(subject) in peer.theirWant
    }

    private fun deltaOf(previous: Map<String, PresenceEntry>, current: Map<String, PresenceEntry>): List<PresenceEntry> {
        val out = ArrayList<PresenceEntry>()
        for ((subject, e) in current) {
            val p = previous[subject]
            if (p == null || p.state != e.state || p.hops != e.hops || p.endpoint != e.endpoint) out += e
        }
        for (subject in previous.keys) {
            if (subject !in current) out += PresenceEntry(subject, 0L, PresenceReportState.Gone, 1)
        }
        return out
    }

    private fun syncSessions(nowMs: Long, view: PresenceLocalView) {
        for ((id, token) in view.sessions) {
            if (id != localDeviceId) peerFor(id, token, nowMs)
        }
        peers.entries.removeAll { (id, peer) ->
            id !in view.sessions && (peer.token != null || nowMs - peer.createdAtMs >= config.refreshMs)
        }
    }

    /**
     * The per-session state for [id]. Same id with a different session object means the old
     * session died: the new one starts clean (new salt, hello, want, digest; silencing is per
     * session). [onFrame] calls this too, so a hello that arrives on a new session before the next
     * [step] is applied to the new state instead of being wiped by it.
     */
    private fun peerFor(id: String, token: Any?, nowMs: Long): Peer {
        val peer = peers[id]
        return when {
            peer == null || (token != null && peer.token != null && peer.token !== token) ->
                Peer(token, nowMs).also { peers[id] = it }
            peer.token == null && token != null -> peer.also { it.token = token }
            else -> peer
        }
    }

    /** Drops expired reports; returns ms until the next one expires, or null when none remain. */
    private fun expireReports(nowMs: Long): Long? {
        var next: Long? = null
        val emptySenders = ArrayList<String>()
        for ((sender, board) in reports) {
            board.entries.removeAll { (_, stored) -> effectiveAge(stored, nowMs) > stored.limitMs }
            if (board.isEmpty()) emptySenders += sender
            for (stored in board.values) {
                val left = stored.limitMs - effectiveAge(stored, nowMs) + 1
                next = minOf(next ?: left, left)
            }
        }
        emptySenders.forEach { reports.remove(it) }
        return next
    }

    /**
     * How long a first-hand report from [peer] is held (PC5): our own [PresenceConfig.maxAgeMs], or
     * 1.5x the sender's announced refresh when that is longer, so a slower sender's reports survive
     * until its next digest.
     */
    private fun holdFor(peer: Peer): Long =
        maxOf(config.maxAgeMs, (peer.theirRefreshMs ?: 0L) * 3 / 2)

    private fun takeToken(peer: Peer, nowMs: Long): Boolean {
        val refilled = ((nowMs - peer.tokensAtMs) / config.rateRefillMs).toInt()
        if (refilled > 0) {
            peer.tokens = minOf(config.rateCapacity, peer.tokens + refilled)
            peer.tokensAtMs += refilled * config.rateRefillMs
        }
        if (peer.tokens <= 0) return false
        peer.tokens--
        return true
    }

    private fun isBetter(a: Stored, b: Stored, nowMs: Long): Boolean =
        a.entry.hops < b.entry.hops ||
            (a.entry.hops == b.entry.hops && effectiveAge(a, nowMs) < effectiveAge(b, nowMs))

    private fun effectiveAge(stored: Stored, nowMs: Long): Long =
        stored.entry.ageMs + (nowMs - stored.receivedAtMs).coerceAtLeast(0L)

    private fun isEligible(peerId: String, view: PresenceLocalView): Boolean =
        peerId in view.trusted || view.rosters.any { peerId in it }

    private fun contactsOf(view: PresenceLocalView): Set<String> {
        val out = HashSet<String>(view.trusted)
        view.rosters.forEach { out.addAll(it) }
        out.remove(localDeviceId)
        return out
    }
}
