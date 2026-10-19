package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the presence UI is allowed to claim about a peer, split into the two honest states.
 *
 * [online] is the live session set exactly as the transport reports it. [connecting] is the peers
 * whose session has just gone but whose reconnect grace has not expired yet — recovery is plausibly
 * under way, so the UI says "Connecting…" instead of either lying about a usable link or flashing
 * Offline through a sub-second session swap. The sets are disjoint.
 */
internal data class PresenceSnapshot(
    val online: Set<String>,
    val connecting: Set<String>,
    /**
     * PC3: peers discovery sees right now that are in neither set above. No link is claimed; a send
     * dials on demand. Disjoint from [online] and [connecting].
     */
    val reachable: Set<String> = emptySet(),
    /**
     * Peer ids the organisation hub reports as online (a live relay session there), in neither set
     * above. The peer is reachable — the hub is holding its socket — so the header may say "Online"
     * and name the relay as the transport; it is disjoint from [reachable] (which claims no
     * transport at all).
     */
    val hubOnline: Set<String> = emptySet(),
)

/** PC3: joins the discovery sightings onto the session snapshot as [PresenceSnapshot.reachable]. */
internal fun Flow<PresenceSnapshot>.withReachable(reachablePeerIds: Flow<Set<String>>): Flow<PresenceSnapshot> =
    combine(this, reachablePeerIds.distinctUntilChanged()) { snapshot, seen ->
        snapshot.copy(reachable = seen - snapshot.online - snapshot.connecting)
    }.distinctUntilChanged()

/**
 * Joins the hub's reported presence onto the snapshot as [PresenceSnapshot.hubOnline].
 *
 * Presentation only, like the grace and the discovery set: a send still goes through the hub only
 * when the hub actually carries it (`HubChatBridge`), so a stale "Online" here can never invent a
 * delivery. A hub-online peer leaves [PresenceSnapshot.reachable], because the hub *is* a named
 * transport for it and "seen by discovery, no link" would understate what we know.
 */
internal fun Flow<PresenceSnapshot>.withHubOnline(hubOnlinePeerIds: Flow<Set<String>>): Flow<PresenceSnapshot> =
    combine(this, hubOnlinePeerIds.distinctUntilChanged()) { snapshot, reported ->
        val online = reported - snapshot.online - snapshot.connecting
        snapshot.copy(reachable = snapshot.reachable - reported, hubOnline = online)
    }.distinctUntilChanged()

/** A direct chat's verdict: what the header may claim and which transport it can name. */
internal data class DirectLinkUi(
    val presence: FlashPeerPresence,
    val transport: FlashNetworkTransport,
    /**
     * The peer's typing member names, empty unless we still believe we can reach it: a typing
     * indicator sticks until the peer clears it, so a session that died mid-compose would otherwise
     * leave "typing…" on screen forever.
     */
    val typingNames: List<String>,
)

/**
 * The direct chat header's presence and transport, from the three presence sets and the peer's
 * reported typing names. Pure, so the precedence below is testable and identical for a LAN peer and
 * a hub-only colleague.
 *
 * Precedence: typing (only while reachable) > live LAN session > hub session > reconnect grace >
 * discovery sighting (no transport claimed) > offline.
 */
internal fun directLinkUi(
    conversationId: String,
    peers: PresenceSnapshot,
    typingNames: List<String>,
): DirectLinkUi {
    val isOnline = conversationId in peers.online
    // Hub presence (colleague with a live session on the organisation hub): a real link to the
    // peer, with the relay as its named transport, even though there is no LAN session.
    val isHubOnline = !isOnline && conversationId in peers.hubOnline
    val isConnecting = !isOnline && !isHubOnline && conversationId in peers.connecting
    val reachable = isOnline || isConnecting || isHubOnline
    val presence = when {
        typingNames.isNotEmpty() && reachable -> FlashPeerPresence.Typing
        isOnline -> FlashPeerPresence.Online
        isHubOnline -> FlashPeerPresence.Online
        // ERROR-031: the reconnect window is its own state. The header renders it as
        // "Connecting…" and the banner agrees, because resolveHealth tests Connecting
        // ahead of the (necessarily) Unknown transport below.
        isConnecting -> FlashPeerPresence.Connecting
        // PC3: seen by discovery, no session. The header says "Online" with a ring and
        // claims no transport, so the banner explains that a send will connect.
        conversationId in peers.reachable -> FlashPeerPresence.Reachable
        else -> FlashPeerPresence.Offline
    }
    // Without a transport the header's resolveHealth() short-circuits Unknown -> Offline and shows
    // "Searching for devices…" even when the peer has a live session. An active session is our
    // LAN/WS mesh link, so stamp Lan when online — and only when online: a peer mid-reconnect has no
    // link to name. A hub-online colleague has no LAN session but a known transport: the relay.
    val transport = when {
        isOnline -> FlashNetworkTransport.Lan
        isHubOnline -> FlashNetworkTransport.Relay
        else -> FlashNetworkTransport.Unknown
    }
    return DirectLinkUi(
        presence = presence,
        transport = transport,
        typingNames = if (reachable) typingNames else emptyList(),
    )
}

/**
 * Splits a live-session set into [PresenceSnapshot.online] / [PresenceSnapshot.connecting] with a
 * per-departure grace period of [holdMs].
 *
 * Rising edge is instant. On a falling edge the peer moves to `connecting` and a **hard deadline**
 * is stamped for it: `holdMs` after *that departure*, it becomes fully absent (in neither set)
 * whether or not anything else happened in between.
 *
 * ## Why this is not `transformLatest { … delay(holdMs) }` (ERROR-031)
 *
 * The previous implementation held the falling edge inside `transformLatest`, which cancels its
 * block on every upstream emission. A peer reconnecting every 1–4 s therefore cancelled the pending
 * Offline verdict before it could ever fire, and — because the same body emitted `shown + live`
 * first — the dot stayed **Online with an empty session set**, indefinitely. That is the "peer shows
 * Online while its app is not even running" report: the header promised a link the transport could
 * not deliver, so a send single-ticked into a socket that no longer existed.
 *
 * Here the timer is a *sibling* of the collector rather than its child, so no upstream traffic can
 * cancel it, and the deadline is stamped with `putIfAbsent` — a peer that keeps reappearing cannot
 * refresh its own deadline. A peer that is genuinely gone therefore reaches "absent" exactly
 * [holdMs] after its last departure, and while it is held it is reported *Connecting*, never Online.
 */
internal fun Flow<Set<String>>.withReconnectGrace(holdMs: Long): Flow<PresenceSnapshot> {
    // distinctUntilChanged on the UPSTREAM keeps a chatty source (a repeated identical set) from
    // re-walking the hold map on every duplicate; it is no longer load-bearing for correctness.
    val upstream = distinctUntilChanged()
    return channelFlow {
        // Guards the three pieces of mutable state below, which the collector and every pending
        // grace timer touch concurrently. `send` happens under the same lock so two publishers can
        // never invert the order of their snapshots downstream.
        val guard = Mutex()
        val graceStartedAt = HashMap<String, Long>()
        var live: Set<String> = emptySet()
        var published: PresenceSnapshot? = null

        suspend fun publish() {
            guard.withLock {
                // Common code cannot read the clock directly; the shared seam (Phase 06) instead.
                val now = SystemTimeSource.nowMs()
                // A grace ends when the peer is back (it is Online again) or when its own deadline
                // passes — never because some other peer's event arrived.
                graceStartedAt.entries.removeAll { (id, since) -> id in live || now - since >= holdMs }
                val next = PresenceSnapshot(online = live, connecting = graceStartedAt.keys.toSet())
                if (next != published) {
                    published = next
                    send(next)
                }
            }
        }

            upstream.collect { sessions ->
            val departed = guard.withLock {
                val gone = live - sessions
                live = sessions
                val now = SystemTimeSource.nowMs()
                // putIfAbsent, not put: a peer that flaps must not be able to push its own deadline
                // into the future on every bounce.
                gone.forEach { graceStartedAt.putIfAbsent(it, now) }
                gone.isNotEmpty()
            }
            publish()
            if (departed) {
                // One timer per departure event, launched as a sibling of this collector. Nothing
                // upstream can cancel it; the worst case is a redundant `publish()` that emits
                // nothing because the peer came back.
                launch {
                    delay(holdMs)
                    publish()
                }
            }
        }
    }
}
