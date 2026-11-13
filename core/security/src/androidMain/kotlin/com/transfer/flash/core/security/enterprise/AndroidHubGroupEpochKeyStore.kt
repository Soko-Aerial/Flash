@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import android.content.Context
import android.content.SharedPreferences
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.trust.KeystoreSecretSealer
import com.transfer.flash.core.security.trust.SecretSealer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [GroupEpochKeyStore]: stores group epoch keys sealed at rest by an AndroidKeyStore
 * AES-256-GCM wrapping key in non-backed-up app-private SharedPreferences, mirroring [AndroidHubSealingKeyStore].
 */
@FlashInternalApi
public class AndroidHubGroupEpochKeyStore(
    private val preferences: SharedPreferences,
    private val sealer: SecretSealer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GroupEpochKeyStore {

    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        KeystoreSecretSealer(KEY_ALIAS),
    )

    override suspend fun loadAll(): List<GroupEpochKey> = withContext(ioDispatcher) {
        val wrapped = preferences.getString(KEY_RECORDS, null) ?: return@withContext emptyList()
        val bytes = runCatching { sealer.open(wrapped) }.getOrNull() ?: return@withContext emptyList()
        try {
            GroupEpochKeyCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun saveAll(keys: List<GroupEpochKey>): Unit = withContext(ioDispatcher) {
        val plain = GroupEpochKeyCodec.encode(keys)
        val wrapped = try {
            sealer.seal(plain)
        } finally {
            plain.fill(0)
        }
        check(preferences.edit().putString(KEY_RECORDS, wrapped).commit()) { "could not write group epoch keys" }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        check(preferences.edit().remove(KEY_RECORDS).commit()) { "could not clear group epoch keys" }
    }

    public companion object {
        public const val PREFS_NAME: String = "flash_hub_group_keys"
        public const val KEY_ALIAS: String = "flash_hub_group_keys_wrap_v1"
        private const val KEY_RECORDS: String = "group_keys_v1"
    }
}
