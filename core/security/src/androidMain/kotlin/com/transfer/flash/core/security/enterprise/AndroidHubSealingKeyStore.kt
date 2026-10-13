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
 * [HubSealingKeyStore] for Android (ADR-126). The sealing key is a SOFTWARE P-256 key (the AndroidKeystore identity key
 * cannot do key agreement below API 31, ADR-121), so its PKCS#8 bytes need protection at rest: they are wrapped by
 * [sealer], a non-exportable AES-256-GCM key in AndroidKeystore (the same construction the pairing session keys use),
 * and only the wrapped form is written to app-private preferences. Neither the wrapped value nor the keystore alias is
 * ever in a backup (`backup_rules.xml` and `data_extraction_rules.xml` exclude `sharedpref`), and a wrapped value that
 * cannot be opened (keystore reset, restored onto another phone) reads as "no key", so a fresh one is made and published.
 *
 * Trade-off, stated plainly: code running as this app can unwrap the key; the wrapping protects the file at rest (a
 * copied preferences file is not enough), not the running process. Hardware-backed non-exportable agreement keys need
 * API 31 and are not used because the app supports API 24.
 */
@FlashInternalApi
public class AndroidHubSealingKeyStore(
    private val preferences: SharedPreferences,
    private val sealer: SecretSealer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HubSealingKeyStore {

    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        KeystoreSecretSealer(KEY_ALIAS),
    )

    override suspend fun load(): HubSealingKeyPair? = withContext(ioDispatcher) {
        val wrapped = preferences.getString(KEY_WRAPPED, null) ?: return@withContext null
        val bytes = runCatching { sealer.open(wrapped) }.getOrNull() ?: return@withContext null
        try {
            HubSealingKeyCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun save(key: HubSealingKeyPair): Unit = withContext(ioDispatcher) {
        val plain = HubSealingKeyCodec.encode(key)
        val wrapped = try {
            sealer.seal(plain)
        } finally {
            plain.fill(0)
        }
        // commit(), not apply(): the caller publishes the public half only after this returned.
        check(preferences.edit().putString(KEY_WRAPPED, wrapped).commit()) { "could not write the sealing key" }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        check(preferences.edit().remove(KEY_WRAPPED).commit()) { "could not remove the sealing key" }
    }

    public companion object {
        public const val PREFS_NAME: String = "flash_hub_sealing"
        public const val KEY_ALIAS: String = "flash_hub_seal_wrap_v1"
        private const val KEY_WRAPPED = "sealing_key_v1"
    }
}

/** [HubSealingPinStore] over app-private preferences. Pins are public fingerprints: no secrecy, only durability. */
@FlashInternalApi
public class AndroidHubSealingPinStore(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HubSealingPinStore {

    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(AndroidHubSealingKeyStore.PREFS_NAME, Context.MODE_PRIVATE),
    )

    override suspend fun load(): Map<String, HubSealingPin> = withContext(ioDispatcher) {
        HubSealingPinCodec.decode(preferences.getString(KEY_PINS, null))
    }

    override suspend fun save(pins: Map<String, HubSealingPin>): Unit = withContext(ioDispatcher) {
        check(preferences.edit().putString(KEY_PINS, HubSealingPinCodec.encode(pins)).commit()) { "could not write the key pins" }
    }

    private companion object {
        const val KEY_PINS = "peer_pins_v1"
    }
}
