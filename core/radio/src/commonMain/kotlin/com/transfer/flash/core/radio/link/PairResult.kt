package com.transfer.flash.core.radio.link

import com.transfer.flash.core.radio.connect.RadioConnectFailureReason

/**
 * Result of an in-app Bluetooth pairing attempt (Plan R §6).
 */
public sealed interface PairResult {
    /** Pairing completed successfully. */
    public data object Success : PairResult

    /** Device was already bonded in the OS. */
    public data object AlreadyPaired : PairResult

    /** Pairing failed with a user-facing failure reason and next step. */
    public data class Failed(val reason: RadioConnectFailureReason) : PairResult
}
