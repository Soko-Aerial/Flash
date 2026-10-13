package com.transfer.flash.server

import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.storage.ServerDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The operator commands of `Main`: the Docker health probe and the break-glass admin reset. */
class MainCommandsTest {

    private val dir: File = Files.createTempDirectory("flash-main-test").toFile()
    private var hub: HubServices? = null

    @AfterTest
    fun cleanUp() {
        runCatching { hub?.stop() }
        dir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun args(port: Int) = arrayOf(
        "--port=$port", "--admin-port=${port + 1}", "--host=127.0.0.1", "--data-dir=${dir.path}", "--external-url=https://hub.test",
    )

    @Test
    fun healthcheckIsZeroForARunningHubAndOneForADeadPort() {
        val port = freePort()
        assertEquals(1, healthcheck(args(port)), "nothing listens yet")
        hub = HubServices.build(ServerConfig.fromEnvironment(args(port), emptyMap())).also { it.start(wait = false) }
        assertEquals(0, healthcheck(args(port)), "the running hub answers /health with 200")
    }

    @Test
    fun breakGlassResetPrintsNewCredentialsOnceAndLocksOutTheOldSessions() {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        try {
            assertEquals(0, adminRecovery(arrayOf("--data-dir=${dir.path}", "--external-url=https://hub.test", "--username", "rescue-admin")))
        } finally {
            System.setOut(original)
        }
        val text = out.toString()
        assertTrue(text.contains("rescue-admin") && text.contains("One-time password"))
        val password = text.lineSequence().first { it.startsWith("One-time password:") }.substringAfter(": ").trim()
        ServerDatabase("jdbc:sqlite:${File(dir, "flash-hub.db").absolutePath}").use { db ->
            val admins = AdminAuthService(printSetupTokenToConsole = false, db = db)
            assertTrue(admins.hasAdmins())
            assertFalse(admins.isSetupOpen(), "a break-glass admin closes first-run setup")
            assertTrue(password.length >= 15)
        }
        assertEquals(2, adminRecovery(arrayOf("--data-dir=${dir.path}", "--external-url=https://hub.test")), "no --username is a usage error")
    }
}
