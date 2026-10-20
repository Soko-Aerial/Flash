@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.security.enterprise.HubFileHasher
import com.transfer.flash.core.security.enterprise.HubFileSealing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HF-C4: the resumable engine against [FakeHubFileServer] on virtual time. "Process death" is a new [HubFileTransfers]
 * over the same storage; "network drop" is [FakeHubFilesApi.offline]; a half-open socket is [FakeHubFilesApi.stallNext].
 */
class HubFileTransfersTest {

    private val hub = "https://hub.example"
    private val chunk = 64 // ciphertext; plaintext 48 per chunk, so a 1000-byte file is 21 chunks
    private val server = FakeHubFileServer(maxFileBytes = 8_192)
    private val senderApi = FakeHubFilesApi(server, "dev-a")
    private val recipientApi = FakeHubFilesApi(server, "dev-b")
    private val senderDisk = MemoryHubFileStorage()
    private val recipientDisk = MemoryHubFileStorage()
    private val plain = Random(7).nextBytes(1000)

    private fun engine(api: HubFilesApi, disk: HubFileStorage, policy: HubTransferPolicy = HubTransferPolicy()) =
        HubFileTransfers(api, disk, policy, Dispatchers.Unconfined, Random(1))

    private fun sender() = engine(senderApi, senderDisk)
    private fun recipient() = engine(recipientApi, recipientDisk)

    private fun request(id: String, bytes: ByteArray = plain, to: String = "dev-b") = HubUploadRequest(
        hubUrl = hub, fileId = id, source = ByteArrayHubFileSource(bytes), toDeviceId = to,
        maxFileBytes = server.maxFileBytes, cipherChunkBytes = chunk,
    )

    private fun sha(bytes: ByteArray) = HubFileHasher().also { it.update(bytes) }.digestHex()

    private suspend fun uploaded(id: String = HubFileSealing.newFileId(), bytes: ByteArray = plain): HubFileTicket {
        val outcome = sender().upload(request(id, bytes))
        return assertIs<HubUploadOutcome.Done>(outcome).ticket
    }

    private suspend fun TestScope.downloaded(ticket: HubFileTicket): ByteArray {
        val outcome = recipient().download(HubDownloadRequest(hub, ticket, senderDeviceId = "dev-a", plainSha256 = sha(plain)))
        assertIs<HubDownloadOutcome.Done>(outcome)
        return recipientDisk.finished.getValue(ticket.fileId)
    }

    // ----- the plain path ---------------------------------------------------------------------------------------

    @Test
    fun aFileGoesThroughTheHubSealedAndComesOutTheSame() = runTest {
        val ticket = uploaded()
        assertEquals(21, ticket.chunkCount)
        val stored = server.files.getValue(ticket.fileId)
        assertEquals(FileStates.READY, stored.state)
        val window = plain.copyOfRange(100, 116)
        assertFalse(stored.chunks.values.any { c -> (0..c.size - window.size).any { c.copyOfRange(it, it + window.size).contentEquals(window) } },
            "the hub must never hold a run of plaintext")
        assertContentEquals(plain, downloaded(ticket))
        assertTrue(recipientDisk.stateIds(HubTransferDirection.DOWNLOAD).isEmpty(), "a finished download leaves no record")
    }

    @Test
    fun aFileOfExactlyTheLimitIsAcceptedAndOneByteMoreIsNot() = runTest {
        val exact = Random(3).nextBytes(server.maxFileBytes.toInt())
        assertIs<HubUploadOutcome.Done>(sender().upload(request(HubFileSealing.newFileId(), exact)))
        val over = sender().upload(request(HubFileSealing.newFileId(), Random(3).nextBytes(server.maxFileBytes.toInt() + 1)))
        assertEquals(HubFileError.TooLarge(server.maxFileBytes), assertIs<HubUploadOutcome.Failed>(over).error)
        assertEquals(1, server.files.size, "nothing was created for the refused file")
    }

    @Test
    fun emptyFilesAndHubsWithoutFilesAreRefusedBeforeAnyRequest() = runTest {
        assertEquals(HubFileError.Empty, (sender().upload(request(HubFileSealing.newFileId(), ByteArray(0))) as HubUploadOutcome.Failed).error)
        val noFiles = request(HubFileSealing.newFileId()).copy(maxFileBytes = 0)
        assertEquals(HubFileError.HubTooOld, (sender().upload(noFiles) as HubUploadOutcome.Failed).error)
        assertTrue(senderApi.calls.isEmpty())
    }

    // ----- upload resume (situation 1) --------------------------------------------------------------------------

    @Test
    fun anUploadCutByTheNetworkContinuesFromTheLastChunkOnTheNextRun() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && (senderApi.calls[route] ?: 0) > 5) senderApi.offline = true }
        val first = sender().upload(request(id))
        val cut = assertIs<HubUploadOutcome.Interrupted>(first)
        assertEquals(HubFileError.Offline, cut.error)
        assertEquals(5, cut.progress.chunksDone)
        assertEquals(listOf(id), sender().pending(HubTransferDirection.UPLOAD), "the record survives for the next run")

        // Back on another network, in a new process.
        senderApi.offline = false
        senderApi.beforeCall = {}
        senderApi.calls.clear()
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertEquals(21 - 5, senderApi.calls[FakeHubFilesApi.ROUTE_PUT], "only the chunks the hub lacks are sent")
        assertEquals(0, senderApi.calls[FakeHubFilesApi.ROUTE_CREATE] ?: 0, "a created upload is not created again")
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun aChunkTheHubStoredButTheDeviceDidNotRecordIsResentIdenticallyAfterAProcessDeath() = runTest {
        val id = HubFileSealing.newFileId()
        // The 3rd chunk lands on the hub, then the disk refuses to save the bit: on disk only chunks 0 and 1 are done.
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 3) senderDisk.failWrites = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        assertEquals(3, server.files.getValue(id).chunks.size)
        senderDisk.failWrites = false
        senderApi.beforeCall = {}

        // A changed key would seal chunk 2 differently and the hub would answer 409; identical bytes answer 204.
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun anUploadKilledMidRequestResumesWithTheSameKey() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 9) senderApi.stallNext = 1 }
        val job = launch { sender().upload(request(id)) }
        runCurrent()
        job.cancelAndJoin() // the process dies while chunk 8 is in flight
        senderApi.beforeCall = {}
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun aSourceThatChangedBetweenRunsEndsTheUploadInsteadOfMixingVersions() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 4) senderApi.offline = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        senderApi.offline = false
        senderApi.beforeCall = {}
        val edited = plain.copyOf().also { it[999] = (it[999] + 1).toByte() }
        val outcome = engine(senderApi, senderDisk).upload(request(id, edited))
        assertEquals(HubFileError.SourceChanged, assertIs<HubUploadOutcome.Failed>(outcome).error)
        assertNull(server.files[id], "the half-upload is withdrawn from the hub")
        assertTrue(senderDisk.stateIds(HubTransferDirection.UPLOAD).isEmpty())
    }

    @Test
    fun anUploadSweptByTheHubStartsAgainUnderTheSameId() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 6) senderApi.offline = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        server.sweep(id) // the 24 h upload window passed while the phone was away (situation 4)
        senderApi.offline = false
        senderApi.beforeCall = {}
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertEquals(id, done.ticket.fileId)
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun chunksTheHubLostAreSentAgain() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 8) senderApi.offline = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        server.loseChunks(id, 0, 3)
        senderApi.offline = false
        senderApi.beforeCall = {}
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun aLostUploadRecordWithdrawsTheOrphanAndStartsOver() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 4) senderApi.offline = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        senderDisk.corruptState(HubTransferDirection.UPLOAD, id, "flash-hub-file-state/1\nkind=upl".encodeToByteArray())
        senderApi.offline = false
        senderApi.beforeCall = {}
        val done = assertIs<HubUploadOutcome.Done>(engine(senderApi, senderDisk).upload(request(id)))
        assertContentEquals(plain, downloaded(done.ticket))
    }

    @Test
    fun anUploadIsNotResumedAgainstAnotherHub() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.offline = true
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        senderApi.offline = false
        val moved = request(id).copy(hubUrl = "https://other-hub.example")
        assertEquals(HubFileError.NotThisHub, (sender().upload(moved) as HubUploadOutcome.Failed).error)
    }

    @Test
    fun twoCallersOfTheSameUploadDoNotRace() = runTest {
        val shared = sender()
        val id = HubFileSealing.newFileId()
        // The first caller is parked mid-chunk so the second really arrives while the upload runs.
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 2) senderApi.stallNext = 1 }
        val outcomes = listOf(async { shared.upload(request(id)) }, async { shared.upload(request(id)) }).awaitAll()
        assertTrue(outcomes.all { it is HubUploadOutcome.Done })
        assertEquals(1, senderApi.calls[FakeHubFilesApi.ROUTE_CREATE], "the second caller continued the first one's upload")
        assertEquals(1, outcomes.map { (it as HubUploadOutcome.Done).ticket.key.encode() }.toSet().size, "one key, not two")
    }

    @Test
    fun aCancelledUploadIsGoneFromTheHubAndTheDevice() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_PUT && senderApi.calls[route] == 3) senderApi.offline = true }
        assertIs<HubUploadOutcome.Interrupted>(sender().upload(request(id)))
        senderApi.offline = false
        assertIs<HubFileResult.Ok<Unit>>(sender().cancelUpload(hub, id))
        assertIs<HubFileResult.Ok<Unit>>(sender().cancelUpload(hub, id), "twice is fine")
        assertNull(server.files[id])
        assertTrue(senderDisk.stateIds(HubTransferDirection.UPLOAD).isEmpty())
    }

    // ----- retries, timeouts, rate limits -----------------------------------------------------------------------

    @Test
    fun aHalfOpenSocketCostsOneTimeoutNotTheTransfer() = runTest {
        val ticket = uploaded()
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && recipientApi.calls[route] == 4) recipientApi.stallNext = 1 }
        assertContentEquals(plain, downloaded(ticket))
        assertTrue(currentTime >= HubTransferPolicy().requestTimeoutMs, "the stalled request ran into the timeout")
    }

    @Test
    fun theHubsRetryAfterIsHonoured() = runTest {
        senderApi.failNext.addLast(HubFileError.RateLimited(7))
        val start = currentTime
        uploaded()
        assertTrue(currentTime - start >= 7_000L)
    }

    @Test
    fun aHubAskingForAnAbsurdWaitIsHeardForTwoMinutesAtMost() = runTest {
        senderApi.failNext.addLast(HubFileError.RateLimited(100_000))
        val start = currentTime
        uploaded()
        val waited = currentTime - start
        assertTrue(waited in 120_000L..125_000L, "waited $waited ms")
    }

    @Test
    fun aDeadNetworkStopsTheRunAfterTheBudgetAndKeepsTheRecord() = runTest {
        val id = HubFileSealing.newFileId()
        senderApi.offline = true
        val policy = HubTransferPolicy(maxConsecutiveFailures = 4)
        assertIs<HubUploadOutcome.Interrupted>(engine(senderApi, senderDisk, policy).upload(request(id)))
        assertEquals(4, senderApi.calls[FakeHubFilesApi.ROUTE_CREATE])
        assertEquals(listOf(id), sender().pending(HubTransferDirection.UPLOAD))
    }

    // ----- download resume (situation 2) and trust --------------------------------------------------------------

    @Test
    fun aDownloadCutByTheNetworkFetchesOnlyTheMissingChunksNextTime() = runTest {
        val ticket = uploaded()
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && (recipientApi.calls[route] ?: 0) > 7) recipientApi.offline = true }
        val cut = assertIs<HubDownloadOutcome.Interrupted>(recipient().download(HubDownloadRequest(hub, ticket)))
        assertEquals(7, cut.progress.chunksDone)
        assertEquals(7L * 48, cut.progress.bytesDone)
        recipientApi.offline = false
        recipientApi.beforeCall = {}
        recipientApi.calls.clear()
        assertContentEquals(plain, downloaded(ticket))
        assertEquals(21 - 7, recipientApi.calls[FakeHubFilesApi.ROUTE_READ])
    }

    @Test
    fun aTamperedChunkIsRefusedAndNothingIsHandedOver() = runTest {
        val ticket = uploaded()
        server.tamper(ticket.fileId, 2)
        val outcome = recipient().download(HubDownloadRequest(hub, ticket))
        assertEquals(HubFileError.Integrity, assertIs<HubDownloadOutcome.Failed>(outcome).error)
        assertTrue(recipientDisk.finished.isEmpty())
        assertNull(recipientDisk.part(ticket.fileId))
    }

    @Test
    fun aHubThatDescribesAnotherFileIsNotBelieved() = runTest {
        val ticket = uploaded()
        val stored = server.files.getValue(ticket.fileId)
        server.files[ticket.fileId] = FakeHubFileServer.Stored(stored.request.copy(sha256 = "b".repeat(64)), stored.sender, stored.state, stored.chunks)
        val outcome = recipient().download(HubDownloadRequest(hub, ticket))
        assertEquals(HubFileError.Integrity, assertIs<HubDownloadOutcome.Failed>(outcome).error)
        assertEquals(0, recipientApi.calls[FakeHubFilesApi.ROUTE_READ] ?: 0, "no chunk is fetched for a file that is not the ticket's")
    }

    @Test
    fun aFileFromSomeoneOtherThanTheMessagesSenderIsRefused() = runTest {
        val ticket = uploaded()
        val outcome = recipient().download(HubDownloadRequest(hub, ticket, senderDeviceId = "dev-x"))
        assertEquals(HubFileError.Integrity, assertIs<HubDownloadOutcome.Failed>(outcome).error)
    }

    @Test
    fun aWrongPlaintextHashInTheMessageFailsTheDownload() = runTest {
        val ticket = uploaded()
        val outcome = recipient().download(HubDownloadRequest(hub, ticket, plainSha256 = "c".repeat(64)))
        assertEquals(HubFileError.Integrity, assertIs<HubDownloadOutcome.Failed>(outcome).error)
        assertTrue(recipientDisk.finished.isEmpty())
    }

    @Test
    fun aDamagedLocalCopyIsFetchedOnceMoreThenRefused() = runTest {
        val ticket = uploaded()
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && (recipientApi.calls[route] ?: 0) > 10) recipientApi.offline = true }
        assertIs<HubDownloadOutcome.Interrupted>(recipient().download(HubDownloadRequest(hub, ticket)))
        val part = assertNotNull(recipientDisk.part(ticket.fileId)).copyOf()
        part[5] = (part[5] + 1).toByte() // the disk changed a byte of a chunk already marked done
        recipientDisk.replacePart(ticket.fileId, part)
        recipientApi.offline = false
        recipientApi.beforeCall = {}
        recipientApi.calls.clear()
        assertContentEquals(plain, downloaded(ticket))
        assertEquals((21 - 10) + 21, recipientApi.calls[FakeHubFilesApi.ROUTE_READ], "one full re-download")
    }

    @Test
    fun withoutAPlaintextHashTheSendersCiphertextHashStillCatchesADamagedLocalCopy() = runTest {
        val ticket = uploaded()
        val request = HubDownloadRequest(hub, ticket) // an older message format: no plaintext hash
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && (recipientApi.calls[route] ?: 0) > 10) recipientApi.offline = true }
        assertIs<HubDownloadOutcome.Interrupted>(recipient().download(request))
        val part = assertNotNull(recipientDisk.part(ticket.fileId)).copyOf()
        part[60] = (part[60] + 1).toByte()
        recipientDisk.replacePart(ticket.fileId, part)
        recipientApi.offline = false
        recipientApi.beforeCall = {}
        recipientApi.calls.clear()
        assertIs<HubDownloadOutcome.Done>(recipient().download(request))
        assertContentEquals(plain, recipientDisk.finished.getValue(ticket.fileId))
        assertEquals((21 - 10) + 21, recipientApi.calls[FakeHubFilesApi.ROUTE_READ], "the re-seal check found the damage")
    }

    @Test
    fun aChunkIsMarkedDoneOnlyAfterItsBytesAreWritten() = runTest {
        val ticket = uploaded()
        // The 6th chunk arrives, but the disk refuses to write it.
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && recipientApi.calls[route] == 6) recipientDisk.failNextPartWrites = 1 }
        val cut = assertIs<HubDownloadOutcome.Interrupted>(recipient().download(HubDownloadRequest(hub, ticket)))
        assertEquals(HubFileError.LocalStorage("write"), cut.error)
        assertEquals(5, cut.progress.chunksDone, "the refused chunk is not counted")
        recipientApi.beforeCall = {}
        recipientApi.calls.clear()
        assertContentEquals(plain, downloaded(ticket))
        assertEquals(21 - 5, recipientApi.calls[FakeHubFilesApi.ROUTE_READ], "no hole, so no whole re-download")
    }

    @Test
    fun aFullDiskPausesTheDownloadAndFreedSpaceContinuesIt() = runTest {
        val ticket = uploaded()
        recipientDisk.usable = 1024
        val outcome = recipient().download(HubDownloadRequest(hub, ticket))
        assertIs<HubFileError.NoSpace>(assertIs<HubDownloadOutcome.Interrupted>(outcome).error)
        recipientDisk.usable = Long.MAX_VALUE
        assertContentEquals(plain, downloaded(ticket))
    }

    @Test
    fun aTornDownloadRecordStartsTheDownloadOver() = runTest {
        val ticket = uploaded()
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && (recipientApi.calls[route] ?: 0) > 3) recipientApi.offline = true }
        assertIs<HubDownloadOutcome.Interrupted>(recipient().download(HubDownloadRequest(hub, ticket)))
        recipientDisk.corruptState(HubTransferDirection.DOWNLOAD, ticket.fileId, ByteArray(40) { 0x41 })
        recipientApi.offline = false
        recipientApi.beforeCall = {}
        recipientApi.calls.clear()
        assertContentEquals(plain, downloaded(ticket))
        assertEquals(21, recipientApi.calls[FakeHubFilesApi.ROUTE_READ])
    }

    @Test
    fun someoneWhoIsNotARecipientCannotFetchTheFile() = runTest {
        val ticket = uploaded()
        val stranger = engine(FakeHubFilesApi(server, "dev-c"), MemoryHubFileStorage())
        assertEquals(HubFileError.Forbidden, (stranger.download(HubDownloadRequest(hub, ticket)) as HubDownloadOutcome.Failed).error)
    }

    @Test
    fun anExpiredFileEndsTheDownloadForGood() = runTest {
        val ticket = uploaded()
        server.sweep(ticket.fileId)
        val outcome = recipient().download(HubDownloadRequest(hub, ticket))
        assertEquals(HubFileError.NotFound, assertIs<HubDownloadOutcome.Failed>(outcome).error)
        assertTrue(recipientDisk.stateIds(HubTransferDirection.DOWNLOAD).isEmpty())
    }

    @Test
    fun aDiscardedDownloadLeavesNothingBehind() = runTest {
        val ticket = uploaded()
        recipientApi.beforeCall = { route -> if (route == FakeHubFilesApi.ROUTE_READ && (recipientApi.calls[route] ?: 0) > 3) recipientApi.offline = true }
        assertIs<HubDownloadOutcome.Interrupted>(recipient().download(HubDownloadRequest(hub, ticket)))
        recipient().discardDownload(ticket.fileId)
        assertNull(recipientDisk.part(ticket.fileId))
        assertTrue(recipient().pending(HubTransferDirection.DOWNLOAD).isEmpty())
    }

    @Test
    fun aTicketWhoseNumbersDoNotAddUpIsRefusedWithoutARequest() = runTest {
        val ticket = uploaded()
        for (bad in listOf(ticket.copy(chunkCount = 20), ticket.copy(cipherChunkBytes = 16), ticket.copy(cipherSha256 = "XYZ"), ticket.copy(fileId = "../x"))) {
            assertIs<HubDownloadOutcome.Failed>(recipient().download(HubDownloadRequest(hub, bad)))
        }
        assertTrue(recipientApi.calls.isEmpty())
    }

    @Test
    fun progressIsSavedAfterEveryChunk() = runTest {
        uploaded()
        // One record for the start, one after create, one per chunk, one for completion.
        assertEquals(1 + 1 + 21 + 1, senderDisk.stateWrites)
    }
}
