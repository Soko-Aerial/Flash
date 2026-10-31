package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O24-01: the optimistic echo is a presentation overlay for a text that has not reached the
 * conversation flow yet. It must be retired by the durable row it stands for, by identity and not by
 * text alone, and it must never be retired by a row that was already on screen when it was created.
 */
class FlashOptimisticEchoMathTest {

    private fun message(id: String, text: String, isMine: Boolean = true) = FlashMessageUi(
        id = id,
        senderName = if (isMine) "Me" else "Alex",
        senderInitials = "",
        timeLabel = "10:00",
        text = text,
        isMine = isMine,
    )

    private fun echo(text: String, knownOutgoingIds: Set<String>) = FlashOptimisticEcho(
        message = message("opt_1", text),
        knownOutgoingIds = knownOutgoingIds,
    )

    @Test
    fun `no echoes means nothing to overlay`() {
        assertEquals(emptyList(), FlashOptimisticEchoMath.unmatched(listOf(message("m1", "hi")), emptyList()))
    }

    @Test
    fun `an echo is retired by the new outgoing row that carries the same text`() {
        val echoes = listOf(echo("hello", knownOutgoingIds = emptySet()))

        val remaining = FlashOptimisticEchoMath.unmatched(listOf(message("m1", "hello")), echoes)

        assertEquals(emptyList(), remaining)
    }

    @Test
    fun `a row that already existed when the echo was created never retires it`() {
        // The user sends "hello" twice. The first row is already in the list when the second echo is
        // created, so only a row outside knownOutgoingIds can be that echo's own row.
        val existing = message("m1", "hello")
        val echoes = listOf(echo("hello", knownOutgoingIds = setOf("m1")))

        assertEquals(echoes, FlashOptimisticEchoMath.unmatched(listOf(existing), echoes))

        // The second row arrives: now the echo is retired, and the list is exactly the two rows.
        val afterSecond = FlashOptimisticEchoMath.unmatched(listOf(existing, message("m2", "hello")), echoes)
        assertEquals(emptyList(), afterSecond)
    }

    @Test
    fun `two echoes of the same text are retired one row at a time`() {
        val echoes = listOf(
            echo("hello", knownOutgoingIds = emptySet()),
            echo("hello", knownOutgoingIds = emptySet()),
        )

        val afterOneRow = FlashOptimisticEchoMath.unmatched(listOf(message("m1", "hello")), echoes)
        assertEquals(listOf(echoes[1]), afterOneRow)

        val afterTwoRows = FlashOptimisticEchoMath.unmatched(
            listOf(message("m1", "hello"), message("m2", "hello")),
            echoes,
        )
        assertEquals(emptyList(), afterTwoRows)
    }

    @Test
    fun `an incoming message with the same text never retires the echo`() {
        val echoes = listOf(echo("hello", knownOutgoingIds = emptySet()))

        val remaining = FlashOptimisticEchoMath.unmatched(listOf(message("m1", "hello", isMine = false)), echoes)

        assertEquals(echoes, remaining)
    }

    @Test
    fun `an echo for a different text stays while another text arrives`() {
        val echoes = listOf(echo("first", knownOutgoingIds = emptySet()))

        val remaining = FlashOptimisticEchoMath.unmatched(listOf(message("m1", "second")), echoes)

        assertEquals(echoes, remaining)
    }

    @Test
    fun `the returned echoes keep the order they were created in`() {
        val echoes = listOf(
            echo("first", knownOutgoingIds = emptySet()),
            echo("second", knownOutgoingIds = emptySet()),
            echo("third", knownOutgoingIds = emptySet()),
        )

        val remaining = FlashOptimisticEchoMath.unmatched(listOf(message("m1", "second")), echoes)

        assertEquals(listOf("first", "third"), remaining.map { it.message.text })
        assertTrue(remaining[0].message.id == "opt_1")
    }
}
