package com.transfer.flash.core.messaging.protocol

/**
 * Signed statement of a group's secret rotation (ADR-044, ADR-073, GM-6).
 *
 * It is signed by an admin or owner using canonical statement tag "flash-grot-v1".
 * It carries [commit], the SHA-256 rotation commitment to the new secret, but NEVER
 * the raw secret itself (GINV-1).
 */
public data class GroupRotation(
    val groupId: String,
    val newEpoch: Long,
    val prevEpoch: Long,
    val commit: String,
    val reason: String,
    val adminId: String,
    val rotationId: String,
    val removedIds: List<String> = emptyList(),
    val sig: String,
) {
    public companion object {
        public const val REASON_REMOVAL: String = "REMOVAL"
        public const val REASON_MANUAL: String = "MANUAL"
        public const val REASON_UPGRADE: String = "UPGRADE"
    }
}

public fun GroupRotation.toEntity(receivedAtMs: Long): com.transfer.flash.core.persistence.db.entity.GroupRotationEntity =
    com.transfer.flash.core.persistence.db.entity.GroupRotationEntity(
        groupId = groupId,
        newEpoch = newEpoch,
        prevEpoch = prevEpoch,
        commit = commit,
        reason = reason,
        adminId = adminId,
        rotationId = rotationId,
        removedIds = removedIds.filter { it.isNotBlank() }.joinToString(","),
        sig = sig,
        receivedAtMs = receivedAtMs,
    )

public fun com.transfer.flash.core.persistence.db.entity.GroupRotationEntity.toRotation(): GroupRotation =
    GroupRotation(
        groupId = groupId,
        newEpoch = newEpoch,
        prevEpoch = prevEpoch,
        commit = commit,
        reason = reason,
        adminId = adminId,
        rotationId = rotationId,
        removedIds = if (removedIds.isBlank()) emptyList() else removedIds.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        sig = sig,
    )

