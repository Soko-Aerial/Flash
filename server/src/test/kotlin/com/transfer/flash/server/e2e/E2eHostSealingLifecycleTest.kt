@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.enterprise.EnrollmentOutcome
import com.transfer.flash.core.enterprise.EnterpriseHub
import com.transfer.flash.core.enterprise.HubSealingKeyState
import com.transfer.flash.core.enterprise.UnenrolResult
import com.transfer.flash.core.enterprise.relay.FlashRelayClient
import com.transfer.flash.core.enterprise.relay.KtorRelayTransport
import com.transfer.flash.core.security.enterprise.HubKeyVerdict
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingFingerprint
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingPins
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.MemoryHubSealingKeyStore
import com.transfer.flash.core.security.enterprise.MemoryHubSealingPinStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-126 against the REAL hub: the host-level sealing key lifecycle. Each device here is the real [EnterpriseHub] with
 * the sealing key store the hosts pass it (an in-memory store; the platform stores have their own tests), so nobody calls
 * the publish endpoint by hand: the key is made, published, served to colleagues, used to seal and open a message,
 * removed on unenrol, and a different key for the same device is noticed by the pins.
 */
class E2eHostSealingLifecycleTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<KeyedDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    /** One install, built like the hosts build theirs: with a [MemoryHubSealingKeyStore] handed to [EnterpriseHub]. */
    private class KeyedDevice(val id: String, val hub: E2eHub) {
        val crypto = E2eCrypto()
        val trust = MemoryEnterpriseTrustStore()
        val keys = MemoryHubSealingKeyStore()
        val received = CopyOnWriteArrayList<RelayFrame.Deliver>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 } }

        val client: EnterpriseHub = EnterpriseHub(
            deviceId = id, friendlyName = "Test $id", crypto = crypto, trustStore = trust, scope = scope,
            httpClient = http, ownsHttpClient = false, allowCleartext = { true }, clock = hub.clock::now,
            ioDispatcher = Dispatchers.IO, sealingKeyStore = keys,
            relayFactory = { identity ->
                FlashRelayClient(
                    deviceId = id, friendlyName = "Test $id", hubUrl = trust.credentials.value?.hubUrl.orEmpty(),
                    identityProvider = identity,
                    transport = KtorRelayTransport(client = HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }),
                    jitter = { 0L },
                )
            },
            tickMs = 250L, maintenanceEveryMs = 0L,
        )

        init {
            client.start()
            scope.launch {
                client.relay.incomingDeliveries.collect {
                    received += it
                    client.relay.acknowledgeReceipt(it.id)
                }
            }
        }

        suspend fun enrol(): KeyedDevice {
            val outcome = client.enroll(hub.invite())
            check(outcome is EnrollmentOutcome.Enrolled) { "enrol failed: $outcome" }
            check(eventually(10_000L) { client.relay.state.value == com.transfer.flash.core.enterprise.relay.RelaySessionState.CONNECTED }) { "relay of $id did not connect" }
            return this
        }

        /** The directory entry of [other] as a colleague sees it (uncached). */
        suspend fun directoryEntry(other: String) =
            (client.directory.fetchDepartmentMembers(hub.url, "eng") as? FlashResult.Success)?.value?.firstOrNull { it.deviceId == other }

        fun shutdown() {
            runCatching { client.shutdown() }
            scope.cancel()
            runCatching { http.close() }
        }
    }

    private fun newHub() = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
    private fun device(id: String, hub: E2eHub) = KeyedDevice(id, hub).also { devices += it }

    @Test
    fun anEnrolledDevicePublishesItsOwnKeyAndAColleagueSealsAMessageTheHubCannotRead() = runBlocking<Unit> {
        val hub = newHub()
        val a = device("dev-a", hub).enrol()
        val b = device("dev-b", hub).enrol()

        assertTrue(eventually(10_000L) { a.client.sealingKeys!!.state.value == HubSealingKeyState.PUBLISHED }, "A published without anyone calling the endpoint")
        assertTrue(eventually(10_000L) { b.client.sealingKeys!!.state.value == HubSealingKeyState.PUBLISHED })

        val entry = assertNotNull(a.directoryEntry("dev-b"), "B is in the directory")
        val servedKey = Base64.getDecoder().decode(assertNotNull(entry.sealingPublicKey, "the hub serves B's key"))
        assertTrue(HubSealingKeyPair.verifyBinding(Base64.getDecoder().decode(entry.subjectPublicKey), "dev-b", servedKey, assertNotNull(entry.sealingSignature)))
        assertEquals(Base64.getEncoder().encodeToString(b.keys.load()!!.publicKeyEncoded), entry.sealingPublicKey, "what the hub serves is what B stores")

        val secret = "quarterly-numbers-0451"
        val sentAt = System.currentTimeMillis()
        val payload = HubSealing.seal(a.crypto, "dev-a", "dev-b", servedKey, "m-1", sentAt, "MSG_TEXT", secret)
        assertTrue(a.client.relay.sendEnvelope("dev-b", FlashEnvelope(id = "m-1", type = HubSealing.ENVELOPE_TYPE, payloadJson = payload, senderId = "dev-a", sentAt = sentAt)))
        val delivered = assertNotNull(awaitItem(b.received) { it.id == "m-1" })
        val aIdentity = Base64.getDecoder().decode(b.directoryEntry("dev-a")!!.subjectPublicKey)
        val opened = assertNotNull(HubSealing.open(b.client.sealingKeys!!.key()!!, aIdentity, delivered.from, "dev-b", delivered.id, delivered.sentAt, delivered.payload))
        assertEquals(secret, opened.body)
    }

    @Test
    fun unenrolRemovesTheKeyFromTheHubAndTheDevice() = runBlocking<Unit> {
        val hub = newHub()
        val a = device("dev-a", hub).enrol()
        val b = device("dev-b", hub).enrol()
        assertTrue(eventually(10_000L) { a.client.sealingKeys!!.state.value == HubSealingKeyState.PUBLISHED })
        assertNotNull(b.directoryEntry("dev-a")?.sealingPublicKey)

        assertEquals(UnenrolResult.DONE, a.client.unenrol())

        assertNull(a.keys.load(), "the local key is deleted")
        val after = b.directoryEntry("dev-a")
        assertTrue(after == null || after.sealingPublicKey == null, "the hub no longer serves a key for a device that left")
    }

    @Test
    fun aDeviceThatLeavesAndComesBackGetsANewKeyAndAColleaguesPinNoticesTheChange() = runBlocking<Unit> {
        val hub = newHub()
        val a = device("dev-a", hub).enrol()
        val b = device("dev-b", hub).enrol()
        assertTrue(eventually(10_000L) { a.client.sealingKeys!!.state.value == HubSealingKeyState.PUBLISHED })

        val pins = HubSealingPins(MemoryHubSealingPinStore())
        val firstKey = Base64.getDecoder().decode(assertNotNull(b.directoryEntry("dev-a")?.sealingPublicKey))
        assertEquals(HubKeyVerdict.FIRST_USE, pins.check("dev-a", HubSealingFingerprint.of(firstKey)))
        assertEquals(HubKeyVerdict.SAME, pins.check("dev-a", HubSealingFingerprint.of(firstKey)))

        // A "reinstalls": leaves and enrols again under the same device id.
        assertEquals(UnenrolResult.DONE, a.client.unenrol())
        a.client.enroll(hub.invite()).also { assertTrue(it is EnrollmentOutcome.Enrolled, "re-enrol: $it") }
        assertTrue(eventually(10_000L) { a.client.sealingKeys!!.state.value == HubSealingKeyState.PUBLISHED }, "the new key is published")

        val secondKey = Base64.getDecoder().decode(assertNotNull(eventuallyEntry(b, "dev-a") { it != null && it.sealingPublicKey != null }?.sealingPublicKey))
        assertNotEquals(HubSealingFingerprint.of(firstKey), HubSealingFingerprint.of(secondKey), "a fresh key, never the retired one")
        assertEquals(HubKeyVerdict.CHANGED, pins.check("dev-a", HubSealingFingerprint.of(secondKey)))
        assertTrue(pins.isBlocked("dev-a"), "nothing is sent to dev-a until the user continues")
        assertTrue(pins.confirm("dev-a"))
        assertTrue(!pins.isBlocked("dev-a"))
    }

    private suspend fun eventuallyEntry(viewer: KeyedDevice, id: String, ok: (com.transfer.flash.core.enterprise.EnterpriseDirectoryMember?) -> Boolean): com.transfer.flash.core.enterprise.EnterpriseDirectoryMember? {
        var last: com.transfer.flash.core.enterprise.EnterpriseDirectoryMember? = null
        eventually(10_000L) {
            last = viewer.directoryEntry(id)
            ok(last)
        }
        return last
    }
}
