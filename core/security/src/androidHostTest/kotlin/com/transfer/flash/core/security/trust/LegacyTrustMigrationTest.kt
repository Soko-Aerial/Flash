package com.transfer.flash.core.security.trust

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.security.trust.pinned.FlashTrustedPeer
import com.transfer.flash.core.security.trust.pinned.LegacyTrustMigration
import com.transfer.flash.core.security.trust.pinned.TofuPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure merge-logic coverage for the SharedPreferences → Room migration (C2.4).
 * DAO/Room behavior itself is covered by the :core:persistence invariant suite;
 * RoomTrustedStore.importFrom is a thin composition of [LegacyTrustMigration] and
 * `TrustedPeerDao.insert`, so no Android/Room instrumentation is needed here.
 */
class LegacyTrustMigrationTest {

    @Test
    fun `legacy peers become unbound rows`() {
        val rows = LegacyTrustMigration.computeMigrations(
            existingRoomDeviceIds = emptySet(),
            legacyPeers = mapOf(
                FlashDeviceId("device-a") to "Galaxy S23",
                FlashDeviceId("device-b") to "Pixel 9",
            ),
            nowMs = 1_234_567L,
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.fingerprintHex == TofuPolicy.LEGACY_UNBOUND_FINGERPRINT })
        assertTrue(rows.all { it.trustedAt == 1_234_567L })
        assertEquals(
            setOf("Galaxy S23", "Pixel 9"),
            rows.map { it.name }.toSet(),
        )
    }

    @Test
    fun `already-migrated devices are skipped - idempotent second pass`() {
        val legacy = mapOf(FlashDeviceId("device-a") to "Galaxy S23")
        val firstPass: List<FlashTrustedPeer> = LegacyTrustMigration.computeMigrations(
            existingRoomDeviceIds = emptySet(),
            legacyPeers = legacy,
            nowMs = 100L,
        )
        val existingAfterFirstPass = firstPass.map { it.deviceId }.toSet()

        val secondPass = LegacyTrustMigration.computeMigrations(
            existingRoomDeviceIds = existingAfterFirstPass,
            legacyPeers = legacy,
            nowMs = 200L,
        )
        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun `pinned room row wins over legacy entry - pin is never downgraded`() {
        val rows = LegacyTrustMigration.computeMigrations(
            existingRoomDeviceIds = setOf("device-a"),
            legacyPeers = mapOf(FlashDeviceId("device-a") to "Old Name"),
            nowMs = 5L,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `mixed case - only new devices migrate`() {
        val rows = LegacyTrustMigration.computeMigrations(
            existingRoomDeviceIds = setOf("known-1", "known-2"),
            legacyPeers = mapOf(
                FlashDeviceId("known-1") to "Known One",
                FlashDeviceId("fresh") to "Fresh Device",
                FlashDeviceId("known-2") to "Known Two",
            ),
            nowMs = 7L,
        )
        assertEquals(listOf(FlashTrustedPeer("fresh", "Fresh Device", "", 7L)), rows)
    }
}
