package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.RememberedEndpointEntity

/** Backing rows for DR1's remembered routes; the policy (paired only, max four, expiry) lives above. */
@Dao
public interface RememberedEndpointDao {
    @Upsert
    public suspend fun upsert(route: RememberedEndpointEntity)

    @Query("SELECT * FROM remembered_endpoints ORDER BY deviceId, lastConnectedAt DESC")
    public suspend fun all(): List<RememberedEndpointEntity>

    @Query("DELETE FROM remembered_endpoints WHERE deviceId = :deviceId AND host = :host AND port = :port")
    public suspend fun delete(deviceId: String, host: String, port: Int)

    @Query("DELETE FROM remembered_endpoints WHERE deviceId = :deviceId")
    public suspend fun deleteForDevice(deviceId: String)
}
