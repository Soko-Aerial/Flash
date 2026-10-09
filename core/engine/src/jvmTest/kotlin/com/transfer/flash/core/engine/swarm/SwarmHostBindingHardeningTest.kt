package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.engine.MagicFrameRouter
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.GroupSwarmAnnouncementListener
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Audit 2026-10-08: remote-triggered crash through a malformed announcement, binding lifetime, frame ordering. */
class SwarmHostBindingHardeningTest {

    private class QuietStorage : PieceStorage {
        override suspend fun openPartial(key: String, size: Long): PartialHandle? = null
        override suspend fun openSource(uri: String): SourceHandle? = null
        override suspend fun freeBytesFor(key: String): Long = 10_000_000_000L
        override suspend fun deletePartial(key: String) {}
        override suspend fun finalize(key: String, fileName: String, mime: String, expectedSha256: ByteArray): StorageFinalizeResult =
            StorageFinalizeResult(false)
        override suspend fun purgeOrphanedPartials(activeKeys: Set<String>) {}
    }

    private class QuietNetwork : FlashNetwork {
        private val state = MutableStateFlow(FlashNetworkState())
        private val sessions = MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
        private val health = MutableStateFlow(FlashConnectionHealth.Offline)
        override val networkState: StateFlow<FlashNetworkState> = state.asStateFlow()
        override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = sessions.asStateFlow()
        override val connectionHealth: StateFlow<FlashConnectionHealth> = health.asStateFlow()
        override suspend fun start(listenPort: Int): FlashResult<Int> = FlashResult.Success(0)
        override suspend fun stop(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> = FlashResult.Failure(FlashError.Unknown("test"))
        override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> = FlashResult.Failure(FlashError.Unknown("test"))
        override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    private class TestChatRepository : FlashChatRepository {
        private val _chatListState = MutableStateFlow(FlashChatListUiState())
        override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()
        private val _conversationState = MutableStateFlow(
            FlashConversationUiState(
                header = FlashChatHeaderUiState(title = "Group 1", avatarInitials = "G1", isGroup = true),
                messages = emptyList(),
            )
        )
        override val conversationState: StateFlow<FlashConversationUiState> = _conversationState.asStateFlow()
        override var swarmAnnouncementListener: GroupSwarmAnnouncementListener? = null
        override var onGroupMessageDeletedForEveryone: ((groupId: String, messageId: String) -> Unit)? = null
        override fun openConversation(conversationId: String) {}
        override fun closeConversation() {}
        override fun sendText(text: String) {}
        override fun openAttachmentPicker() {}
        override fun enterListSelectionMode(conversationId: String) {}
        override fun toggleListSelection(conversationId: String) {}
        override fun clearListSelection() {}
        override fun archiveConversation(conversationId: String) {}
    }

    private class Rig(
        val scope: CoroutineScope,
        val chat: TestChatRepository,
        val binding: SwarmHostBinding,
        val uncaught: CopyOnWriteArrayList<Throwable>,
        val polls: AtomicInteger,
    )

    private fun rig(): Rig {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        // The parent has NO handler for the binding's own failures other than recording them: a throw that escapes the
        // binding's scope lands here (in production it lands in the process's uncaught handler and kills Android).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })
        val chat = TestChatRepository()
        val polls = AtomicInteger()
        val transferRepo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) },
            store = null,
            repositoryScope = scope,
        )
        val binding = SwarmHostBinding(
            config = FlashSwarmConfig(),
            localDeviceId = "local",
            scope = scope,
            magicRouter = MagicFrameRouter(),
            network = QuietNetwork(),
            transferRepository = transferRepo,
            groupContext = SwarmInteropTest.TestSwarmGroupContext("local", setOf("local", "peer"), emptyMap()),
            storage = QuietStorage(),
            stateStore = RoomSwarmStateStore(SwarmInteropTest.FakeSwarmDao()),
            chatRepository = chat,
            isCallActive = { polls.incrementAndGet(); false },
        )
        return Rig(scope, chat, binding, uncaught, polls)
    }

    @Test
    fun `a malformed announcement is dropped and the binding keeps processing the next valid one`() = runBlocking<Unit> {
        val r = rig()
        try {
            val listener = requireNotNull(r.chat.swarmAnnouncementListener)
            val validRoot = "ab".repeat(32)
            for (bad in listOf("zz", "ab".repeat(31), "AB".repeat(32), "", "../../x")) {
                // Must not throw to the caller either.
                listener.onSwarmAnnouncement("g1", "m-bad", "m-bad", "peer", bad, 65_536, 262_144L, "f.bin", "application/octet-stream", 1_000L, "sig")
            }
            // Out-of-range sizes are refused as well.
            listener.onSwarmAnnouncement("g1", "m-size", "m-size", "peer", validRoot, 12_345, 262_144L, "f.bin", "x/y", 1_000L, "sig")
            listener.onSwarmAnnouncement("g1", "m-size", "m-size", "peer", validRoot, 65_536, 0L, "f.bin", "x/y", 1_000L, "sig")

            listener.onSwarmAnnouncement("g1", "m-good", "m-good", "peer", validRoot, 65_536, 262_144L, "good.bin", "application/octet-stream", 1_000L, "sig")

            withTimeout(10_000) { while (r.binding.driver.rows.value.none { it.id.value == "m-good" }) delay(25) }
            assertTrue("the binding must still be alive", r.binding.bindingJob.isActive)
            assertTrue("nothing may escape to the uncaught handler: ${r.uncaught}", r.uncaught.isEmpty())
            assertTrue(r.binding.driver.rows.value.none { it.id.value == "m-bad" || it.id.value == "m-size" })
        } finally {
            r.binding.detach()
            r.scope.cancel()
        }
    }

    @Test
    fun `announcementProblem accepts exactly what ContentRoot and PieceMath accept`() {
        val ok = "0123456789abcdef".repeat(4)
        assertNull(SwarmHostBinding.announcementProblem(ok, 65_536, 1L))
        assertNull(SwarmHostBinding.announcementProblem(ok, 1_048_576, 16L * 1024 * 1024 * 1024))
        assertTrue(SwarmHostBinding.announcementProblem("zz", 65_536, 10L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok.uppercase(), 65_536, 10L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok, 65_537, 10L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok, 32_768, 10L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok, 2_097_152, 10L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok, 65_536, 0L) != null)
        assertTrue(SwarmHostBinding.announcementProblem(ok, 65_536, -5L) != null)
        // 65536 * 16385 pieces
        assertTrue(SwarmHostBinding.announcementProblem(ok, 65_536, 65_536L * 16_385) != null)
    }

    @Test
    fun `cancelling the parent scope stops the environment poll, the collectors and the driver`() = runBlocking<Unit> {
        val r = rig()
        try {
            withTimeout(5_000) { while (r.polls.get() < 1) delay(20) }
            r.scope.cancel()
            r.binding.bindingJob.join()
            assertTrue("the binding job must end with its parent", r.binding.bindingJob.isCancelled)
            val after = r.polls.get()
            delay(2_600) // longer than the 2 s poll interval
            assertEquals("the poll loop must have stopped", after, r.polls.get())
        } finally {
            r.scope.cancel()
        }
    }

    @Test
    fun `detach is idempotent and a second call does not unhook a newer binding's listener`() = runBlocking<Unit> {
        val r = rig()
        try {
            r.binding.detach()
            assertNull(r.chat.swarmAnnouncementListener)
            assertFalse(r.binding.bindingJob.isActive)
            val newer = GroupSwarmAnnouncementListener { _, _, _, _, _, _, _, _, _, _, _ -> }
            r.chat.swarmAnnouncementListener = newer
            r.binding.detach() // e.g. the engine closing after detachSwarm already ran
            assertSame(newer, r.chat.swarmAnnouncementListener)
        } finally {
            r.scope.cancel()
        }
    }

    // ---- PeerOrderedInbox ----

    @Test
    fun `frames from one peer are handled in arrival order even when earlier ones are slower`() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = PeerOrderedInbox(scope, Dispatchers.Default, capacity = 512) { _, frame ->
            val n = frame[0].toInt() and 0xFF
            if (n % 7 == 0) delay(3)
            seen += n
        }
        try {
            for (n in 0 until 200) assertTrue(inbox.offer("peer-a", byteArrayOf(n.toByte())))
            withTimeout(10_000) { while (seen.size < 200) delay(10) }
            assertEquals((0 until 200).toList(), seen.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a flood is bounded and dropped, then the queued frames are still handled in order`() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = PeerOrderedInbox(scope, Dispatchers.Default, capacity = 4) { _, frame ->
            gate.await()
            seen += frame[0].toInt()
        }
        try {
            val accepted = (0 until 50).count { inbox.offer("peer-a", byteArrayOf(it.toByte())) }
            assertTrue("bounded buffer: accepted=$accepted", accepted <= 6)
            assertTrue(inbox.droppedCount >= 44)
            gate.complete(Unit)
            withTimeout(5_000) { while (seen.size < accepted) delay(10) }
            assertEquals(seen.sorted(), seen.toList())
            assertEquals(accepted, seen.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a handler that throws does not stop the peer's worker`() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = PeerOrderedInbox(scope, Dispatchers.Default) { _, frame ->
            if (frame[0].toInt() == 1) error("bad frame")
            seen += frame[0].toInt()
        }
        try {
            inbox.offer("p", byteArrayOf(0)); inbox.offer("p", byteArrayOf(1)); inbox.offer("p", byteArrayOf(2))
            withTimeout(5_000) { while (seen.size < 2) delay(10) }
            assertEquals(listOf(0, 2), seen.toList())
        } finally {
            scope.cancel()
        }
    }
}
