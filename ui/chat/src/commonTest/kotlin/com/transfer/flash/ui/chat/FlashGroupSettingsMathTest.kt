package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.protocol.GroupSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashGroupSettingsMathTest {

    @Test
    fun testCanEditGroupRules() {
        assertTrue(FlashGroupSettingsMath.canEditGroupRules(isOwner = true, isAdmin = false))
        assertTrue(FlashGroupSettingsMath.canEditGroupRules(isOwner = false, isAdmin = true))
        assertTrue(FlashGroupSettingsMath.canEditGroupRules(isOwner = true, isAdmin = true))
        assertFalse(FlashGroupSettingsMath.canEditGroupRules(isOwner = false, isAdmin = false))
    }

    @Test
    fun testClampMaxMembers() {
        assertEquals(10, FlashGroupSettingsMath.clampMaxMembers(10, 0))
        assertEquals(15, FlashGroupSettingsMath.clampMaxMembers(10, 5))
        assertEquals(5, FlashGroupSettingsMath.clampMaxMembers(10, -5))
        assertEquals(2, FlashGroupSettingsMath.clampMaxMembers(5, -10))
        assertEquals(20, FlashGroupSettingsMath.clampMaxMembers(18, 5))
    }

    @Test
    fun testJoinPolicyLabels() {
        assertEquals("Approval required", FlashGroupSettingsMath.joinPolicyLabel(GroupSettings.POLICY_APPROVE))
        assertEquals("Open to anyone with link", FlashGroupSettingsMath.joinPolicyLabel(GroupSettings.POLICY_OPEN))
        assertTrue(FlashGroupSettingsMath.joinPolicyDescription(GroupSettings.POLICY_APPROVE).contains("approval"))
        assertTrue(FlashGroupSettingsMath.joinPolicyDescription(GroupSettings.POLICY_OPEN).contains("immediately"))
    }

    @Test
    fun testInviteSharersLabels() {
        assertEquals("Admins only", FlashGroupSettingsMath.inviteSharersLabel(GroupSettings.SHARERS_ADMINS))
        assertEquals("All members", FlashGroupSettingsMath.inviteSharersLabel(GroupSettings.SHARERS_ALL))
        assertTrue(FlashGroupSettingsMath.inviteSharersDescription(GroupSettings.SHARERS_ADMINS).contains("Only group admins"))
        assertTrue(FlashGroupSettingsMath.inviteSharersDescription(GroupSettings.SHARERS_ALL).contains("Any active member"))
    }

    @Test
    fun testPreferenceDescriptions() {
        assertTrue(FlashGroupSettingsMath.serveToGroupDescription(true).contains("Help share"))
        assertTrue(FlashGroupSettingsMath.serveToGroupDescription(false).contains("will not seed"))
        assertTrue(FlashGroupSettingsMath.serveWifiOnlyDescription(true).contains("Wi-Fi"))
        assertEquals("Pause file sharing when battery drops below 20%.", FlashGroupSettingsMath.batteryThresholdDescription(20))
        assertEquals("Keep completed files available for 1 day.", FlashGroupSettingsMath.keepAvailableDaysDescription(1))
        assertEquals("Keep completed files available for 7 days.", FlashGroupSettingsMath.keepAvailableDaysDescription(7))
        assertEquals("30 members", FlashGroupSettingsMath.maxMembersLabel(30))
    }
}
