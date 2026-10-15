@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.hubprotocol.certs.RevocationList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubRevocationCacheTest {
    private fun list(v: Long, vararg ids: String) =
        RevocationList(version = v, revokedDeviceIds = ids.toSet(), issuerPublicKey = "k", signature = "s")

    @Test
    fun keepsTheNewestAndIgnoresAReplayedOlderList() = runTest {
        var next: RevocationList? = list(5, "a")
        val cache = HubRevocationCache { next }
        assertNull(cache.current())
        cache.refresh()
        assertEquals(5, cache.current()?.version)
        next = list(3)
        cache.refresh()
        assertEquals(5, cache.current()?.version, "an older list must not un-revoke anyone")
        next = list(6, "a", "b")
        cache.refresh()
        assertEquals(setOf("a", "b"), cache.current()?.revokedDeviceIds)
    }

    @Test
    fun aFailedFetchKeepsThePreviousListAndClearForgets() = runTest {
        var mode = 0
        val cache = HubRevocationCache {
            when (mode) { 0 -> list(2, "x"); 1 -> null; else -> error("boom") }
        }
        cache.refresh()
        mode = 1; cache.refresh()
        mode = 2; cache.refresh()
        assertEquals(2, cache.current()?.version)
        cache.clear()
        assertNull(cache.current())
    }
}
