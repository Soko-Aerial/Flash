package com.transfer.flash.core.swarm.driver

import kotlinx.coroutines.flow.Flow

/**
 * Group membership and cryptographic signing port for the swarm (§5.2, SW-8).
 */
public interface SwarmGroupContext {
    /** True only when [peerId] may exchange content of [groupId] with this device now (INV-3). */
    public suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean

    /**
     * True when this device may SERVE [groupId] content to [peerId]: the group's signed serving setting and this
     * device's own preference. Membership ([isPeerAllowed]) is checked separately. Defaults to membership only.
     */
    public suspend fun isServeAllowed(groupId: String, peerId: String): Boolean = isPeerAllowed(groupId, peerId)

    /** True only when this device is an active member of [groupId]. */
    public suspend fun isLocalActiveMember(groupId: String): Boolean

    /** Signs a domain-separated statement with this device's group key; null when cannot sign. */
    public suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray?

    /** Verifies against [authorKey] (stored with announcement), so a departed origin's cancel still verifies. */
    public fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean

    /** Looks up public key for [authorId] in [groupId]. */
    public suspend fun authorKey(groupId: String, authorId: String): String?

    /** Emits a group ID whenever that group's roster or trust changed. */
    public val membershipChanges: Flow<String>
}
