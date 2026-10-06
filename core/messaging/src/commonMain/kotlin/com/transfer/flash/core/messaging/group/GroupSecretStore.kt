package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.security.group.GroupSecret
import com.transfer.flash.core.security.group.GroupSecretCommit

/**
 * Origin source of a stored group secret.
 */
public enum class GroupSecretSource {
    CREATED,
    INVITE,
    HANDOVER,
    ROTATED,
}

/**
 * A persistent group secret entry (GM-2).
 *
 * Security:
 * - [toString] is redacted and NEVER exposes raw secret bytes in logs or traces.
 */
public class StoredGroupSecret(
    public val groupId: String,
    public val epoch: Long,
    public val secret: GroupSecret,
    public val commit: String = GroupSecretCommit.ofHex(groupId, epoch, secret),
    public val source: GroupSecretSource,
    public val receivedAtMs: Long,
) {
    override fun toString(): String =
        "StoredGroupSecret(groupId=$groupId, epoch=$epoch, commit=$commit, source=$source, receivedAtMs=$receivedAtMs)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredGroupSecret) return false
        return groupId == other.groupId &&
            epoch == other.epoch &&
            secret.constantTimeEquals(other.secret) &&
            commit == other.commit &&
            source == other.source &&
            receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + secret.hashCode()
        result = 31 * result + commit.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}

/**
 * Port for storing and retrieving group secrets safely (ADR-044, ADR-073, GM-2).
 *
 * Requirements:
 * - Only `current(groupId)`, `get(groupId, epoch)`, `put(record)`, `forget(groupId)`.
 * - Nothing returns the secret as a string.
 */
public interface GroupSecretStore {

    /** Returns the highest-epoch secret stored for [groupId], or null if none is stored. */
    public suspend fun current(groupId: String): StoredGroupSecret?

    /** Returns the secret stored for [groupId] at the specified [epoch], or null if none is stored. */
    public suspend fun get(groupId: String, epoch: Long): StoredGroupSecret?

    /** Stores or updates a [StoredGroupSecret]. */
    public suspend fun put(record: StoredGroupSecret)

    /** Forgets (deletes) all secrets stored for [groupId] (e.g. on leave or removal). */
    public suspend fun forget(groupId: String)
}
