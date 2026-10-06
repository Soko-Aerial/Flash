package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.engine.interop.DesktopEndpointFixture
import com.transfer.flash.core.persistence.db.dao.SwarmDao
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity
import com.transfer.flash.core.swarm.driver.SwarmGroupContext
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ManifestBuilder
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.transfer.model.FlashTransferState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * End-to-end swarm interop automated test across three JVM endpoints (§7A SW-8 Task 14).
 *
 * Verifies:
 * 1. Origin sends a 5 MB file to a group of three (origin + 2 receivers).
 * 2. Origin disconnects at ~50% transfer.
 * 3. The two receivers exchange missing pieces to converge on the union.
 * 4. Origin reconnects.
 * 5. All nodes complete and whole-file SHA-256 checks pass.
 */
class SwarmInteropTest {

    @Test
    fun testSwarmInteropThreeEndpointsOriginDropAndRejoin() = runBlocking {
        val testTag = System.currentTimeMillis()
        val originStateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-origin-$testTag").apply { mkdirs() }
        val rx1StateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-rx1-$testTag").apply { mkdirs() }
        val rx2StateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-rx2-$testTag").apply { mkdirs() }

        val originRecv = File(originStateDir, "received").apply { mkdirs() }
        val rx1Recv = File(rx1StateDir, "received").apply { mkdirs() }
        val rx2Recv = File(rx2StateDir, "received").apply { mkdirs() }

        // 1. Generate 5 MB payload (5 * 1024 * 1024 bytes)
        val payloadSize = 5 * 1024 * 1024L
        val payloadFile = File(originStateDir, "payload-5mb.bin").apply {
            outputStream().buffered().use { out ->
                val chunk = ByteArray(64 * 1024)
                var written = 0L
                while (written < payloadSize) {
                    val toWrite = minOf(chunk.size.toLong(), payloadSize - written).toInt()
                    for (i in 0 until toWrite) {
                        chunk[i] = ((written + i) % 251).toByte()
                    }
                    out.write(chunk, 0, toWrite)
                    written += toWrite
                }
            }
        }
        val sourceDigest = sha256(payloadFile)

        // Build manifest with 64 KiB piece size (MIN_PIECE_SIZE) -> 80 pieces
        val pieceSize = PieceMath.MIN_PIECE_SIZE
        val builder = ManifestBuilder(pieceSize)
        payloadFile.inputStream().buffered().use { input ->
            val buf = ByteArray(pieceSize)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                builder.addBlock(buf, 0, read)
            }
        }
        val manifest = builder.build()
        assertEquals("5 MB at 64 KiB piece size must yield 80 pieces", 80, manifest.pieceCount)

        val origin = DesktopEndpointFixture("origin-$testTag", originRecv)
        val rx1 = DesktopEndpointFixture("rx1-$testTag", rx1Recv)
        val rx2 = DesktopEndpointFixture("rx2-$testTag", rx2Recv)

        val originId = origin.identity.deviceId.value
        val rx1Id = rx1.identity.deviceId.value
        val rx2Id = rx2.identity.deviceId.value
        val groupMembers = setOf(originId, rx1Id, rx2Id)
        val cryptoMap = mapOf(
            originId to origin.crypto,
            rx1Id to rx1.crypto,
            rx2Id to rx2.crypto,
        )

        val originStore = RoomSwarmStateStore(FakeSwarmDao())
        val rx1Dao = FakeSwarmDao()
        val rx2Dao = FakeSwarmDao()
        val rx1Store = RoomSwarmStateStore(rx1Dao)
        val rx2Store = RoomSwarmStateStore(rx2Dao)

        val originCtx = TestSwarmGroupContext(originId, groupMembers, cryptoMap)
        val rx1Ctx = TestSwarmGroupContext(rx1Id, groupMembers, cryptoMap)
        val rx2Ctx = TestSwarmGroupContext(rx2Id, groupMembers, cryptoMap)

        val originBinding = origin.attachSwarm(
            groupContext = originCtx,
            stateStore = originStore,
            seed = 1L,
        )
        val rx1Binding = rx1.attachSwarm(
            groupContext = rx1Ctx,
            stateStore = rx1Store,
            seed = 101L,
        )
        val rx2Binding = rx2.attachSwarm(
            groupContext = rx2Ctx,
            stateStore = rx2Store,
            seed = 202L,
        )

        try {
            val originPort = origin.start(enableDiscovery = false)
            val rx1Port = rx1.start(enableDiscovery = false)
            val rx2Port = rx2.start(enableDiscovery = false)

            // Connect mesh manually
            val dialRx1 = origin.network.connectManual("127.0.0.1", rx1Port)
            assertTrue("origin dial rx1 must succeed: $dialRx1", dialRx1 is FlashResult.Success)
            val dialRx2 = origin.network.connectManual("127.0.0.1", rx2Port)
            assertTrue("origin dial rx2 must succeed: $dialRx2", dialRx2 is FlashResult.Success)

            val dialRx2FromRx1 = rx1.network.connectManual("127.0.0.1", rx2Port)
            assertTrue("rx1 dial rx2 must succeed: $dialRx2FromRx1", dialRx2FromRx1 is FlashResult.Success)

            // Wait for mesh connections to settle
            withTimeout(15_000) {
                while (origin.network.activeSessions.value.size < 2 ||
                    rx1.network.activeSessions.value.size < 2 ||
                    rx2.network.activeSessions.value.size < 2
                ) {
                    delay(50)
                }
            }

            val groupId = "g2-swarm-interop"
            val messageId = "msg-interop-5mb"

            val regSig = originBinding.registerOrigin(
                groupId = groupId,
                messageId = messageId,
                fileName = payloadFile.name,
                mimeType = "application/octet-stream",
                sizeBytes = payloadFile.length(),
                uri = payloadFile.absolutePath,
                manifest = manifest,
            )
            assertNotNull("origin registration signature must not be null", regSig)

            val originAuthorKey = Base64.encode(origin.crypto.identityPublicKeyEncoded)
            val nowMs = System.currentTimeMillis()
            val rxAnnounce = SwarmEvent.Announced(
                groupId = groupId,
                messageId = messageId,
                originId = originId,
                originKey = originAuthorKey,
                root = manifest.root,
                totalSize = payloadFile.length(),
                pieceSize = manifest.pieceSize,
                fileName = payloadFile.name,
                mime = "application/octet-stream",
                sentAtMs = nowMs,
                expiresAtMs = nowMs + 86400_000L,
                isOrigin = false,
                localUri = null,
                autoAccept = true,
                nowMs = nowMs,
            )

            // Announce to receivers
            rx1Binding.driver.announceContent(rxAnnounce)
            rx2Binding.driver.announceContent(rxAnnounce.copy())

            fun getBits(dao: FakeSwarmDao): Bitfield {
                val entity = dao.contents.values.firstOrNull { it.root == manifest.root.hex }
                val raw = entity?.bits ?: return Bitfield(manifest.pieceCount)
                return Bitfield.fromByteArray(manifest.pieceCount, raw)
            }

            // Step 2: Wait until origin uploads about 50% across rx1 and rx2
            withTimeout(45_000) {
                while (true) {
                    val b1 = getBits(rx1Dao)
                    val b2 = getBits(rx2Dao)
                    val u = unionOf(b1, b2)
                    if (u.count() in 30..55 && u.count() < 75) {
                        break
                    }
                    delay(50)
                }
            }

            // Disconnect origin cleanly so auto-reconnect does not fire while offline
            origin.network.disconnect(FlashDeviceId(rx1Id))
            origin.network.disconnect(FlashDeviceId(rx2Id))
            rx1.network.disconnect(FlashDeviceId(originId))
            rx2.network.disconnect(FlashDeviceId(originId))

            withTimeout(10_000) {
                while (rx1.network.activeSessions.value.containsKey(FlashDeviceId(originId)) ||
                    rx2.network.activeSessions.value.containsKey(FlashDeviceId(originId)) ||
                    origin.network.activeSessions.value.isNotEmpty()
                ) {
                    delay(50)
                }
            }

            // Step 3: Assert convergence on union while origin is offline
            val unionAtDisconnect = unionOf(getBits(rx1Dao), getBits(rx2Dao))
            assertTrue(
                "Union at disconnect must have non-zero pieces: ${unionAtDisconnect.count()}",
                unionAtDisconnect.count() > 0,
            )
            assertTrue(
                "Union at disconnect must not be complete yet: ${unionAtDisconnect.count()} < 80",
                unionAtDisconnect.count() < 80,
            )

            // Receivers exchange missing pieces with each other until they converge on the union
            withTimeout(60_000) {
                while (true) {
                    val b1 = getBits(rx1Dao)
                    val b2 = getBits(rx2Dao)
                    val curUnion = unionOf(b1, b2)
                    if (b1.count() == curUnion.count() && b2.count() == curUnion.count() && b1 == b2) {
                        break
                    }
                    delay(100)
                }
            }

            val b1AfterSync = getBits(rx1Dao)
            val b2AfterSync = getBits(rx2Dao)
            assertEquals("Receivers must converge on the exact same bitfield while origin is offline", b1AfterSync, b2AfterSync)
            assertTrue(
                "Converged bitfield must hold all pieces uploaded prior to disconnect: count=${b1AfterSync.count()}",
                b1AfterSync.count() >= unionAtDisconnect.count(),
            )
            assertTrue(
                "Converged bitfield must still be incomplete while origin is offline: count=${b1AfterSync.count()}",
                b1AfterSync.count() < 80,
            )

            // Step 4: Reconnect origin
            val re1 = origin.network.connectManual("127.0.0.1", rx1Port)
            assertTrue("origin reconnect to rx1 must succeed: $re1", re1 is FlashResult.Success)
            val re2 = origin.network.connectManual("127.0.0.1", rx2Port)
            assertTrue("origin reconnect to rx2 must succeed: $re2", re2 is FlashResult.Success)

            withTimeout(15_000) {
                while (origin.network.activeSessions.value.size < 2 ||
                    rx1.network.activeSessions.value[FlashDeviceId(originId)] == null ||
                    rx2.network.activeSessions.value[FlashDeviceId(originId)] == null
                ) {
                    delay(50)
                }
            }

            // Step 5: Assert all complete and whole-file checks pass
            withTimeout(60_000) {
                while (true) {
                    val row1 = rx1Binding.driver.rows.value.firstOrNull { it.id.value == messageId || it.id.value == manifest.root.hex }
                    val row2 = rx2Binding.driver.rows.value.firstOrNull { it.id.value == messageId || it.id.value == manifest.root.hex }
                    if (row1?.state == FlashTransferState.Completed && row2?.state == FlashTransferState.Completed) {
                        break
                    }
                    if (row1?.state == FlashTransferState.Failed) {
                        fail("rx1 transfer failed: ${row1.errorMessage}")
                    }
                    if (row2?.state == FlashTransferState.Failed) {
                        fail("rx2 transfer failed: ${row2.errorMessage}")
                    }
                    delay(100)
                }
            }

            // Verify files on disk
            val rx1File = rx1Recv.walkTopDown().firstOrNull { it.isFile && it.length() == payloadSize }
            assertNotNull("rx1 must have completed file of size $payloadSize", rx1File)
            assertEquals("rx1 file sha256 must match source", sourceDigest, sha256(rx1File!!))

            val rx2File = rx2Recv.walkTopDown().firstOrNull { it.isFile && it.length() == payloadSize }
            assertNotNull("rx2 must have completed file of size $payloadSize", rx2File)
            assertEquals("rx2 file sha256 must match source", sourceDigest, sha256(rx2File!!))

        } finally {
            origin.stop()
            rx1.stop()
            rx2.stop()
            originStateDir.deleteRecursively()
            rx1StateDir.deleteRecursively()
            rx2StateDir.deleteRecursively()
        }
    }

    private fun unionOf(a: Bitfield, b: Bitfield): Bitfield {
        require(a.size == b.size)
        val u = Bitfield(a.size)
        for (i in 0 until a.size) {
            if (a.get(i) || b.get(i)) u.set(i, true)
        }
        return u
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal class TestSwarmGroupContext(
        private val localId: String,
        private val groupMembers: Set<String>,
        private val cryptoMap: Map<String, com.transfer.flash.core.security.crypto.FlashCrypto>,
    ) : SwarmGroupContext {
        override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean =
            peerId in groupMembers

        override suspend fun isLocalActiveMember(groupId: String): Boolean =
            localId in groupMembers

        override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? =
            cryptoMap[localId]?.sign(statement)

        override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean {
            val keyBytes = Base64.decode(authorKey) ?: return false
            val crypto = cryptoMap[localId] ?: return false
            return crypto.verify(signature, statement, keyBytes)
        }

        override suspend fun authorKey(groupId: String, authorId: String): String? {
            val c = cryptoMap[authorId] ?: return null
            return Base64.encode(c.identityPublicKeyEncoded)
        }

        override val membershipChanges: Flow<String> = emptyFlow()
    }

    internal class FakeSwarmDao : SwarmDao {
        val contents = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, SwarmContentEntity>()
        val tombstones = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, SwarmTombstoneEntity>()

        override suspend fun upsertContent(entity: SwarmContentEntity) {
            contents[entity.root to entity.groupId] = entity
        }

        override suspend fun getContent(root: String, groupId: String): SwarmContentEntity? =
            contents[root to groupId]

        override suspend fun getContentByTransferId(transferId: String): SwarmContentEntity? =
            contents.values.firstOrNull { it.localTransferId == transferId }

        override suspend fun getContentByMessageId(groupId: String, messageId: String): SwarmContentEntity? =
            contents.values.firstOrNull { it.groupId == groupId && it.messageId == messageId }

        override suspend fun loadAllContent(): List<SwarmContentEntity> =
            contents.values.toList()

        override suspend fun loadContentForGroup(groupId: String): List<SwarmContentEntity> =
            contents.values.filter { it.groupId == groupId }

        override suspend fun updateBits(root: String, groupId: String, bits: ByteArray, bytesDone: Long, nowMs: Long) {
            contents.compute(root to groupId) { _, current ->
                if (current == null) null
                else if (bytesDone >= current.bytesDone) {
                    current.copy(bits = bits, bytesDone = bytesDone, lastProgressAtMs = nowMs)
                } else {
                    current
                }
            }
        }

        override suspend fun updateState(root: String, groupId: String, state: String, waitReason: String?, failReason: String?, nowMs: Long) {
            contents.compute(root to groupId) { _, current ->
                current?.copy(state = state, waitReason = waitReason, failReason = failReason, lastProgressAtMs = nowMs)
            }
        }

        override suspend fun finalizeContent(root: String, groupId: String, finalPath: String, identitySize: Long, identityModifiedMs: Long, state: String, nowMs: Long) {
            contents.compute(root to groupId) { _, current ->
                current?.copy(finalPath = finalPath, identitySize = identitySize, identityModifiedMs = identityModifiedMs, state = state, waitReason = null, failReason = null, lastProgressAtMs = nowMs)
            }
        }

        override suspend fun deleteContent(root: String, groupId: String) {
            contents.remove(root to groupId)
        }

        override suspend fun upsertTombstone(entity: SwarmTombstoneEntity) {
            tombstones[entity.groupId to entity.messageId] = entity
        }

        override suspend fun getTombstone(groupId: String, messageId: String): SwarmTombstoneEntity? =
            tombstones[groupId to messageId]

        override suspend fun getTombstonesForGroup(groupId: String): List<SwarmTombstoneEntity> =
            tombstones.values.filter { it.groupId == groupId }

        override suspend fun getTombstonesForContent(root: String, groupId: String): List<SwarmTombstoneEntity> =
            tombstones.values.filter { it.root == root && it.groupId == groupId }

        override suspend fun loadAllTombstones(): List<SwarmTombstoneEntity> =
            tombstones.values.toList()

        override suspend fun deleteTombstone(groupId: String, messageId: String) {
            tombstones.remove(groupId to messageId)
        }

        override suspend fun purgeExpiredContent(nowMs: Long): Int {
            val toRemove = contents.filter { (_, v) ->
                v.expiresAtMs in 1L..nowMs && v.state in setOf("COMPLETE", "CANCELLED", "FAILED")
            }.keys
            toRemove.forEach { contents.remove(it) }
            return toRemove.size
        }

        override suspend fun purgeExpiredTombstones(nowMs: Long): Int {
            val toRemove = tombstones.filter { (_, v) ->
                v.expiresAtMs in 1L..nowMs
            }.keys
            toRemove.forEach { tombstones.remove(it) }
            return toRemove.size
        }
    }
}
