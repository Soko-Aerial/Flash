package com.transfer.flash.core.radio.wire

import com.transfer.flash.core.radio.kiss.Ax25Address

/**
 * Source of pairing trust and pairing session keys from an existing Flash pairing.
 *
 * Rule from Plan R §6 and ADR-101 / ADR-173:
 * Radio communication derives its session keys strictly from an EXISTING Flash pairing
 * (never a new trust path, never unauthenticated).
 */
public interface RadioPeerTrustProvider {
    /** True if [peerId] is a trusted, paired peer. */
    public fun isPeerTrusted(peerId: String): Boolean

    /** Retrieves the 32-byte shared session key for [peerId], or null if not paired. */
    public fun getPairingSessionKey(peerId: String): ByteArray?

    /** Retrieves the pinned identity public key fingerprint (hex) for [peerId], if known. */
    public fun getPinnedFingerprint(peerId: String): String? = null

    /** All currently trusted peer IDs. */
    public fun getTrustedPeerIds(): Set<String> = emptySet()
}

/**
 * Derived directional cryptographic keys for one radio peer.
 * Domain-separated per RADIO-WIRE-FORMAT.md §6.
 */
public class RadioDirectionalKeys(
    public val key: ByteArray,
    public val nonceSalt: ByteArray,
    public val tagKey: ByteArray,
)

/**
 * Adapts Flash pairing trust into [RadioSession], computing directional keys, rotating station tags,
 * and station labels from pairing keys and identity public keys without ever exposing raw keys in logs.
 */
public class RadioPairingAdapter(
    public val localDeviceId: String,
    private val trustProvider: RadioPeerTrustProvider,
    private val crypto: RadioCrypto,
) {
    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId must not be blank" }
    }

    /**
     * Checks if [peerId] is currently trusted and has a valid 32-byte pairing session key.
     */
    public fun isPairedForRadio(peerId: String): Boolean {
        if (!trustProvider.isPeerTrusted(peerId)) return false
        val key = trustProvider.getPairingSessionKey(peerId) ?: return false
        return key.size == 32
    }

    /**
     * Derives directional session keys for [peerId] in the specified direction.
     *
     * Per RADIO-WIRE-FORMAT.md §6:
     * OKM (68 bytes) = HKDF-SHA256(ikm = K, salt = "flash-radio-v1",
     *                             info = "flash-radio-v1/dir" || len(S) || S || len(R) || R, L = 68)
     * key = OKM[0..32)
     * nonceSalt = OKM[32..36)
     * tagKey = OKM[36..68)
     */
    public fun deriveDirectionKeys(peerId: String, sendDirection: Boolean): RadioDirectionalKeys? {
        if (!isPairedForRadio(peerId)) return null
        val pairKey = trustProvider.getPairingSessionKey(peerId) ?: return null
        val sender = if (sendDirection) localDeviceId else peerId
        val receiver = if (sendDirection) peerId else localDeviceId

        val s = sender.encodeToByteArray()
        val r = receiver.encodeToByteArray()
        require(s.size in 1..255 && r.size in 1..255) { "Device IDs must be 1..255 bytes" }

        val info = "flash-radio-v1/dir".encodeToByteArray() +
            byteArrayOf(s.size.toByte()) + s +
            byteArrayOf(r.size.toByte()) + r

        val okm = RadioHkdf.derive(
            crypto = crypto,
            ikm = pairKey,
            salt = "flash-radio-v1".encodeToByteArray(),
            info = info,
            length = 32 + 4 + 32,
        )

        return RadioDirectionalKeys(
            key = okm.copyOfRange(0, 32),
            nonceSalt = okm.copyOfRange(32, 36),
            tagKey = okm.copyOfRange(36, 68),
        )
    }

    /**
     * Registers [peerId] into [session] if it is trusted and has a 32-byte pairing key.
     * Returns true if registered, false if untrusted or key missing.
     */
    public fun registerPeer(session: RadioSession, peerId: String): Boolean {
        if (!isPairedForRadio(peerId)) {
            session.removePeer(peerId)
            return false
        }
        val pairKey = trustProvider.getPairingSessionKey(peerId) ?: return false
        session.addPeer(peerId, pairKey)
        return true
    }

    /** The ids [RadioPeerTrustProvider] currently trusts. */
    public fun trustedPeerIds(): Set<String> = trustProvider.getTrustedPeerIds()

    /**
     * Synchronizes all trusted peers from [trustProvider] into [session].
     * Also removes any peer previously registered in [session] that is no longer trusted.
     */
    public fun syncPeers(session: RadioSession) {
        val trusted = trustProvider.getTrustedPeerIds()
        for (peerId in trusted) {
            registerPeer(session, peerId)
        }
    }

    /**
     * Computes the 4-byte rotating station tag for [peerId] at [epoch] (15-minute epoch).
     * Returns null if [peerId] is not paired.
     */
    public fun computeRotatingTag(peerId: String, epoch: Long, sendDirection: Boolean): ByteArray? {
        val keys = deriveDirectionKeys(peerId, sendDirection) ?: return null
        val epochBytes = ByteArray(8) { (epoch ushr (56 - 8 * it)).toByte() }
        val tagPrefix = "tag".encodeToByteArray()
        return crypto.hmacSha256(keys.tagKey, tagPrefix + epochBytes).copyOf(4)
    }

    /**
     * Computes the neutral rotating AX.25 source station label for [peerId] at [nowMs].
     * Returns null if [peerId] is not paired.
     */
    public fun computeStationLabel(peerId: String, nowMs: Long, epochMs: Long = 15 * 60 * 1000L): Ax25Address? {
        val keys = deriveDirectionKeys(peerId, sendDirection = true) ?: return null
        val epoch = nowMs / epochMs
        val epochBytes = ByteArray(8) { (epoch ushr (56 - 8 * it)).toByte() }
        val lblPrefix = "lbl".encodeToByteArray()
        val mac = crypto.hmacSha256(keys.tagKey, lblPrefix + epochBytes)
        val chars = CharArray(6) { LABEL_ALPHABET[(mac[it].toInt() and 0xFF) % LABEL_ALPHABET.length] }
        val ssid = mac[6].toInt() and 0x0F
        return Ax25Address(chars.concatToString(), ssid)
    }

    /**
     * Computes the 4-byte static station tag for Profile A (signed broadcast),
     * which is the first 4 bytes of SHA-256(sender SPKI public key).
     */
    public fun computeSignedTag(identityPublicKey: ByteArray): ByteArray {
        require(identityPublicKey.isNotEmpty()) { "Identity public key must not be empty" }
        return crypto.sha256(identityPublicKey).copyOf(4)
    }

    private companion object {
        const val LABEL_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
