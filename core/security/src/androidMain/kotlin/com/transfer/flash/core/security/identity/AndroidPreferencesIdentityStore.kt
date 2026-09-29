package com.transfer.flash.core.security.identity

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceNames
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import java.util.UUID

/**
 * Android [SharedPreferences] implementation of [FlashIdentityStore].
 * Maintains 100% backward compatibility with Flash 1.0 identity storage keys.
 *
 * Default names are an animal or a fruit picked from the device id ([FlashDeviceNames], ERROR-077): the old
 * default, "Flash <model>", gave every phone of one model the same name. A name still equal to that old
 * default is replaced once; a name the owner typed is kept.
 */
public class AndroidPreferencesIdentityStore(
    private val preferences: SharedPreferences,
    private val defaultNameProvider: (deviceId: String) -> String = FlashDeviceNames::forDeviceId,
    /** The pre-ERROR-077 default, recognised so it can be replaced. Read only for names starting "Flash ". */
    private val legacyDefaultName: () -> String = ::legacyModelName,
) : FlashIdentityStore {

    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    override fun getIdentity(): FlashIdentity {
        val rawDeviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            }

        val stored = preferences.getString(KEY_FRIENDLY_NAME, null)
        val friendlyName = when {
            stored == null -> defaultNameProvider(rawDeviceId).also { generated ->
                preferences.edit()
                    .putString(KEY_FRIENDLY_NAME, generated)
                    .putInt(KEY_NAME_VERSION, NAME_VERSION)
                    .apply()
            }
            preferences.getInt(KEY_NAME_VERSION, 0) < NAME_VERSION -> {
                val migrated = if (stored.startsWith("Flash ") && stored == legacyDefaultName()) {
                    defaultNameProvider(rawDeviceId)
                } else {
                    stored
                }
                preferences.edit()
                    .putString(KEY_FRIENDLY_NAME, migrated)
                    .putInt(KEY_NAME_VERSION, NAME_VERSION)
                    .apply()
                migrated
            }
            else -> stored
        }

        return FlashIdentity(
            deviceId = FlashDeviceId(rawDeviceId),
            friendlyName = friendlyName,
        )
    }

    override fun updateFriendlyName(name: String): FlashResult<Unit> {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return FlashResult.Failure(FlashError.StorageError("Friendly name cannot be blank"))
        }
        preferences.edit()
            .putString(KEY_FRIENDLY_NAME, trimmed)
            .putInt(KEY_NAME_VERSION, NAME_VERSION)
            .apply()
        return FlashResult.Success(Unit)
    }

    public companion object {
        public const val PREFERENCES_NAME: String = "flash_identity"
        public const val KEY_DEVICE_ID: String = "device_id"
        public const val KEY_FRIENDLY_NAME: String = "friendly_name"

        /** Set once the default-name migration (ERROR-077) has run for this install. */
        public const val KEY_NAME_VERSION: String = "friendly_name_version"
        private const val NAME_VERSION: Int = 2

        /** The default name before ERROR-077. Kept only to recognise it; new installs never get it. */
        public fun legacyModelName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isBlank()) "Flash Android" else "Flash $model"
        }
    }
}
