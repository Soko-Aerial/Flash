package com.transfer.flash.core.network.remembered

/**
 * An address a paired peer was last reached at (DR1, ADR-047). A dial hint only: identity is proven
 * by the TLS pin plus the HELLO binding, never by the address.
 *
 * @property lastConnectedAtMs wall-clock epoch millis of the last authenticated session over this
 *   address; persisted, so it must be wall-clock rather than monotonic.
 * @property firstFailureAtMs wall-clock epoch millis of the first failed dial since the last
 *   success, or null when the route last worked.
 */
public data class RememberedRoute(
    val deviceId: String,
    val host: String,
    val port: Int,
    val lastConnectedAtMs: Long,
    val firstFailureAtMs: Long? = null,
)

/**
 * Storage port for [RememberedRoutes] (ADR-024: `core:network` owns the port and pulls in no Room
 * types; `core:engine` and the desktop module supply the Room adapters). Every call may throw; the
 * policy class treats a storage failure as "run without persistence" and never lets it reach a dial.
 */
public interface RememberedEndpointStore {
    public suspend fun loadAll(): List<RememberedRoute>

    /** Inserts or replaces the row for `(deviceId, host, port)`. */
    public suspend fun save(route: RememberedRoute)

    public suspend fun delete(deviceId: String, host: String, port: Int)

    public suspend fun deleteForDevice(deviceId: String)
}
