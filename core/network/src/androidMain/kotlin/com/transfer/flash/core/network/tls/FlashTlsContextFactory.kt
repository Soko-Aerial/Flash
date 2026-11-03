package com.transfer.flash.core.network.tls

import com.transfer.flash.core.security.trust.TrustChangeHub
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
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

    private val clientCache = ConcurrentHashMap<ClientKey, SSLContext>()
    private val serverCache = ConcurrentHashMap<ServerKey, SSLContext>()

    init {
        // A resumed TLS 1.3 session never re-runs the trust manager, so a cached SSLContext outlives
        // "Forget", a pin replacement or a withdrawn vouch until its session cache expires. Drop every
        // cached context on any trust change; revocation is rare, a full handshake is cheap next to a
        // connection that still authenticates a peer the user removed.
        TrustChangeHub.addListener { clearCache() }
    }

    /**
     * Identity-keyed wrapper around this host's [KeyManager] array — the local TLS identity.
     *
     * A bare `System.identityHashCode(keyManagers)` is not a safe cache key, for two independent
     * reasons (integrator review, 2026-10-31):
     * - identity hash codes collide, so an unrelated array can hit a cached [SSLContext] built for
     *   a different local identity and present the WRONG certificate to a peer;
     * - the code is derived from an address, and the array it came from is otherwise unreferenced,
     *   so it can be collected and its code reused — a fresh identity array would then reuse a
     *   stale context holding a retired (possibly revoked) certificate.
     *
     * Holding the array here makes both impossible: [equals] is reference equality on the array
     * itself and a hash collision can no longer be mistaken for a match. Keeping it strongly
     * reachable is not a lifetime regression — the cached [SSLContext] already retains it through
     * its KeyManagers, and [clearCache] drops both together.
     */
    private class KeyManagersRef(private val managers: Array<KeyManager>?) {
        override fun equals(other: Any?): Boolean =
            other is KeyManagersRef && managers === other.managers

        override fun hashCode(): Int = System.identityHashCode(managers)
    }

    private data class ClientKey(
        val expectedDeviceId: String,
        val pinVerifier: FlashPinVerifier,
        val keyManagersRef: KeyManagersRef,
    )

    private data class ServerKey(
        val expectedDeviceId: String?,
        val pinVerifier: FlashPinVerifier,
        val keyManagersRef: KeyManagersRef,
    )

    fun clearCache() {
        clientCache.clear()
        serverCache.clear()
    }

    /**
     * Context used by outgoing connections. Verifies the server's chain against the pin for
     * [expectedDeviceId]; a null id makes every handshake fail closed (peer not yet identified).
     *
     * OPT-O09-02: Caches the SSLContext per expected peer so subsequent reconnects reuse the
     * active SSLSessionContext, enabling TLS session resumption across reconnects and eliminating
     * full asymmetric ECDH/ECDSA handshakes.
     */
    fun clientContext(
        pinVerifier: FlashPinVerifier,
        expectedDeviceId: String?,
        keyManagers: Array<KeyManager>? = null,
        onKeyChanged: (presentedFingerprintHex: String) -> Unit = {},
        /** Manual-dial deferral (ADR-040); see [TofuX509TrustManager]. */
        deferPinWhenDeviceIdUnknown: Boolean = false,
        onLeafObserved: (leafFingerprintHex: String) -> Unit = {},
    ): SSLContext {
        if (expectedDeviceId != null && !deferPinWhenDeviceIdUnknown) {
            val key = ClientKey(expectedDeviceId, pinVerifier, KeyManagersRef(keyManagers))
            return clientCache.computeIfAbsent(key) {
                createContext(
                    keyManagers,
                    TofuX509TrustManager(
                        pinVerifier,
                        expectedDeviceId,
                        onKeyChanged,
                        deferPinWhenDeviceIdUnknown = false,
                        onLeafObserved = onLeafObserved,
                    ),
                )
            }
        }
        return createContext(
            keyManagers,
            TofuX509TrustManager(
                pinVerifier,
                expectedDeviceId,
                onKeyChanged,
                deferPinWhenDeviceIdUnknown,
                onLeafObserved,
            ),
        )
    }

    /**
     * Context used by accepting endpoints. When client-auth is requested via
     * `setWantClientAuth/setNeedClientAuth`, incoming client chains pass through the identical
     * TOFU validation with [expectedDeviceId] identifying the anticipated peer.
     *
     * OPT-O09-02: Caches the server SSLContext so session tickets issued to connecting peers are
     * recognized on reconnect, enabling TLS session resumption.
     */
    fun serverContext(
        pinVerifier: FlashPinVerifier,
        keyManagers: Array<KeyManager>? = null,
        expectedDeviceId: String? = null,
        onKeyChanged: (presentedFingerprintHex: String) -> Unit = {},
        /**
         * Inbound deferral (audit S1): an accepting server cannot know the client's id until HELLO,
         * so the client leaf is reported here and bound to the HELLO id by the network layer.
         */
        deferPinWhenDeviceIdUnknown: Boolean = false,
        onLeafObserved: (leafFingerprintHex: String) -> Unit = {},
    ): SSLContext {
        if (!deferPinWhenDeviceIdUnknown) {
            val key = ServerKey(expectedDeviceId, pinVerifier, KeyManagersRef(keyManagers))
            return serverCache.computeIfAbsent(key) {
                createContext(
                    keyManagers,
                    TofuX509TrustManager(
                        pinVerifier,
                        expectedDeviceId,
                        onKeyChanged,
                        deferPinWhenDeviceIdUnknown = false,
                        onLeafObserved = onLeafObserved,
                    ),
                )
            }
        }
        return createContext(
            keyManagers,
            TofuX509TrustManager(
                pinVerifier,
                expectedDeviceId,
                onKeyChanged,
                deferPinWhenDeviceIdUnknown,
                onLeafObserved,
            ),
        )
    }

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
        runCatching {
            context.clientSessionContext?.sessionTimeout = 86400
            context.clientSessionContext?.sessionCacheSize = 256
            context.serverSessionContext?.sessionTimeout = 86400
            context.serverSessionContext?.sessionCacheSize = 256
        }
        return context
    }
}
