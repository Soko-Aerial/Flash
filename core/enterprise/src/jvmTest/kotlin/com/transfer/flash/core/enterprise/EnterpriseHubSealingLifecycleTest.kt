@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.SealingKeyRequest
import com.transfer.flash.core.enterprise.relay.FlashRelayClient
import com.transfer.flash.core.enterprise.relay.ScriptedRelayTransport
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.MemoryHubSealingKeyStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-126: how the façade drives the sealing key: published after enrolment and after a renewal, retried when the hub
 * is unreachable or rate limiting, replaced on a 409, removed on unenrol and wiped on revoke. Real HTTP classes, a
 * scripted hub.
 */
class EnterpriseHubSealingLifecycleTest {

    private class Rig(testScope: TestScope) {
        val store = MemoryEnterpriseTrustStore()
        val keyStore = MemoryHubSealingKeyStore()
        val org = TestOrg()
        val device = TestCrypto()
        val fake = FakeHub()
        var now = TestOrg.T0
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val transport = ScriptedRelayTransport { now }
        val scope = CoroutineScope(dispatcher)
        var token = 0
        val puts get() = fake.requests.filter { it.method == "PUT" && it.path == HubContract.PATH_DEVICE_SEALING_KEY }
        val deletes get() = fake.requests.filter { it.method == "DELETE" && it.path == HubContract.PATH_DEVICE_SEALING_KEY }

        val hub = EnterpriseHub(
            deviceId = "dev-1", friendlyName = "Phone", crypto = device, trustStore = store, scope = scope,
            httpClient = fake.client(), clock = { now }, ioDispatcher = dispatcher, reauthJitterMs = { 0L },
            sealingKeyStore = keyStore,
            relayFactory = { idp ->
                FlashRelayClient(
                    deviceId = "dev-1", friendlyName = "Phone", hubUrl = "", identityProvider = idp, transport = transport,
                    timeSource = object : FlashTimeSource { override fun nowMs(): Long = now },
                    ioDispatcher = dispatcher, scope = CoroutineScope(dispatcher), jitter = { it },
                )
            },
            tickMs = 3_600_000L, maintenanceEveryMs = 1_000L,
        )

        init {
            fake.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(org.fingerprint) }
            fake.on("POST", FakeHub.VERIFY) { HttpStatusCode.OK to FakeHub.verified("dev-1", "tok-${++token}") }
            fake.on("PUT", HubContract.PATH_DEVICE_SEALING_KEY) { HttpStatusCode.OK to FakeHub.ok() }
            fake.on("DELETE", HubContract.PATH_DEVICE_SEALING_KEY) { HttpStatusCode.OK to FakeHub.ok() }
            fake.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.OK to FakeHub.ok() }
        }

        suspend fun enrolled(serial: Long = 10L) {
            store.saveCredentials(org.credentialsFor("dev-1", device, serial = serial))
        }

        fun settle(t: TestScope, ms: Long = 100L) {
            t.runCurrent()
            t.advanceTimeBy(ms)
            t.runCurrent()
        }

        fun publishedKey(request: RecordedRequest): SealingKeyRequest = HubRestCodec.decode(request.body)
    }

    private val rigs = mutableListOf<Rig>()
    private fun TestScope.rig(): Rig = Rig(this).also { rigs += it }

    private fun hubTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            rigs.forEach { it.hub.shutdown(); it.scope.cancel() }
        }
    }

    @Test
    fun anEnrolledDevicePublishesAKeyWithAValidBindingWhenTheHubStarts() = hubTest {
        val r = rig()
        r.enrolled()
        r.hub.start()
        r.settle(this)

        val request = r.publishedKey(r.puts.single())
        assertTrue(
            HubSealingKeyPair.verifyBinding(r.device.identityPublicKeyEncoded, "dev-1", Base64.decode(request.sealingPublicKey), request.signature),
        )
        assertEquals(request.sealingPublicKey, Base64.encode(r.keyStore.load()!!.publicKeyEncoded), "what the hub got is what is stored")
        assertTrue(r.puts.single().authorization!!.startsWith("Bearer "), "published with the device's own sign-in")
        assertEquals(HubSealingKeyState.PUBLISHED, r.hub.sealingKeys!!.state.value)
    }

    @Test
    fun aDeviceThatIsNotEnrolledMakesNoKey() = hubTest {
        val r = rig()
        r.hub.start()
        r.settle(this)
        assertTrue(r.puts.isEmpty())
        assertNull(r.keyStore.load())
    }

    @Test
    fun aRenewedCertificateRepublishesTheSameKey() = hubTest {
        val r = rig()
        r.enrolled(serial = 10L)
        r.hub.start()
        r.settle(this)
        val before = r.puts.size

        r.enrolled(serial = 11L) // the renewal saved new credentials
        r.settle(this)

        assertEquals(before + 1, r.puts.size, "renewal republishes")
        assertEquals(r.puts.map { r.publishedKey(it).sealingPublicKey }.toSet().size, 1, "the key itself does not change on renewal")
    }

    @Test
    fun aHubThatIsDownIsRetriedAndTheKeyArrivesWhenItComesBack() = hubTest {
        val r = rig()
        r.enrolled()
        r.fake.online = false
        r.hub.start()
        r.settle(this)
        assertEquals(HubSealingKeyState.PUBLISHING, r.hub.sealingKeys!!.state.value)
        assertNotNull(r.keyStore.load(), "the key exists locally while offline")

        r.fake.online = true
        r.settle(this, ms = HubSealingKeyManager.FIRST_RETRY_MS + 1_000L)

        assertEquals(HubSealingKeyState.PUBLISHED, r.hub.sealingKeys!!.state.value)
        assertEquals(1, r.puts.size)
    }

    @Test
    fun aRateLimitIsWaitedOutNotHammered() = hubTest {
        val r = rig()
        r.enrolled()
        var answers = 0
        r.fake.on("PUT", HubContract.PATH_DEVICE_SEALING_KEY) {
            if (++answers == 1) HttpStatusCode.TooManyRequests to FakeHub.error("ERR_RATE_LIMIT") else HttpStatusCode.OK to FakeHub.ok()
        }
        r.hub.start()
        r.settle(this)
        assertEquals(1, answers, "one attempt, then it waits")
        r.settle(this, ms = 20_000L)
        assertEquals(1, answers, "still inside the 35 s wait")
        r.settle(this, ms = 20_000L)
        assertEquals(2, answers)
        assertEquals(HubSealingKeyState.PUBLISHED, r.hub.sealingKeys!!.state.value)
    }

    @Test
    fun aConflictForARetiredKeyMakesANewKeyAndPublishesIt() = hubTest {
        val r = rig()
        r.enrolled()
        val first = HubSealingKeyPair.generate()
        r.keyStore.save(first)
        var answers = 0
        r.fake.on("PUT", HubContract.PATH_DEVICE_SEALING_KEY) {
            if (++answers == 1) HttpStatusCode.Conflict to FakeHub.error("ERR_CONFLICT") else HttpStatusCode.OK to FakeHub.ok()
        }
        r.hub.start()
        r.settle(this)

        assertEquals(2, answers)
        val sent = r.puts.map { r.publishedKey(it).sealingPublicKey }
        assertEquals(Base64.encode(first.publicKeyEncoded), sent[0])
        assertTrue(sent[1] != sent[0], "the retried publish carries a NEW key")
        assertEquals(sent[1], Base64.encode(r.keyStore.load()!!.publicKeyEncoded))
    }

    @Test
    fun unenrolAsksTheHubToForgetTheKeyThenDeletesTheLocalKey() = hubTest {
        val r = rig()
        r.enrolled()
        r.hub.start()
        r.settle(this)
        assertNotNull(r.keyStore.load())

        assertEquals(UnenrolResult.DONE, r.hub.unenrol())

        assertEquals(1, r.deletes.size, "the hub is told while the sign-in still works")
        assertNull(r.keyStore.load(), "the local key is gone")
        assertNull(r.hub.sealingKeys!!.publishedFingerprint.value)
    }

    @Test
    fun unenrolOfflineStillDeletesTheLocalKey() = hubTest {
        val r = rig()
        r.enrolled()
        r.hub.start()
        r.settle(this)
        r.fake.online = false

        assertEquals(UnenrolResult.DONE_HUB_NOT_TOLD, r.hub.unenrol())
        assertNull(r.keyStore.load(), "a key must not outlive the enrolment because the hub was unreachable")
    }

    @Test
    fun aConfirmedRevocationWipesTheKeyWithoutCallingTheHub() = hubTest {
        val r = rig()
        r.enrolled()
        r.hub.start()
        r.settle(this)
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(r.org.revocations(serials = setOf(10L))) }

        assertTrue(r.hub.confirmRevocation())

        assertNull(r.keyStore.load())
        assertTrue(r.deletes.isEmpty(), "a revoked device cannot sign in; the hub already dropped the key")
    }

    @Test
    fun anUnconfirmedRevokedFrameKeepsTheKey() = hubTest {
        val r = rig()
        r.enrolled()
        r.hub.start()
        r.settle(this)
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(r.org.revocations(serials = setOf(99L))) }
        assertTrue(!r.hub.confirmRevocation())
        assertNotNull(r.keyStore.load(), "only a signed list naming this device may wipe anything")
    }
}
