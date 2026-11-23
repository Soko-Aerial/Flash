package com.transfer.flash.desktop

import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A desktop peer that disappears must not leave its inbound row "Downloading" at its last byte
 * count. The Android hosts have always cleaned inbound transfers on a session drop; the desktop
 * path only opened a call-recovery window, which is why the same transfer could look Failed on the
 * sender and still active here.
 */
class DesktopInboundRecoveryTest {

    @Test
    fun `a peer disconnect fails only its inbound transfers and preserves their progress`() = runBlocking {
        val repository = RealFlashTransferRepository(
            streamChannelFactory = StreamChannelFactory { _, _ -> null },
            fileSourceOpener = { Buffer() },
            defaultStreams = 1,
        )
        repository.onIncomingStarted(
            "tx-a", "f-a", "a.bin", 2L * 65_536, "Peer A", "peer-a", "/tmp/a.part",
        )
        repository.onIncomingStarted(
            "tx-b", "f-b", "b.bin", 2L * 65_536, "Peer B", "peer-b", "/tmp/b.part",
        )
        repository.onIncomingProgress("tx-a", 65_536L)

        val pipeline = ReceivePipeline(sink = ChunkSink { _, _ -> })
        pipeline.onFrame(ChunkFrame.serialize(fileStart("tx-a", "f-a", "a.bin")))
        pipeline.onFrame(ChunkFrame.serialize(fileStart("tx-b", "f-b", "b.bin")))

        val incomingOwners = mutableMapOf("tx-a" to "peer-a", "tx-b" to "peer-b")
        val incomingMeta = mutableMapOf(
            "tx-a" to fileStart("tx-a", "f-a", "a.bin"),
            "tx-b" to fileStart("tx-b", "f-b", "b.bin"),
        )
        val receivedPaths = mutableMapOf("tx-a" to "/tmp/a.part", "tx-b" to "/tmp/b.part")
        val openHandles = mutableMapOf<String, com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle>()
        val failed = mutableListOf<String>()

        val ids = failInboundTransfersForPeer(
            peerDeviceId = "peer-a",
            incomingOwners = incomingOwners,
            receivePipeline = pipeline,
            openHandles = openHandles,
            incomingMeta = incomingMeta,
            receivedPaths = receivedPaths,
            onTransferFailed = { transferId ->
                failed += transferId
                repository.onIncomingFailed(transferId, "peer disconnected")
            },
        )

        assertEquals(listOf("tx-a"), ids)
        assertEquals(listOf("tx-a"), failed)

        val stopped = repository.activeTransfers.value.first { it.id.value == "tx-a" }
        assertEquals(FlashTransferState.Failed, stopped.state)
        assertEquals(TransferFailureText.CONNECTION_LOST, stopped.errorMessage)
        assertEquals(65_536L, stopped.bytesDone, "the durable bytes must survive for Retry")

        val untouched = repository.activeTransfers.value.first { it.id.value == "tx-b" }
        assertEquals(FlashTransferState.Transferring, untouched.state)

        assertFalse(pipeline.activeTransferIds().contains("tx-a"))
        assertTrue(pipeline.activeTransferIds().contains("tx-b"))
        assertFalse(incomingOwners.containsKey("tx-a"))
        assertTrue(incomingOwners.containsKey("tx-b"))
        assertFalse(incomingMeta.containsKey("tx-a"))
        assertTrue(incomingMeta.containsKey("tx-b"))
    }

    private fun fileStart(transferId: String, fileId: String, name: String): ChunkFrame.FileStart =
        ChunkFrame.FileStart(
            transferId = transferId,
            fileId = fileId,
            fileName = name,
            totalBytes = 2L * 65_536,
            totalChunks = 2,
            chunkSize = 65_536,
            fileSha256Hex = "0".repeat(64),
        )
}
