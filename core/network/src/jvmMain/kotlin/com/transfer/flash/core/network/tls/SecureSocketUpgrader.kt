package com.transfer.flash.core.network.tls

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLSocket
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original — byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.

 * Per-endpoint TLS configuration shared by [com.transfer.flash.core.network.ws.WsTransferServer]
 * and [com.transfer.flash.core.network.ws.WsTransferClient] (C4.1). Passing `null` keeps the
 * plaintext dev mode (loudly tracked debt per AGENTS.md §19); passing a value upgrades the
 * WebSocket transport to TOFU-pinned TLS.
 *
 * @param pinVerifier            seam to the persistent trust store ([FlashPinVerifier] contract).
 * @param keyManagers            local identity managers (production: AndroidKeyStore-backed;
 *                               tests: software keystore).
 * @param expectedDeviceId       stable id of the REMOTE peer. Client side: mandatory for a
 *                               meaningful pin check (null ⇒ fail closed). Server side: only
 *                               consulted when client-auth is requested (see
 *                               [FlashTlsContextFactory.serverContext]).
 * @param handshakeTimeoutMs     bound applied to the TLS handshake (see
 *                               [SecureSocketUpgrader.wrapClient] for the timeout mechanism).
 */
public class TlsOptions(
    public val pinVerifier: FlashPinVerifier,
    public val keyManagers: Array<KeyManager>,
    public val expectedDeviceId: String? = null,
    public val handshakeTimeoutMs: Long = SecureSocketUpgrader.DEFAULT_HANDSHAKE_TIMEOUT_MS,
)

/**
 * Upgrades ALREADY-CONNECTED plain TCP sockets to TOFU-pinned TLS (plan step C4.1).
 *
 * ## Research findings (R1, verified 2026-08-23)
 *
 * **(a) Wrapping a connected socket.**
 * `SSLSocketFactory.createSocket(socket, host, port, autoClose)` layers an SSLSocket over an
 * existing connected socket without connecting again — host/port are only the "logical peer
 * destination"; `autoClose=true` closes the underlying socket when the layered socket closes.
 * The returned socket performs NO I/O until first use or explicit `startHandshake()`, which is
 * what makes the server-side pattern legal: wrap the ACCEPTED plain socket the same way, then
 * call `setUseClientMode(false)` BEFORE any byte flows (mode may only be changed while the
 * handshake has not started). This is the standard "port unification" server pattern
 * - https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLSocketFactory.html#createSocket(java.net.Socket,java.lang.String,int,boolean)
 * - https://developer.android.com/reference/javax/net/ssl/SSLSocketFactory
 * - https://stackoverflow.com/questions/6559859/is-it-possible-to-change-plain-socket-to-sslsocket
 *
 * **(b) Clean-boundary rule (STARTTLS-class pitfalls).**
 * ANY byte read from or written to the plain socket before wrapping corrupts the TLS stream:
 * buffered plaintext is lost to the wrapper and attacker-controlled plaintext injected between
 * the protocol switch becomes "TLS-protected" from the application's point of view (the classic
 * plaintext-injection flaw). Once the decision to upgrade is made, use of the plaintext streams
 * MUST cease completely — the safest boundary is to never touch them at all.
 * - https://duesee.dev/p/avoid-implementing-starttls/
 * - https://lists.openwall.net/bugtraq/2011/03/07/17 (SMTP/STARTTLS plaintext injection)
 * - https://stackoverflow.com/questions/15957198/upgrading-socket-to-sslsocket-with-starttls-recv-failed
 *
 * Enforcement: the JDK/Android `Socket` API exposes NO portable way to learn that some other
 * component already obtained (let alone consumed) the plain streams of a foreign Socket
 * implementation. Detection is therefore BEST-EFFORT via the opt-in tracking wrapper
 * [withPlainStreamTracking] — both WS endpoints wrap their sockets in it while TLS is enabled,
 * so any pre-wrap stream access deterministically raises [IllegalStateException]. Sockets not
 * created through the tracking wrapper are protected by convention (this KDoc) only.
 *
 * **(c) Handshake timeouts.**
 * Standard `javax.net.ssl.SSLSocket` has NO public per-handshake timeout; the historic options —
 * `SSLCertificateSocketFactory.getDefault(handshakeTimeoutMillis)` (deprecated API 29) and
 * Conscrypt-internal `setHandshakeTimeout` — both implement the timeout by TEMPORARILY swapping
 * `SO_TIMEOUT` for the duration of the handshake and restoring it afterwards. This object uses
 * exactly that portable mechanism (save soTimeout → set handshake bound → `startHandshake()` →
 * restore 0), so a dead peer fails closed with [java.net.SocketTimeoutException] instead of
 * hanging forever.
 * - https://developer.android.com/reference/android/net/SSLCertificateSocketFactory
 * - https://android.googlesource.com/platform/external/conscrypt/+/master/src/main/java/org/conscrypt/OpenSSLSocketImpl.java
 */
internal object SecureSocketUpgrader {

    /** Default handshake bound; generous enough for slow radios, short enough to fail fast. */
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS: Long = 5_000

    /**
     * Opt-in marker for sockets whose plain streams have been accessed before wrapping.
     * Implemented by the wrapper returned from [withPlainStreamTracking].
     */
    interface PlainStreamAccessAudited {
        /** True once [Socket.getInputStream] or [Socket.getOutputStream] was ever called. */
        val plainStreamsAccessed: Boolean
    }

    /**
     * Returns a delegating view of [socket] that records plain-stream access so a later
     * [wrapClient]/[wrapAccepted] call can refuse tainted sockets (clean-boundary rule above).
     * The returned Socket is interchangeable with [socket] for all other purposes.
     */
    fun withPlainStreamTracking(socket: Socket): Socket = TrackedSocket(socket)

    /**
     * Wraps an ALREADY-CONNECTED plain socket in CLIENT mode and completes the TLS handshake
     * before returning (fail-closed: any verification/handshake error yields [Result.failure]
     * with BOTH the layered and underlying socket closed).
     *
     * CLEAN-BOUNDARY RULE: callers MUST NOT have touched the plain socket's streams before
     * calling this — see the class KDoc for why even one buffered byte corrupts the session.
     *
     * @param peerDeviceId       pin lookup key; `null` fails every handshake closed.
     * @param handshakeTimeoutMs applied as a temporary SO_TIMEOUT (mechanism documented above).
     */
    suspend fun wrapClient(
        socket: Socket,
        peerDeviceId: String?,
        pinVerifier: FlashPinVerifier,
        keyManagers: Array<KeyManager>? = null,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        /** Manual-dial deferral (ADR-040): accept an unknown peer's leaf and report it here. */
        deferPinWhenDeviceIdUnknown: Boolean = false,
        onLeafObserved: (leafFingerprintHex: String) -> Unit = {},
    ): Result<SSLSocket> = withContext(Dispatchers.IO) {
        val result = runCatching {
            refuseIfTouched(socket)
            val context = FlashTlsContextFactory.clientContext(
                pinVerifier,
                peerDeviceId,
                keyManagers,
                deferPinWhenDeviceIdUnknown = deferPinWhenDeviceIdUnknown,
                onLeafObserved = onLeafObserved,
            )
            val underlying = if (socket is TrackedSocket) socket.delegate else socket
            val ssl = context.socketFactory.createSocket(
                underlying,
                underlying.inetAddress?.hostAddress ?: "",
                underlying.port,
                /* autoClose = */ true,
            ) as SSLSocket
            try {
                FlashTlsContextFactory.configure(ssl)
                val previousTimeout = socket.soTimeout
                ssl.soTimeout = handshakeTimeoutMs.toInt().coerceIn(1, Int.MAX_VALUE)
                ssl.startHandshake()
                ssl.soTimeout = previousTimeout
                ssl
            } catch (error: Throwable) {
                // autoClose=true: closing the layered socket closes the underlying one too.
                runCatching { ssl.close() }
                throw error
            }
        }
        if (result.isFailure) {
            // Covers pre-createSocket failures (e.g. taint refusal) where nothing wraps yet.
            runCatching { socket.close() }
        }
        result
    }

    /**
     * Forces the (lazy) handshake of a socket produced by [wrapAccepted], with the same
     * SO_TIMEOUT-based bound as [wrapClient]. Fail-closed: closes the socket on error.
     */
    suspend fun forceHandshake(
        socket: SSLSocket,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): Result<SSLSocket> = withContext(Dispatchers.IO) {
        runCatching {
            val previousTimeout = socket.soTimeout
            socket.soTimeout = handshakeTimeoutMs.toInt().coerceIn(1, Int.MAX_VALUE)
            socket.startHandshake()
            socket.soTimeout = previousTimeout
            socket
        }.onFailure { runCatching { socket.close() } }
    }

    /**
     * Wraps an ACCEPTED plain socket in SERVER mode and returns IMMEDIATELY — the handshake is
     * LAZY and runs on the first read/write through the returned socket (JDK semantics: an
     * SSLSocket created via `createSocket(socket, …)` performs no I/O until first use, which is
     * also why `setUseClientMode(false)` below is still legal here). Callers that want eager
     * verification may invoke [forceHandshake]; otherwise the caller's next read (e.g. the
     * WebSocket HTTP-upgrade parse) transparently drives the handshake.
     *
     * CLEAN-BOUNDARY RULE: the accepted socket's streams MUST be untouched — see class KDoc.
     *
     * @param expectedClientDeviceId only consulted if the caller enables client-auth on the
     *                               returned socket; see [FlashTlsContextFactory.serverContext].
     */
    fun wrapAccepted(
        acceptedPlainSocket: Socket,
        pinVerifier: FlashPinVerifier,
        keyManagers: Array<KeyManager>,
        expectedClientDeviceId: String? = null,
        /**
         * Audit S1: demand the client's certificate. Without this an inbound peer is encrypted but
         * unauthenticated, and its identity is whatever it writes into HELLO. Every Flash client
         * already presents its identity certificate when asked.
         */
        requireClientCertificate: Boolean = false,
        onClientLeafObserved: (leafFingerprintHex: String) -> Unit = {},
    ): SSLSocket {
        refuseIfTouched(acceptedPlainSocket)
        val context = FlashTlsContextFactory.serverContext(
            pinVerifier,
            keyManagers,
            expectedClientDeviceId,
            deferPinWhenDeviceIdUnknown = requireClientCertificate && expectedClientDeviceId == null,
            onLeafObserved = onClientLeafObserved,
        )
        val underlying = if (acceptedPlainSocket is TrackedSocket) acceptedPlainSocket.delegate else acceptedPlainSocket
        val ssl = context.socketFactory.createSocket(
            underlying,
            underlying.inetAddress?.hostAddress ?: "",
            underlying.port,
            /* autoClose = */ true,
        ) as SSLSocket
        // Legal ONLY because the handshake has not started yet (lazy semantics, research (a)).
        ssl.useClientMode = false
        FlashTlsContextFactory.configure(ssl)
        if (requireClientCertificate) ssl.needClientAuth = true
        return ssl
    }

    /**
     * Best-effort enforcement of the clean-boundary rule: deterministic for sockets created via
     * [withPlainStreamTracking]; undocumented for foreign implementations (convention only).
     */
    private fun refuseIfTouched(socket: Socket) {
        if (socket is PlainStreamAccessAudited && socket.plainStreamsAccessed) {
            throw IllegalStateException(
                "TLS upgrade refused: the plain socket's streams were accessed before wrapping. " +
                    "Bytes read/written before the handshake corrupt the TLS stream (clean-boundary " +
                    "rule) — obtain streams only AFTER SecureSocketUpgrader wrapping.",
            )
        }
    }

    /**
     * Delegating view of [delegate] that records plain-stream access.
     *
     * Implemented with explicit method delegation (`java.net.Socket` has NO public
     * copy/delegating constructor); every member the WS transport or this object touches is
     * forwarded, so the wrapper is behaviourally interchangeable with the original socket.
     * The unused base `Socket()` impl stays forever unconnected — all meaningful calls route
     * through [delegate].
     */
    private class TrackedSocket(
        internal val delegate: Socket,
    ) : Socket(), PlainStreamAccessAudited {

        @Volatile
        override var plainStreamsAccessed: Boolean = false
            private set

        override fun getInputStream(): InputStream {
            plainStreamsAccessed = true
            return delegate.getInputStream()
        }

        override fun getOutputStream(): OutputStream {
            plainStreamsAccessed = true
            return delegate.getOutputStream()
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
        override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)

        override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

        override fun close() = delegate.close()

        override fun isClosed(): Boolean = delegate.isClosed
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isBound(): Boolean = delegate.isBound
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)

        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()

        override fun toString(): String = "TrackedSocket[$delegate]"
    }
}
