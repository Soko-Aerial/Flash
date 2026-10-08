package com.transfer.flash.desktop.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Linux plan L1: the Secret Service flow (open session, search, unlock, prompt, read, create) against a fake service.
 * It proves the sequence and the failure behaviour; it cannot prove the wire encoding, which is device test `LNX-02`.
 */
class DbusSecretKeyStoreTest {

    private class FakeKeyring {
        var item: ByteArray? = null
        var locked = false
        var promptCompletes = true
        var defaultCollection = "/org/freedesktop/secrets/collection/login"
        var promptShown = 0
        var sessionsOpened = 0
        var sessionsClosed = 0
        var lastReplace: Boolean? = null
        var lastAttributes: Any? = null
        var lastContentType: String? = null
        var transportsOpened = 0
        var transportsClosed = 0

        private val itemPath = DBusPath("/org/freedesktop/secrets/collection/login/1")
        private val noPrompt = DBusPath("/")
        private val promptPath = DBusPath("/org/freedesktop/secrets/prompt/p1")

        val service = object : SecretServiceApi.Service {
            override fun getObjectPath() = SecretServiceApi.SERVICE_PATH
            override fun openSession(algorithm: String, input: Variant<*>): SecretServiceApi.OpenSessionResult {
                assertEquals("plain", algorithm)
                sessionsOpened++
                return SecretServiceApi.OpenSessionResult(Variant(""), DBusPath("/org/freedesktop/secrets/session/s1"))
            }

            override fun searchItems(attributes: Map<String, String>): SecretServiceApi.SearchResult {
                assertEquals("flash", attributes["application"])
                assertEquals("identity-master-key", attributes["account"])
                val found = item?.let { listOf(itemPath) }.orEmpty()
                return if (locked) SecretServiceApi.SearchResult(emptyList(), found) else SecretServiceApi.SearchResult(found, emptyList())
            }

            override fun unlock(objects: List<DBusPath>): SecretServiceApi.UnlockResult {
                if (!locked) return SecretServiceApi.UnlockResult(objects, noPrompt)
                return SecretServiceApi.UnlockResult(emptyList(), promptPath)
            }

            override fun readAlias(name: String): DBusPath {
                assertEquals("default", name)
                return DBusPath(defaultCollection)
            }
        }

        fun transport(): SecretServiceTransport {
            transportsOpened++
            return object : SecretServiceTransport {
                override val service = this@FakeKeyring.service
                override fun session(path: DBusPath) = object : SecretServiceApi.Session {
                    override fun getObjectPath() = path.path
                    override fun close() {
                        sessionsClosed++
                    }
                }

                override fun collection(path: DBusPath) = object : SecretServiceApi.Collection {
                    override fun getObjectPath() = path.path
                    override fun createItem(
                        properties: Map<String, Variant<*>>,
                        secret: SecretServiceApi.SecretStruct,
                        replace: Boolean,
                    ): SecretServiceApi.CreateItemResult {
                        lastReplace = replace
                        lastAttributes = properties[SecretServiceApi.ITEM_ATTRIBUTES]?.value
                        lastContentType = secret.contentType
                        item = secret.value.copyOf()
                        return SecretServiceApi.CreateItemResult(itemPath, noPrompt)
                    }
                }

                override fun item(path: DBusPath) = object : SecretServiceApi.Item {
                    override fun getObjectPath() = path.path
                    override fun getSecret(session: DBusPath): SecretServiceApi.SecretStruct {
                        check(!locked) { "item is locked" }
                        return SecretServiceApi.SecretStruct(session, ByteArray(0), item!!.copyOf(), "application/octet-stream")
                    }
                }

                override fun runPrompt(prompt: DBusPath, timeoutMs: Long): Boolean {
                    promptShown++
                    if (promptCompletes) locked = false
                    return promptCompletes
                }

                override fun close() {
                    transportsClosed++
                }
            }
        }
    }

    private fun store(fake: FakeKeyring) = DbusSecretKeyStore(openTransport = fake::transport)
    private val key = ByteArray(32) { (it + 5).toByte() }

    @Test
    fun `lookup returns null when nothing is stored`() {
        val fake = FakeKeyring()

        assertNull(store(fake).lookup())
        assertEquals(fake.transportsOpened, fake.transportsClosed, "the connection must always be closed")
        assertEquals(fake.sessionsOpened, fake.sessionsClosed, "the session must always be closed")
    }

    @Test
    fun `store then lookup round-trips and replaces an earlier value`() {
        val fake = FakeKeyring()
        val keyring = store(fake)

        keyring.store(key)
        assertEquals(true, fake.lastReplace)
        assertEquals(mapOf("application" to "flash", "account" to "identity-master-key"), fake.lastAttributes)
        assertEquals("application/octet-stream", fake.lastContentType)
        assertContentEquals(key, keyring.lookup())
    }

    @Test
    fun `a locked keyring is unlocked through the prompt and then read`() {
        val fake = FakeKeyring().apply { item = key.copyOf(); locked = true }

        assertContentEquals(key, store(fake).lookup())
        assertEquals(1, fake.promptShown)
    }

    @Test
    fun `a dismissed prompt throws instead of returning nothing`() {
        val fake = FakeKeyring().apply { item = key.copyOf(); locked = true; promptCompletes = false }

        assertFailsWith<IllegalStateException> { store(fake).lookup() }
        assertEquals(fake.transportsOpened, fake.transportsClosed)
        assertEquals(fake.sessionsOpened, fake.sessionsClosed)
    }

    @Test
    fun `store refuses when there is no default collection`() {
        val fake = FakeKeyring().apply { defaultCollection = "/" }

        assertFailsWith<IllegalStateException> { store(fake).store(key) }
        assertFalse(fake.item != null)
    }

    @Test
    fun `no service on the bus throws so the caller falls back`() {
        val keyring = DbusSecretKeyStore(openTransport = { error("no session bus") })

        assertFailsWith<IllegalStateException> { keyring.lookup() }
        assertFailsWith<IllegalStateException> { keyring.store(key) }
    }

    @Test
    fun `the stored bytes are copied, not aliased`() {
        val fake = FakeKeyring()
        val mine = key.copyOf()

        store(fake).store(mine)
        mine.fill(0)

        assertTrue(fake.item!!.any { it != 0.toByte() })
    }
}
