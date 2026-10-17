package com.transfer.flash.core.radio.chat


/**
 * Priority order for radio frame transmission (Plan R §7 / Phase R3 rule 3e):
 * EMERGENCY > TEXT > ACK > LOCATION
 */
public enum class RadioChatPriority(public val weight: Int) {
    /** Emergency traffic: highest priority, preempts all other radio traffic. */
    EMERGENCY(0),

    /** Regular chat messages (segmented or single-frame). */
    TEXT(1),

    /** Cryptographic delivery acknowledgements. */
    ACK(2),

    /** Position beacons / location reports (Wave 3). */
    LOCATION(3);
}

/**
 * Connectivity and operational status of the radio chat transport.
 */
public enum class RadioChatStatus {
    OFF,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/**
 * Inbound radio message delivered after successful authentication, decryption,
 * replay protection, deduplication, and segment reassembly.
 */
public data class RadioChatInbound(
    public val senderDeviceId: String,
    public val messageId: String,
    public val text: String,
    public val sentAtMs: Long,
    public val senderCallsign: String? = null,
)

/**
 * Cryptographic delivery receipt confirming that the recipient received and
 * authenticated the message.
 */
public data class RadioChatReceipt(
    public val messageId: String,
    public val recipientDeviceId: String,
    public val deliveredAtMs: Long,
)

/**
 * Result of submitting a message to [RadioChatEngine].
 */
public sealed interface RadioChatSendOutcome {
    /** Frame(s) accepted by driver and transmitted to TNC; waiting for cryptographic ACK. */
    public data class SentWaitingAck(public val firstCounter: Long) : RadioChatSendOutcome

    /** Radio gateway is switched off or disconnected. */
    public data class NotConnected(public val reason: String) : RadioChatSendOutcome

    /** Refused: payload text exceeds segment budget (max 3,008 bytes). */
    public data class RefusedTooLong(public val reason: String) : RadioChatSendOutcome

    /** Refused: security authorization gate declined transmission (Profile M unconfirmed). */
    public data class RefusedAuthorization(public val reason: String) : RadioChatSendOutcome

    /** Refused: recipient is not paired or has no radio session key. */
    public data class RefusedUnreachable(public val reason: String) : RadioChatSendOutcome

    /** Transmission failed permanently. */
    public data class Failed(public val reason: String) : RadioChatSendOutcome
}

/**
 * Thread-safe bounded deduplication cache for radio frames and messages.
 */
public class RadioDedupCache(private val maxSize: Int = 1000) {
    private val seen = LinkedHashSet<String>()

    public fun isDuplicate(key: String): Boolean = synchronized(seen) {
        key in seen
    }

    public fun markSeen(key: String): Unit = synchronized(seen) {
        if (seen.size >= maxSize) {
            val first = seen.first()
            seen.remove(first)
        }
        seen.add(key)
    }

    public fun clear(): Unit = synchronized(seen) {
        seen.clear()
    }
}
