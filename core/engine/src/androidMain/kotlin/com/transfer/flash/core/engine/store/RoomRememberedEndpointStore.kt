package com.transfer.flash.core.engine.store

import com.transfer.flash.core.network.remembered.RememberedEndpointStore
import com.transfer.flash.core.network.remembered.RememberedRoute
import com.transfer.flash.core.persistence.db.dao.RememberedEndpointDao
import com.transfer.flash.core.persistence.db.entity.RememberedEndpointEntity

/**
 * Room adapter for the network-owned [RememberedEndpointStore] port (DR1, ADR-047; the same
 * inversion as [RoomTransferStore], ADR-024). It lives here because `core:engine` is the one module
 * that sees both `core:network` and `core:persistence`. The desktop has its own twin in its module,
 * for the same reason, since `core:engine` does not depend on persistence on the JVM target.
 */
public class RoomRememberedEndpointStore(
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
