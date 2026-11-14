@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.server.announcements

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.HubBearerSource
import com.transfer.flash.core.enterprise.announcements.RealHubAnnouncementsApi
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ERROR-410: the REAL phone-side client against the REAL announcement routes. The client used to leave `since` out on its
 * first fetch, the hub answered with the search shape, the client could not decode it and the phone never showed any
 * announcement posted from the console.
 */
class AnnouncementClientContractTest {

    @Test
    fun theFirstFetchOfAMemberReturnsTheOrgAndOwnDepartmentAnnouncementsAndNotOtherDepartments() = testApplication {
        val db = ServerDatabase.inMemory()
        val store = AnnouncementStore.open(db)
        val tokens = BearerTokenStore()
        store.createAnnouncement(scope = "org", departmentId = null, title = "Party", body = "Friday", authorKind = "console", authorRef = "c", authorName = "Admin", authorRole = "admin")
        store.createAnnouncement(scope = "department", departmentId = "software", title = "Sprint", body = "Monday", authorKind = "console", authorRef = "c", authorName = "Admin", authorRole = "admin")
        store.createAnnouncement(scope = "department", departmentId = "finance", title = "Budget", body = "Tuesday", authorKind = "console", authorRef = "c", authorName = "Admin", authorRole = "admin")
        val token = tokens.issue(HubPrincipal("dev-1", "member", "software", 100L)).first
        routing {
            announcementRoutes(store = store, principalResolver = BearerPrincipalResolver(tokens), router = null, limits = HubRateLimits.unlimited())
        }
        val api = RealHubAnnouncementsApi(httpClient = client, bearerSource = HubBearerSource { token })

        val first = api.fetchFeed("http://localhost", since = 0L, limit = 100)
        assertIs<FlashResult.Success<*>>(first, "the first fetch must decode: $first")
        val feed = (first as FlashResult.Success).value
        assertEquals(setOf("Party", "Sprint"), feed.items.map { it.title }.toSet())

        store.createAnnouncement(scope = "org", departmentId = null, title = "Later", body = "x", authorKind = "console", authorRef = "c", authorName = "Admin", authorRole = "admin")
        val delta = api.fetchFeed("http://localhost", since = feed.seq, limit = 100)
        assertIs<FlashResult.Success<*>>(delta)
        assertTrue((delta as FlashResult.Success).value.items.any { it.title == "Later" })
    }

    @Test
    fun emptyInitialSyncFollowedByNewPostWithKnownEpochDeliversDeltaNotFullResync() = testApplication {
        val db = ServerDatabase.inMemory()
        val store = AnnouncementStore.open(db)
        val tokens = BearerTokenStore()
        val memberToken = tokens.issue(HubPrincipal("dev-reader", "member", "software", 100L)).first
        routing {
            announcementRoutes(store = store, principalResolver = BearerPrincipalResolver(tokens), router = null, limits = HubRateLimits.unlimited())
        }
        val api = RealHubAnnouncementsApi(httpClient = client, bearerSource = HubBearerSource { memberToken })

        // 1. Initial sync on an empty hub: seq=0, fullResync=true, no items
        val emptySync = (api.fetchFeed("http://localhost", since = 0L, limit = 100, epoch = null) as FlashResult.Success).value
        assertEquals(0L, emptySync.seq)
        assertTrue(emptySync.fullResync)
        assertTrue(emptySync.items.isEmpty())

        // 2. Another user publishes the first announcement on the Hub
        store.createAnnouncement(scope = "org", departmentId = null, title = "First Real Post", body = "Important update", authorKind = "device", authorRef = "dev-author", authorName = "Admin", authorRole = "admin")

        // 3. Returning client passes stored (seq=0, epoch=emptySync.epoch): Hub treats this as delta (fullResync=false)
        val afterEmpty = (api.fetchFeed("http://localhost", since = emptySync.seq, limit = 100, epoch = emptySync.epoch) as FlashResult.Success).value
        assertEquals(1L, afterEmpty.seq)
        assertEquals(false, afterEmpty.fullResync)
        assertEquals(listOf("First Real Post"), afterEmpty.items.map { it.title })

        // 4. Newly enrolled client without prior epoch still gets a quiet initial fullResync snapshot
        val freshClient = (api.fetchFeed("http://localhost", since = 0L, limit = 100, epoch = null) as FlashResult.Success).value
        assertTrue(freshClient.fullResync)
        assertEquals(listOf("First Real Post"), freshClient.items.map { it.title })

        // 5. Stale epoch from an older hub history also forces fullResync=true
        val staleEpoch = (api.fetchFeed("http://localhost", since = 0L, limit = 100, epoch = "old-epoch-id") as FlashResult.Success).value
        assertTrue(staleEpoch.fullResync)
    }
}
