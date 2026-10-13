package com.transfer.flash.server.core

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey

/*
 * REV-02: ONE definition of "the path of this request" for every security decision (the API gate, the rate limiters, the
 * security headers). Ktor's routing percent-decodes the path and drops empty segments, so a check on the RAW path
 * (`/%61pi/v1/...`, `//api/v1/...`, `/api//v1/...`) skipped the gate and the headers while the route still matched.
 * The guard canonicalises once, refuses what has no honest reading, and every later check reads the canonical form.
 */

/** The canonical path of the call (decoded once, duplicate slashes and `.` segments removed), set by [installRequestPathGuard]. */
val CanonicalPathAttribute: AttributeKey<String> = AttributeKey("flash.hub.canonical-path")

/** The canonical path set by the guard, or the raw path when the guard is not installed (a test mounting a bare route). */
fun ApplicationCall.canonicalPath(): String =
    attributes.getOrNull(CanonicalPathAttribute) ?: CanonicalPath.of(request.local.uri) ?: request.local.uri.substringBefore('?')

object CanonicalPath {

    /**
     * The canonical form of the path part of [requestTarget], or null when the path must be refused: no leading slash,
     * a backslash, a control character, an encoded slash / backslash / NUL (`%2f %5c %00`), a double-encoded percent
     * (`%25`), a malformed escape, or a `..` segment.
     */
    fun of(requestTarget: String): String? {
        val raw = requestTarget.substringBefore('?').substringBefore('#')
        if (!raw.startsWith("/")) return null
        if (raw.any { it == '\\' || it.code < 0x20 || it.code == 0x7F }) return null
        val lower = raw.lowercase()
        if ("%2f" in lower || "%5c" in lower || "%00" in lower || "%25" in lower) return null
        val out = ArrayList<String>()
        for (segment in raw.split('/')) {
            if (segment.isEmpty()) continue
            val decoded = decode(segment) ?: return null
            if (decoded.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }) return null
            when (decoded) {
                "." -> continue
                ".." -> return null
            }
            out += decoded
        }
        return "/" + out.joinToString("/")
    }

    private fun decode(segment: String): String? {
        if ('%' !in segment) return segment
        val bytes = java.io.ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%') {
                val hi = hex(segment.getOrNull(i + 1))
                val lo = hex(segment.getOrNull(i + 2))
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun hex(c: Char?): Int = when (c) {
        in '0'..'9' -> c!! - '0'
        in 'a'..'f' -> c!! - 'a' + 10
        in 'A'..'F' -> c!! - 'A' + 10
        else -> -1
    }
}

/**
 * Installs the guard at the front of the pipeline: it sets [CanonicalPathAttribute], or answers 400 (with the baseline
 * security headers) and finishes the call for a path that has no honest reading. Install it BEFORE the API gate.
 */
fun Application.installRequestPathGuard() {
    intercept(ApplicationCallPipeline.Setup) {
        val canonical = CanonicalPath.of(call.request.local.uri)
        if (canonical == null) {
            BaselineHeaders.apply(call)
            call.respondText(
                """{"code":"${HubErrorCodes.ERR_BAD_REQUEST}","message":"Bad request path."}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            finish()
            return@intercept
        }
        call.attributes.put(CanonicalPathAttribute, canonical)
    }
}

/** Headers every response of the hub carries, whatever the path (the console adds its stricter set on top). */
object BaselineHeaders {
    private val ALL = listOf(
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'",
        "X-Frame-Options" to "DENY",
        "Cross-Origin-Resource-Policy" to "same-origin",
    )

    fun apply(call: ApplicationCall) {
        for ((k, v) in ALL) if (!call.response.headers.contains(k)) call.response.headers.append(k, v)
    }
}
