@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.testsupport

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.server.relay.RelaySession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * A [WebSocketSession] that records what the hub writes. [outgoingCapacity] decides how a test models the client:
 * `Channel.UNLIMITED` is a client that always reads; `Channel.RENDEZVOUS` is a client whose TCP window is full (a slow
 * or half-open consumer: every write suspends).
 */
class FakeWebSocketSession(
    outgoingCapacity: Int = Channel.UNLIMITED,
    override val coroutineContext: CoroutineContext = Job(),
) : WebSocketSession {
    val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    val outgoingChannel = Channel<Frame>(outgoingCapacity)
    val closedByHub = AtomicBoolean(false)

    override val incoming: ReceiveChannel<Frame> = incomingChannel
    override val outgoing: SendChannel<Frame> = outgoingChannel
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false

    override suspend fun flush() {}

    @Deprecated("Use cancel instead")
    override fun terminate() {
        closedByHub.set(true)
        incomingChannel.close()
        outgoingChannel.close()
    }

    /** The next frame the hub wrote, decoded with the shared codec (a close frame ends the wait with a failure). */
    suspend fun next(timeoutMs: Long = 5_000L): RelayFrame = withTimeout(timeoutMs) {
        while (true) {
            when (val f = outgoingChannel.receive()) {
                is Frame.Text -> return@withTimeout requireNotNull(RelayFrameCodec.decode(f.readText())) { "undecodable frame: ${f.readText()}" }
                is Frame.Close -> {
                    closedByHub.set(true)
                    error("the hub closed the socket")
                }
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Every frame already written, without waiting. */
    fun drainNow(): List<RelayFrame> {
        val out = ArrayList<RelayFrame>()
        while (true) {
            val r = outgoingChannel.tryReceive()
            val f = r.getOrNull() ?: break
            if (f is Frame.Text) RelayFrameCodec.decode(f.readText())?.let(out::add)
            if (f is Frame.Close) closedByHub.set(true)
        }
        return out
    }

    /** Waits until a frame of type [T] arrives and returns it, skipping other frames. */
    suspend inline fun <reified T : RelayFrame> nextOf(timeoutMs: Long = 5_000L, crossinline matching: (T) -> Boolean = { true }): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            check(remaining > 0) { "timed out waiting for ${T::class.simpleName}" }
            val f = next(remaining)
            if (f is T && matching(f)) return f
        }
    }
}

/** A [RelaySession] over a fresh [FakeWebSocketSession]. */
fun fakeSession(
    deviceId: String,
    outgoingCapacity: Int = Channel.UNLIMITED,
    outboundQueue: Int = 256,
    sendTimeoutMs: Long = 10_000L,
): Pair<RelaySession, FakeWebSocketSession> {
    val ws = FakeWebSocketSession(outgoingCapacity)
    val session = RelaySession(
        deviceId = deviceId,
        friendlyName = "Device-$deviceId",
        remoteAddress = "127.0.0.1:40000",
        websocketSession = ws,
        outboundCapacity = outboundQueue,
        sendTimeoutMs = sendTimeoutMs,
    )
    return session to ws
}
