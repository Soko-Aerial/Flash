package com.transfer.flash.server.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Ktor HTTP routing extension for identity and cryptographic enrollment endpoints:
 * - GET / POST `/api/v1/auth/challenge`: Requests a fresh 32-byte server nonce with 60s TTL.
 * - POST `/api/v1/auth/verify`: Verifies client signature over (serverNonce + clientNonce + deviceId) and cert chain.
 * - POST `/api/v1/enroll`: Single-use invite token exchange for a delegated Device Certificate.
 */
fun Route.authRoutes(
    challengeAuth: ChallengeResponseAuth,
    enrollmentService: EnrollmentService,
    serverExternalUrl: String = "http://localhost:8443",
) {
    // GET /enroll: Web browser landing page for invitation links
    route("/enroll") {
        get {
            val token = call.request.queryParameters["token"] ?: ""
            val record = if (token.isNotBlank()) enrollmentService.getTokenRecord(token) else null
            val now = System.currentTimeMillis()
            val isExpired = record != null && now > record.scope.expiry
            val isUsed = record != null && record.remainingUses.get() <= 0
            val isValid = record != null && !isExpired && !isUsed

            val rootFp = com.transfer.flash.core.security.crypto.FlashCrypto.sha256FingerprintHex(enrollmentService.rootPublicKey)
            val hubUrl = call.request.queryParameters["server"]?.ifBlank { null }
                ?: serverExternalUrl
            val deepLink = if (isValid) {
                "flash://enroll?server=${java.net.URLEncoder.encode(hubUrl, "UTF-8")}&token=$token&root=$rootFp"
            } else ""

            val html = com.transfer.flash.server.admin.AdminUiHtml.webEnrollmentLandingPage(
                token = token,
                department = record?.scope?.department,
                role = record?.scope?.role ?: "MEMBER",
                deepLink = deepLink,
                hubUrl = hubUrl,
                isValid = isValid,
                isExpired = isExpired,
                isUsed = isUsed,
            )
            val status = if (isValid) HttpStatusCode.OK else HttpStatusCode.BadRequest
            call.respondText(html, ContentType.Text.Html, status)
        }
    }

    route("/api/v1") {
        route("/auth") {
            // GET /api/v1/auth/challenge
            get("/challenge") {
                val challenge = challengeAuth.generateChallenge()
                val json = """{"serverNonce":"${escapeJson(challenge.serverNonce)}","timestamp":${challenge.timestamp},"ttlSeconds":${challenge.ttlSeconds}}"""
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            // POST /api/v1/auth/challenge
            post("/challenge") {
                val challenge = challengeAuth.generateChallenge()
                val json = """{"serverNonce":"${escapeJson(challenge.serverNonce)}","timestamp":${challenge.timestamp},"ttlSeconds":${challenge.ttlSeconds}}"""
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }

            // POST /api/v1/auth/verify
            post("/verify") {
                val body = call.receiveText()
                if (body.isBlank()) {
                    call.respondText(
                        """{"error":"Empty request body"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val serverNonce = extractJsonField(body, "serverNonce")
                val clientNonce = extractJsonField(body, "clientNonce")
                val deviceId = extractJsonField(body, "deviceId")
                val signature = extractJsonField(body, "signature")

                if (serverNonce.isNullOrBlank() || clientNonce.isNullOrBlank() || deviceId.isNullOrBlank() || signature.isNullOrBlank()) {
                    call.respondText(
                        """{"error":"Missing required fields (serverNonce, clientNonce, deviceId, signature)"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val deviceCert = parseDeviceCertFromJson(body)
                if (deviceCert == null) {
                    call.respondText(
                        """{"error":"Malformed or missing deviceCert"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val delegationCert = parseDelegationCertFromJson(body)

                val result = challengeAuth.verifyChallenge(
                    serverNonce = serverNonce,
                    clientNonce = clientNonce,
                    deviceId = deviceId,
                    signature = signature,
                    deviceCert = deviceCert,
                    delegationCert = delegationCert,
                )

                result.fold(
                    onSuccess = { principal ->
                        val resp = buildString {
                            append("""{"status":"AUTHENTICATED","principal":{""")
                            append(""""deviceId":"${escapeJson(principal.deviceId)}",""")
                            append(""""role":"${escapeJson(principal.role)}",""")
                            if (principal.department != null) {
                                append(""""department":"${escapeJson(principal.department)}",""")
                            } else {
                                append(""""department":null,""")
                            }
                            append(""""certSerial":${principal.certSerial}""")
                            append("}}")
                        }
                        call.respondText(resp, ContentType.Application.Json, HttpStatusCode.OK)
                    },
                    onFailure = { error ->
                        val status = when (error) {
                            is IllegalArgumentException -> HttpStatusCode.BadRequest
                            is SecurityException -> HttpStatusCode.Unauthorized
                            else -> HttpStatusCode.Forbidden
                        }
                        val errJson = """{"error":"${escapeJson(error.message ?: "Authentication failed")}"}"""
                        call.respondText(errJson, ContentType.Application.Json, status)
                    }
                )
            }
        }

        // POST /api/v1/enroll
        post("/enroll") {
            val body = call.receiveText()
            if (body.isBlank()) {
                call.respondText(
                    """{"error":"Empty request body"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val token = extractJsonField(body, "token")
            val deviceId = extractJsonField(body, "deviceId")
            val subjectPublicKey = extractJsonField(body, "subjectPublicKey")
            val friendlyName = extractJsonField(body, "friendlyName")

            if (token.isNullOrBlank() || deviceId.isNullOrBlank() || subjectPublicKey.isNullOrBlank()) {
                call.respondText(
                    """{"error":"Missing required fields (token, deviceId, subjectPublicKey)"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val result = enrollmentService.enrollDevice(
                token = token,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                friendlyName = friendlyName,
            )

            result.fold(
                onSuccess = { enrollment ->
                    val certJson = serializeDeviceCertToJson(enrollment.deviceCert)
                    val chainJson = enrollment.delegationChain.joinToString(",", "[", "]") {
                        serializeDelegationCertToJson(it)
                    }
                    val resp = """{"status":"ENROLLED","rootFingerprint":"${escapeJson(enrollment.rootFingerprint)}","deviceCert":$certJson,"delegationChain":$chainJson}"""
                    call.respondText(resp, ContentType.Application.Json, HttpStatusCode.OK)
                },
                onFailure = { error ->
                    val status = when (error) {
                        is IllegalArgumentException -> HttpStatusCode.BadRequest
                        is SecurityException -> HttpStatusCode.Forbidden
                        is IllegalStateException -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.InternalServerError
                    }
                    val errJson = """{"error":"${escapeJson(error.message ?: "Enrollment failed")}"}"""
                    call.respondText(errJson, ContentType.Application.Json, status)
                }
            )
        }
    }
}

// ─── JSON Utilities ─────────────────────────────────────────────────────────

private fun escapeJson(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

internal fun extractJsonField(json: String, fieldName: String): String? {
    val regex = Regex(""""$fieldName"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
    val match = regex.find(json)
    if (match != null) {
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
    // Also try non-quoted fields (numbers, booleans)
    val numRegex = Regex(""""$fieldName"\s*:\s*(-?\d+)""")
    val numMatch = numRegex.find(json)
    return numMatch?.groupValues?.get(1)
}

internal fun parseDeviceCertFromJson(json: String): DeviceCert? {
    return try {
        val certBlock = if (json.contains("\"deviceCert\"")) {
            val start = json.indexOf("\"deviceCert\"")
            val openBrace = json.indexOf('{', start)
            val closeBrace = json.indexOf('}', openBrace)
            if (openBrace != -1 && closeBrace != -1) json.substring(openBrace, closeBrace + 1) else json
        } else {
            json
        }

        val deviceId = extractJsonField(certBlock, "deviceId") ?: return null
        val subjectPublicKey = extractJsonField(certBlock, "subjectPublicKey") ?: return null
        val role = extractJsonField(certBlock, "role") ?: DeviceCert.ROLE_MEMBER
        val department = extractJsonField(certBlock, "department")
        val validFrom = extractJsonField(certBlock, "validFrom")?.toLongOrNull() ?: return null
        val validTo = extractJsonField(certBlock, "validTo")?.toLongOrNull() ?: return null
        val serial = extractJsonField(certBlock, "serial")?.toLongOrNull() ?: return null
        val issuerPublicKey = extractJsonField(certBlock, "issuerPublicKey") ?: return null
        val issuerSignature = extractJsonField(certBlock, "issuerSignature") ?: return null

        DeviceCert(
            deviceId = deviceId,
            subjectPublicKey = subjectPublicKey,
            role = role,
            department = department,
            validFrom = validFrom,
            validTo = validTo,
            serial = serial,
            issuerPublicKey = issuerPublicKey,
            issuerSignature = issuerSignature,
        )
    } catch (_: Exception) {
        null
    }
}

internal fun parseDelegationCertFromJson(json: String): DelegationCert? {
    return try {
        if (!json.contains("\"delegationCert\"")) return null
        val start = json.indexOf("\"delegationCert\"")
        val openBrace = json.indexOf('{', start)
        val closeBrace = json.indexOf('}', openBrace)
        if (openBrace == -1 || closeBrace == -1) return null
        val block = json.substring(openBrace, closeBrace + 1)

        val rootPublicKey = extractJsonField(block, "rootPublicKey") ?: return null
        val delegatePublicKey = extractJsonField(block, "delegatePublicKey") ?: return null
        val validFrom = extractJsonField(block, "validFrom")?.toLongOrNull() ?: return null
        val validTo = extractJsonField(block, "validTo")?.toLongOrNull() ?: return null
        val maxDevices = extractJsonField(block, "maxDevices")?.toIntOrNull() ?: 100
        val serial = extractJsonField(block, "serial")?.toLongOrNull() ?: return null
        val signature = extractJsonField(block, "signature") ?: return null

        // Parse scopes array: ["scope1", "scope2"]
        val scopes = mutableSetOf<String>()
        val scopesMatch = Regex(""""scopes"\s*:\s*\[(.*?)\]""").find(block)
        if (scopesMatch != null) {
            val items = scopesMatch.groupValues[1].split(",")
            for (item in items) {
                val clean = item.trim().trim('"')
                if (clean.isNotEmpty()) scopes.add(clean)
            }
        }
        if (scopes.isEmpty()) scopes.add("enrol:member")

        DelegationCert(
            rootPublicKey = rootPublicKey,
            delegatePublicKey = delegatePublicKey,
            scopes = scopes,
            validFrom = validFrom,
            validTo = validTo,
            maxDevices = maxDevices,
            serial = serial,
            signature = signature,
        )
    } catch (_: Exception) {
        null
    }
}

private fun serializeDeviceCertToJson(cert: DeviceCert): String {
    return buildString {
        append("{")
        append(""""deviceId":"${escapeJson(cert.deviceId)}",""")
        append(""""subjectPublicKey":"${escapeJson(cert.subjectPublicKey)}",""")
        append(""""role":"${escapeJson(cert.role)}",""")
        if (cert.department != null) {
            append(""""department":"${escapeJson(cert.department)}",""")
        } else {
            append(""""department":null,""")
        }
        append(""""validFrom":${cert.validFrom},""")
        append(""""validTo":${cert.validTo},""")
        append(""""serial":${cert.serial},""")
        append(""""issuerPublicKey":"${escapeJson(cert.issuerPublicKey)}",""")
        append(""""issuerSignature":"${escapeJson(cert.issuerSignature)}"""")
        append("}")
    }
}

private fun serializeDelegationCertToJson(cert: DelegationCert): String {
    val scopesJson = cert.scopes.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" }
    return buildString {
        append("{")
        append(""""rootPublicKey":"${escapeJson(cert.rootPublicKey)}",""")
        append(""""delegatePublicKey":"${escapeJson(cert.delegatePublicKey)}",""")
        append(""""scopes":$scopesJson,""")
        append(""""validFrom":${cert.validFrom},""")
        append(""""validTo":${cert.validTo},""")
        append(""""maxDevices":${cert.maxDevices},""")
        append(""""serial":${cert.serial},""")
        append(""""signature":"${escapeJson(cert.signature)}"""")
        append("}")
    }
}
