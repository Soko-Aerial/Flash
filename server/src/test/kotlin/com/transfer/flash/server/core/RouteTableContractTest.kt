package com.transfer.flash.server.core

import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.e2e.E2eHub
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps `docs/protocol-routes.txt` (the checked-in REST surface of the hub) equal to the routes the running server really answers, so a
 * route added in code cannot go undocumented (REV docs gap: `POST /api/v1/departments`, `/groups`, `/admin/api/v1/delegation`, ... were
 * missing from `docs/protocol.md` for a whole release). A failure prints the new table; copy it to the file and describe the route in
 * `docs/protocol.md`.
 */
class RouteTableContractTest {

    private val hubs = ArrayList<E2eHub>()

    @AfterTest
    fun stop() = hubs.forEach { it.close() }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    @Test
    fun theCheckedInRouteListEqualsTheRoutesTheServerAnswers() {
        val hub = E2eHub(limits = { HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val actual = hub.services.engine.routeTable()
        assertTrue(actual.isNotEmpty(), "the routing tree could not be read")
        val actualText = actual.joinToString("\n", postfix = "\n")
        val file = File(repoRoot(), "docs/protocol-routes.txt")
        File(System.getProperty("user.dir"), "build/route-table.txt").also { it.parentFile.mkdirs() }.writeText(actualText)
        val expected = if (file.exists()) file.readText().replace("\r\n", "\n") else ""
        assertEquals(
            expected.lines().filter { it.isNotBlank() && !it.startsWith("#") },
            actual,
            "docs/protocol-routes.txt is out of date. Copy server/build/route-table.txt over it (keep the header comment) and document " +
                "the route in docs/protocol.md.\nCurrent table:\n$actualText",
        )
    }
}
