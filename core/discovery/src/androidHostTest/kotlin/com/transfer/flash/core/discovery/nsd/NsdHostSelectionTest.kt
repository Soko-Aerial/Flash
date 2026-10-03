package com.transfer.flash.core.discovery.nsd

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdHostSelectionTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun routableIpv4_beatsALinkLocalIpv6ListedFirst() {
        val chosen = preferredDialableHost(listOf(ip("fe80::1"), ip("192.168.1.20")))
        assertEquals("192.168.1.20", chosen)
    }

    @Test
    fun routableIpv4_beatsLinkLocalIpv4() {
        assertEquals("10.0.0.5", preferredDialableHost(listOf(ip("169.254.3.4"), ip("10.0.0.5"))))
    }

    @Test
    fun linkLocalIpv4_isUsedWhenItIsTheOnlyIpv4() {
        // An isolated hotspot hands out 169.254/16 when DHCP is absent; better than nothing.
        assertEquals("169.254.3.4", preferredDialableHost(listOf(ip("fe80::1"), ip("169.254.3.4"))))
    }

    @Test
    fun globalIpv6_isUsedWhenThereIsNoIpv4() {
        // Java renders IPv6 in its expanded form, so compare against the same rendering.
        assertEquals(ip("2001:db8::7").hostAddress, preferredDialableHost(listOf(ip("fe80::1"), ip("2001:db8::7"))))
    }

    @Test
    fun anEmptyList_yieldsNothing() {
        assertNull(preferredDialableHost(emptyList()))
    }

    @Test
    fun listOrderBreaksTies() {
        assertEquals("192.168.1.2", preferredDialableHost(listOf(ip("192.168.1.2"), ip("192.168.1.3"))))
    }
}
