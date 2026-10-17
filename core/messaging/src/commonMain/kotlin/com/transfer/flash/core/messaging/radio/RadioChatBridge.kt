package com.transfer.flash.core.messaging.radio

import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Adapter connecting [RealFlashChatRepository] outbox and delivery pipeline to [RadioOutboxPort].
 *
 * Implements Plan R §7 / Phase R3 rules:
 * - Radio is selected ONLY when recipient has no live LAN or hub path and is radio-reachable.
 * - Text payloads only, segmented up to 3,008 bytes; longer refused with a sentence.
 * - Photos, files, voice notes rejected with "Photos and files cannot be sent by radio", not queued.
 * - Delivery state 'sent by radio, waiting' when transmitted; 'delivered' ONLY upon cryptographic ACK.
 * - Gateway off/disconnected: queued with honest state 'radio not connected', never transmitted on air.
 * - Inbound messages authenticated, decrypted, deduplicated and fed to repository inbound path.
 */
public class RadioChatBridge(
    public val port: RadioOutboxPort,
    private val scope: CoroutineScope,
    private val onInboundMessage: suspend (senderId: String, callsign: String?, messageId: String, text: String, sentAtMs: Long) -> Unit,
    private val onDeliveryReceipt: suspend (messageId: String, deliveredAtMs: Long, recipientDeviceId: String) -> Unit,
    private val onMessageFailed: suspend (messageId: String) -> Unit,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private val _notes = MutableStateFlow<Map<String, RadioDeliveryNote>>(emptyMap())
    public val notes: StateFlow<Map<String, RadioDeliveryNote>> = _notes.asStateFlow()

    private val attempts = HashMap<String, Int>()
    private var collectorJob: Job? = null

    init {
        startCollectors()
    }

    private fun startCollectors() {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch {
                port.deliveryReceipts.collect { receipt ->
                    _notes.update { current ->
                        current + (receipt.messageId to RadioDeliveryNote(
                            statusText = RadioDeliveryNote.STATE_DELIVERED,
                            isDelivered = true,
                            isFailure = false,
                        ))
                    }
                    attempts.remove(receipt.messageId)
                    onDeliveryReceipt(receipt.messageId, receipt.deliveredAtMs, receipt.recipientDeviceId)
                }
            }
            launch {
                port.inboundMessages.collect { msg ->
                    onInboundMessage(
                        msg.senderDeviceId,
                        msg.senderCallsign,
                        msg.messageId,
                        msg.text,
                        msg.sentAtMs,
                    )
                }
            }
        }
    }

    /**
     * Checks if [recipientId] is a paired peer known to be radio-reachable.
     */
    public fun canCarry(recipientId: String): Boolean =
        port.isRadioReachable(recipientId)

    /**
     * Attempts to send [frame] over the radio link.
     * Returns true if transmitted ('sent by radio, waiting'), false if queued or refused.
     */
    public suspend fun sendDirect(
        recipientId: String,
        frame: MessageWireFrame.TextMessage,
        emergency: Boolean = false,
    ): Boolean {
        val messageId = frame.localId
        val textBytes = frame.text.encodeToByteArray()

        // Rule a: max 3,008 bytes (16 segments of 188 bytes)
        if (textBytes.size > MAX_BODY_BYTES) {
            _notes.update { it + (messageId to RadioDeliveryNote(RadioDeliveryNote.REFUSAL_TOO_LONG, isFailure = true)) }
            onMessageFailed(messageId)
            return false
        }

        // Rule f: Gateway off or disconnected
        if (!port.isGatewayEnabled || !port.isConnected) {
            _notes.update { it + (messageId to RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED, isFailure = false)) }
            return false
        }

        val currentAttempt = (attempts[messageId] ?: 0) + 1
        attempts[messageId] = currentAttempt

        if (currentAttempt > maxAttempts) {
            _notes.update { it + (messageId to RadioDeliveryNote(RadioDeliveryNote.STATE_FAILED, isFailure = true)) }
            onMessageFailed(messageId)
            return false
        }

        return when (val result = port.sendTextMessage(recipientId, messageId, frame.text, frame.sentAt, emergency)) {
            is RadioSendResult.SentWaitingAck -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote(RadioDeliveryNote.STATE_SENT_WAITING, isFailure = false, isDelivered = false))
                }
                true
            }
            is RadioSendResult.NotConnected -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED, isFailure = false))
                }
                false
            }
            is RadioSendResult.RefusedTooLong -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote(result.reason, isFailure = true))
                }
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedAttachment -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote(result.reason, isFailure = true))
                }
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedAuthorization -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote("Profile M unconfirmed: ${result.reason}", isFailure = true))
                }
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedUnreachable -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote(result.reason, isFailure = true))
                }
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.Failed -> {
                _notes.update {
                    it + (messageId to RadioDeliveryNote("${RadioDeliveryNote.STATE_FAILED}: ${result.reason}", isFailure = true))
                }
                onMessageFailed(messageId)
                false
            }
        }
    }

    /**
     * Refusal check for attachments (photos, files, voice notes, calls).
     * If the peer has no live LAN or hub path and is radio-only, attachment sending is refused.
     */
    public fun checkAttachmentRefusal(peerId: String): String? {
        return if (canCarry(peerId)) {
            RadioDeliveryNote.REFUSAL_ATTACHMENT
        } else {
            null
        }
    }

    public companion object {
        public const val MAX_ATTEMPTS: Int = 5
        public const val MAX_BODY_BYTES: Int = 3008 // 16 * 188
    }
}
