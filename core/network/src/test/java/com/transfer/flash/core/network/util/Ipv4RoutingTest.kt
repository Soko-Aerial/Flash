package com.transfer.flash.core.network.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the routing decision that made a hotspot host unable to dial its own clients (ERROR-035).
 *
 * The cases below are the four real topologies: one LAN, host with two LANs, client with one LAN, and
 * a peer reached through a gateway. Each assertion is about a dial that either lands or burns a 4 s
 * connect timeout.
 */
class Ipv4RoutingTest {

    private fun bits(address: String): Int =
        requireNotNull(Ipv4Routing.parse(address)) { "not parseable: $address" }

    @Test
    fun `parses dotted quads including the boundary values`() {
        assertEquals(0, Ipv4Routing.parse("0.0.0.0"))
        assertEquals(-1, Ipv4Routing.parse("255.255.255.255"))
        // Big-endian bits in a signed Int, so anything with the high bit set is negative. The
        // .toInt() is the point of the assertion: a Long literal here would compare unequal even
        // though the bits match.
        assertEquals(0xC0A80114.toInt(), Ipv4Routing.parse("192.168.1.20"))
        assertEquals(0xC0A82B01.toInt(), Ipv4Routing.parse("192.168.43.1"))
        assertEquals(0x0A000004, Ipv4Routing.parse("10.0.0.4"))
    }

    @Test
    fun `rejects everything that is not a bare IPv4 literal`() {
        // A null here routes the caller to its deterministic fallback. It must never be a *parse*
        // that happens to succeed against the wrong thing, and it must never trigger a DNS lookup —
        // that would be a blocking network call on the dial path.
        assertNull(Ipv4Routing.parse("peer.local"))
        assertNull(Ipv4Routing.parse("fe80::1"))
        assertNull(Ipv4Routing.parse("192.168.1"))
        assertNull(Ipv4Routing.parse("192.168.1.20.5"))
        assertNull(Ipv4Routing.parse("192.168.1.256"))
        assertNull(Ipv4Routing.parse("192.168..20"))
        assertNull(Ipv4Routing.parse("192.168.1.0020"))
        assertNull(Ipv4Routing.parse(""))
        assertNull(Ipv4Routing.parse("192.168.1.20 "))
        assertNull(Ipv4Routing.parse("192.168.1.20:8080"))
    }

    @Test
    fun `a peer on the same LAN is on-link`() {
        // The ordinary case, and the one the old destination-blind code already got right.
        assertTrue(Ipv4Routing.onLink(bits("192.168.1.20"), 24, bits("192.168.1.31")))
    }

    @Test
    fun `a hotspot client is not on-link for the router network`() {
        // THE bug. The host is on the router at 192.168.1.20/24 and its client is at
        // 192.168.43.31 behind ap0. Binding the dial to the router network put the packet on a
        // network where that address has no route, and every attempt spent the full connect timeout.
        assertFalse(Ipv4Routing.onLink(bits("192.168.1.20"), 24, bits("192.168.43.31")))
        // ...and is on-link for the SoftAP interface, which is what makes "bind nothing" correct.
        assertTrue(Ipv4Routing.onLink(bits("192.168.43.1"), 24, bits("192.168.43.31")))
    }

    @Test
    fun `prefix length is honoured rather than assumed to be 24`() {
        // Some APs hand out /16 (192.168.0.0/16 is common on cheap routers) and enterprise LANs use
        // /22 or /23. Assuming /24 would call a reachable peer unreachable.
        assertTrue(Ipv4Routing.onLink(bits("192.168.1.20"), 16, bits("192.168.43.31")))
        assertFalse(Ipv4Routing.onLink(bits("10.0.4.5"), 22, bits("10.0.8.9")))
        assertTrue(Ipv4Routing.onLink(bits("10.0.4.5"), 22, bits("10.0.7.9")))
        assertTrue(Ipv4Routing.onLink(bits("10.1.2.3"), 32, bits("10.1.2.3")))
        assertFalse(Ipv4Routing.onLink(bits("10.1.2.3"), 32, bits("10.1.2.4")))
    }

    @Test
    fun `a default route is never on-link`() {
        // A /0 would make every destination look directly connected to whichever interface reported
        // it, which would bind every dial to the first network in the list — the old bug with extra
        // steps.
        assertFalse(Ipv4Routing.onLink(bits("0.0.0.0"), 0, bits("8.8.8.8")))
        assertFalse(Ipv4Routing.onLink(bits("192.168.1.20"), 0, bits("192.168.1.31")))
        assertFalse(Ipv4Routing.onLink(bits("192.168.1.20"), 33, bits("192.168.1.20")))
        assertFalse(Ipv4Routing.onLink(bits("192.168.1.20"), -1, bits("192.168.1.20")))
    }

    @Test
    fun `loopback and link-local are not usable local identities`() {
        // 127/8 would swallow every 127.x destination; a 169.254 address means DHCP failed, so
        // binding dials to that interface sends them somewhere that cannot carry them.
        assertFalse(Ipv4Routing.isUsableLocalAddress("127.0.0.1"))
        assertFalse(Ipv4Routing.isUsableLocalAddress("169.254.12.7"))
        assertFalse(Ipv4Routing.isUsableLocalAddress(""))
        assertFalse(Ipv4Routing.isUsableLocalAddress("   "))
        assertTrue(Ipv4Routing.isUsableLocalAddress("192.168.43.1"))
        assertTrue(Ipv4Routing.isUsableLocalAddress("10.0.0.4"))
    }

    @Test
    fun `only RFC 1918 space can win the unmanaged-interface branch`() {
        // The branch returns "bind nothing", so a false positive lets a dial leave over whatever the
        // kernel's default route is — mobile data. Carrier space must fail this test.
        assertTrue(Ipv4Routing.isPrivate(bits("10.0.0.4")))
        assertTrue(Ipv4Routing.isPrivate(bits("172.16.0.1")))
        assertTrue(Ipv4Routing.isPrivate(bits("172.31.255.254")))
        assertTrue(Ipv4Routing.isPrivate(bits("192.168.43.1")))
        assertFalse("CGNAT is where carriers put phones", Ipv4Routing.isPrivate(bits("100.64.1.2")))
        assertFalse(Ipv4Routing.isPrivate(bits("172.32.0.1")))
        assertFalse(Ipv4Routing.isPrivate(bits("172.15.255.255")))
        assertFalse(Ipv4Routing.isPrivate(bits("8.8.8.8")))
        assertFalse(Ipv4Routing.isPrivate(bits("192.167.1.1")))
    }

    @Test
    fun `the standard tethering subnets are all private`() {
        // Android has used several over the years and OEMs vary; all of them must pass, because
        // failing one silently disables host-to-client dialling on that OEM.
        listOf("192.168.43.1", "192.168.42.1", "192.168.49.1", "192.168.61.1", "172.20.10.1")
            .forEach { address ->
                assertTrue("$address must count as private", Ipv4Routing.isPrivate(bits(address)))
            }
    }
}
