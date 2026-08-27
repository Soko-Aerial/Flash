package com.transfer.flash.core.security.trust.pinned

import com.transfer.flash.core.security.pairing.NumericComparisonCode
import java.security.MessageDigest

/**
 * TOFU (Trust On First Use) pin decision for a peer's identity fingerprint (C2.5).
 *
 * ## What is pinned, and why the fingerprint not the certificate chain
 *
 * The pin is the peer's **identity public-key fingerprint** (SHA-256 over the
 * public key / SPKI material, D3) — never a whole certificate chain. Pinning a
 * leaf certificate breaks on every routine renewal; OWASP recommends pinning the
 * SubjectPublicKeyInfo/public key precisely so the pin survives cert rotation as
 * long as the underlying key pair is unchanged
 * (https://cheatsheetseries.owasp.org/cheatsheets/Pinning_Cheat_Sheet.html).
 * RFC 7469 §1 likewise defines pins as relationships to *public keys* in SPKI form,
 * and documents that TOFU's residual risk is exactly "the first connection was
 * MITM'd" (https://datatracker.ietf.org/doc/html/rfc7469). In Flash the residual
 * risk of that first connection is mitigated out-of-band by the 6-digit numeric
 * comparison during pairing ([com.transfer.flash.core.security.pairing.NumericComparisonCode]).
 *
 * ## Fail-closed rules
 *
 * - No stored record (`known == null`) + presentable fingerprint → **FirstConnect**:
 *   the caller must prompt (pair or reject). This is the ONLY path that trusts.
 * - A stored record whose fingerprint is blank (legacy SharedPreferences rows
 *   migrated without key binding) → treated as unpinned → **FirstConnect** again;
 *   the cryptographic pin only exists after the next successful pairing.
 * - Blank/null **presented** fingerprint while any record exists → **Mismatch**
 *   (fail closed). A peer that cannot present its fingerprint is indistinguishable
 *   from an impostor and MUST NOT be allowed through; per RFC 7469 §2.6 pin-failure
 *   semantics there is no "proceed anyway" bypass.
 * - Known ≠ presented (constant-time compare) → **Mismatch**: hard fail carrying
 *   event data for the UI-031 "key changed" warning.
 */
internal object TofuPolicy {

    /** Sentinel stored in migrated legacy rows: trusted historically, no crypto pin yet. */
    const val LEGACY_UNBOUND_FINGERPRINT: String = ""

    sealed interface Decision {
        /**
         * No usable pin exists for this device. The caller must surface consent UI
         * (UI-032 pairing card); on user acceptance the presented fingerprint becomes
         * the new pin.
         */
        data class FirstConnect(val presentedFingerprintHex: String) : Decision

        /** Presented fingerprint equals the pinned one (constant-time verified). Proceed. */
        object Match : Decision

        /**
         * Hard failure: either the pinned key CHANGED (possible MITM / re-paired
         * device) or the peer presented nothing verifiable. Callers MUST refuse the
         * connection and raise the UI-031 key-changed warning with [pinnedFingerprintHex].
         * There is intentionally NO "ignore warning and continue" decision here.
         */
        data class Mismatch(
            val pinnedFingerprintHex: String?,
            val presentedFingerprintHex: String?,
            val reason: Reason,
        ) : Decision {
            enum class Reason { KEY_CHANGED, PRESENTED_FINGERPRINT_MISSING }
        }
    }

    /**
     * Evaluates the pin state for one device.
     *
     * @param knownFingerprintHex pinned fingerprint from the trust store, `null` when
     *   the device has never been seen, blank for legacy unbound rows.
     * @param presentedFingerprintHex fingerprint the peer just presented, `null`/blank
     *   when it presented none.
     */
    fun evaluate(knownFingerprintHex: String?, presentedFingerprintHex: String?): Decision {
        val presented = presentedFingerprintHex?.let(NumericComparisonCode::normalizeHex).orEmpty()
        if (presented.isEmpty()) {
            return if (knownFingerprintHex.isNullOrBlank()) {
                // Truly nothing known AND nothing presented: cannot pin, cannot verify.
                Decision.Mismatch(null, null, Decision.Mismatch.Reason.PRESENTED_FINGERPRINT_MISSING)
            } else {
                Decision.Mismatch(
                    pinnedFingerprintHex = knownFingerprintHex,
                    presentedFingerprintHex = presentedFingerprintHex,
                    reason = Decision.Mismatch.Reason.PRESENTED_FINGERPRINT_MISSING,
                )
            }
        }

        val known = knownFingerprintHex?.let(NumericComparisonCode::normalizeHex).orEmpty()
        if (known.isEmpty()) {
            // Unknown device, or legacy row without a cryptographic pin: prompt again.
            return Decision.FirstConnect(presented)
        }

        return if (constantTimeEquals(known, presented)) {
            Decision.Match
        } else {
            Decision.Mismatch(
                pinnedFingerprintHex = knownFingerprintHex,
                presentedFingerprintHex = presentedFingerprintHex,
                reason = Decision.Mismatch.Reason.KEY_CHANGED,
            )
        }
    }

    /**
     * Constant-time hex equality via [MessageDigest.isEqual] so pin checks don't leak
     * match position through timing.
     */
    private fun constantTimeEquals(aHex: String, bHex: String): Boolean =
        MessageDigest.isEqual(
            aHex.toByteArray(Charsets.US_ASCII),
            bHex.toByteArray(Charsets.US_ASCII),
        )
}
