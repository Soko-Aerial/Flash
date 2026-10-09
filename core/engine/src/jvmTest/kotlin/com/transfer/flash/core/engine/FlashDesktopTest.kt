@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Present on the classpath, but its static initialiser throws: the "found but broken" half of the reflection fallback. */
internal object BrokenDesktopEngineStub {
    init {
        error("stub initialiser exploded")
    }
}

/**
 * [FlashDesktop]'s registry and its reflection fallback. The registry is reset around each test so the order of tests
 * (and of other classes in the same JVM) does not matter.
 */
class FlashDesktopTest {

    @BeforeTest
    @AfterTest
    fun resetRegistry() {
        FlashDesktop.resetForTesting()
    }

    @Test
    fun `create without a factory says what is missing`() {
        val failure = assertFailsWith<IllegalStateException> { FlashDesktop.create() }
        assertTrue(failure.message.orEmpty().contains("No FlashDesktop factory registered"), failure.message)
    }

    @Test
    fun `a registered factory receives the config and its engine is returned`() {
        var seen: FlashConfig? = null
        val expected = FlashConfig(displayName = "Registry Test", enableResume = false)
        FlashDesktop.registerFactory { config ->
            seen = config
            error("factory reached with ${config.displayName}")
        }

        val failure = assertFailsWith<IllegalStateException> { FlashDesktop.create(expected) }

        assertEquals(expected, seen)
        assertEquals("factory reached with Registry Test", failure.message)
    }

    @Test
    fun `a missing engine class is not an error by itself`() {
        FlashDesktop.ensureFactoryRegistered("com.transfer.flash.core.engine.DoesNotExist")
    }

    @Test
    fun `an engine class that fails to initialise keeps the original cause`() {
        val failure = assertFailsWith<IllegalStateException> {
            FlashDesktop.ensureFactoryRegistered("com.transfer.flash.core.engine.BrokenDesktopEngineStub")
        }

        assertTrue(failure.message.orEmpty().contains("BrokenDesktopEngineStub"), failure.message)
        // Previously `catch (_: Throwable) {}` dropped this and the caller saw only "No factory registered".
        val cause = failure.cause
        assertIs<ExceptionInInitializerError>(cause)
        assertEquals("stub initialiser exploded", cause.cause?.message)
    }
}
