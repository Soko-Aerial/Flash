@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** A scripted [HubRelayPort]: tests push deliveries and hub verdicts in, and read what the device sent out. */
class FakeHubRelay(private val log: MutableList<String> = mutableListOf()) : HubRelayPort {

    data class Sent(val to: String, val id: String, val type: String, val sentAt: Long, val payload: String)

    @kotlin.concurrent.Volatile
    override var usable: Boolean = true

    private val inbound = Channel<HubInbound>(Channel.UNLIMITED)
    override val deliveries: Flow<HubInbound> = inbound.receiveAsFlow()

    val ackEvents = MutableSharedFlow<HubAckEvent>(extraBufferCapacity = 64)
    override val acks: Flow<HubAckEvent> = ackEvents

    /** Whether the fake relay session is signed in; flip it to model a (re)connect. */
    val connectedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val connected: Flow<Boolean> = connectedState

    val typingEvents = MutableSharedFlow<HubTypingEvent>(extraBufferCapacity = 64)
    override val typing: Flow<HubTypingEvent> = typingEvents

    @kotlin.concurrent.Volatile
    var sent: List<Sent> = emptyList()
        private set

    @kotlin.concurrent.Volatile
    var recvAcks: List<String> = emptyList()
        private set

    /** Which sender each acknowledgement named (REV-08), in order. */
    @kotlin.concurrent.Volatile
    var recvAckSenders: List<Pair<String, String?>> = emptyList()
        private set

    @kotlin.concurrent.Volatile
    var typingSent: List<Pair<String, String>> = emptyList()
        private set

    /** What [send] answers; flip to false to model a dead socket. */
    @kotlin.concurrent.Volatile
    var sendResult: Boolean = true

    fun deliver(d: HubInbound) {
        inbound.trySend(d)
    }

    override suspend fun send(to: String, id: String, type: String, sentAt: Long, payload: String): Boolean {
        sent = sent + Sent(to, id, type, sentAt, payload)
        return sendResult
    }

    override suspend fun recvAck(id: String, from: String?): Boolean {
        log += "recvAck:$id"
        recvAcks = recvAcks + id
        recvAckSenders = recvAckSenders + (id to from)
        return true
    }

    override suspend fun sendTyping(conversationId: String, to: String): Boolean {
        typingSent = typingSent + (conversationId to to)
        return true
    }
}
