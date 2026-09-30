package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.pairing.PairingPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chat/group sync audit, step 1: a desktop must fire the same three chat edges as the app host on every
 * session-up (`notifyPeerSessionUp`, `sendGroupSyncRequests`, `reconcileGroupMembership`).
 *
 * The scenario is the one that needs them. Two paired desktops; the second one goes away; the first creates a
 * group with it (nothing can carry the `Add` frame, so the group is legacy and the member never hears of it) and
 * sends a message. When the second comes back, only the first's `reconcileGroupMembership` hands it the roster and
 * only the first's `notifyPeerSessionUp` makes the pending delivery due, so the group and the message arrive.
 * Without the wiring the returning member never learns the group exists and drops the message.
 *
 * What this cannot prove: behaviour on a phone, and real network loss (the second engine is stopped and rebuilt
 * from the same state directory). The phone-side equivalent is `TEST-BACKLOG.md` CGS-01.
 */
class DesktopEngineGroupSessionUpTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun dir(label: String): File = Files.createTempDirectory("flash-gsu-$label").toFile().also { tempDirs += it }

    private fun engine(state: File, received: File): DesktopEngine =
        testDesktopEngine(receivedRoot = received, stateDir = state).also { engines += it }

    @Test
    fun `a returning member learns the group and receives what was sent while it was away`() = runBlocking {
        val alphaState = dir("alpha-state")
        val alphaReceived = dir("alpha-received")
        val betaState = dir("beta-state")
        val betaReceived = dir("beta-received")

        val alpha = engine(alphaState, alphaReceived)
        var beta = engine(betaState, betaReceived)
        val alphaId = alpha.localDeviceId
        val betaId = beta.localDeviceId

        alpha.start()
        beta.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value }, "engines did not boot")
        assertTrue(await(SESSION_TIMEOUT_MS) { hasSession(alpha, betaId) && hasSession(beta, alphaId) }, "no session")
        alpha.pairing.beginPair(betaId, "beta") { }
        assertTrue(await(PAIR_TIMEOUT_MS) { beta.pairing.pairing.value?.phase == PairingPhase.RequestReceived }, "no pair request")
        beta.pairing.acceptLocal()
        assertTrue(
            await(PAIR_TIMEOUT_MS) { alpha.trust.isTrusted(FlashDeviceId(betaId)) && beta.trust.isTrusted(FlashDeviceId(alphaId)) },
            "pairing did not persist trust on both sides",
        )

        // Beta leaves. Alpha keeps its trust, so it can still create a group with beta, but no frame can reach it.
        beta.stop()
        assertTrue(await(SESSION_TIMEOUT_MS) { !hasSession(alpha, betaId) }, "alpha still sees a session with the stopped beta")

        val created = alpha.chats.createGroup("Crew", setOf(betaId))
        val groupId = assertIs<FlashResult.Success<String>>(created, "createGroup failed: $created").value
        alpha.chats.openConversation(groupId)
        alpha.chats.sendText(MESSAGE)

        // Beta comes back from the same state directory: same identity, same trust, same database.
        beta = engine(betaState, betaReceived)
        beta.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { beta.ready.value }, "restarted beta did not boot: ${beta.startError.value}")

        assertTrue(
            await(ARRIVAL_TIMEOUT_MS) { beta.chats.chatListState.value.items.any { it.id == groupId } },
            "beta never learned the group: alpha did not re-send the roster on session-up",
        )
        val betaChats = beta.chats
        betaChats.openConversation(groupId)
        assertTrue(
            await(ARRIVAL_TIMEOUT_MS) { betaChats.conversationState.value.messages.any { it.text == MESSAGE } },
            "beta never received the message sent while it was away: alpha did not make the delivery due on session-up",
        )
    }

    private fun hasSession(engine: DesktopEngine, peerId: String): Boolean =
        engine.network?.activeSessions?.value?.keys?.any { it.value == peerId } == true

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    private companion object {
        const val MESSAGE = "sent while you were away"
        const val BOOT_TIMEOUT_MS = 30_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val PAIR_TIMEOUT_MS = 15_000L
        const val ARRIVAL_TIMEOUT_MS = 40_000L
        const val POLL_MS = 100L
    }
}
