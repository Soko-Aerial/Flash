@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop.linux

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.security.identity.SecretKeyStore
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where [DbusSecretKeyStore] reaches the Secret Service. A thin seam so the search, unlock, prompt and read flow is
 * unit-tested with fakes, and the dbus-java connection stays in one small class ([DbusSecretServiceTransport]).
 */
internal interface SecretServiceTransport : AutoCloseable {
    val service: SecretServiceApi.Service
    fun session(path: DBusPath): SecretServiceApi.Session
    fun collection(path: DBusPath): SecretServiceApi.Collection
    fun item(path: DBusPath): SecretServiceApi.Item

    /** Shows [prompt] and waits up to [timeoutMs]. `true` only if the user completed it (did not dismiss it). */
    fun runPrompt(prompt: DBusPath, timeoutMs: Long): Boolean
}

/**
 * Keeps Flash's identity master key in the user's keyring through the freedesktop.org Secret Service
 * (GNOME Keyring, KWallet's compatibility service, KeePassXC). Linux plan L1, ADR-092.
 *
 * The key is one Secret Service item with the attributes `application=flash`, `account=identity-master-key`.
 * Every call opens its own session bus connection and closes it again: the key is read once per start and written
 * once per install, so there is nothing to keep alive.
 *
 * - **Transport of the secret:** the session algorithm is `plain`, so the 32 key bytes cross the per-user session bus
 *   unencrypted. The bus is private to the login session; the encrypted `dh-ietf1024-sha256-aes128-cbc-pkcs7` mode was
 *   left out to keep this layer small. Recorded in ADR-092.
 * - **Locked keyring:** the item or collection is unlocked through the service; if that needs the user, the prompt is
 *   shown and waited for up to [promptTimeoutMs]. Dismissing it, or timing out, throws.
 * - **Every failure throws.** No service on the bus, headless session, locked and dismissed, wrong shape: the caller
 *   ([com.transfer.flash.core.security.identity.IdentityKeyVault.withKeyring]) then uses the owner-only key file.
 */
internal class DbusSecretKeyStore(
    private val openTransport: () -> SecretServiceTransport = { DbusSecretServiceTransport.open() },
    private val attributes: Map<String, String> = DEFAULT_ATTRIBUTES,
    private val promptTimeoutMs: Long = PROMPT_TIMEOUT_MS,
) : SecretKeyStore {

    override fun lookup(): ByteArray? = openTransport().use { transport ->
        val session = openSession(transport)
        try {
            val found = transport.service.searchItems(attributes)
            val item = found.unlocked.firstOrNull() ?: run {
                val locked = found.locked.firstOrNull() ?: return null
                unlock(transport, locked)
                locked
            }
            transport.item(item).getSecret(session).value
        } finally {
            closeQuietly(transport, session)
        }
    }

    override fun store(key: ByteArray) {
        openTransport().use { transport ->
            val session = openSession(transport)
            try {
                val collection = transport.service.readAlias("default")
                check(collection.path != SecretServiceApi.NO_OBJECT) { "the keyring has no default collection" }
                unlock(transport, collection)
                val properties = HashMap<String, Variant<*>>()
                properties[SecretServiceApi.ITEM_LABEL] = Variant(LABEL)
                properties[SecretServiceApi.ITEM_ATTRIBUTES] = Variant(HashMap(attributes), "a{ss}")
                val secret = SecretServiceApi.SecretStruct(session, ByteArray(0), key.copyOf(), CONTENT_TYPE)
                val created = transport.collection(collection).createItem(properties, secret, true)
                awaitPrompt(transport, created.prompt)
            } finally {
                closeQuietly(transport, session)
            }
        }
    }

    private fun openSession(transport: SecretServiceTransport): DBusPath =
        transport.service.openSession("plain", Variant("")).session

    /** Unlocks [path]; shows the keyring's prompt when it needs the user. Throws if it stays locked. */
    private fun unlock(transport: SecretServiceTransport, path: DBusPath) {
        val result = transport.service.unlock(listOf(path))
        if (result.unlocked.isNotEmpty()) return
        awaitPrompt(transport, result.prompt)
    }

    private fun awaitPrompt(transport: SecretServiceTransport, prompt: DBusPath) {
        if (prompt.path == SecretServiceApi.NO_OBJECT) return
        check(transport.runPrompt(prompt, promptTimeoutMs)) { "the keyring prompt was dismissed or timed out" }
    }

    private fun closeQuietly(transport: SecretServiceTransport, session: DBusPath) {
        runCatching { transport.session(session).close() }
            .onFailure { FlashLog.i(TAG, "keyring session close failed: ${it.message}") }
    }

    companion object {
        val DEFAULT_ATTRIBUTES: Map<String, String> =
            mapOf("application" to "flash", "account" to "identity-master-key")
        const val LABEL = "Flash identity key"
        const val CONTENT_TYPE = "application/octet-stream"
        const val PROMPT_TIMEOUT_MS = 60_000L
        private const val TAG = "SECURITY"
    }
}

/** The real session-bus connection over dbus-java (`dbus-java-transport-native-unixsocket`). */
internal class DbusSecretServiceTransport private constructor(
    private val connection: DBusConnection,
) : SecretServiceTransport {

    override val service: SecretServiceApi.Service by lazy {
        connection.getRemoteObject(SecretServiceApi.BUS_NAME, SecretServiceApi.SERVICE_PATH, SecretServiceApi.Service::class.java)
    }

    override fun session(path: DBusPath): SecretServiceApi.Session =
        connection.getRemoteObject(SecretServiceApi.BUS_NAME, path.path, SecretServiceApi.Session::class.java)

    override fun collection(path: DBusPath): SecretServiceApi.Collection =
        connection.getRemoteObject(SecretServiceApi.BUS_NAME, path.path, SecretServiceApi.Collection::class.java)

    override fun item(path: DBusPath): SecretServiceApi.Item =
        connection.getRemoteObject(SecretServiceApi.BUS_NAME, path.path, SecretServiceApi.Item::class.java)

    override fun runPrompt(prompt: DBusPath, timeoutMs: Long): Boolean {
        val promptObject = connection.getRemoteObject(SecretServiceApi.BUS_NAME, prompt.path, SecretServiceApi.Prompt::class.java)
        val done = CountDownLatch(1)
        val dismissed = java.util.concurrent.atomic.AtomicBoolean(true)
        // Subscribe BEFORE asking for the prompt, or a fast Completed signal is missed.
        val subscription = connection.addSigHandler(
            SecretServiceApi.Prompt.Completed::class.java,
            promptObject,
            DBusSigHandler<SecretServiceApi.Prompt.Completed> { signal ->
                dismissed.set(signal.dismissed)
                done.countDown()
            },
        )
        try {
            promptObject.prompt("")
            return done.await(timeoutMs, TimeUnit.MILLISECONDS) && !dismissed.get()
        } finally {
            runCatching { subscription.close() }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    companion object {
        /** Throws when there is no session bus (`DBUS_SESSION_BUS_ADDRESS` unset, headless). */
        fun open(): DbusSecretServiceTransport =
            DbusSecretServiceTransport(DBusConnectionBuilder.forSessionBus().build())
    }
}
