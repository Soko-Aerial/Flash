package com.transfer.flash.ui.radio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RADUI-U04: The no-dead-actions checklist (Plan R UI quality contract, mirroring FlashHubNoDeadActionsTest).
 *
 * Verifies that:
 * 1. Every [FlashRadioAction] has a table entry mapping it to required [RadioGatewayController] methods or pure UI state.
 * 2. Every named controller method exists on [RadioGatewayController].
 * 3. Every method on [RadioGatewayController] is mapped to an action (no dead interface hooks).
 * 4. The radio UI sources actually call every mapped controller action.
 */
class FlashRadioNoDeadActionsTest {

    /** Action -> controller methods it invokes; emptyList() = pure UI state (e.g. dialog presentation). */
    private val table: Map<FlashRadioAction, List<String>> = mapOf(
        FlashRadioAction.TOGGLE_ENABLED to listOf("setEnabled"),
        FlashRadioAction.START_SCAN to listOf("startScan"),
        FlashRadioAction.STOP_SCAN to listOf("stopScan"),
        FlashRadioAction.CONFIRM_CANDIDATE to listOf("confirmCandidate"),
        FlashRadioAction.DISMISS_CANDIDATE to listOf("startScan"),
        FlashRadioAction.PAIR_DEVICE to listOf("pair"),
        FlashRadioAction.RECONNECT to listOf("reconnect"),
        FlashRadioAction.FORGET_RADIO to listOf("forget"),
        FlashRadioAction.SELECT_PROFILE_M to listOf("selectProfile"),
        FlashRadioAction.SELECT_PROFILE_A to listOf("selectProfile"),
        FlashRadioAction.OPEN_AUTHORISATION_DIALOG to emptyList(),
        FlashRadioAction.CONFIRM_AUTHORISATION to listOf("confirmAuthorisation"),
        FlashRadioAction.CANCEL_AUTHORISATION to emptyList(),
        FlashRadioAction.SELECT_MODE to listOf("selectMode"),
        FlashRadioAction.REQUEST_SEND_TEST_MESSAGE to emptyList(),
        FlashRadioAction.CONFIRM_SEND_TEST_MESSAGE to listOf("sendTestMessage"),
        FlashRadioAction.CANCEL_SEND_TEST_MESSAGE to emptyList(),
        FlashRadioAction.OPEN_BT_SETTINGS to listOf("openSystemBluetoothSettings"),
        FlashRadioAction.REQUEST_PERMISSION to listOf("requestNearbyDevicesPermission"),
    )

    private fun controllerMethods(): Set<String> = RadioGatewayController::class.java.methods
        .filter { !it.isSynthetic && it.name != "getState" && it.declaringClass == RadioGatewayController::class.java }
        .map { it.name }
        .toSet()

    private fun radioSources(): String {
        val dir = File("src/commonMain/kotlin/com/transfer/flash/ui/radio")
        if (!dir.isDirectory) {
            // Check fallback path if running from root project
            val fallback = File("ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio")
            if (fallback.isDirectory) {
                return fallback.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
            }
        }
        assertTrue(dir.isDirectory, "Source directory must exist: ${dir.absolutePath}")
        return dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
    }

    @Test
    fun everyActionHasAChecklistRow() {
        assertEquals(FlashRadioAction.entries.toSet(), table.keys, "Every FlashRadioAction must have a checklist entry")
    }

    @Test
    fun everyNamedMethodExistsOnControllerInterface() {
        val methods = controllerMethods()
        table.values.flatten().toSet().forEach { method ->
            assertTrue(method in methods, "RadioGatewayController has no method '$method'")
        }
    }

    @Test
    fun everyControllerMethodIsMappedToAnAction() {
        val methods = controllerMethods()
        val mapped = table.values.flatten().toSet()
        assertEquals(emptySet(), methods - mapped, "Every RadioGatewayController method must be bound to a UI action")
    }

    @Test
    fun screensCallEveryMappedControllerMethod() {
        val src = radioSources()
        table.values.flatten().toSet().forEach { method ->
            assertTrue(
                Regex("""controller\.$method\b""").containsMatchIn(src),
                "No screen code calls controller.$method"
            )
        }
    }
}
