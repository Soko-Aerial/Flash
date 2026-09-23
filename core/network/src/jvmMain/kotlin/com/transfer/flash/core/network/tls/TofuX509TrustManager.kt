package com.transfer.flash.core.network.tls

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original — byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.

 * Production TOFU trust manager for P2P TLS (C4.1, plan step C2.2/C2.5).
 *
 * ## What it verifies
 * SHA-256 over each presented certificate's **encoded public key** (`PublicKey.encoded`, which is
 * the DER `SubjectPublicKeyInfo`) compared against the pin recorded by the injected
 * [FlashPinVerifier]. Pinning SPKI public keys — not certificate bytes or CA chains — follows the
 * OWASP Pinning Cheat Sheet (https://cheatsheetseries.owasp.org/cheatsheets/Pinning_Cheat_Sheet.html)
 * and RFC 7469 §2.4 (https://datatracker.ietf.org/doc/html/rfc7469#section-2.4): a pin survives
 * certificate reissue and cannot be defeated by algorithm misinterpretation.
 *
 * ## Accept rule (documented choice)
 * ACCEPT iff **any** certificate in the presented chain pins — leaf preferred, chain fallback
 * tolerated. OWASP recommends leaf pinning as the primary target; the any-of-chain fallback keeps
 * handshakes working when a platform keystore wraps the identity key in an intermediate/root
 * cert whose SPKI is what was actually pinned. In practice Flash chains are single self-signed
 * leaves, so leaf-first ordering means the fallback path is never exercised on-device.
 *
 * ## Fail-closed behaviour
 * - empty/null chain ⇒ [CertificateException]
 * - no expected device id, or a presented key with unusable encoding ⇒ [CertificateException]
 * - nothing in the chain matches a pin ⇒ [CertificateException] AND [onKeyChanged] invoked
 *   **exactly once** with the presented leaf fingerprint before the exception propagates.
 *
 * The manager cannot distinguish "wrong key" from "no record yet" — only the consumer-side trust
 * store can. It therefore fires [onKeyChanged] on every rejection that carried a computable leaf
 * fingerprint; consumers filter FirstConnect vs PeerKeyChanged against their own store state
 * (mirrors `TofuPolicy.evaluate` semantics in docs/security.md §3). The event feeds UI-031's
 * key-changed warning pipeline; a missing/blank fingerprint can never reach the callback because
 * such chains fail closed first.
 *
 * Both [checkClientTrusted] and [checkServerTrusted] route through identical logic: in this P2P
 * topology every peer plays both TLS roles, so client certificates get the same pin validation
 * as server certificates.
 *
 * Constant-time comparison note: the actual secret-material equality happens inside the injected
 * [FlashPinVerifier], which is contractually required to use `MessageDigest.isEqual`
 * (see [FlashPinVerifier] KDoc). This class never compares fingerprints itself.
 */
internal class TofuX509TrustManager(
    private val pinVerifier: FlashPinVerifier,
    private val expectedDeviceId: String?,
    private val onKeyChanged: (presentedFingerprintHex: String) -> Unit = {},
    /**
     * Manual-dial only (ADR-040). With no [expectedDeviceId] there is nothing to evaluate a pin
     * against, so instead of failing closed, report the leaf via [onLeafObserved] and let the
     * caller run the SAME `isPinned` check once `FLASH_WS_HELLO` names the peer. Never set this
     * for a dial that already knows which device it expects.
     */
    private val deferPinWhenDeviceIdUnknown: Boolean = false,
    private val onLeafObserved: (leafFingerprintHex: String) -> Unit = {},
) : X509ExtendedTrustManager() {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        verify(chain, "client")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) =
        verify(chain, "client")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        verify(chain, "client")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        verify(chain, "server")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) =
        verify(chain, "server")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        verify(chain, "server")

    /** No CA anchors exist in a pure-pinning model; returning an empty array (never null). */
    override fun getAcceptedIssuers(): Array<X509Certificate> = EMPTY_CHAIN

    private fun verify(chain: Array<X509Certificate>?, role: String) {
        if (chain == null || chain.isEmpty()) {
            throw CertificateException("TOFU($role): peer presented an empty certificate chain")
        }
        val leafFp = fingerprintHexOrNull(chain[0])
            ?: throw rejected(role, null, "leaf certificate exposes no usable SubjectPublicKeyInfo")
        val deviceId = expectedDeviceId ?: if (deferPinWhenDeviceIdUnknown) {
            // Identity is not known YET (manual IP dial). The handshake may complete; the binding
            // check is owed by the caller after HELLO, and the session must not carry traffic
            // before it passes. See ADR-040.
            onLeafObserved(leafFp)
            return
        } else {
            throw rejected(role, leafFp, "no expected device id — pin evaluation impossible")
        }

        val pinned = chain.any { cert ->
            val fp = fingerprintHexOrNull(cert)
            fp != null && pinVerifier.isPinned(deviceId, fp)
        }
        if (!pinned) {
            throw rejected(role, leafFp, "no chain certificate matches the pin for device '$deviceId'")
        }
    }

    /**
     * Builds the fail-closed exception; fires [onKeyChanged] exactly once per failed check when
     * the presented leaf fingerprint is known (invoked BEFORE the exception propagates).
     */
    private fun rejected(role: String, leafFp: String?, reason: String): CertificateException {
        if (leafFp != null) onKeyChanged(leafFp)
        return CertificateException("TOFU($role): $reason")
    }

    private fun fingerprintHexOrNull(cert: X509Certificate): String? {
        val encoded = runCatching { cert.publicKey?.encoded }.getOrNull()
        if (encoded == null || encoded.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }

    private companion object {
        val EMPTY_CHAIN = arrayOf<X509Certificate>()
    }
}
