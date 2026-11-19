package com.transfer.flash.core.radio.chat

import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.InMemoryRadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.driver.InMemoryRadioStatePersistence
import com.transfer.flash.core.radio.driver.KissTncConfig
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.PersistentRadioStore
import com.transfer.flash.core.radio.driver.TxPacingPolicy
import com.transfer.flash.core.radio.sim.FakeAirChannel
import com.transfer.flash.core.radio.sim.FakeTnc
import com.transfer.flash.core.radio.sim.inMemoryLinkPair
import com.transfer.flash.core.radio.wire.JdkRadioCrypto
import com.transfer.flash.core.radio.wire.RadioPairingAdapter
import com.transfer.flash.core.radio.wire.RadioPeerTrustProvider
import com.transfer.flash.core.radio.wire.RadioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** RADWIRE-U28..U30: the host that binds the chat engine to whichever driver the gateway currently has (ERROR-703). */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioChatHostTest {

    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { (it + 1).toByte() }
    private val trust = object : RadioPeerTrustProvider {
        override fun isPeerTrusted(peerId: String): Boolean = true
        override fun getPairingSessionKey(peerId: String): ByteArray? = pairKey
        override fun getTrustedPeerIds(): Set<String> = setOf("station-a", "station-b")
    }
    private val auth = AuthorisationConfirmation(confirmedAtEpochMs = 1_700_000_000_000L, operatorCallsign = "W1AW")

    private fun newDriver(id: String, scope: CoroutineScope, air: FakeAirChannel): KissTncDriver {
        val (hostLink, tncLink) = inMemoryLinkPair(id)
        FakeTnc(hostSide = tncLink, air = air, scope = scope)
        return KissTncDriver(
            scope = scope,
            openLink = { hostLink },
            config = KissTncConfig(reconnectBaseMs = 100, reconnectCapMs = 500, readTimeoutMs = 50),
            pacing = TxPacingPolicy.NONE,
        )
    }

    private fun newEngine(id: String, scope: CoroutineScope, driver: KissTncDriver): RadioChatEngine {
        val store = PersistentRadioStore(InMemoryRadioStatePersistence())
        val session = RadioSession(localId = id, crypto = crypto, replayStore = store, counterStore = store)
        return RadioChatEngine(
            localDeviceId = id,
            scope = scope,
            driver = driver,
            session = session,
            pairingAdapter = RadioPairingAdapter(id, trust, crypto),
            authorisationStore = InMemoryRadioAuthorisationStore(auth),
        )
    }

    @Test
    fun radwireU28_engineFollowsTheDriverAndTheGatewaySettings() = runTest {
        val air = FakeAirChannel()
        val drivers = MutableStateFlow<KissTncDriver?>(null)
        val settings = MutableStateFlow(RadioChatSettings(enabled = true, profile = RadioSecurityProfile.PROFILE_M_ENCRYPTED))
        val created = mutableListOf<RadioChatEngine>()
        val host = RadioChatHost(backgroundScope, drivers, settings) { sc, d ->
            newEngine("station-b", sc, d).also { created += it }
        }
        runCurrent()
        assertNull(host.engine.value, "no driver, no engine")
        assertEquals(RadioChatStatus.DISCONNECTED, host.status.value)

        val d1 = newDriver("b1", backgroundScope, air)
        drivers.value = d1
        runCurrent()
        val e1 = assertNotNull(host.engine.value)
        assertEquals(1, created.size)
        assertSame(created[0], e1)
        assertEquals(RadioSecurityProfile.PROFILE_M_ENCRYPTED, e1.currentProfile.value)

        settings.value = RadioChatSettings(enabled = false, profile = RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED)
        runCurrent()
        assertFalse(e1.isEnabled.value, "the engine follows the gateway switch")
        assertEquals(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, e1.currentProfile.value)
        assertEquals(RadioChatStatus.OFF, host.status.value)

        // A reconnect gives the gateway a new driver: a new engine that starts with the CURRENT settings.
        val d2 = newDriver("b2", backgroundScope, air)
        drivers.value = d2
        runCurrent()
        val e2 = assertNotNull(host.engine.value)
        assertEquals(2, created.size)
        assertFalse(e2 === e1)
        assertFalse(e2.isEnabled.value)
        assertEquals(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, e2.currentProfile.value)

        drivers.value = null
        runCurrent()
        assertNull(host.engine.value, "driver gone, engine gone: a send is refused, not queued for a dead link")
        host.close()
    }

    @Test
    fun radwireU29_aPairedPeerWeHaveNeverSentToIsHeard() = runTest {
        val air = FakeAirChannel()
        val driverA = newDriver("a", backgroundScope, air)
        val engineA = newEngine("station-a", backgroundScope, driverA)
        val drivers = MutableStateFlow<KissTncDriver?>(null)
        val settings = MutableStateFlow(RadioChatSettings(true, RadioSecurityProfile.PROFILE_M_ENCRYPTED))
        val host = RadioChatHost(backgroundScope, drivers, settings) { sc, d -> newEngine("station-b", sc, d) }
        val driverB = newDriver("b", backgroundScope, air)
        drivers.value = driverB
        runCurrent()

        driverA.start()
        driverB.start()
        withTimeout(3000) {
            while (!engineA.isConnected || host.engine.value?.isConnected != true) delay(20)
        }

        // B never called sendText or registered A by hand: only the peer sync can let A's frame through.
        assertTrue(engineA.isPeerRadioReachable("station-b"))
        // Subscribe first: the flows replay nothing.
        val inbound = async { host.inboundMessages.first() }
        val receipts = async { engineA.deliveryReceipts.first() }
        runCurrent()
        val sent = engineA.sendText("station-b", "msg-1", "hello from A", sentAtMs = 1000L)
        assertIs<RadioChatSendOutcome.SentWaitingAck>(sent)

        val received = withTimeout(3000) { inbound.await() }
        assertEquals("station-a", received.senderDeviceId)
        assertEquals("hello from A", received.text)
        val receipt = withTimeout(3000) { receipts.await() }
        assertEquals("msg-1", receipt.messageId)
        host.close()
    }

    @Test
    fun `host preserves retry frames and pending ACKs across driver swap so reconnect never creates duplicate bubbles`() = runTest {
        val air = FakeAirChannel()
        val storeA = PersistentRadioStore(InMemoryRadioStatePersistence())
        val storeB = PersistentRadioStore(InMemoryRadioStatePersistence())

        fun buildSession(id: String, store: PersistentRadioStore) =
            RadioSession(localId = id, crypto = crypto, replayStore = store, counterStore = store)

        val settings = MutableStateFlow(RadioChatSettings(true, RadioSecurityProfile.PROFILE_M_ENCRYPTED))
        val driversA = MutableStateFlow<KissTncDriver?>(null)
        val hostA = RadioChatHost(backgroundScope, driversA, settings) { sc, d ->
            RadioChatEngine(
                localDeviceId = "station-a",
                scope = sc,
                driver = d,
                session = buildSession("station-a", storeA),
                pairingAdapter = RadioPairingAdapter("station-a", trust, crypto),
                authorisationStore = InMemoryRadioAuthorisationStore(auth),
            )
        }
        val driverB = newDriver("b", backgroundScope, air)
        val engineB = RadioChatEngine(
            localDeviceId = "station-b",
            scope = backgroundScope,
            driver = driverB,
            session = buildSession("station-b", storeB),
            pairingAdapter = RadioPairingAdapter("station-b", trust, crypto),
            authorisationStore = InMemoryRadioAuthorisationStore(auth),
        )
        val driverA1 = newDriver("a1", backgroundScope, air)
        driversA.value = driverA1
        driverA1.start()
        driverB.start()
        runCurrent()
        withTimeout(3000) {
            while (hostA.engine.value?.isConnected != true || !engineB.isConnected) delay(20)
        }

        val receivedOnB = mutableListOf<RadioChatInbound>()
        val rxJob = backgroundScope.launch { engineB.inboundMessages.collect { receivedOnB += it } }
        val receiptsOnA = mutableListOf<RadioChatReceipt>()
        val receiptJob = backgroundScope.launch { hostA.deliveryReceipts.collect { receiptsOnA += it } }
        runCurrent()

        val first = hostA.engine.value!!.sendText("station-b", "msg-swap-1", "hello swap", sentAtMs = 1000L)
        val firstCounter = assertIs<RadioChatSendOutcome.SentWaitingAck>(first).firstCounter
        withTimeout(3000) { while (receivedOnB.isEmpty()) delay(20) }

        // Transient disconnect drops driver -> null -> new driver2 (simulating reconnect).
        driversA.value = null
        driverA1.stop()
        runCurrent()
        val driverA2 = newDriver("a2", backgroundScope, air)
        driversA.value = driverA2
        driverA2.start()
        runCurrent()
        withTimeout(3000) {
            while (hostA.engine.value?.isConnected != true) delay(20)
        }

        // Retrying the same messageId after swap() must reuse the original counter and not produce a second bubble on B.
        val retry = hostA.engine.value!!.sendText("station-b", "msg-swap-1", "hello swap", sentAtMs = 1000L)
        val retryCounter = assertIs<RadioChatSendOutcome.SentWaitingAck>(retry).firstCounter
        assertEquals(firstCounter, retryCounter, "retry after swap() must reuse original counter")

        withTimeout(3000) { while (receiptsOnA.isEmpty()) delay(20) }
        delay(100)
        assertEquals(1, receivedOnB.size, "B must only emit one message bubble across A's reconnect retry")
        assertEquals("msg-swap-1", receiptsOnA.first().messageId)

        rxJob.cancel()
        receiptJob.cancel()
        engineB.close()
        hostA.close()
    }
}
