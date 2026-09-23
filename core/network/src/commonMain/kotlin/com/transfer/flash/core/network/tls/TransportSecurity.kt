package com.transfer.flash.core.network.tls

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Thrown when an engine cannot build its TLS identity and therefore refuses to start.
 *
 * There is deliberately no plaintext fallback (audit 2026-09-23, S3; AGENTS.md §19): a node that
 * "falls back to plain" runs an unauthenticated server anyone on the LAN can connect to, pairs over
 * plaintext, and can still show an "Encrypted" badge, because that badge reads the app-layer key.
 * Hosts surface this as a start error with a retry, never as a degraded-but-running engine.
 */
public class TransportSecurityUnavailableException(cause: Throwable) :
    IllegalStateException(
        "Secure transport could not be initialised; Flash will not start without TLS " +
            "(${cause::class.simpleName}: ${cause.message})",
        cause,
    )

/**
 * Builds an engine's TLS options, retrying [attempts] times before failing closed with
 * [TransportSecurityUnavailableException].
 *
 * One retry covers the transient keystore failures seen on real devices (ERROR-070: the first
 * attempt regenerates a key that lacked `DIGEST_NONE`). [onAttemptFailed] is for logging.
 */
@FlashInternalApi
public inline fun <T : Any> requireTransportSecurity(
    attempts: Int = 2,
    onAttemptFailed: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    build: () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    var last: Throwable? = null
    for (attempt in 1..attempts) {
        try {
            return build()
        } catch (e: Exception) {
            last = e
            onAttemptFailed(attempt, e)
        }
    }
    throw TransportSecurityUnavailableException(last!!)
}
