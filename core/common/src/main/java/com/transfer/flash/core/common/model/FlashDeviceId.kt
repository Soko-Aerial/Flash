package com.transfer.flash.core.common.model

/**
 * Type-safe value class representing a unique device identifier (UUID, public key hash, etc.).
 */
@JvmInline
public value class FlashDeviceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FlashDeviceId cannot be blank" }
    }

    override fun toString(): String = value
}
