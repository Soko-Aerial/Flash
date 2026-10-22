package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The exact byte strings a device signs to prove possession of its identity key (SEC-14, SEC-07). Every one is a
 * length-prefixed [AuthCanonicalWriter] form with its own domain tag, so a signature made for one purpose can never
 * be replayed as another, and `a|bc` can never be confused with `ab|c`.
 *
 * `serverId` is the realm audience: the upper-case hex SHA-256 fingerprint of the organisation ROOT public key, the
 * same value an invite link carries as `root=`. A client knows it before it has ever talked to the hub, which is what
 * lets the very first request (enrolment) be audience-bound. The module treats it as an opaque string (it does not
 * hash anything); callers compare it case-insensitively against what they pinned.
 */
@FlashInternalApi
public object HubAuth {

    public const val AUTH_TAG: String = "flash-hub-auth-v1"
    public const val ENROLL_TAG: String = "flash-hub-enroll-v1"
    public const val RENEW_TAG: String = "flash-hub-renew-v1"

    /** Domain tag of the sealing-key binding (ADR-121, ADR-124). Never change it: add a new tag for a new format. */
    public const val SEALING_KEY_TAG: String = "flash-hub-sealkey-v1"

    /**
     * Relay handshake (`FLASH_HUB_HELLO.signature`) and REST `/auth/verify`: the device signs
     * `(serverId, serverNonce, clientNonce, deviceId, certSerial)`.
     *
     * The relay nonce comes from the WebSocket `FLASH_HUB_CHALLENGE`, the REST nonce from `/auth/challenge`; the two
     * nonce stores are separate and every nonce is single-use, so a signature cannot be moved between the two channels.
     */
    public fun authSigningBytes(
        serverId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        certSerial: Long,
    ): ByteArray = AuthCanonicalWriter(AUTH_TAG)
        .text(serverId)
        .text(serverNonce)
        .text(clientNonce)
        .text(deviceId)
        .long(certSerial)
        .build()

    /**
     * Enrolment proof of possession (`EnrollRequest.proof`): the device signs
     * `(token, deviceId, subjectPublicKey, serverId)` with the key whose public half it submits, so a token holder
     * cannot enrol someone else's public key (or an invented one) as its own.
     */
    public fun enrollProofBytes(
        token: String,
        deviceId: String,
        subjectPublicKey: String,
        serverId: String,
    ): ByteArray = AuthCanonicalWriter(ENROLL_TAG)
        .text(token)
        .text(deviceId)
        .text(subjectPublicKey)
        .text(serverId)
        .build()

    /**
     * Renewal proof (`RenewRequest.signature`): the device signs
     * `(serverId, serverNonce, clientNonce, deviceId, currentCertSerial, subjectPublicKey)`.
     */
    public fun renewProofBytes(
        serverId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        currentCertSerial: Long,
        subjectPublicKey: String,
    ): ByteArray = AuthCanonicalWriter(RENEW_TAG)
        .text(serverId)
        .text(serverNonce)
        .text(clientNonce)
        .text(deviceId)
        .long(currentCertSerial)
        .text(subjectPublicKey)
        .build()

    /**
     * Sealing-key binding (ADR-121 / ADR-124, `PUT /api/v1/device/sealing-key`): the device identity key signs
     * `(deviceId, sealingPublicKey)` where `sealingPublicKey` is the raw X.509 SPKI byte string of the P-256 ECDH key
     * (NOT its Base64 text). The client signs these bytes and the hub verifies the very same bytes against the
     * certified device key before it publishes the sealing key to the directory.
     */
    public fun sealingKeyBindingBytes(deviceId: String, sealingPublicKey: ByteArray): ByteArray =
        AuthCanonicalWriter(SEALING_KEY_TAG)
            .text(deviceId)
            .bytes(sealingPublicKey)
            .build()

    /** True when [identifier] is acceptable as a device id: 1..128 chars, no control characters, no whitespace padding. */
    public fun isValidDeviceId(identifier: String): Boolean =
        identifier.isNotEmpty() &&
            identifier.length <= com.transfer.flash.core.hubprotocol.HubContract.MAX_ID_LENGTH &&
            identifier == identifier.trim() &&
            identifier.none { it.code < 0x20 || it.code == 0x7F }
}

/**
 * Renewal window rule (default 24 h before `validTo` to 72 h after; ADR-109). The hub derives the windows of a longer-lived
 * certificate from its own lifetime with [earlyWindowFor] and [graceFor] (ADR-222): a 30-day certificate must not keep a 1-day
 * window.
 *
 * A client must use the SAME derivation, or it refuses to ask for a renewal the hub would accept, and it starts too late: a
 * 365-day certificate is renewable for 18.25 days after it expired, but a hardcoded 72-hour grace left the app reporting
 * CERTIFICATE_INVALID without ever contacting the hub (2026-10-22 review F4). Passing [earlyWindowMs]/[graceMs] explicitly still
 * wins, so the hub's configured overrides keep their meaning.
 */
@FlashInternalApi
public object RenewalPolicy {
    /** The early renewal window for a certificate that lives [lifetimeMs]: a tenth of it, never under the 24 h default. */
    public fun earlyWindowFor(lifetimeMs: Long): Long =
        maxOf(com.transfer.flash.core.hubprotocol.HubContract.RENEW_EARLY_WINDOW_MS, lifetimeMs / 10)

    /** The grace after `validTo` for a certificate that lives [lifetimeMs]: a twentieth of it, never under the 72 h default. */
    public fun graceFor(lifetimeMs: Long): Long =
        maxOf(com.transfer.flash.core.hubprotocol.HubContract.RENEW_GRACE_MS, lifetimeMs / 20)

    /** True when [cert] may be renewed at [nowMs]; null windows mean "derive them from this certificate's lifetime". */
    public fun isRenewable(
        cert: DeviceCert,
        nowMs: Long,
        earlyWindowMs: Long? = null,
        graceMs: Long? = null,
    ): Boolean {
        val lifetimeMs = cert.validTo - cert.validFrom
        val early = earlyWindowMs ?: earlyWindowFor(lifetimeMs)
        val grace = graceMs ?: graceFor(lifetimeMs)
        return nowMs >= cert.validTo - early && nowMs <= cert.validTo + grace
    }

    /**
     * True when a client should start trying to renew (inside the early window, or already past `validTo`); a null
     * [earlyWindowMs] is derived from the certificate's lifetime.
     */
    public fun shouldRenew(
        cert: DeviceCert,
        nowMs: Long,
        earlyWindowMs: Long? = null,
    ): Boolean {
        val early = earlyWindowMs ?: earlyWindowFor(cert.validTo - cert.validFrom)
        return nowMs >= cert.validTo - early
    }
}
