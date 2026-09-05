package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.model.FlashTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun transportRank(transport: FlashTransportType): Int =
    com.transfer.flash.core.network.resilience.SessionHardeningPolicy.transportRank(transport)

class SessionHardeningPolicyTest {

    private val policy = SessionHardeningPolicy(maxConcurrentSessions = 8)

    @Test
    fun `admission allows up to limit and rejects beyond`() {
        assertTrue(policy.canAcceptSession(0))
        assertTrue(policy.canAcceptSession(7))
        assertFalse(policy.canAcceptSession(8))
        assertFalse(policy.canAcceptSession(20))
    }

    @Test
    fun `lower rank wins - lan displaces wifi direct`() {
        assertEquals(
            DuplicateSessionDecision.PreferNew,
            policy.resolveDuplicate(transportRank(FlashTransportType.WIFI_DIRECT), 0),
        )
        assertEquals(
            DuplicateSessionDecision.PreferNew,
            policy.resolveDuplicate(
                FlashTransportType.WIFI_DIRECT,
                FlashTransportType.LAN,
            ),
        )
    }

    @Test
    fun `worse new path keeps existing`() {
        assertEquals(
            DuplicateSessionDecision.KeepExisting,
            policy.resolveDuplicate(0, 1),
        )
        assertEquals(
            DuplicateSessionDecision.KeepExisting,
            policy.resolveDuplicate(0, 99),
        )
    }

    @Test
    fun `tie keeps existing - documented behavior`() {
        for (rank in listOf(0, 1, 2, 3, 99)) {
            assertEquals(
                "tie at rank $rank must KeepExisting",
                DuplicateSessionDecision.KeepExisting,
                policy.resolveDuplicate(rank, rank),
            )
        }
        assertEquals(
            DuplicateSessionDecision.KeepExisting,
            policy.resolveDuplicate(FlashTransportType.LAN, FlashTransportType.LAN),
        )
    }

    @Test
    fun `rank order mirrors discovery priority`() {
        val rankOf = { t: FlashTransportType -> SessionHardeningPolicy.transportRank(t) }
        val lan = rankOf(FlashTransportType.LAN)
        val direct = rankOf(FlashTransportType.WIFI_DIRECT)
        val ws = rankOf(FlashTransportType.WEBSOCKET)
        val relayClass = rankOf(FlashTransportType.RELAY)
        val mesh = rankOf(FlashTransportType.MESH)
        val unknown = rankOf(FlashTransportType.UNKNOWN)

        assertTrue(lan < direct)
        assertTrue(direct < ws)
        assertTrue(ws < relayClass)
        assertEquals(relayClass, mesh) // BLE-presence (post-v1) will share rank 3
        assertTrue(relayClass < unknown)
        assertEquals(SessionHardeningPolicy.TRANSPORT_RANK_UNKNOWN, unknown)
        assertEquals(SessionHardeningPolicy.TRANSPORT_RANK_LAN, 0)
    }

    @Test
    fun `unknown never wins against any known path`() {
        for (t in FlashTransportType.entries) {
            if (t == FlashTransportType.UNKNOWN) continue
            assertEquals(
                DuplicateSessionDecision.PreferNew,
                policy.resolveDuplicate(FlashTransportType.UNKNOWN, t),
            )
        }
    }

    @Test
    fun `default concurrency limit is documented value 8`() {
        assertEquals(8, SessionHardeningPolicy().maxConcurrentSessions)
    }
}
