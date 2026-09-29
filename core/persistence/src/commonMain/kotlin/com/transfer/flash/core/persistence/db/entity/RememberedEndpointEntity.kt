package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity

/**
 * An address a **paired** peer was last reached at (DR1, `docs/network/DISCOVERY-RESILIENCE-PLAN.md`
 * §3.3 A, ADR-047). It is a dial hint only: identity is proven by the TLS pin and the HELLO binding,
 * so a stale row costs one failed dial and can never reach the wrong device.
 *
 * A peer can have several rows (Wi-Fi, hotspot, Ethernet); the store keeps at most four per peer.
 * Rows are written only after an authenticated session, never from a discovery sighting.
 *
 * @property lastConnectedAt epoch millis of the last authenticated session over this address.
 * @property firstFailureAt epoch millis of the first failed dial since the last success, or null.
 *   Together with an in-memory failure count it drives expiry (a route only expires after failing
 *   over several days, not after a laptop was briefly off).
 */
@Entity(tableName = "remembered_endpoints", primaryKeys = ["deviceId", "host", "port"])
public data class RememberedEndpointEntity(
    val deviceId: String,
    val host: String,
    val port: Int,
    val lastConnectedAt: Long,
    val firstFailureAt: Long? = null,
)
