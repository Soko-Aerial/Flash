package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.hubprotocol.HubContract
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/**
 * The one place a device REST call is admitted and authorised (CLI-01), shared by the announcements and the files
 * clients so the rule cannot drift between them:
 *
 * 1. [hubUrl][call] must be the pinned hub (same host and port), or the call is refused before any bearer exists;
 * 2. it must not be cleartext unless the debug-only opt-in allows it ([HubUrlPolicy]);
 * 3. a `401` is answered once with a forced bearer refresh, then reported as it is.
 *
 * [read] turns the response into a value while the response is still open (text, bounded bytes). A network failure in
 * [send] or [read] is [Outcome.Unreachable]; a coroutine cancellation is never swallowed.
 */
@FlashInternalApi
internal class HubRestExecutor(
    private val bearerSource: HubBearerSource?,
    private val pinnedHubUrl: () -> String?,
    private val allowCleartext: () -> Boolean,
) {

    sealed interface Outcome<out T> {
        /** The hub answered; [status] may still be an error status. */
        data class Reply<T>(val status: HttpStatusCode, val retryAfter: String?, val value: T) : Outcome<T>

        /** [call]'s URL is not the hub this device is enrolled with. */
        data object NotThisHub : Outcome<Nothing>

        /** Cleartext without the opt-in, or not a URL at all. */
        data object Insecure : Outcome<Nothing>

        /** There is a bearer source but it has no bearer (unenrolled, revoked, refresh refused). */
        data object NoBearer : Outcome<Nothing>

        /** The request did not complete: no route, DNS, TLS, reset, or a body that broke off. */
        data class Unreachable(val cause: Throwable) : Outcome<Nothing>
    }

    suspend fun <T> call(
        hubUrl: String,
        send: suspend (baseUrl: String, bearer: String?) -> HttpResponse,
        read: suspend (HttpResponse) -> T,
    ): Outcome<T> {
        val pinned = pinnedHubUrl()
        if (pinned != null && !HubUrlPolicy.sameHub(hubUrl, pinned)) return Outcome.NotThisHub
        if (HubUrlPolicy.check(hubUrl, allowCleartext()) != HubUrlPolicy.Verdict.OK) return Outcome.Insecure
        val base = HubUrlPolicy.baseUrl(hubUrl)
        var forceRefresh = false
        while (true) {
            val bearer = bearerSource?.bearer(forceRefresh)
            if (bearerSource != null && bearer == null) return Outcome.NoBearer
            val reply = runSuspendCatching {
                val response = send(base, bearer)
                Outcome.Reply(response.status, response.headers[HubContract.HEADER_RETRY_AFTER], read(response))
            }.getOrElse { return Outcome.Unreachable(it) }
            if (reply.status == HttpStatusCode.Unauthorized && bearerSource != null && !forceRefresh) {
                forceRefresh = true
                continue
            }
            return reply
        }
    }
}
