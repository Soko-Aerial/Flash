package com.transfer.flash.core.network.tls

import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original — byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.

 * Builds the client and server [SSLContext]s for Flash P2P TLS (C4.1).
 *
 * Both contexts share the same [TofuX509TrustManager] logic: every Flash peer acts as TLS server
 * when accepting and TLS client when connecting, and both directions validate the presented chain
 * against the injected [FlashPinVerifier]. Key material is NOT created here — callers inject
 * `KeyManager`s:
 * - production (`:app`/`:core:engine` wiring): platform AndroidKeyStore managers wrapping the
 *   C2.2 self-signed identity cert (`docs/security.md` §2);
 * - tests: software managers from the test-source-set `SoftwareCertMaker`.
 *
 * ## Why fingerprint identity replaces hostname verification
 * Hostname verification binds a certificate to a DNS name. Flash peers have no hostnames at all:
 * they are addressed by ephemeral link-local IPs across LAN / Wi-Fi Direct / Aware interfaces
 * (C4.6), where DNS names would be meaningless, spoofable, or simply absent. Identity is instead
 * established by pinning the SHA-256 SPKI fingerprint of the peer's key to its stable deviceId at
 * pairing time (TOFU + 6-digit comparison code, docs/security.md §3). The custom trust manager is
 * therefore the ONLY authentication decision point; no endpoint-identification algorithm is set
 * on sockets built from these contexts (raw `SSLSocket` performs none by default per
 * https://developer.android.com/privacy-and-security/security-ssl). This is the same trust model
 * as SSH known-hosts, and it fails closed: an unknown key aborts the handshake.
 */
internal object FlashTlsContextFactory {

    /** Preferred first; adjacent versions so negotiation never degrades (see [configure]). */
    private val PREFERRED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /**
     * Context used by outgoing connections. Verifies the server's chain against the pin for
     * [expectedDeviceId]; a null id makes every handshake fail closed (peer not yet identified).
     */
    fun clientContext(
        pinVerifier: FlashPinVerifier,
        expectedDeviceId: String?,
        keyManagers: Array<KeyManager>? = null,
        onKeyChanged: (presentedFingerprintHex: String) -> Unit = {},
        /** Manual-dial deferral (ADR-040); see [TofuX509TrustManager]. */
        deferPinWhenDeviceIdUnknown: Boolean = false,
        onLeafObserved: (leafFingerprintHex: String) -> Unit = {},
    ): SSLContext = createContext(
        keyManagers,
        TofuX509TrustManager(
            pinVerifier,
            expectedDeviceId,
            onKeyChanged,
            deferPinWhenDeviceIdUnknown,
            onLeafObserved,
        ),
    )

    /**
     * Context used by accepting endpoints. When client-auth is requested via
     * `setWantClientAuth/setNeedClientAuth`, incoming client chains pass through the identical
     * TOFU validation with [expectedDeviceId] identifying the anticipated peer.
     */
    fun serverContext(
        pinVerifier: FlashPinVerifier,
        keyManagers: Array<KeyManager>? = null,
        expectedDeviceId: String? = null,
        onKeyChanged: (presentedFingerprintHex: String) -> Unit = {},
    ): SSLContext = createContext(
        keyManagers,
        TofuX509TrustManager(pinVerifier, expectedDeviceId, onKeyChanged),
    )

    /** TLSv1.3 preferred, TLSv1.2 fallback, intersected with what the socket actually supports. */
    fun configure(socket: SSLSocket) {
        val enabled = PREFERRED_PROTOCOLS.filter { it in socket.supportedProtocols }
        if (enabled.isNotEmpty()) {
            socket.enabledProtocols = enabled.toTypedArray()
        }
    }

    private fun createContext(keyManagers: Array<KeyManager>?, trustManager: TofuX509TrustManager): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
        return context
    }
}
