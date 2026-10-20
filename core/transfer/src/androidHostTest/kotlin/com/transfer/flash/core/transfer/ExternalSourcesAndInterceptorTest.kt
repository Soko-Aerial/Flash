package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-216: the swarm and the hub file binding both publish external rows (one must never hide the other), and the hub
 * binding may take a 1:1 send before the direct pipeline does.
 */
class ExternalSourcesAndInterceptorTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val channelOpens = AtomicInteger(0)

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private val peer = FlashDevice(FlashDeviceId("peer-1"), "Pixel", FlashTransportType.LAN)

    private fun repo() = RealFlashTransferRepository(
        chunker = Chunker(),
        streamChannelFactory = StreamChannelFactory { channelId, _ ->
            channelOpens.incrementAndGet()
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        },
        fileSourceOpener = FileSourceOpener { Buffer().write(ByteArray(64)) },
        repositoryScope = scope,
        workerDispatcher = dispatcher,
        defaultStreams = 1,
    )

    private fun row(id: String, viaHub: Boolean = false) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = "Pixel",
        fileName = "$id.bin",
        direction = FlashTransferDirection.Sending,
        bytesDone = 0L,
        bytesTotal = 10L,
        state = FlashTransferState.Transferring,
        viaHub = viaHub,
    )

    /** Records which control was asked to do what, so a misrouted call is visible. */
    private class RecordingControl(val name: String, val rows: MutableStateFlow<List<FlashTransfer>>) : ExternalTransferControl {
        val calls = CopyOnWriteArrayList<String>()
        override fun owns(transferId: String): Boolean = rows.value.any { it.id.value == transferId }
        override suspend fun accept(transferId: String) { calls += "accept:$transferId" }
        override suspend fun decline(transferId: String) { calls += "decline:$transferId" }
        override suspend fun pause(transferId: String) { calls += "pause:$transferId" }
        override suspend fun resume(transferId: String) { calls += "resume:$transferId" }
        override suspend fun cancel(transferId: String) { calls += "cancel:$transferId" }
        override suspend fun pauseForSystem(transferId: String, reason: String) { calls += "system:$transferId:$reason" }
    }

    private fun awaitUntil(describe: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: ${describe()}" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `two sources keep both sets of rows and each control gets only its own ids`() = runBlocking {
        val repository = repo()
        val swarmRows = MutableStateFlow(listOf(row("swarm-1")))
        val hubRows = MutableStateFlow(listOf(row("hub-1", viaHub = true)))
        val swarm = RecordingControl("swarm", swarmRows)
        val hub = RecordingControl("hub", hubRows)
        repository.attachExternalRows(swarmRows, swarm, source = "swarm")
        repository.attachExternalRows(hubRows, hub, source = "hub")
        awaitUntil({ repository.activeTransfers.value.toString() }) { repository.activeTransfers.value.size == 2 }

        // A later emission of one source must not drop the other's rows (the single-source bug this replaces).
        swarmRows.value = listOf(row("swarm-1"), row("swarm-2"))
        awaitUntil({ repository.activeTransfers.value.toString() }) { repository.activeTransfers.value.size == 3 }
        assertTrue(repository.activeTransfers.value.single { it.id.value == "hub-1" }.viaHub)

        repository.pauseTransfer(FlashTransferId("hub-1"))
        repository.resumeTransfer(FlashTransferId("swarm-2"))
        repository.cancelTransfer(FlashTransferId("hub-1"))
        repository.acceptIncoming(FlashTransferId("swarm-1"))
        repository.declineIncoming(FlashTransferId("hub-1"))
        repository.pauseForSystem(FlashTransferId("hub-1"), "SERVICE_TIMEOUT")
        assertEquals(listOf("pause:hub-1", "cancel:hub-1", "decline:hub-1", "system:hub-1:SERVICE_TIMEOUT"), hub.calls.toList())
        assertEquals(listOf("resume:swarm-2", "accept:swarm-1"), swarm.calls.toList())
        assertTrue(repository.isExternalRow("hub-1"))
        assertTrue(repository.isExternalRow("swarm-2"))
        assertFalse(repository.isExternalRow("lan-1"))
    }

    @Test
    fun `attaching a source again replaces only that source and the old flow stops counting`() = runBlocking {
        val repository = repo()
        val first = MutableStateFlow(listOf(row("a-1")))
        val other = MutableStateFlow(listOf(row("b-1")))
        repository.attachExternalRows(first, RecordingControl("a", first), source = "a")
        repository.attachExternalRows(other, RecordingControl("b", other), source = "b")
        awaitUntil({ repository.activeTransfers.value.toString() }) { repository.activeTransfers.value.size == 2 }

        val second = MutableStateFlow(listOf(row("a-2")))
        val secondControl = RecordingControl("a2", second)
        repository.attachExternalRows(second, secondControl, source = "a")
        awaitUntil({ repository.activeTransfers.value.toString() }) {
            repository.activeTransfers.value.map { it.id.value }.toSet() == setOf("a-2", "b-1")
        }
        // The replaced flow is no longer collected: its emissions change nothing.
        first.value = listOf(row("a-1"), row("a-3"))
        Thread.sleep(100)
        assertEquals(setOf("a-2", "b-1"), repository.activeTransfers.value.map { it.id.value }.toSet())
        assertFalse(repository.isExternalRow("a-1"))
        repository.pauseTransfer(FlashTransferId("a-2"))
        assertEquals(listOf("pause:a-2"), secondControl.calls.toList())
    }

    @Test
    fun `the default source keeps the old single-argument call working`() = runBlocking {
        val repository = repo()
        val rows = MutableStateFlow(listOf(row("s-1")))
        val control = RecordingControl("s", rows)
        repository.attachExternalRows(rows, control)
        awaitUntil({ repository.activeTransfers.value.toString() }) { repository.activeTransfers.value.size == 1 }
        repository.cancelTransfer(FlashTransferId("s-1"))
        assertEquals(listOf("cancel:s-1"), control.calls.toList())
    }

    @Test
    fun `a claimed send never opens a direct channel and returns the interceptor's id`() = runBlocking {
        val repository = repo()
        val asked = CopyOnWriteArrayList<String>()
        repository.sendInterceptor = TransferSendInterceptor { device, uri, name, size ->
            asked += "${device.id.value}|$uri|$name|$size"
            FlashTransferId("hub-o-1")
        }
        val result = repository.sendFile(peer, "content://doc/1", "report.pdf", 64L)
        assertEquals(FlashTransferId("hub-o-1"), (result as FlashResult.Success).value)
        assertEquals(listOf("peer-1|content://doc/1|report.pdf|64"), asked.toList())
        Thread.sleep(100)
        assertEquals(0, channelOpens.get())
        assertTrue(repository.activeTransfers.value.none { it.id.value != "hub-o-1" })
    }

    @Test
    fun `a declining or throwing interceptor leaves the send to the direct pipeline`() = runBlocking {
        val repository = repo()
        repository.sendInterceptor = TransferSendInterceptor { _, _, _, _ -> null }
        val declined = repository.sendFile(peer, "file:///a", "a.bin", 64L)
        val declinedId = (declined as FlashResult.Success).value
        awaitUntil({ repository.activeTransfers.value.toString() }) {
            repository.activeTransfers.value.any { it.id == declinedId }
        }

        repository.sendInterceptor = TransferSendInterceptor { _, _, _, _ -> error("broken") }
        val fellThrough = repository.sendFile(peer, "file:///b", "b.bin", 64L)
        val fellThroughId = (fellThrough as FlashResult.Success).value
        assertNotEquals(declinedId, fellThroughId)
        awaitUntil({ repository.activeTransfers.value.toString() }) {
            repository.activeTransfers.value.any { it.id == fellThroughId }
        }
        assertFalse(repository.activeTransfers.value.first { it.id == fellThroughId }.viaHub)
    }

    @Test
    fun `sends with explicit identities (group and swarm fallbacks) never reach the interceptor`() = runBlocking {
        val repository = repo()
        val asked = AtomicInteger(0)
        repository.sendInterceptor = TransferSendInterceptor { _, _, _, _ ->
            asked.incrementAndGet()
            FlashTransferId("hub-o-x")
        }
        repository.sendFile(peer, "file:///c", "c.bin", 64L, wireFileId = "w-1")
        repository.sendFile(peer, "file:///d", "d.bin", 64L, transferId = "t-2", wireFileId = "w-2")
        assertEquals(0, asked.get())
    }
}
