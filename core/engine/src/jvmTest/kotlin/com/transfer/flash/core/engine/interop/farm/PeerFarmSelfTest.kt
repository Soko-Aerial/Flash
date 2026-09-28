package com.transfer.flash.core.engine.interop.farm

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.result.FlashResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the PC0 farm's own bookkeeping over real TLS sockets on loopback: an external device that
 * dials every farm peer is counted once per peer, farm peers hold no sessions with each other, and a
 * lost device is recorded as down on every peer. Discovery is off so the test cannot see the LAN.
 *
 * It cannot prove anything about a phone: that is the PC0 run itself (`logs/experiments.md`).
 */
class PeerFarmSelfTest {

    @Test
    fun `options parse and reject out-of-range values`() {
        val o = PeerFarm.parse(arrayOf("--count=12", "--minutes=60", "--tier=low", "--dial=none", "--report=30"))
        assertEquals(12, o.count)
        assertEquals(60L, o.minutes)
        assertEquals(FlashPerformanceMode.LOW, o.tier)
        assertEquals(PeerFarm.DialPolicy.NONE, o.dial)
        assertEquals(30L, o.reportSeconds)
        assertTrue(runCatching { PeerFarm.parse(arrayOf("--count=0")) }.isFailure)
        assertTrue(runCatching { PeerFarm.parse(arrayOf("--bogus=1")) }.isFailure)
    }

    @Test
    fun `an external device is counted once per farm peer and farm peers never pair up`() {
        val root = Files.createTempDirectory("flash-peer-farm-test").toFile()
        val farm = PeerFarm.Farm(
            PeerFarm.Options(count = 3, dial = PeerFarm.DialPolicy.NONE, reportSeconds = 3_600,
                stateRoot = File(root, "farm"), discovery = false),
        )
        // A one-peer "farm" in its own state root stands in for the phone: its id is not a farm id.
        val phone = PeerFarm.Farm(
            PeerFarm.Options(count = 1, dial = PeerFarm.DialPolicy.NONE, reportSeconds = 3_600,
                stateRoot = File(root, "phone"), discovery = false),
        )
        try {
            farm.start()
            phone.start()
            val dialer = phone.peers.single()
            farm.peers.forEach { p ->
                val r = runBlocking {
                    withTimeout(15_000) { dialer.network.connectManual("127.0.0.1", p.port, p.identity.deviceId.value) }
                }
                assertTrue("dial ${p.name} must succeed: $r", r is FlashResult.Success)
            }
            awaitTrue("all three farm peers hold the external session") {
                farm.recorder.heldByExternal().values.singleOrNull() == 3
            }
            farm.peers.forEach { p ->
                assertEquals("${p.name} must hold only the external session", 1, p.network.activeSessions.value.size)
            }

            phone.stop()
            awaitTrue("every farm peer records the external device as down") {
                farm.recorder.downs.get() == 3L && farm.recorder.heldByExternal().values.all { it == 0 }
            }
        } finally {
            phone.stop()
            farm.stop()
        }
        val rows = farm.csvFile.readLines()
        assertEquals(3, rows.count { it.endsWith(",up,in") })
        assertEquals(3, rows.count { it.contains(",down,") })
        root.deleteRecursively()
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            delay(100)
        }
    }
}
