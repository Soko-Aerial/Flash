package com.transfer.flash.core.security.trust

import android.content.Context
import android.content.SharedPreferences
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult

/**
 * Android [SharedPreferences] implementation of [FlashTrustStore].
 * Maintains 100% backward compatibility with Flash 1.0 pairing storage keys (`flash_ws_pairing`).
 */
public class AndroidPreferencesTrustStore(
    private val preferences: SharedPreferences,
) : FlashTrustStore {

    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    override fun isTrusted(deviceId: FlashDeviceId): Boolean {
        return preferences.contains(keyFor(deviceId.value))
    }

    override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
        preferences.edit().putString(keyFor(deviceId.value), friendlyName).apply()
        return FlashResult.Success(Unit)
    }

    override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
        preferences.edit()
            .remove(keyFor(deviceId.value))
            .remove(sessionKeyFor(deviceId.value))
            .remove(pinKeyFor(deviceId.value))
            .apply()
        return FlashResult.Success(Unit)
    }

    override fun saveSessionKey(deviceId: FlashDeviceId, key: ByteArray): FlashResult<Unit> {
        val encoded = com.transfer.flash.core.common.protocol.Base64.encode(key)
        preferences.edit().putString(sessionKeyFor(deviceId.value), encoded).apply()
        return FlashResult.Success(Unit)
    }

    override fun getSessionKey(deviceId: FlashDeviceId): ByteArray? {
        val encoded = preferences.getString(sessionKeyFor(deviceId.value), null) ?: return null
        return runCatching { com.transfer.flash.core.common.protocol.Base64.decode(encoded) }.getOrNull()
    }

    override fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit> {
        preferences.edit().putString(pinKeyFor(deviceId.value), fingerprintHex.uppercase()).apply()
        return FlashResult.Success(Unit)
    }

    override fun getPin(deviceId: FlashDeviceId): String? {
        return preferences.getString(pinKeyFor(deviceId.value), null)
    }

    override fun getTrustedPeers(): Map<FlashDeviceId, String> {
        val result = mutableMapOf<FlashDeviceId, String>()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val rawId = key.removePrefix(KEY_PREFIX)
                if (rawId.isNotBlank()) {
                    result[FlashDeviceId(rawId)] = value
                }
            }
        }
        return result
    }

    private fun keyFor(deviceId: String): String = "$KEY_PREFIX$deviceId"
    private fun sessionKeyFor(deviceId: String): String = "$SESSION_KEY_PREFIX$deviceId"
    private fun pinKeyFor(deviceId: String): String = "$PIN_PREFIX$deviceId"

    public companion object {
        public const val PREFERENCES_NAME: String = "flash_ws_pairing"
        public const val KEY_PREFIX: String = "paired_"
        public const val SESSION_KEY_PREFIX: String = "session_key_"
        public const val PIN_PREFIX: String = "pin_"
    }

}
