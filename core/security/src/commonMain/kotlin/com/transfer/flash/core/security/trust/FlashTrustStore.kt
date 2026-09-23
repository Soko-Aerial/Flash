package com.transfer.flash.core.security.trust

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult

/**
 * Persistent store for trusted/paired peer devices.
 * Tracks peers that have completed visual verification or pairing handshakes.
 */
public interface FlashTrustStore {
    /** Returns true if [deviceId] is registered as a trusted/paired peer. */
    public fun isTrusted(deviceId: FlashDeviceId): Boolean

    /** Convenience overload checking trust by raw string device ID. */
    public fun isTrusted(deviceId: String): Boolean = isTrusted(FlashDeviceId(deviceId))

    /** Persists [deviceId] with its known [friendlyName] as a trusted peer. */
    public fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit>

    /** Convenience overload trusting peer by raw string device ID. */
    public fun trustPeer(deviceId: String, friendlyName: String): FlashResult<Unit> =
        trustPeer(FlashDeviceId(deviceId), friendlyName)

    /** Revokes trust from [deviceId], requiring a new pairing step on next connection. */
    public fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit>

    /** Convenience overload revoking trust by raw string device ID. */
    public fun revokeTrust(deviceId: String): FlashResult<Unit> = revokeTrust(FlashDeviceId(deviceId))

    /** Returns all currently trusted peers mapped by device ID to friendly name. */
    public fun getTrustedPeers(): Map<FlashDeviceId, String>

    /** Persists a derived shared session key (e.g. 32-byte AES-256 key) for [deviceId]. */
    public fun saveSessionKey(deviceId: FlashDeviceId, key: ByteArray): FlashResult<Unit> =
        FlashResult.Success(Unit)

    /** Convenience overload saving session key by raw string device ID. */
    public fun saveSessionKey(deviceId: String, key: ByteArray): FlashResult<Unit> =
        saveSessionKey(FlashDeviceId(deviceId), key)

    /** Retrieves the derived shared session key for [deviceId], or null if none is stored. */
    public fun getSessionKey(deviceId: FlashDeviceId): ByteArray? = null

    /** Convenience overload retrieving session key by raw string device ID. */
    public fun getSessionKey(deviceId: String): ByteArray? = getSessionKey(FlashDeviceId(deviceId))

    /** Persists an identity public-key fingerprint (SHA-256 SPKI hex) for [deviceId] (TOFU pin). */
    public fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit> =
        FlashResult.Success(Unit)

    /** Convenience overload saving pin by raw string device ID. */
    public fun savePin(deviceId: String, fingerprintHex: String): FlashResult<Unit> =
        savePin(FlashDeviceId(deviceId), fingerprintHex)

    /** Retrieves the pinned identity public-key fingerprint for [deviceId], or null if none is stored. */
    public fun getPin(deviceId: FlashDeviceId): String? = null

    /** Convenience overload retrieving pin by raw string device ID. */
    public fun getPin(deviceId: String): String? = getPin(FlashDeviceId(deviceId))

    /**
     * Records that [deviceId] was paired with protocol v2 (ADR-042), whose code a man-in-the-middle
     * cannot force. Pairings made with v1 remain trusted but are reported unverified until re-verified.
     */
    public fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)

    /** True when [deviceId]'s current pairing was made with protocol v2. Cleared by [revokeTrust]. */
    public fun isVerified(deviceId: FlashDeviceId): Boolean = false
}

