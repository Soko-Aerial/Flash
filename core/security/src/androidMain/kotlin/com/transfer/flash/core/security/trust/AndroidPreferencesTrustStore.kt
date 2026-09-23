package com.transfer.flash.core.security.trust

import android.content.Context
import android.content.SharedPreferences
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult

/**
 * Android [SharedPreferences] implementation of [FlashTrustStore].
 * Maintains backward compatibility with Flash 1.0 pairing storage keys (`flash_ws_pairing`).
 *
 * **Session keys are sealed at rest** (audit 2026-09-23, S4) with [sealer], an AndroidKeyStore AES-GCM
 * key by default. They used to be stored as plain Base64, readable on any rooted device and copied by
 * cloud backup. Legacy plaintext entries are re-sealed the first time they are read. A key that cannot
 * be sealed is not stored (the save fails); a sealed key that can no longer be opened is dropped, and
 * the peer has to be re-paired.
 *
 * Pins and friendly names stay plaintext: a pin is a public-key fingerprint, not a secret. What matters
 * for pins is that they do not leave the device, which the app's backup rules enforce.
 */
public class AndroidPreferencesTrustStore(
    private val preferences: SharedPreferences,
    private val sealer: SecretSealer = KeystoreSecretSealer(),
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
            .remove(verifiedKeyFor(deviceId.value))
            .apply()
        return FlashResult.Success(Unit)
    }

    override fun saveSessionKey(deviceId: FlashDeviceId, key: ByteArray): FlashResult<Unit> {
        val sealed = try {
            sealer.seal(key)
        } catch (e: Exception) {
            // Never fall back to storing the key in the clear.
            return FlashResult.Failure(
                FlashError.Unknown("Could not seal session key for ${deviceId.value}: ${e.message}", e),
            )
        }
        preferences.edit().putString(sessionKeyFor(deviceId.value), SEALED_PREFIX + sealed).apply()
        return FlashResult.Success(Unit)
    }

    override fun getSessionKey(deviceId: FlashDeviceId): ByteArray? {
        val prefKey = sessionKeyFor(deviceId.value)
        val stored = preferences.getString(prefKey, null) ?: return null
        if (stored.startsWith(SEALED_PREFIX)) {
            val opened = sealer.open(stored.removePrefix(SEALED_PREFIX))
            if (opened == null) {
                // The sealing key is gone (restore, keystore wipe): this entry is unrecoverable, and a
                // stale unreadable key would only make every inbound FLASH_SEC frame fail. Re-pair.
                preferences.edit().remove(prefKey).apply()
            }
            return opened
        }
        // Legacy plaintext Base64 (pre-S4): re-store it sealed. If sealing fails right now the legacy
        // entry is left in place and still served, rather than losing the pairing.
        val legacy = runCatching { Base64.decode(stored) }.getOrNull() ?: return null
        saveSessionKey(deviceId, legacy)
        return legacy
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
    override fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> {
        preferences.edit().putBoolean(verifiedKeyFor(deviceId.value), true).apply()
        return FlashResult.Success(Unit)
    }

    override fun isVerified(deviceId: FlashDeviceId): Boolean =
        preferences.getBoolean(verifiedKeyFor(deviceId.value), false)

    private fun pinKeyFor(deviceId: String): String = "$PIN_PREFIX$deviceId"
    private fun verifiedKeyFor(deviceId: String): String = "$VERIFIED_PREFIX$deviceId"

    public companion object {
        public const val PREFERENCES_NAME: String = "flash_ws_pairing"
        public const val KEY_PREFIX: String = "paired_"
        public const val SESSION_KEY_PREFIX: String = "session_key_"
        public const val PIN_PREFIX: String = "pin_"

        /** Set once a pairing completes with protocol v2 (ADR-042); absent = legacy v1 pairing. */
        public const val VERIFIED_PREFIX: String = "verified_v2_"

        /** Marks a sealed session-key value; anything without it is a legacy plaintext entry. */
        public const val SEALED_PREFIX: String = "s1:"
    }

}
