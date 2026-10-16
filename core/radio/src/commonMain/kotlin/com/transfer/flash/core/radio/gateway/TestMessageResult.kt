package com.transfer.flash.core.radio.gateway

/** Outcome of [RadioGatewayManager.requestTestMessage]. Only [SENT] and [FAILED] mean bytes were offered to the link. */
public enum class TestMessageResult {
    /** The frame was handed to the radio and the driver reported it as sent. */
    SENT,

    /** The driver tried and did not send (link error or queue refusal). */
    FAILED,

    /** Gateway is off or no radio is connected and ready. Nothing written. */
    NOT_CONNECTED,

    /** The user did not confirm the transmit warning in this request. Nothing written. */
    NOT_CONFIRMED,

    /** No authorisation record with a positive date exists. Nothing written. */
    NOT_AUTHORISED,

    /** Empty text or longer than [RadioGatewayManager.MAX_TEST_MESSAGE_BYTES] bytes. Nothing written. */
    INVALID_TEXT,

    /** Another test message is still being sent. Nothing written. */
    BUSY,
}
