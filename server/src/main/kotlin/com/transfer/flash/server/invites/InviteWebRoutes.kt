package com.transfer.flash.server.invites

import com.transfer.flash.core.common.protocol.InviteAppLink
import com.transfer.flash.server.admin.StaticAssets
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun inviteWebRoutes(route: Route, signingFingerprints: List<String>) = with(route) {
    val assets = StaticAssets("invite-ui")
    for (path in listOf(InviteAppLink.GROUP_PATH, InviteAppLink.ENROLL_PATH)) {
        get(path) {
            assets.respond(call, listOf("index.html"), cacheControl = "no-store")
        }
    }
    get("/invite/assets/invite.css") { assets.respond(call, listOf("invite.css")) }
    get("/invite/assets/invite.js") { assets.respond(call, listOf("invite.js")) }
    if (signingFingerprints.isNotEmpty()) {
        val fingerprints = signingFingerprints.distinct().joinToString(",") { "\"$it\"" }
        get("/.well-known/assetlinks.json") {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            call.respondText(
                """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.transfer.flash","sha256_cert_fingerprints":[$fingerprints]}}]""",
                ContentType.Application.Json,
            )
        }
    }
}
