package com.transfer.flash.server.deploy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-128: the hub client lives in `:core:enterprise`. The SERVER must never depend on it in main code (it would couple the
 * hub to its own client); only its tests may, because they drive a real client against a real hub.
 */
class ModuleBoundaryTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private fun lines(path: String) = File(repoRoot(), path).readLines().map { it.trim() }.filter { !it.startsWith("//") }

    @Test
    fun theServerMainCodeDoesNotDependOnTheHubClient() {
        val deps = lines("server/build.gradle.kts").filter { it.contains("project(\":core:enterprise\")") }
        assertTrue(deps.isNotEmpty(), "the server tests need :core:enterprise")
        assertTrue(deps.all { it.startsWith("testImplementation") }, "only testImplementation may name :core:enterprise, found: $deps")
    }

    @Test
    fun theLanTransportDoesNotDependOnTheHubClientOrTheHubProtocol() {
        val deps = lines("core/network/build.gradle.kts")
        assertFalse(deps.any { it.contains("project(\":core:enterprise\")") }, ":core:network must not depend on :core:enterprise")
        assertFalse(deps.any { it.contains("project(\":core:hub-protocol\")") }, ":core:network must not depend on :core:hub-protocol")
    }

    @Test
    fun theHubClientDoesNotDependOnTheLanTransport() {
        val deps = lines("core/enterprise/build.gradle.kts")
        assertFalse(deps.any { it.contains("project(\":core:network\")") }, ":core:enterprise must not depend on :core:network")
    }
}
