@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HUBT-U40..: every state of `docs/ui/flash-hub-tab.md` maps to one exact string / layout variant. */
class FlashHubMathTest {

    private val now = 10_000_000_000L
    private val day = 86_400_000L

    private fun state(block: HubUiState.() -> HubUiState = { this }) = HubUiState(
        enrolled = true, loaded = true, orgName = "Acme", myDeviceId = "me", syncStatus = HubSyncStatus.OK,
        lastSyncedAtMs = now - 10_000, lease = OrgLease.Valid(now + 20 * day), peopleCount = 12,
    ).block()

    private fun person(id: String, name: String, block: HubPersonUi.() -> HubPersonUi = { this }) = HubPersonUi(
        deviceId = id, displayName = name, departmentId = "d1", departmentName = "Finance", role = HubPersonRole.PERSON,
        online = null, reachability = HubReachability.UNKNOWN, paired = false, orgVerified = false, isSelf = false,
    ).block()

    private fun ann(id: String, block: HubAnnouncementUi.() -> HubAnnouncementUi = { this }) = HubAnnouncementUi(
        id = id, scope = "org", title = "t", body = "b", authorName = "A", authorRole = "admin", createdAtMs = 1000, updatedAtMs = 1000,
        edited = false, pinned = false, seq = 1, isRead = false,
    ).block()

    // --- mode --------------------------------------------------------------------------------------------

    @Test fun modeNotEnrolledIsHidden() =
        assertEquals(FlashHubMode.HIDDEN, FlashHubMath.mode(state { copy(enrolled = false) }, HubLinkState.NOT_ENROLLED))

    @Test fun modeNotLoadedIsLoadingSkeleton() =
        assertEquals(FlashHubMode.LOADING, FlashHubMath.mode(state { copy(loaded = false) }, HubLinkState.ONLINE))

    @Test fun modeRevokedReplacesEverythingEvenWhenLoaded() =
        assertEquals(FlashHubMode.REVOKED, FlashHubMath.mode(state(), HubLinkState.REVOKED))

    @Test fun modeNeverSyncedOfflineNeedsNoPeopleAndNoConnection() {
        val s = state { copy(syncStatus = HubSyncStatus.NEVER, peopleCount = 0, lastSyncedAtMs = null) }
        assertEquals(FlashHubMode.NEVER_SYNCED_OFFLINE, FlashHubMath.mode(s, HubLinkState.OFFLINE))
        assertEquals(FlashHubMode.CONTENT, FlashHubMath.mode(s, HubLinkState.ONLINE), "online and about to sync is content, not the offline empty state")
    }

    @Test fun modeNeverSyncedWithCachedPeopleIsContent() =
        assertEquals(FlashHubMode.CONTENT, FlashHubMath.mode(state { copy(syncStatus = HubSyncStatus.NEVER) }, HubLinkState.OFFLINE))

    @Test fun modeCoversEveryLinkState() {
        for (link in HubLinkState.entries) {
            val mode = FlashHubMath.mode(state(), link)
            assertEquals(link == HubLinkState.REVOKED, mode == FlashHubMode.REVOKED, "$link")
        }
    }

    // --- strip -------------------------------------------------------------------------------------------

    @Test fun stripFresh() {
        val s = FlashHubMath.strip(state(), HubLinkState.ONLINE, now)!!
        assertEquals("Connected · updated just now", s.text)
        assertEquals(FlashHubStripTone.CALM, s.tone)
        assertEquals(FlashHubStripAction.NONE, s.action)
    }

    @Test fun stripCachedHubUnreachable() {
        val s = FlashHubMath.strip(
            state { copy(syncStatus = HubSyncStatus.FAILED_OFFLINE, lastSyncedAtMs = now - 3 * 3_600_000L) }, HubLinkState.OFFLINE, now,
        )!!
        assertEquals("Offline · showing information from 3 hours ago", s.text)
        assertEquals(FlashHubStripTone.INFO, s.tone)
        assertEquals(FlashHubStripAction.RETRY, s.action)
    }

    @Test fun stripOfflineWithoutAnyTimestamp() {
        val s = FlashHubMath.strip(state { copy(lastSyncedAtMs = null) }, HubLinkState.OFFLINE, now)!!
        assertEquals("Offline", s.text)
    }

    @Test fun stripLeaseExpiredIsWarningWithWhy() {
        val s = FlashHubMath.strip(state { copy(lease = OrgLease.Expired(now - day, ExpiredReason.TIME)) }, HubLinkState.ONLINE, now)!!
        assertEquals(FlashHubCopy.STRIP_LEASE_EXPIRED, s.text)
        assertEquals(FlashHubStripTone.WARNING, s.tone)
        assertEquals(FlashHubStripAction.WHY, s.action)
    }

    @Test fun stripLeaseExpiringSoonWarnsWithDays() {
        val s = FlashHubMath.strip(state { copy(lease = OrgLease.Valid(now + 2 * day)) }, HubLinkState.ONLINE, now)!!
        assertEquals("Connect to the company network within 2 days to keep chatting with colleagues offline.", s.text)
        assertEquals(FlashHubStripAction.WHY, s.action)
    }

    @Test fun stripClockWrongBeatsEverything() {
        val s = FlashHubMath.strip(
            state { copy(clockWrong = true, isRefreshing = true, lease = OrgLease.Expired(0, ExpiredReason.TIME)) }, HubLinkState.OFFLINE, now,
        )!!
        assertEquals("Your device clock looks wrong. Colleague trust may fail until it is fixed.", s.text)
        assertEquals(FlashHubStripTone.WARNING, s.tone)
    }

    @Test fun stripIdentityMismatchUsesPlanBSentence() {
        val s = FlashHubMath.strip(state { copy(identityMismatch = true) }, HubLinkState.ONLINE, now)!!
        assertEquals("Your device identity changed. Rejoin your organisation to chat with colleagues again.", s.text)
    }

    @Test fun stripRefreshingSaysUpdating() {
        assertEquals(FlashHubCopy.STRIP_UPDATING, FlashHubMath.strip(state { copy(isRefreshing = true) }, HubLinkState.ONLINE, now)!!.text)
    }

    @Test fun stripUnsupportedServer() {
        val s = FlashHubMath.strip(state { copy(syncStatus = HubSyncStatus.UNSUPPORTED) }, HubLinkState.ONLINE, now)!!
        assertEquals("This company server does not support offline colleague chat yet.", s.text)
    }

    @Test fun stripFailedOtherKeepsCacheAndOffersRetry() {
        val s = FlashHubMath.strip(state { copy(syncStatus = HubSyncStatus.FAILED_OTHER) }, HubLinkState.ONLINE, now)!!
        assertTrue(s.text.startsWith("Could not update"))
        assertEquals(FlashHubStripAction.RETRY, s.action)
    }

    @Test fun stripIsNullWhenRevokedHiddenOrNeverSynced() {
        assertNull(FlashHubMath.strip(state(), HubLinkState.REVOKED, now))
        assertNull(FlashHubMath.strip(state { copy(enrolled = false) }, HubLinkState.NOT_ENROLLED, now))
        assertNull(FlashHubMath.strip(state { copy(syncStatus = HubSyncStatus.NEVER, peopleCount = 0) }, HubLinkState.OFFLINE, now))
    }

    @Test fun stripNeverUsesColourAlone() {
        for (link in HubLinkState.entries) for (sync in HubSyncStatus.entries) {
            val s = FlashHubMath.strip(state { copy(syncStatus = sync) }, link, now) ?: continue
            assertTrue(s.text.isNotBlank(), "$link $sync")
        }
    }

    // --- relative time -----------------------------------------------------------------------------------

    @Test fun relativeTimeBuckets() {
        assertEquals("just now", FlashHubMath.relativeTime(now, now))
        assertEquals("just now", FlashHubMath.relativeTime(now + day, now), "clock jump forward")
        assertEquals("1 minute ago", FlashHubMath.relativeTime(now - 60_000, now))
        assertEquals("5 minutes ago", FlashHubMath.relativeTime(now - 300_000, now))
        assertEquals("1 hour ago", FlashHubMath.relativeTime(now - 3_600_000, now))
        assertEquals("yesterday", FlashHubMath.relativeTime(now - 30 * 3_600_000L, now))
        assertEquals("3 days ago", FlashHubMath.relativeTime(now - 3 * day, now))
    }

    // --- segments ----------------------------------------------------------------------------------------

    @Test fun defaultSegmentRules() {
        assertEquals(FlashHubSegment.NEWS, FlashHubMath.defaultSegment(2, FlashHubSegment.GROUPS))
        assertEquals(FlashHubSegment.GROUPS, FlashHubMath.defaultSegment(0, FlashHubSegment.GROUPS))
        assertEquals(FlashHubSegment.PEOPLE, FlashHubMath.defaultSegment(0, null))
    }

    @Test fun segmentFromNameToleratesGarbage() {
        assertEquals(FlashHubSegment.GROUPS, FlashHubMath.segmentFromName(" groups "))
        assertNull(FlashHubMath.segmentFromName("announcements"))
        assertNull(FlashHubMath.segmentFromName(null))
        assertNull(FlashHubMath.segmentFromName(""))
    }

    @Test fun reselectScrollsThenJumpsToNews() {
        assertEquals(FlashHubMath.ReselectResult.SCROLL_TOP, FlashHubMath.reselect(false, FlashHubSegment.PEOPLE))
        assertEquals(FlashHubMath.ReselectResult.JUMP_TO_NEWS, FlashHubMath.reselect(true, FlashHubSegment.PEOPLE))
        assertEquals(FlashHubMath.ReselectResult.SCROLL_TOP, FlashHubMath.reselect(true, FlashHubSegment.NEWS))
    }

    @Test fun everySegmentHasALabel() {
        FlashHubSegment.entries.forEach { assertTrue(FlashHubMath.segmentLabel(it).isNotBlank()) }
    }

    // --- people ------------------------------------------------------------------------------------------

    @Test fun lettersFoldAccentsAndGroupTheRestUnderHash() {
        assertEquals("E", FlashHubMath.letterFor("élodie"))
        assertEquals("A", FlashHubMath.letterFor("  Ada"))
        assertEquals("#", FlashHubMath.letterFor("😀 Smile"))
        assertEquals("#", FlashHubMath.letterFor(""))
        assertEquals("#", FlashHubMath.letterFor("محمد"))
        assertEquals("#", FlashHubMath.letterFor("42nd"))
    }

    @Test fun peopleItemsInsertOneHeaderPerLetter() {
        val items = FlashHubMath.peopleItems(listOf(person("1", "Ada"), person("2", "Abe"), person("3", "Bo")), FlashHubPeopleFilter.All)
        assertEquals(5, items.size)
        assertEquals(listOf("h:A", "p:1", "p:2", "h:B", "p:3"), items.map { it.key })
    }

    @Test fun peopleItemKeysAreUnique() {
        val people = (0 until 500).map { person("id$it", "Name${it % 7}") }
        val keys = FlashHubMath.peopleItems(people, FlashHubPeopleFilter.All).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun peopleItemsEmptyListAndNoHeaders() {
        assertTrue(FlashHubMath.peopleItems(emptyList(), FlashHubPeopleFilter.All).isEmpty())
        assertEquals(1, FlashHubMath.peopleItems(listOf(person("1", "Ada")), FlashHubPeopleFilter.All, withHeaders = false).size)
    }

    @Test fun departmentHiddenOnRowsWhenListIsFilteredToIt() {
        val all = FlashHubMath.peopleItems(listOf(person("1", "Ada")), FlashHubPeopleFilter.All).filterIsInstance<FlashHubPeopleItem.Person>()
        val mine = FlashHubMath.peopleItems(listOf(person("1", "Ada")), FlashHubPeopleFilter.Mine).filterIsInstance<FlashHubPeopleItem.Person>()
        assertTrue(all.single().showDepartment)
        assertFalse(mine.single().showDepartment)
    }

    @Test fun filterDepartmentIdMapping() {
        assertNull(FlashHubMath.filterDepartmentId(FlashHubPeopleFilter.All, "d1"))
        assertEquals("d1", FlashHubMath.filterDepartmentId(FlashHubPeopleFilter.Mine, "d1"))
        assertNull(FlashHubMath.filterDepartmentId(FlashHubPeopleFilter.Mine, null))
        assertEquals("x", FlashHubMath.filterDepartmentId(FlashHubPeopleFilter.Department("x"), "d1"))
    }

    @Test fun searchNoMatchShowsTheQuery() {
        assertEquals("No one named “zed”", FlashHubMath.peopleEmptyText(" zed ", FlashHubPeopleFilter.All))
        assertEquals(FlashHubCopy.NO_PEOPLE, FlashHubMath.peopleEmptyText("", FlashHubPeopleFilter.All))
        assertEquals(FlashHubCopy.NO_PEOPLE_IN_DEPARTMENT, FlashHubMath.peopleEmptyText("", FlashHubPeopleFilter.Mine))
    }

    @Test fun personAccessibilitySentence() {
        val p = person("1", "Ada Lovelace") { copy(role = HubPersonRole.HEAD, online = true, paired = true) }
        assertEquals("Ada Lovelace, Finance, Head, Paired, Online", FlashHubMath.personAccessibility(p))
        assertEquals("Ada Lovelace, Head, Paired, Online", FlashHubMath.personAccessibility(p, includeDepartment = false))
    }

    @Test fun withheldPresenceHasNoWordAndNoDot() {
        assertNull(FlashHubMath.onlineWord(null))
        assertEquals("Offline", FlashHubMath.onlineWord(false))
        assertFalse(FlashHubMath.personAccessibility(person("1", "Ada")).contains("Offline"))
    }

    @Test fun initialsHandleEmojiMultiWordAndEmpty() {
        assertEquals("AL", FlashHubMath.initials("Ada Byron Lovelace"))
        assertEquals("A", FlashHubMath.initials("Ada"))
        assertEquals("?", FlashHubMath.initials("   "))
        assertEquals("😀", FlashHubMath.initials("😀"))
    }

    @Test fun roleBadgeNeverColourOnly() {
        assertNull(FlashHubMath.roleBadge(HubPersonRole.PERSON))
        assertEquals("Head", FlashHubMath.roleBadge(HubPersonRole.HEAD))
        assertEquals("Admin", FlashHubMath.roleBadge(HubPersonRole.ADMIN))
    }

    @Test fun reachabilityLinesAreTheDesignSentences() {
        assertEquals("On your Wi-Fi now", FlashHubMath.reachabilityLine(HubReachability.LAN))
        assertEquals("Reachable through the company server", FlashHubMath.reachabilityLine(HubReachability.HUB))
        assertEquals("Offline", FlashHubMath.reachabilityLine(HubReachability.OFFLINE))
        HubReachability.entries.forEach { assertTrue(FlashHubMath.reachabilityLine(it).isNotBlank()) }
    }

    // --- person sheet, message, call, pair ---------------------------------------------------------------

    @Test fun sheetForSelfDisablesMessageAndHidesCallAndPair() {
        val s = FlashHubMath.personSheet(person("me", "Me") { copy(isSelf = true, reachability = HubReachability.LAN) }, callAllowed = true, pairAvailable = true)
        assertFalse(s.messageEnabled)
        assertFalse(s.showCall)
        assertFalse(s.showPair)
        assertNull(s.footer)
        assertEquals("That is you", s.messageLabel)
    }

    @Test fun callOnlyOnLanAndOnlyWhenGateAllows() {
        fun call(r: HubReachability, allowed: Boolean) =
            FlashHubMath.personSheet(person("1", "A") { copy(reachability = r) }, allowed, false).showCall
        assertTrue(call(HubReachability.LAN, true))
        assertFalse(call(HubReachability.LAN, false))
        assertFalse(call(HubReachability.HUB, true))
        assertFalse(call(HubReachability.OFFLINE, true))
        assertFalse(call(HubReachability.UNKNOWN, true))
    }

    @Test fun pairHookShownOnlyWhenWiredAndNotPaired() {
        fun pair(paired: Boolean, wired: Boolean) = FlashHubMath.personSheet(person("1", "A") { copy(paired = paired) }, false, wired).showPair
        assertTrue(pair(false, true))
        assertFalse(pair(false, false))
        assertFalse(pair(true, true))
    }

    @Test fun unpairedFooterAndHonestyLine() {
        val unpaired = FlashHubMath.personSheet(person("1", "A") { copy(orgVerified = true) }, false, false)
        assertNotNull(unpaired.footer)
        assertEquals(FlashHubCopy.PERSON_HONESTY, unpaired.honesty)
        val paired = FlashHubMath.personSheet(person("1", "A") { copy(paired = true) }, false, false)
        assertNull(paired.footer)
        assertTrue(paired.pairedBadge)
    }

    @Test fun sheetShowsIdSuffixAndDepartmentFallback() {
        val s = FlashHubMath.personSheet(person("device-abcd1234") { copy(departmentName = "") }.let { it }, false, false)
        assertEquals("ID ending 1234", s.idLine)
        assertEquals(FlashHubCopy.PERSON_DEPARTMENT_UNKNOWN, s.departmentLine)
    }

    private fun person(id: String) = person(id, "N")
    private fun person(id: String, block: HubPersonUi.() -> HubPersonUi) = person(id, "N", block)

    @Test fun messageOutcomeOpensConversationOnSuccess() {
        val p = person("dev1", "A")
        assertEquals(FlashHubMessageOutcome.Open("dev1"), FlashHubMath.messageOutcome(p, FlashResult.Success("dev1")))
    }

    @Test fun messageOutcomePairedColleagueOpensExistingWithoutCallingTheRepository() {
        val p = person("dev1", "A") { copy(paired = true) }
        assertEquals(FlashHubMessageOutcome.Open("dev1"), FlashHubMath.messageOutcome(p, null))
    }

    @Test fun messageOutcomeShowsPlanBSentencesUnchanged() {
        val p = person("dev1", "A")
        listOf(
            "That is you.",
            "This person is not in your company directory.",
            "This person is no longer part of your organisation.",
            "Reconnect to your company network to start a chat with this colleague.",
            "Connect to the company network once to load your colleagues.",
        ).forEach { sentence ->
            val outcome = FlashHubMath.messageOutcome(p, FlashResult.Failure(FlashError.Unknown(sentence)))
            assertEquals(FlashHubMessageOutcome.Show(sentence), outcome)
        }
    }

    @Test fun messageOutcomeHidesTechnicalErrors() {
        val p = person("dev1", "A")
        val outcome = FlashHubMath.messageOutcome(p, FlashResult.Failure(FlashError.Unknown("java.lang.NullPointerException at x")))
        assertEquals(FlashHubMessageOutcome.Show(FlashHubCopy.MESSAGE_FAILED), outcome)
    }

    @Test fun messageOutcomeNetworkErrorIsAPlainSentence() {
        val p = person("dev1", "A")
        val outcome = FlashHubMath.messageOutcome(p, FlashResult.Failure(FlashError.NetworkUnavailable("x"))) as FlashHubMessageOutcome.Show
        assertTrue(outcome.text.startsWith("Could not reach your organisation"))
    }

    @Test fun messageToSelfNeverReachesTheRepository() {
        val outcome = FlashHubMath.messageOutcome(person("me", "Me") { copy(isSelf = true) }, FlashResult.Success("me"))
        assertEquals(FlashHubMessageOutcome.Show("That is you."), outcome)
    }

    // --- departments -------------------------------------------------------------------------------------

    private fun dept(id: String, name: String, mine: Boolean = false, heads: List<String> = emptyList(), members: Int = 3, groups: Int = 1) =
        HubDepartmentUi(id, name, members, groups, mine, heads.map { "id-$it" }, heads, hidden = false)

    @Test fun departmentOrderMineFirstThenFoldedName() {
        val ordered = FlashHubMath.orderDepartments(listOf(dept("b", "Zeta"), dept("a", "émile"), dept("c", "Mine", mine = true)))
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test fun departmentSummaryPluralisation() {
        assertEquals("1 person · 1 group", FlashHubMath.departmentSummary(dept("a", "A", members = 1, groups = 1)))
        assertEquals("0 people · 0 groups", FlashHubMath.departmentSummary(dept("a", "A", members = 0, groups = 0)))
    }

    @Test fun headsLine() {
        assertEquals(FlashHubCopy.DEPT_NO_HEADS, FlashHubMath.headsLine(dept("a", "A")))
        assertEquals("Ann, Bo", FlashHubMath.headsLine(dept("a", "A", heads = listOf("Ann", "Bo"))))
    }

    @Test fun hiddenDepartmentLookupNeverRevealsExistence() {
        assertNull(FlashHubMath.visibleDepartment(listOf(dept("a", "A")), "secret"))
        assertEquals("This department is not visible to you", FlashHubCopy.DEPARTMENT_HIDDEN)
    }

    // --- groups ------------------------------------------------------------------------------------------

    private fun group(id: String, dept: String, m: HubGroupMembership = HubGroupMembership.NOT_JOINED, approval: Boolean = false) =
        HubGroupUi(id, "G$id", dept, 4, 1, approval, m, null)

    @Test fun groupSectionsYoursFirstThenDepartmentsThenOther() {
        val sections = FlashHubMath.groupSections(
            listOf(
                group("1", "Sales"), group("2", "Finance", HubGroupMembership.JOINED), group("3", ""), group("4", "Admin", HubGroupMembership.PENDING),
            ),
        )
        assertEquals(listOf("Yours", "Sales", "Other"), sections.map { it.title })
        assertEquals(listOf("2", "4"), sections.first().groups.map { it.groupId })
    }

    @Test fun groupSectionsEmpty() = assertTrue(FlashHubMath.groupSections(emptyList()).isEmpty())

    @Test fun groupButtonLabelsReuseJoinSemantics() {
        assertEquals("Open", FlashHubMath.groupButtonLabel(group("1", "A", HubGroupMembership.JOINED)))
        assertEquals("Waiting for approval", FlashHubMath.groupButtonLabel(group("1", "A", HubGroupMembership.PENDING)))
        assertEquals("Ask to join", FlashHubMath.groupButtonLabel(group("1", "A", approval = true)))
        assertEquals("Join", FlashHubMath.groupButtonLabel(group("1", "A")))
        assertFalse(FlashHubMath.groupButtonEnabled(group("1", "A", HubGroupMembership.PENDING), busy = false))
        assertFalse(FlashHubMath.groupButtonEnabled(group("1", "A"), busy = true))
        assertTrue(FlashHubMath.groupButtonEnabled(group("1", "A"), busy = false))
    }

    @Test fun groupMetaLine() {
        assertEquals("4 members · 1 online · Needs approval", FlashHubMath.groupMeta(group("1", "A", approval = true)))
        assertEquals("1 member", FlashHubMath.groupMeta(HubGroupUi("g", "G", "", 1, null, false, HubGroupMembership.NOT_JOINED, null)))
        assertTrue(FlashHubMath.groupMeta(HubGroupUi("g", "G", "", 2, 0, false, HubGroupMembership.JOINED, 3)).endsWith("3 requests waiting"))
    }

    // --- news --------------------------------------------------------------------------------------------

    @Test fun newsOrderPinnedFirstThenNewestAndDropsExpired() {
        val list = listOf(
            ann("old") { copy(createdAtMs = 1) }, ann("new") { copy(createdAtMs = 9) }, ann("pin") { copy(pinned = true, createdAtMs = 2) },
            ann("gone") { copy(expiresAtMs = now - 1) }, ann("later") { copy(expiresAtMs = now + 1, createdAtMs = 5) },
        )
        assertEquals(listOf("pin", "new", "later", "old"), FlashHubMath.orderNews(list, now).map { it.id })
    }

    @Test fun newsOrderStableOnTies() {
        val ordered = FlashHubMath.orderNews(listOf(ann("b"), ann("a")), now)
        assertEquals(listOf("a", "b"), ordered.map { it.id })
    }

    @Test fun unreadCountIgnoresReadAndExpired() {
        val list = listOf(ann("1"), ann("2") { copy(isRead = true) }, ann("3") { copy(expiresAtMs = 1) })
        assertEquals(1, FlashHubMath.unreadCount(list, now))
    }

    @Test fun readMarkingNeedsSixtyPercentForOneSecond() {
        assertTrue(FlashHubMath.shouldMarkRead(false, 0.6f, 1000))
        assertFalse(FlashHubMath.shouldMarkRead(false, 0.59f, 5000))
        assertFalse(FlashHubMath.shouldMarkRead(false, 1f, 999))
        assertFalse(FlashHubMath.shouldMarkRead(true, 1f, 5000))
    }

    @Test fun scopeLabels() {
        assertEquals("Whole company", FlashHubMath.scopeLabel(ann("1")) { null })
        assertEquals("Finance", FlashHubMath.scopeLabel(ann("1") { copy(scope = "department", departmentId = "d1") }) { "Finance" })
        assertEquals("Department", FlashHubMath.scopeLabel(ann("1") { copy(scope = "department", departmentId = "zz") }) { null })
    }

    @Test fun authorRoleChipAcceptsBothVocabularies() {
        assertEquals("Admin", FlashHubMath.authorRoleChip("ORG_ADMIN"))
        assertEquals("Admin", FlashHubMath.authorRoleChip("admin"))
        assertEquals("Department head", FlashHubMath.authorRoleChip("DEPT_HEAD"))
        assertEquals("Department head", FlashHubMath.authorRoleChip("dept_admin"))
        assertNull(FlashHubMath.authorRoleChip("member"))
    }

    @Test fun rightsTable() {
        val org = ann("1")
        val dept = ann("2") { copy(scope = "department", departmentId = "d1") }
        val other = ann("3") { copy(scope = "department", departmentId = "d2") }
        assertFalse(FlashHubMath.rights(org, HubPostingRights.None).any)
        assertTrue(FlashHubMath.rights(org, HubPostingRights.OrgWide()).canDelete)
        val head = HubPostingRights.Department(listOf("d1"))
        assertFalse(FlashHubMath.rights(org, head).any)
        assertTrue(FlashHubMath.rights(dept, head).canEdit)
        assertFalse(FlashHubMath.rights(other, head).any)
        assertFalse(FlashHubMath.canPost(HubPostingRights.None))
        assertFalse(FlashHubMath.canPost(HubPostingRights.Department(emptyList())))
        assertTrue(FlashHubMath.canPost(head))
    }

    @Test fun audiences() {
        val depts = listOf(dept("d1", "Finance"), dept("d2", "Sales"))
        assertEquals(3, FlashHubMath.audiences(HubPostingRights.OrgWide(), depts).size)
        assertEquals("org", FlashHubMath.audiences(HubPostingRights.OrgWide(), depts).first().scope)
        assertEquals(listOf("Finance"), FlashHubMath.audiences(HubPostingRights.Department(listOf("d1")), depts).map { it.label })
        assertTrue(FlashHubMath.audiences(HubPostingRights.None, depts).isEmpty())
        assertEquals("zz", FlashHubMath.audiences(HubPostingRights.Department(listOf("zz")), depts).single().label)
    }

    @Test fun pinLimitPreCheck() {
        val pins = (1..3).map { ann("p$it") { copy(pinned = true) } }
        assertTrue(FlashHubMath.pinWouldExceedLimit(pins + ann("x"), ann("x")))
        assertFalse(FlashHubMath.pinWouldExceedLimit(pins.take(2) + ann("x"), ann("x")))
        assertFalse(FlashHubMath.pinWouldExceedLimit(pins, pins.first()), "unpinning is always allowed")
    }

    // --- composer ----------------------------------------------------------------------------------------

    @Test fun codePointsCountEmojiAsOne() {
        assertEquals(3, FlashHubMath.codePoints("a😀b"))
        assertEquals(0, FlashHubMath.codePoints(""))
        assertEquals(1, FlashHubMath.codePoints("\uD83D"), "lone surrogate counts once, never crashes")
    }

    @Test fun counterTones() {
        assertEquals(FlashHubMath.CounterTone.NORMAL, FlashHubMath.counterTone(3599, 4000))
        assertEquals(FlashHubMath.CounterTone.WARNING, FlashHubMath.counterTone(3600, 4000))
        assertEquals(FlashHubMath.CounterTone.WARNING, FlashHubMath.counterTone(4000, 4000))
        assertEquals(FlashHubMath.CounterTone.ERROR, FlashHubMath.counterTone(4001, 4000))
    }

    @Test fun canSendRules() {
        assertTrue(FlashHubMath.canSend("T", "B", false))
        assertFalse(FlashHubMath.canSend(" ", "B", false))
        assertFalse(FlashHubMath.canSend("T", "\n ", false))
        assertFalse(FlashHubMath.canSend("T", "B", true), "double tap while sending")
        assertTrue(FlashHubMath.canSend("a".repeat(120), "b".repeat(4000), false))
        assertFalse(FlashHubMath.canSend("a".repeat(121), "B", false))
        assertFalse(FlashHubMath.canSend("T", "b".repeat(4001), false))
    }

    @Test fun composerFailureSentences() {
        fun f(e: FlashError) = FlashHubMath.composerFailure(e)
        assertEquals(FlashHubCopy.ANN_OFFLINE, f(FlashError.NetworkUnavailable("x")).text)
        assertEquals("This announcement is too long. Shorten it to 4000 characters.", f(FlashError.Domain("TOO_LONG", "", 4000)).text)
        assertEquals("You are posting too fast. Try again in 7 seconds.", f(FlashError.Domain("RATE_LIMITED", "", 7)).text)
        assertEquals(7, f(FlashError.Domain("RATE_LIMITED", "", 7)).retryAfterSec)
        assertEquals(FlashHubCopy.ANN_POST_FAILED, f(FlashError.Unknown("boom")).text)
        assertEquals(FlashHubCopy.ANN_POST_FAILED, f(FlashError.Domain("SERVER", "stack trace")).text)
        assertEquals(FlashHubCopy.ANN_PIN_LIMIT, f(FlashError.Domain("PIN_LIMIT", "")).text)
        assertEquals(FlashHubCopy.ANN_CONFLICT, f(FlashError.Domain("CONFLICT", "")).text)
        assertEquals(FlashHubCopy.ANN_REMOVED, f(FlashError.Domain("REMOVED", "")).text)
        assertFalse(f(FlashError.Domain("NOT_ALLOWED", "")).retryable)
        assertTrue(f(FlashError.Unknown("boom")).keepDraft)
    }

    @Test fun rateLimitedZeroSecondsIsClampedToOne() {
        assertEquals(1, FlashHubMath.composerFailure(FlashError.Domain("RATE_LIMITED", "", 0)).retryAfterSec)
    }

    // --- links -------------------------------------------------------------------------------------------

    @Test fun onlyHttpAndHttpsAreOpenable() {
        assertTrue(FlashHubMath.isOpenableLink("https://example.com/a?b=1#c"))
        assertTrue(FlashHubMath.isOpenableLink("HTTP://example.com"))
        listOf(
            "intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)", "file:///etc/passwd", "ftp://x.y", "https://", "http:// x.com",
            "content://media/1", "market://details?id=a", "https:///path", "//example.com",
        ).forEach { assertFalse(FlashHubMath.isOpenableLink(it), it) }
    }

    @Test fun parseLinksSplitsAndStripsTrailingPunctuation() {
        val parts = FlashHubMath.parseLinks("See https://example.com/a, then (http://b.org/x).")
        assertEquals(
            listOf(
                FlashHubMath.TextPart.Plain("See "), FlashHubMath.TextPart.Link("https://example.com/a", "https://example.com/a"),
                FlashHubMath.TextPart.Plain(", then ("), FlashHubMath.TextPart.Link("http://b.org/x", "http://b.org/x"), FlashHubMath.TextPart.Plain(")."),
            ),
            parts,
        )
    }

    @Test fun parseLinksNeverMakesDangerousSchemesTappable() {
        val text = "javascript:alert(1) intent://x file:///a data:text/html,hi xhttps://nope.com https://"
        assertTrue(FlashHubMath.parseLinks(text).none { it is FlashHubMath.TextPart.Link })
        assertEquals(text, FlashHubMath.parseLinks(text).joinToString("") { (it as FlashHubMath.TextPart.Plain).text })
    }

    @Test fun parseLinksRoundTripsAnyInput() {
        val inputs = listOf("", "plain", "https://a.b", "x https://a.b y https://c.d", "😀 https://a.b/😀", "https://a.b\nhttps://c.d")
        for (s in inputs) {
            val rebuilt = FlashHubMath.parseLinks(s).joinToString("") {
                when (it) { is FlashHubMath.TextPart.Plain -> it.text; is FlashHubMath.TextPart.Link -> it.text }
            }
            assertEquals(s, rebuilt)
        }
    }

    @Test fun linkConfirmationOnlyForForeignHosts() {
        assertFalse(FlashHubMath.linkNeedsConfirmation("https://hub.acme.example/a", "hub.acme.example"))
        assertFalse(FlashHubMath.linkNeedsConfirmation("https://HUB.acme.example:8443/a", "hub.acme.example"))
        assertTrue(FlashHubMath.linkNeedsConfirmation("https://evil.example/a", "hub.acme.example"))
        assertTrue(FlashHubMath.linkNeedsConfirmation("https://hub.acme.example.evil.com/", "hub.acme.example"))
        assertTrue(FlashHubMath.linkNeedsConfirmation("https://user@evil.example/", "hub.acme.example"))
        assertTrue(FlashHubMath.linkNeedsConfirmation("https://a.b/", null))
    }

    @Test fun linkHostExtraction() {
        assertEquals("example.com", FlashHubMath.linkHost("https://Example.com:8080/x"))
        assertEquals("example.com", FlashHubMath.linkHost("https://u:p@example.com/x"))
        assertNull(FlashHubMath.linkHost("mailto:a@b.c"))
    }

    @Test fun linkConfirmSentence() =
        assertEquals("Open this link in your browser? https://a.b", FlashHubCopy.annLinkConfirm("https://a.b"))

    // --- routes ------------------------------------------------------------------------------------------

    @Test fun routesRoundTrip() {
        assertEquals(FlashHubMath.Route.Announcement("a-1"), FlashHubMath.parseRoute(FlashHubMath.routeAnnouncement("a-1")))
        assertEquals(FlashHubMath.Route.Person("dev_9:x"), FlashHubMath.parseRoute(FlashHubMath.routePerson("dev_9:x")))
    }

    @Test fun routesRejectHostileInput() {
        listOf(
            null, "", "hub", "hub/announcement/", "hub/person/", "hub/announcement/../../x", "hub/announcement/a b", "hub/person/a/b",
            "hub/other/1", "HUB/announcement/1", "hub/announcement/" + "x".repeat(129), "hub/person/\u0000", "hub/person/😀",
        ).forEach { assertNull(FlashHubMath.parseRoute(it), it.toString()) }
    }

    // --- tab badge ---------------------------------------------------------------------------------------

    @Test fun tabBadgeDescription() {
        assertEquals("Hub", FlashHubCopy.tabBadgeDescription(0))
        assertEquals("Hub, 1 unread announcement", FlashHubCopy.tabBadgeDescription(1))
        assertEquals("Hub, 3 unread announcements", FlashHubCopy.tabBadgeDescription(3))
    }

    // --- overview card -----------------------------------------------------------------------------------

    @Test fun overviewRoleLineNamesTheRoleAndTheDepartment() {
        assertEquals("Member · Software", FlashHubMath.overviewRoleLine(HubPersonRole.PERSON, "Software"))
        assertEquals("Member", FlashHubMath.overviewRoleLine(HubPersonRole.PERSON, null))
        assertEquals("Member", FlashHubMath.overviewRoleLine(HubPersonRole.PERSON, "  "))
        assertEquals("Department head · Finance", FlashHubMath.overviewRoleLine(HubPersonRole.HEAD, "Finance"))
        assertEquals("Administrator", FlashHubMath.overviewRoleLine(HubPersonRole.ADMIN, "Finance"))
    }

    @Test fun overviewSummaryPluralisesAndSkipsEmptyParts() {
        assertEquals("1 colleague · 1 department", FlashHubMath.overviewSummary(1, 1, 0))
        assertEquals("42 colleagues · 5 departments · 3 groups", FlashHubMath.overviewSummary(42, 5, 3))
        assertEquals("", FlashHubMath.overviewSummary(0, 0, 0))
    }

    // --- scale -------------------------------------------------------------------------------------------

    @Test fun fiveThousandPeopleBuildOneHeaderPerLetterInOnePass() {
        val people = (0 until 5000).map { person("id$it", "${'A' + (it % 26)}name$it") }.sortedBy { it.displayName }
        val items = FlashHubMath.peopleItems(people, FlashHubPeopleFilter.All)
        assertEquals(5000 + 26, items.size)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    // Verifier report-5 DEFECT-018/019: screen-reader strings come from FlashHubCopy, not inline literals.
    @Test fun accessibilityStringsComeFromTheCopyObject() {
        assertEquals("Reachability: On the same network", FlashHubCopy.reachabilityAccessibility("On the same network"))
        assertEquals("On", FlashHubCopy.SWITCH_ON)
        assertEquals("Off", FlashHubCopy.SWITCH_OFF)
    }
}
