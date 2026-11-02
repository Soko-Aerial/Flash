package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.common.perf.FlashThermalStatus
import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OPT-O08-06: `AndroidThermalGovernor` was wired into the 1:1 transfer dispatcher but not into the swarm, so a device in
 * `THERMAL_STATUS_SEVERE` kept serving with its full profile (4 upload slots, 32 MiB window) and kept hashing.
 *
 * The engine now follows the governor: `MODERATE` steps the serve profile down one notch, `SEVERE` and above drop to the
 * lowest profile *and* stop serving, and recovery restores the configured profile and the user's own serving choice.
 */
class SwarmThermalStepDownTest {

    private val pieceSize = 65_536

    private fun manifest(pieceCount: Int = 8): SwarmManifest {
        val total = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = total,
            fileSha256 = Sha256.digest(ByteArray(pieceCount) { it.toByte() }),
            pieceHashes = List(pieceCount) { Sha256.digest(ByteArray(64) { b -> (it + b).toByte() }) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        return unsigned.copy(root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned)))
    }

    private fun origin(profile: SwarmProfile, m: SwarmManifest): SwarmEngine {
        val engine = SwarmEngine(SwarmConfig(profile = profile, autoAcceptIncoming = true), "origin", seed = 1L)
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
                originKey = "key",
                root = m.root,
                totalSize = m.totalSize,
                pieceSize = m.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = true,
                manifest = m,
                nowMs = 1_000L,
            )
        )
        return engine
    }

    /** One request from [peer] for a piece nobody else has, so the origin's offer policy serves it. */
    private fun request(engine: SwarmEngine, m: SwarmManifest, peer: String, index: Int, nowMs: Long) =
        engine.handle(
            SwarmEvent.RequestArrived(
                peerId = peer,
                frame = SwarmFrame.Request("g1", m.root, listOf(index)),
                allowed = true,
                nowMs = nowMs,
            )
        )

    @Test
    fun `moderate steps the profile down one notch and severe stops serving`() {
        val m = manifest()
        val engine = origin(SwarmProfile.HIGH, m)
        assertEquals(32L * 1024 * 1024, engine.inFlightBudgetBytes, "HIGH starts with a 32 MiB window")

        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.MODERATE, 1_100L))
        assertEquals(SwarmProfile.MEDIUM, engine.activeServeProfile)
        assertEquals(16L * 1024 * 1024, engine.inFlightBudgetBytes, "MODERATE halves the window")

        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.SEVERE, 1_200L))
        assertEquals(SwarmProfile.LOW, engine.activeServeProfile)
        assertEquals(8L * 1024 * 1024, engine.inFlightBudgetBytes, "SEVERE drops to the lowest window")

        val busy = request(engine, m, "peerA", 0, 1_300L)
            .filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Reject>()
        assertTrue(busy.isNotEmpty(), "SEVERE must answer a request with a rejection, not serve it")
        assertEquals(SwarmRejectReason.BUSY, busy.first().reason)

        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.NONE, 1_400L))
        assertEquals(SwarmProfile.HIGH, engine.activeServeProfile, "the configured profile is restored")
        assertEquals(32L * 1024 * 1024, engine.inFlightBudgetBytes)

        val served = request(engine, m, "peerA", 0, 1_500L).filterIsInstance<SwarmCommand.ReadPiece>()
        assertEquals(1, served.size, "cooled down, the same request is served again")
    }

    @Test
    fun `a call still takes priority over the thermal profile`() {
        val m = manifest()
        val engine = origin(SwarmProfile.HIGH, m)
        engine.handle(SwarmEvent.CallActive(true, 1_100L))
        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.LIGHT, 1_200L))
        // LIGHT keeps the configured profile; the call throttle is what limits serving, exactly as before.
        assertEquals(SwarmProfile.HIGH, engine.activeServeProfile)
        val read = request(engine, m, "peerA", 0, 1_300L).filterIsInstance<SwarmCommand.ReadPiece>()
        assertEquals(1, read.size, "one slot during a call: a single request is still served")
    }

    @Test
    fun `the serving preference the person chose is never overwritten by heat`() {
        val m = manifest()
        val engine = origin(SwarmProfile.MEDIUM, m)
        engine.handle(SwarmEvent.ServingEnabled(false, 1_100L))
        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.CRITICAL, 1_200L))
        engine.handle(SwarmEvent.ThermalChanged(FlashThermalStatus.NONE, 1_300L))
        // The person's "do not serve" is still in force after the device cooled: cooling cannot opt a device back in.
        val rej = request(engine, m, "peerA", 0, 1_400L)
            .filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Reject>()
        assertTrue(rej.isNotEmpty())
        assertEquals(SwarmRejectReason.BUSY, rej.first().reason)
    }
}
