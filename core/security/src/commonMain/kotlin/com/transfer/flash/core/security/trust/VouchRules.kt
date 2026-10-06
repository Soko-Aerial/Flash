package com.transfer.flash.core.security.trust

/** Where the fingerprint pinned for a device came from (ADR-044 V2, `docs/group/v2-vouched-trust-plan.md` E1). */
public enum class PinSource {
    /** The user completed pairing with the device. The strongest source. */
    PAIRED,

    /** A group owner the user paired with signed a certificate naming this key, or named by an invite this device accepted for that group. Scoped to those groups. */
    VOUCHED,

    /** Recorded when the device first connected, never verified by a person or an owner. */
    TOFU,
}

/** What applying a vouch would do (or did) to a device's pin. */
public enum class VouchVerdict {
    /** The vouch is compatible with what is stored: installed, refreshed, or replaced an unverified pin. */
    ACCEPT,

    /** The device is paired under a different key. Pairing wins and the vouch is refused. */
    CONFLICT_PAIRED,

    /** Another group vouched a different key for this device, so two owners disagree. The first one stays. */
    CONFLICT_VOUCHED,

    /** The fingerprint was blank. */
    INVALID,
}

/**
 * The one copy of the pin-source and vouch rules; every [FlashTrustStore] applies them over its own storage,
 * so the two hosts cannot drift apart. Pure: state goes in, a verdict comes out.
 */
public object VouchRules {

    /** Uppercase, no colons or spaces: the form the stores compare and persist. */
    public fun normalize(raw: String): String = raw.replace(":", "").replace(" ", "").uppercase()

    /** The source of the pin of a device with this state, or null when it has no pin at all. */
    public fun sourceOf(paired: Boolean, pin: String?, vouchingGroups: Set<String>): PinSource? = when {
        pin.isNullOrBlank() -> null
        paired -> PinSource.PAIRED
        vouchingGroups.isNotEmpty() -> PinSource.VOUCHED
        else -> PinSource.TOFU
    }

    /**
     * Whether [groupId] may vouch [fingerprintHex] for a device that is [paired], holds [pin] and is already
     * vouched for by [vouchingGroups].
     *
     * | State | Same key | Different key |
     * |---|---|---|
     * | no pin | accept | accept |
     * | first-use pin only | accept | accept, replaces it |
     * | vouched by this group only | accept | accept, replaces it (the owner re-issued the cert) |
     * | vouched by another group | accept | [VouchVerdict.CONFLICT_VOUCHED] |
     * | paired | accept, nothing stored | [VouchVerdict.CONFLICT_PAIRED] |
     */
    public fun decide(
        paired: Boolean,
        pin: String?,
        vouchingGroups: Set<String>,
        fingerprintHex: String,
        groupId: String,
    ): VouchVerdict {
        val fingerprint = normalize(fingerprintHex)
        if (fingerprint.isEmpty() || groupId.isBlank()) return VouchVerdict.INVALID
        val current = pin?.let(::normalize)?.takeIf { it.isNotEmpty() }
        if (paired) return if (current == null || current == fingerprint) VouchVerdict.ACCEPT else VouchVerdict.CONFLICT_PAIRED
        if (current == null || current == fingerprint) return VouchVerdict.ACCEPT
        return when {
            vouchingGroups.isEmpty() -> VouchVerdict.ACCEPT
            vouchingGroups == setOf(groupId) -> VouchVerdict.ACCEPT
            else -> VouchVerdict.CONFLICT_VOUCHED
        }
    }

    /**
     * The groups that still vouch a device after [groupId] stops doing so, or null when [groupId] was not
     * vouching it (nothing to revoke, and in particular an unrelated first-use pin must survive).
     */
    public fun afterRevoke(vouchingGroups: Set<String>, groupId: String): Set<String>? =
        if (groupId in vouchingGroups) vouchingGroups - groupId else null
}
