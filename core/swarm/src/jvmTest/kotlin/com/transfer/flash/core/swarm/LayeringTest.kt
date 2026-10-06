package com.transfer.flash.core.swarm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces architectural dependency boundaries for :core:swarm (SW-3 Task 9, Plan §2.5).
 * - No lower-layer module may import com.transfer.flash.core.swarm.
 * - core/swarm/src/commonMain must never import network, messaging, or persistence.
 */
class LayeringTest {

    @Test
    fun `no lower layer module imports com transfer flash core swarm`() {
        val rootDir = findProjectRoot()
        val forbiddenModules = listOf(
            "core/common",
            "core/network",
            "core/transfer",
            "core/messaging",
            "core/persistence",
            "core/discovery",
            "core/security",
        )

        val violations = mutableListOf<String>()
        for (mod in forbiddenModules) {
            val srcDir = File(rootDir, "$mod/src")
            if (!srcDir.exists()) continue
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.forEachLine { line ->
                        if (line.trimStart().startsWith("import") && line.contains("com.transfer.flash.core.swarm")) {
                            violations.add("${file.relativeTo(rootDir)}: $line")
                        }
                    }
                }
        }

        assertTrue(
            "Found forbidden imports of com.transfer.flash.core.swarm in lower layers:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `core swarm commonMain never imports network messaging or persistence`() {
        val rootDir = findProjectRoot()
        val swarmCommonMain = File(rootDir, "core/swarm/src/commonMain")
        assertTrue("core/swarm/src/commonMain does not exist", swarmCommonMain.exists())

        val forbiddenPackages = listOf(
            "com.transfer.flash.core.network",
            "com.transfer.flash.core.messaging",
            "com.transfer.flash.core.persistence",
            "com.transfer.flash.core.engine",
            "com.transfer.flash.core.calling",
            "com.transfer.flash.core.ptt",
        )

        val violations = mutableListOf<String>()
        swarmCommonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.forEachLine { line ->
                    if (line.trimStart().startsWith("import")) {
                        for (pkg in forbiddenPackages) {
                            if (line.contains(pkg)) {
                                violations.add("${file.relativeTo(rootDir)}: $line")
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Found forbidden imports in core/swarm commonMain:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        checkNotNull(dir) { "Could not locate project root containing settings.gradle.kts" }
        return dir
    }
}
