package com.transfer.flash.core.ptt

import com.transfer.flash.core.messaging.protocol.PttPingFrame
import com.transfer.flash.core.messaging.ptt.PttFloorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-facing push-to-talk contract (ADR-032) — the PTT counterpart of `FlashDiscovery`
 * and `FlashNetwork`.
 *
 * The module owns floor control, capture, playout, and PTT wire decoding. The host supplies only
 * identity/policy snapshots and two transport sinks when it constructs [PttSessionEngine]. Runtime
 * microphone permission and foreground-service lifecycle remain application responsibilities.
 *
 * Every public type of the module is declared in this file on purpose: the concrete driver is the
 * only implementation, and a consumer is expected to hold this interface.
 */
public interface FlashPtt {

    /** Live floor state: `Idle`, `Talking` (local capture) or `Listening` (remote playout). */
    public val state: StateFlow<PttFloorState>

    /** Sampled session telemetry for a UI badge/notification; null while idle. */
    public val stats: StateFlow<PttSessionStats?>

    /** One-shot user-facing notices (denials, session end reasons, burst warnings). */
    public val notices: SharedFlow<String>

    /** Accepted inbound pings from trusted peers, already deduplicated on `eventId`. */
    public val pings: Flow<PttPingEvent>

    /**
     * Hardware-press / UI entry: toggles the floor. Slow-path denials (no peers, no microphone
     * grant, a live call, or an active voice-note lease) answer synchronously; the toggle itself
     * runs asynchronously, so this is safe from any thread, including main.
     */
    public fun onPttButton(): PttPressOutcome

    /**
     * Sends one `FLASH_PTT` ping to every paired + online peer. False when there is nobody to
     * ping. Blocking by contract like the transport sink itself — call it off the main thread.
     */
    public fun sendPing(): Boolean

    /**
     * Pushes one host-authored notice into the same [notices] channel the engine uses, so a host
     * with its own toast surface does not need a second path. Safe from any thread.
     */
    public fun postNotice(text: String)

    /** Local stop entry for notification actions / UI. Safe from any thread. */
    public fun stopLocal()

    /** A phone call went active: tears any live session down and refuses new capture. */
    public fun onCallStarted()

    /**
     * Voice-message capture gate. Returns a lease id when no PTT/call/other voice note holds the
     * microphone, or null when the microphone is already claimed. Only the owner of the returned
     * id can release it.
     */
    public fun acquireVoiceNoteLease(): String?

    /** Releases only the matching lease id returned by [acquireVoiceNoteLease]. */
    public fun releaseVoiceNoteLease(leaseId: String)

    /**
     * Routes one inbound text frame: PTT ping first, then PTT session control. Returns true when
     * [text] is a PTT ping/session-control frame, **including rejected frames** — a recognized but
     * unauthenticated frame must never fall through into unrelated protocol handlers. False means
     * the text is not PTT at all, so a host can chain this ahead of its own text handlers.
     */
    public fun onInboundText(peerId: String, text: String): Boolean

    /** Returns true when [data] has PTT audio magic, including malformed or stale PTT packets. */
    public fun onInboundBinary(peerId: String?, data: ByteArray): Boolean

    /** Tears the session down and releases capture/playout. Terminal: the engine is not reusable. */
    public fun shutdown()
}

/** Result of a PTT press attempt. */
public enum class PttPressOutcome { ACCEPTED, NO_PEERS, NO_MIC, CALL_ACTIVE, VOICE_NOTE_ACTIVE }

/** Which half of the half-duplex floor this device holds. */
public enum class PttRole { TALKER, LISTENER }

/** Immutable session telemetry snapshot: elapsed time, latency, loss, buffer depth, level. */
public data class PttSessionStats(
    val sessionId: String,
    val role: PttRole,
    val elapsedMs: Long,
    val rttMs: Long?,
    val lossPercent: Float,
    val depthMs: Long,
    val amplitude01: Float,
    val members: Int,
)

/** One accepted inbound PTT ping: the receiver-side half of `PttPingFrame`. */
public data class PttPingEvent(
    val eventId: String,
    val fromDeviceId: String,
    val senderName: String,
    val sentAtMs: Long,
)

/** Converts an authenticated wire ping to the public event shape. */
internal fun PttPingFrame.toEvent(): PttPingEvent = PttPingEvent(
    eventId = eventId,
    fromDeviceId = from,
    senderName = senderName,
    sentAtMs = sentAt,
)
