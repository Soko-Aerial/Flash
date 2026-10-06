package com.transfer.flash.core.security.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupSecretCommitTest {

    private val testSecret = GroupSecret.fromBytes(ByteArray(32) { it.toByte() })
    private val testGroupId = "g2-6f01129f28cff84f8ef67cb9d1970499"

    @Test
    fun derivesDeterministic32ByteCommitment() {
        val c1 = GroupSecretCommit.of(testGroupId, 1L, testSecret)
        val c2 = GroupSecretCommit.of(testGroupId, 1L, testSecret)

        assertEquals(32, c1.size)
        assertTrue(c1.contentEquals(c2))
    }

    @Test
    fun matchesAndMatchesHex() {
        val commit = GroupSecretCommit.of(testGroupId, 1L, testSecret)
        val commitHex = GroupSecretCommit.ofHex(testGroupId, 1L, testSecret)

        assertTrue(GroupSecretCommit.matches(commit, testGroupId, 1L, testSecret))
        assertTrue(GroupSecretCommit.matchesHex(commitHex, testGroupId, 1L, testSecret))
        assertTrue(GroupSecretCommit.matchesHex(commitHex.uppercase(), testGroupId, 1L, testSecret))

        // Wrong secret
        val wrongSecret = GroupSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })
        assertFalse(GroupSecretCommit.matches(commit, testGroupId, 1L, wrongSecret))
        assertFalse(GroupSecretCommit.matchesHex(commitHex, testGroupId, 1L, wrongSecret))

        // Wrong epoch
        assertFalse(GroupSecretCommit.matches(commit, testGroupId, 2L, testSecret))
        assertFalse(GroupSecretCommit.matchesHex(commitHex, testGroupId, 2L, testSecret))

        // Wrong groupId
        assertFalse(GroupSecretCommit.matches(commit, "g2-other", 1L, testSecret))
        assertFalse(GroupSecretCommit.matchesHex(commitHex, "g2-other", 1L, testSecret))

        // Invalid commit length
        assertFalse(GroupSecretCommit.matches(ByteArray(31), testGroupId, 1L, testSecret))
    }

    @Test
    fun validatesInputs() {
        assertFailsWith<IllegalArgumentException> {
            GroupSecretCommit.of("", 1L, testSecret)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecretCommit.of(testGroupId, 0L, testSecret)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupSecretCommit.of(testGroupId, -1L, testSecret)
        }
    }
}
