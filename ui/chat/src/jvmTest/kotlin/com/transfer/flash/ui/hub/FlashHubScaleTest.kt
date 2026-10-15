@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HUBT-U95: SYNTHETIC scale numbers for the Hub view maths with 5,000 people and 1,000 announcements. This measures the pure
 * presentation code on the build machine's JVM, not a phone and not the screen; it only proves nothing in this layer is
 * super-linear. The numbers are printed ("HUBT-SCALE ...") and copied into the experiments log as synthetic.
 */
class FlashHubScaleTest {

    private val people: List<HubPersonUi> = (0 until 5_000).map { i ->
        HubPersonUi(
            deviceId = "device-%05d".format(i),
            displayName = "${listOf("Amara", "Bjorn", "Chen", "Dalia", "Émile", "Farah", "Gita")[i % 7]} Person$i",
            departmentId = "d${i % 40}",
            departmentName = "Department ${i % 40}",
            role = if (i % 97 == 0) HubPersonRole.HEAD else HubPersonRole.PERSON,
            online = if (i % 3 == 0) true else if (i % 3 == 1) false else null,
            reachability = HubReachability.entries[i % HubReachability.entries.size],
            paired = i % 11 == 0,
            orgVerified = i % 2 == 0,
            isSelf = i == 0,
        )
    }.sortedBy { it.displayName }

    private val news: List<HubAnnouncementUi> = (0 until 1_000).map { i ->
        HubAnnouncementUi("a$i", "org", null, "Title $i about Finance", "Body $i ".repeat(40), "Author $i", "admin", i.toLong(), i.toLong(), i % 5 == 0, i % 2 == 0, null, 1, false)
    }

    private inline fun medianMs(runs: Int = 15, block: () -> Unit): Double {
        repeat(5) { block() }
        val times = (0 until runs).map {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        return times[times.size / 2]
    }

    @Test fun peopleListBuildsLinearly() {
        val items = FlashHubMath.peopleItems(people, FlashHubPeopleFilter.All)
        assertTrue(items.size >= people.size)
        assertEquals(people.size, items.count { it is FlashHubPeopleItem.Person })
        val ms = medianMs { FlashHubMath.peopleItems(people, FlashHubPeopleFilter.All) }
        println("HUBT-SCALE peopleItems(5000) median=%.2f ms headers=%d".format(ms, items.count { it is FlashHubPeopleItem.Header }))
        assertTrue(ms < 250.0, "peopleItems took $ms ms")
    }

    @Test fun spokenRowsForEveryoneAreCheap() {
        val ms = medianMs { people.forEach { FlashHubMath.personAccessibility(it) } }
        println("HUBT-SCALE personAccessibility(5000) median=%.2f ms".format(ms))
        assertTrue(ms < 250.0, "personAccessibility took $ms ms")
    }

    @Test fun twiceThePeopleIsRoughlyTwiceTheWork() {
        val double = people + people.map { it.copy(deviceId = it.deviceId + "x") }
        val one = medianMs(30) { FlashHubMath.peopleItems(people, FlashHubPeopleFilter.All) }.coerceAtLeast(0.05)
        val two = medianMs(30) { FlashHubMath.peopleItems(double, FlashHubPeopleFilter.All) }
        println("HUBT-SCALE peopleItems 5000 -> 10000 ratio=%.2f".format(two / one))
        assertTrue(two / one < 8.0, "ratio ${two / one} suggests worse than linear")
    }

    @Test fun newsSearchOverAThousandItems() {
        val hits = FlashHubMath.matchNews(news, "finance")
        assertEquals(1_000, hits.size)
        val ms = medianMs { FlashHubMath.matchNews(news, "title 99") }
        println("HUBT-SCALE matchNews(1000) median=%.2f ms".format(ms))
        assertTrue(ms < 250.0, "matchNews took $ms ms")
    }

    @Test fun newsOrderingAndUnreadCountOverAThousandItems() {
        val ms = medianMs { FlashHubMath.orderNews(news, 10_000L); FlashHubMath.unreadCount(news, 10_000L) }
        println("HUBT-SCALE orderNews+unreadCount(1000) median=%.2f ms".format(ms))
        assertTrue(ms < 250.0, "ordering took $ms ms")
    }
}
