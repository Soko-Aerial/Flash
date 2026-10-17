package com.transfer.flash.desktop.radio

import com.transfer.flash.core.messaging.radio.RadioDeliveryReceipt
import com.transfer.flash.core.messaging.radio.RadioGatewayConnectionStatus
import com.transfer.flash.core.messaging.radio.RadioInboundMessage
import com.transfer.flash.core.messaging.radio.RadioOutboxPort
import com.transfer.flash.core.messaging.radio.RadioSendResult
import com.transfer.flash.core.radio.chat.RadioChatEngine
import com.transfer.flash.core.radio.chat.RadioChatSendOutcome
import com.transfer.flash.core.radio.chat.RadioChatStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Desktop host adapter implementing [RadioOutboxPort] over [RadioChatEngine].
 */
public class DesktopRadioOutboxAdapter(
    public val engine: RadioChatEngine,
    scope: CoroutineScope,
) : RadioOutboxPort {

    override val isGatewayEnabled: Boolean get() = engine.isEnabled.value

    override val isConnected: Boolean get() = engine.isConnected

    override val connectionStatus: StateFlow<RadioGatewayConnectionStatus> =
        engine.status.map { status ->
            when (status) {
                RadioChatStatus.OFF -> RadioGatewayConnectionStatus.OFF
                RadioChatStatus.CONNECTING -> RadioGatewayConnectionStatus.CONNECTING
                RadioChatStatus.CONNECTED -> RadioGatewayConnectionStatus.CONNECTED
                RadioChatStatus.DISCONNECTED -> RadioGatewayConnectionStatus.DISCONNECTED
                RadioChatStatus.FAILED -> RadioGatewayConnectionStatus.FAILED
            }
        }.stateIn(scope, SharingStarted.Eagerly, RadioGatewayConnectionStatus.DISCONNECTED)

    override fun isRadioReachable(recipientDeviceId: String): Boolean =
        engine.isPeerRadioReachable(recipientDeviceId)

    override suspend fun sendTextMessage(
        recipientDeviceId: String,
        messageId: String,
        text: String,
        sentAt: Long,
        emergency: Boolean,
    ): RadioSendResult {
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
        engine.inboundMessages.map { inbound ->
            RadioInboundMessage(
                senderDeviceId = inbound.senderDeviceId,
                messageId = inbound.messageId,
                text = inbound.text,
                sentAtMs = inbound.sentAtMs,
                senderCallsign = inbound.senderCallsign,
            )
        }

    override val deliveryReceipts: Flow<RadioDeliveryReceipt> =
        engine.deliveryReceipts.map { receipt ->
            RadioDeliveryReceipt(
                messageId = receipt.messageId,
                recipientDeviceId = receipt.recipientDeviceId,
                deliveredAtMs = receipt.deliveredAtMs,
            )
        }
}
