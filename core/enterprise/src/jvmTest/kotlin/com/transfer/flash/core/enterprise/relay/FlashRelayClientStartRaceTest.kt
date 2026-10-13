@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-124 (found by the client/server contract test): `start()` called concurrently, as `EnterpriseHub` does from its
 * credentials collector and from `enroll()`, must create ONE connection loop. Two loops are two sockets for one device,
 * and the hub's "one session per device" rule makes them displace each other for ever.
 */
class FlashRelayClientStartRaceTest {

    private class CountingTransport : RelayTransport {
        val connects = AtomicInteger()
        private val s = MutableStateFlow(RelaySessionState.DISCONNECTED)
        override val state: StateFlow<RelaySessionState> = s
        private val frames = MutableSharedFlow<RelayFrame>(extraBufferCapacity = 8)
        override val incomingFrames: Flow<RelayFrame> = frames.asSharedFlow()

        override suspend fun connect(hubUrl: String, identity: RelayIdentity): Boolean {
            connects.incrementAndGet()
            s.value = RelaySessionState.CONNECTED
            CompletableDeferred<Unit>().await() // a healthy session: stays open until the loop is cancelled
            return true
        }

        override suspend fun sendFrame(frame: RelayFrame): Boolean = true
        override suspend fun sendRaw(rawText: String): Boolean = true
        override suspend fun disconnect(reason: String) {
            s.value = RelaySessionState.DISCONNECTED
        }
    }

    @Test
    fun manyConcurrentStartCallsOpenExactlyOneConnection() = runBlocking<Unit> {
        val transport = CountingTransport()
        val client = FlashRelayClient(
            deviceId = "dev-1",
            friendlyName = "Phone",
            hubUrl = "ws://127.0.0.1:1/flash-relay",
            identityProvider = { testIdentity() },
            transport = transport,
            jitter = { 0L },
        )
        try {
            (1..32).map { async(Dispatchers.Default) { client.start() } }.awaitAll()
            delay(500)
            assertEquals(1, transport.connects.get(), "one loop, one connection")
        } finally {
            client.stop()
        }
    }
}
