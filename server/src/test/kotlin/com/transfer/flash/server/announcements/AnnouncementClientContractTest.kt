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
}
