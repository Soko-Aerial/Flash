package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi

/** Why a relay connection attempt or session ended. The UI turns each kind into a plain sentence. */
@FlashInternalApi
public enum class RelayFailureKind {
    /** The socket could not be opened or dropped (offline, DNS, TLS, reset). Retry with backoff. */
    NETWORK,

    /** The hub did not complete the handshake within the deadline. Retry with backoff. */
    HANDSHAKE_TIMEOUT,

    /** The hub that answered is not the pinned organisation's (its `serverId` is not the pinned root). Do not trust; retry slowly. */
    SERVER_MISMATCH,

    /** The hub says this certificate is revoked. Terminal until the device enrols again. */
    REVOKED,

    /** The hub says the certificate is expired or not yet valid. Renewal is attempted, then the connection retried. */
    CERT_EXPIRED,

    /** The hub rejected the handshake (bad signature, unknown device). */
    AUTH_REJECTED,

    /** The hub or the client sent something the other side does not understand. */
    PROTOCOL,

    /** The hub closed the session on purpose (`FLASH_KICK`). */
    KICKED,

    /** The hub speaks another relay wire version. Terminal until the app or the hub is updated. */
    UNSUPPORTED_VERSION,

    /** The hub stopped answering keep-alives. */
    KEEPALIVE_TIMEOUT,

    /** The client could not keep up and dropped frames; it reconnects so the hub replays what was not acknowledged. */
    OVERFLOW,
}

/** The latest relay failure with the hub's error [code] (if any) and when it happened. */
@FlashInternalApi
public data class RelayFailure(
    public val kind: RelayFailureKind,
    public val code: String? = null,
    public val atMs: Long = 0L,
) {
    /** True when retrying with the same certificate cannot help. */
    public val isTerminal: Boolean
        get() = kind == RelayFailureKind.REVOKED || kind == RelayFailureKind.UNSUPPORTED_VERSION
}
