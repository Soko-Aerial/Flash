package com.transfer.flash.core.network.mode

/**
 * What one device knows about its links, gathered by the host for the ECO rules (PC5).
 *
 * @property available peers that could hold a session now: discovery sightings, presence reports and
 *   current sessions.
 * @property contacts paired peers and fellow members of this device's active groups.
 * @property activity every registered session, keyed by peer id.
 * @property busy peers in an active call (transfers show up as user traffic in [activity]).
 * @property nearbyOpen the Nearby screen is on screen, so unpaired peers may be dialed for pairing.
 */
public class LinkView(
    public val available: Set<String>,
    public val contacts: Set<String>,
    public val activity: Map<String, LinkActivity>,
    public val busy: Set<String>,
    public val nearbyOpen: Boolean,
)

/**
 * One registered session as the ECO rules see it.
 *
 * @property outbound this device dialed it. ECO only ever parks a session it dialed.
 * @property userIdleMs time since the last text or binary frame, either way, that was not presence
 *   or link control (`WsConnection.lastUserTrafficAtMs`).
 */
public data class LinkActivity(val outbound: Boolean, val userIdleMs: Long)

/**
 * The ECO session rules of plan §3.4, as pure functions (PC5, ADR-048).
 *
 * ECO keeps sessions with:
 * 1. up to [ConnectionModePolicy.ECO_NEIGHBOURS] **ring neighbours** among the contacts that are
 *    available: every phone sorts the same ids the same way and takes its nearest successors and
 *    predecessors, so the phones of a group stay joined up without anyone coordinating;
 * 2. peers with user traffic in the last [ConnectionModePolicy.ECO_ACTIVE_WINDOW_MS];
 * 3. peers in an active call;
 * 4. unpaired peers, but only while the Nearby screen is open.
 *
 * Everything else is still **accepted** when the other side dials; ECO only stops dialing it, and
 * parks it when this side dialed it and it has gone idle ([ConnectionModeController]).
 */
public object EcoLinkSelector {

    /**
     * The ring neighbours of [localDeviceId] among [candidates]: successor, predecessor, second
     * successor, second predecessor and so on, until [count] distinct peers are picked.
     */
    public fun neighbours(
        localDeviceId: String,
        candidates: Collection<String>,
        count: Int = ConnectionModePolicy.ECO_NEIGHBOURS,
    ): Set<String> {
        val ring = (candidates.toSet() + localDeviceId).sorted()
        if (ring.size <= 1 || count <= 0) return emptySet()
        val self = ring.indexOf(localDeviceId)
        val out = LinkedHashSet<String>()
        var step = 1
        while (out.size < count && out.size < ring.size - 1) {
            out += ring[(self + step).mod(ring.size)]
            if (out.size < count) out += ring[(self - step).mod(ring.size)]
            step++
        }
        return out
    }

    /** The peers ECO wants a session with right now: rules 1–4 above. Never contains [localDeviceId]. */
    public fun wanted(localDeviceId: String, view: LinkView): Set<String> {
        val out = HashSet<String>()
        out += neighbours(localDeviceId, view.contacts.filterTo(HashSet()) { it in view.available })
        for ((peer, activity) in view.activity) {
            if (activity.userIdleMs < ConnectionModePolicy.ECO_ACTIVE_WINDOW_MS) out += peer
        }
        out += view.busy
        if (view.nearbyOpen) view.available.filterTo(out) { it !in view.contacts }
        out -= localDeviceId
        return out
    }

    /**
     * Sessions this device should ask to park: ones it dialed, idle for
     * [ConnectionModePolicy.ECO_IDLE_PARK_MS], with a peer it does not want.
     */
    public fun parkCandidates(localDeviceId: String, view: LinkView, wanted: Set<String> = wanted(localDeviceId, view)): Set<String> =
        view.activity.filterTo(LinkedHashMap()) { (peer, activity) ->
            activity.outbound && activity.userIdleMs >= ConnectionModePolicy.ECO_IDLE_PARK_MS && peer !in wanted
        }.keys
}
