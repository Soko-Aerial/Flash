@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.radio

import com.transfer.flash.core.common.logging.FlashProbe
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
                    putNote(
                        receipt.messageId,
                        RadioDeliveryNote(
                            statusText = RadioDeliveryNote.STATE_DELIVERED,
                            isDelivered = true,
                            isFailure = false,
                        ),
                    )
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
     * Records [note] for [messageId], keeping the map bounded.
     *
     * A note is the bubble's only memory of the radio path, so it lives as long as the message is on screen; but a
     * device that keeps the app open for weeks must not grow this map with every radio message it ever sent. When
     * the cap is reached the oldest entry goes: it belongs to a bubble far enough up the list that its delivery line
     * is no longer being read, and the *newest* entry — the sending one, with its Stop control — is never evicted.
     */
    private fun putNote(messageId: String, note: RadioDeliveryNote) {
        _notes.update { current ->
            val kept = if (messageId !in current && current.size >= MAX_NOTES) {
                current.entries.drop(current.size - MAX_NOTES + 1).associate { it.key to it.value }
            } else {
                current
            }
            kept + (messageId to note)
        }
    }

    /**
     * Stops this message's radio retry locally. It cannot retract a frame already on the air; a late cryptographic
     * ACK still replaces this note with Delivered. Returns false when the message has no pending radio attempt.
     */
    public fun stopRetry(messageId: String): Boolean {
        val note = _notes.value[messageId] ?: return false
        if (note.isDelivered || note.isFailure) return false
        attempts.remove(messageId)
        putNote(messageId, RadioDeliveryNote(RadioDeliveryNote.STATE_STOPPED, isFailure = true))
        return true
    }

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
            putNote(messageId, RadioDeliveryNote(RadioDeliveryNote.REFUSAL_TOO_LONG, isFailure = true))
            onMessageFailed(messageId)
            return false
        }

        // Rule f: Gateway off or disconnected. This is NOT a failed attempt: during the 2026-10-28
        // two-radio run a two-minute outage burned a message's whole attempt budget and left it
        // permanently refused even after the link came back (ERROR-869 follow-up).
        if (!port.isGatewayEnabled || !port.isConnected) {
            putNote(messageId, RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED, isFailure = false))
            probeFailure(messageId, if (port.isGatewayEnabled) "not_connected" else "gateway_off", attempts[messageId] ?: 0)
            return false
        }

        val currentAttempt = (attempts[messageId] ?: 0) + 1
        attempts[messageId] = currentAttempt

        return when (val result = port.sendTextMessage(recipientId, messageId, frame.text, frame.sentAt, emergency)) {
            is RadioSendResult.SentWaitingAck -> {
                // A delivered transmission clears the budget: the next failure starts from one again.
                attempts.remove(messageId)
                putNote(
                    messageId,
                    RadioDeliveryNote(RadioDeliveryNote.STATE_SENT_WAITING, isFailure = false, isDelivered = false),
                )
                true
            }
            is RadioSendResult.NotConnected -> {
                // The link dropped between the check above and the send: still not the message's fault.
                attempts[messageId] = currentAttempt - 1
                putNote(messageId, RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED, isFailure = false))
                probeFailure(messageId, "not_connected", currentAttempt)
                false
            }
            is RadioSendResult.RefusedTooLong -> {
                putNote(messageId, RadioDeliveryNote(result.reason, isFailure = true))
                probeFailure(messageId, "too_long", currentAttempt)
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedAttachment -> {
                putNote(messageId, RadioDeliveryNote(result.reason, isFailure = true))
                probeFailure(messageId, "attachment", currentAttempt)
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedAuthorization -> {
                putNote(messageId, RadioDeliveryNote("Profile M unconfirmed: ${result.reason}", isFailure = true))
                probeFailure(messageId, "authorisation", currentAttempt)
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.RefusedUnreachable -> {
                putNote(messageId, RadioDeliveryNote(result.reason, isFailure = true))
                probeFailure(messageId, "unreachable", currentAttempt)
                onMessageFailed(messageId)
                false
            }
            is RadioSendResult.Failed -> {
                putNote(messageId, RadioDeliveryNote("${RadioDeliveryNote.STATE_FAILED}: ${result.reason}", isFailure = true))
                probeFailure(messageId, "failed", currentAttempt)
                onMessageFailed(messageId)
                false
            }
        }
    }

    /**
     * Why a radio send did not key the transmitter. The bubble shows a sentence, but the repository probe
     * (`radio.path`) only says "refused"; this line gives the reason, which is what made the 2026-10-28 run
     * hard to read (ERROR-869).
     */
    private fun probeFailure(messageId: String, reason: String, attempt: Int) {
        FlashProbe.emit(
            "radio.send.fail",
            "message" to FlashProbe.short(messageId),
            "reason" to reason,
            "attempt" to attempt,
        )
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
        /**
         * No attempt cap here on purpose: a message that cannot be sent yet is the outbox's business (it gives
         * up after `OUTBOX_GIVE_UP_AFTER_MS`, 30 minutes, and spaces retries). A second, hidden five-attempt
         * budget inside this bridge killed a message during the 2026-10-28 radio outage and could never revive
         * it (ERROR-869).
         */
        public const val MAX_BODY_BYTES: Int = 3008 // 16 * 188

        /** How many delivery notes are kept (see [putNote]). One per recent radio message; oldest is dropped first. */
        private const val MAX_NOTES: Int = 512
    }
}
