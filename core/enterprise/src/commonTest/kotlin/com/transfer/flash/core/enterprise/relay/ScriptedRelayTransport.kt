@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise.relay

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** What the scripted hub does with one connection attempt. */
sealed interface ConnectScript {
    /** Handshake succeeds; the session stays up until [ScriptedRelayTransport.dropSession] or a disconnect. */
    data object Up : ConnectScript

    /** Handshake fails with [failure] (REVOKED moves the state to REVOKED, anything else to SUSPENDED). */
    data class Fail(val failure: RelayFailure) : ConnectScript
}

/** A [RelayTransport] whose hub is scripted per attempt, with a buffered reliable delivery stream like the real one. */
class ScriptedRelayTransport(private val now: () -> Long = { 0L }) : RelayTransport {
    private val _state = MutableStateFlow(RelaySessionState.DISCONNECTED)
    override val state: StateFlow<RelaySessionState> = _state.asStateFlow()

    private val _failure = MutableStateFlow<RelayFailure?>(null)
    override val failure: StateFlow<RelayFailure?> = _failure.asStateFlow()

    private val frames = MutableSharedFlow<RelayFrame>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<RelayFrame> = frames.asSharedFlow()

    private val deliveryChannel = Channel<RelayFrame.Deliver>(Channel.UNLIMITED)
    override val deliveries: Flow<RelayFrame.Deliver> = deliveryChannel.receiveAsFlow()

    /** Script per attempt (1-based); default is "Up". */
    var script: (attempt: Int) -> ConnectScript = { ConnectScript.Up }

    val connectTimes = mutableListOf<Long>()
    val connectUrls = mutableListOf<String>()
    val connectSerials = mutableListOf<Long>()
    val disconnectReasons = mutableListOf<String>()
    val sent = mutableListOf<RelayFrame>()
    var sendResult: Boolean = true

    override suspend fun connect(hubUrl: String, identity: RelayIdentity): Boolean {
        connectTimes += now()
        connectUrls += hubUrl
        connectSerials += identity.deviceCert.serial
        _failure.value = null
        _state.value = RelaySessionState.CONNECTING
        return when (val s = script(connectTimes.size)) {
            ConnectScript.Up -> {
                _state.value = RelaySessionState.CONNECTED
                frames.emit(RelayFrame.Welcome(principal = HubPrincipal(identity.deviceId, "member"), serverTime = now()))
                true
            }
            is ConnectScript.Fail -> {
                _failure.value = s.failure
                _state.value = if (s.failure.kind == RelayFailureKind.REVOKED) RelaySessionState.REVOKED else RelaySessionState.SUSPENDED
                false
            }
        }
    }

    override suspend fun sendFrame(frame: RelayFrame): Boolean {
        sent += frame
        return sendResult
    }

    override suspend fun sendRaw(rawText: String): Boolean = sendResult

    /** When set, [disconnect] reports DISCONNECTED at once but only finishes closing the socket when this completes (a slow close). */
    var disconnectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Closes that finished after a newer connect had started: each one would have killed the fresh session (ERROR-397, RVW22 F4). */
    var closesAfterReconnect: Int = 0

    override suspend fun disconnect(reason: String) {
        val connectsWhenStarted = connectTimes.size
        disconnectReasons += reason
        if (_state.value != RelaySessionState.REVOKED) _state.value = RelaySessionState.DISCONNECTED
        disconnectGate?.await()
        if (connectTimes.size > connectsWhenStarted) closesAfterReconnect++
    }

    /** The hub (or the network) ends the live session. */
    fun dropSession(failure: RelayFailure? = RelayFailure(RelayFailureKind.NETWORK)) {
        _failure.value = failure
        _state.value = RelaySessionState.SUSPENDED
    }

    suspend fun emit(frame: RelayFrame) = frames.emit(frame)

    fun deliver(frame: RelayFrame.Deliver) {
        deliveryChannel.trySend(frame)
    }
}

fun testCert(serial: Long = 1L, deviceId: String = "dev-1"): DeviceCert =
    DeviceCert(deviceId, "key", "member", "dept", 0L, Long.MAX_VALUE / 2, serial, "issuer", "sig")

fun testIdentity(serial: Long = 1L, deviceId: String = "dev-1"): RelayIdentity =
    RelayIdentity(deviceId, "Phone", testCert(serial, deviceId), emptyList(), "AA", RelaySigner { it })
