package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId

/**
 * The cross-radio TXT-record contract (plan C3.2).
 *
 * Every radio transport (NSD/LAN today; Wi-Fi Direct, Wi-Fi Aware, BLE later)
 * MUST carry its advertised identity using exactly this key set so any radio
 * can decode any peer's advertisement without radio-specific knowledge:
 *
 * | Key         | Meaning                                | Required |
 * |-------------|----------------------------------------|----------|
 * | `device_id` | Stable unique device id                | yes      |
 * | `name`      | User-facing friendly name              | no       |
 * | `model`     | Device model string (UI row subtitle)  | no       |
 * | `proto`     | Flash protocol version (decimal int)   | yes      |
 * | `caps`      | Comma-joined capability flags          | no       |
 * | `fp8`       | First 8 hex chars of identity fingerprint | no    |
 *
 * Size budget (research 2026-08-23, plan P3.5-A2):
 * - RFC 6763 §6.2 recommends a typical DNS-SD TXT record ≤200 bytes (≤400 to fit
 *   a 512-byte DNS message); each constituent string is limited to 255 bytes
 *   (RFC 6763 §6.1/§6.4, https://www.rfc-editor.org/rfc/rfc6763.html).
 * - The joined `caps` value is therefore capped at [MAX_CAPS_VALUE_LENGTH] (120):
 *   `"caps="` (5) + 120 stays well inside one 255-byte TXT string and keeps the
 *   whole record comfortably under the RFC's few-hundred-byte guidance even with
 *   all other keys present. Oversized sets are truncated at FLAG boundaries
 *   (whole flags dropped, never cut mid-token).
 * - `fp8` is always 8 hex chars by construction upstream; [decode] tolerates any
 *   value (hostile input never throws).
 *
 * Unknown extra keys are ignored (forward compatibility). Values are trimmed;
 * [decode] returns null when `device_id` is missing/blank or when `proto`
 * cannot be parsed as an Int (never throws on hostile input). Absent `caps` /
 * `fp8` decode to emptySet / null respectively (backward compatible with
 * pre-P3.5 advertisers).
 */
internal object TxtCodec {

    const val KEY_DEVICE_ID = "device_id"
    const val KEY_NAME = "name"
    const val KEY_MODEL = "model"
    const val KEY_PROTO = "proto"
    const val KEY_CAPS = "caps"
    const val KEY_FP8 = "fp8"

    /**
     * Maximum length of the joined `caps` value. See class KDoc for the
     * RFC 6763-derived size rationale.
     */
    const val MAX_CAPS_VALUE_LENGTH = 120

    /** Encodes an identity into transport-agnostic TXT attributes. */
    fun encode(identity: FlashAdvertisedIdentity): Map<String, String> {
        val attrs = linkedMapOf(
            KEY_DEVICE_ID to identity.deviceId.value.trim(),
            KEY_NAME to identity.friendlyName.trim(),
            KEY_MODEL to identity.deviceModel.trim(),
            KEY_PROTO to identity.protocolVersion.toString(),
        )
        val caps = truncateFlags(identity.capabilities)
        if (caps.isNotEmpty()) attrs[KEY_CAPS] = caps
        identity.fingerprintPrefix?.trim()?.takeIf { it.isNotEmpty() }?.let {
            attrs[KEY_FP8] = it
        }
        return attrs
    }

    /**
     * Joins capability flags comma-separated; when the joined form exceeds
     * [MAX_CAPS_VALUE_LENGTH], trailing flags are dropped whole (never split
     * mid-token) until it fits. Returns "" for an empty/null input.
     */
    fun truncateFlags(flags: Set<String>?): String {
        var joined = flags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().joinToString(",")
        while (joined.length > MAX_CAPS_VALUE_LENGTH) {
            val lastComma = joined.lastIndexOf(',')
            if (lastComma < 0) return ""
            joined = joined.substring(0, lastComma)
        }
        return joined
    }

    /**
     * Decodes TXT attributes into an identity.
     * Returns null when `device_id` is missing/blank or `proto` is not a valid
     * integer; missing optional keys default to empty strings / emptySet / null.
     */
    fun decode(attrs: Map<String, String>): FlashAdvertisedIdentity? {
        val deviceId = attrs[KEY_DEVICE_ID]?.trim().orEmpty()
        if (deviceId.isBlank()) return null
        val protocolVersion = attrs[KEY_PROTO]?.trim()?.toIntOrNull() ?: return null
        val capabilities = attrs[KEY_CAPS]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        return FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(deviceId),
            friendlyName = attrs[KEY_NAME]?.trim().orEmpty(),
            deviceModel = attrs[KEY_MODEL]?.trim().orEmpty(),
            protocolVersion = protocolVersion,
            capabilities = capabilities,
            fingerprintPrefix = attrs[KEY_FP8]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
