package com.transfer.flash.core.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceIdentityTest {

    @Test
    fun `a prefix mismatch is a change even when size and mtime match`() {
        val stored = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000, prefixSha256Hex = "aa")
        val current = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000, prefixSha256Hex = "bb")
        assertEquals(SourceIdentityCheck.Changed, checkSourceIdentity(stored, current))
    }

    @Test
    fun `a touch with the same prefix is conservatively refused`() {
        val stored = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000, prefixSha256Hex = "aa")
        val current = SourceIdentity(sizeBytes = 10, lastModifiedMs = 9_000, prefixSha256Hex = "aa")
        assertEquals(SourceIdentityCheck.Changed, checkSourceIdentity(stored, current))
    }

    @Test
    fun `matching size, prefix and mtime is a match`() {
        val stored = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000, prefixSha256Hex = "aa")
        val current = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000, prefixSha256Hex = "aa")
        assertEquals(SourceIdentityCheck.Match, checkSourceIdentity(stored, current))
    }

    @Test
    fun `a size change is always a change`() {
        val stored = SourceIdentity(sizeBytes = 10, prefixSha256Hex = "aa")
        val current = SourceIdentity(sizeBytes = 11, prefixSha256Hex = "aa")
        assertEquals(SourceIdentityCheck.Changed, checkSourceIdentity(stored, current))
    }

    @Test
    fun `without a prefix the modification time decides`() {
        val stored = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000)
        val same = SourceIdentity(sizeBytes = 10, lastModifiedMs = 1_000)
        val touched = SourceIdentity(sizeBytes = 10, lastModifiedMs = 2_000)
        assertEquals(SourceIdentityCheck.Match, checkSourceIdentity(stored, same))
        assertEquals(SourceIdentityCheck.Changed, checkSourceIdentity(stored, touched))
    }

    @Test
    fun `a size-only row cannot be verified`() {
        val stored = SourceIdentity(sizeBytes = 10)
        val current = SourceIdentity(sizeBytes = 10)
        assertEquals(SourceIdentityCheck.Unverifiable, checkSourceIdentity(stored, current))
    }

    @Test
    fun `no stored identity is unverifiable`() {
        assertEquals(
            SourceIdentityCheck.Unverifiable,
            checkSourceIdentity(null, SourceIdentity(sizeBytes = 10, prefixSha256Hex = "aa")),
        )
    }
}
