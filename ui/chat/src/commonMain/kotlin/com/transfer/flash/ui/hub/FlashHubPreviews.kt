@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubSampleData
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// One preview per state of docs/ui/flash-hub-tab.md "States" plus the main pieces. Fixed clock so the text is stable.

private const val NOW = 1_800_000_100_000L

private fun news(): List<HubAnnouncementUi> = listOf(
    HubAnnouncementUi("a1", "org", null, "Office closed on Friday", "The office is closed on Friday for the annual maintenance. Work from home.\nDetails: https://hub.acme.example/notice", "Dana Kruger", "admin", NOW - 3_600_000L, NOW - 3_600_000L, false, true, null, 3L, false),
    HubAnnouncementUi("a2", "department", "dept-eng", "Release freeze", "No merges after 17:00 on Thursday.", "Amara Okafor", "head", NOW - 86_400_000L, NOW - 80_000_000L, true, false, null, 2L, true),
)

private fun actions(): FlashHubActions = FlashHubActions(
    onRefresh = {}, onSignOut = {}, onMarkRead = {}, onOpenConversation = {},
    startConversation = { FlashResult.Success(it) },
    postAnnouncement = { FlashResult.Success(news().first()) },
    editAnnouncement = { _, _, _ -> FlashResult.Success(news().first()) },
    deleteAnnouncement = { FlashResult.Success(Unit) },
    pinAnnouncement = { _, _ -> FlashResult.Success(Unit) },
    onOpenLink = {}, groupAction = { FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("preview")) },
    onOpenGroup = {}, onRetryGroups = {}, onDismissTip = {}, onRouteConsumed = {},
    onCall = { _, _ -> }, callAllowed = { true }, onPairColleague = null,
)

private fun data(
    state: HubUiState = HubSampleData.state,
    link: HubLinkState = HubLinkState.ONLINE,
    withNews: Boolean = true,
    posting: HubPostingRights = HubPostingRights.None,
): FlashHubScreenData = FlashHubScreenData(
    state = state, link = link, nowMs = NOW, news = if (withNews) news() else emptyList(), posting = posting,
    people = HubSampleData.people, departments = HubSampleData.departments, groups = HubSampleData.groups,
)

@Composable
private fun Host(
    data: FlashHubScreenData,
    selection: FlashHubSelection = FlashHubSelection(segment = FlashHubSegment.PEOPLE),
) {
    FlashTheme { FlashHubScreen(data = data, selection = selection, onSelection = {}, actions = actions()) }
}

@Preview(name = "Hub - loading first time", showBackground = true)
@Composable
private fun HubLoadingPreview() = Host(data(state = HubUiState(enrolled = true, loaded = false)))

@Preview(name = "Hub - loaded fresh (people)", showBackground = true)
@Composable
private fun HubFreshPreview() = Host(data())

@Preview(name = "Hub - news", showBackground = true)
@Composable
private fun HubNewsPreview() = Host(data(posting = HubPostingRights.OrgWide()), FlashHubSelection(segment = FlashHubSegment.NEWS))

@Preview(name = "Hub - news empty", showBackground = true)
@Composable
private fun HubNewsEmptyPreview() = Host(data(withNews = false), FlashHubSelection(segment = FlashHubSegment.NEWS))

@Preview(name = "Hub - news unsupported by the hub", showBackground = true)
@Composable
private fun HubNewsUnsupportedPreview() =
    Host(data().copy(newsUnsupported = true, news = emptyList()), FlashHubSelection(segment = FlashHubSegment.NEWS))

@Preview(name = "Hub - departments", showBackground = true)
@Composable
private fun HubDepartmentsPreview() = Host(data(), FlashHubSelection(segment = FlashHubSegment.DEPARTMENTS))

@Preview(name = "Hub - groups", showBackground = true)
@Composable
private fun HubGroupsPreview() = Host(data(), FlashHubSelection(segment = FlashHubSegment.GROUPS))

@Preview(name = "Hub - groups failed to load", showBackground = true)
@Composable
private fun HubGroupsFailedPreview() =
    Host(data().copy(groups = emptyList(), groupsLoadError = FlashHubCopy.GROUPS_NOT_LOADED_BODY), FlashHubSelection(segment = FlashHubSegment.GROUPS))

@Preview(name = "Hub - cached, hub unreachable", showBackground = true)
@Composable
private fun HubOfflinePreview() =
    Host(data(state = HubSampleData.state.copy(syncStatus = HubSyncStatus.FAILED_OFFLINE), link = HubLinkState.OFFLINE))

@Preview(name = "Hub - lease expired", showBackground = true)
@Composable
private fun HubLeaseExpiredPreview() =
    Host(data(state = HubSampleData.state.copy(lease = OrgLease.Expired(NOW - 1_000L, ExpiredReason.TIME))))

@Preview(name = "Hub - never synced and offline", showBackground = true)
@Composable
private fun HubNeverSyncedPreview() = Host(
    data(state = HubUiState(enrolled = true, loaded = true, orgName = "Acme Corp", syncStatus = HubSyncStatus.NEVER), link = HubLinkState.OFFLINE),
)

@Preview(name = "Hub - revoked", showBackground = true)
@Composable
private fun HubRevokedPreview() = Host(data(link = HubLinkState.REVOKED))

@Preview(name = "Hub - clock wrong", showBackground = true)
@Composable
private fun HubClockWrongPreview() = Host(data(state = HubSampleData.state.copy(clockWrong = true)))

@Preview(name = "Hub - search with no match", showBackground = true)
@Composable
private fun HubSearchNoMatchPreview() = Host(
    data().copy(people = emptyList()),
    FlashHubSelection(segment = FlashHubSegment.PEOPLE, searchOpen = true, query = "Zyx"),
)

@Preview(name = "Hub - search results", showBackground = true)
@Composable
private fun HubSearchResultsPreview() = Host(
    data().copy(people = HubSampleData.people.take(2)),
    FlashHubSelection(segment = FlashHubSegment.PEOPLE, searchOpen = true, query = "e"),
)

@Preview(name = "Hub - department not visible", showBackground = true)
@Composable
private fun HubHiddenDepartmentPreview() = Host(data(), FlashHubSelection(segment = FlashHubSegment.DEPARTMENTS, departmentId = "dept-secret"))

@Preview(name = "Hub - announcement reader", showBackground = true)
@Composable
private fun HubReaderPreview() = Host(data(posting = HubPostingRights.OrgWide()), FlashHubSelection(segment = FlashHubSegment.NEWS, announcementId = "a1"))

@Preview(name = "Hub - announcement removed while open", showBackground = true)
@Composable
private fun HubRemovedNoticePreview() = FlashTheme { NoticeLine(FlashHubCopy.ANN_REMOVED) }

@Preview(name = "Hub - person, paired online", showBackground = true)
@Composable
private fun HubPersonPairedPreview() = PersonPreview(HubSampleData.people[0], callAllowed = true)

@Preview(name = "Hub - person, unpaired through the hub", showBackground = true)
@Composable
private fun HubPersonUnpairedPreview() = PersonPreview(HubSampleData.people[3], callAllowed = false, pairAvailable = true)

@Preview(name = "Hub - person, offline", showBackground = true)
@Composable
private fun HubPersonOfflinePreview() = PersonPreview(HubSampleData.people[1], callAllowed = false)

@Preview(name = "Hub - person, message failed", showBackground = true)
@Composable
private fun HubPersonNotePreview() = PersonPreview(HubSampleData.people[2], callAllowed = false, note = FlashHubCopy.MESSAGE_FAILED)

@Composable
private fun PersonPreview(person: com.transfer.flash.core.messaging.model.HubPersonUi, callAllowed: Boolean, pairAvailable: Boolean = false, note: String? = null) {
    FlashTheme {
        Box(Modifier.fillMaxWidth()) {
            FlashHubPersonContent(
                person, FlashHubPersonPaneState(callAllowed = callAllowed, pairAvailable = pairAvailable, note = note),
                onMessage = {}, onCall = {}, onPair = {}, onClose = null,
            )
        }
    }
}

@Composable
private fun ComposerPreview(state: FlashHubComposerState) {
    FlashTheme {
        FlashAnnouncementComposer(
            state = state, audiences = FlashHubMath.audiences(HubPostingRights.OrgWide(), HubSampleData.departments), canPin = true,
            onChange = {}, onSubmit = {}, onCancel = {},
        )
    }
}

@Preview(name = "Hub - composer, post failed", showBackground = true)
@Composable
private fun HubComposerFailedPreview() = ComposerPreview(
    FlashHubComposerState(title = "Office closed", body = "Friday.", failure = FlashHubMath.composerFailure(com.transfer.flash.core.common.result.FlashError.NetworkUnavailable("x"))),
)

@Preview(name = "Hub - composer, rate limited", showBackground = true)
@Composable
private fun HubComposerRatePreview() = ComposerPreview(
    FlashHubComposerState(
        title = "Office closed", body = "Friday.",
        failure = FlashHubMath.composerFailure(com.transfer.flash.core.common.result.FlashError.Domain("RATE_LIMITED", "slow", 12)),
    ),
)

@Preview(name = "Hub - composer, over size", showBackground = true)
@Composable
private fun HubComposerOverPreview() = ComposerPreview(
    FlashHubComposerState(title = "Long one", body = "x".repeat(FlashHubMath.BODY_MAX + 20)),
)

// --- Redesign 2026-10-20: the same screen in each mode (docs/ui/flash-hub-tab.md "Redesign 2026-10-20") ---------------------

@Composable
private fun ModeHost(
    dark: Boolean,
    reduceMotion: Boolean = false,
    minimal: Boolean = false,
    ultra: Boolean = false,
    data: FlashHubScreenData = data(posting = HubPostingRights.OrgWide()),
    selection: FlashHubSelection = FlashHubSelection(segment = FlashHubSegment.NEWS),
) {
    FlashTheme(
        darkTheme = dark,
        motion = com.transfer.flash.ui.theme.rememberFlashMotion(reduceMotion = reduceMotion),
        minimalChrome = minimal,
        ultraLowUi = ultra,
    ) { FlashHubScreen(data = data, selection = selection, onSelection = {}, actions = actions()) }
}

@Preview(name = "Hub - dark, full", showBackground = true)
@Composable
private fun HubDarkPreview() = ModeHost(dark = true)

@Preview(name = "Hub - light, full", showBackground = true)
@Composable
private fun HubLightPreview() = ModeHost(dark = false)

@Preview(name = "Hub - dark, reduced motion", showBackground = true)
@Composable
private fun HubReducedMotionPreview() = ModeHost(dark = true, reduceMotion = true)

@Preview(name = "Hub - dark, minimal chrome", showBackground = true)
@Composable
private fun HubMinimalPreview() = ModeHost(dark = true, minimal = true)

@Preview(name = "Hub - light, ultra low UI", showBackground = true)
@Composable
private fun HubUltraLowPreview() = ModeHost(dark = false, reduceMotion = true, minimal = true, ultra = true)

@Preview(name = "Hub - loading, dark", showBackground = true)
@Composable
private fun HubLoadingDarkPreview() = ModeHost(dark = true, data = data(state = HubUiState(enrolled = true, loaded = false)))

@Preview(name = "Hub - lease ending soon, departments", showBackground = true)
@Composable
private fun HubLeaseSoonPreview() = ModeHost(
    dark = false,
    data = data(state = HubSampleData.state.copy(lease = OrgLease.Valid(NOW + 3 * 24L * 60L * 60L * 1000L))),
    selection = FlashHubSelection(segment = FlashHubSegment.DEPARTMENTS),
)

@Preview(name = "Hub - status page", showBackground = true)
@Composable
private fun HubStatusPreview() = FlashTheme {
    FlashHubStatusContent(data = data(state = HubSampleData.state.copy(lastSyncedAtMs = NOW - 4 * 60_000L)), onRefresh = {})
}

@Preview(name = "Hub - status page, offline and stale", showBackground = true)
@Composable
private fun HubStatusOfflinePreview() = FlashTheme {
    FlashHubStatusContent(
        data = data(
            state = HubSampleData.state.copy(
                syncStatus = HubSyncStatus.FAILED_OFFLINE, lastSyncedAtMs = NOW - 3 * 3_600_000L,
                lease = OrgLease.Expired(NOW - 1000L, ExpiredReason.TIME), clockWrong = true,
            ),
            link = HubLinkState.OFFLINE,
        ),
        onRefresh = {},
    )
}
