package com.transfer.flash.server.core

import com.transfer.flash.server.auth.RateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** REV-02 / REV-10: the one canonical path every security decision reads, and the limiter key for IPv6 clients. */
class CanonicalPathTest {

    @Test
    fun encodedLettersAndDuplicateSlashesFoldToTheSamePathTheRouterMatches() {
        assertEquals("/api/v1/directory/members", CanonicalPath.of("/%61pi/v1/directory/members"))
        assertEquals("/api/v1/directory/members", CanonicalPath.of("//api/v1/directory/members"))
        assertEquals("/api/v1/directory/members", CanonicalPath.of("/api//v1///directory/members/"))
        assertEquals("/api/v1/directory/members", CanonicalPath.of("/api/./v1/directory/members?x=1"))
        assertEquals("/admin", CanonicalPath.of("/%41dmin".lowercase().replace("%41", "%61")))
        assertEquals("/", CanonicalPath.of("/"))
    }

    @Test
    fun aPathWithNoHonestReadingIsRefused() {
        val bad = listOf(
            "api/v1", "/api/../admin", "/api/%2e%2e/admin", "/api/%2E%2e/admin", "/a%2fb", "/a%2Fb", "/a%5cb", "/a\b", "/a%00b", "/a\u0000b",
            "/%2561pi", "/api/%zz", "/api/%4", "/api/%c0%af", "/a\nb",
        )
        for (path in bad) assertNull(CanonicalPath.of(path), "must be refused: $path")
    }

    @Test
    fun anIpv6ClientIsCountedByItsSlash64AndAMappedIpv4ByItsIpv4() {
        val a = RateKeys.normalise("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        val b = RateKeys.normalise("2001:db8:1:2:1:2:3:4")
        assertEquals(a, b)
        assertEquals("2001:0db8:0001:0002::/64", a)
        assertEquals(false, a == RateKeys.normalise("2001:db8:1:3::1"))
        assertEquals("203.0.113.7", RateKeys.normalise("203.0.113.7"))
        assertEquals("203.0.113.7", RateKeys.normalise("::ffff:203.0.113.7"))
        assertEquals("2001:0db8:0000:0000::/64", RateKeys.normalise("[2001:db8::1]"))
    }
}
