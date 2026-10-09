package com.transfer.flash.core.security.crypto

import java.security.PrivateKey

/**
 * What a look at the stored identity key found (sweep R-09, 2026-10-09).
 *
 * [KeystoreFlashCrypto] used to treat ANY exception while probing the key as "the key lacks DIGEST_NONE", delete the entry and
 * generate a new identity. A transient `KeyStoreException` / `ProviderException` right after boot, a Keystore daemon restart or
 * a StrongBox hiccup therefore cost the device its identity: every peer's TOFU pin stopped matching and every pairing had to be
 * redone. The decision is now pure and unit-tested: only a DEFINITE verdict ([LacksDigestNone], [MissingKey]) allows
 * regeneration; an [Inconclusive] probe is retried and then fails loudly with the key untouched.
 */
internal sealed interface IdentityKeyVerdict {
    /** The key can sign with `NONEwithECDSA`, which the Conscrypt TLS handshake needs. */
    data object Usable : IdentityKeyVerdict

    /** The alias exists but holds no private key: there is no identity to lose. */
    data object MissingKey : IdentityKeyVerdict

    /** The key's parameters were read successfully and its digests do not include `NONE`. */
    data object LacksDigestNone : IdentityKeyVerdict

    /** Anything else: the probe itself failed, so nothing is known about the key. */
    data class Inconclusive(val cause: Exception) : IdentityKeyVerdict
}

internal enum class IdentityKeyAction { KEEP, REGENERATE }

/** Thrown when the identity key could not be verified. The key was NOT deleted; a later call tries again. */
internal class IdentityKeyUnverifiableException(cause: Exception?) :
    IllegalStateException(
        "The device identity key could not be verified and was left untouched" +
            (cause?.let { " (${it::class.simpleName})" } ?: ""),
        cause,
    )

/** The platform calls the probe needs, behind a seam so a test can inject failures. */
internal interface IdentityKeyInspector {
    /** The stored private key handle, null when the alias holds none. May throw. */
    fun privateKey(): PrivateKey?

    /** The digests the key is authorised for (Android: `KeyInfo.digests`). May throw. */
    fun digests(key: PrivateKey): Set<String>

    /** Opens a `NONEwithECDSA` signing operation with [key] (the old probe). May throw. */
    fun initNoneSigning(key: PrivateKey)
}

internal object IdentityKeyCheck {
    const val DIGEST_NONE: String = "NONE"
    const val PROBE_ATTEMPTS: Int = 3
    const val PROBE_PAUSE_MS: Long = 250L

    /**
     * One look at the key. The digests are read from the key's own parameters; if that read fails the signing probe is used
     * instead, and if that fails too the result is [IdentityKeyVerdict.Inconclusive]. Only a successful read that lacks
     * `NONE` is [IdentityKeyVerdict.LacksDigestNone].
     */
    fun inspect(inspector: IdentityKeyInspector): IdentityKeyVerdict {
        val key = try {
            inspector.privateKey()
        } catch (e: Exception) {
            return IdentityKeyVerdict.Inconclusive(e)
        } ?: return IdentityKeyVerdict.MissingKey
        try {
            val digests = inspector.digests(key)
            return if (DIGEST_NONE in digests) IdentityKeyVerdict.Usable else IdentityKeyVerdict.LacksDigestNone
        } catch (_: Exception) {
            // A failed parameter read says nothing about the key; fall back to opening a signing operation.
        }
        return try {
            inspector.initNoneSigning(key)
            IdentityKeyVerdict.Usable
        } catch (e: Exception) {
            IdentityKeyVerdict.Inconclusive(e)
        }
    }

    /**
     * Runs [probe] up to [attempts] times while it is inconclusive (calling [pause] in between). Returns [IdentityKeyAction.KEEP]
     * for a usable key, [IdentityKeyAction.REGENERATE] only for a definite verdict, and throws
     * [IdentityKeyUnverifiableException] when every attempt was inconclusive. Never deletes anything itself.
     */
    fun decide(
        attempts: Int = PROBE_ATTEMPTS,
        pause: () -> Unit = { Thread.sleep(PROBE_PAUSE_MS) },
        probe: () -> IdentityKeyVerdict,
    ): IdentityKeyAction {
        var last: Exception? = null
        for (attempt in 1..attempts) {
            when (val verdict = probe()) {
                IdentityKeyVerdict.Usable -> return IdentityKeyAction.KEEP
                IdentityKeyVerdict.MissingKey, IdentityKeyVerdict.LacksDigestNone -> return IdentityKeyAction.REGENERATE
                is IdentityKeyVerdict.Inconclusive -> {
                    last = verdict.cause
                    if (attempt < attempts) pause()
                }
            }
        }
        throw IdentityKeyUnverifiableException(last)
    }
}
