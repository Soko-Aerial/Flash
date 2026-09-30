package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.security.pairing.PairingPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chat/group sync audit, step 5: renaming a desktop must reach every place that holds a copy of the name.
 *
 * Before, `renameLocalDevice` wrote the store and nudged discovery; the UI read a plain property (so nothing
 * recomposed), the WebSocket hello kept the boot-time name, and pairing requests and group messages were stamped
 * with the name captured at construction. This pins the observable name, the persisted name, the transport's name
 * and the name a peer sees in the next pairing request, on two real engines.
 *
 * What it cannot prove: that a peer which stays connected learns the name (it only does on its next connection,
 * a documented limit), what the Compose shell draws, and any phone-side behaviour. Those are
 * `TEST-BACKLOG.md` DNAME-01 and DNAME-02.
 */
class DesktopEngineRenameTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun engine(label: String): DesktopEngine {
        val state = Files.createTempDirectory("flash-rename-$label-state").toFile().also { tempDirs += it }
        val received = Files.createTempDirectory("flash-rename-$label-received").toFile().also { tempDirs += it }
        return testDesktopEngine(receivedRoot = received, stateDir = state).also { engines += it }
    }

    @Test
    fun `a rename reaches the observable name, the store, the transport and the next pairing request`() = runBlocking {
        val alpha = engine("alpha")
        val beta = engine("beta")
        alpha.start()
        beta.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value }, "engines did not boot")
        val alphaId = alpha.localDeviceId
        val betaId = beta.localDeviceId
        assertTrue(
            await(SESSION_TIMEOUT_MS) {
                alpha.network?.activeSessions?.value?.keys?.any { it.value == betaId } == true &&
                    beta.network?.activeSessions?.value?.keys?.any { it.value == alphaId } == true
            },
            "no session between the engines",
        )
        val before = alpha.localFriendlyNameState.value

        assertTrue(alpha.renameLocalDevice("  Ada Desktop  "), "a real name is accepted")

        assertEquals("Ada Desktop", alpha.localFriendlyNameState.value, "what the UI observes")
        assertEquals("Ada Desktop", alpha.localFriendlyName)
        assertEquals("Ada Desktop", alpha.identity.friendlyName, "what survives a restart")
        assertEquals("Ada Desktop", (alpha.network as JvmWsFlashNetwork).localFriendlyName, "the hello the next connection sends")

        alpha.pairing.beginPair(betaId, "beta") { }
        assertTrue(
            await(PAIR_TIMEOUT_MS) { beta.pairing.pairing.value?.phase == PairingPhase.RequestReceived },
            "beta never received the pairing request",
        )
        assertEquals("Ada Desktop", beta.pairing.pairing.value?.peerName, "beta is asked by the new name, not '$before'")
    }

    @Test
    fun `a group message sent after a rename carries the new name`() = runBlocking {
        // The chat repository captured the name at construction, so a renamed desktop kept signing its group
        // messages (and the roster cert of every group it created) with the old one.
        val alpha = engine("sender")
        val beta = engine("receiver")
        alpha.start()
        beta.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value }, "engines did not boot")
        val alphaId = alpha.localDeviceId
        val betaId = beta.localDeviceId
        assertTrue(await(SESSION_TIMEOUT_MS) { hasSession(alpha, betaId) && hasSession(beta, alphaId) }, "no session")
        alpha.pairing.beginPair(betaId, "beta") { }
        assertTrue(await(PAIR_TIMEOUT_MS) { beta.pairing.pairing.value?.phase == PairingPhase.RequestReceived }, "no pair request")
        beta.pairing.acceptLocal()
        assertTrue(
            await(PAIR_TIMEOUT_MS) { alpha.trust.isTrusted(FlashDeviceId(betaId)) && beta.trust.isTrusted(FlashDeviceId(alphaId)) },
            "pairing did not persist trust on both sides",
        )

        assertTrue(alpha.renameLocalDevice("Ada Desktop"))
        val groupId = assertIs<FlashResult.Success<String>>(alpha.chats.createGroup("Crew", setOf(betaId))).value
        alpha.chats.openConversation(groupId)
        alpha.chats.sendText("after the rename")

        assertTrue(await(ARRIVAL_TIMEOUT_MS) { beta.chats.chatListState.value.items.any { it.id == groupId } }, "beta never learned the group")
        beta.chats.openConversation(groupId)
        assertTrue(
            await(ARRIVAL_TIMEOUT_MS) { beta.chats.conversationState.value.messages.any { it.text == "after the rename" } },
            "beta never received the message",
        )
        val received = beta.chats.conversationState.value.messages.first { it.text == "after the rename" }
        assertEquals("Ada Desktop", received.senderName, "the message is stamped with the renamed device's new name")
    }

    @Test
    fun `a blank rename is refused and changes nothing`() = runBlocking {
        val alpha = engine("solo")
        alpha.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { alpha.ready.value }, "engine did not boot")
        val before = alpha.localFriendlyNameState.value

        assertFalse(alpha.renameLocalDevice("   "))

        assertEquals(before, alpha.localFriendlyNameState.value)
        assertEquals(before, alpha.identity.friendlyName)
    }

    private fun hasSession(engine: DesktopEngine, peerId: String): Boolean =
        engine.network?.activeSessions?.value?.keys?.any { it.value == peerId } == true

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val PAIR_TIMEOUT_MS = 15_000L
        const val ARRIVAL_TIMEOUT_MS = 40_000L
        const val POLL_MS = 100L
    }
}
