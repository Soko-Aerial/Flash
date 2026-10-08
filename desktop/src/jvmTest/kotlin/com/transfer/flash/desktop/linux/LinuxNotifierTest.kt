package com.transfer.flash.desktop.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxNotifierTest {

    @Test
    fun bodyMarkupCharactersAreEscaped() {
        assertEquals("a &amp; b &lt;i&gt;x&lt;/i&gt;", LinuxNotifier.escapeBody("a & b <i>x</i>"))
        assertEquals("plain text", LinuxNotifier.escapeBody("plain text"))
    }

    @Test
    fun dbusIsTriedFirstAndTheCommandIsNotRunWhenItWorks() {
        val posted = mutableListOf<Triple<String, String, Boolean>>()
        var cliCalls = 0
        val notifier = LinuxNotifier(
            postDbus = { s, b, c -> posted += Triple(s, b, c); true },
            postCli = { _, _, _ -> cliCalls++; true },
        )
        assertTrue(notifier.send("Alice", "1 < 2", critical = true))
        assertEquals(listOf(Triple("Alice", "1 &lt; 2", true)), posted)
        assertEquals(0, cliCalls)
    }

    @Test
    fun aBlankTitleBecomesTheAppName() {
        var summary = ""
        val notifier = LinuxNotifier(postDbus = { s, _, _ -> summary = s; true }, postCli = { _, _, _ -> false })
        notifier.send("  ", "hello")
        assertEquals("Flash", summary)
    }

    @Test
    fun aDbusFailureFallsBackToTheCommand() {
        var cliBody = ""
        val notifier = LinuxNotifier(
            postDbus = { _, _, _ -> error("no session bus") },
            postCli = { _, b, _ -> cliBody = b; true },
        )
        assertTrue(notifier.send("Alice", "hi"))
        assertEquals("hi", cliBody)
    }

    @Test
    fun whenNothingWorksTheCallerIsToldSoItCanUseTheTray() {
        val notifier = LinuxNotifier(
            postDbus = { _, _, _ -> false },
            postCli = { _, _, _ -> throw java.io.IOException("notify-send not found") },
        )
        assertFalse(notifier.send("Alice", "hi"))
    }
}
