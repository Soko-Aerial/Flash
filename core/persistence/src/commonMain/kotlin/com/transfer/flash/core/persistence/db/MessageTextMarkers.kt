package com.transfer.flash.core.persistence.db

/**
 * The two internal markers that live in a message's `text` column instead of human-readable content.
 *
 * A voice note carries no body, so its duration and waveform are packed into `text` (`vmsg:<durationMs>:<amplitudes>`)
 * and a call event is packed there too (`cmsg:<KIND>:<0|1>:<durationMs>`); both are rendered back into a card by the
 * chat repository, and the chat list shows a label rather than the blob ("Voice message", "Missed voice call").
 *
 * They are declared here, next to the queries that must ignore them, because **content search must never match them**
 * (F36 of the 2026-10-22 review): a waveform's digits and commas match ordinary queries such as "45", and the raw blob
 * then leaks into the result snippet (`…vmsg:4500:10,24,50,88…`). The encoders and decoders in
 * `RealFlashChatRepository` alias these constants, so the wire-ish format has one definition.
 */
public object MessageTextMarkers {

    /** Voice note: `vmsg:<durationMs>:<amplitude>[,<amplitude>]*`. */
    public const val VOICE: String = "vmsg:"

    /** Call event: `cmsg:<KIND>:<video 0|1>:<durationMs>`. */
    public const val CALL: String = "cmsg:"
}
