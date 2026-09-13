@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.jmdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.jmdns.ServiceInfo

/**
 * Coverage for [toNeutral] — the function that turns a JmDNS `ServiceInfo` into the
 * [JmdnsResolvedService] `JmdnsTransport` reads a peer's `device_id` from.
 *
 * **Why this suite exists.** `JmdnsTransportTest` drives everything through `FakeJmdnsBridge` (by
 * design — it runs on a CI box with no multicast), so `toNeutral` had **never executed** before
 * this file. CONVENTIONS R3.1's rule — an `actual` that is only compiled is not verified — applies
 * to it unchanged. It is the single point where a `device_id` can vanish between an advertisement
 * and the directory, and when it does, the only symptom is
 * `Dropping mDNS endpoint without device_id` for **every** peer, including our own.
 *
 * **What this suite canNOT prove, stated plainly:** that JmDNS delivers TXT across a real network
 * round trip. A same-host test cannot — [RealJmdnsBridge] derives its mDNS hostname from the bound
 * address (`mdnsHostnameFor`), so two bridges on one machine share a hostname and JmDNS ignores
 * records from its own host. That half is only observable between two real devices, which is what
 * the app log and the pairing runbook are for. This suite pins the half that is testable: given a
 * `ServiceInfo` carrying TXT, the extraction must carry every key through unchanged.
 */
public class JmdnsBridgeAttributeTest {

    @Test
    fun `toNeutral carries every txt attribute through`() {
        // Built exactly the way RealJmdnsBridge.register builds one.
        val info = ServiceInfo.create(
            SERVICE_TYPE,
            SERVICE_NAME,
            PORT,
            0,
            0,
            linkedMapOf(
                "device_id" to DEVICE_ID,
                "name" to "Desktop",
                "model" to "Desktop",
                "proto" to "2",
            ),
        )

        // THE DECISIVE HALF. `RealJmdnsBridge.register` builds its record with exactly this call.
        // A non-empty property map proves nothing about what goes on the wire — JmDNS could hold
        // the map locally and announce nothing. These bytes ARE the wire record. If this is null
        // or empty, every peer receives an advertisement with no TXT, `device_id` can never parse,
        // and the drop log is correct: the advertiser is what is broken, not the reader.
        val textBytes = info.textBytes
        assertTrue(
            "props must be serialised into wire TXT bytes or nothing is advertised; " +
                "textBytes=${textBytes?.toList()}",
            textBytes != null && textBytes.isNotEmpty(),
        )

        val neutral = info.toNeutral()

        assertEquals(SERVICE_NAME, neutral.serviceName)
        assertEquals(PORT, neutral.port)
        assertEquals(
            "the device_id JmdnsTransport keys the whole directory off must survive extraction",
            DEVICE_ID,
            neutral.attributes["device_id"],
        )
        assertEquals(
            "every advertised key must arrive, not just the one we happen to read",
            setOf("device_id", "name", "model", "proto"),
            neutral.attributes.keys,
        )
        assertTrue(
            "txtByteCount must report the bytes JmDNS delivered; it is what separates " +
                "'nothing advertised' from 'bytes we could not read' at the drop site",
            neutral.txtByteCount > 0,
        )
    }

    @Test
    fun `toNeutral reports empty attributes rather than inventing them`() {
        // A bare ServiceInfo, i.e. what an ANNOUNCEMENT carries before resolution. JmnsTransport
        // must be able to tell "no TXT yet" from "TXT without a device_id" — this pins that the
        // former yields an empty map rather than nulls or placeholder keys.
        val info = ServiceInfo.create(
            SERVICE_TYPE,
            SERVICE_NAME,
            PORT,
            0,
            0,
            emptyMap<String, String>(),
        )

        val neutral = info.toNeutral()

        assertTrue(
            "an unresolved/empty record must produce an EMPTY map, got ${neutral.attributes}",
            neutral.attributes.isEmpty(),
        )
        assertNull("an empty record has no device_id", neutral.attributes["device_id"])
        assertNull("an empty record has no name", neutral.attributes["name"])
    }

    private companion object {
        const val SERVICE_TYPE = "_flash-selfcheck._tcp.local."
        const val SERVICE_NAME = "Flash Attribute Check"
        const val DEVICE_ID = "attr-check-device-1"
        const val PORT = 45_999
    }
}
