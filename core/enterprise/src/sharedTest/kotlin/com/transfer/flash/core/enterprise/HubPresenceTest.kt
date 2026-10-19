@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hub presence (the read side of `PersonDto.online`).
 *
 * The field symptom these guard: two colleagues who are **not** on one LAN chat over the hub, the
 * messages arrive, and the header still says Offline — because presence only ever came from LAN
 * sessions. `hubOnlineDeviceIds` is the missing input.
 */
class HubPresenceTest {

    @Test
    fun `ids the hub reports online are collected, everyone else is left out`() = runBlocking {
        val api = FakePeopleApi(arrayOf(page(person("alice", online = true), person("bob"))))

        assertEquals(setOf("alice"), hubOnlineDeviceIds(api, HUB))
    }

    @Test
    fun `paging follows nextCursor until the last page`() = runBlocking {
        val api = FakePeopleApi(
            arrayOf(
                page(person("alice", online = true), cursor = "c1"),
                page(person("bob", online = true), cursor = "c2"),
                page(person("dave")),
            ),
        )

        assertEquals(setOf("alice", "bob"), hubOnlineDeviceIds(api, HUB))
        assertEquals(3, api.calls)
    }

    @Test
    fun `a hub that cannot be read reports nothing rather than everybody offline`() = runBlocking {
        val api = FakePeopleApi(failure = FlashError.NetworkUnavailable("down"))

        // null = "we learned nothing", never "nobody is online": the caller keeps its last answer.
        assertNull(hubOnlineDeviceIds(api, HUB))
    }

    @Test
    fun `a hub too old for the listing reports nothing`() = runBlocking {
        val api = FakePeopleApi(arrayOf(PeoplePage(emptyList(), hubTooOld = true)))

        assertNull(hubOnlineDeviceIds(api, HUB))
    }

    @Test
    fun `an unenrolled or blank address is never dialled`() = runBlocking {
        val api = FakePeopleApi(arrayOf(page(person("alice", online = true))))

        assertNull(hubOnlineDeviceIds(api, ""))
        assertEquals(0, api.calls)
    }

    @Test
    fun `the page cap bounds one poll of a very large organisation`() = runBlocking {
        // Every page is full and points at the next, so only the cap can stop the walk.
        val api = FakePeopleApi(Array(10) { index -> page(person("p$index", online = true), cursor = "c$index") })

        val ids = hubOnlineDeviceIds(api, HUB, maxPages = 3)

        assertEquals(setOf("p0", "p1", "p2"), ids)
        assertEquals(3, api.calls)
    }

    @Test
    fun `cancellation is not mistaken for a hub failure`() = runBlocking {
        val api = FakePeopleApi(throwable = CancellationException("scope closed"))

        val thrown = try {
            hubOnlineDeviceIds(api, HUB)
            null
        } catch (e: CancellationException) {
            e
        }

        assertEquals("scope closed", thrown?.message)
    }

    private fun page(vararg people: EnterpriseDirectoryMember, cursor: String? = null): PeoplePage =
        PeoplePage(people.toList(), nextCursor = cursor)

    private fun person(id: String, online: Boolean = false) = EnterpriseDirectoryMember(
        deviceId = id,
        friendlyName = id.replaceFirstChar { it.uppercase() },
        department = "",
        role = "member",
        online = online,
    )

    /** The people listing, scripted page by page. [failure] and [throwable] replace the walk entirely. */
    private class FakePeopleApi(
        private val pages: Array<PeoplePage> = emptyArray(),
        private val failure: FlashError? = null,
        private val throwable: Throwable? = null,
    ) : DirectoryPrivacyApi {

        var calls: Int = 0
            private set

        override suspend fun fetchPeople(
            hubUrl: String,
            cursor: String?,
            limit: Int,
            q: String?,
            departmentId: String?,
        ): FlashResult<PeoplePage> {
            val index = calls++
            throwable?.let { throw it }
            failure?.let { return FlashResult.Failure(it) }
            return FlashResult.Success(pages[index.coerceAtMost(pages.lastIndex)])
        }

        override suspend fun lookupPerson(hubUrl: String, deviceId: String): FlashResult<EnterpriseDirectoryMember?> =
            FlashResult.Success(null)
    }

    private companion object {
        const val HUB = "https://hub.example.test"
    }
}
