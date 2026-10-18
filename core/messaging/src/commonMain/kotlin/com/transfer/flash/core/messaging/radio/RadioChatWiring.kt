package com.transfer.flash.core.messaging.radio

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.radio.chat.RadioChatEngine
import com.transfer.flash.core.radio.chat.RadioChatHost
import com.transfer.flash.core.radio.connect.RadioAuthorisationStore
import com.transfer.flash.core.radio.driver.PersistentRadioStore
import com.transfer.flash.core.radio.driver.RadioStatePersistence
import com.transfer.flash.core.radio.gateway.RadioGatewayManager
import com.transfer.flash.core.radio.wire.EcdsaRawSignature
import com.transfer.flash.core.radio.wire.RadioCrypto
import com.transfer.flash.core.radio.wire.RadioPairingAdapter
import com.transfer.flash.core.radio.wire.RadioPeerTrustProvider
import com.transfer.flash.core.radio.wire.RadioSession
import com.transfer.flash.core.radio.wire.RadioSignatureScheme
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.trust.FlashTrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The shared host wiring that connects the radio gateway to the chat engine (ERROR-703): Flash pairings become radio
 * peers, the device identity key signs Profile A frames, and one persistent store keeps the send counters and replay
 * windows across engines and restarts. Android and Windows call [createHost] with their own platform pieces.
 */
public object RadioChatWiring {

    /** Radio peers are exactly the peers Flash has paired with and holds a 32-byte pairing session key for. */
    public fun peerTrust(trustStore: FlashTrustStore): RadioPeerTrustProvider = object : RadioPeerTrustProvider {
        override fun isPeerTrusted(peerId: String): Boolean = trustStore.isTrusted(peerId)

        override fun getPairingSessionKey(peerId: String): ByteArray? =
            trustStore.getSessionKey(peerId)?.takeIf { it.size == 32 }

        override fun getPinnedFingerprint(peerId: String): String? = trustStore.getPin(peerId)

        override fun getTrustedPeerIds(): Set<String> = trustStore.getTrustedPeers().keys.mapTo(HashSet()) { it.value }
    }

    /** Signs with the device identity key (FlashCrypto gives DER, the radio wire wants raw r||s); verifies with [verifier]. */
    public fun signer(crypto: FlashCrypto, verifier: RadioSignatureScheme): RadioSignatureScheme =
        object : RadioSignatureScheme {
            override fun sign(data: ByteArray): ByteArray =
                EcdsaRawSignature.derToRaw(crypto.sign(data)) ?: error("identity signature is not a valid DER ECDSA signature")

            override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
                verifier.verify(publicKey, data, signature)
        }

    /**
     * A [RadioChatHost] that follows [managers] (the host's gateway controller) and builds a [RadioChatEngine] over
     * every driver the gateway creates.
     */
    public fun createHost(
        scope: CoroutineScope,
        managers: Flow<RadioGatewayManager?>,
        localDeviceId: String,
        trustStore: FlashTrustStore,
        identity: FlashCrypto,
        radioCrypto: RadioCrypto,
        verifier: RadioSignatureScheme,
        persistence: RadioStatePersistence,
        authorisationStore: RadioAuthorisationStore,
        timeSource: FlashTimeSource = SystemTimeSource,
    ): RadioChatHost {
        val store = PersistentRadioStore(persistence)
        val trust = peerTrust(trustStore)
        val signer = signer(identity, verifier)
        return RadioChatHost.over(scope, managers) { engineScope, driver ->
            val session = RadioSession(
                localId = localDeviceId,
                crypto = radioCrypto,
                replayStore = store,
                counterStore = store,
                signer = signer,
                localPublicKey = identity.identityPublicKeyEncoded,
            )
            RadioChatEngine(
                localDeviceId = localDeviceId,
                scope = engineScope,
                driver = driver,
                session = session,
                pairingAdapter = RadioPairingAdapter(localDeviceId, trust, radioCrypto),
                authorisationStore = authorisationStore,
                timeSource = timeSource,
            )
        }
    }
}
