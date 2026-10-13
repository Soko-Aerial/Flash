@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingFingerprint
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingPin
import com.transfer.flash.core.security.enterprise.HubSealingPins
import com.transfer.flash.core.security.enterprise.UnsealedPayload
import kotlinx.coroutines.flow.StateFlow

/**
 * [HubSealingPort] backed by the hub directory (ADR-121, ADR-126). A recipient's sealing key is accepted only when the
 * identity key the hub certified for that device signed it ([HubSealingKeyPair.verifyBinding]); the hub itself cannot
 * mint a key the sender would trust.
 *
 * With [pins] the first key seen for a colleague is remembered; a different one later is [HubSealingStatus.KEY_CHANGED]
 * and nothing is sealed or sent to them until [confirmKeyChange]. A pending change is remembered even while the
 * directory cannot be read, so a missing answer never downgrades to a plain message.
 *
 * Until a colleague publishes a key ([EnterpriseDirectoryMember.sealingPublicKey] is null) the status is
 * [HubSealingStatus.NOT_PUBLISHED] and the bridge follows the user's content-via-hub choice.
 *
 * @param ownKey this device's own sealing key for opening messages (loaded from the key store on demand); null when
 *   the device has none yet.
 * @param lookup the directory entry of a device (cached by the caller).
 */
@FlashInternalApi
public class DirectoryHubSealing(
    private val crypto: FlashCrypto,
    private val localDeviceId: String,
    private val ownKey: suspend () -> HubSealingKeyPair?,
    private val pins: HubSealingPins? = null,
    private val lookup: suspend (deviceId: String) -> EnterpriseDirectoryMember?,
) : HubSealingPort {

    private class Verified(val sealingKey: ByteArray, val identityKey: ByteArray)

    private suspend fun verified(deviceId: String): Verified? {
        val member = lookup(deviceId) ?: return null
        val identity = member.subjectPublicKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        val sealing = member.sealingPublicKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        val signature = member.sealingSignature ?: return null
        if (!HubSealingKeyPair.verifyBinding(identity, deviceId, sealing, signature)) return null
        return Verified(sealing, identity)
    }

    override suspend fun statusFor(recipientDeviceId: String): HubSealingStatus {
        val key = verified(recipientDeviceId)
        if (key != null && pins != null) {
            pins.check(recipientDeviceId, HubSealingFingerprint.of(key.sealingKey))
        }
        // Even when the directory gave nothing now, a change that was never confirmed keeps holding.
        if (pins?.isBlocked(recipientDeviceId) == true) return HubSealingStatus.KEY_CHANGED
        return if (key != null) HubSealingStatus.AVAILABLE else HubSealingStatus.NOT_PUBLISHED
    }

    override val pendingKeyChanges: StateFlow<Map<String, HubSealingPin>>
        get() = pins?.blocked ?: super.pendingKeyChanges

    override suspend fun canSealFor(recipientDeviceId: String): Boolean = statusFor(recipientDeviceId) == HubSealingStatus.AVAILABLE

    override suspend fun confirmKeyChange(recipientDeviceId: String): Boolean = pins?.confirm(recipientDeviceId) == true

    override suspend fun seal(recipientDeviceId: String, msgId: String, sentAt: Long, innerType: String, body: String): String? {
        if (statusFor(recipientDeviceId) != HubSealingStatus.AVAILABLE) return null
        val recipient = verified(recipientDeviceId) ?: return null
        return HubSealing.seal(crypto, localDeviceId, recipientDeviceId, recipient.sealingKey, msgId, sentAt, innerType, body)
    }

    override suspend fun open(senderDeviceId: String, msgId: String, sentAt: Long, payload: String): UnsealedPayload? {
        val key = ownKey() ?: return null
        val sender = lookup(senderDeviceId)?.subjectPublicKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        return HubSealing.open(key, sender, senderDeviceId, localDeviceId, msgId, sentAt, payload)
    }
}
