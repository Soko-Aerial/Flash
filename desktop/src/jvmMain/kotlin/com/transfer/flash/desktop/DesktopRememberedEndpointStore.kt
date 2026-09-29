package com.transfer.flash.desktop

import com.transfer.flash.core.network.remembered.RememberedEndpointStore
import com.transfer.flash.core.network.remembered.RememberedRoute
import com.transfer.flash.core.persistence.db.dao.RememberedEndpointDao
import com.transfer.flash.core.persistence.db.entity.RememberedEndpointEntity

/**
 * Room adapter for the network-owned [RememberedEndpointStore] port (DR1, ADR-047; ADR-024's
 * inversion). The Android twin is `RoomRememberedEndpointStore` in `core:engine`, which does not
 * depend on persistence on the JVM target, so the desktop module supplies its own. The mapping is
 * mechanical and pinned by the persistence module's own tests, not duplicated policy.
 */
internal class DesktopRememberedEndpointStore(
    private val dao: RememberedEndpointDao,
) : RememberedEndpointStore {

    override suspend fun loadAll(): List<RememberedRoute> = dao.all().map {
        RememberedRoute(it.deviceId, it.host, it.port, it.lastConnectedAt, it.firstFailureAt)
    }

    override suspend fun save(route: RememberedRoute) {
        dao.upsert(
            RememberedEndpointEntity(
                deviceId = route.deviceId,
                host = route.host,
                port = route.port,
                lastConnectedAt = route.lastConnectedAtMs,
                firstFailureAt = route.firstFailureAtMs,
            ),
        )
    }

    override suspend fun delete(deviceId: String, host: String, port: Int) {
        dao.delete(deviceId, host, port)
    }

    override suspend fun deleteForDevice(deviceId: String) {
        dao.deleteForDevice(deviceId)
    }
}
