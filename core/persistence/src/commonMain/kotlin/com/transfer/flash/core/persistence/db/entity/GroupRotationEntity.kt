package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Storage for group rotation notices (ADR-044, ADR-073, GM-6).
 *
 * Each entry represents a signed statement by an owner or admin that [groupId]
 * has advanced to [newEpoch], with SHA-256 commitment [commit] to the new secret,
 * [reason] ("REMOVAL", "MANUAL", "UPGRADE"), [adminId], [rotationId] (16 bytes hex),
 * and [removedIds] (comma-separated list of removed member device IDs).
 */
@Entity(
    tableName = "group_rotation",
    primaryKeys = ["groupId", "newEpoch"],
    indices = [Index(value = ["groupId"])],
)
public data class GroupRotationEntity(
    val groupId: String,
    val newEpoch: Long,
    val prevEpoch: Long,
    val commit: String,
    val reason: String,
    val adminId: String,
    val rotationId: String,
    val removedIds: String,
    val sig: String,
    val receivedAtMs: Long,
)
