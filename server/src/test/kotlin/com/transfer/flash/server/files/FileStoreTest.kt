package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.server.storage.ServerDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HF-S2: the sealed file store. Every rule the routes rely on is proven here first: idempotency, the chunk arithmetic,
 * the quotas inside the writing transaction, authorization, retention and the consistency pass.
 */
class FileStoreTest {

    private lateinit var db: ServerDatabase
    private lateinit var root: File
    private var now: Long = 1_700_000_000_000L
    private var limits: FileLimits = FileLimits()
    private val groups: MutableMap<String, List<String>> = mutableMapOf()
    private lateinit var store: FileStore

    private val sender = "device-sender"
    private val recipient = "device-recipient"

    @BeforeTest
    fun setUp() {
        db = ServerDatabase.inMemory()
        root = Files.createTempDirectory("flash-files-test").toFile()
        store = FileStore.open(
            db = db,
            rootDir = root,
            clock = { now },
            limits = { limits },
            groupMembers = { id -> groups[id] },
            knownDevice = { it != "device-unknown" },
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    // ----- helpers ----------------------------------------------------------------------------------------------

    private fun id(n: Int = 1): String = "%032x".format(n)

    private fun sha(bytes: ByteArray): String = FileStore.sha256Hex(bytes)

    private fun chunk(size: Int = 1024, fill: Byte = 7): ByteArray = ByteArray(size) { fill }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ok(outcome: FileOutcome<T>): T =
        (outcome as? FileOutcome.Ok<T>)?.value ?: fail("expected Ok, got $outcome")

    private fun refused(outcome: FileOutcome<*>): FileOutcome.Refused =
        outcome as? FileOutcome.Refused ?: fail("expected Refused, got $outcome")

    private fun createRequest(
        fileId: String = id(),
        totalBytes: Long = 2048,
        chunkSize: Int = 1024,
        chunkCount: Int = 2,
        hash: String = sha(ByteArray(2048) { 7 }),
        toDeviceId: String? = recipient,
        groupId: String? = null,
    ) = CreateFileRequestDto(
        fileId = fileId,
        totalBytes = totalBytes,
        chunkSize = chunkSize,
        chunkCount = chunkCount,
        sha256 = hash,
        toDeviceId = toDeviceId,
        groupId = groupId,
    )

    private fun makeReady(fileId: String = id(), chunks: Int = 2, chunkSize: Int = 1024): String {
        val bytes = chunk(chunkSize)
        val request = createRequest(
            fileId = fileId,
            totalBytes = chunkSize.toLong() * chunks,
            chunkSize = chunkSize,
            chunkCount = chunks,
            hash = sha(ByteArray(chunkSize * chunks) { 7 }),
        )
        ok(store.create(sender, request))
        for (i in 0 until chunks) {
            ok(store.putChunk(sender, fileId, i, bytes, sha(bytes)))
        }
        ok(store.complete(sender, fileId))
        return fileId
    }

    // ----- happy path -------------------------------------------------------------------------------------------

    @Test
    fun createUploadCompleteAndDownloadByteIdentically() {
        val first = chunk(1024, 1)
        val second = chunk(1024, 2)
        val request = createRequest(hash = sha(first + second))

        val created = store.create(sender, request)
        val ticket = ok(created) as FileTicketDto
        assertEquals(request.fileId, ticket.fileId)
        assertEquals(HubContract.MAX_CHUNK_BYTES, ticket.maxChunkBytes)
        assertTrue(ticket.uploadExpiresAtMs > now)

        ok(store.putChunk(sender, request.fileId, 0, first, sha(first)))
        val secondPut = ok(store.putChunk(sender, request.fileId, 1, second, null))
        assertEquals(2, secondPut.receivedChunks)

        val completed = ok(store.complete(sender, request.fileId)) as FileStatusDto
        assertEquals(FileStates.READY, completed.state)
        assertEquals(request.sha256, completed.sha256)
        assertEquals(listOf(recipient), completed.recipients)
        assertTrue(completed.expiresAtMs > now)

        assertTrue(first.contentEquals(ok(store.readChunk(recipient, request.fileId, 0))))
        assertTrue(second.contentEquals(ok(store.readChunk(recipient, request.fileId, 1))))
    }

    @Test
    fun createIsIdempotentForIdenticalMetadataAndConflictsOtherwise() {
        val request = createRequest()
        ok(store.create(sender, request))
        ok(store.create(sender, request))

        val changed = request.copy(sha256 = sha(ByteArray(2048) { 9 }))
        assertEquals(HubErrorCodes.ERR_CONFLICT, refused(store.create(sender, changed)).code)

        // Another device may not take over an id that is already in use.
        assertEquals(HubErrorCodes.ERR_CONFLICT, refused(store.create("device-other", request)).code)
    }

    @Test
    fun aChunkReplayWithTheSameBytesStoresNothingAndDifferentBytesConflict() {
        val request = createRequest()
        ok(store.create(sender, request))
        val bytes = chunk()

        assertTrue(ok(store.putChunk(sender, request.fileId, 0, bytes, sha(bytes))).newChunk)

        val replay = ok(store.putChunk(sender, request.fileId, 0, bytes, sha(bytes)))
        assertFalse(replay.newChunk)
        assertEquals(1, replay.receivedChunks)

        assertEquals(
            HubErrorCodes.ERR_CONFLICT,
            refused(store.putChunk(sender, request.fileId, 0, chunk(1024, 3), null)).code,
        )

        // The stored bytes are still the first ones.
        assertEquals(1, (ok(store.status(sender, request.fileId)) as FileStatusDto).receivedChunks)
    }

    // ----- validation --------------------------------------------------------------------------------------------

    @Test
    fun theChunkArithmeticIsEnforced() {
        assertEquals(
            HubErrorCodes.ERR_BAD_REQUEST,
            refused(store.create(sender, createRequest(totalBytes = 2048, chunkSize = 1024, chunkCount = 3))).code,
        )
        assertEquals(
            HubErrorCodes.ERR_TOO_LARGE,
            refused(
                store.create(
                    sender,
                    createRequest(totalBytes = 4L * 1024 * 1024, chunkSize = 2 * 1024 * 1024, chunkCount = 2),
                ),
            ).code,
        )
        assertEquals(
            HubErrorCodes.ERR_BAD_REQUEST,
            refused(store.create(sender, createRequest(totalBytes = 0, chunkSize = 1024, chunkCount = 0))).code,
        )
    }

    @Test
    fun aFileAboveTheLimitIsRefused() {
        limits = limits.copy(maxFileBytes = 2048)
        // 2048 plaintext bytes sealed in 3 chunks are 2048 + 3 * 16 = 2096 ciphertext bytes: one more is refused.
        val tooBig = store.create(
            sender,
            createRequest(fileId = id(2), totalBytes = 2097, chunkSize = 1024, chunkCount = 3),
        )
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, refused(tooBig).code)
    }

    @Test
    fun theLimitIsThePlaintextSizeSoTheSealingTagsDoNotCountAgainstIt() {
        limits = limits.copy(maxFileBytes = 2048)
        val exact = store.create(
            sender,
            createRequest(
                fileId = id(3),
                totalBytes = 2048L + 3 * HubContract.SEALED_CHUNK_OVERHEAD_BYTES,
                chunkSize = 1024,
                chunkCount = 3,
            ),
        )
        assertTrue(exact is FileOutcome.Ok, "a file of exactly the limit, plus its tags, must be accepted: $exact")
    }

    @Test
    fun anIdThatIsNotPureLowerHexCannotBecomeAPath() {
        for (bad in listOf("../etc/passwd", "ABCDEF0123456789ABCDEF0123456789", "abc", "0".repeat(31) + "/", "0".repeat(33))) {
            assertEquals(HubErrorCodes.ERR_BAD_REQUEST, refused(store.create(sender, createRequest(fileId = bad))).code, "id $bad")
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("etc") })
    }

    @Test
    fun aChunkOfTheWrongSizeOrHashIsRefused() {
        val request = createRequest()
        ok(store.create(sender, request))

        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, refused(store.putChunk(sender, request.fileId, 0, chunk(512), null)).code)
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, refused(store.putChunk(sender, request.fileId, 0, chunk(2048), null)).code)
        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, refused(store.putChunk(sender, request.fileId, 5, chunk(), null)).code)
        assertEquals(
            HubErrorCodes.ERR_BAD_REQUEST,
            refused(store.putChunk(sender, request.fileId, 0, chunk(), sha(chunk(1024, 9)))).code,
        )
    }

    @Test
    fun onlyTheSenderMayUploadOrCancel() {
        val request = createRequest()
        ok(store.create(sender, request))

        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.putChunk(recipient, request.fileId, 0, chunk(), null)).code)
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.cancel(recipient, request.fileId)).code)
    }

    @Test
    fun aRecipientCannotReadBeforeTheFileIsReady() {
        val request = createRequest()
        ok(store.create(sender, request))
        ok(store.putChunk(sender, request.fileId, 0, chunk(), null))

        assertEquals(HubErrorCodes.ERR_NOT_FOUND, refused(store.status(recipient, request.fileId)).code)
        assertEquals(HubErrorCodes.ERR_NOT_FOUND, refused(store.readChunk(recipient, request.fileId, 0)).code)

        // The sender still sees the upload.
        assertEquals(FileStates.UPLOADING, (ok(store.status(sender, request.fileId)) as FileStatusDto).state)
    }

    @Test
    fun anOutsiderCannotReadMetadataOrAChunk() {
        val ready = makeReady()
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.status("device-nobody", ready)).code)
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.readChunk("device-nobody", ready, 0)).code)
    }

    @Test
    fun aGroupFileIsReadableByItsMembersAndTheRosterIsCheckedLive() {
        groups["group-1"] = listOf(sender, recipient, "device-third")
        val bytes = chunk()
        val request = createRequest(
            totalBytes = 1024,
            chunkCount = 1,
            hash = sha(bytes),
            toDeviceId = null,
            groupId = "group-1",
        )
        ok(store.create(sender, request))
        ok(store.putChunk(sender, request.fileId, 0, bytes, null))
        val completed = ok(store.complete(sender, request.fileId)) as FileStatusDto
        assertEquals(listOf(recipient, "device-third"), completed.recipients)

        ok(store.readChunk("device-third", request.fileId, 0))

        // The member is removed from the group: the file stays on the hub, the access does not.
        groups["group-1"] = listOf(sender, recipient)
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.readChunk("device-third", request.fileId, 0)).code)
        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refused(store.readChunk("device-nobody", request.fileId, 0)).code)
    }

    @Test
    fun aDeviceThatIsNotInTheGroupCannotCreateAGroupFile() {
        groups["group-closed"] = listOf(recipient, "device-third")

        val refusal = refused(store.create(sender, createRequest(toDeviceId = null, groupId = "group-closed")))

        assertEquals(HubErrorCodes.ERR_FORBIDDEN, refusal.code)
        // Nothing was created: the same id is still free, and a member can create it.
        groups["group-closed"] = listOf(sender, recipient)
        ok(store.create(sender, createRequest(toDeviceId = null, groupId = "group-closed")))
    }

    @Test
    fun aGroupThatDoesNotExistOrHasNoOtherMembersIsRefused() {
        assertEquals(
            HubErrorCodes.ERR_UNKNOWN_RECIPIENT,
            refused(store.create(sender, createRequest(toDeviceId = null, groupId = "group-missing"))).code,
        )

        groups["group-solo"] = listOf(sender)
        assertEquals(
            HubErrorCodes.ERR_BAD_REQUEST,
            refused(store.create(sender, createRequest(toDeviceId = null, groupId = "group-solo"))).code,
        )

        groups["group-ok"] = listOf(sender, recipient)
        assertEquals(
            HubErrorCodes.ERR_BAD_REQUEST,
            refused(store.create(sender, createRequest(toDeviceId = recipient, groupId = "group-ok"))).code,
        )
        assertEquals(
            HubErrorCodes.ERR_UNKNOWN_RECIPIENT,
            refused(store.create(sender, createRequest(toDeviceId = "device-unknown"))).code,
        )
    }

    // ----- quota and lifecycle ----------------------------------------------------------------------------------

    @Test
    fun theDeviceAndGlobalQuotasCountReservationsAndReleaseOnCancel() {
        limits = limits.copy(deviceStoredBytes = 3000, globalStoredBytes = 5000, maxFilesPerDevice = 10)
        ok(store.create(sender, createRequest(fileId = id(1))))

        // A second 2048 would make 4096 > 3000.
        assertEquals(HubErrorCodes.ERR_QUOTA_EXCEEDED, refused(store.create(sender, createRequest(fileId = id(2)))).code)

        // Cancel the first: the reservation goes with it.
        ok(store.cancel(sender, id(1)))
        ok(store.create(sender, createRequest(fileId = id(3))))

        // The global cap holds even when each device fits its own allowance: 2048 is already reserved by id(3), so a
        // second device's 2048 would cross a 3000-byte global cap.
        limits = limits.copy(deviceStoredBytes = 3000, globalStoredBytes = 3000)
        assertEquals(
            HubErrorCodes.ERR_QUOTA_EXCEEDED,
            refused(store.create("device-other", createRequest(fileId = id(5)))).code,
        )
    }

    @Test
    fun twoSendersRacingForTheLastBytesCannotBothWin() {
        val raceDb = ServerDatabase.inMemory(poolSize = 4)
        val raceRoot = Files.createTempDirectory("flash-files-race").toFile()
        val raceLimits = FileLimits(deviceStoredBytes = 5000, globalStoredBytes = 5000, maxFilesPerDevice = 10)
        val raceStore = FileStore.open(raceDb, raceRoot, clock = { now }, limits = { raceLimits })
        try {
            val total = 4096L
            val chunkSize = 1024
            val count = 4
            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val futures = (1..2).map { n ->
                pool.submit(
                    java.util.concurrent.Callable {
                        start.await()
                        raceStore.create(
                            "device-$n",
                            CreateFileRequestDto(
                                fileId = id(100 + n),
                                totalBytes = total,
                                chunkSize = chunkSize,
                                chunkCount = count,
                                sha256 = sha(ByteArray(1)),
                                toDeviceId = "device-target",
                            ),
                        )
                    },
                )
            }
            start.countDown()
            val outcomes = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            pool.shutdown()

            // 2 x 4096 cannot fit in 5000: the IMMEDIATE write transaction must let exactly one reserve the space.
            assertEquals(1, outcomes.count { it is FileOutcome.Ok<*> }, "exactly one upload may reserve: $outcomes")
            assertEquals(1, outcomes.count { it is FileOutcome.Refused }, "the other is refused: $outcomes")
            assertEquals(total, raceStore.stats().uploadingBytes, "the loser reserved nothing")
        } finally {
            raceDb.close()
            raceRoot.deleteRecursively()
        }
    }

    @Test
    fun theFileCountCapAppliesPerDevice() {
        limits = limits.copy(maxFilesPerDevice = 2)
        ok(store.create(sender, createRequest(fileId = id(1))))
        ok(store.create(sender, createRequest(fileId = id(2))))
        assertEquals(HubErrorCodes.ERR_QUOTA_EXCEEDED, refused(store.create(sender, createRequest(fileId = id(3)))).code)
    }

    @Test
    fun completeRefusesAnIncompleteUpload() {
        val request = createRequest()
        ok(store.create(sender, request))
        ok(store.putChunk(sender, request.fileId, 0, chunk(), null))
        assertEquals(HubErrorCodes.ERR_CONFLICT, refused(store.complete(sender, request.fileId)).code)
    }

    @Test
    fun cancelDeletesTheBytesAndTheRows() {
        val fileId = makeReady()
        assertTrue(File(root, fileId).isDirectory)

        ok(store.cancel(sender, fileId))
        assertFalse(File(root, fileId).exists())
        assertEquals(HubErrorCodes.ERR_NOT_FOUND, refused(store.status(sender, fileId)).code)
    }

    @Test
    fun theSweeperRemovesAbandonedUploadsAndExpiredFiles() {
        limits = limits.copy(retentionMs = 60_000, uploadWindowMs = 30_000)
        val abandoned = id(1)
        ok(store.create(sender, createRequest(fileId = abandoned)))
        val ready = makeReady(id(2), chunks = 2)

        now += 31_000
        val first = store.sweep(now)
        assertEquals(1, first.uploadsAbandoned)
        assertEquals(0, first.filesExpired)
        assertFalse(File(root, abandoned).exists())
        assertTrue(File(root, ready).isDirectory)

        now += 60_000
        val second = store.sweep(now)
        assertEquals(1, second.filesExpired)
        assertEquals(0, second.uploadsAbandoned)
        assertFalse(File(root, ready).exists())

        val third = store.sweep(now)
        assertEquals(0, third.uploadsAbandoned + third.filesExpired)
    }

    @Test
    fun anExpiredFileIsNoLongerReadableEvenBeforeTheSweeperRuns() {
        limits = limits.copy(retentionMs = 1000)
        val fileId = makeReady()
        now += 2000

        assertEquals(HubErrorCodes.ERR_NOT_FOUND, refused(store.readChunk(recipient, fileId, 0)).code)
        // The sender may still find and cancel it.
        ok(store.cancel(sender, fileId))
    }

    @Test
    fun reconcileRemovesOrphanDirectoriesAndChunkRowsWhoseBytesVanished() {
        val request = createRequest()
        ok(store.create(sender, request))
        ok(store.putChunk(sender, request.fileId, 0, chunk(), null))

        // A crash between the DB write and the file write: the row survives, the bytes do not.
        File(root, "${request.fileId}/0.chunk").delete()
        // A directory nobody refers to.
        File(root, "orphan").mkdirs()
        File(root, "orphan/0.chunk").writeBytes(byteArrayOf(1))

        store.reconcile()

        assertFalse(File(root, "orphan").exists())
        assertEquals(0, (ok(store.status(sender, request.fileId)) as FileStatusDto).receivedChunks)
        // The chunk can be sent again.
        ok(store.putChunk(sender, request.fileId, 0, chunk(), null))
    }

    @Test
    fun aReadyFileWhoseBytesAreGoneAnswersUnavailableInsteadOfEmpty() {
        val fileId = makeReady()
        File(root, "$fileId/0.chunk").delete()

        assertEquals(FileOutcome.Unavailable, store.readChunk(recipient, fileId, 0))

        // complete() refuses to declare a file ready whose bytes are missing.
        val second = id(9)
        val bytes = chunk()
        val request = createRequest(fileId = second, totalBytes = 1024, chunkCount = 1, hash = sha(bytes))
        ok(store.create(sender, request))
        ok(store.putChunk(sender, second, 0, bytes, null))
        File(root, "$second/0.chunk").delete()
        assertEquals(FileOutcome.Unavailable, store.complete(sender, second))
    }

    @Test
    fun aFiftyMegabyteFileIsAcceptedWhenTheLimitAllowsIt() {
        limits = limits.copy(maxFileBytes = 60L * 1024 * 1024)
        val fileId = id(7)
        val chunkSize = 1024 * 1024
        val count = 50
        val one = ByteArray(chunkSize) { (it % 251).toByte() }
        val whole = ByteArray(chunkSize * count) { (it % 251).toByte() }

        ok(
            store.create(
                sender,
                CreateFileRequestDto(fileId, whole.size.toLong(), chunkSize, count, sha(whole), toDeviceId = recipient),
            ),
        )
        for (i in 0 until count) {
            ok(store.putChunk(sender, fileId, i, one, sha(one)))
        }
        val completed = ok(store.complete(sender, fileId)) as FileStatusDto
        assertEquals(FileStates.READY, completed.state)
        assertEquals(whole.size.toLong(), completed.totalBytes)

        assertTrue(one.contentEquals(ok(store.readChunk(recipient, fileId, count - 1))))
        assertEquals(whole.size.toLong(), store.stats().readyBytes)
    }

    @Test
    fun statsReportOnlyCountsAndBytes() {
        makeReady(id(1))
        ok(store.create(sender, createRequest(fileId = id(2))))

        val stats = store.stats()
        assertEquals(1, stats.ready)
        assertEquals(1, stats.uploading)
        assertEquals(2048L, stats.readyBytes)
        assertEquals(2048L, stats.uploadingBytes)
        assertEquals(listOf(sender), stats.devices.map { it.deviceId })

        // An upload in progress is not visible to the recipient.
        assertEquals(HubErrorCodes.ERR_NOT_FOUND, refused(store.status(recipient, id(2))).code)
    }
}
