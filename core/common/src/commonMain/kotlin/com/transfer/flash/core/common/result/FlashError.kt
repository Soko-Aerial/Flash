package com.transfer.flash.core.common.result

/**
 * Well-typed, exhaustive sealed hierarchy representing failure reasons across Flash operations.
 */
public sealed interface FlashError {
    /** Network connectivity is missing or unavailable. */
    public data class NetworkUnavailable(val message: String? = null) : FlashError

    /** Targeted peer device is unreachable or disconnected. */
    public data class PeerUnavailable(val deviceId: String, val message: String? = null) : FlashError

    /** An operation or connection attempt timed out. */
    public data class ConnectionTimeout(val timeoutMs: Long, val message: String? = null) : FlashError

    /** Protocol version mismatch between peers. */
    public data class ProtocolMismatch(val expected: Int, val actual: Int) : FlashError

    /** File transfer or chunking operation failed. */
    public data class TransferFailed(val transferId: String, val reason: String) : FlashError

    /** Cryptographic hash or byte-count verification failed. */
    public data class VerificationFailed(val expectedHash: String, val actualHash: String) : FlashError

    /** Filesystem or Storage Access Framework I/O error. */
    public data class StorageError(val message: String, val cause: Throwable? = null) : FlashError

    /** Operation was explicitly cancelled by the user or system. */
    public data class Cancelled(val reason: String? = null) : FlashError

    /** General or unexpected error. */
    public data class Unknown(val message: String, val cause: Throwable? = null) : FlashError
}
