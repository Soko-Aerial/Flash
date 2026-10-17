package com.transfer.flash.desktop.radio

import com.transfer.flash.core.messaging.radio.RadioGatewayConnectionStatus
import com.transfer.flash.core.messaging.radio.RadioSendResult
import com.transfer.flash.core.radio.chat.RadioChatEngine
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.InMemoryRadioAuthorisationStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopRadioOutboxAdapterTest {

    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { (it + 1).toByte() }
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun testAdapterStatusAndReachability() = runBlocking {
        val (hostLink, tncLink) = inMemoryLinkPair("desktop-test")
        val air = FakeAirChannel()
        val fakeTnc = FakeTnc(hostSide = tncLink, air = air, scope = testScope)
        val driver = KissTncDriver(
            scope = testScope,
            openLink = { hostLink },
            config = KissTncConfig(reconnectBaseMs = 100, reconnectCapMs = 500, readTimeoutMs = 50),
            pacing = TxPacingPolicy.NONE,
        )
        val persistence = InMemoryRadioStatePersistence()
        val store = PersistentRadioStore(persistence)
        val session = RadioSession("desktop-test", crypto, store, store)
        val trustProvider = object : RadioPeerTrustProvider {
            override fun isPeerTrusted(peerId: String): Boolean = peerId == "peer-radio"
            override fun getPairingSessionKey(peerId: String): ByteArray? = if (peerId == "peer-radio") pairKey else null
            override fun getTrustedPeerIds(): Set<String> = setOf("peer-radio")
        }
        val pairingAdapter = RadioPairingAdapter("desktop-test", trustProvider, crypto)
        val authStore = InMemoryRadioAuthorisationStore(AuthorisationConfirmation(1000L, "W1AW"))

        val engine = RadioChatEngine(
            localDeviceId = "desktop-test",
            scope = testScope,
            driver = driver,
            session = session,
            pairingAdapter = pairingAdapter,
            authorisationStore = authStore,
        )

        val adapter = DesktopRadioOutboxAdapter(engine, testScope)

        assertTrue(adapter.isGatewayEnabled)
        assertFalse(adapter.isConnected)

        // Peer registered via pairing adapter is reachable
        pairingAdapter.registerPeer(session, "peer-radio")
        assertTrue(adapter.isRadioReachable("peer-radio"))
        assertFalse(adapter.isRadioReachable("peer-unknown"))

        // Send when link is down -> NotConnected
        val outcome = adapter.sendTextMessage("peer-radio", "msg-1", "Hello", 1000L, false)
        assertIs<RadioSendResult.NotConnected>(outcome)

        // Disable gateway -> isGatewayEnabled is false
        engine.setEnabled(false)
        assertFalse(adapter.isGatewayEnabled)

        engine.close()
        driver.stop()
        hostLink.close()
        tncLink.close()
    }
}
