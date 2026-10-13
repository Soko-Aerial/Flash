package com.transfer.flash.server.mailbox

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-124 / client<->server e2e finding: the mailbox's recipient check must follow the identity store (enrolment), not
 * a legacy table nobody fills. With the old implementation every enrolled-but-offline recipient was UNKNOWN_RECIPIENT.
 */
class DatabaseDeviceRegistryTest {

    private val now = 1_800_000_000_000L

    @Test
    fun anEnrolledActiveDeviceIsAValidRecipientAndARevokedOrUnknownOneIsNot() = runBlocking<Unit> {
        val db = ServerDatabase.inMemory()
        try {
            val registry = DatabaseDeviceRegistry(db)
            assertFalse(registry.isEnrolled("dev-a"), "nobody enrolled yet")

            val identities = IdentityStore.open(db)
            val key = FlashCrypto.generateEcKeyPair()
            val issuer = FlashCrypto.generateEcKeyPair()
            identities.createInvite("tok", null, DeviceCert.ROLE_MEMBER, emptySet(), emptySet(), now + 100_000, 1, now)
            identities.enroll("tok", "dev-a", key.toBase64Spki(), "A", now, 10, 1L) { serial, _ ->
                DeviceCert.issue(issuer.private, issuer.toBase64Spki(), "dev-a", key.toBase64Spki(), DeviceCert.ROLE_MEMBER, null, now - 1000, now + 100_000, serial)
            }
            assertTrue(registry.isEnrolled("dev-a"), "an ACTIVE enrolment is a valid mailbox recipient")
            assertFalse(registry.isEnrolled("dev-ghost"))

            identities.setStatus("dev-a", DeviceStatus.REVOKED, now)
            assertFalse(registry.isEnrolled("dev-a"), "a revoked device no longer receives queued mail")
        } finally {
            db.close()
        }
    }
}
