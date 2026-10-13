package com.transfer.flash.server.admin

import com.transfer.flash.server.core.BaselineHeaders
import com.transfer.flash.server.core.canonicalPath
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * The static admin console (ADR-125): a dependency-free single page application served from the hub's own classpath
 * (`admin-ui/`), no build step, no CDN, no request to any other origin. This file holds the three pieces that make that
 * safe: the security headers every console response carries, the static asset handler (whitelisted names, ETags, no
 * directory listing, no traversal) and the routes (`/admin/` shell, assets under `/admin/ui/`, legacy URL redirects).
 */

/** The security headers of the console (and of the public enrolment landing page). One definition, asserted by tests. */
object AdminSecurityHeaders {
    /** The exact Content-Security-Policy: scripts, styles, images, fonts and connections only from the hub's own origin. */
    const val CSP: String =
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; " +
            "form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

    const val PERMISSIONS_POLICY: String =
        "accelerometer=(), autoplay=(), bluetooth=(), camera=(), display-capture=(), geolocation=(), gyroscope=(), hid=(), " +
            "magnetometer=(), microphone=(), midi=(), payment=(), serial=(), usb=(), xr-spatial-tracking=()"

    /** Header name to value, applied to every response whose path is [covers]. `Cache-Control` is separate: see [CACHE_NO_STORE]. */
    val ALL: List<Pair<String, String>> = listOf(
        "Content-Security-Policy" to CSP,
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "Permissions-Policy" to PERMISSIONS_POLICY,
        "X-Frame-Options" to "DENY",
    )

    const val CACHE_NO_STORE: String = "no-store"

    /** Assets are revalidated with their ETag on every use (cheap on a LAN, and an upgrade is never stale). */
    const val CACHE_REVALIDATE: String = "no-cache"

    /** True for the paths this policy covers: the console, and the public enrolment landing page with its assets. */
    fun covers(path: String): Boolean =
        path == "/admin" || path.startsWith("/admin/") || path == "/enroll" || path.startsWith("/enroll-assets/")
}

/**
 * Application plugin: adds [AdminSecurityHeaders] to EVERY response under the console (success, redirect, 304, 4xx, 5xx, status pages) and
 * defaults `Cache-Control` to `no-store` where the handler did not choose another value (static assets choose `no-cache` + ETag).
 */
val AdminConsoleHeaders = createApplicationPlugin("AdminConsoleHeaders") {
    onCall { call ->
        // REV-02: the decision reads the canonical path (decoded once), never the raw one, and EVERY other response of the
        // hub still gets the baseline set (nosniff, a closed CSP, no framing) and no-store.
        if (AdminSecurityHeaders.covers(call.canonicalPath())) {
            AdminSecurityHeaders.ALL.forEach { (k, v) -> if (!call.response.headers.contains(k)) call.response.headers.append(k, v) }
        } else {
            BaselineHeaders.apply(call)
        }
    }
    onCallRespond { call, _ ->
        if (!call.response.headers.contains(HttpHeaders.CacheControl)) {
            call.response.headers.append(HttpHeaders.CacheControl, AdminSecurityHeaders.CACHE_NO_STORE)
        }
    }
}

/**
 * Serves files of one classpath directory ([root], for example `admin-ui`). Only whitelisted names and extensions are
 * served: every path segment must match `[A-Za-z0-9][A-Za-z0-9._-]*`, `..` is refused, the last segment needs a known extension (so a
 * directory can never be listed), and the answer carries a strong ETag (SHA-256) so `If-None-Match` gets a 304.
 */
class StaticAssets(private val root: String) {
    private class Asset(val bytes: ByteArray, val etag: String, val type: ContentType)

    private val cache = ConcurrentHashMap<String, Asset>()

    private fun find(segments: List<String>): Asset? {
        if (segments.isEmpty() || segments.size > 6) return null
        if (!segments.all { SEGMENT.matches(it) && !it.contains("..") }) return null
        val name = segments.last()
        val type = typeOf(name) ?: return null
        val key = segments.joinToString("/")
        cache[key]?.let { return it }
        val bytes = StaticAssets::class.java.classLoader.getResourceAsStream("$root/$key")?.use { it.readNBytes(MAX_ASSET_BYTES + 1) } ?: return null
        if (bytes.size > MAX_ASSET_BYTES) return null
        val etag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)).take(27) + "\""
        return Asset(bytes, etag, type).also { cache[key] = it }
    }

    /** Responds with the asset or 404 / 304. [cacheControl] is `no-store` for the HTML shell and `no-cache` (revalidate) for assets. */
    suspend fun respond(call: ApplicationCall, segments: List<String>, cacheControl: String = AdminSecurityHeaders.CACHE_REVALIDATE) {
        val asset = find(segments)
        if (asset == null) {
            call.response.headers.append(HttpHeaders.CacheControl, AdminSecurityHeaders.CACHE_NO_STORE)
            call.respondText("Not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return
        }
        call.response.headers.append(HttpHeaders.ETag, asset.etag)
        call.response.headers.append(HttpHeaders.CacheControl, cacheControl)
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == asset.etag } == true) {
            call.respond(HttpStatusCode.NotModified)
            return
        }
        call.respondBytes(asset.bytes, asset.type)
    }

    companion object {
        private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private const val MAX_ASSET_BYTES = 2 * 1024 * 1024

        private fun typeOf(name: String): ContentType? = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
            "js", "mjs" -> ContentType.parse("text/javascript; charset=utf-8")
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "svg" -> ContentType.Image.SVG
            "json" -> ContentType.Application.Json
            "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
            "png" -> ContentType.Image.PNG
            "ico" -> ContentType.parse("image/x-icon")
            else -> null
        }
    }
}

/**
 * The console routes: `GET /admin` redirects to `/admin/` (the shell, `no-store`), `GET /admin/ui/{path...}` serves the assets, and the old
 * server-rendered URLs (`/admin/login`, `/admin/dashboard`, ...) redirect into the new shell. Everything else under `/admin/` that no
 * other route handles is a 404. The listener policy applies: on a listener that may not serve the console every one of these answers 404.
 */
fun Route.adminConsoleRoutes(web: AdminWeb, assets: StaticAssets = StaticAssets("admin-ui")) {
    route("/admin") {
        get {
            if (!web.listenerAllowed(call)) return@get call.notFoundText()
            call.respondRedirect("/admin/", permanent = false)
        }
        get("/") {
            if (!web.listenerAllowed(call)) return@get call.notFoundText()
            assets.respond(call, listOf("index.html"), AdminSecurityHeaders.CACHE_NO_STORE)
        }
        get("/ui/{path...}") {
            if (!web.listenerAllowed(call)) return@get call.notFoundText()
            assets.respond(call, call.parameters.getAll("path").orEmpty())
        }
        for ((old, target) in LEGACY_REDIRECTS) {
            get(old) {
                if (!web.listenerAllowed(call)) return@get call.notFoundText()
                call.respondRedirect(target, permanent = false)
            }
        }
    }
}

private val LEGACY_REDIRECTS = mapOf(
    "/login" to "/admin/",
    "/setup" to "/admin/#/setup",
    "/dashboard" to "/admin/#/overview",
    "/departments" to "/admin/#/organization",
    "/groups" to "/admin/#/organization",
    "/invites" to "/admin/#/invites",
    "/audit" to "/admin/#/audit",
)

private suspend fun ApplicationCall.notFoundText() = respondText("Not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
