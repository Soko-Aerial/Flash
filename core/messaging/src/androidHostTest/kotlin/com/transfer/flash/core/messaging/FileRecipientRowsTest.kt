package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashRecipientProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class FileRecipientRowsTest {

    private fun member(id: String, progress: Float, hasAll: Boolean = false, online: Boolean = true, bps: Long = 0L) =
        FlashRecipientProgress(id, progress, hasAll, online, bps)

    private val names = mapOf("a" to "Alice", "b" to "bob", "c" to "Cara")

    @Test
    fun `members with the whole file come first then the rest furthest first`() {
        val view = buildFileRecipientView(
            listOf(member("c", 0.2f), member("b", 1f, hasAll = true), member("a", 0.7f), member("d", 1f, hasAll = true)),
            knownRecipientCount = 0,
            nameOf = { names[it] },
        )
        assertEquals(listOf("b", "d", "a", "c"), view.rows.map { it.id })
        assertEquals(2, view.haveAll)
    }

    @Test
    fun `an unnamed member falls back to the start of its id`() {
        val view = buildFileRecipientView(listOf(member("0123456789abcdef", 0.1f)), 0) { null }
        assertEquals("01234567", view.rows.single().name)
    }

    @Test
    fun `the total counts members not seen yet and they pull the mean down`() {
        val view = buildFileRecipientView(
            listOf(member("a", 1f, hasAll = true), member("b", 0.5f)),
            knownRecipientCount = 4,
            nameOf = { names[it] },
        )
        assertEquals(4, view.total)
        assertEquals(0.375f, view.meanProgress)
        assertEquals("1 of 4 have it", view.summaryLine(canGoOffline = false))
        assertEquals("1 of 4 have it · You can go offline now", view.summaryLine(canGoOffline = true))
    }

    @Test
    fun `everyone done says so`() {
        val view = buildFileRecipientView(
            listOf(member("a", 1f, hasAll = true), member("b", 1f, hasAll = true, online = false)),
            0,
            { names[it] },
        )
        assertEquals("Everyone has the file", view.summaryLine(canGoOffline = true))
    }

    @Test
    fun `an offline or complete member shows no speed`() {
        val view = buildFileRecipientView(
            listOf(member("a", 0.5f, online = false, bps = 2_000_000L), member("b", 1f, hasAll = true, bps = 2_000_000L), member("c", 0.5f, bps = 2_000_000L)),
            0,
            { names[it] },
        )
        assertEquals(listOf(0f, 0f, 2f), view.rows.sortedBy { it.id }.map { it.speedMbps })
    }
}
