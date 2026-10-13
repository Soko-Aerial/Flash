package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The closed set of device-certificate roles (SEC-18). A certificate carrying any other role string never verifies,
 * on either side, whoever signed it.
 *
 * The role names are the ones already in issued certificates and persisted credentials: `member`, `dept_admin`,
 * `admin`. (`admin` is the organisation-wide administrator; the directory's own `ORG_ADMIN` / `DEPT_ADMIN` /
 * `MEMBER` are display values of the REST DTOs, mapped by [toDirectoryRole].)
 */
@FlashInternalApi
public object HubRoles {
    public const val MEMBER: String = "member"
    public const val DEPT_ADMIN: String = "dept_admin"
    public const val ADMIN: String = "admin"

    public val ALL: Set<String> = setOf(MEMBER, DEPT_ADMIN, ADMIN)

    public fun isValid(role: String): Boolean = role in ALL

    /** The delegation scope a signer needs in order to issue a certificate with [role]. */
    public fun enrolScope(role: String): String = "enrol:$role"

    /** Directory REST role name for a certificate role ([com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues]). */
    public fun toDirectoryRole(role: String): String = when (role) {
        ADMIN -> "ORG_ADMIN"
        DEPT_ADMIN -> "DEPT_ADMIN"
        else -> "MEMBER"
    }
}

/**
 * Delegation scope strings and the ONE wildcard rule both sides use (SEC-18).
 *
 * Granted scopes are matched against a requested scope like this, and in no other way:
 * 1. an exact match (`enrol:member` grants `enrol:member`);
 * 2. the bare `*` grants every scope;
 * 3. `prefix:*` grants every scope of the form `prefix:<anything non-empty>`.
 *
 * There is no partial wildcard (`enr*`), no leading wildcard (`*:member`) and no case folding.
 * Exception for safety: the `admin` role is NEVER granted by a wildcard. A delegation must list `enrol:admin`
 * explicitly ([grantsExplicitly]), so a `*` or `enrol:*` delegation to a server cannot mint organisation admins.
 */
@FlashInternalApi
public object HubScopes {
    public const val ENROL_MEMBER: String = "enrol:member"
    public const val ENROL_DEPT_ADMIN: String = "enrol:dept_admin"
    public const val ENROL_ADMIN: String = "enrol:admin"
    public const val REVOKE_MEMBER: String = "revoke:member"

    public const val WILDCARD: String = "*"

    public fun grants(granted: Set<String>, requested: String): Boolean {
        if (requested.isEmpty()) return false
        if (requested in granted) return true
        if (WILDCARD in granted) return true
        val colon = requested.indexOf(':')
        if (colon <= 0 || colon == requested.length - 1) return false
        return "${requested.substring(0, colon)}:*" in granted
    }

    /** True only for a literal entry; wildcards do not count. */
    public fun grantsExplicitly(granted: Set<String>, requested: String): Boolean = requested in granted
}
