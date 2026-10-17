package com.transfer.flash.core.radio.driver

import android.content.Context
import android.content.SharedPreferences

/**
 * Android SharedPreferences-backed persistence for [RadioStatePersistence].
 *
 * Saves counter high-water marks and replay windows with synchronous `commit()`.
 * Zero private keys, session keys, or plaintext data are ever persisted.
 */
public class AndroidPrefsRadioStatePersistence(
    context: Context,
) : RadioStatePersistence {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readAll(): Map<String, String> {
        return runCatching {
            val all = prefs.all
            val result = HashMap<String, String>(all.size)
            for ((k, v) in all) {
                if (v is String) {
                    result[k] = v
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    override fun writeAll(entries: Map<String, String>) {
        runCatching {
            prefs.edit().apply {
                clear()
                for ((k, v) in entries) {
                    putString(k, v)
                }
                commit()
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "flash_radio_state"
    }
}
