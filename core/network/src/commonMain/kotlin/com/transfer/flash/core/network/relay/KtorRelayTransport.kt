@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production Ktor CIO-backed implementation of [RelayTransport].
 * Runs purely on Kotlin Multiplatform across Android and Desktop JVM.
 */
@FlashInternalApi
public class KtorRelayTransport(
    private val client: HttpClient = createDefaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) : RelayTransport {

    private val _state = MutableStateFlow(RelaySessionState.DISCONNECTED)
    override val state: StateFlow<RelaySessionState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayFrame>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<RelayFrame> = _incomingFrames.asSharedFlow()

    private var activeSession: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null

    override suspend fun connect(
        hubUrl: String,
        deviceId: String,
        friendlyName: String,
        role: String,
    ): Boolean = withContext(ioDispatcher) {
        if (_state.value == RelaySessionState.CONNECTED || _state.value == RelaySessionState.CONNECTING) {
            return@withContext true
        }

        val welcomeDeferred = CompletableDeferred<Boolean>()
        val normalizedUrl = normalizeRelayUrl(hubUrl, deviceId, friendlyName, role)

        _state.value = RelaySessionState.CONNECTING

        connectionJob = scope.launch {
            try {
                client.webSocket(urlString = normalizedUrl) {
                    activeSession = this
                    _state.value = RelaySessionState.AUTHENTICATING

                    // Send explicit FLASH_HUB_HELLO frame as handshake
                    val hello = RelayFrame.Hello(
                        deviceId = deviceId,
                        friendlyName = friendlyName,
                        role = role,
                    )
                    send(Frame.Text(RelayFrameCodec.encode(hello)))

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val decoded = RelayFrameCodec.decode(text)
                            if (decoded != null) {
                                handleDecodedFrame(decoded, welcomeDeferred)
                                _incomingFrames.emit(decoded)
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // Cooperative cancellation
                welcomeDeferred.complete(false)
            } catch (_: Throwable) {
                welcomeDeferred.complete(false)
            } finally {
                activeSession = null
                if (_state.value != RelaySessionState.REVOKED) {
                    _state.value = RelaySessionState.SUSPENDED
                }
            }
        }

        try {
            welcomeDeferred.await()
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleDecodedFrame(frame: RelayFrame, welcomeDeferred: CompletableDeferred<Boolean>) {
        when (frame) {
            is RelayFrame.Welcome -> {
                _state.value = RelaySessionState.CONNECTED
                welcomeDeferred.complete(true)
            }
            is RelayFrame.Error -> {
                if (frame.code == RelayFrame.Error.ERR_REVOKED) {
                    _state.value = RelaySessionState.REVOKED
                }
                welcomeDeferred.complete(false)
            }
            else -> {
                // If the hub sends any valid frame before explicit welcome, consider connected
                if (_state.value == RelaySessionState.AUTHENTICATING) {
                    _state.value = RelaySessionState.CONNECTED
                    welcomeDeferred.complete(true)
                }
            }
        }
    }

    override suspend fun sendFrame(frame: RelayFrame): Boolean {
        val text = RelayFrameCodec.encode(frame)
        return sendRaw(text)
    }

    override suspend fun sendRaw(rawText: String): Boolean = withContext(ioDispatcher) {
        val session = activeSession ?: return@withContext false
        if (!_state.value.canSend) return@withContext false

        return@withContext try {
            session.send(Frame.Text(rawText))
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun disconnect(reason: String): Unit = withContext(ioDispatcher) {
        try {
            activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (_: Throwable) {
            // Ignore close exceptions
        } finally {
            activeSession = null
            connectionJob?.cancel()
            connectionJob = null
            if (_state.value != RelaySessionState.REVOKED) {
                _state.value = RelaySessionState.DISCONNECTED
            }
        }
    }

    public companion object {
        public fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
            }
        }

        public fun normalizeRelayUrl(input: String, deviceId: String, name: String, role: String): String {
            var base = input.trim()
            if (base.startsWith("https://")) {
                base = "wss://" + base.removePrefix("https://")
            } else if (base.startsWith("http://")) {
                base = "ws://" + base.removePrefix("http://")
            } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
                base = "ws://$base"
            }

            val urlWithoutQuery = if (base.contains("?")) base.substringBefore("?") else base
            val path = if (!urlWithoutQuery.endsWith("/flash-relay")) {
                if (urlWithoutQuery.endsWith("/")) "${urlWithoutQuery}flash-relay" else "$urlWithoutQuery/flash-relay"
            } else {
                urlWithoutQuery
            }

            return "$path?deviceId=$deviceId&name=$name&role=$role"
        }
    }
}
