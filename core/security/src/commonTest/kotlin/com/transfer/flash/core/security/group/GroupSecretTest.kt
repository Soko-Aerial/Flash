package com.transfer.flash.core.security.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupSecretTest {

    @Test
    fun generatesExact32Bytes() {
        val secret = GroupSecret.generate()
        assertEquals(32, secret.toByteArray().size)
    }

    @Test
    fun rejectsNon32ByteSecret() {
        assertFailsWith<IllegalArgumentException> {
            GroupSecret.fromBytes(ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecret.fromBytes(ByteArray(33))
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecret.fromBytes(ByteArray(0))
        }
    }

    @Test
    fun toStringNeverExposesSecretBytes() {
        val bytes = ByteArray(32) { (it + 1).toByte() }
        val secret = GroupSecret.fromBytes(bytes)
        val repr = secret.toString()
        assertEquals("GroupSecret(redacted)", repr)
        assertFalse(repr.contains("01"))
        assertFalse(repr.contains("02"))
        assertFalse(repr.contains("1f"))
    }

    @Test
    fun constantTimeEqualsAndOperatorEquals() {
        val b1 = ByteArray(32) { it.toByte() }
        val b2 = ByteArray(32) { it.toByte() }
        val b3 = ByteArray(32) { (it + 1).toByte() }

        val s1 = GroupSecret.fromBytes(b1)
        val s2 = GroupSecret.fromBytes(b2)
        val s3 = GroupSecret.fromBytes(b3)

        assertTrue(s1.constantTimeEquals(s2))
        assertTrue(s1 == s2)
        assertFalse(s1.constantTimeEquals(s3))
        assertFalse(s1 == s3)
        assertFalse(s1.constantTimeEquals(null))
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val bytes = ByteArray(32) { 0x42.toByte() }
        val secret = GroupSecret.fromBytes(bytes)
        val exported = secret.toByteArray()
        exported[0] = 0x00

        assertEquals(0x42.toByte(), secret.toByteArray()[0])
    }
}
