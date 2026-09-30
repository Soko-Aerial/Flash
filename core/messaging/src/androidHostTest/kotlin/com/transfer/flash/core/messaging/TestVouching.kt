package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.protocol.GroupVouchVerdict
import com.transfer.flash.core.messaging.protocol.GroupVouching
import com.transfer.flash.core.security.trust.VouchRules
import com.transfer.flash.core.security.trust.VouchVerdict
import java.util.concurrent.ConcurrentHashMap

/**
 * One device's trust store as [GroupVouching] sees it (ADR-044 V2), in memory.
 *
 * The precedence rules are the real [VouchRules] the production stores use; only the storage is faked, so a
 * test exercises the same accept/replace/refuse table. A paired device's pin is [pairedPin] (the harness's
 * source of truth); every other pin, vouched or first-use, lives here.
 */
internal class TestVouching(
    private val isPaired: (String) -> Boolean,
    private val pairedPin: (String) -> String?,
) : GroupVouching {

    private val pins = ConcurrentHashMap<String, String>()
    private val groups = ConcurrentHashMap<String, Set<String>>()

    /** The pin this device holds for [deviceId], or null. */
    fun pinOf(deviceId: String): String? = if (isPaired(deviceId)) pairedPin(deviceId) else pins[deviceId]

    /** The groups vouching [deviceId] on this device. */
    fun groupsOf(deviceId: String): Set<String> = groups[deviceId].orEmpty()

    /** A first-use pin: what a device that connected before any vouch would leave behind. */
    fun tofu(deviceId: String, fingerprintHex: String) {
        pins[deviceId] = VouchRules.normalize(fingerprintHex)
    }

    /** Forgets every unpaired pin and vouch, as a cleared trust store would. */
    fun clear() {
        pins.clear()
        groups.clear()
    }

    /** Every device this store holds an unpaired pin for. */
    fun pinnedDevices(): Set<String> = pins.keys.toSet()

    override fun verdict(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict =
        VouchRules.decide(isPaired(deviceId), pinOf(deviceId), groupsOf(deviceId), fingerprintHex, groupId).toGroup()

    override fun vouch(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict {
        val verdict = verdict(deviceId, fingerprintHex, groupId)
        if (verdict != GroupVouchVerdict.ACCEPT) return verdict
        val fingerprint = VouchRules.normalize(fingerprintHex)
        // A different key replaces the pin and the groups that vouched the old one; a paired device keeps its pairing.
        val kept = if (pinOf(deviceId)?.let(VouchRules::normalize) == fingerprint) groupsOf(deviceId) else emptySet()
        if (!isPaired(deviceId)) pins[deviceId] = fingerprint
        groups[deviceId] = kept + groupId
        return verdict
    }

    override fun isVouched(deviceId: String, fingerprintHex: String, groupId: String): Boolean =
        groupId in groupsOf(deviceId) && pinOf(deviceId)?.let(VouchRules::normalize) == VouchRules.normalize(fingerprintHex)

    override fun revoke(deviceId: String, groupId: String) {
        val remaining = VouchRules.afterRevoke(groupsOf(deviceId), groupId) ?: return
        if (remaining.isEmpty()) {
            groups.remove(deviceId)
            if (!isPaired(deviceId)) pins.remove(deviceId)
        } else {
            groups[deviceId] = remaining
        }
    }

    private fun VouchVerdict.toGroup(): GroupVouchVerdict = when (this) {
        VouchVerdict.ACCEPT -> GroupVouchVerdict.ACCEPT
        VouchVerdict.CONFLICT_PAIRED -> GroupVouchVerdict.CONFLICT_PAIRED
        VouchVerdict.CONFLICT_VOUCHED -> GroupVouchVerdict.CONFLICT_VOUCHED
        VouchVerdict.INVALID -> GroupVouchVerdict.INVALID
    }
}
