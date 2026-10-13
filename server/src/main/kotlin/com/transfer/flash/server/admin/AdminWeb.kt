package com.transfer.flash.server.admin

import com.transfer.flash.server.auth.ClientAddressResolver
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import java.net.URI
import java.security.MessageDigest

/**
 * Which local listener may serve the administration console (SEC-06). The console belongs on the management listener
 * (`FLASH_ADMIN_PORT`, loopback by default); it is served on the public port only when the operator opted in
 * (`FLASH_ADMIN_ON_PUBLIC_PORT`).
 */
class AdminListenerPolicy(
    private val publicPort: Int,
    private val adminPort: Int,
    private val adminOnPublicPort: Boolean,
) {
    /** True when a request that arrived on [localPort] may reach `/admin`. */
    fun allows(localPort: Int): Boolean = adminOnPublicPort || adminPort == publicPort || localPort == adminPort

    companion object {
        /** Everything allowed: for tests of the console routes on their own and for single-port setups. */
        val Open = AdminListenerPolicy(publicPort = 0, adminPort = 0, adminOnPublicPort = true)
    }
}

/**
 * Anti-CSRF checks for the console (SEC-13). Three independent layers:
 *  1. `SameSite=Strict` session cookie (set by [AdminWeb]);
 *  2. the request's `Sec-Fetch-Site` / `Origin` must show it came from the console itself;
 *  3. every state-changing JSON API call must carry the session's own CSRF token in `X-CSRF-Token`.
 */
object CsrfGuard {
    const val HEADER = "X-CSRF-Token"

    enum class Verdict { OK, BAD_ORIGIN, BAD_TOKEN }

    private val SAFE = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

    fun isSafe(method: HttpMethod): Boolean = method in SAFE

    /**
     * @param origin the request's `Origin` header or null; @param secFetchSite its `Sec-Fetch-Site` or null;
     * @param hostAuthority the `Host` header (`host[:port]`); @param externalUrl the configured public URL or null;
     * @param requireToken true for the JSON API (a matching [csrfToken] must be presented for [sessionToken]).
     */
    fun check(
        method: HttpMethod,
        origin: String?,
        secFetchSite: String?,
        hostAuthority: String?,
        externalUrl: String?,
        csrfToken: String?,
        sessionToken: String?,
        requireToken: Boolean,
    ): Verdict {
        if (isSafe(method)) return Verdict.OK
        if (secFetchSite != null && secFetchSite != "same-origin" && secFetchSite != "none") return Verdict.BAD_ORIGIN
        if (origin != null && !originAllowed(origin, hostAuthority, externalUrl)) return Verdict.BAD_ORIGIN
        if (requireToken) {
            if (csrfToken.isNullOrEmpty() || sessionToken.isNullOrEmpty()) return Verdict.BAD_TOKEN
            if (!MessageDigest.isEqual(csrfToken.toByteArray(), sessionToken.toByteArray())) return Verdict.BAD_TOKEN
        }
        return Verdict.OK
    }

    private fun originAllowed(origin: String, hostAuthority: String?, externalUrl: String?): Boolean {
        if (origin == "null") return false
        val authority = try {
            val uri = URI(origin)
            if (uri.host == null) return false
            if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        } catch (_: Exception) {
            return false
        }
        if (hostAuthority != null && authority.equals(hostAuthority, ignoreCase = true)) return true
        val external = externalUrl?.let { runCatching { URI(it) }.getOrNull() }
        if (external?.host != null) {
            val ext = if (external.port == -1) external.host else "${external.host}:${external.port}"
            if (authority.equals(ext, ignoreCase = true)) return true
        }
        return false
    }
}

/**
 * Cookie, session and CSRF plumbing shared by the console's HTML routes and its JSON API. The session cookie is
 * `__Host-flash_admin_session` (Secure, Path=/, no Domain) whenever the request arrived over TLS and
 * `flash_admin_session` otherwise (development over plain http).
 */
class AdminWeb(
    val admins: AdminAuthService,
    val addresses: ClientAddressResolver = ClientAddressResolver(),
    val directTls: Boolean = false,
    val externalUrl: String? = null,
    val policy: AdminListenerPolicy = AdminListenerPolicy.Open,
) {
    fun isHttps(call: ApplicationCall): Boolean = addresses.isHttps(call, directTls)

    fun cookieName(call: ApplicationCall): String = if (isHttps(call)) SECURE_COOKIE else PLAIN_COOKIE

    fun clientIp(call: ApplicationCall): String = addresses.clientIp(call)

    /** The key limiters and lockouts count under: [clientIp] with IPv6 folded to its /64 (REV-10). */
    fun clientKey(call: ApplicationCall): String = addresses.clientKey(call)

    /** True when the console may answer on the listener this call arrived on. */
    fun listenerAllowed(call: ApplicationCall): Boolean = policy.allows(call.request.local.localPort)

    fun session(call: ApplicationCall): AdminSession? =
        admins.validateSession(call.request.cookies[cookieName(call)])

    fun setSessionCookie(call: ApplicationCall, session: AdminSession) {
        val https = isHttps(call)
        call.response.cookies.append(
            Cookie(
                name = cookieName(call),
                value = session.token,
                maxAge = (AdminAuthService.SESSION_TTL_MS / 1000L).toInt(),
                path = if (https) "/" else "/admin",
                httpOnly = true,
                secure = https,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }

    fun clearSessionCookie(call: ApplicationCall) {
        val https = isHttps(call)
        call.response.cookies.append(
            Cookie(
                name = cookieName(call),
                value = "",
                maxAge = 0,
                path = if (https) "/" else "/admin",
                httpOnly = true,
                secure = https,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }

    fun csrfVerdict(call: ApplicationCall, session: AdminSession?, requireToken: Boolean): CsrfGuard.Verdict {
        val h = call.request.headers
        return CsrfGuard.check(
            method = call.request.httpMethod,
            origin = h["Origin"],
            secFetchSite = h["Sec-Fetch-Site"],
            hostAuthority = h["Host"],
            externalUrl = externalUrl,
            csrfToken = h[CsrfGuard.HEADER],
            sessionToken = session?.csrfToken,
            requireToken = requireToken,
        )
    }

    companion object {
        const val SECURE_COOKIE = "__Host-flash_admin_session"
        const val PLAIN_COOKIE = "flash_admin_session"
    }
}
