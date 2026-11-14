package com.transfer.flash.core.common

import com.transfer.flash.core.common.protocol.InviteAppLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InviteAppLinkTest {
    @Test
    fun groupInviteOnlyCarriesItsCredentialInTheFragment() {
        val invite = "flash://g/1/exampleCredential"
        val link = assertNotNull(InviteAppLink.wrap("join.example.org", invite))
        assertEquals("https://join.example.org/invite/group", link.substringBefore('#'))
        assertFalse(link.substringBefore('#').contains("exampleCredential"))
        assertEquals(invite, InviteAppLink.unwrap(link, "join.example.org"))
        assertNull(InviteAppLink.unwrap(link, "another.example.org"))
        assertNull(InviteAppLink.unwrap(link.replace("/group#", "/enroll#")))
        assertNull(InviteAppLink.unwrap(link.replace("#", "?tracking=1#")))
        assertNull(InviteAppLink.unwrap(link.replace("https:", "http:")))
    }

    @Test
    fun hubInviteSupportsConfiguredTlsPortsAndRejectsOtherSchemes() {
        val invite = "flash://enroll?server=https%3A%2F%2Fhub.example.org%3A8443&token=example"
        val link = assertNotNull(InviteAppLink.wrap("hub.example.org:8443", invite))
        assertEquals(invite, InviteAppLink.unwrap(link))
        assertNull(InviteAppLink.wrap("hub.example.org:99999", invite))
        assertNull(InviteAppLink.wrap("hub.example.org", "javascript:alert(1)"))
        assertNull(InviteAppLink.wrap("hub.example.org", invite + "\n"))
    }
}
