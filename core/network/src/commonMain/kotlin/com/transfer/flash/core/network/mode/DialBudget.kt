package com.transfer.flash.core.network.mode

/**
 * How many sessions STANDARD and BOOST ask for when a crowd is around (ADR-057).
 *
 * Both modes dial every discovered device, which is right for a room of friends and wrong for a
 * conference hall: a device would fill its whole session ceiling with strangers, and the paired peers
 * and group members that matter would then be refused ("session cap reached") and retried every 5 s.
 * While the number of devices a phone could hold a session with fits the [limit], nothing changes
 * ([allowed] is null: dial everyone, exactly as before). Above it, the phone keeps the sessions it has
 * and spends the remaining slots on **busy peers first, then contacts, then everyone else**, so a
 * 20-member group still meshes in a crowd.
 *
 * Only dials are limited. A session a stranger opens to this phone is still admitted up to the
 * ceiling (`SessionHardeningPolicy`), which is why the limit stays [ConnectionModePolicy.DIAL_HEADROOM]
 * below it: those slots are for peers that dial in, dial on demand, and a session being replaced.
 * Dial on demand, gateway probes and sweep hits ignore the filter, as they do for ECO.
 *
 * Pure and deterministic: ranking inside a class is by device id, so the same view always gives the
 * same set, and a peer that goes live moves from "to dial" into "held" without disturbing the rest.
 */
public object DialBudget {

    /**
     * The peers the connection planner may dial, or null for every discovered peer.
     *
     * @param limit the most sessions (held plus newly dialed) this device wants right now.
     */
    public fun allowed(localDeviceId: String, view: LinkView, limit: Int): Set<String>? {
        val held = view.activity.keys - localDeviceId
        val busy = view.busy - localDeviceId
        val dialable = (view.available + view.busy) - localDeviceId - held
        if (held.size + dialable.size <= limit) return null

        val busyToDial = busy.filterTo(HashSet()) { it in dialable }
        val ranked = ArrayList<String>(dialable.size)
        ranked += busyToDial.sorted()
        ranked += dialable.filter { it in view.contacts && it !in busyToDial }.sorted()
        ranked += dialable.filter { it !in view.contacts && it !in busyToDial }.sorted()

        val slots = (limit - held.size - busyToDial.size).coerceAtLeast(0)
        // Busy peers are always dialable: a call is the one thing a full phone must still be able to reach.
        return held + busyToDial + ranked.drop(busyToDial.size).take(slots)
    }
}
