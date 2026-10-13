package com.transfer.flash.server.observability

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.security.MessageDigest

/**
 * Decides who may read operational detail (`/health/detail`, `/metrics`). Those pages name devices, queue depths and
 * the hub's behaviour, so they are never public (SEC-16, REL-13).
 */
fun interface ManagementGuard {
    fun allows(call: ApplicationCall): Boolean
}

/**
 * Default guard: a request is allowed when it carries `Authorization: Bearer <token>` equal to [token] (constant-time
 * compare), or, when [allowLoopback] is true, when it comes from a loopback address AND was not forwarded by a proxy.
 * The forwarded-header test matters: behind nginx/caddy every request reaches the hub from 127.0.0.1, so "loopback"
 * alone would expose the page to the internet.
 *
 * With no token and [allowLoopback] false nothing is allowed (fail closed). SERVER-A's admin API can supply its own
 * [ManagementGuard] to require an admin session instead.
 */
class DefaultManagementGuard(
    private val token: String? = null,
    private val allowLoopback: Boolean = true,
) : ManagementGuard {

    override fun allows(call: ApplicationCall): Boolean {
        val expected = token
        if (!expected.isNullOrEmpty()) {
            val header = call.request.headers["Authorization"]
            if (header != null && header.startsWith("Bearer ") && constantTimeEquals(header.removePrefix("Bearer "), expected)) {
                return true
            }
        }
        return allowLoopback && isDirectLoopback(call)
    }

    private fun isDirectLoopback(call: ApplicationCall): Boolean {
        val headers = call.request.headers
        if (headers["X-Forwarded-For"] != null || headers["Forwarded"] != null || headers["X-Real-IP"] != null) return false
        val address = call.request.origin.remoteAddress
        return address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1" || address == "localhost"
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
