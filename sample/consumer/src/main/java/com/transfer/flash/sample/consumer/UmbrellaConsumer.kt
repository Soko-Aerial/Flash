package com.transfer.flash.sample.consumer

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.engine.FlashEngine

/**
 * Compile-only proof of publishing shape A (umbrella): a downstream consumer that
 * depends on ONLY `:core:engine` must see the full public vocabulary. `FlashDeviceId`
 * and `FlashResult` live in `:core:common`, which reaches this classpath solely via
 * `:core:engine`'s `api(project(":core:common"))`. If this file compiles, shape A is
 * sound — no consumer needs to add `core:common` (or any other sibling) by hand.
 *
 * This module is a test harness and is never published.
 */
internal object UmbrellaConsumer {

    /** Constructs a `:core:common` value type reached transitively through the engine. */
    fun buildId(raw: String): FlashDeviceId = FlashDeviceId(raw)

    /** References the engine's own public interface. */
    fun sameEngine(engine: FlashEngine): FlashEngine = engine

    /** References a `:core:common` sealed type by way of the engine's api exposure. */
    fun describe(result: FlashResult<*>): String = result.toString()
}
