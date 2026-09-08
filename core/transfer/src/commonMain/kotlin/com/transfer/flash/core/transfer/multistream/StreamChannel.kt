package com.transfer.flash.core.transfer.multistream

/**
 * One independent send path (C5.7) — typically one TCP socket inside a multi-stream session, but
 * anything frame-shaped works (tests use in-memory fakes). Pure logic lives in
 * [MultiStreamDispatcher]; implementations only push opaque FLSH v2 frames
 * (`ChunkFrame.serialize` output) and report delivery.
 *
 * Deviation note (documented per task spec): the spec asked for `fun interface`; Kotlin forbids
 * abstract properties (`val id`) on functional interfaces, so [StreamChannel] is a normal
 * single-purpose interface instead. [StreamChannelFactory] below remains a true `fun interface`.
 *
 * ## Contract
 *
 * - [sendFrame] is called from at most ONE dispatcher worker coroutine at a time per channel —
 *   no internal locking is required for thread safety, but implementations must be safe against
 *   sequential suspension.
 * - Return **true** = bytes handed to the transport (delivery to the remote peer is eventually
 *   confirmed by ACK_BATCH frames, never by this return value).
 * - Return **false** OR throw = this path is unusable. The dispatcher marks the channel dead,
 *   returns every un-ACKed claimed chunk of this channel to the shared pool, and continues on
 *   surviving channels; failure isolation is the whole point of N streams.
 */
public interface StreamChannel {

    /** Stable identity used for telemetry, end-game owner election, and ACK routing. */
    public val id: Int

    /**
     * Sends one serialized FLSH v2 frame. Suspending is expected (socket writes); returning
     * `false`/throwing signals permanent death of THIS channel only.
     */
    public suspend fun sendFrame(frameBytes: ByteArray): Boolean
}

/**
 * Opens send paths on demand. [open] is called once per planned channel id (`0 until n`) before
 * dispatch starts; returning **null** means "cannot open more streams" and that slot is simply
 * skipped — the transfer proceeds with however many channels opened successfully (>= 1 required,
 * otherwise the transfer fails immediately).
 *
 * [peerDeviceId] is the intended recipient (when known, null otherwise) so factories can route
 * every channel of this session to the correct peer instead of an arbitrary live one.
 */
public fun interface StreamChannelFactory {

    public suspend fun open(channelId: Int, peerDeviceId: String?): StreamChannel?
}
