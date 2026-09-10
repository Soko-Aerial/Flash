package com.transfer.flash.core.messaging.protocol

/**
 * A push-to-talk ping carried by the chat transport (v1, 2026-09-09).
 *
 * A single press of a hardware PTT button fans one of these out to every paired + online
 * peer. Fire-and-forget: no outbox row, no retry, no persistence. Receivers deduplicate on
 * [eventId] and surface a tone + notification; there is deliberately no chat-row write in v1.
 *
 * Standalone on purpose: it is NOT a [MessageWireFrame] subtype, so both hosts' exhaustive
 * `when` expressions over [MessageWireFrame] keep compiling untouched.
 */
public data class PttPingFrame(
    /** Sender-generated UUID; receivers ignore replays of a seen id. */
    val eventId: String,
    /** Authoring device id; receivers require this to equal the transport session peer. */
    val from: String,
    /** Author display name for the notification. */
    val senderName: String,
    /** Sender timestamp in ms. */
    val sentAt: Long,
)
