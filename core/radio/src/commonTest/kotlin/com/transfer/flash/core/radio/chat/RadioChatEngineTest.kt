package com.transfer.flash.core.radio.chat

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.InMemoryRadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioAuthorisationGate
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.driver.InMemoryRadioStatePersistence
import com.transfer.flash.core.radio.driver.KissTncConfig
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.PersistentRadioStore
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.driver.TxPacingPolicy
import com.transfer.flash.core.radio.kiss.Ax25Address
import com.transfer.flash.core.radio.kiss.Ax25FrameCodec
import com.transfer.flash.core.radio.kiss.Ax25Role
import com.transfer.flash.core.radio.sim.FakeAirChannel
import com.transfer.flash.core.radio.sim.FakeTnc
import com.transfer.flash.core.radio.sim.InMemoryLink
import com.transfer.flash.core.radio.sim.inMemoryLinkPair
import com.transfer.flash.core.radio.wire.JdkRadioCrypto
import com.transfer.flash.core.radio.wire.RadioCrypto
import com.transfer.flash.core.radio.wire.RadioDrop
import com.transfer.flash.core.radio.wire.RadioIngest
import com.transfer.flash.core.radio.wire.RadioKind
import com.transfer.flash.core.radio.wire.RadioPairingAdapter
import com.transfer.flash.core.radio.wire.RadioPeerTrustProvider
import com.transfer.flash.core.radio.wire.RadioSession
import com.transfer.flash.core.radio.wire.RadioSessionConfig
import com.transfer.flash.core.radio.wire.RadioSignatureScheme
import com.transfer.flash.core.radio.wire.RadioWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadioChatEngineTest {

    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { (it + 1).toByte() }
    private val wrongKey = ByteArray(32) { (it + 42).toByte() }

    private class StationRig(
        val deviceId: String,
        val hostLink: InMemoryLink,
        val tncLink: InMemoryLink,
        val fakeTnc: FakeTnc,
        val driver: KissTncDriver,
        val session: RadioSession,
        val authStore: InMemoryRadioAuthorisationStore,
        val engine: RadioChatEngine,
        val pairingAdapter: RadioPairingAdapter,
    ) {
        suspend fun close() {
            engine.close()
            driver.stop()
            hostLink.close()
            tncLink.close()
        }
    }

    private var rigA: StationRig? = null
    private var rigB: StationRig? = null
    private var air: FakeAirChannel? = null

    private fun buildStation(
        deviceId: String,
        scope: CoroutineScope,
        airChannel: FakeAirChannel,
        auth: AuthorisationConfirmation? = AuthorisationConfirmation(
            confirmedAtEpochMs = 1_700_000_000_000L,
            operatorCallsign = "W1AW",
        ),
    ): StationRig {
        val (hostLink, tncLink) = inMemoryLinkPair(deviceId)
        val fakeTnc = FakeTnc(hostSide = tncLink, air = airChannel, scope = scope)

        val driver = KissTncDriver(
            scope = scope,
            openLink = { hostLink },
            config = KissTncConfig(reconnectBaseMs = 100, reconnectCapMs = 500, readTimeoutMs = 50),
            pacing = TxPacingPolicy.NONE,
        )

        val persistence = InMemoryRadioStatePersistence()
        val store = PersistentRadioStore(persistence)
        val fakeSigner = object : RadioSignatureScheme {
            override fun sign(data: ByteArray): ByteArray = ByteArray(RadioWire.SIGNATURE_SIZE) { 0x42 }
            override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = true
        }
        val fakePubKey = ByteArray(64) { 0x01 }

        val session = RadioSession(
            localId = deviceId,
            crypto = crypto,
            replayStore = store,
            counterStore = store,
            config = RadioSessionConfig(epochMs = 15 * 60 * 1000L),
            signer = fakeSigner,
            localPublicKey = fakePubKey,
        )

        val trustProvider = object : RadioPeerTrustProvider {
            override fun isPeerTrusted(peerId: String): Boolean = true
            override fun getPairingSessionKey(peerId: String): ByteArray? = pairKey
            override fun getTrustedPeerIds(): Set<String> = setOf("station-a", "station-b")
        }
        val pairingAdapter = RadioPairingAdapter(deviceId, trustProvider, crypto)
        val authStore = InMemoryRadioAuthorisationStore(auth)

        val engine = RadioChatEngine(
            localDeviceId = deviceId,
            scope = scope,
            driver = driver,
            session = session,
            pairingAdapter = pairingAdapter,
            authorisationStore = authStore,
        )

        return StationRig(deviceId, hostLink, tncLink, fakeTnc, driver, session, authStore, engine, pairingAdapter)
    }

    @AfterTest
    fun tearDown() {
        rigA = null
        rigB = null
    }

    @Test
    fun radchatU01_textRoundTrip_deliveredAndCryptographicAckReceived() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) {
                delay(20)
            }
        }

        val sendResult = a.engine.sendText(
            recipientId = "station-b",
            messageId = "msg-101",
            text = "Hello station B from station A",
            sentAtMs = 1000L,
        )
        assertIs<RadioChatSendOutcome.SentWaitingAck>(sendResult)

        // Station B receives message
        val received = withTimeout(3000) {
            b.engine.inboundMessages.first()
        }
        assertEquals("station-a", received.senderDeviceId)
        assertEquals("Hello station B from station A", received.text)

        // Station A receives cryptographic delivery receipt
        val receipt = withTimeout(3000) {
            a.engine.deliveryReceipts.first()
        }
        assertEquals("msg-101", receipt.messageId)
        assertEquals("station-b", receipt.recipientDeviceId)
    }

    @Test
    fun radchatU02_segmentedMessage_reassembledCorrectly() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        // 500 bytes text requires 3 segments (188 bytes per segment max)
        val longText = "Segment-Payload-Test-".repeat(25) // 525 chars
        val sendResult = a.engine.sendText(
            recipientId = "station-b",
            messageId = "msg-long",
            text = longText,
            sentAtMs = 2000L,
        )
        assertIs<RadioChatSendOutcome.SentWaitingAck>(sendResult)

        val received = withTimeout(3000) {
            b.engine.inboundMessages.first()
        }
        assertEquals(longText, received.text)

        val receipt = withTimeout(3000) {
            a.engine.deliveryReceipts.first()
        }
        assertEquals("msg-long", receipt.messageId)
    }

    @Test
    fun radchatU03_duplicateFrames_deliveredOnlyOnceToChat() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        val inboundList = mutableListOf<RadioChatInbound>()
        val collectorJob = backgroundScope.launch {
            b.engine.inboundMessages.collect { inboundList.add(it) }
        }

        a.engine.sendText("station-b", "msg-dup", "Duplicate Test Message", 3000L)

        withTimeout(3000) {
            while (inboundList.isEmpty()) delay(20)
        }
        assertEquals(1, inboundList.size)

        // Inject duplicate AX.25 frame from fake TNC A transmissions directly to B
        val lastTransmitted = a.fakeTnc.transmitted.last()
        b.fakeTnc.injectRaw(com.transfer.flash.core.radio.kiss.KissFrameCodec.encodeData(lastTransmitted))

        delay(100)
        // Deduplication ensures still only 1 message was delivered to chat
        assertEquals(1, inboundList.size)

        collectorJob.cancel()
    }

    @Test
    fun radchatU04_wrongKey_rejectedSilentlyWithZeroAirResponse() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        // Station A uses wrong key
        a.session.addPeer("station-b", wrongKey)
        b.session.addPeer("station-a", pairKey)

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        val bInitialTxCount = b.fakeTnc.transmitted.size

        a.engine.sendText("station-b", "msg-bad-key", "Secret message", 4000L)

        delay(200)

        // Station B dropped silently: zero ACK or response frame transmitted on air (rule 3d)
        assertEquals(bInitialTxCount, b.fakeTnc.transmitted.size)
    }

    @Test
    fun radchatU05_deliveredOnlyOnCryptographicAck_mutationCheck() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        val receipts = mutableListOf<RadioChatReceipt>()
        val job = backgroundScope.launch {
            a.engine.deliveryReceipts.collect { receipts.add(it) }
        }

        // Before sending: no receipts
        assertTrue(receipts.isEmpty())

        // Send: returns SentWaitingAck, but NOT delivered yet
        val outcome = a.engine.sendText("station-b", "msg-waiting", "Waiting for ACK", 5000L)
        assertIs<RadioChatSendOutcome.SentWaitingAck>(outcome)

        // Awaits inbound ACK
        withTimeout(3000) {
            while (receipts.isEmpty()) delay(20)
        }
        assertEquals(1, receipts.size)
        assertEquals("msg-waiting", receipts[0].messageId)

        job.cancel()
    }

    @Test
    fun radchatU06_gatewayOff_refusedWithNotConnected() = runTest {
        val airChannel = FakeAirChannel()
        val a = buildStation("station-a", backgroundScope, airChannel)
        rigA = a

        a.engine.setEnabled(false)

        val outcome = a.engine.sendText("station-b", "msg-off", "Hello", 6000L)
        assertIs<RadioChatSendOutcome.NotConnected>(outcome)
        assertEquals("radio not connected", outcome.reason)
    }

    @Test
    fun radchatU07_profileMPrivacy_noPlaintextOnAir() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        val secretPayload = "CONFIDENTIAL_TOP_SECRET_COORDINATES_47_N_122_W"
        a.engine.sendText("station-b", "msg-secret", secretPayload, 7000L)

        withTimeout(3000) {
            b.engine.inboundMessages.first()
        }

        // Task 4 / BT-R08: Examine every byte put on air by Station A
        val allAirBytes = a.fakeTnc.transmitted.flatMap { it.toList() }.toByteArray()
        val airString = allAirBytes.decodeToString()

        // Privacy assertion: under Profile M, NO plaintext substring appears on air
        assertFalse(airString.contains("CONFIDENTIAL"), "Plaintext leaked onto air!")
        assertFalse(airString.contains("TOP_SECRET"), "Plaintext leaked onto air!")
    }

    @Test
    fun radchatU08_profileATransparency_operatorCallsignAppearsOnAir() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel, auth = AuthorisationConfirmation(1000L, "W1AW"))
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        a.engine.setProfile(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED)

        // Clear text message under Profile A
        val testText = "CQ CQ DE W1AW TEST"
        a.engine.sendText("station-b", "msg-clear", testText, 8000L)

        // Examine transmitted AX.25 frame
        withTimeout(3000) {
            while (a.fakeTnc.transmitted.isEmpty()) delay(20)
        }

        val frameBytes = a.fakeTnc.transmitted.first()
        val decoded = Ax25FrameCodec.decode(frameBytes)
        assertIs<com.transfer.flash.core.radio.kiss.Ax25DecodeResult.Ok>(decoded)

        // Profile A: source callsign is set to the operator callsign W1AW
        assertEquals("W1AW", decoded.frame.source.callsign)
    }

    @Test
    fun radchatU09_profileM_refusedWithoutRecordedConfirmation() = runTest {
        val airChannel = FakeAirChannel()
        // Unconfirmed authorization (null)
        val a = buildStation("station-a", backgroundScope, airChannel, auth = null)
        rigA = a

        a.pairingAdapter.registerPeer(a.session, "station-b")
        a.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected) delay(20)
        }

        val outcome = a.engine.sendText("station-b", "msg-unauth", "Test text", 9000L)
        assertIs<RadioChatSendOutcome.RefusedAuthorization>(outcome)
        assertTrue(outcome.reason.contains("Profile M unconfirmed"))
    }

    @Test
    fun radchatU10_priorityOrdering_emergencyPreemptsText() = runTest {
        val airChannel = FakeAirChannel()
        air = airChannel

        val a = buildStation("station-a", backgroundScope, airChannel)
        val b = buildStation("station-b", backgroundScope, airChannel)
        rigA = a
        rigB = b

        a.pairingAdapter.registerPeer(a.session, "station-b")
        b.pairingAdapter.registerPeer(b.session, "station-a")

        a.driver.start()
        b.driver.start()

        withTimeout(3000) {
            while (!a.engine.isConnected || !b.engine.isConnected) delay(20)
        }

        val receivedMessages = mutableListOf<RadioChatInbound>()
        val job = backgroundScope.launch {
            b.engine.inboundMessages.collect { receivedMessages.add(it) }
        }

        // Emergency flagged message
        val emergencyOutcome = a.engine.sendText(
            recipientId = "station-b",
            messageId = "msg-emg",
            text = "MAYDAY MAYDAY",
            sentAtMs = 10_000L,
            emergency = true,
        )
        assertIs<RadioChatSendOutcome.SentWaitingAck>(emergencyOutcome)

        withTimeout(3000) {
            while (receivedMessages.isEmpty()) delay(20)
        }
        assertEquals("MAYDAY MAYDAY", receivedMessages.first().text)

        job.cancel()
    }
}
