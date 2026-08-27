package com.transfer.flash.core.security.trust.pinned

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.security.trust.FlashTrustStore

/** Snapshot row of a trusted peer produced by [LegacyTrustMigration.computeMigrations]. */
internal data class FlashTrustedPeer(
    val deviceId: String,
    val name: String,
    val fingerprintHex: String,
    val trustedAt: Long,
)

/**
 * Pure merge logic for the one-time migration of legacy SharedPreferences-backed
 * trust entries into the Room pin store (C2.4).
 *
 * Legacy rows (`AndroidPreferencesTrustStore`, keys `paired_<deviceId>` → name)
 * carry NO cryptographic fingerprint. They are imported as history with
 * [TofuPolicy.LEGACY_UNBOUND_FINGERPRINT] so:
 * - they still appear in `trustedPeers()` / UI device lists, but
 * - [TofuPolicy] treats them as unpinned and prompts again on next contact — a
 *   legacy "trust flag bound to nothing" must never silently become a crypto pin.
 *
 * Idempotency rule: any device already present in Room (with or without pin) wins;
 * the legacy entry is skipped. Pure function — unit-testable without Android/Room.
 */
internal object LegacyTrustMigration {

    /**
     * Computes the rows to insert for one migration pass.
     *
     * @param existingRoomDeviceIds device ids already persisted in Room.
     * @param legacyPeers legacy store snapshot ([FlashTrustStore.getTrustedPeers]).
     * @param nowMs wall-clock stamp applied to migrated rows.
     * @return entities to insert; empty when everything is already migrated.
     */
    fun computeMigrations(
        existingRoomDeviceIds: Set<String>,
        legacyPeers: Map<FlashDeviceId, String>,
        nowMs: Long,
    ): List<FlashTrustedPeer> = legacyPeers.mapNotNull { (deviceId, friendlyName) ->
        if (deviceId.value in existingRoomDeviceIds) {
            null
        } else {
            FlashTrustedPeer(
                deviceId = deviceId.value,
                name = friendlyName,
                fingerprintHex = TofuPolicy.LEGACY_UNBOUND_FINGERPRINT,
                trustedAt = nowMs,
            )
        }
    }
}
