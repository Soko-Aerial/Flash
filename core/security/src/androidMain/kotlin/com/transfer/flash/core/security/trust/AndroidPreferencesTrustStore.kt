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
        preferences.edit().remove(keyFor(deviceId.value)).apply()
        return FlashResult.Success(Unit)
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

    public companion object {
        public const val PREFERENCES_NAME: String = "flash_ws_pairing"
        public const val KEY_PREFIX: String = "paired_"
    }
}
