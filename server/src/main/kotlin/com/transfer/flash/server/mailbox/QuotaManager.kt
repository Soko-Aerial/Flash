package com.transfer.flash.server.mailbox

/**
 * Result of a quota check evaluated before enqueuing a mailbox payload.
 */
sealed class QuotaCheckResult {
    object Allowed : QuotaCheckResult()
    data class Exceeded(val reason: String) : QuotaCheckResult()

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Manages store-and-forward mailbox storage limits and asset size policies.
 *
 * Enforces:
 * 1. Lightweight asset filter: payloads > [maxPayloadSizeBytes] (default 50 MB) are rejected with
 *    "Payload exceeds server mailbox limit; transfer via direct LAN".
 * 2. Per-device pending envelope ceiling (default 1,000 pending envelopes).
 * 3. Per-device cumulative storage quota (default 50 MB).
 */
class QuotaManager(
    val maxPayloadSizeBytes: Long = DEFAULT_MAX_PAYLOAD_SIZE_BYTES,
    val maxDeviceMailboxBytes: Long = DEFAULT_MAX_DEVICE_MAILBOX_BYTES,
    val maxPendingEnvelopesPerDevice: Int = DEFAULT_MAX_PENDING_ENVELOPES,
) {

    companion object {
        const val DEFAULT_MAX_PAYLOAD_SIZE_BYTES: Long = 50 * 1024 * 1024L // 50 MB
        const val DEFAULT_MAX_DEVICE_MAILBOX_BYTES: Long = 50 * 1024 * 1024L // 50 MB
        const val DEFAULT_MAX_PENDING_ENVELOPES: Int = 1000

        const val ERROR_PAYLOAD_EXCEEDS_LIMIT: String =
            "Payload exceeds server mailbox limit; transfer via direct LAN"
    }

    /**
     * Checks if a single envelope payload size complies with server relay policy.
     */
    fun checkPayloadSize(payloadSizeBytes: Long): QuotaCheckResult {
        if (payloadSizeBytes > maxPayloadSizeBytes) {
            return QuotaCheckResult.Exceeded(ERROR_PAYLOAD_EXCEEDS_LIMIT)
        }
        return QuotaCheckResult.Allowed
    }

    /**
     * Evaluates whether an incoming envelope payload can be safely queued for [targetDeviceId]
     * without exceeding pending envelope counts or aggregate disk quota.
     */
    fun checkDeviceQuota(
        targetDeviceId: String,
        currentQueuedBytes: Long,
        currentPendingCount: Int,
        newPayloadSizeBytes: Long,
    ): QuotaCheckResult {
        val payloadCheck = checkPayloadSize(newPayloadSizeBytes)
        if (!payloadCheck.isAllowed) {
            return payloadCheck
        }

        if (currentPendingCount >= maxPendingEnvelopesPerDevice) {
            return QuotaCheckResult.Exceeded(
                "Device '$targetDeviceId' reached maximum pending envelope limit ($maxPendingEnvelopesPerDevice)"
            )
        }

        val projectedBytes = currentQueuedBytes + newPayloadSizeBytes
        if (projectedBytes > maxDeviceMailboxBytes) {
            return QuotaCheckResult.Exceeded(
                "Device '$targetDeviceId' mailbox quota exceeded: projected $projectedBytes bytes exceeds limit of $maxDeviceMailboxBytes bytes"
            )
        }

        return QuotaCheckResult.Allowed
    }
}
