package com.transfer.flash.core.engine

/**
 * Consumer-facing configuration knobs for engine factories ([Flash.create] on Android,
 * [FlashDesktop.create] on Desktop JVM).
 *
 * Every field has a sensible default, allowing zero-configuration engine assembly.
 */
public data class FlashConfig(
    /**
     * Friendly name advertised to peers. When null, the persisted device identity's name is used
     * (falling back to a platform default on first run).
     *
     * On Desktop this is an in-memory override for what this run advertises. It is never written to
     * the persisted device identity, so it cannot overwrite a name the owner chose in Settings;
     * renaming through the engine persists the new name and ends the override.
     */
    public val displayName: String? = null,

    /**
     * When true, outbound/inbound chunk progress is persisted so a transfer interrupted by a
     * process restart resumes instead of restarting. When false, the transfer repository runs with
     * no persistent store (still fully functional in-session).
     *
     * Desktop does not support this yet (no persistent transfer store, D5=C): it resumes within a
     * session only and logs that once at creation.
     */
    public val enableResume: Boolean = true,

    /**
     * Inbound-offer gate. When false (default), every inbound file arrives as an OFFER that the
     * consumer must accept via [com.transfer.flash.core.transfer.FlashTransferRepository.acceptIncoming];
     * senders park until then. When true, inbound transfers are accepted automatically and senders
     * stream immediately.
     *
     * Applies to paired (trusted) peers only (AGENTS.md section 19). An offer from an unpaired peer
     * always waits for the user.
     */
    public val autoAcceptIncoming: Boolean = false,

    /**
     * Absolute filesystem path for received files. When null, a platform-specific default is used.
     */
    public val receivedFilesPath: String? = null,
)
