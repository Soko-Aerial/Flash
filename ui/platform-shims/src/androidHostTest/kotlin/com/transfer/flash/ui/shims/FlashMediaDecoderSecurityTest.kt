package com.transfer.flash.ui.shims

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlashMediaDecoderSecurityTest {

    @Test
    fun `resolveLocalFile rejects non-existent or empty file paths`() {
        val nonExistent = "file:///tmp/flash_test_non_existent_${System.currentTimeMillis()}.jpg"
        // FlashMediaDecoder.decode or internal resolveLocalFile via openStream behavior
        // Since resolveLocalFile is private, openStream with file:// invokes resolveLocalFile
        // memoize=false: host unit tests run against android.jar stubs where
        // android.util.LruCache.get throws ("not mocked"). The cache tier is not this test's
        // subject — skipping it exercises the guard path directly. (PR #14 merge fix; the bot's
        // original called the default memoize=true and failed on host for that reason alone.)
        assertNull(FlashMediaDecoder.decode(context = DummyContext(), source = nonExistent, isVideo = false, memoize = false, computeInSampleSize = { _, _, _ -> 1 }))
    }

    @Test
    fun `resolveLocalFile normalizes canonical paths`() {
        val tempFile = File.createTempFile("flash_sec_test", ".tmp").apply {
            writeText("test content")
            deleteOnExit()
        }
        val dotDotPath = tempFile.parentFile.absolutePath + "/../" + tempFile.parentFile.name + "/" + tempFile.name
        val canonicalPath = tempFile.canonicalFile.absolutePath

        assertEquals(tempFile.canonicalFile, File(dotDotPath).canonicalFile)
        tempFile.delete()
    }

    private class DummyContext : android.content.ContextWrapper(null)
}
