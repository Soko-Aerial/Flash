package com.transfer.flash.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural caller contracts; real AWT/Compose interactions remain PERF-E device checks. */
class DesktopCompositionOwnershipTest {
    private fun source(name: String): String {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/$name.kt").isFile }
        return File(base, "desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/$name.kt").readText()
    }

    @Test
    fun `window scoped drop target invokes updated whole sender not boot time repository closure`() {
        val shell = source("DesktopShell")
        assertTrue(shell.contains("val currentDropSender by rememberUpdatedState(::sendFileToPeer)"))
        val drop = shell.substringAfter("DisposableEffect(window)").substringBefore("// ──", "")
        assertTrue(drop.contains("currentDropSender("))
        assertFalse(drop.contains("sendFileToPeer("), "a long-lived DropTarget must read the updated sender")
    }

    @Test
    fun `production root owns radio above conditional window and passes it into settings`() {
        val main = source("DesktopMain")
        assertTrue(main.indexOf("val radioGatewayController = remember(engine)") < main.indexOf("if (isWindowVisible) {"))
        assertTrue(main.contains("scope = engine.scope"))
        assertTrue(main.contains("radioGatewayController = radioGatewayController"))
        val settings = source("DesktopShell").substringAfter("private fun DesktopSettingsDestination(")
        assertFalse(settings.contains("DesktopRadioGatewayController("), "Settings must not recreate a stored-ON manager")
        assertTrue(settings.contains("radioGatewayController.state.collectAsState()"))
    }

    @Test
    fun `composition reads only the non initializing ready pairing view`() {
        val shell = source("DesktopShell")
        assertTrue(shell.contains("remember(engine, ready) { engine.readyPairing }"))
        assertFalse(shell.contains("engine.pairing.pairing.collect"))
        assertFalse(shell.contains("engine.pairing.trustedPeers.collectAsState"))
        assertFalse(shell.contains("localFingerprint = engine.pairing"))
    }

    @Test
    fun `application observes OS motion preference for window and toast without synchronous composition queries`() {
        val main = source("DesktopMain")
        assertTrue(main.contains("DesktopOsReduceMotion.preferences.collectAsState(initial = false)"))
        assertTrue(main.contains("effectivePerformanceMode.reduceMotion || osReduceMotion"))
        assertTrue(main.contains("toastPerformance.reduceMotion || osReduceMotion"))
        assertFalse(main.contains("DesktopOsReduceMotion.isReduceMotion()"))
    }
}
