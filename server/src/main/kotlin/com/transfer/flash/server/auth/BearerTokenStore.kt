package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.server.relay.HubPrincipalResolver
import io.ktor.server.application.ApplicationCall
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Opaque, short-lived REST bearer tokens issued by `POST /api/v1/auth/verify` (ADR-109).
 *
 * A token is 32 random bytes (Base64URL). Only a SHA-256 of it is kept, so a memory dump of the hub does not yield
 * usable credentials. Tokens live [HubContract.BEARER_TTL_SECONDS] and are never renewed: the device authenticates
 * again, which re-checks the certificate and the revocation list.
 *
 * Every [resolve] re-checks revocation (SEC-05): a device revoked after its token was issued is refused on its very
 * next request, not when the token expires. Nothing is persisted (a restart invalidates all tokens by design).
 */
class BearerTokenStore(
    private val ttlMs: Long = HubContract.BEARER_TTL_SECONDS * 1000L,
    private val maxTokens: Int = 20_000,
    private val random: SecureRandom = SecureRandom(),
    private val revocations: RevocationService = RevocationService.None,
) {
    private data class Entry(val principal: HubPrincipal, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Issues a token for [principal]; returns the token and its absolute expiry. */
    fun issue(principal: HubPrincipal, nowMs: Long = System.currentTimeMillis()): Pair<String, Long> {
        if (entries.size >= maxTokens) purgeExpired(nowMs)
        if (entries.size >= maxTokens) {
            // Still full of live tokens: drop the oldest-expiring ones instead of refusing a legitimate device.
            entries.entries.sortedBy { it.value.expiresAt }.take(maxTokens / 10 + 1).forEach { entries.remove(it.key) }
        }
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = nowMs + ttlMs
        entries[hash(token)] = Entry(principal, expiresAt)
        return token to expiresAt
    }

    /** The principal for [token], or null when it is unknown or expired. */
    fun resolve(token: String, nowMs: Long = System.currentTimeMillis()): HubPrincipal? {
        val key = hash(token)
        val entry = entries[key] ?: return null
        if (nowMs > entry.expiresAt) {
            entries.remove(key)
            return null
        }
        if (revocations.isRevoked(entry.principal.certSerial, entry.principal.deviceId)) {
            entries.remove(key)
            return null
        }
        return entry.principal
    }

    /** Forgets every token of [deviceId] (called when a device is revoked). */
    fun revokeDevice(deviceId: String) {
        entries.entries.removeIf { it.value.principal.deviceId == deviceId }
    }

    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        entries.entries.removeIf { nowMs > it.value.expiresAt }
    }

    val size: Int get() = entries.size

    private fun hash(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }
}

/** [HubPrincipalResolver] that reads `Authorization: Bearer <token>` and looks it up in a [BearerTokenStore]. */
class BearerPrincipalResolver(private val store: BearerTokenStore) : HubPrincipalResolver {
    override suspend fun resolve(call: ApplicationCall): HubPrincipal? {
        val header = call.request.headers[HubContract.HEADER_AUTHORIZATION] ?: return null
        if (!header.startsWith(HubContract.BEARER_PREFIX, ignoreCase = true)) return null
        val token = header.substring(HubContract.BEARER_PREFIX.length).trim()
        if (token.isEmpty()) return null
        return store.resolve(token)
    }
}
