package com.transfer.flash.core.messaging.radio

import com.transfer.flash.core.radio.chat.RadioChatHost
import com.transfer.flash.core.radio.chat.RadioChatSendOutcome
import com.transfer.flash.core.radio.chat.RadioChatStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The [RadioOutboxPort] both hosts hand to the chat repository (ERROR-703). It sits on a [RadioChatHost], so it keeps
 * working across radio reconnects: with no radio connected a send is refused as "radio not connected" instead of
 * going to a dead engine.
 */
public class HostedRadioOutboxPort(
    private val host: RadioChatHost,
    scope: CoroutineScope,
) : RadioOutboxPort {

    override val isGatewayEnabled: Boolean get() = host.enabled.value

    override val isConnected: Boolean get() = host.engine.value?.isConnected == true

    override val connectionStatus: StateFlow<RadioGatewayConnectionStatus> =
        host.status.map { status ->
            when (status) {
                RadioChatStatus.OFF -> RadioGatewayConnectionStatus.OFF
                RadioChatStatus.CONNECTING -> RadioGatewayConnectionStatus.CONNECTING
                RadioChatStatus.CONNECTED -> RadioGatewayConnectionStatus.CONNECTED
                RadioChatStatus.DISCONNECTED -> RadioGatewayConnectionStatus.DISCONNECTED
                RadioChatStatus.FAILED -> RadioGatewayConnectionStatus.FAILED
            }
        }.stateIn(scope, SharingStarted.Eagerly, RadioGatewayConnectionStatus.OFF)

    override fun isRadioReachable(recipientDeviceId: String): Boolean =
        host.engine.value?.isPeerRadioReachable(recipientDeviceId) == true

    override suspend fun sendTextMessage(
        recipientDeviceId: String,
        messageId: String,
        text: String,
        sentAt: Long,
        emergency: Boolean,
    ): RadioSendResult {
        val engine = host.engine.value ?: return RadioSendResult.NotConnected()
        return when (val outcome = engine.sendText(recipientDeviceId, messageId, text, sentAt, emergency)) {
            is RadioChatSendOutcome.SentWaitingAck -> RadioSendResult.SentWaitingAck
            is RadioChatSendOutcome.NotConnected -> RadioSendResult.NotConnected(outcome.reason)
            is RadioChatSendOutcome.RefusedTooLong -> RadioSendResult.RefusedTooLong(outcome.reason)
            is RadioChatSendOutcome.RefusedAuthorization -> RadioSendResult.RefusedAuthorization(outcome.reason)
            is RadioChatSendOutcome.RefusedUnreachable -> RadioSendResult.RefusedUnreachable(outcome.reason)
            is RadioChatSendOutcome.Failed -> RadioSendResult.Failed(outcome.reason)
        }
    }

    override val inboundMessages: Flow<RadioInboundMessage> =
        host.inboundMessages.map {
            RadioInboundMessage(it.senderDeviceId, it.messageId, it.text, it.sentAtMs, it.senderCallsign)
        }

    override val deliveryReceipts: Flow<RadioDeliveryReceipt> =
        host.deliveryReceipts.map { RadioDeliveryReceipt(it.messageId, it.recipientDeviceId, it.deliveredAtMs) }
}
