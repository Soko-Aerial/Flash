package com.transfer.flash.core.common.model

/**
 * Device-local outbound consent for the optional radio fallback.
 *
 * A local decision not to transmit to a peer over radio never rejects their inbound radio messages.
 */
public enum class FlashRadioPolicy {
    AUTO,
    NEVER;

    public companion object {
        public fun fromStorage(value: String?): FlashRadioPolicy =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
