package com.transfer.flash.core.radio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Architectural guard for Plan R §4.4 (R0 acceptance):
 * - :core:radio must NOT depend on :core:network (neither at compile time nor runtime).
 * - :core:network must NOT depend on :core:radio.
 * - :core:network must have no jSerialComm dependency.
 */
class RadioDependencyDirectionContractTest {

    @Test
    fun coreNetworkIsNotOnRadioClasspath() {
        assertFailsWith<ClassNotFoundException>("core:radio must not have :core:network on its classpath") {
            Class.forName("com.transfer.flash.core.network.FlashSession")
        }
    }

    @Test
    fun radioBuildFileDoesNotReferenceCoreNetwork() {
        val rootDir = findProjectRoot()
        val radioGradle = File(rootDir, "core/radio/build.gradle.kts")
        assertTrue(radioGradle.exists(), "core/radio/build.gradle.kts must exist")
        val content = radioGradle.readText()
        assertFalse(content.contains(":core:network"), "core/radio must NOT depend on :core:network")
    }

    @Test
    fun networkBuildFileDoesNotReferenceCoreRadioOrJSerialComm() {
        val rootDir = findProjectRoot()
        val networkGradle = File(rootDir, "core/network/build.gradle.kts")
        assertTrue(networkGradle.exists(), "core/network/build.gradle.kts must exist")
        val content = networkGradle.readText()
        assertFalse(content.contains(":core:radio"), "core/network must NOT depend on :core:radio")
        assertFalse(content.contains("jserialcomm"), "core/network must NOT depend on jserialcomm")
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not find project root (settings.gradle.kts)")
    }
}
