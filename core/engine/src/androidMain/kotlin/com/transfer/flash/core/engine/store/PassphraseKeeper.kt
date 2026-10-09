package com.transfer.flash.core.engine.store

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import java.security.SecureRandom

/**
 * The passphrase policy behind [KeystorePassphraseProvider], with the Android pieces behind seams so it runs on a host JVM.
 *
 * The policy exists because the old code treated EVERY exception from the unwrap as "the key is gone": a transient
 * `KeyStoreException` (a keystore daemon busy at boot, a StrongBox timeout) minted a new passphrase, overwrote the stored
 * wrapper and made the app quarantine a perfectly good database. Now:
 *
 * - Key permanently gone (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`) or a wrapper that cannot even
 *   be parsed: mint a new passphrase.
 * - Authentication failure (`AEADBadTagException`/`BadPaddingException`): this is what a missing key looks like (the provider
 *   regenerates the alias on demand and the new key cannot open the old wrapper), but it can also be a glitch, so it is
 *   retried once before it is believed.
 * - Anything else: retried once, and if it still fails the error is thrown ([PassphraseUnavailableException]). Nothing is
 *   minted, nothing is overwritten, no database is moved; the next launch tries again.
 * - A minted passphrase may be held back ([resolve] with `commit = false`) and written only by [commitPending], so the old
 *   wrapper survives until the caller has moved the old database aside and created the new one.
 */
@OptIn(FlashInternalApi::class)
internal class PassphraseKeeper(
    private val readStored: () -> String?,
    private val writeStored: (String) -> Unit,
    private val unwrap: (String) -> ByteArray,
    private val wrap: (ByteArray) -> String,
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
    private val pause: () -> Unit = { Thread.sleep(RETRY_PAUSE_MS) },
) {

    /** True when the most recent [resolve] minted a new passphrase. */
    @Volatile
    var minted: Boolean = false
        private set

    @Volatile
    private var pendingWrapped: String? = null

    fun resolve(commit: Boolean): ByteArray {
        pendingWrapped = null
        val stored = readStored()
        if (stored != null) {
            val existing = tryUnwrap(stored)
            if (existing != null) {
                minted = false
                return existing
            }
            FlashLog.w(TAG, "stored passphrase wrapper is unusable (key lost or wrapper corrupt); minting a new passphrase")
        }
        val fresh = randomBytes(PASSPHRASE_BYTES)
        // Wrapping first proves the keystore works NOW: a passphrase that cannot be stored must not be handed out.
        val wrapped = wrap(fresh)
        if (commit) writeStored(wrapped) else pendingWrapped = wrapped
        minted = true
        return fresh
    }

    /** Writes the wrapper of a passphrase minted with `commit = false`. A no-op otherwise. */
    fun commitPending() {
        val wrapped = pendingWrapped ?: return
        writeStored(wrapped)
        pendingWrapped = null
    }

    /** The unwrapped passphrase, or null when the stored wrapper is genuinely unusable. Throws when it is merely unreadable now. */
    private fun tryUnwrap(stored: String): ByteArray? {
        var lastFailure: Throwable? = null
        for (attempt in 1..2) {
            try {
                return unwrap(stored)
            } catch (t: Throwable) {
                if (t is InterruptedException || t is OutOfMemoryError) throw t
                when (classify(t)) {
                    Failure.PERMANENT -> return null
                    Failure.AUTHENTICATION, Failure.TRANSIENT -> {
                        lastFailure = t
                        if (attempt == 1) {
                            FlashLog.w(TAG, "passphrase unwrap failed (${t::class.simpleName}), retrying once")
                            pause()
                        }
                    }
                }
            }
        }
        val failure = checkNotNull(lastFailure)
        if (classify(failure) == Failure.AUTHENTICATION) return null
        throw PassphraseUnavailableException("The database passphrase could not be read from the keystore: ${failure::class.simpleName}", failure)
    }

    private enum class Failure { PERMANENT, AUTHENTICATION, TRANSIENT }

    private fun classify(t: Throwable): Failure {
        var cause: Throwable? = t
        var depth = 0
        var sawAuthentication = false
        while (cause != null && depth < 8) {
            when (cause::class.simpleName) {
                "KeyPermanentlyInvalidatedException", "UnrecoverableKeyException" -> return Failure.PERMANENT
                "AEADBadTagException", "BadPaddingException" -> sawAuthentication = true
            }
            cause = cause.cause
            depth++
        }
        if (sawAuthentication) return Failure.AUTHENTICATION
        // A wrapper that is not valid Base64 or is shorter than its IV can never be unwrapped.
        if (t is IllegalArgumentException || t is IndexOutOfBoundsException) return Failure.PERMANENT
        return Failure.TRANSIENT
    }

    private companion object {
        const val TAG = "DATABASE"
        const val PASSPHRASE_BYTES = 32
        const val RETRY_PAUSE_MS = 150L
    }
}

/** The keystore could not be read right now. The database was not touched; the next start tries again. */
public class PassphraseUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)
