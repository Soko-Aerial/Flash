@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.HubIceConfigDto
import com.transfer.flash.core.hubprotocol.rest.HubIceServerDto
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HR9: Produces short-lived, device-scoped ICE/TURN credentials using coturn REST API HMAC-SHA1
 * (`username = "<expiryEpochSeconds>:<deviceId>"`, `credential = base64(HMAC-SHA1(secret, username))`).
 * When no STUN/TURN URLs (or valid TURN secret) are configured, [isSupported] is false so clients
 * do not advertise broken off-LAN call affordances.
 */
public class CallIceService(
    private val stunUrls: List<String> = emptyList(),
    private val turnUrls: List<String> = emptyList(),
    private val turnSharedSecret: String? = null,
    private val ttlSeconds: Long = 3600L,
    private val mediaPolicy: String = DEFAULT_MEDIA_POLICY,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val sanitizedStunUrls = stunUrls.map { it.trim() }.filter { it.isNotEmpty() }
    private val sanitizedTurnUrls = turnUrls.map { it.trim() }.filter { it.isNotEmpty() }
    private val hasTurn = sanitizedTurnUrls.isNotEmpty() && !turnSharedSecret.isNullOrBlank()
    private val hasStun = sanitizedStunUrls.isNotEmpty()

    public val isSupported: Boolean
        get() = (hasTurn || hasStun) && (mediaPolicy != "RELAY_ONLY" || hasTurn)

    public fun configFor(deviceId: String): HubIceConfigDto {
        if (!isSupported || deviceId.isBlank()) {
            return HubIceConfigDto(
                offLanCallingSupported = false,
                mediaPolicy = mediaPolicy,
                ttlSeconds = 0L,
                iceServers = emptyList(),
            )
        }
        val servers = ArrayList<HubIceServerDto>(2)
        if (hasStun && mediaPolicy != "RELAY_ONLY") {
            servers += HubIceServerDto(urls = sanitizedStunUrls)
        }
        if (hasTurn) {
            val safeTtl = ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
            val expirySeconds = (clockMs() / 1000L) + safeTtl
            val username = "$expirySeconds:$deviceId"
            val credential = hmacSha1Base64(requireNotNull(turnSharedSecret), username)
            servers += HubIceServerDto(
                urls = sanitizedTurnUrls,
                username = username,
                credential = credential,
            )
            return HubIceConfigDto(
                offLanCallingSupported = true,
                mediaPolicy = mediaPolicy,
                ttlSeconds = safeTtl,
                iceServers = servers,
            )
        }
        return HubIceConfigDto(
            offLanCallingSupported = servers.isNotEmpty(),
            mediaPolicy = mediaPolicy,
            ttlSeconds = ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS),
            iceServers = servers,
        )
    }

    public companion object {
        public const val DEFAULT_MEDIA_POLICY: String = "DIRECT_PREFERRED_RELAY_FALLBACK"
        private const val MIN_TTL_SECONDS: Long = 60L
        private const val MAX_TTL_SECONDS: Long = 86_400L

        public fun hmacSha1Base64(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
            val raw = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(raw)
        }
    }
}

internal fun Route.callConfigRoutes(
    iceService: CallIceService,
    principalResolver: HubPrincipalResolver,
    revocations: RevocationService = RevocationService.None,
    limits: HubRateLimits = HubRateLimits(),
) {
    get(HubContract.PATH_CALL_ICE) {
        val principal = call.requirePrincipal(principalResolver) ?: return@get
        if (revocations.isRevoked(principal.certSerial, principal.deviceId)) {
            call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_REVOKED, "Device certificate has been revoked")
            return@get
        }
        if (!limits.directorySync.tryAcquire(principal.deviceId)) {
            val retryAfter = limits.directorySync.retryAfterSeconds(principal.deviceId)
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many ICE config requests")
            return@get
        }
        call.respondJson(iceService.configFor(principal.deviceId))
    }
}
