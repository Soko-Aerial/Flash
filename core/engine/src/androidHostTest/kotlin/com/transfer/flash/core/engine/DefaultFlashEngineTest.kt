package com.transfer.flash.core.engine

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.SampleFlashChatRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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

    @Test
    fun `DefaultFlashEngine binds all subsystem delegates correctly`() {
        val chatRepo = SampleFlashChatRepository()
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
}
