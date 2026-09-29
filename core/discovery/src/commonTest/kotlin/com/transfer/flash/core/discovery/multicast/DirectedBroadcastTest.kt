package com.transfer.flash.core.discovery.multicast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The address arithmetic behind the DR2 beacon; no sockets, no network. */
class DirectedBroadcastTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun broadcast(a: Int, b: Int, c: Int, d: Int, prefix: Int) =
        DirectedBroadcast.forIpv4(ip(a, b, c, d), prefix)

    @Test
    fun aHomeNetwork_slash24() {
        assertEquals("192.168.1.255", broadcast(192, 168, 1, 42, 24))
    }

    @Test
    fun anAndroidHotspot_slash24() {
        assertEquals("192.168.43.255", broadcast(192, 168, 43, 1, 24))
    }

    @Test
    fun aWiderCorporateNetwork_slash8_doesNotOverflowTheHostMask() {
        assertEquals("10.255.255.255", broadcast(10, 1, 2, 3, 8))
    }

    @Test
    fun aSubnetThatIsNotOnAnOctetBoundary() {
        assertEquals("172.16.15.255", broadcast(172, 16, 5, 9, 20))
        assertEquals("192.168.1.3", broadcast(192, 168, 1, 1, 30))
        assertEquals("192.168.1.127", broadcast(192, 168, 1, 100, 25))
    }

    @Test
    fun bytesAboveOneHundredTwentySeven_areUnsignedInJava() {
        // Java's bytes are signed: 200, 250 and 255 arrive as negative numbers.
        assertEquals("200.250.255.255", broadcast(200, 250, 255, 1, 24))
    }

    @Test
    fun aHostRouteOrPointToPointLink_hasNoBroadcastAddress() {
        assertNull(broadcast(192, 168, 1, 1, 31))
        assertNull(broadcast(192, 168, 1, 1, 32))
    }

    @Test
    fun aPrefixWiderThanALan_orNonsense_isRefused() {
        assertNull(broadcast(10, 0, 0, 1, 7))
        assertNull(broadcast(10, 0, 0, 1, 0))
        assertNull(broadcast(10, 0, 0, 1, -1))
        assertNull(broadcast(10, 0, 0, 1, 33))
        assertNull(broadcast(10, 0, 0, 1, 128), "the value some Android releases return for an IPv4 address")
    }

    @Test
    fun addressesThatAreNotOnARealSubnet_areRefused() {
        assertNull(broadcast(0, 0, 0, 0, 24), "unset")
        assertNull(broadcast(127, 0, 0, 1, 8), "loopback")
        assertNull(broadcast(224, 0, 0, 168, 24), "multicast")
        assertNull(broadcast(240, 0, 0, 1, 24), "class E")
    }

    @Test
    fun anAddressEqualToItsOwnBroadcast_isAMisconfiguration() {
        assertNull(broadcast(192, 168, 1, 255, 24))
    }

    @Test
    fun aWrongLengthAddress_isRefused() {
        assertNull(DirectedBroadcast.forIpv4(byteArrayOf(10, 0, 0), 24))
        assertNull(DirectedBroadcast.forIpv4(ByteArray(16), 24), "an IPv6 address has no directed broadcast")
    }
}
