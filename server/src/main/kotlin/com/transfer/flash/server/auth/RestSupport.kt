package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.security.SecureRandom

/**
 * Helpers shared by every REST route so all of them speak the contract of `docs/protocol.md` (ADR-109): JSON bodies
 * built only from `:core:hub-protocol` DTOs, errors as `ErrorBody {code, message, correlationId}` with a FIXED sentence
 * (never exception text: SEC-16).
 */

private val correlationRandom = SecureRandom()

internal fun newCorrelationId(): String {
    val bytes = ByteArray(6).also(correlationRandom::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Responds with a JSON-encoded DTO. */
internal suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(HubRestCodec.encode(value), ContentType.Application.Json, status)
}

/**
 * Responds with the contract error body. The caller's `X-Correlation-Id` is echoed when it sent one, otherwise a new
 * id is generated; either way it is returned in the header and the body so a log line can be matched to a report.
 */
internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    val correlationId = request.headers[HubContract.HEADER_CORRELATION_ID]
        ?.takeIf { it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        ?: newCorrelationId()
    response.header(HubContract.HEADER_CORRELATION_ID, correlationId)
    respondText(
        HubRestCodec.encode(ErrorBody(code = code, message = message, correlationId = correlationId)),
        ContentType.Application.Json,
        status,
    )
}

/** The HTTP status that goes with a [HubErrorCodes] code. */
internal fun statusFor(code: String): HttpStatusCode = when (code) {
    HubErrorCodes.ERR_AUTH, HubErrorCodes.ERR_EXPIRED -> HttpStatusCode.Unauthorized
    HubErrorCodes.ERR_REVOKED, HubErrorCodes.ERR_FORBIDDEN, HubErrorCodes.ERR_NOT_MEMBER -> HttpStatusCode.Forbidden
    HubErrorCodes.ERR_NOT_FOUND, HubErrorCodes.ERR_UNKNOWN_RECIPIENT -> HttpStatusCode.NotFound
    HubErrorCodes.ERR_CONFLICT -> HttpStatusCode.Conflict
    HubErrorCodes.ERR_RATE_LIMIT, HubErrorCodes.ERR_QUOTA_EXCEEDED -> HttpStatusCode.TooManyRequests
    HubErrorCodes.ERR_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
    HubErrorCodes.ERR_INTERNAL -> HttpStatusCode.InternalServerError
    else -> HttpStatusCode.BadRequest
}
