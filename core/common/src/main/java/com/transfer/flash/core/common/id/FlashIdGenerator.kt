package com.transfer.flash.core.common.id

/**
 * Injectable identifier generator (C0.4). Consumers depend on this interface; production
 * wiring uses [UuidIdGenerator], tests inject a deterministic fake.
 */
internal interface FlashIdGenerator {
    /** Returns a fresh, unique identifier string. */
    fun newId(): String
}

/** UUID v4 generator backed by [java.util.UUID.randomUUID]. Production default. */
internal object UuidIdGenerator : FlashIdGenerator {
    override fun newId(): String = java.util.UUID.randomUUID().toString()
}
