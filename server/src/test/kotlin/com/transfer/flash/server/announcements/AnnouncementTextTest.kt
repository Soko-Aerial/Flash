package com.transfer.flash.server.announcements

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnouncementTextTest {

    @Test
    fun validNormalAnnouncementCleansSuccessfully() {
        val cleaned = AnnouncementText.clean(
            rawTitle = "Quarterly All-Hands Meeting",
            rawBody = "Join us this Friday at 10:00 AM UTC for our quarterly updates.\n\nLink: https://flash.org/meeting",
        )
        assertEquals("Quarterly All-Hands Meeting", cleaned.title)
        assertEquals(27, cleaned.titleCodePoints)
        assertTrue(cleaned.body.contains("Join us this Friday"))
        val links = AnnouncementText.extractLinks(cleaned.body)
        assertEquals(listOf("https://flash.org/meeting"), links)
    }

    @Test
    fun emptyOrWhitespaceOnlyThrows() {
        assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean("", "Some body")
        }
        assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean("   \t\n  ", "Some body")
        }
        assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean("Valid Title", "")
        }
        assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean("Valid Title", "   \t\n  ")
        }
    }

    @Test
    fun stripsAsciiControlCharactersAndNul() {
        val titleWithControls = "Title\u0000With\u0007Bell\u0008AndNul"
        val bodyWithControls = "Body\u0000With\u001FNul\tAnd\nPreservedTabsAndNewlines"

        val cleaned = AnnouncementText.clean(titleWithControls, bodyWithControls)
        assertEquals("TitleWithBellAndNul", cleaned.title)
        assertEquals("BodyWithNul\tAnd\nPreservedTabsAndNewlines", cleaned.body)
    }

    @Test
    fun stripsBidiOverrideAndIsolateControls() {
        // U+202E is RIGHT-TO-LEFT OVERRIDE (used in filename/text spoofing)
        // U+2066 is LEFT-TO-RIGHT ISOLATE
        val spoofedTitle = "Notice\u202E\u2066from HR"
        val spoofedBody = "Important message:\u202E\u2069 Click here"

        val cleaned = AnnouncementText.clean(spoofedTitle, spoofedBody)
        assertEquals("Noticefrom HR", cleaned.title)
        assertEquals("Important message: Click here", cleaned.body)
        assertFalse(cleaned.title.contains("\u202E"))
        assertFalse(cleaned.body.contains("\u202E"))
    }

    @Test
    fun zeroWidthCharactersRemovedFromTitleAndStrippedFromBody() {
        // U+200B (ZWSP), U+200C (ZWNJ), U+200D (ZWJ), U+FEFF (BOM)
        val titleWithZw = "Sec\u200Bure\uFEFFTitle\u200D"
        // ZWJ (U+200D) in body inside emoji sequence should be preserved
        val emojiWithZwj = "Team: \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\uFEFF"
        val cleaned = AnnouncementText.clean(titleWithZw, emojiWithZwj)

        assertEquals("SecureTitle", cleaned.title)
        assertEquals("Team: \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", cleaned.body)
        assertFalse(cleaned.body.contains("\uFEFF"))
        assertTrue(cleaned.body.contains("\u200D")) // ZWJ preserved in emoji sequence
    }

    @Test
    fun collapsesExcessiveNewlinesInBody() {
        val bodyWithManyNewlines = "Paragraph 1\n\n\n\n\n\nParagraph 2\n\n\nParagraph 3"
        val cleaned = AnnouncementText.clean("Title", bodyWithManyNewlines)
        assertEquals("Paragraph 1\n\n\nParagraph 2\n\n\nParagraph 3", cleaned.body)
    }

    @Test
    fun codePointBoundaryChecksForTitle() {
        // Construct string with exactly 120 code points
        // Use non-BMP emoji \uD83D\uDE80 (Rocket) which is 2 Java chars but 1 code point
        val title120 = "\uD83D\uDE80".repeat(120)
        assertEquals(240, title120.length) // 240 chars in UTF-16
        assertEquals(120, title120.codePointCount(0, title120.length))

        val cleaned120 = AnnouncementText.clean(title120, "Valid body")
        assertEquals(120, cleaned120.titleCodePoints)

        // 121 code points must fail
        val title121 = "\uD83D\uDE80".repeat(121)
        val ex = assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean(title121, "Valid body")
        }
        assertEquals("title", ex.field)
        assertEquals(120, ex.limit)
    }

    @Test
    fun codePointBoundaryChecksForBody() {
        // Construct body with exactly 4000 code points
        val body4000 = "\uD83D\uDE00".repeat(4000)
        assertEquals(4000, body4000.codePointCount(0, body4000.length))

        val cleaned4000 = AnnouncementText.clean("Valid title", body4000)
        assertEquals(4000, cleaned4000.bodyCodePoints)

        // 4001 code points must fail
        val body4001 = "\uD83D\uDE00".repeat(4001)
        val ex = assertFailsWith<AnnouncementText.ValidationException> {
            AnnouncementText.clean("Valid title", body4001)
        }
        assertEquals("body", ex.field)
        assertEquals(4000, ex.limit)
    }

    @Test
    fun scriptTagsTreatedAsPlainText() {
        val scriptPayload = "<script>alert('XSS')</script>"
        val cleaned = AnnouncementText.clean("XSS Attempt", scriptPayload)
        // Cleaned body retains the literal text because it is plain text, not parsed as HTML
        assertEquals("<script>alert('XSS')</script>", cleaned.body)
    }

    @Test
    fun extractLinksHandlesMultipleHttpAndHttps() {
        val text = "Visit http://insecure.org and https://secure.org/path?query=1#hash for details."
        val links = AnnouncementText.extractLinks(text)
        assertEquals(listOf("http://insecure.org", "https://secure.org/path?query=1#hash"), links)
    }
}
