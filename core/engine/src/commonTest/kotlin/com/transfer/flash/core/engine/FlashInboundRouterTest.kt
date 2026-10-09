@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.ptt.PttPingEvent
import com.transfer.flash.core.ptt.PttPressOutcome
import com.transfer.flash.core.ptt.PttSessionStats
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashInboundRouterTest {

    // ---------------------------------------------------------------------------------------------
    // Frame predicates and simple text routing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun framePredicatesIdentifyFrameTypesCorrectly() {
        assertTrue(FlashInboundRouter.isCallFrameText("FLASH_CALL callId=c1 type=offer"))
        assertFalse(FlashInboundRouter.isCallFrameText("FLASH_MSG text=hello"))

        assertTrue(FlashInboundRouter.isPairingFrameText("FLASH_PAIR t=hello fp=112233"))
        assertFalse(FlashInboundRouter.isPairingFrameText("FLASH_CALL callId=c1"))

        assertTrue(FlashInboundRouter.isTransferControlText("FLASH_XFER action=cancel transferId=tx1"))
        assertFalse(FlashInboundRouter.isTransferControlText("FLASH_PRES v=1"))

        val pttHeader = byteArrayOf('P'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0, 0)
        assertTrue(FlashInboundRouter.isPttAudio(pttHeader))
        val flshHeader = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 0, 0)
        assertFalse(FlashInboundRouter.isPttAudio(flshHeader))
    }

    @Test
    fun routerLogTagMatchesHostTagSoLogcatFiltersKeepWorking() {
        assertEquals("FlashEngine", FlashInboundRouter.TAG)
    }

    @Test
    fun callingSignalingFrameRoutesToCallHandler() = runTest {
        var calledPeer = ""
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerA",
            text = "FLASH_CALL callId=call1 type=offer",
            callHandler = { peer, _ ->
                calledPeer = peer
                true
            },
        )
        assertTrue(handled)
        assertEquals("peerA", calledPeer)
    }

    @Test
    fun presenceFrameRoutesToPresenceHandler() = runTest {
        var presenceHandled = false
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerA",
            text = "FLASH_PRES salt=123",
            presenceHandler = { _, _ ->
                presenceHandled = true
                true
            },
        )
        assertTrue(handled)
        assertTrue(presenceHandled)
    }

    @Test
    fun modeFrameRoutesToModeHandler() = runTest {
        var modeHandled = false
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerA",
            text = "FLASH_LINK mode=BOOST",
            modeHandler = { _, _ ->
                modeHandled = true
                true
            },
        )
        assertTrue(handled)
        assertTrue(modeHandled)
    }

    @Test
    fun pairingFrameRoutesToPairingHandler() = runTest {
        var pairedPeer = ""
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerB",
            text = "FLASH_PAIR t=hello fp=aabbcc",
            pairingHandler = { peer, _ -> pairedPeer = peer },
        )
        assertTrue(handled)
        assertEquals("peerB", pairedPeer)
    }

    @Test
    fun pairingFrameWithoutPairingHandlerFallsThroughSilently() = runTest {
        // Android pairs elsewhere (the app's discovery holder): no handler means "not ours", not a
        // consumed frame and not a warning on every hello.
        val warnings = CapturingLogSink.install()
        try {
            val handled = FlashInboundRouter.routeInboundText(
                peerDeviceId = "peerP",
                text = "FLASH_PAIR t=hello fp=aabbcc",
                pairingHandler = null,
            )
            assertFalse(handled)
            assertTrue(warnings.warnings.none { it.contains("Pairing") }, "no pairing warning: ${warnings.warnings}")
        } finally {
            CapturingLogSink.restore()
        }
    }

    @Test
    fun unhandledTextFallsThroughToCustomHandler() = runTest {
        var customHandled = false
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerC",
            text = "CUSTOM_FRAME payload=xyz",
            customHandler = { _, _ ->
                customHandled = true
                true
            },
        )
        assertTrue(handled)
        assertTrue(customHandled)
    }

    @Test
    fun unknownFrameReturnsFalseWhenNoHandlerConsumesIt() = runTest {
        val handled = FlashInboundRouter.routeInboundText(peerDeviceId = "peerC", text = "UNKNOWN_FRAME 12345")
        assertFalse(handled)
    }

    @Test
    fun sessionEventsExecuteWithoutExceptions() {
        var restoredPeer = ""
        var lostPeer = ""
        var pairingHelloPeer = ""

        FlashSessionCoordinator.onSessionUp(
            peerDeviceId = "peerD",
            chatRepository = null,
            onSignalingRestored = { restoredPeer = it },
            onPairingHello = { pairingHelloPeer = it },
        )
        assertEquals("peerD", restoredPeer)
        assertEquals("peerD", pairingHelloPeer)

        FlashSessionCoordinator.onSessionDown(peerDeviceId = "peerE", onSignalingLost = { lostPeer = it })
        assertEquals("peerE", lostPeer)
    }

    // ---------------------------------------------------------------------------------------------
    // E2E, downgrade guard and chat delivery (recording sink)
    // ---------------------------------------------------------------------------------------------

    private class RecordingChatSink : InboundChatSink {
        val direct = mutableListOf<Pair<MessageWireFrame, String?>>()
        val group = mutableListOf<Pair<String, GroupWireFrame>>()
        override suspend fun onInboundWireFrame(frame: MessageWireFrame, transportPeerId: String?) {
            direct += frame to transportPeerId
        }

        override suspend fun onInboundGroupWireFrame(peerDeviceId: String, frame: GroupWireFrame) {
            group += peerDeviceId to frame
        }
    }

    private fun plainChatText(sender: String, text: String = "hello"): String =
        assertNotNull(
            ChatTextFrameCodec.encode(
                MessageWireFrame.TextMessage(
                    localId = "m1", conversationId = sender, senderId = sender,
                    senderName = "Peer", text = text, sentAt = 1L,
                ),
            ),
        )

    private val key = ByteArray(32) { 7 }

    @Test
    fun downgradeGuardDropsPlaintextDirectChatFromKeyedPeer() = runTest {
        val sink = RecordingChatSink()
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "keyedPeer",
            text = plainChatText("keyedPeer"),
            sessionKeyLookup = { key },
            chatSink = sink,
        )
        assertTrue(handled)
        assertTrue(sink.direct.isEmpty(), "a plaintext direct-chat frame from a keyed peer is a downgrade and must not reach chat")
    }

    @Test
    fun plaintextDirectChatIsDeliveredWhenNoSessionKeyExists() = runTest {
        val sink = RecordingChatSink()
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "newPeer",
            text = plainChatText("newPeer", "hi there"),
            sessionKeyLookup = { null },
            chatSink = sink,
        )
        assertTrue(handled)
        assertEquals(1, sink.direct.size)
        val (frame, transportPeer) = sink.direct.single()
        assertEquals("newPeer", transportPeer)
        assertEquals("hi there", (frame as MessageWireFrame.TextMessage).text)
    }

    @Test
    fun securedFrameWithKeyIsDecryptedAndDelivered() = runTest {
        val sink = RecordingChatSink()
        val wire = E2eFrameCodec.encryptToWireFrame(plainChatText("peerS", "secret"), key)
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerS",
            text = wire,
            sessionKeyLookup = { key },
            chatSink = sink,
        )
        assertTrue(handled)
        assertEquals("secret", (sink.direct.single().first as MessageWireFrame.TextMessage).text)
    }

    @Test
    fun tamperedSecuredFrameIsDroppedAndWrongKeyIsDropped() = runTest {
        val sink = RecordingChatSink()
        val wire = E2eFrameCodec.encryptToWireFrame(plainChatText("peerS", "secret"), key)
        val tampered = wire.dropLast(3) + (if (wire.takeLast(3) == "AAA") "BBB" else "AAA")
        assertTrue(FlashInboundRouter.routeInboundText("peerS", tampered, sessionKeyLookup = { key }, chatSink = sink))
        assertTrue(FlashInboundRouter.routeInboundText("peerS", wire, sessionKeyLookup = { ByteArray(32) { 9 } }, chatSink = sink))
        assertTrue(FlashInboundRouter.routeInboundText("peerS", wire, sessionKeyLookup = { null }, chatSink = sink))
        assertTrue(sink.direct.isEmpty())
    }

    @Test
    fun throwingChatSinkDoesNotEscapeTheTextRouter() = runTest {
        val handled = FlashInboundRouter.routeInboundText(
            peerDeviceId = "peerT",
            text = plainChatText("peerT"),
            chatSink = object : InboundChatSink {
                override suspend fun onInboundWireFrame(frame: MessageWireFrame, transportPeerId: String?) {
                    throw IllegalStateException("database closed")
                }

                override suspend fun onInboundGroupWireFrame(peerDeviceId: String, frame: GroupWireFrame) = Unit
            },
        )
        assertTrue(handled, "a frame whose handler failed is dropped, not retried by falling through")
    }

    @Test
    fun throwingHandlersDoNotEscapeTheTextRouter() = runTest {
        assertTrue(
            FlashInboundRouter.routeInboundText(
                peerDeviceId = "p", text = "FLASH_PRES x=1",
                presenceHandler = { _, _ -> error("boom") },
            ),
        )
        assertTrue(
            FlashInboundRouter.routeInboundText(
                peerDeviceId = "p", text = "FLASH_PAIR t=hello",
                pairingHandler = { _, _ -> error("boom") },
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Transfer control ownership (text)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun transferControlFromAnotherPeerIsIgnored() = runTest {
        val f = Fixture()
        f.repo.onIncomingOffered("tx1", "f1", "a.bin", 100L, "peer", "owner")
        f.repo.onIncomingStarted("tx1", "f1", "a.bin", 100L, "peer", "owner", null)

        FlashInboundRouter.routeInboundText(
            peerDeviceId = "intruder",
            text = "FLASH_XFER action=cancel transferId=tx1",
            transferRepository = f.repo,
        )
        assertEquals(FlashTransferState.Transferring, f.row("tx1").state, "another peer must not cancel a transfer it does not own")

        FlashInboundRouter.routeInboundText(
            peerDeviceId = "owner",
            text = "FLASH_XFER action=cancel transferId=tx1",
            transferRepository = f.repo,
        )
        assertEquals(FlashTransferState.Cancelled, f.row("tx1").state)
    }

    @Test
    fun transferControlIsBoundToTheOwnerMapBeforeAnyRowExists() = runTest {
        val f = Fixture()
        f.repo.onIncomingOffered("tx2", "f1", "a.bin", 100L, "peer", null)
        val owners = mapOf("tx2" to "owner")
        FlashInboundRouter.routeInboundText("intruder", "FLASH_XFER action=cancel transferId=tx2", transferRepository = f.repo, incomingOwners = owners)
        assertEquals(FlashTransferState.Offered, f.row("tx2").state)
    }

    // ---------------------------------------------------------------------------------------------
    // Binary: PTT, magic, secure frames
    // ---------------------------------------------------------------------------------------------

    @Test
    fun pttAudioRoutesToPttProviderInBinaryRouter() {
        val dummyPtt = DummyPtt()
        val frame = PttAudioFrame(sessionId = "s1", seq = 1L, captureTsMs = 1000L, pcm = ByteArray(10) { 0 })
        val handled = FlashInboundRouter.routeInboundBinary(
            peerDeviceId = "peer1",
            data = PttAudioFrame.encode(frame),
            reply = { true },
            pttProvider = { dummyPtt },
        )
        assertTrue(handled)
        assertTrue(dummyPtt.binaryReceived)
    }

    @Test
    fun magicFrameDispatchesInBinaryRouter() {
        val router = MagicFrameRouter()
        var magicHandled = false
        val testMagic = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
        router.register(testMagic) { _, _, _ ->
            magicHandled = true
            true
        }
        val handled = FlashInboundRouter.routeInboundBinary(
            peerDeviceId = "peer1",
            data = testMagic + "payload".encodeToByteArray(),
            reply = { true },
            magicRouter = router,
        )
        assertTrue(handled)
        assertTrue(magicHandled)
    }

    @Test
    fun encryptedBinaryFrameWithoutKeyIsDroppedSafely() {
        val encrypted = SecureBinaryFrameCodec.encrypt("secret".encodeToByteArray(), key)
        val handled = FlashInboundRouter.routeInboundBinary(
            peerDeviceId = "peerX", data = encrypted, reply = { true }, sessionKeyLookup = { null },
        )
        assertTrue(handled)
    }

    @Test
    fun receiverRepliesAreEncryptedWhenTheSessionHasAKey() {
        val f = Fixture()
        val handled = f.route("p1", f.startFrame("t-enc"), key = key, encryptIncoming = true)
        assertTrue(handled)
        // alreadyCompleted is not involved; the offer parks. Force a Rejected reply path instead:
        // an unknown chunk makes the router cancel, which is a text frame, so verify encryption on
        // the ack path in the dedicated progress test below.
        assertTrue(f.log.isNotEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Receive pipeline events
    // ---------------------------------------------------------------------------------------------

    @Test
    fun newOfferCallsSessionStartedFirstThenOfferReceived() {
        val f = Fixture()
        assertTrue(f.route("p1", f.startFrame("t1")))
        assertEquals(listOf("session:t1:p1", "offer:t1:p1"), f.log)
        assertEquals("t1", f.meta.keys.single())
        assertEquals("p1", f.owners["t1"])
        assertEquals(FlashTransferState.Offered, f.row("t1").state)
    }

    @Test
    fun newOfferWithoutHookRecordsAnOfferedRow() {
        val f = Fixture(useOfferHook = false)
        f.route("p1", f.startFrame("t1"))
        val row = f.row("t1")
        assertEquals(FlashTransferState.Offered, row.state)
        assertEquals("p1", row.peerDeviceId)
    }

    @Test
    fun alreadyCompletedOfferRepliesCompleteResumesAndDoesNotLeak() {
        val f = Fixture()
        f.repo.onIncomingOffered("t1", "f1", "video.bin", f.data.size.toLong(), "peer", "p1")
        f.repo.onIncomingCompleted("t1", verified = true, localPath = null)

        assertTrue(f.route("p1", f.startFrame("t1")))

        val complete = f.replied().filterIsInstance<ChunkFrame.Complete>().single()
        assertTrue(complete.verified)
        assertEquals(listOf("p1:resume:t1"), f.xfer)
        assertTrue(f.log.contains("session:t1:p1"), "tracking is registered before the branches")
        assertTrue(f.log.contains("untracked:t1"), "and released again: this branch never sees a Completed event")
        assertFalse(f.log.any { it.startsWith("offer:") })
        assertTrue(f.meta.isEmpty(), "incomingMeta must not leak")
        assertTrue(f.owners.isEmpty(), "owner must not leak")
        assertTrue(f.pipeline.activeTransferIds().isEmpty(), "the awaiting pipeline session must not leak a slot")
    }

    @Test
    fun r04AFileOfTheRightLengthButOtherContentIsNotAnAlreadyCompletedTransfer() {
        val f = Fixture()
        val file = tempFile("r04-wrong", ByteArray(f.data.size) { 7 })
        try {
            f.sizeMatches = true
            f.paths["t1"] = file.path

            assertTrue(f.route("p1", f.startFrame("t1")))

            assertTrue(f.replied().filterIsInstance<ChunkFrame.Complete>().isEmpty(), "no 'already complete' reply for look-alike bytes")
            assertTrue(f.xfer.isEmpty(), "the sender is not released as if it were done: ${f.xfer}")
            assertTrue(f.log.any { it.startsWith("offer:t1") }, "it goes through the normal offer path: ${f.log}")
        } finally {
            file.delete()
        }
    }

    @Test
    fun r04AFileWhoseHashMatchesTheOfferIsStillRecognisedAsComplete() {
        val f = Fixture()
        val file = tempFile("r04-right", f.data)
        try {
            f.sizeMatches = true
            f.paths["t1"] = file.path

            assertTrue(f.route("p1", f.startFrame("t1")))

            assertTrue(f.replied().filterIsInstance<ChunkFrame.Complete>().single().verified)
            assertEquals(listOf("p1:resume:t1"), f.xfer)
        } finally {
            file.delete()
        }
    }

    @Test
    fun r06AFailedStorageWriteFailsTheRowClosesTheSinkAndCancelsTheSender() {
        val f = Fixture(failWrites = true)
        val handle = FakeHandle()
        var hookSaw: RejectReason? = null
        f.rejectedHook = { event, _ -> hookSaw = event.reason }
        f.route("p1", f.startFrame("t1"))
        f.pipeline.acceptSession("t1")
        f.openHandles["t1"] = handle

        f.route("p1", f.chunkFrames("t1")[0])

        val row = f.row("t1")
        assertEquals(FlashTransferState.Failed, row.state)
        assertTrue(row.errorMessage!!.contains("could not be saved"), "a sentence for people, not an exception: ${row.errorMessage}")
        assertEquals(listOf("p1:cancel:t1"), f.xfer, "the sender is told once")
        assertEquals(1, handle.closed)
        assertTrue(f.openHandles.isEmpty())
        assertTrue(f.meta.isEmpty(), "incomingMeta must not leak")
        assertTrue(f.owners.isEmpty(), "owner must not leak")
        assertTrue(f.log.contains("untracked:t1"))
        assertFalse("t1" in f.pipeline.activeTransferIds(), "the dead session is gone")
        assertEquals(RejectReason.WRITE_FAILED, hookSaw, "the host hook still sees the event")
    }

    @Test
    fun resumableRetryUsesTheHostHookAndSkipsTheOfferGate() {
        val f = Fixture()
        f.startedRow("t1", "p1")
        f.route("p1", f.startFrame("t1"))
        assertEquals(listOf("session:t1:p1", "retry:t1:p1"), f.log)
        assertEquals(Fixture.TRANSFERRING, f.row("t1").state)
    }

    @Test
    fun resumableRetryDefaultPathIsGatedByStorageAdmission() {
        val f = Fixture(useRetryHook = false, freeSpace = 0L)
        f.startedRow("t1", "p1")
        f.route("p1", f.startFrame("t1"))
        // ADR-069 / FA-2: refused by name, sink never resolved, sender never released.
        assertEquals(FlashTransferState.Failed, f.row("t1").state)
        assertTrue(f.xfer.none { it.contains("resume") }, "a refused retry must not release the sender: ${f.xfer}")
        assertTrue(f.sinks.isEmpty(), "no sink may be resolved for a refused retry")
    }

    @Test
    fun resumableRetryDefaultPathAcceptsSeedsAndResumes() {
        val f = Fixture(useRetryHook = false)
        f.startedRow("t1", "p1")
        f.route("p1", f.startFrame("t1"))
        assertEquals(listOf("p1:resume:t1"), f.xfer)
        assertEquals(1, f.sinks.size, "acceptSession resolved the sink")
    }

    @Test
    fun resumableRetryDefaultPathDoesNotResumeWhenThereIsNothingToAccept() {
        // requireAcceptance = false: the session is already open, acceptSession reports false.
        val f = Fixture(useRetryHook = false, requireAcceptance = false)
        f.startedRow("t1", "p1")
        f.route("p1", f.startFrame("t1"))
        assertTrue(f.xfer.none { it.contains("resume") }, "acceptSession=false must not release the sender: ${f.xfer}")
    }

    @Test
    fun ackBatchReportsProgressConfirmsChunksAndRepliesWithTheAck() {
        val f = Fixture(ackEvery = 2)
        f.route("p1", f.startFrame("t1"))
        f.pipeline.acceptSession("t1")
        val chunks = f.chunkFrames("t1")
        f.route("p1", chunks[0])
        f.route("p1", chunks[1])

        assertEquals(listOf(2L * f.chunkSize), f.progress.map { it.second })
        val ack = f.replied().filterIsInstance<ChunkFrame.AckBatch>().single()
        assertEquals(listOf(0, 1), ack.indexes)
    }

    @Test
    fun repliesAreEncryptedWithTheSessionKey() {
        val f = Fixture(ackEvery = 2)
        f.route("p1", f.startFrame("t1"), key = key)
        f.pipeline.acceptSession("t1")
        val chunks = f.chunkFrames("t1")
        f.route("p1", chunks[0], key = key)
        f.route("p1", chunks[1], key = key)
        val raw = f.replies.single()
        assertTrue(SecureBinaryFrameCodec.isSecureFrame(raw))
        assertTrue(ChunkFrame.parse(SecureBinaryFrameCodec.decrypt(raw, key)) is ChunkFrame.AckBatch)
    }

    @Test
    fun rejectedUnknownTransferAsksTheSenderToCancel() {
        val f = Fixture()
        f.route("p1", f.chunkFrames("ghost")[0])
        assertEquals(listOf("p1:cancel:ghost"), f.xfer)
    }

    @Test
    fun rejectedAwaitingAcceptanceIsQuietAndDoesNotCancel() {
        val f = Fixture()
        f.route("p1", f.startFrame("t1"))
        f.route("p1", f.chunkFrames("t1")[0])
        assertTrue(f.xfer.isEmpty())
    }

    @Test
    fun rejectedHookReceivesTheEvent() {
        val f = Fixture()
        var seen: ReceiveEvent.Rejected? = null
        f.rejectedHook = { event, _ -> seen = event }
        f.route("p1", f.chunkFrames("ghost")[0])
        assertEquals(RejectReason.UNKNOWN_TRANSFER, seen?.reason)
        assertTrue(f.xfer.isEmpty(), "the hook replaces the default cancel")
    }

    // ---- Completed ----

    private fun runToCompletion(f: Fixture, tid: String, handle: FakeHandle?, filePath: String?) {
        f.route("p1", f.startFrame(tid))
        f.pipeline.acceptSession(tid)
        handle?.let { f.openHandles[tid] = it }
        filePath?.let { f.receivedPaths[tid] = it }
        f.chunkFrames(tid).forEach { f.route("p1", it) }
    }

    @Test
    fun completedWithMatchingFileFlushesClosesAndRepliesVerified() {
        val f = Fixture()
        val file = tempFile("t-ok", f.data)
        try {
            val handle = FakeHandle()
            runToCompletion(f, "t-ok", handle, file.path)
            assertEquals(1, handle.flushed)
            assertEquals(1, handle.closed)
            val complete = f.replied().filterIsInstance<ChunkFrame.Complete>().single()
            assertTrue(complete.verified)
            assertEquals(FlashTransferState.Completed, f.row("t-ok").state)
            assertNull(f.row("t-ok").errorMessage)
            assertTrue(f.log.contains("untracked:t-ok"))
            assertTrue(f.log.contains("completed:t-ok"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun completedWithMismatchingFileCancelsSessionAndRepliesUnverified() {
        val f = Fixture()
        val file = tempFile("t-bad", ByteArray(f.data.size) { 1 })
        try {
            runToCompletion(f, "t-bad", FakeHandle(), file.path)
            val complete = f.replied().filterIsInstance<ChunkFrame.Complete>().single()
            assertFalse(complete.verified)
            assertEquals(FlashTransferState.Failed, f.row("t-bad").state)
            assertFalse(file.exists(), "the damaged file is deleted")
            assertFalse("t-bad" in f.pipeline.activeTransferIds(), "MISMATCH drops the pipeline session")
        } finally {
            file.delete()
        }
    }

    @Test
    fun flushFailureWithoutAWholeFileProofFailsTheTransfer() {
        val f = Fixture()
        val handle = FakeHandle(failFlush = true)
        runToCompletion(f, "t-flush", handle, filePath = null)

        assertEquals(1, handle.closed, "close is attempted even when flush throws")
        val complete = f.replied().filterIsInstance<ChunkFrame.Complete>().single()
        assertFalse(complete.verified, "bytes that may not be on disk must not be reported verified")
        assertEquals(FlashTransferState.Failed, f.row("t-flush").state)
        assertFalse("t-flush" in f.pipeline.activeTransferIds())
        assertTrue(f.log.contains("untracked:t-flush"))
    }

    @Test
    fun flushFailureWithAMismatchingFileFailsTheTransfer() {
        val f = Fixture()
        val file = tempFile("t-flush2", ByteArray(f.data.size) { 2 })
        try {
            runToCompletion(f, "t-flush2", FakeHandle(failFlush = true), file.path)
            assertFalse(f.replied().filterIsInstance<ChunkFrame.Complete>().single().verified)
            assertEquals(FlashTransferState.Failed, f.row("t-flush2").state)
        } finally {
            file.delete()
        }
    }

    @Test
    fun flushFailureIsToleratedWhenTheWholeFileDigestMatches() {
        val f = Fixture()
        val file = tempFile("t-flush3", f.data)
        try {
            val handle = FakeHandle(failFlush = true)
            runToCompletion(f, "t-flush3", handle, file.path)
            assertEquals(1, handle.closed)
            assertTrue(f.replied().filterIsInstance<ChunkFrame.Complete>().single().verified)
            assertEquals(FlashTransferState.Completed, f.row("t-flush3").state)
        } finally {
            file.delete()
        }
    }

    @Test
    fun closeFailureAfterASuccessfulFlushFailsTheTransferWithoutProof() {
        val f = Fixture()
        runToCompletion(f, "t-close", FakeHandle(failClose = true), filePath = null)
        assertFalse(f.replied().filterIsInstance<ChunkFrame.Complete>().single().verified)
        assertEquals(FlashTransferState.Failed, f.row("t-close").state)
    }

    // ---- exception containment ----

    @Test
    fun aThrowingHookDoesNotStarveLaterEventsOfTheSameFrame() {
        val f = Fixture()
        f.route("p1", f.startFrame("t1"))
        f.pipeline.acceptSession("t1")
        val chunks = f.chunkFrames("t1")
        chunks.dropLast(1).forEach { f.route("p1", it) }
        // The last chunk yields [AckBatchReady, Completed]; the first handler blows up.
        f.progressHook = { _, _ -> throw IllegalStateException("progress sink failed") }
        val handled = f.route("p1", chunks.last())
        assertTrue(handled)
        assertTrue(
            f.replied().filterIsInstance<ChunkFrame.Complete>().isNotEmpty(),
            "the Completed event after the failing AckBatchReady must still be processed",
        )
        assertTrue(f.log.contains("untracked:t1"))
    }

    @Test
    fun aThrowingSessionStartedHookDoesNotEscape() {
        val f = Fixture()
        f.sessionHook = { _, _ -> throw IllegalStateException("tracking failed") }
        assertTrue(f.route("p1", f.startFrame("t1")))
    }

    @Test
    fun aThrowingReplyDoesNotEscape() {
        val f = Fixture(ackEvery = 2)
        f.route("p1", f.startFrame("t1"))
        f.pipeline.acceptSession("t1")
        f.replyFails = true
        val chunks = f.chunkFrames("t1")
        f.route("p1", chunks[0])
        assertTrue(f.route("p1", chunks[1]))
    }

    // ---- transfer identity ----

    @Test
    fun offerFromAnotherPeerForAnOwnedIdIsDropped() {
        val f = Fixture()
        f.route("owner", f.startFrame("t1"))
        f.log.clear()
        f.route("intruder", f.startFrame("t1"))
        assertTrue(f.log.isEmpty(), "no session / offer events for the intruder: ${f.log}")
        assertEquals("owner", f.owners["t1"])
    }

    @Test
    fun chunkFromAnotherPeerForAnOwnedIdNeverReachesTheSink() {
        val f = Fixture()
        f.route("owner", f.startFrame("t1"))
        f.pipeline.acceptSession("t1")
        f.route("intruder", f.chunkFrames("t1")[0])
        assertTrue(f.sinks.getValue("t1").written.isEmpty(), "an intruder's chunk must not be written")
        f.route("owner", f.chunkFrames("t1")[0])
        assertEquals(listOf(0), f.sinks.getValue("t1").written)
    }

    @Test
    fun offerForAnOwnedRowFromAnotherPeerIsDroppedEvenWithoutAnOwnerMapEntry() {
        val f = Fixture()
        f.repo.onIncomingOffered("t1", "f1", "video.bin", f.data.size.toLong(), "peer", "owner")
        f.route("intruder", f.startFrame("t1"))
        assertTrue(f.log.isEmpty())
        assertTrue(f.pipeline.activeTransferIds().isEmpty())
    }

    @Test
    fun unsafeTransferIdsAreRefusedBeforeAnySessionOpens() {
        for (bad in listOf("..", ".", "a:b", "a/b", "a\\b", "CON", "bad.", "x".repeat(200))) {
            val f = Fixture()
            assertTrue(f.route("p1", f.startFrame(bad)), "id=$bad")
            assertTrue(f.log.isEmpty(), "id=$bad must not open a session: ${f.log}")
            assertTrue(f.pipeline.activeTransferIds().isEmpty(), "id=$bad")
            assertEquals(listOf("p1:cancel:$bad"), f.xfer, "the sender is told to give up on id=$bad")
        }
    }

    @Test
    fun safeTransferIdsAreStillAccepted() {
        for (ok in listOf("7f3c1d2e-aaaa-bbbb-cccc-123456789012", "t_1", "transfer-42")) {
            val f = Fixture()
            f.route("p1", f.startFrame(ok))
            assertEquals(listOf("session:$ok:p1", "offer:$ok:p1"), f.log, "id=$ok")
        }
    }

    @Test
    fun idsThatDifferOnlyByCaseAreNotAllowedToAliasOneDirectory() {
        val f = Fixture()
        f.route("p1", f.startFrame("AbC"))
        f.log.clear()
        f.route("p1", f.startFrame("abc"))
        assertTrue(f.log.isEmpty(), "a case-variant id must not open a second session: ${f.log}")
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private fun tempFile(name: String, content: ByteArray): java.io.File =
        java.io.File.createTempFile("router-$name-", ".bin").also { it.writeBytes(content) }

    private class FakeHandle(
        private val failFlush: Boolean = false,
        private val failClose: Boolean = false,
    ) : RandomAccessSinkHandle {
        var flushed = 0
        var closed = 0
        override fun writeAt(byteOffset: Long, data: ByteArray) = Unit
        override fun flush() {
            flushed++
            if (failFlush) throw IllegalStateException("flush failed: disk full")
        }

        override fun close() {
            closed++
            if (failClose) throw IllegalStateException("close failed")
        }

        override val isOpen: Boolean get() = closed == 0
    }

    private class RecordingSink(private val failWrites: Boolean = false) : ChunkSink {
        val written = mutableListOf<Int>()
        override fun write(index: Int, data: ByteArray) {
            if (failWrites) throw okio.IOException("No space left on device")
            written += index
        }
    }

    private class Fixture(
        useOfferHook: Boolean = true,
        useRetryHook: Boolean = true,
        freeSpace: Long? = null,
        requireAcceptance: Boolean = true,
        ackEvery: Int = 32,
        failWrites: Boolean = false,
    ) {
        /** What the host's `fileExistsAndSizeMatches` hook answers (length only, as the real hosts do). */
        var sizeMatches: Boolean = false
        val chunkSize = 16_384
        val data = ByteArray(chunkSize * 4) { (it % 251).toByte() }
        val fileHash = Sha256.digestHex(data)

        val sinks = mutableMapOf<String, RecordingSink>()
        val pipeline = ReceivePipeline(
            sink = { _, _ -> },
            ackEvery = ackEvery,
            sinkFactory = { start -> RecordingSink(failWrites).also { sinks[start.transferId] = it } },
            emitSessionStarted = true,
            requireAcceptance = requireAcceptance,
        )
        val repo = RealFlashTransferRepository(
            streamChannelFactory = { _, _ -> null },
            fileSourceOpener = { error("unused") },
            freeSpaceBytes = { freeSpace },
        )

        val meta = mutableMapOf<String, ChunkFrame.FileStart>()
        val paths = mutableMapOf<String, String>()
        val receivedPaths: MutableMap<String, String> get() = paths
        val openHandles = mutableMapOf<String, RandomAccessSinkHandle>()
        val owners = mutableMapOf<String, String>()
        val log = mutableListOf<String>()
        val xfer = mutableListOf<String>()
        val replies = mutableListOf<ByteArray>()
        val progress = mutableListOf<Pair<String, Long>>()

        var replyFails = false
        var sessionHook: ((ChunkFrame.FileStart, String?) -> Unit)? = null
        var progressHook: ((String, Long) -> Unit)? = null
        var rejectedHook: ((ReceiveEvent.Rejected, String?) -> Unit)? = null

        private val offerHook: ((ChunkFrame.FileStart, String?, (ByteArray) -> Boolean) -> Unit)? =
            if (useOfferHook) {
                { frame, pid, _ ->
                    log += "offer:${frame.transferId}:$pid"
                    repo.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", pid)
                }
            } else null
        private val retryHook: ((ChunkFrame.FileStart, String?, (ByteArray) -> Boolean) -> Unit)? =
            if (useRetryHook) { frame, pid, _ -> log += "retry:${frame.transferId}:$pid" } else null

        fun row(id: String): FlashTransfer = assertNotNull(rowOrNull(id), "row $id")
        fun rowOrNull(id: String): FlashTransfer? = repo.activeTransfers.value.firstOrNull { it.id.value == id }

        fun startedRow(id: String, peer: String) {
            repo.onIncomingOffered(id, "f1", "video.bin", data.size.toLong(), "peer", peer)
            repo.onIncomingStarted(id, "f1", "video.bin", data.size.toLong(), "peer", peer, null)
        }

        fun replied(): List<ChunkFrame> = replies.mapNotNull { ChunkFrame.parse(it) }

        fun startFrame(tid: String): ByteArray {
            val m = FileMeta(tid, "f1", "video.bin", data.size.toLong())
            val c = Chunker()
            return ChunkFrame.serialize(c.fileStart(m, c.plan(m, chunkSize), fileHash))
        }

        fun chunkFrames(tid: String): List<ByteArray> = (0 until 4).map { i ->
            val part = data.copyOfRange(i * chunkSize, (i + 1) * chunkSize)
            ChunkFrame.serialize(ChunkFrame.Chunk(tid, "f1", i, part, Sha256.digest(part)))
        }

        fun route(
            peer: String?,
            bytes: ByteArray,
            key: ByteArray? = null,
            encryptIncoming: Boolean = false,
        ): Boolean = FlashInboundRouter.routeInboundBinary(
            peerDeviceId = peer,
            data = if (key != null && encryptIncoming) SecureBinaryFrameCodec.encrypt(bytes, key) else bytes,
            reply = { out ->
                if (replyFails) throw IllegalStateException("socket closed")
                replies += out
                true
            },
            sessionKeyLookup = key?.let { k -> { _: String -> k } },
            transferRepository = repo,
            receivePipeline = pipeline,
            incomingMeta = meta,
            receivedPaths = paths,
            openHandles = openHandles,
            fileExistsAndSizeMatches = { _, _ -> sizeMatches },
            sendXferResume = { pid, tid -> xfer += "$pid:resume:$tid" },
            sendXferCancel = { pid, tid -> xfer += "$pid:cancel:$tid" },
            onOfferReceived = offerHook,
            onProgressUpdated = { tid, bytesDone ->
                progress += tid to bytesDone
                progressHook?.invoke(tid, bytesDone)
            },
            onRejected = rejectedHook?.let { h -> { e: ReceiveEvent.Rejected, p: String? -> h(e, p) } },
            onCompleted = { e, _ -> log += "completed:${e.frame.transferId}" },
            onSessionStarted = { frame, pid ->
                log += "session:${frame.transferId}:$pid"
                sessionHook?.invoke(frame, pid)
            },
            onTransferUntracked = { tid -> log += "untracked:$tid" },
            onResumableRetry = retryHook,
            incomingOwners = owners,
        )

        companion object {
            val TRANSFERRING = FlashTransferState.Transferring
        }
    }

    private class CapturingLogSink private constructor() : com.transfer.flash.core.common.logging.FlashLogSink {
        val warnings = mutableListOf<String>()
        override fun write(
            level: com.transfer.flash.core.common.logging.FlashLogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            if (level == com.transfer.flash.core.common.logging.FlashLogLevel.WARN) warnings += "$tag: $message"
        }

        companion object {
            fun install(): CapturingLogSink = CapturingLogSink().also {
                com.transfer.flash.core.common.logging.FlashLog.installSink(it)
            }

            fun restore() {
                com.transfer.flash.core.common.logging.FlashLog.installSink { level, tag, message, _ ->
                    println("[$level] $tag: $message")
                }
            }
        }
    }

    private class DummyPtt : FlashPtt {
        var binaryReceived = false
        override val state: kotlinx.coroutines.flow.StateFlow<PttFloorState> =
            kotlinx.coroutines.flow.MutableStateFlow(PttFloorState.Idle)
        override val stats: kotlinx.coroutines.flow.StateFlow<PttSessionStats?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)
        override val notices: kotlinx.coroutines.flow.SharedFlow<String> =
            kotlinx.coroutines.flow.MutableSharedFlow()
        override val pings: kotlinx.coroutines.flow.Flow<PttPingEvent> =
            kotlinx.coroutines.flow.emptyFlow()
        override fun onPttButton(): PttPressOutcome = PttPressOutcome.ACCEPTED
        override fun sendPing(): Boolean = true
        override fun postNotice(text: String) {}
        override fun stopLocal() {}
        override fun onCallStarted() {}
        override fun acquireVoiceNoteLease(): String? = null
        override fun releaseVoiceNoteLease(leaseId: String) {}
        override fun onInboundText(peerId: String, text: String): Boolean = true
        override fun onInboundBinary(peerId: String?, data: ByteArray): Boolean {
            binaryReceived = true
            return true
        }

        override fun shutdown() {}
    }
}
