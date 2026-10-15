@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.messaging.model.FlashHubNoticeKind
import com.transfer.flash.core.messaging.model.FlashHubNoticeUi
import com.transfer.flash.core.security.enterprise.HubSealingFingerprint
import com.transfer.flash.core.security.enterprise.HubSealingPin

/** Hub messages this device dropped instead of storing (ADR-126): shown as plain counts in the connection details. */
@FlashInternalApi
public data class HubDropCounts(
    /** Sealed messages that did not verify (changed on the way, wrong recipient, unknown sender key). */
    public val unverified: Int = 0,
    /** Readable hub messages refused because "Messages through the hub" is off. */
    public val readableOff: Int = 0,
)

/**
 * Decides which one inline notice, if any, a conversation shows about the hub path (ADR-126). Pure: the repository feeds
 * it the bridge's state, the UI renders the result.
 *
 * - A changed sealing key of the colleague always shows (it is a security notice), whether or not they are online.
 * - "Waiting for X to update the app" shows only while X is not reachable another way: when they are online the message
 *   goes directly and the hub path is irrelevant.
 * - A group linked to the hub always says truthfully how its messages travel through the server (they are never sealed).
 */
@FlashInternalApi
public object HubNoticeMath {

    public fun notice(
        conversationId: String,
        isGroup: Boolean,
        groupLinkedToHub: Boolean,
        contentViaHub: Boolean,
        peerName: String,
        peerOnline: Boolean,
        waiting: HubWaitReason?,
        changedKey: HubSealingPin?,
    ): FlashHubNoticeUi? {
        if (isGroup) {
            if (!groupLinkedToHub) return null
            val kind = if (contentViaHub) FlashHubNoticeKind.GROUP_READABLE else FlashHubNoticeKind.GROUP_HUB_OFF
            return FlashHubNoticeUi(kind, peerId = conversationId, peerName = "")
        }
        if (changedKey != null && changedKey.blocked) {
            return FlashHubNoticeUi(
                kind = FlashHubNoticeKind.KEY_CHANGED,
                peerId = conversationId,
                peerName = peerName,
                newCode = HubSealingFingerprint.short(changedKey.pending ?: changedKey.pinned),
                oldCode = HubSealingFingerprint.short(changedKey.pinned),
            )
        }
        if (peerOnline) return null
        return when (waiting) {
            HubWaitReason.NEEDS_UPDATE -> FlashHubNoticeUi(FlashHubNoticeKind.PEER_NEEDS_UPDATE, conversationId, peerName)
            HubWaitReason.SWITCHED_OFF -> FlashHubNoticeUi(FlashHubNoticeKind.HUB_SWITCHED_OFF, conversationId, peerName)
            HubWaitReason.IDENTITY_MISMATCH -> FlashHubNoticeUi(FlashHubNoticeKind.IDENTITY_MISMATCH, conversationId, peerName)
            HubWaitReason.KEY_CHANGED, null -> null
        }
    }
}
