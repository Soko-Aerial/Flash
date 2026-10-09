package com.transfer.flash.core.persistence.db

import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.core.common.perf.MemoryTrimListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-18 (sweep 2026-10-09): opening the same database again must not stack another memory-trim listener. */
class DatabaseTrimRegistrationTest {

    private val registered = mutableListOf<MemoryTrimListener>()
    private val registration = DatabaseTrimRegistration(
        register = { registered += it },
        unregister = { registered -= it },
    )

    @Test
    fun reopeningTheDatabaseLeavesExactlyOneListenerRegistered() {
        repeat(5) { registration.attach(isOpen = { true }, shrink = {}) }
        assertEquals("five opens, one listener", 1, registered.size)
    }

    @Test
    fun theListenerOfTheLatestOpenIsTheOneThatShrinks() {
        var firstShrinks = 0
        var secondShrinks = 0
        registration.attach(isOpen = { false }, shrink = { firstShrinks++ })
        registration.attach(isOpen = { true }, shrink = { secondShrinks++ })

        registered.forEach { it.onTrimMemory(MemoryTrimLevel.RUNNING_CRITICAL) }

        assertEquals(0, firstShrinks)
        assertEquals(1, secondShrinks)
    }

    @Test
    fun aMildTrimLevelDoesNotShrinkAndAClosedHandleIsLeftAlone() {
        var shrinks = 0
        registration.attach(isOpen = { true }, shrink = { shrinks++ })
        registered.single().onTrimMemory(MemoryTrimLevel.UI_HIDDEN)
        assertEquals(0, shrinks)

        registration.attach(isOpen = { false }, shrink = { shrinks++ })
        registered.single().onTrimMemory(MemoryTrimLevel.COMPLETE)
        assertEquals(0, shrinks)
        assertTrue(registered.size == 1)
    }

    @Test
    fun aFailingShrinkNeverEscapesTheListener() {
        registration.attach(isOpen = { true }, shrink = { error("disk I/O") })
        registered.single().onTrimMemory(MemoryTrimLevel.RUNNING_LOW)
    }
}
