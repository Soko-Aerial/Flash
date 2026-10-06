package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R4 / E-22 / E-29: a process that restarts rebuilds the engine from what was persisted, and the
 * review fixes around it (stale pieces after cancel, per-group serve switch, denied groups on connect,
 * membership loss leaving finished rows alone).
 */
class SwarmRestartRestoreTest {

    private val pieceSize = 65536

    private fun manifest(pieceCount: Int = 4): SwarmManifest {
        val totalSize = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = Sha256.digest(ByteArray(8) { 1 }),
            pieceHashes = List(pieceCount) { Sha256.digest(byteArrayOf(it.toByte())) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        val root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned))
        return unsigned.copy(root = root)
    }

    private fun record(
        manifest: SwarmManifest,
        role: SwarmRole,
        state: SwarmLifecycleState,
        doneBits: Set<Int> = emptySet(),
        groupId: String = "g1",
        originId: String = "origin",
    ): SwarmContentRecord {
        val pieceCount = manifest.pieceCount
        val bits = Bitfield(pieceCount).also { b -> doneBits.forEach { b.set(it, true) } }
        return SwarmContentRecord(
            root = manifest.root,
            groupId = groupId,
            messageId = "m1",
            role = role,
            originId = originId,
            originKey = "key",
            fileName = "f.bin",
            mime = "application/octet-stream",
            totalSize = manifest.totalSize,
            pieceSize = manifest.pieceSize,
            manifestBytes = ManifestCodec.encodeCanonical(manifest),
            bits = bits.toByteArray(),
            // Deliberately wrong: restore must recompute progress from the bits.
            bytesDone = 0L,
            state = state,
            waitReason = null,
            failReason = null,
            localTransferId = "m1",
            sourceUri = if (role == SwarmRole.ORIGIN) "content://src" else null,
            sourcePersistent = role == SwarmRole.ORIGIN,
            partialKey = "key",
            finalPath = null,
            identitySize = manifest.totalSize,
            identityModifiedMs = 1L,
            deliveredTo = emptySet(),
            createdAtMs = 1000L,
            lastProgressAtMs = 1000L,
            expiresAtMs = 10_000_000L,
        )
    }

    @Test
    fun `receiver partial download resumes from the persisted bits and asks the holder for the rest`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, setOf(0, 1)), 2000L)
        )
        // The holder's summary arrives after the restore (the driver feeds frames only once restore is done).
        val cmds = engine.handle(SwarmEvent.HaveAllArrived("origin", SwarmFrame.HaveAll("g1", m.root), 2100L))

        val snap = engine.snapshot().contents["g1" to m.root]!!
        assertEquals(SwarmLifecycleState.ACTIVE, snap.state)
        assertEquals(2, snap.piecesDone)
        assertEquals(2L * pieceSize, snap.bytesDone)
        val requested = cmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }.filterIsInstance<SwarmFrame.Request>().flatMap { it.pieces }.toSet()
        assertTrue(requested.isNotEmpty(), "a restored download must start requesting again")
        assertTrue(requested.none { it == 0 || it == 1 }, "pieces already persisted are not requested again")
    }

    @Test
    fun `restored origin serves a request after restart`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "origin", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.ORIGIN, SwarmLifecycleState.ACTIVE), 1000L))
        engine.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 1000L))

        val cmds = engine.handle(
            SwarmEvent.RequestArrived("rx", SwarmFrame.Request("g1", m.root, listOf(0)), allowed = true, nowMs = 2000L)
        )

        assertTrue(cmds.any { it is SwarmCommand.ReadPiece && it.index == 0 }, "origin must read and serve piece 0")
    }

    @Test
    fun `complete receiver whose finalize never finished is finalized again`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        val cmds = engine.handle(
            SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.VERIFYING, setOf(0, 1, 2, 3)), 2000L)
        )
        assertTrue(cmds.any { it is SwarmCommand.Finalize })
        assertEquals(SwarmLifecycleState.VERIFYING, engine.snapshot().contents["g1" to m.root]!!.state)
    }

    @Test
    fun `restored origin is never finalized`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "origin", 1L)
        val cmds = engine.handle(SwarmEvent.Restored(record(m, SwarmRole.ORIGIN, SwarmLifecycleState.ACTIVE), 1000L))
        assertFalse(cmds.any { it is SwarmCommand.Finalize })
    }

    @Test
    fun `restored content with a persisted tombstone is cancelled and its partial deleted`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(
            SwarmEvent.TombstonesRestored(
                listOf(SwarmTombstone("g1", m.root, "origin", "m1", SwarmTombstoneReason.DELETED, 1500L, ByteArray(64) { 1 })),
                1500L,
            )
        )
        val cmds = engine.handle(
            SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, setOf(0)), 2000L)
        )
        assertEquals(SwarmLifecycleState.CANCELLED, engine.snapshot().contents["g1" to m.root]!!.state)
        assertTrue(cmds.any { it is SwarmCommand.DeletePartial })
    }

    @Test
    fun `restoring the same content twice keeps the first state`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, setOf(0, 1)), 1000L))
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, emptySet()), 1001L))
        assertEquals(2, engine.snapshot().contents["g1" to m.root]!!.piecesDone)
    }

    @Test
    fun `denied group gets no summary when a peer connects`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "origin", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.ORIGIN, SwarmLifecycleState.ACTIVE), 1000L))

        val denied = engine.handle(SwarmEvent.PeerUp("stranger", setOf("sw1"), 2000L, deniedGroups = setOf("g1")))
        assertTrue(denied.none { it is SwarmCommand.Send }, "nothing about a group goes to a peer the gate denies")

        val allowed = engine.handle(SwarmEvent.PeerUp("member", setOf("sw1"), 2000L))
        assertTrue(allowed.any { it is SwarmCommand.Send && it.frame is SwarmFrame.Summary })
    }

    @Test
    fun `a piece that lands after cancel does not write or revive the content`() {
        val m = manifest(pieceCount = 2)
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 1000L))
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, setOf(0)), 1000L))
        engine.handle(SwarmEvent.HaveAllArrived("origin", SwarmFrame.HaveAll("g1", m.root), 1100L))

        engine.handle(SwarmEvent.LocalCancel("g1", m.root, asOrigin = false, nowMs = 2000L))
        assertEquals(SwarmLifecycleState.CANCELLED, engine.snapshot().contents["g1" to m.root]!!.state)

        val arrived = engine.handle(
            SwarmEvent.PieceArrived("origin", "g1", m.root, 1, ByteArray(pieceSize), verified = true, nowMs = 2100L)
        )
        assertTrue(arrived.none { it is SwarmCommand.WritePiece })

        val stored = engine.handle(SwarmEvent.PieceStored("g1", m.root, 1, 2200L))
        assertTrue(stored.none { it is SwarmCommand.Finalize })
        assertEquals(SwarmLifecycleState.CANCELLED, engine.snapshot().contents["g1" to m.root]!!.state)
    }

    @Test
    fun `serving switched off for the group rejects the request as busy`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "origin", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.ORIGIN, SwarmLifecycleState.ACTIVE), 1000L))
        engine.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 1000L))

        val cmds = engine.handle(
            SwarmEvent.RequestArrived(
                "rx", SwarmFrame.Request("g1", m.root, listOf(0)),
                allowed = true, nowMs = 2000L, serveAllowed = false,
            )
        )
        val reject = cmds.filterIsInstance<SwarmCommand.Send>().map { it.frame }.filterIsInstance<SwarmFrame.Reject>().single()
        assertEquals(SwarmRejectReason.BUSY, reject.reason)
        assertTrue(cmds.none { it is SwarmCommand.ReadPiece })
    }

    @Test
    fun `losing group membership leaves a finished download alone`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.COMPLETE, setOf(0, 1, 2, 3)), 1000L))

        engine.handle(SwarmEvent.MembershipChanged("g1", emptySet(), localActive = false, nowMs = 2000L))

        assertEquals(SwarmLifecycleState.COMPLETE, engine.snapshot().contents["g1" to m.root]!!.state)
    }

    @Test
    fun `losing group membership still fails an unfinished download`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE, setOf(0)), 1000L))

        val cmds = engine.handle(SwarmEvent.MembershipChanged("g1", emptySet(), localActive = false, nowMs = 2000L))

        assertEquals(SwarmLifecycleState.FAILED, engine.snapshot().contents["g1" to m.root]!!.state)
        assertTrue(cmds.any { it is SwarmCommand.DeletePartial })
    }

    @Test
    fun `a not-member reject backs the requester off instead of repeating every tick`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(), "rx", 1L)
        engine.handle(SwarmEvent.PeerUp("holder", setOf("sw1"), 1000L))
        engine.handle(SwarmEvent.Restored(record(m, SwarmRole.RECEIVER, SwarmLifecycleState.ACTIVE), 1000L))
        val first = engine.handle(SwarmEvent.HaveAllArrived("holder", SwarmFrame.HaveAll("g1", m.root), 1100L))
        val req = first.filterIsInstance<SwarmCommand.Send>().map { it.frame }.filterIsInstance<SwarmFrame.Request>()
        assertTrue(req.isNotEmpty(), "the holder must have been asked for pieces first")
        // Reject them all as not-a-member.
        engine.handle(
            SwarmEvent.RejectArrived(
                "holder",
                SwarmFrame.Reject("g1", m.root, SwarmRejectReason.NOT_MEMBER, 0L, scopeAll = true, pieces = emptyList()),
                1400L,
            )
        )
        val next = engine.handle(SwarmEvent.Tick(1650L))
        assertTrue(
            next.filterIsInstance<SwarmCommand.Send>().none { it.frame is SwarmFrame.Request },
            "no new request to a peer that just said it does not serve us (first tick sent ${req.size})",
        )
    }
}
