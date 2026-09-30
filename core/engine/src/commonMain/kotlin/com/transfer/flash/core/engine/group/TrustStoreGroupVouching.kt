package com.transfer.flash.core.engine.group

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.messaging.protocol.GroupVouchVerdict
import com.transfer.flash.core.messaging.protocol.GroupVouching
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.VouchRules
import com.transfer.flash.core.security.trust.VouchVerdict

/**
 * Backs the messaging module's [GroupVouching] port with the host's [FlashTrustStore] (ADR-044 V2).
 *
 * `:core:messaging` cannot see `:core:security`, so each host hands the chat repository this adapter, like
 * [FlashGroupCrypto]. It adds no rule of its own: the accept/replace/refuse table is [VouchRules], applied by the
 * store, and the TLS layer already trusts whatever `getPin` returns, so a vouch needs no change there.
 *
 * A blank device id can never name a peer (`FlashDeviceId` refuses it), so it is answered as an invalid vouch
 * instead of throwing inside the group layer, which feeds this the ids an owner-signed certificate carries.
 */
public class TrustStoreGroupVouching(private val trustStore: FlashTrustStore) : GroupVouching {

    override fun verdict(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict =
        if (deviceId.isBlank()) {
            GroupVouchVerdict.INVALID
        } else {
            trustStore.vouchVerdict(FlashDeviceId(deviceId), fingerprintHex, groupId).toGroup()
        }

    override fun vouch(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict =
        if (deviceId.isBlank()) {
            GroupVouchVerdict.INVALID
        } else {
            trustStore.applyVouch(FlashDeviceId(deviceId), fingerprintHex, groupId).toGroup()
        }

    override fun isVouched(deviceId: String, fingerprintHex: String, groupId: String): Boolean {
        if (deviceId.isBlank()) return false
        val id = FlashDeviceId(deviceId)
        return groupId in trustStore.vouchingGroups(id) &&
            trustStore.getPin(id)?.let(VouchRules::normalize) == VouchRules.normalize(fingerprintHex)
    }

    override fun revoke(deviceId: String, groupId: String) {
        if (deviceId.isNotBlank()) trustStore.revokeVouch(FlashDeviceId(deviceId), groupId)
    }

    private fun VouchVerdict.toGroup(): GroupVouchVerdict = when (this) {
        VouchVerdict.ACCEPT -> GroupVouchVerdict.ACCEPT
        VouchVerdict.CONFLICT_PAIRED -> GroupVouchVerdict.CONFLICT_PAIRED
        VouchVerdict.CONFLICT_VOUCHED -> GroupVouchVerdict.CONFLICT_VOUCHED
        VouchVerdict.INVALID -> GroupVouchVerdict.INVALID
    }
}
