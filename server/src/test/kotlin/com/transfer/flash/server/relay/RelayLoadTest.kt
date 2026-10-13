@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.FakeWebSocketSession
import com.transfer.flash.server.testsupport.fakeSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-JVM load sanity for the relay (audit REL-02/REL-07/REL-14): many devices send to each other while connections are
 * cut at random and re-established. Every message must reach its recipient AT LEAST once, in the end nothing stays in
 * the mailbox, and the run must not stall. Sessions are fakes (no sockets), the mailbox is a real file database.
 *
 * Size: environment `FLASH_LOAD_DEVICES=500 FLASH_LOAD_MESSAGES=100` (Gradle does not forward `-D` to the test JVM) for a bigger run (see docs/testing/TEST-BACKLOG.md HUBB).
 */
class RelayLoadTest {

    private val dirs = mutableListOf<File>()
    private val databases = mutableListOf<ServerDatabase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        databases.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private class Endpoint(val session: RelaySession, val ws: FakeWebSocketSession, val reader: Job)

    @Test
    fun everyMessageArrivesAtLeastOnceDespiteRandomDisconnects() = runBlocking<Unit> {
        val devices = (System.getProperty("flash.load.devices") ?: System.getenv("FLASH_LOAD_DEVICES"))?.toIntOrNull() ?: 200
        val perDevice = (System.getProperty("flash.load.messages") ?: System.getenv("FLASH_LOAD_MESSAGES"))?.toIntOrNull() ?: 50
        val dir = Files.createTempDirectory("flash-load").toFile().also { dirs += it }
        val db = ServerDatabase("jdbc:sqlite:${File(dir, "hub.db").path.replace('\\', '/')}", 8).also { databases += it }
        val limits = MailboxLimits(perRecipientRows = 100_000, perSenderRows = 100_000, globalRows = 1_000_000)
        val mailbox = MailboxQueue(db, limits)
        val router = RelayFrameRouter(
            connectionPool = RelayConnectionPool(),
            mailboxQueue = mailbox,
            policy = RelayPolicy(frameBurst = 1_000_000, framesPerSecond = 1_000_000.0, maxInFlightPerSession = 256),
        )

        val total = devices * perDevice
        val received = ConcurrentHashMap<String, AtomicInteger>() // msgId -> times seen
        val seenTotal = AtomicInteger()
        val endpoints = ConcurrentHashMap<String, Endpoint>()

        fun connect(id: String) {
            val (session, ws) = fakeSession(id, outboundQueue = 1024)
            router.register(session)
            router.requestDrain(session)
            val reader = scope.launch {
                while (isActive) {
                    val frame = ws.outgoingChannel.receiveCatching().getOrNull() ?: break
                    if (frame !is Frame.Text) continue
                    val decoded = RelayFrameCodec.decode(frame.readText()) ?: continue
                    if (decoded is RelayFrame.Deliver) {
                        if (received.computeIfAbsent(decoded.id) { AtomicInteger() }.getAndIncrement() == 0) seenTotal.incrementAndGet()
                        router.processFrame(session, RelayFrameCodec.encode(RelayFrame.RecvAck(msgId = decoded.id)))
                    }
                }
            }
            endpoints[id] = Endpoint(session, ws, reader)
        }

        fun cut(id: String) {
            val e = endpoints[id] ?: return
            router.sessionEnded(e.session, "churn")
            e.reader.cancel()
        }

        val ids = (0 until devices).map { "dev-$it" }
        ids.forEach(::connect)

        val started = System.nanoTime()
        val churning = AtomicInteger(1)
        val churn = scope.launch {
            val rnd = Random(42)
            while (churning.get() == 1) {
                val id = ids[rnd.nextInt(ids.size)]
                cut(id)
                delay(rnd.nextLong(0, 4))
                connect(id)
                delay(rnd.nextLong(1, 6))
            }
        }

        val senders = ids.mapIndexed { index, id ->
            scope.launch {
                val rnd = Random(index)
                repeat(perDevice) { n ->
                    var to = ids[rnd.nextInt(ids.size)]
                    if (to == id) to = ids[(index + 1) % ids.size]
                    val session = endpoints[id]!!.session
                    router.processFrame(
                        session,
                        RelayFrameCodec.encode(RelayFrame.Send(to = to, id = "$id-$n", type = "MSG_TEXT", sentAt = System.currentTimeMillis(), payload = "{\"n\":$n}")),
                    )
                    if (n % 10 == 0) delay(1)
                }
            }
        }
        senders.joinAll()
        val sentMs = (System.nanoTime() - started) / 1_000_000

        // Stop the churn, make sure everybody has a live session, then let the system settle.
        churning.set(0)
        churn.join()
        for (id in ids) if (endpoints[id]?.session?.isOpen != true) connect(id)

        withTimeout(60_000) {
            while (seenTotal.get() < total) delay(20)
            while (ids.sumOf { mailbox.pendingCount(it) } > 0) delay(20)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val duplicates = received.values.sumOf { it.get() - 1 }
        println("RELAY_LOAD devices=$devices messagesPerDevice=$perDevice total=$total sendPhaseMs=$sentMs totalMs=$elapsedMs redeliveries=$duplicates")

        assertEquals(total, received.size, "every message id must have been seen")
        assertTrue(received.values.all { it.get() >= 1 })
        assertEquals(0, ids.sumOf { mailbox.pendingCount(it) }, "nothing is left in the mailbox")
        router.shutdown(2_000)
    }
}
