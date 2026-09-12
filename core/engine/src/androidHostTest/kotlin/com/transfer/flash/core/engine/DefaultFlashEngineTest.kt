@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.FlashCallMedia
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.EmptyFlashChatRepository
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the facade itself: the subsystem delegate bindings, and the two optional
 * seams (`ptt` is covered by `:core:ptt`'s own suite for the engine internals; here it is only the
 * attach/detach behaviour on `DefaultFlashEngine`) plus calling, which is pinned end-to-end through
 * a stub because the real one needs WebRTC.
 */
class DefaultFlashEngineTest {

    private class FakeDiscovery : FlashDiscovery {
        override val state: StateFlow<FlashDiscoveryState> = MutableStateFlow(FlashDiscoveryState())
        override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> = MutableStateFlow(emptyList())
        override suspend fun startDiscovery(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopDiscovery(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopAdvertising(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopAll(): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    private class FakeNetwork : FlashNetwork {
        override val networkState: StateFlow<FlashNetworkState> = MutableStateFlow(FlashNetworkState())
        override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = MutableStateFlow(emptyMap())
        override val connectionHealth: StateFlow<FlashConnectionHealth> = MutableStateFlow(FlashConnectionHealth.Offline)
        override suspend fun start(listenPort: Int): FlashResult<Int> = FlashResult.Success(8080)
        override suspend fun stop(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun connect(device: com.transfer.flash.core.common.model.FlashDevice): FlashResult<FlashSession> =
            FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.NetworkUnavailable())
        override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> =
            FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.NetworkUnavailable())
        override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    private class FakeTrustStore : FlashTrustStore {
        private val peers = mutableMapOf<FlashDeviceId, String>()
        override fun isTrusted(deviceId: FlashDeviceId): Boolean = peers.containsKey(deviceId)
        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
            peers[deviceId] = friendlyName
            return FlashResult.Success(Unit)
        }
        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
            peers.remove(deviceId)
            return FlashResult.Success(Unit)
        }
        override fun getTrustedPeers(): Map<FlashDeviceId, String> = peers
    }

    private class FakeTransferRepo : FlashTransferRepository {
        override val activeTransfers: StateFlow<List<FlashTransfer>> = MutableStateFlow(emptyList())
        override suspend fun sendFile(
            targetDevice: com.transfer.flash.core.common.model.FlashDevice,
            fileUri: String,
            displayName: String,
            fileSize: Long,
        ): FlashResult<FlashTransferId> = FlashResult.Success(FlashTransferId("test-id"))
        override suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    /**
     * Stub calling engine: records what the facade routed to it and answers [consume] for inbound
     * frames. `media` stays null — nothing here touches WebRTC, which is the point: the facade's
     * routing is a text-frame contract, so a stub can pin all of it on the host JVM.
     */
    private class FakeCalling(
        private val consume: (peerId: String, text: String) -> Boolean = { _, _ -> true },
    ) : FlashCalling {
        override val activeCall: StateFlow<FlashCallUiState?> = MutableStateFlow(null)
        override val media: FlashCallMedia? = null

        /** Every frame the facade dispatched, in order, with the authenticated peer it came from. */
        val inbound: MutableList<Pair<String, String>> = mutableListOf()

        /** Signaling-lifecycle notifications in the order the facade raised them. */
        val signaling: MutableList<String> = mutableListOf()

        override suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean = false
        override suspend fun accept(): Boolean = false
        override suspend fun decline(): Boolean = false
        override suspend fun hangUp(): Boolean = false
        override fun toggleMute(): Boolean = false
        override fun toggleCamera(): Boolean = false
        override suspend fun switchCamera(): Unit = Unit
        override fun setSpeaker(on: Boolean): Unit = Unit

        override suspend fun onInboundText(peerId: String, text: String): Boolean {
            inbound += peerId to text
            return consume(peerId, text)
        }

        override fun onSignalingLost(peerId: String) {
            signaling += "lost:$peerId"
        }

        override fun onSignalingRestored(peerId: String) {
            signaling += "restored:$peerId"
        }
    }

    /**
     * A hand-assembled engine: no `pttFactory`, no calling — the shape a `DefaultFlashEngine`
     * consumer gets. The calling tests use it because both seams must answer honestly on an engine
     * `Flash.create` did not build.
     */
    private fun handAssembledEngine(): FlashEngine = DefaultFlashEngine(
        chats = EmptyFlashChatRepository,
        transfers = FakeTransferRepo(),
        discovery = FakeDiscovery(),
        network = FakeNetwork(),
        trustStore = FakeTrustStore(),
        settings = FlashSettingsDataStore(produceFile = { java.io.File.createTempFile("test", "preferences_pb") }),
    )

    private val inviteFrame: String =
        "FLASH_CALL action=invite from=device-a callId=call-1 video=false"

    @Test
    fun `DefaultFlashEngine binds all subsystem delegates correctly`() {
        // Any FlashChatRepository proves the delegate binding; this test never reads content from
        // it. EmptyFlashChatRepository rather than the sample one because the sample repository is
        // test-only in :core:messaging and not visible from here (ERROR-034).
        val chatRepo = EmptyFlashChatRepository
        val transferRepo = FakeTransferRepo()
        val discovery = FakeDiscovery()
        val network = FakeNetwork()
        val trustStore = FakeTrustStore()
        val settings = FlashSettingsDataStore(produceFile = { java.io.File.createTempFile("test", "preferences_pb") })

        val engine: FlashEngine = DefaultFlashEngine(
            chats = chatRepo,
            transfers = transferRepo,
            discovery = discovery,
            network = network,
            trustStore = trustStore,
            settings = settings,
        )

        assertNotNull(engine.chats)
        assertNotNull(engine.transfers)
        assertNotNull(engine.discovery)
        assertNotNull(engine.network)
        assertNotNull(engine.trustStore)
        assertNotNull(engine.settings)

        assertEquals(chatRepo, engine.chats)
        assertEquals(transferRepo, engine.transfers)
        assertEquals(discovery, engine.discovery)
        assertEquals(network, engine.network)
        assertEquals(trustStore, engine.trustStore)
    }

    @Test
    fun `calling is unattached until a host attaches an engine, and call frames are then dropped`() = runTest {
        val engine = handAssembledEngine()

        assertNull(engine.calls)

        // The recognition half of the routing contract: this is what makes a dropped call frame a
        // dropped call frame rather than noise for the chat/transfer/PTT parsers. `Flash.create`
        // runs it on every inbound text frame; nothing is attached, so nothing consumes it.
        assertTrue(isCallFrameText(inviteFrame))
        assertFalse(engine.onInboundCallText("device-a", inviteFrame))

        // Signaling lifecycle notifications are accepted (not thrown) while nothing is attached —
        // the session collector in Flash.kt calls these on every session edge, calling or not.
        engine.onCallSignalingLost("device-a")
        engine.onCallSignalingRestored("device-a")

        engine.close()
    }

    @Test
    fun `an attached engine receives call frames and its consumption verdict is the facade's`() = runTest {
        val engine = handAssembledEngine()
        // Consumes invites, ignores everything else — exactly the real coordinator's split between
        // "a frame for a call I own" and "not a call frame at all".
        val calling = FakeCalling { _, text -> FlashTextFraming.parseFields(text, "FLASH_CALL") != null }
        engine.attachCalling(calling)

        assertSame(calling, engine.calls)

        assertTrue(engine.onInboundCallText("device-a", inviteFrame))
        assertFalse(engine.onInboundCallText("device-a", "FLASH_MSG localId=1 text=hi"))
        assertEquals(
            listOf("device-a" to inviteFrame, "device-a" to "FLASH_MSG localId=1 text=hi"),
            calling.inbound,
        )

        engine.close()
    }

    @Test
    fun `a second attach is ignored and detach stops routing without shutting the engine down`() = runTest {
        val engine = handAssembledEngine()
        val first = FakeCalling()
        val second = FakeCalling()
        engine.attachCalling(first)

        engine.attachCalling(second)
        assertSame("the first attached engine owns the seam (mirrors attachPtt)", first, engine.calls)

        engine.detachCalling()
        assertNull(engine.calls)
        assertFalse(engine.onInboundCallText("device-a", inviteFrame))
        // Detach is not hang-up: FlashCalling exposes no shutdown, and the media/foreground service
        // are the host's. Only routing stops.
        assertTrue(first.inbound.isEmpty())

        // close() detaches too, so a host that never calls detachCalling() leaks no routing.
        engine.attachCalling(second)
        assertEquals(second, engine.calls)
        engine.close()
        assertNull(engine.calls)
    }

    @Test
    fun `signaling lifecycle reaches the attached engine in order, and only while attached`() {
        val engine = handAssembledEngine()
        val calling = FakeCalling()
        engine.attachCalling(calling)

        engine.onCallSignalingLost("device-a")
        engine.onCallSignalingRestored("device-a")
        engine.onCallSignalingLost("device-b")

        engine.detachCalling()
        engine.onCallSignalingLost("device-c")

        assertEquals(listOf("lost:device-a", "restored:device-a", "lost:device-b"), calling.signaling)
    }

    @Test
    fun `only the calling family is recognized as a call frame`() {
        assertTrue(isCallFrameText(inviteFrame))
        assertTrue(isCallFrameText("FLASH_CALL action=ginvite from=device-a groupId=g1"))

        // Each other family gates on its own first token, so a FLASH_CALL frame cannot be parsed by
        // any of them: mutating a prefix is the same check those parsers make.
        listOf("FLASH_MSG", "FLASH_RCPT", "FLASH_READ", "FLASH_REACT", "FLASH_TYPING", "FLASH_XFER")
            .forEach { assertNull(FlashTextFraming.parseFields(inviteFrame, it)) }
        assertFalse(isCallFrameText("FLASH_GROUP action=state groupId=g1"))
        assertFalse(isCallFrameText("FLASH_PTT eventId=e1 from=device-a"))
        assertFalse(isCallFrameText("FLASH_MSG localId=1 text=FLASH_CALL"))
        assertFalse(isCallFrameText(""))
    }
}
