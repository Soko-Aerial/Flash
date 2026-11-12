package com.transfer.flash.ptt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards Phase 4/7 Android PTT overlay scrim touch-blocking and Back-handler wiring. */
class PttSessionOverlayWiringTest {

    private fun repoFile(relativePath: String): String {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, relativePath).isFile }
        return File(base, relativePath).readText()
    }

    @Test
    fun `android ptt overlay blocks scrim touches and intercepts back while active`() {
        val src = repoFile("app/src/main/java/com/transfer/flash/ptt/PttSessionOverlay.kt")
        assertTrue("missing BackHandler in PttSessionOverlay.kt", src.contains("BackHandler(enabled ="))
        assertTrue("BackHandler should stop/leave active PTT session", src.contains("engine.stopLocal()"))
        assertTrue("missing scrimModifier on PttSessionOverlayContent", src.contains("scrimModifier ="))
        assertTrue("scrimModifier should consume pointer events via pointerInput", src.contains("pointerInput(Unit)"))
        assertTrue("scrimModifier should consume pointer changes", src.contains("it.consume()"))
        assertFalse("scrimModifier must not use clickable (adds TalkBack node)", src.contains("Modifier.clickable"))
    }
}
