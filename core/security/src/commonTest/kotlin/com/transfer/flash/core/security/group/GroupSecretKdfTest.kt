package com.transfer.flash.core.security.group

import com.transfer.flash.core.security.crypto.toHexLower
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GroupSecretKdfTest {

    private val testSecret = GroupSecret.fromBytes(ByteArray(32) { it.toByte() })
    private val testGroupId = "g2-6f01129f28cff84f8ef67cb9d1970499"

    @Test
    fun derivesExpectedAuthKeyLengthAndDeterministicOutput() {
        val k1 = GroupSecretKdf.authKey(testSecret, testGroupId, 1L)
        val k2 = GroupSecretKdf.authKey(testSecret, testGroupId, 1L)

        assertEquals(32, k1.size)
        assertEquals(k1.toHexLower(), k2.toHexLower())
    }

    @Test
    fun derivesExpectedBeaconKeyLengthAndDistinctFromAuthKey() {
        val auth = GroupSecretKdf.authKey(testSecret, testGroupId, 1L)
        val beacon = GroupSecretKdf.beaconKey(testSecret, testGroupId, 1L)

        assertEquals(32, beacon.size)
        assertFalse(auth.contentEquals(beacon))
    }

    @Test
    fun differentEpochsProduceDifferentKeys() {
        val kEpoch1 = GroupSecretKdf.authKey(testSecret, testGroupId, 1L)
        val kEpoch2 = GroupSecretKdf.authKey(testSecret, testGroupId, 2L)

        assertFalse(kEpoch1.contentEquals(kEpoch2))
    }

    @Test
    fun differentGroupsProduceDifferentKeys() {
        val kGroup1 = GroupSecretKdf.authKey(testSecret, testGroupId, 1L)
        val kGroup2 = GroupSecretKdf.authKey(testSecret, "g2-othergroupid1234567890", 1L)

        assertFalse(kGroup1.contentEquals(kGroup2))
    }

    @Test
    fun validatesInputs() {
        assertFailsWith<IllegalArgumentException> {
            GroupSecretKdf.authKey(testSecret, "", 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecretKdf.authKey(testSecret, testGroupId, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecretKdf.authKey(testSecret, testGroupId, -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecretKdf.authKey(testSecret, testGroupId, 0x1_0000_0000L)
        }
    }
}
