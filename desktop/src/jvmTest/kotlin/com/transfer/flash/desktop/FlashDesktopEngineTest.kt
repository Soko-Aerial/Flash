@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.FlashDesktop
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.core.engine.FlashReadiness
import com.transfer.flash.core.engine.awaitReady
import com.transfer.flash.core.messaging.EmptyFlashChatRepository
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DesktopEngine] as the unified [FlashEngine] (C7.0 / ADR-010) and [FlashDesktop.create] as its factory.
 *
 * Every engine here lives in a temporary state directory with the pass-through identity vault and a fake microphone,
 * including the ones built through [FlashDesktop.create] (via [DesktopEngine.factoryOptions]): an earlier version of
 * this class created engines on the developer's real `~/.flash` and DPAPI vault. The registry is reset around every
 * test, so the order of tests does not matter.
 *
 * What this cannot prove: behaviour on a second machine, and what the Compose shell draws. Those are device checks.
 */
class FlashDesktopEngineTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<FlashEngine>()
    private val helperScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        FlashDesktop.resetForTesting()
        DesktopEngine.registerFactory()
    }

    @AfterTest
    fun tearDown() {
        DesktopEngine.factoryOptions = null
        engines.forEach { runCatching { it.close() } }
        engines.clear()
        helperScopes.forEach { it.cancel() }
        helperScopes.clear()
        FlashDesktop.resetForTesting()
        DesktopEngine.registerFactory()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun dir(label: String): File = Files.createTempDirectory("flash-fde-$label").toFile().also { tempDirs += it }

    /** An unstarted engine on [state]; closed by [tearDown]. */
    private fun engine(
        state: File = dir("state"),
        received: File = dir("received"),
        displayName: String? = null,
        autoAccept: Boolean = false,
    ): DesktopEngine = DesktopEngine(
        receivedRoot = received,
        stateDir = state,
        identityVault = testIdentityVault(),
        pttAudio = FakePttAudio(),
        displayName = displayName,
        autoAcceptIncoming = autoAccept,
    ).also { engines += it }

    private fun useFactoryOptions(state: File) {
        DesktopEngine.factoryOptions = DesktopEngine.Companion.FactoryOptions(
            stateDir = state,
            identityVault = testIdentityVault(),
            pttAudio = FakePttAudio(),
        )
    }

    private fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Default).also { helperScopes += it }

    private suspend fun await(timeoutMs: Long = WAIT_MS, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    // ---------------------------------------------------------------------------------------------------------------
    // The facade contract
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a not yet started engine exposes an empty but live facade with its own identity`() {
        val desktop = engine()
        val facade: FlashEngine = desktop

        assertTrue(facade.transfers.activeTransfers.value.isEmpty())
        assertTrue(facade.network.activeSessions.value.isEmpty())
        assertTrue(facade.discovery.discoveredEndpoints.value.isEmpty())
        assertSame(desktop.trust, facade.trustStore)
        assertSame(EmptyFlashChatRepository, facade.chats)
        assertNull(facade.calls)
        assertNull(facade.ptt)
        assertNull(facade.swarm)
        assertTrue(desktop.localDeviceId.isNotEmpty())
        assertTrue(desktop.localFriendlyName.isNotEmpty())
        assertFalse(desktop.ready.value)
        assertNull(desktop.startError.value)
    }

    @Test
    fun `before the boot every operation that needs the engine fails, only idempotent stops succeed`() = runBlocking<Unit> {
        val facade: FlashEngine = engine()
        val peer = FlashDevice(FlashDeviceId("peer"), "Peer", FlashTransportType.LAN)

        // Previously: Success(Unit), Success(0) (a port that does not exist), Success(Unit) ...
        assertIs<FlashResult.Failure>(facade.transfers.acceptIncoming(FlashTransferId("t")))
        assertIs<FlashResult.Failure>(facade.transfers.declineIncoming(FlashTransferId("t")))
        assertIs<FlashResult.Failure>(facade.transfers.sendFile(peer, "x", "x", 1L))
        assertIs<FlashResult.Failure>(facade.transfers.pauseTransfer(FlashTransferId("t")))
        assertIs<FlashResult.Failure>(facade.transfers.resumeTransfer(FlashTransferId("t")))
        assertIs<FlashResult.Failure>(facade.transfers.cancelTransfer(FlashTransferId("t")))
        assertIs<FlashResult.Failure>(facade.network.start(0))
        assertIs<FlashResult.Failure>(facade.network.connectManual("127.0.0.1", 1))
        assertIs<FlashResult.Failure>(facade.network.disconnect(FlashDeviceId("peer")))
        assertIs<FlashResult.Failure>(facade.discovery.startDiscovery())
        assertIs<FlashResult.Failure>(facade.discovery.startAdvertising(1))

        assertIs<FlashResult.Success<Unit>>(facade.network.stop())
        assertIs<FlashResult.Success<Unit>>(facade.discovery.stopDiscovery())
        assertIs<FlashResult.Success<Unit>>(facade.discovery.stopAdvertising())
        assertIs<FlashResult.Success<Unit>>(facade.discovery.stopAll())
    }

    @Test
    fun `references captured before the boot start working after it and their flows re-emit from the real source`() =
        runBlocking<Unit> {
            val desktop = engine()
            val earlyTransfers = desktop.transfers
            val earlyNetwork = desktop.network
            val earlyDiscovery = desktop.discovery

            val seen = CopyOnWriteArrayList<List<FlashTransfer>>()
            scope().launch { earlyTransfers.activeTransfers.collect { seen += it } }
            assertIs<FlashResult.Failure>(earlyNetwork.start(0), "no port before the boot")

            desktop.start()
            desktop.awaitReady()
            assertTrue(desktop.ready.value)

            // The captured network object now talks to the real transport: a started server answers with its port.
            val port = desktop.boundPort
            assertTrue(port > 0)
            assertEquals(FlashResult.Success(port), earlyNetwork.start(0), "was Success(0) from the Empty singleton")
            assertEquals(desktop.network.networkState.value, earlyNetwork.networkState.value)
            assertEquals(desktop.discovery.state.value, earlyDiscovery.state.value)

            // A row created in the real repository reaches the captured reference's value and its collector.
            desktop.transfers.onIncomingOffered("t-swap", "f-swap", "a.bin", 10L, "Peer", "peer-id")
            assertTrue(await { earlyTransfers.activeTransfers.value.any { it.id.value == "t-swap" } }, "value follows")
            assertTrue(await { seen.any { rows -> rows.any { it.id.value == "t-swap" } } }, "a pre-boot collector re-emits")
        }

    @Test
    fun `awaitReady returns once booted and reports a close that came first`() = runBlocking<Unit> {
        val booted = engine()
        booted.start()
        booted.awaitReady()
        assertTrue(booted.ready.value)

        val closedEarly = engine()
        closedEarly.close()
        val failure = assertFailsWith<IllegalStateException> { closedEarly.awaitReady() }
        assertTrue(failure.cause is IllegalStateException)
        assertTrue(closedEarly is FlashReadiness)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lifecycle: close(), the boot race, start after close
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `close is idempotent, resets the facade and frees the state directory for a fresh engine`() = runBlocking<Unit> {
        val state = dir("reuse")
        val first = engine(state = state)
        first.start()
        first.awaitReady()
        val port = first.boundPort

        first.close()
        first.close()
        first.stop()

        assertFalse(first.ready.value)
        assertSame(EmptyFlashChatRepository, first.chats)
        assertNull(first.calls)
        assertNull(first.ptt)
        assertIs<FlashResult.Failure>(first.network.start(0), "a closed engine's network is the Empty stand-in again")
        assertTrue(first.network.activeSessions.value.isEmpty())
        assertTrue(canBind(port), "the WebSocket port is released by close()")

        val second = engine(state = state)
        second.start()
        second.awaitReady()
        assertTrue(second.ready.value, "a fresh engine boots on the same state dir after close")
        assertEquals(first.localDeviceId, second.localDeviceId)
    }

    @Test
    fun `a close that lands right at the start of the boot leaves ready false and nothing bound`() = runBlocking<Unit> {
        val desktop = engine()
        desktop.bootStageHook = { stage -> if (stage == "assemble entered") desktop.close() }

        desktop.start()
        desktop.bootJob!!.join()

        assertFalse(desktop.ready.value)
        assertNotNull(desktop.startError.value)
        assertEquals(0, desktop.boundPort, "the transport was never bound")
        delay(300)
        assertFalse(desktop.ready.value, "ready never flips on a closed engine")
    }

    @Test
    fun `a close that lands after the server is bound tears it down and ready never flips`() = runBlocking<Unit> {
        val desktop = engine()
        desktop.bootStageHook = { stage -> if (stage.startsWith("ws server bound")) desktop.close() }

        desktop.start()
        desktop.bootJob!!.join()

        val port = desktop.boundPort
        assertTrue(port > 0, "the hook ran after the bind")
        assertFalse(desktop.ready.value, "was set true by the boot's tail on a closed engine")
        assertNotNull(desktop.startError.value)
        assertTrue(canBind(port), "the port the boot bound is released, not leaked")
        assertTrue(desktop.network.activeSessions.value.isEmpty())
        assertSame(EmptyFlashChatRepository, desktop.chats, "the chat database was closed and dropped")
    }

    @Test
    fun `a close that lands before the server binds does not leave the late bind behind`() = runBlocking<Unit> {
        // The shutdown's own release finds a transport that is not listening yet; the boot then binds it anyway
        // (assemble is blocking code, scope.cancel() cannot stop it). Only the boot tail's second release frees it.
        val desktop = engine()
        desktop.bootStageHook = { stage -> if (stage == "before ws bind") desktop.close() }

        desktop.start()
        desktop.bootJob!!.join()

        val port = desktop.boundPort
        assertTrue(port > 0, "the boot did bind after the close")
        assertFalse(desktop.ready.value)
        assertTrue(canBind(port), "the late bind is released by the boot's tail")
    }

    @Test
    fun `starting a closed engine fails loudly instead of doing nothing`() {
        val neverStarted = engine()
        neverStarted.close()
        val failure = assertFailsWith<IllegalStateException> { neverStarted.start() }
        assertEquals(failure, neverStarted.startError.value)
        assertFalse(neverStarted.ready.value)

        val stopped = engine()
        stopped.start()
        stopped.stop()
        assertFailsWith<IllegalStateException> { stopped.start() }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The inbound-offer gate (AGENTS.md section 19)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `auto accept applies to paired peers only`() {
        // untrusted: never, whatever the switches say (the old expression returned true for the last two)
        assertFalse(shouldAutoAcceptOffer(peerTrusted = false, perKindAutoDownload = false, autoAcceptIncoming = false))
        assertFalse(shouldAutoAcceptOffer(peerTrusted = false, perKindAutoDownload = true, autoAcceptIncoming = false))
        assertFalse(shouldAutoAcceptOffer(peerTrusted = false, perKindAutoDownload = false, autoAcceptIncoming = true))
        assertFalse(shouldAutoAcceptOffer(peerTrusted = false, perKindAutoDownload = true, autoAcceptIncoming = true))
        // trusted: per-kind setting or the config switch
        assertFalse(shouldAutoAcceptOffer(peerTrusted = true, perKindAutoDownload = false, autoAcceptIncoming = false))
        assertTrue(shouldAutoAcceptOffer(peerTrusted = true, perKindAutoDownload = true, autoAcceptIncoming = false))
        assertTrue(shouldAutoAcceptOffer(peerTrusted = true, perKindAutoDownload = false, autoAcceptIncoming = true))
    }

    @Test
    fun `an offer from an unpaired peer waits for the user even with autoAcceptIncoming, a paired peer is accepted`() =
        runBlocking<Unit> {
            val receiver = engine(autoAccept = true)
            val sender = engine()
            receiver.start()
            sender.start()
            receiver.awaitReady()
            sender.awaitReady()
            val file = File(dir("payload"), "hello.bin").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
            val target = FlashDevice(FlashDeviceId(receiver.localDeviceId), "receiver", FlashTransportType.LAN)

            assertIs<FlashResult.Success<*>>(sender.network.connectManual("127.0.0.1", receiver.boundPort))
            assertTrue(await { receiver.network.activeSessions.value.containsKey(FlashDeviceId(sender.localDeviceId)) }, "no session")

            assertIs<FlashResult.Success<*>>(sender.transfers.sendFile(target, file.absolutePath, file.name, file.length()))
            assertTrue(await { receiver.transfers.activeTransfers.value.any { it.fileName == file.name } }, "no offer arrived")
            delay(2_000)
            val unpaired = receiver.transfers.activeTransfers.value.single { it.fileName == file.name }
            assertEquals(FlashTransferState.Offered, unpaired.state, "an unpaired peer's offer must wait for the user")

            // Control: the same switch does accept for a paired peer, so the check above can fail.
            receiver.trust.trustPeer(sender.localDeviceId, "sender")
            val second = File(dir("payload2"), "second.bin").apply { writeBytes(ByteArray(2048) { 1 }) }
            assertIs<FlashResult.Success<*>>(sender.transfers.sendFile(target, second.absolutePath, second.name, second.length()))
            assertTrue(
                await {
                    receiver.transfers.activeTransfers.value.any { it.fileName == second.name && it.state != FlashTransferState.Offered }
                },
                "a paired peer's offer is auto-accepted",
            )
        }

    // ---------------------------------------------------------------------------------------------------------------
    // Display name
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a config display name is an in-memory override and never overwrites the persisted name`() = runBlocking<Unit> {
        val state = dir("names")
        val owner = engine(state = state)
        assertTrue(owner.renameLocalDevice("Chosen By The Owner"))
        owner.close()

        val overridden = engine(state = state, displayName = "  Config Default  ")
        assertEquals("Config Default", overridden.localFriendlyName, "what this run advertises")
        assertEquals("Chosen By The Owner", overridden.identity.friendlyName, "the persisted name is untouched")
        assertTrue(File(state, "identity.properties").readText().contains("Chosen By The Owner"))
        assertFalse(File(state, "identity.properties").readText().contains("Config Default"))

        // The owner's own rename ends the override and is persisted.
        assertTrue(overridden.renameLocalDevice("Renamed Later"))
        assertEquals("Renamed Later", overridden.localFriendlyName)
        assertEquals("Renamed Later", overridden.identity.friendlyName)
        overridden.close()

        assertEquals("Renamed Later", engine(state = state).localFriendlyName, "no override: the persisted name is back")
    }

    @Test
    fun `FlashDesktop create applies the config display name without persisting it`() {
        val state = dir("factory-name")
        useFactoryOptions(state)
        val created = FlashDesktop.create(
            FlashConfig(displayName = "Alpha Workstation", enableResume = false, receivedFilesPath = dir("recv").absolutePath),
        )
        engines += created
        val desktop = created as DesktopEngine

        assertEquals("Alpha Workstation", desktop.localFriendlyName)
        assertNotEquals("Alpha Workstation", desktop.identity.friendlyName)
        assertFalse(File(state, "identity.properties").readText().contains("Alpha Workstation"))
    }

    @Test
    fun `updateFriendlyName persists without blocking on the discovery re-advertise and refuses blank`() {
        val desktop = engine()
        assertFalse(desktop.updateFriendlyName("   "))
        assertTrue(desktop.updateFriendlyName(" Quick Rename "))
        assertEquals("Quick Rename", desktop.localFriendlyName)
        assertEquals("Quick Rename", desktop.identity.friendlyName)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // attach / detach
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `a host swarm is facade only, idempotent for the same instance and exclusive with any other`() {
        val desktop = engine()
        val first = fakeSwarm()
        val other = fakeSwarm()

        desktop.attachSwarm(first)
        desktop.attachSwarm(first)
        assertSame(first, desktop.swarm)
        assertFailsWith<IllegalStateException> { desktop.attachSwarm(other) }
        // The engine must not build a second binding next to a host swarm.
        assertFailsWith<IllegalStateException> { desktop.attachSwarm(com.transfer.flash.core.swarm.api.FlashSwarmConfig()) }
        assertSame(first, desktop.swarm)

        desktop.detachSwarm()
        desktop.detachSwarm()
        assertNull(desktop.swarm)
        desktop.attachSwarm(other)
        assertSame(other, desktop.swarm)
    }

    @Test
    fun `an engine built swarm is not returned when closed and attach after close is refused`() {
        val desktop = engine()
        assertNull(desktop.attachSwarm(com.transfer.flash.core.swarm.api.FlashSwarmConfig()), "no transport before the boot")
        desktop.close()
        assertNull(desktop.attachSwarm(com.transfer.flash.core.swarm.api.FlashSwarmConfig()))
        assertFailsWith<IllegalStateException> { desktop.attachSwarm(fakeSwarm()) }
    }

    @Test
    fun `calling attach is exclusive, the boot keeps a host engine, and detach hangs up the active call`() = runBlocking<Unit> {
        val desktop = engine()
        val hangUps = AtomicInteger()
        val host = fakeCalling(FlashCallState.ACTIVE, hangUps)
        val another = fakeCalling(null, AtomicInteger())

        desktop.attachCalling(host)
        desktop.attachCalling(host)
        assertFailsWith<IllegalStateException> { desktop.attachCalling(another) }

        desktop.start()
        desktop.awaitReady()
        assertSame(host, desktop.calls, "the boot used to overwrite a pre-attached engine with its own coordinator")

        desktop.detachCalling()
        assertEquals(1, hangUps.get(), "the call in progress was hung up")
        assertNull(desktop.calls)
        desktop.detachCalling()
        assertEquals(1, hangUps.get(), "idempotent")
    }

    @Test
    fun `close hangs up the active call of an attached calling engine`() = runBlocking<Unit> {
        val desktop = engine()
        val hangUps = AtomicInteger()
        desktop.attachCalling(fakeCalling(FlashCallState.CONNECTING, hangUps))

        desktop.close()

        assertEquals(1, hangUps.get())
        assertNull(desktop.calls)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The factory
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `FlashDesktop create builds a started DesktopEngine on the configured received folder`() = runBlocking<Unit> {
        val state = dir("factory")
        val received = dir("factory-received")
        useFactoryOptions(state)

        val created = FlashDesktop.create(FlashConfig(enableResume = false, receivedFilesPath = received.absolutePath))
        engines += created
        created.awaitReady()

        val desktop = assertIs<DesktopEngine>(created)
        assertEquals(received.canonicalPath, desktop.canonicalRoot.canonicalPath)
        assertTrue(desktop.ready.value)
    }

    @Test
    fun `a second create on the same state directory fails fast and is released by close`() {
        val state = dir("guard")
        useFactoryOptions(state)
        val config = FlashConfig(enableResume = false, receivedFilesPath = dir("guard-received").absolutePath)

        val first = FlashDesktop.create(config)
        engines += first
        val refusal = assertFailsWith<IllegalStateException> { FlashDesktop.create(config) }
        assertTrue(refusal.message.orEmpty().contains("already owns"), refusal.message)

        first.close()
        val third = FlashDesktop.create(config)
        engines += third
        assertTrue(third !== first)
    }

    @Test
    fun `without a registered factory create explains what is missing`() {
        FlashDesktop.resetForTesting()
        // The class is already initialised in this JVM, so the reflection fallback cannot register it again.
        val failure = assertFailsWith<IllegalStateException> { FlashDesktop.create() }
        assertTrue(failure.message.orEmpty().contains("No FlashDesktop factory registered"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------

    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(port)) }
    }.isSuccess

    private fun fakeSwarm(): FlashSwarm = object : FlashSwarm {
        override val rows: StateFlow<List<FlashTransfer>> = MutableStateFlow(emptyList())
        override fun status(transferId: String): StateFlow<com.transfer.flash.core.swarm.api.FlashSwarmStatus?> =
            MutableStateFlow(null)
        override suspend fun accept(transferId: String) {}
        override suspend fun decline(transferId: String) {}
        override suspend fun pause(transferId: String) {}
        override suspend fun resume(transferId: String) {}
        override suspend fun cancelLocal(transferId: String) {}
        override suspend fun cancelAsOrigin(
            transferId: String,
            reason: com.transfer.flash.core.swarm.model.SwarmTombstoneReason,
        ) {}
        override suspend fun pauseForSystem(transferId: String, reason: String) {}
        override fun reevaluate() {}
        override suspend fun runRetentionCleanup() {}
    }

    /** A [FlashCalling] whose `activeCall` is in [state] (or none) and which counts `hangUp()`; the rest is inert. */
    private fun fakeCalling(state: FlashCallState?, hangUps: AtomicInteger): FlashCalling {
        val call = state?.let {
            FlashCallUiState(
                callId = "c1",
                peerId = "peer",
                peerName = "Peer",
                direction = FlashCallDirection.OUTGOING,
                video = false,
                state = it,
            )
        }
        val active = MutableStateFlow(call)
        lateinit var proxy: FlashCalling
        proxy = Proxy.newProxyInstance(
            FlashCalling::class.java.classLoader,
            arrayOf(FlashCalling::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getActiveCall" -> active
                "hangUp" -> {
                    hangUps.incrementAndGet()
                    true
                }
                "toString" -> "FakeCalling"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.firstOrNull() === proxy
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    StateFlow::class.java -> MutableStateFlow(emptyMap<String, Any>())
                    else -> null
                }
            }
        } as FlashCalling
        return proxy
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val POLL_MS = 50L
    }
}
