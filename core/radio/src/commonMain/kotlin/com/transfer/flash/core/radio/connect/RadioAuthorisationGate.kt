package com.transfer.flash.core.radio.connect

/**
 * Security profiles for Flash radio transmission (Plan R §8).
 */
public enum class RadioSecurityProfile {
    /**
     * Profile A: Signed clear text with callsign in the clear.
     * Permissible on amateur frequencies where encryption is forbidden by law.
     */
    PROFILE_A_PLAINTEXT_SIGNED,

    /**
     * Profile M: Authenticated and encrypted (AES-256-GCM, rotating station tags, no callsign).
     * Strictly requires verified authorization confirmation for military / authorized licensed services.
     */
    PROFILE_M_ENCRYPTED,
}

/**
 * Recorded user authorization confirmation for encrypted transmission (Plan R §8).
 */
public data class AuthorisationConfirmation(
    /**
     * Epoch timestamp in milliseconds when the user confirmed authorization.
     * Must be positive (date is mandatory).
     */
    val confirmedAtEpochMs: Long,
    /** Optional callsign or operator ID recorded with confirmation. */
    val operatorCallsign: String? = null,
) {
    init {
        require(confirmedAtEpochMs > 0) {
            "confirmedAtEpochMs must be greater than zero; confirmation date is mandatory"
        }
    }
}

/**
 * Enforces security profile selection rules (Plan R §8).
 *
 * Rules:
 * 1. [RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED] is always selectable without confirmation.
 * 2. [RadioSecurityProfile.PROFILE_M_ENCRYPTED] is selectable ONLY after a recorded confirmation with a valid date.
 */
public class RadioAuthorisationGate {

    /**
     * Checks if [requestedProfile] is permissible under [confirmation].
     */
    public fun isProfileAllowed(
        requestedProfile: RadioSecurityProfile,
        confirmation: AuthorisationConfirmation?,
    ): Boolean {
        return when (requestedProfile) {
            RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED -> true
            RadioSecurityProfile.PROFILE_M_ENCRYPTED -> confirmation != null && confirmation.confirmedAtEpochMs > 0
        }
    }

    /**
     * Returns the set of profiles selectable under [confirmation].
     */
    public fun selectableProfiles(confirmation: AuthorisationConfirmation?): Set<RadioSecurityProfile> {
        return if (confirmation != null && confirmation.confirmedAtEpochMs > 0) {
            setOf(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, RadioSecurityProfile.PROFILE_M_ENCRYPTED)
        } else {
            setOf(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED)
        }
    }

    /**
     * Enforces that [profile] is allowed. Throws [IllegalStateException] if an unauthorized profile is selected.
     */
    public fun requireProfileAllowed(
        profile: RadioSecurityProfile,
        confirmation: AuthorisationConfirmation?,
    ) {
        check(isProfileAllowed(profile, confirmation)) {
            "Profile M (encrypted) requires recorded user authorisation with a valid confirmation date"
        }
    }
}
