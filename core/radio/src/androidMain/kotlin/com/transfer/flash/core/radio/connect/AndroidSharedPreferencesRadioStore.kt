package com.transfer.flash.core.radio.connect

import android.content.Context
import android.content.SharedPreferences

/**
 * Android SharedPreferences-backed persistence for remembered radio and authorization confirmation.
 *
 * Safety contract:
 * - Writes use commit() for atomic synchronous persistence.
 * - Corrupted, partial or unreadable values yield null / defaults; NEVER crash.
 * - Stores Bluetooth MAC address and display name only; NEVER private or session keys.
 */
public class AndroidSharedPreferencesRadioStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val KEY_BT_ADDRESS = "radio_bt_address"
    private val KEY_DISPLAY_NAME = "radio_display_name"
    private val KEY_LAST_PORT = "radio_last_port"
    private val KEY_AUTH_EPOCH_MS = "radio_auth_epoch_ms"
    private val KEY_AUTH_CALLSIGN = "radio_auth_callsign"

    public val rememberedStore: RememberedRadioStore = object : RememberedRadioStore {
        override suspend fun load(): RememberedRadio? {
            return runCatching {
                val addr = prefs.getString(KEY_BT_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
                val name = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
                val port = prefs.getString(KEY_LAST_PORT, null)?.takeIf { it.isNotBlank() }
                RememberedRadio(
                    bluetoothAddress = addr,
                    displayName = name,
                    lastKnownPortName = port,
                )
            }.getOrNull()
        }

        override suspend fun save(radio: RememberedRadio) {
            runCatching {
                prefs.edit().apply {
                    putString(KEY_BT_ADDRESS, radio.bluetoothAddress)
                    putString(KEY_DISPLAY_NAME, radio.displayName)
                    if (radio.lastKnownPortName != null) {
                        putString(KEY_LAST_PORT, radio.lastKnownPortName)
                    } else {
                        remove(KEY_LAST_PORT)
                    }
                    commit()
                }
            }
        }

        override suspend fun clear() {
            runCatching {
                prefs.edit().apply {
                    remove(KEY_BT_ADDRESS)
                    remove(KEY_DISPLAY_NAME)
                    remove(KEY_LAST_PORT)
                    commit()
                }
            }
        }
    }

    public val authorisationStore: RadioAuthorisationStore = object : RadioAuthorisationStore {
        override suspend fun load(): AuthorisationConfirmation? {
            return runCatching {
                if (!prefs.contains(KEY_AUTH_EPOCH_MS)) return null
                val epochMs = prefs.getLong(KEY_AUTH_EPOCH_MS, -1L)
                if (epochMs <= 0L) return null
                val callsign = prefs.getString(KEY_AUTH_CALLSIGN, null)?.takeIf { it.isNotBlank() }
                AuthorisationConfirmation(
                    confirmedAtEpochMs = epochMs,
                    operatorCallsign = callsign,
                )
            }.getOrNull()
        }

        override suspend fun save(confirmation: AuthorisationConfirmation) {
            runCatching {
                prefs.edit().apply {
                    putLong(KEY_AUTH_EPOCH_MS, confirmation.confirmedAtEpochMs)
                    if (confirmation.operatorCallsign != null) {
                        putString(KEY_AUTH_CALLSIGN, confirmation.operatorCallsign)
                    } else {
                        remove(KEY_AUTH_CALLSIGN)
                    }
                    commit()
                }
            }
        }

        override suspend fun clear() {
            runCatching {
                prefs.edit().apply {
                    remove(KEY_AUTH_EPOCH_MS)
                    remove(KEY_AUTH_CALLSIGN)
                    commit()
                }
            }
        }
    }

    public companion object {
        public const val PREFS_NAME: String = "flash_radio_settings"
    }
}
