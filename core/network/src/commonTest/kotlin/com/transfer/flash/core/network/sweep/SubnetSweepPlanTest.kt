package com.transfer.flash.core.network.sweep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DR3 limits: which addresses a sweep may touch. */
class SubnetSweepPlanTest {

    private fun subnet(address: String, prefix: Int) = LocalSubnet("wlan0", address, prefix)

    private fun targets(address: String, prefix: Int, manual: Boolean = false) =
        assertIs<SubnetSweepPlan.Outcome.Targets>(SubnetSweepPlan.plan(subnet(address, prefix), manual))

    private fun refusal(address: String, prefix: Int, manual: Boolean = false) =
        assertIs<SubnetSweepPlan.Outcome.Refused>(SubnetSweepPlan.plan(subnet(address, prefix), manual)).refusal

    @Test
    fun `a slash 24 yields 253 hosts without self, network or broadcast address`() {
        val plan = targets("192.168.1.20", 24)
        assertEquals(253, plan.hosts.size)
        assertFalse(plan.narrowed)
        assertFalse("192.168.1.20" in plan.hosts)
        assertFalse("192.168.1.0" in plan.hosts)
        assertFalse("192.168.1.255" in plan.hosts)
        assertTrue("192.168.1.1" in plan.hosts && "192.168.1.254" in plan.hosts)
        assertEquals(plan.hosts.size, plan.hosts.toSet().size)
    }

    @Test
    fun `hosts are ordered nearest to this device first`() {
        val plan = targets("192.168.1.100", 24)
        // Ties go to the lower address.
        assertEquals(listOf("192.168.1.99", "192.168.1.101", "192.168.1.98", "192.168.1.102"), plan.hosts.take(4))
    }

    @Test
    fun `a smaller subnet is swept whole using its real bounds`() {
        val plan = targets("192.168.43.130", 26) // 192.168.43.128 - .191: 62 usable, minus this device
        assertEquals(61, plan.hosts.size)
        assertTrue(plan.hosts.all { host -> host.substringAfterLast('.').toInt() in 129..190 })
        assertFalse("192.168.43.130" in plan.hosts)
    }

    @Test
    fun `a slash 30 has one other host`() {
        assertEquals(listOf("10.1.2.6"), targets("10.1.2.5", 30).hosts)
    }

    @Test
    fun `slash 31 and 32 are point-to-point links and refused`() {
        assertEquals(SweepRefusal.TOO_SMALL, refusal("192.168.1.2", 31))
        assertEquals(SweepRefusal.TOO_SMALL, refusal("192.168.1.2", 32))
    }

    @Test
    fun `a subnet wider than 24 is refused when automatic`() {
        assertEquals(SweepRefusal.TOO_LARGE, refusal("10.20.30.40", 16))
        assertEquals(SweepRefusal.TOO_LARGE, refusal("192.168.0.77", 23))
    }

    @Test
    fun `a subnet wider than 24 is narrowed to this devices own 24 when manual`() {
        val plan = targets("10.20.30.40", 16, manual = true)
        assertTrue(plan.narrowed)
        // .0 and .255 of the block are ordinary hosts of a /16, so all 256 addresses minus this device.
        assertEquals(255, plan.hosts.size)
        assertTrue(plan.hosts.all { it.startsWith("10.20.30.") })
        assertFalse("10.20.30.40" in plan.hosts)
    }

    @Test
    fun `a narrowed sweep keeps a block edge that is a real host of the wider subnet`() {
        // 192.168.1.0/23: 192.168.1.255 is the broadcast address, but 192.168.1.0 is an ordinary host.
        val plan = targets("192.168.1.9", 23, manual = true)
        assertTrue("192.168.1.0" in plan.hosts)
        assertFalse("192.168.1.255" in plan.hosts)
        assertEquals(254, plan.hosts.size)
    }

    @Test
    fun `only RFC 1918 space is swept`() {
        assertEquals(SweepRefusal.NOT_PRIVATE, refusal("100.72.5.9", 24)) // carrier CGNAT
        assertEquals(SweepRefusal.NOT_PRIVATE, refusal("8.8.8.100", 24))
        assertEquals(SweepRefusal.NOT_PRIVATE, refusal("172.32.0.5", 24)) // just outside 172.16/12
        assertEquals(SweepRefusal.NOT_PRIVATE, refusal("100.72.5.9", 16, manual = true)) // manual does not lift it
        assertIs<SubnetSweepPlan.Outcome.Targets>(SubnetSweepPlan.plan(subnet("172.31.255.7", 24), false))
        assertIs<SubnetSweepPlan.Outcome.Targets>(SubnetSweepPlan.plan(subnet("172.20.10.2", 28), false))
    }

    @Test
    fun `unusable local addresses and malformed input are refused`() {
        assertEquals(SweepRefusal.NO_LAN, refusal("169.254.10.10", 16))
        assertEquals(SweepRefusal.NO_LAN, refusal("127.0.0.1", 8))
        assertEquals(SweepRefusal.NO_LAN, refusal("not-an-ip", 24))
        assertEquals(SweepRefusal.NO_LAN, refusal("192.168.1.5", 0))
        assertEquals(SweepRefusal.NO_LAN, refusal("192.168.1.5", 33))
    }

    @Test
    fun `network key names the subnet, not the address`() {
        assertEquals("192.168.1.0/24", SubnetSweepPlan.networkKey(subnet("192.168.1.77", 24)))
        assertEquals("10.20.0.0/16", SubnetSweepPlan.networkKey(subnet("10.20.30.40", 16)))
        assertNull(SubnetSweepPlan.networkKey(subnet("garbage", 24)))
        assertNull(SubnetSweepPlan.networkKey(subnet("192.168.1.1", 0)))
    }
}
