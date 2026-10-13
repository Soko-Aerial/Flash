package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.server.HubServices
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The static admin console as the browser meets it (ADR-125, closes SEC-11): every asset carries the exact security headers, the
 * shell has no inline code, no asset can be reached by a crafted path, errors carry the headers too, and the repository itself holds no
 * pattern the policy would forbid. The assertions run against the real, fully wired hub, not a hand-built route.
 */
class AdminConsoleWebTest {

    private val dir: File = Files.createTempDirectory("flash-console-web").toFile()
    private val built = ArrayList<HubServices>()

    @AfterTest
    fun cleanUp() {
        built.forEach { runCatching { it.database.close() } }
        dir.deleteRecursively()
    }

    private fun devConfig(port: Int = 18444) = ServerConfig(
        port = port, adminPort = port, adminOnPublicPort = true, host = "127.0.0.1", dataDir = dir.path,
        tlsMode = "none", allowInsecure = true, externalUrl = "http://localhost:$port", devEmbeddedRoot = true,
    )

    private fun build(): HubServices = HubServices.build(devConfig()).also { built += it }

    /** The classpath directory of a resource root, as files (the Gradle test runtime has resources as plain directories). */
    private fun resourceRoot(name: String): File {
        val url = AdminConsoleWebTest::class.java.classLoader.getResource(name)
        assertNotNull(url, "$name is on the classpath")
        return File(url.toURI())
    }

    private fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    private fun assertConsoleHeaders(response: HttpResponse, what: String) {
        for ((name, value) in AdminSecurityHeaders.ALL) {
            assertEquals(value, response.headers[name], "$what: header $name")
        }
        assertEquals(1, response.headers.getAll("Content-Security-Policy")?.size, "$what: exactly one CSP header")
    }

    // ------------------------------------------------------------------------------------------------ REV-02

    @Test
    fun theConsoleHeadersAndTheApiGateCannotBeBypassedWithAnEncodedOrDoubledPath() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }

        // The console behind an encoded first letter is the console: same page, same headers (the review's probe served it bare).
        for (path in listOf("/%61dmin/", "/admin//", "/%61dmin/ui/js/main.js")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertConsoleHeaders(r, path)
        }
        // The API gate sees the same path the router does: no token, no data, and the unauthenticated limiter counts it.
        for (path in listOf("/api/v1/directory/members", "/%61pi/v1/directory/members", "/api//v1/directory/members", "/api/v1/directory/members/")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, r.status, path)
        }
        // A path with no honest reading is a 400 with the baseline headers, never a route.
        for (path in listOf("/api/v1/%2e%2e/admin/", "/admin/ui/%2e%2e/%2e%2e/etc/passwd", "/api/v1%2fdirectory", "/%2561dmin/")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, r.status, path)
            assertEquals("nosniff", r.headers["X-Content-Type-Options"], path)
        }
        // Every other response (API errors, health, 404) carries the baseline set too.
        for (path in listOf("/api/v1/directory/members", "/health", "/no-such-route")) {
            val r = client.get(path)
            assertEquals("nosniff", r.headers["X-Content-Type-Options"], path)
            assertEquals("DENY", r.headers["X-Frame-Options"], path)
            assertTrue(r.headers["Content-Security-Policy"].orEmpty().contains("default-src 'none'"), path)
            assertEquals("no-store", r.headers["Cache-Control"], path)
        }
    }

    // ------------------------------------------------------------------------------------------------ the policy

    @Test
    fun theContentSecurityPolicyIsTheExactStringAndNeverAllowsInlineCodeOrOutsideOrigins() {
        assertEquals(
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; " +
                "form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
            AdminSecurityHeaders.CSP,
        )
        assertFalse(AdminSecurityHeaders.CSP.contains("unsafe-inline"))
        assertFalse(AdminSecurityHeaders.CSP.contains("unsafe-eval"))
        assertFalse(Regex("https?:").containsMatchIn(AdminSecurityHeaders.CSP), "no outside origin is named")
        assertFalse(AdminSecurityHeaders.CSP.contains('*'))
        assertEquals("nosniff", AdminSecurityHeaders.ALL.toMap()["X-Content-Type-Options"])
        assertEquals("no-referrer", AdminSecurityHeaders.ALL.toMap()["Referrer-Policy"])
        assertEquals("same-origin", AdminSecurityHeaders.ALL.toMap()["Cross-Origin-Opener-Policy"])
        val permissions = AdminSecurityHeaders.PERMISSIONS_POLICY
        for (feature in listOf("camera", "microphone", "geolocation", "usb", "payment")) assertTrue("$feature=()" in permissions, feature)
    }

    @Test
    fun everyAssetOfTheConsoleAndOfTheLandingPageIsServedWithTheExactHeadersAndATypeThatMatches() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val adminFiles = filesUnder(resourceRoot("admin-ui"))
        val landingFiles = filesUnder(resourceRoot("enroll-ui"))
        assertTrue(adminFiles.size > 30, "the console has its files: $adminFiles")
        assertTrue("index.html" in adminFiles && "js/main.js" in adminFiles && "css/tokens.css" in adminFiles)
        assertEquals(listOf("landing.css", "landing.js"), landingFiles)

        val targets = adminFiles.filter { it != "index.html" }.map { "/admin/ui/$it" } + landingFiles.map { "/enroll-assets/$it" } + "/admin/"
        for (path in targets) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertConsoleHeaders(r, path)
            val type = r.headers["Content-Type"].orEmpty()
            when {
                path.endsWith(".js") -> assertTrue(type.startsWith("text/javascript"), "$path $type")
                path.endsWith(".css") -> assertTrue(type.startsWith("text/css"), "$path $type")
                path.endsWith(".svg") -> assertTrue(type.startsWith("image/svg+xml"), "$path $type")
                path == "/admin/" -> assertTrue(type.startsWith("text/html"), "$path $type")
            }
            if (path == "/admin/") {
                assertEquals("no-store", r.headers["Cache-Control"], "the shell is never cached")
            } else {
                assertEquals("no-cache", r.headers["Cache-Control"], "$path is revalidated, not frozen")
                assertNotNull(r.headers["ETag"], path)
            }
        }
    }

    @Test
    fun anAssetIsRevalidatedWithItsEtagAndAnUnchangedOneAnswers304() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val first = client.get("/admin/ui/js/main.js")
        val etag = first.headers["ETag"]
        assertNotNull(etag)
        val again = client.get("/admin/ui/js/main.js") { header("If-None-Match", etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
        assertConsoleHeaders(again, "304")
        val changed = client.get("/admin/ui/js/main.js") { header("If-None-Match", "\"something-else\"") }
        assertEquals(HttpStatusCode.OK, changed.status)
    }

    // ------------------------------------------------------------------------------------------------ the shell

    @Test
    fun theShellHasNoInlineScriptNoInlineHandlerNoStyleAttributeAndNoOutsideReference() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val html = client.get("/admin/").bodyAsText()
        assertNoInlineCode(html, "index.html")
        for (tag in Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            assertTrue(Regex("src=\"/admin/ui/[^\"]+\"").containsMatchIn(tag.value), "a script tag loads from this hub only: ${tag.value}")
        }
        for (tag in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            assertTrue(Regex("href=\"/admin/ui/[^\"]+\"").containsMatchIn(tag.value), "a link tag loads from this hub only: ${tag.value}")
        }
        assertFalse(Regex("<script\\b[^>]*>\\s*[^<\\s]", RegexOption.IGNORE_CASE).containsMatchIn(html), "no script tag has a body")
    }

    private fun assertNoInlineCode(html: String, name: String) {
        assertFalse(Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: no inline event handler")
        assertFalse(Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: no style attribute")
        assertFalse(Regex("<style\\b", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: no style element")
        assertFalse(Regex("javascript:", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: no javascript: URL")
        assertFalse(Regex("<script\\b(?![^>]*\\bsrc=)[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: no inline script")
    }

    // ------------------------------------------------------------------------------------------------ the repository

    @Test
    fun noSourceFileOfTheConsoleOrTheLandingPageContainsAPatternTheSecurityPolicyForbids() {
        val roots = listOf(resourceRoot("admin-ui"), resourceRoot("enroll-ui"))
        val forbidden = listOf(
            "innerHTML" to Regex("innerHTML"),
            "outerHTML" to Regex("outerHTML"),
            "insertAdjacentHTML" to Regex("insertAdjacentHTML"),
            "eval(" to Regex("(?<![A-Za-z0-9_.])eval\\s*\\("),
            "new Function" to Regex("new\\s+Function\\b"),
            "document.write" to Regex("document\\s*\\.\\s*write"),
            "setTimeout with a string" to Regex("setTimeout\\s*\\(\\s*[\"'`]"),
            "setInterval with a string" to Regex("setInterval\\s*\\(\\s*[\"'`]"),
            "inline handler attribute" to Regex("setAttribute\\(\\s*['\"]on[a-z]+['\"]"),
            "javascript: URL" to Regex("javascript:", RegexOption.IGNORE_CASE),
            "import from a URL" to Regex("import\\s*\\(?\\s*['\"]https?:"),
            "XMLHttpRequest" to Regex("XMLHttpRequest"),
            "WebSocket to another host" to Regex("new\\s+WebSocket\\s*\\(\\s*['\"]wss?:"),
            "document.cookie" to Regex("document\\s*\\.\\s*cookie"),
        )
        val externalUrl = Regex("https?://[^\\s\"'`)<>]+", RegexOption.IGNORE_CASE)
        val allowedUrls = setOf("http://www.w3.org/2000/svg")
        val offences = ArrayList<String>()
        var scanned = 0
        for (root in roots) {
            for (file in root.walkTopDown().filter { it.isFile }) {
                val rel = file.relativeTo(root.parentFile).invariantSeparatorsPath
                if (file.extension !in setOf("js", "mjs", "html", "css", "svg", "json", "txt")) continue
                val text = file.readText()
                scanned++
                for ((label, pattern) in forbidden) {
                    // The words of a comment that explain the rule are not code, but strings in comments are cheap to avoid: the sources do.
                    if (pattern.containsMatchIn(text)) offences += "$rel: $label"
                }
                for (m in externalUrl.findAll(text)) {
                    if (m.value !in allowedUrls) offences += "$rel: external URL ${m.value}"
                }
                if (file.extension == "html") {
                    for (tag in Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(text)) {
                        if (!Regex("src=\"/(admin/ui|enroll-assets)/[^\"]+\"").containsMatchIn(tag.value)) offences += "$rel: script tag ${tag.value}"
                    }
                    for (m in Regex("\\s(on[a-z]+|style)\\s*=", RegexOption.IGNORE_CASE).findAll(text)) offences += "$rel: attribute ${m.value.trim()}"
                }
                if (file.extension == "css") {
                    for (m in Regex("url\\(\\s*['\"]?(?!data:|/admin/ui/|/enroll-assets/|#)([^)'\"]+)").findAll(text)) offences += "$rel: css url ${m.groupValues[1]}"
                    if (Regex("@import").containsMatchIn(text)) offences += "$rel: @import"
                }
            }
        }
        assertTrue(scanned > 40, "the scan really read the sources: $scanned files")
        assertTrue(offences.isEmpty(), "forbidden patterns:\n" + offences.joinToString("\n"))
    }

    @Test
    fun theConsoleIsOnlyTextFilesAndStaysSmall() {
        val root = resourceRoot("admin-ui")
        var total = 0L
        for (file in root.walkTopDown().filter { it.isFile }) {
            assertTrue(file.extension in setOf("js", "css", "html", "svg"), "${file.name} is a type the handler serves")
            assertTrue(file.length() < 400_000, "${file.name} is ${file.length()} bytes")
            total += file.length()
        }
        assertTrue(total < 2_500_000, "the whole console is $total bytes")
    }

    // ------------------------------------------------------------------------------------------------ paths and redirects

    @Test
    fun noCraftedPathReachesAnythingButTheWhitelistedAssets() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val attempts = listOf(
            "/admin/ui/../../../etc/passwd",
            "/admin/ui/..%2f..%2f..%2fetc%2fpasswd",
            "/admin/ui/%2e%2e/%2e%2e/etc/passwd",
            "/admin/ui/%2e%2e%2f%2e%2e%2fetc%2fpasswd",
            "/admin/ui/js/..%2fjs%2fmain.js%00.png",
            "/admin/ui/js%5cmain.js",
            "/admin/ui/js\\main.js",
            "/admin/ui/js/main.js%00",
            "/admin/ui/js/main.js%00.css",
            "/admin/ui/%00",
            "/admin/ui/",
            "/admin/ui",
            "/admin/ui/js",
            "/admin/ui/js/",
            "/admin/ui/css/",
            "/admin/ui/views",
            "/admin/ui/js/views/",
            "/admin/ui/.hidden",
            "/admin/ui/js/.main.js",
            "/admin/ui/js/main.js/",
            "/admin/ui/js/main.js/extra",
            "/admin/ui/index.html.bak",
            "/admin/ui/js/main.jsx",
            "/admin/ui/META-INF/MANIFEST.MF",
            "/admin/ui/com/transfer/flash/server/MainKt.class",
            "/enroll-assets/../application.conf",
            "/enroll-assets/..%2fadmin-ui%2findex.html",
            "/enroll-assets/",
            "/enroll-assets/landing.js%00.css",
            "/admin/ui/a/b/c/d/e/f/g/h/main.js",
        )
        for (path in attempts) {
            val r = client.get(path)
            assertFalse(r.status.value in 200..299, "$path answered ${r.status}")
            assertFalse(r.status.value in 300..399 && r.headers["Location"]?.contains("..") == true, path)
            val body = r.bodyAsText()
            assertFalse(body.contains("root:") || body.contains("export ") || body.contains("<!doctype", ignoreCase = true), "$path leaked content")
        }
        // The same name is fine where it exists, so the failures above are the path's and not a broken handler.
        assertEquals(HttpStatusCode.OK, client.get("/admin/ui/js/main.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/ui/js/no-such-file.js").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/admin/ui/js/views/login.js/..").status, "a dot-dot segment is refused outright (REV-02)")
    }

    @Test
    fun theOldConsoleAddressesRedirectIntoTheNewShell() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val noFollow = createClient { followRedirects = false }
        fun location(r: HttpResponse) = URLDecoder.decode(r.headers["Location"].orEmpty(), Charsets.UTF_8)
        val root = noFollow.get("/admin")
        assertEquals(HttpStatusCode.Found, root.status)
        assertEquals("/admin/", location(root))
        assertConsoleHeaders(root, "redirect")
        val expected = mapOf(
            "/admin/login" to "/admin/",
            "/admin/setup" to "/admin/#/setup",
            "/admin/dashboard" to "/admin/#/overview",
            "/admin/departments" to "/admin/#/organization",
            "/admin/groups" to "/admin/#/organization",
            "/admin/invites" to "/admin/#/invites",
            "/admin/audit" to "/admin/#/audit",
        )
        for ((old, target) in expected) {
            val r = noFollow.get(old)
            assertEquals(HttpStatusCode.Found, r.status, old)
            assertEquals(target, location(r), old)
            assertConsoleHeaders(r, old)
        }
    }

    // ------------------------------------------------------------------------------------------------ errors and policies

    @Test
    fun errorResponsesCarryTheHeadersToo() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val unknownAsset = client.get("/admin/ui/js/nothing.js")
        assertEquals(HttpStatusCode.NotFound, unknownAsset.status)
        assertConsoleHeaders(unknownAsset, "404 asset")
        assertEquals("no-store", unknownAsset.headers["Cache-Control"])

        val unauthenticatedApi = client.get("/admin/api/v1/overview")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticatedApi.status)
        assertConsoleHeaders(unauthenticatedApi, "401 api")
        assertEquals("no-store", unauthenticatedApi.headers["Cache-Control"], "API answers are never cached")

        val unknownAdminPath = client.get("/admin/does-not-exist")
        assertConsoleHeaders(unknownAdminPath, "unknown /admin path")

        val badToken = client.get("/enroll?token=not-a-token")
        assertEquals(HttpStatusCode.BadRequest, badToken.status)
        assertConsoleHeaders(badToken, "landing page, invalid token")
    }

    @Test
    fun aListenerThatMayNotServeTheConsoleAnswers404ForEveryConsoleRoute() = testApplication {
        val hub = build()
        // The console listener is a different port from the public one and this request arrives on neither of them.
        val closed = AdminListenerPolicy(publicPort = 8443, adminPort = 8444, adminOnPublicPort = false)
        install(AdminConsoleHeaders)
        routing { adminConsoleRoutes(AdminWeb(hub.admins, policy = closed)) }
        for (path in listOf("/admin", "/admin/", "/admin/ui/js/main.js", "/admin/login", "/admin/dashboard")) {
            val r = noRedirects().get(path)
            assertEquals(HttpStatusCode.NotFound, r.status, path)
            assertConsoleHeaders(r, path)
            assertFalse(r.bodyAsText().contains("Flash Hub"), "$path reveals nothing about the console")
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.noRedirects(): HttpClient = createClient { followRedirects = false }

    // ------------------------------------------------------------------------------------------------ the landing page

    @Test
    fun theLandingPageIsBuiltWithoutInlineCodeLoadsOnlyItsOwnAssetsAndStillRevealsNothingAboutBadTokens() = testApplication {
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val enrollment = hub.enrollment!!
        val now = System.currentTimeMillis()
        val token = enrollment.createInvite("http://localhost:18444", InviteScope(department = "eng", expiry = now + 60_000)).link.substringAfter("token=").substringBefore("&")

        val ok = client.get("/enroll?token=$token&server=https://evil.example")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertConsoleHeaders(ok, "landing page")
        assertEquals("no-store", ok.headers["Cache-Control"])
        val html = ok.bodyAsText()
        assertNoInlineCode(html, "landing page")
        assertTrue("/enroll-assets/landing.css" in html && "/enroll-assets/landing.js" in html)
        assertFalse(html.contains("evil.example"), "a caller-chosen hub is never echoed")
        assertTrue(html.contains("id=\"copy-link\"") && html.contains("data-link=\""), "the copy button is wired by the script through a data attribute")
        assertTrue(html.contains("<svg"), "the QR is drawn by the server")

        val unknown = client.get("/enroll?token=not-a-token")
        val missing = client.get(HubContract.PATH_ENROLL_LANDING)
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(unknown.bodyAsText(), missing.bodyAsText(), "an unknown and an absent token look identical")
        assertNoInlineCode(unknown.bodyAsText(), "landing page (invalid)")
        assertFalse(unknown.bodyAsText().contains("eng"))
    }

    @Test
    fun theLandingPageEscapesEveryValueItPrints() {
        val html = EnrollLandingPage.render(
            deepLink = "flash://enroll?x=\"><script>alert(1)</script>",
            hubUrl = "http://hub.test/<img src=x onerror=alert(1)>",
            department = "<b onmouseover=alert(1)>dept</b>",
            role = "admin",
            isValid = true,
        )
        assertFalse(html.contains("<script>alert"), html)
        assertFalse(html.contains("<img src=x"), html)
        assertFalse(html.contains("<b onmouseover"), html)
        assertTrue(html.contains("&lt;b onmouseover"))
    }
}
