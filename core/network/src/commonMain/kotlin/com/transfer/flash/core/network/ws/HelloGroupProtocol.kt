package com.transfer.flash.core.network.ws

/**
 * The group protocol level a peer advertised in its HELLO (`gv`, ADR-044 V1).
 *
 * A HELLO without the field, or with one that is not a positive number, is level 1: an older build
 * that predates v2 groups. The value is capped so a hostile peer cannot make a nonsense level look
 * newer than anything this build knows about; callers compare it with `>=`.
 */
internal fun parseGroupProtocol(raw: String?): Int =
    raw?.toIntOrNull()?.takeIf { it >= 1 }?.coerceAtMost(MAX_GROUP_PROTOCOL) ?: 1

private const val MAX_GROUP_PROTOCOL = 255
