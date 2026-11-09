@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.enterprise.HubRevocationGossip
import com.transfer.flash.core.enterprise.RealOrgPeerResolver
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.security.enterprise.HubClientAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The host calls this only for its post-handshake session, whose identity key is the TLS peer SPKI. */
@FlashInternalApi
public fun HubRevocationGossip.bindLanSession(
    session: FlashSession,
    resolver: RealOrgPeerResolver,
    isCurrent: () -> Boolean,
): HubRevocationGossip.Session {
    val peerId = session.peerDeviceId.value
    val negotiated = session.transportType == FlashTransportType.LAN && HubRevocationGossip.FEATURE in session.peer.features
    val fingerprint = if (negotiated) session.peer.identityKey?.let { key ->
        runCatching { HubClientAuth.rootFingerprintHex(key) }.getOrNull()
    } else null
    return this.session(peerId, fingerprint != null, { root ->
        fingerprint != null && isCurrent() && resolver.authenticatesRevocationPeer(peerId, root, fingerprint)
    }) { text ->
        withContext<Unit>(Dispatchers.IO) { if (isCurrent()) session.sendText(text) }
    }
}
