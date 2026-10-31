package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi

/**
 * One locally echoed outgoing message (OPT-O24-01).
 *
 * [knownOutgoingIds] is the set of outgoing row ids that were already on screen when the echo was
 * created. The echo is retired only by an outgoing row whose id is **not** in that set, so a text
 * that already exists in the conversation cannot retire it before its own row does.
 *
 * The echo is a presentation overlay only: the durable outbox row is still written by
 * `onSendText` / `onSendReply`, and nothing here may be used to claim a message was stored.
 */
internal data class FlashOptimisticEcho(
    val message: FlashMessageUi,
    val knownOutgoingIds: Set<String>,
)

internal object FlashOptimisticEchoMath {

    /**
     * The echoes that have no durable row yet, in creation order.
     *
     * Matching is by identity, not by text alone: an echo is retired by the n-th outgoing row in
     * [messages] that carries the same text and whose id the echo had not already seen, so sending
     * the same text twice retires one echo per new row, and an unrelated emit of the message list
     * (receipt, incoming message) cannot retire an echo early.
     *
     * An echo whose row never appears stays in the result — the caller decides how long it is shown.
     */
    fun unmatched(
        messages: List<FlashMessageUi>,
        echoes: List<FlashOptimisticEcho>,
    ): List<FlashOptimisticEcho> {
        if (echoes.isEmpty()) return emptyList()
        val consumedPerText = mutableMapOf<String, Int>()
        val remaining = mutableListOf<FlashOptimisticEcho>()
        for (echo in echoes) {
            val text = echo.message.text
            val candidates = messages.count { it.isMine && it.text == text && it.id !in echo.knownOutgoingIds }
            val alreadyConsumed = consumedPerText[text] ?: 0
            if (alreadyConsumed < candidates) {
                consumedPerText[text] = alreadyConsumed + 1
            } else {
                remaining.add(echo)
            }
        }
        return remaining
    }
}
