@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.core.hubprotocol.rest.SealingKeyRequest
import com.transfer.flash.core.enterprise.EnrollmentOutcome
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.server.auth.HubRateLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Part 1 of ADR-124: `PUT|DELETE /api/v1/device/sealing-key`, with the REAL client key/signature code
 * (`HubSealingKeyPair`) against the real hub. The hub must verify the binding before it stores the key and must store
 * and serve public material only.
 */
class E2eSealingKeyContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()
    private val http = HttpClient(CIO)

    @AfterTest
    fun cleanUp() {
        http.close()
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private class World(val hub: E2eHub, val a: E2eDevice, val b: E2eDevice, val c: E2eDevice)

    /** a and b in `eng`, c in `ops`; all enrolled (no relay needed here). */
    private suspend fun world(realLimits: Boolean = false): World {
        val hub = E2eHub(limits = { if (realLimits) HubRateLimits() else HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        suspend fun enrolled(id: String, department: String): E2eDevice {
            val d = E2eDevice(id, hub).also { devices += it }
            assertIs<EnrollmentOutcome.Enrolled>(d.client.enroll(hub.invite(department = department)))
            return d
        }
        return World(hub, enrolled("dev-a", "eng"), enrolled("dev-b", "eng"), enrolled("dev-c", "ops"))
    }

    private suspend fun bearer(d: E2eDevice): String = assertNotNull(d.client.session.bearer(forceRefresh = true))

    private suspend fun put(d: E2eDevice, hub: E2eHub, body: String, token: String? = null): HttpResponse =
        http.put("${hub.url}${HubContract.PATH_DEVICE_SEALING_KEY}") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${token ?: bearer(d)}")
            setBody(body)
        }

    private fun body(spki: ByteArray, signature: String) =
        HubRestCodec.encode(SealingKeyRequest(Base64.getEncoder().encodeToString(spki), signature))

    private suspend fun publish(d: E2eDevice, hub: E2eHub, pair: HubSealingKeyPair = HubSealingKeyPair.generate()): HttpResponse =
        put(d, hub, body(pair.publicKeyEncoded, pair.bindingSignature(d.crypto, d.id)))

    private suspend fun members(asDevice: E2eDevice, hub: E2eHub, dept: String = "eng"): List<MemberDto> {
        val r = http.get("${hub.url}${HubContract.PATH_DIRECTORY_MEMBERS}?departmentId=$dept") {
            header("Authorization", "Bearer ${bearer(asDevice)}")
        }
        assertEquals(200, r.status.value, r.bodyAsText())
        return HubRestCodec.decode(r.bodyAsText())
    }

    private fun auditActions(hub: E2eHub): List<String> = hub.services.database.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT action FROM admin_audit ORDER BY idx").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private suspend fun code(r: HttpResponse): String = HubRestCodec.decode<ErrorBody>(r.bodyAsText()).code

    @Test
    fun theGoldenBindingBytesAreTheOnesTheClientSigns() {
        val spki = ByteArray(16) { it.toByte() }
        val hex = HubAuth.sealingKeyBindingBytes("dev-alpha", spki).joinToString("") { "%02x".format(it) }
        assertEquals("00000014666c6173682d6875622d7365616c6b65792d7631000000096465762d616c70686100000010000102030405060708090a0b0c0d0e0f", hex)
        assertEquals(hex, HubSealingKeyPair.bindingBytes("dev-alpha", spki).joinToString("") { "%02x".format(it) }, "client and server derive the same bytes")
    }

    @Test
    fun aVerifiedKeyIsStoredAndServedAndTheClientVerifiesTheServedBinding() = runBlocking<Unit> {
        val w = world()
        val pair = HubSealingKeyPair.generate()
        val response = publish(w.a, w.hub, pair)
        assertEquals(200, response.status.value, response.bodyAsText())

        val seen = members(w.b, w.hub).first { it.deviceId == "dev-a" }
        assertEquals(Base64.getEncoder().encodeToString(pair.publicKeyEncoded), seen.sealingPublicKey)
        val identityKey = Base64.getDecoder().decode(assertNotNull(seen.subjectPublicKey))
        assertTrue(
            HubSealingKeyPair.verifyBinding(identityKey, "dev-a", Base64.getDecoder().decode(assertNotNull(seen.sealingPublicKey)), assertNotNull(seen.sealingSignature)),
            "the client's own verification accepts what the hub serves",
        )
        assertNull(members(w.b, w.hub).first { it.deviceId == "dev-b" }.sealingPublicKey, "a device that published nothing has no key")
    }

    @Test
    fun theClientsOwnPublishCallIsAcceptedByTheHub() = runBlocking<Unit> {
        val w = world()
        val pair = HubSealingKeyPair.generate()
        val result = w.a.client.directory.publishSealingKey(
            w.hub.url,
            Base64.getEncoder().encodeToString(pair.publicKeyEncoded),
            pair.bindingSignature(w.a.crypto, "dev-a"),
        )
        assertTrue(result is com.transfer.flash.core.common.result.FlashResult.Success, result.toString())
        assertNotNull(w.hub.services.identities.sealingKey("dev-a"))
        val bad = w.a.client.directory.publishSealingKey(w.hub.url, Base64.getEncoder().encodeToString(pair.publicKeyEncoded), "AAAA")
        assertTrue(bad is com.transfer.flash.core.common.result.FlashResult.Failure, "a bad signature is a failure for the client")
    }

    @Test
    fun noBearerNoKeysAndAnotherDepartmentSeesNothing() = runBlocking<Unit> {
        val w = world()
        publish(w.a, w.hub)
        assertEquals(401, http.get("${w.hub.url}${HubContract.PATH_DIRECTORY_MEMBERS}?departmentId=eng").status.value)
        val unauthenticated = http.put("${w.hub.url}${HubContract.PATH_DEVICE_SEALING_KEY}") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(401, unauthenticated.status.value)
        val outsider = http.get("${w.hub.url}${HubContract.PATH_DIRECTORY_MEMBERS}?departmentId=eng") {
            header("Authorization", "Bearer ${bearer(w.c)}")
        }
        assertTrue(outsider.status.value in 400..499, "an out-of-scope caller is refused (${outsider.status.value})")
        assertTrue(!outsider.bodyAsText().contains("sealingPublicKey"), "no key material in the refusal")
    }

    @Test
    fun aBindingThatIsNotProvenByThisDevicesIdentityKeyIsRefusedAndNothingIsStored() = runBlocking<Unit> {
        val w = world()
        val pair = HubSealingKeyPair.generate()
        val spki = pair.publicKeyEncoded
        fun sign(d: E2eDevice, id: String, key: ByteArray) = Base64.getEncoder().encodeToString(d.crypto.sign(HubAuth.sealingKeyBindingBytes(id, key)))

        // wrong curve (P-384) with a valid signature over it
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded
        assertEquals(400, put(w.a, w.hub, body(p384, sign(w.a, "dev-a", p384))).status.value, "wrong curve")

        // malformed SPKI and malformed bodies
        val junk = ByteArray(40) { 7 }
        assertEquals(400, put(w.a, w.hub, body(junk, sign(w.a, "dev-a", junk))).status.value, "malformed SPKI")
        assertEquals(400, put(w.a, w.hub, HubRestCodec.encode(SealingKeyRequest("not base64 !!", "x"))).status.value)
        assertEquals(400, put(w.a, w.hub, "{not json").status.value)

        // signed by someone else's identity key (B signs A's binding)
        assertEquals(400, put(w.a, w.hub, body(spki, sign(w.b, "dev-a", spki))).status.value, "bad signature")

        // A signs a binding for ANOTHER device id: A cannot publish it as its own
        val other = put(w.a, w.hub, body(spki, sign(w.a, "dev-b", spki)))
        assertEquals(400, other.status.value, "binding for another device id")
        assertEquals("ERR_BAD_REQUEST", code(other))
        assertNull(w.hub.services.identities.sealingKey("dev-a"), "nothing stored by any refused attempt")

        // B replays A's published (key, signature) as its own
        assertEquals(200, publish(w.a, w.hub).status.value)
        val stored = assertNotNull(w.hub.services.identities.sealingKey("dev-a"))
        val replay = put(w.b, w.hub, HubRestCodec.encode(SealingKeyRequest(stored.sealingPublicKey, stored.sealingSignature)))
        assertEquals(400, replay.status.value, "another device cannot take over a bound key")
        assertNull(w.hub.services.identities.sealingKey("dev-b"))
    }

    @Test
    fun aRotationIsAuditedAndAnOlderKeyCannotBePutBackAndARepublishIsHarmless() = runBlocking<Unit> {
        val w = world()
        val first = HubSealingKeyPair.generate()
        val second = HubSealingKeyPair.generate()
        assertEquals(200, publish(w.a, w.hub, first).status.value)
        assertEquals(200, publish(w.a, w.hub, first).status.value, "an unchanged republish is accepted")
        assertEquals(200, publish(w.a, w.hub, second).status.value, "a new key rotates")
        val secondB64 = Base64.getEncoder().encodeToString(second.publicKeyEncoded)
        assertEquals(secondB64, assertNotNull(w.hub.services.identities.sealingKey("dev-a")).sealingPublicKey)

        val back = publish(w.a, w.hub, first)
        assertEquals(409, back.status.value, "a replayed older binding is refused")
        assertEquals("ERR_CONFLICT", code(back))
        assertEquals(secondB64, w.hub.services.identities.sealingKey("dev-a")!!.sealingPublicKey)

        val actions = auditActions(w.hub).filter { it.startsWith("device.sealing-key") }
        assertEquals(1, actions.count { it == "device.sealing-key.publish" }, "$actions")
        assertEquals(1, actions.count { it == "device.sealing-key.rotate" }, "$actions")
        assertTrue("device.sealing-key.refused" in actions, "$actions")
    }

    @Test
    fun revokingUnenrollingOrDeletingRemovesTheKey() = runBlocking<Unit> {
        val w = world()
        assertEquals(200, publish(w.a, w.hub).status.value)
        assertEquals(200, publish(w.b, w.hub).status.value)
        assertNotNull(w.hub.services.identities.sealingKey("dev-a"))

        val del = http.delete("${w.hub.url}${HubContract.PATH_DEVICE_SEALING_KEY}") { header("Authorization", "Bearer ${bearer(w.a)}") }
        assertEquals(200, del.status.value)
        assertNull(w.hub.services.identities.sealingKey("dev-a"))
        assertNull(members(w.b, w.hub).first { it.deviceId == "dev-a" }.sealingPublicKey)

        // An administrator revokes B: the key goes with the enrolment (trigger: whichever code path flips the status).
        w.hub.services.revocations!!.revokeDevice("dev-b", "test", "admin")
        assertNull(w.hub.services.identities.sealingKey("dev-b"), "revocation deletes the sealing key")

        // A device that unenrols leaves nothing behind.
        assertEquals(200, publish(w.a, w.hub).status.value)
        w.a.client.unenrol()
        assertNull(w.hub.services.identities.sealingKey("dev-a"), "unenrolment deletes the sealing key")
    }

    @Test
    fun publishingIsRateLimitedAndTheBodyIsCapped() = runBlocking<Unit> {
        val w = world(realLimits = true)
        val token = bearer(w.a)
        val pair = HubSealingKeyPair.generate()
        val good = body(pair.publicKeyEncoded, pair.bindingSignature(w.a.crypto, "dev-a"))
        val statuses = (1..8).map { put(w.a, w.hub, good, token).status.value }
        assertTrue(429 in statuses, "a burst is rate limited: $statuses")
        assertEquals("ERR_RATE_LIMIT", code(put(w.a, w.hub, good, token)))

        val big = body(pair.publicKeyEncoded, "A".repeat(70 * 1024))
        assertEquals(413, put(w.b, w.hub, big).status.value, "body over 64 KiB")
    }

    @Test
    fun theHubStoresAndServesPublicMaterialOnly() = runBlocking<Unit> {
        val w = world()
        val pair = HubSealingKeyPair.generate()
        assertEquals(200, publish(w.a, w.hub, pair).status.value)

        val columns = w.hub.services.database.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM pragma_table_info('hub_sealing_keys')").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertTrue(columns.none { it.contains("private", true) || it.contains("secret", true) || it.contains("pkcs8", true) }, "$columns")

        // The private half never left the device: its PKCS#8 bytes appear nowhere in the hub's database files.
        val pkcs8 = pair.exportPrivateKeyPkcs8()
        w.hub.services.database.withConnection { c -> c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
        val files = w.hub.dataDir.walkTopDown().filter { it.isFile }.map(File::readBytes).toList()
        assertTrue(files.isNotEmpty())
        assertTrue(files.none { indexOf(it, pkcs8) >= 0 }, "the PKCS#8 private key is not in the hub's data")
        // And the public key IS there, so the scan is able to find key material at all.
        assertTrue(files.any { indexOf(it, Base64.getEncoder().encodeToString(pair.publicKeyEncoded).toByteArray()) >= 0 })
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
