package com.transfer.flash.core.transfer

/**
 * What a send source looked like when a transfer started or resumed (ADR-352).
 *
 * Resume state is only meaningful for the same bytes. A chunk index that was confirmed for one version of a file
 * cannot be reused for another: the sender would skip bytes that the receiver already has from the old version, and
 * the receiver cannot tell the difference until its whole-file check fails (or, worse, a chunk boundary happens to
 * line up). Every field is nullable because providers differ: Android's Storage Access Framework does not always
 * report a size or a modification time, and a provider may refuse to open the source for the prefix read.
 *
 * [prefixSha256Hex] is the content signal when both the stored and the current identity have it. Size and modification
 * time are also checked whenever both sides report them, so any observed difference is treated as a change.
 */
public data class SourceIdentity(
    public val sizeBytes: Long? = null,
    public val lastModifiedMs: Long? = null,
    public val prefixSha256Hex: String? = null,
)

/**
 * Host port that reports the current identity of [fileUri]. Called before a send starts and before a resume, on a
 * worker dispatcher. Returning null means "could not tell"; the repository then keeps the old behaviour rather than
 * failing a transfer it cannot inspect.
 */
public interface SourceIdentityProvider {
    public suspend fun identity(fileUri: String): SourceIdentity?
}

/** The result of comparing the identity stored for a transfer with the source's identity now. */
public enum class SourceIdentityCheck {
    /** Every available signal agrees, so the persisted chunks are for these bytes. */
    Match,

    /** The source is not the bytes the persisted chunks were confirmed for; the resume must not proceed. */
    Changed,

    /** The row predates source identity or the provider could not report a decisive signal. */
    Unverifiable,
}

/**
 * Compares [stored] with [current].
 *
 * Any available signal that differs is a change; a touch is refused conservatively rather than risking a resumed edit
 * beyond the 64 KiB prefix. The prefix digest is the content signal when both sides have it, the modification time
 * covers providers without one, and a row with only a size cannot be verified.
 */
public fun checkSourceIdentity(stored: SourceIdentity?, current: SourceIdentity): SourceIdentityCheck {
    if (stored == null) return SourceIdentityCheck.Unverifiable

    val storedSize = stored.sizeBytes
    val currentSize = current.sizeBytes
    if (storedSize != null && currentSize != null && storedSize != currentSize) {
        return SourceIdentityCheck.Changed
    }

    val storedPrefix = stored.prefixSha256Hex?.takeIf { it.isNotBlank() }
    val currentPrefix = current.prefixSha256Hex?.takeIf { it.isNotBlank() }
    if (storedPrefix != null && currentPrefix != null &&
        !storedPrefix.equals(currentPrefix, ignoreCase = true)
    ) {
        return SourceIdentityCheck.Changed
    }

    val storedModified = stored.lastModifiedMs?.takeIf { it > 0L }
    val currentModified = current.lastModifiedMs?.takeIf { it > 0L }
    if (storedModified != null && currentModified != null && storedModified != currentModified) {
        return SourceIdentityCheck.Changed
    }

    return if ((storedPrefix != null && currentPrefix != null) ||
        (storedModified != null && currentModified != null)
    ) {
        SourceIdentityCheck.Match
    } else {
        SourceIdentityCheck.Unverifiable
    }
}
