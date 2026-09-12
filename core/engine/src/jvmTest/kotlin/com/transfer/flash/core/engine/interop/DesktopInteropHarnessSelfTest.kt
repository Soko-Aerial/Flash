package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 self-test: exercises the harness's own composition on the desktop tier, so the
 * desktop half of the gate is *proven working* before a human runs it against an Android device.
 *
 * What this proves (and what it deliberately cannot):
 *
 * - The full desktop composition — `JmdnsTransport` + `JvmWsFlashNetwork` +
 *   `RealFlashTransferRepository` over an Okio file source, wired exactly as `DesktopInteropHarness`
 *   wires it — initializes, accepts a connection, and pushes a file to completion over the real
 *   WS wire protocol, with SHA-256 matching at both ends.
 * - It CANNOT prove cross-platform interop: both endpoints here are desktop JVMs. The phase's
 *   Do-NOT list explicitly forbids counting same-platform runs as gate scenarios — this suite is
 *   harness verification, not a substitute for G1-G7 against Android. The gate verdict stays
 *   with the human-run scenarios in the migration log.
 */
class DesktopInteropHarnessSelfTest {

    @Test
    fun `harness endpoints compose and a file crosses the ws wire with matching sha256`() {
        val senderDir = File(System.getProperty("java.io.tmpdir"), "flash-selftest-send").apply { mkdirs() }
        val receiverDir = File(System.getProperty("java.io.tmpdir"), "flash-selftest-recv").apply { mkdirs() }
        val payload = File(senderDir, "payload.bin").apply {
            writeBytes(ByteArray(512 * 1024) { (it % 251).toByte() }) // 512 KB, non-trivial pattern
        }
        val sourceDigest = sha256(payload)

        // Recompose the harness pieces directly (its main() is interactive).
        val receiver = DesktopEndpointFixture("selftest-recv", receiverDir)
        val sender = DesktopEndpointFixture("selftest-send", senderDir)
        try {
            val receiverPort = receiver.start()
            assertTrue("receiver must bind a port", receiverPort > 0)
            sender.start()

            // Dial receiver from sender, then push the file across.
            val connect = runBlocking {
                withTimeout(15_000) { sender.network.connectManual("127.0.0.1", receiverPort) }
            }
            assertTrue("dial must succeed: $connect", connect is FlashResult.Success)
            val session = (connect as FlashResult.Success).value

            val result = runBlocking {
                withTimeout(60_000) {
                    sender.transfer.sendFile(
                        targetDevice = session.peer,
                        fileUri = payload.absolutePath,
                        displayName = payload.name,
                        fileSize = payload.length(),
                    )
                }
            }
            assertTrue("sendFile must succeed: $result", result is FlashResult.Success)

            val id = (result as FlashResult.Success).value
            runBlocking {
                withTimeout(60_000) {
                    while (true) {
                        val t = sender.transfer.activeTransfers.value.firstOrNull { it.id == id }
                        if (t != null && t.state == FlashTransferState.Completed) break
                        if (t != null && t.state == FlashTransferState.Failed) {
                            error("transfer failed: ${t.errorMessage}")
                        }
                        kotlinx.coroutines.delay(200)
                    }
                }
            }

            // The bytes landed wherever the receiver pipeline put them; find the completed file
            // and compare digests.
            val received = receiverDir.walkTopDown().filter { it.isFile && it.length() == payload.length() }
                .toList()
            assertTrue("a file of the sent size must exist under the receiver root", received.isNotEmpty())
            assertEquals("sha256 must match across the wire", sourceDigest, sha256(received.first()))
        } finally {
            sender.stop()
            receiver.stop()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
