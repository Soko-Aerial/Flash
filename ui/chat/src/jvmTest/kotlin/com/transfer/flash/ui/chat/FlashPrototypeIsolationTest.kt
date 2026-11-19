package com.transfer.flash.ui.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ensures prototype/competition/fake UI code inside `:ui:chat` stays isolated from shipped
 * production screens while preserving `:ui:hub-competition-claude` and standalone demo harnesses.
 */
class FlashPrototypeIsolationTest {

    @Test
    fun `production ui-chat sources never reference competition prototypes, placeholder hub, or fake radio`() {
        val root = listOf(File("src/commonMain/kotlin"), File("ui/chat/src/commonMain/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate ui/chat/src/commonMain/kotlin from ${File(".").absolutePath}")

        val forbiddenImportsOrSymbols = listOf(
            "com.transfer.flash.ui.hub.competition",
            "FlashHubPlaceholderScreen",
            "FakeRadioGatewayController",
        )

        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val normalized = file.path.replace('\\', '/')
                if (normalized.contains("/ui/hub/competition/") ||
                    normalized.endsWith("/FlashHubPlaceholderScreen.kt") ||
                    normalized.endsWith("/FakeRadioGatewayController.kt") ||
                    normalized.endsWith("/RadioGatewayController.kt")
                ) {
                    return@forEach
                }
                val content = file.readText()
                forbiddenImportsOrSymbols.forEach { symbol ->
                    if (content.contains(symbol)) {
                        violations += "$normalized references $symbol"
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Prototype/fake isolation violated in production :ui:chat sources:\n${violations.joinToString("\n")}",
        )
    }
}
