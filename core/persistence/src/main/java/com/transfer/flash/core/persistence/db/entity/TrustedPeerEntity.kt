package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A paired/trusted device (TOFU pin record, consumed by C2). [fingerprintHex] is part of the
 * pin: `isPinned(deviceId, fingerprintHex)` fails closed when the peer's key changes.
 */
@Entity(tableName = "trusted_peers", indices = [Index(value = ["trustedAt"])])
public data class TrustedPeerEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val fingerprintHex: String,
    val trustedAt: Long,
)
