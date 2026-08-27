package com.transfer.flash.core.common.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Single shared wire container for Flash application frames (C0.2).
 *
 * Used by both the messaging layer and the transfer layer so that all transports carry one
 * uniform envelope type. The payload is JSON text ([payloadJson]); serialization of concrete
 * payload types happens at higher layers once kotlinx-serialization is introduced
 * (dependency addition is intentionally out of scope here — see docs/decisions.md rules).
 *
 * The peer's negotiated protocol version travels inside the handshake that precedes any
 * envelope; see [FlashProtocol] for the assert-on-handshake policy.
 *
 * @property id Unique envelope identifier (client-generated UUID v4; end-to-end idempotency key).
 * @property type Envelope type discriminator (e.g. `"MSG_TEXT"`, `"FILE_START"`). Non-blank.
 * @property payloadJson JSON-encoded payload body. May be empty but never null.
 * @property senderId Device identifier of the originating peer. Non-blank.
 * @property sentAt Sender-side wall-clock timestamp in epoch milliseconds (> 0).
 *
 * @throws IllegalArgumentException if [id], [type], or [senderId] is blank, or [sentAt] <= 0.
 */
@FlashInternalApi
public data class FlashEnvelope(
    val id: String,
    val type: String,
    val payloadJson: String,
    val senderId: String,
    val sentAt: Long,
) {
    init {
        require(id.isNotBlank()) { "FlashEnvelope.id must not be blank" }
        require(type.isNotBlank()) { "FlashEnvelope.type must not be blank" }
        require(senderId.isNotBlank()) { "FlashEnvelope.senderId must not be blank" }
        require(sentAt > 0) { "FlashEnvelope.sentAt must be positive, was $sentAt" }
    }
}
