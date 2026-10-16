package com.transfer.flash.core.radio.connect

/**
 * Port interface for persistent storage of the user authorization confirmation (Plan R §8).
 * Stores only confirmation epoch timestamp and callsign; NEVER cryptographic keys.
 */
public interface RadioAuthorisationStore {
    /** Loads the persisted authorization confirmation, or null if none recorded. */
    public suspend fun load(): AuthorisationConfirmation?

    /** Persists [confirmation]. */
    public suspend fun save(confirmation: AuthorisationConfirmation)

    /** Clears the recorded authorization. */
    public suspend fun clear()
}

/**
 * In-memory test stand-in for [RadioAuthorisationStore].
 */
public class InMemoryRadioAuthorisationStore(
    initial: AuthorisationConfirmation? = null,
) : RadioAuthorisationStore {
    private var current: AuthorisationConfirmation? = initial

    override suspend fun load(): AuthorisationConfirmation? = current

    override suspend fun save(confirmation: AuthorisationConfirmation) {
        current = confirmation
    }

    override suspend fun clear() {
        current = null
    }
}
