package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import kotlinx.coroutines.flow.Flow

/**
 * TOFU pin store backing C2. `isPinned` matches on both device id AND fingerprint, so a peer
 * presenting a different key is no longer trusted (fail closed).
 */
@Dao
public interface TrustedPeerDao {

    /** @return row id of the inserted row, or -1 when already present. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(peer: TrustedPeerEntity): Long

    @Query("SELECT * FROM trusted_peers ORDER BY trustedAt DESC")
    public fun observeAll(): Flow<List<TrustedPeerEntity>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM trusted_peers " +
            "WHERE deviceId = :deviceId AND fingerprintHex = :fingerprintHex)",
    )
    public suspend fun isPinned(deviceId: String, fingerprintHex: String): Boolean

    @Query("DELETE FROM trusted_peers WHERE deviceId = :deviceId")
    public suspend fun revoke(deviceId: String)
}
