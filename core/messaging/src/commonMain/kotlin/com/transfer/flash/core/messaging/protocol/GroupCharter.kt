package com.transfer.flash.core.messaging.protocol

/**
 * The owner-signed, immutable founding statement of a v2 group (ADR-044 V1, plan D2).
 *
 * It fixes who the owner is. The group id is derived from the owner's key and [nonce] (plan D1),
 * so this charter is the only one that can ever exist for [groupId]. Byte-valued fields are
 * base64 strings because that is both their wire and their storage form.
 */
public data class GroupCharter(
    val groupId: String,
    val name: String,
    val ownerId: String,
    /** Owner identity public key, X.509 SPKI, base64. */
    val ownerKey: String,
    val createdAt: Long,
    /** 16 random bytes, base64. Part of the group id derivation. */
    val nonce: String,
    val proto: Int = GroupPolicy.V2_PROTOCOL,
    /** Owner signature over the canonical charter bytes, base64. */
    val sig: String,
)
