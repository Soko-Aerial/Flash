package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Text

/**
 * The News segment: a composer entry for people who may post (or one calm sentence for everyone else), the pinned
 * announcements, the latest ones, an honesty footer, and a clear empty / unsupported state.
 */
internal fun LazyListScope.hubNewsItems(
    news: List<HubAnnouncementUi>,
    posting: HubPostingRights,
    unsupported: Boolean,
    nowMs: Long,
    departmentName: (String) -> String?,
    onOpen: (String) -> Unit,
    onMenu: (String) -> Unit,
    onNew: () -> Unit,
) {
    val canPost = FlashHubMath.canPost(posting)
    if (unsupported) {
        item(key = "news-unsupported", contentType = "empty") {
            FlashHubEmptyState(icon = FlashIcons.Announcement, headline = FlashHubCopy.ANN_UNSUPPORTED)
        }
        return
    }
    item(key = "news-compose", contentType = "compose") {
        if (canPost) {
            FlashHubComposeEntry(
                subtitle = if (posting is HubPostingRights.OrgWide) FlashHubCopy.ANN_COMPOSE_SUB_ORG else FlashHubCopy.ANN_COMPOSE_SUB_DEPT,
                onClick = onNew,
            )
        } else {
            FlashHubNoteCard(FlashIcons.Announcement, FlashHubCopy.ANN_MEMBERS_CANT_POST)
        }
    }
    if (news.isEmpty()) {
        item(key = "news-empty", contentType = "empty") {
            FlashHubEmptyState(
                icon = FlashIcons.Announcement,
                headline = FlashHubCopy.ANN_EMPTY_HEADLINE,
                body = if (canPost) FlashHubCopy.ANN_EMPTY_POSTER else FlashHubCopy.ANN_EMPTY_MEMBER,
            )
        }
    } else {
        val pinned = news.filter { it.pinned }
        val latest = news.filterNot { it.pinned }
        if (pinned.isNotEmpty()) item(key = "news-h-pinned", contentType = "header") { FlashHubSectionTitle(FlashHubCopy.ANN_SECTION_PINNED) }
        items(pinned.size, key = { "a:${pinned[it].id}" }, contentType = { "card" }) { index ->
            val a = pinned[index]
            FlashAnnouncementCard(a, FlashHubMath.scopeLabel(a, departmentName), FlashHubMath.rights(a, posting).any, nowMs, { onOpen(a.id) }, { onMenu(a.id) })
        }
        if (pinned.isNotEmpty() && latest.isNotEmpty()) item(key = "news-h-latest", contentType = "header") { FlashHubSectionTitle(FlashHubCopy.ANN_SECTION_LATEST) }
        items(latest.size, key = { "a:${latest[it].id}" }, contentType = { "card" }) { index ->
            val a = latest[index]
            FlashAnnouncementCard(a, FlashHubMath.scopeLabel(a, departmentName), FlashHubMath.rights(a, posting).any, nowMs, { onOpen(a.id) }, { onMenu(a.id) })
        }
    }
    item(key = "news-honesty", contentType = "footer") {
        FlashText(
            FlashHubCopy.ANN_HONESTY,
            modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textTertiary,
        )
    }
}

/** The "Share an update" row at the top of News for people who may post. The whole row is the button. */
@Composable
internal fun FlashHubComposeEntry(subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val shape = FlashShapes.attachment
    Row(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.accentPrimary.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = "${FlashHubCopy.ANN_COMPOSE_TITLE}. $subtitle" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentPrimary), contentAlignment = Alignment.Center) {
            FlashIcon(icon = FlashIcons.Announcement, contentDescription = null, tint = colors.textOnAccent, size = FlashDimensions.iconMd)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(FlashHubCopy.ANN_COMPOSE_TITLE, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
            FlashText(subtitle, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlashHubChip(FlashHubCopy.ANN_POST, emphasised = true)
    }
}

/** Marks cards read after 60 % of them were visible for one second (design doc "Animation specification"). */
@Composable
internal fun FlashHubReadMarking(
    listState: LazyListState,
    unreadIds: Set<String>,
    onRead: (String) -> Unit,
) {
    if (unreadIds.isEmpty()) return
    LaunchedEffect(listState, unreadIds) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.mapNotNull { item ->
                val key = item.key as? String ?: return@mapNotNull null
                if (!key.startsWith("a:")) return@mapNotNull null
                val id = key.removePrefix("a:")
                if (id !in unreadIds) return@mapNotNull null
                val f = FlashHubMath.visibleFraction(item.offset, item.size, info.viewportStartOffset, info.viewportEndOffset)
                if (f >= FlashHubMath.READ_VISIBLE_FRACTION) id else null
            }.toSet()
        }.collectLatest { visible ->
            if (visible.isNotEmpty()) {
                delay(FlashHubMath.READ_VISIBLE_MS)
                visible.forEach(onRead)
            }
        }
    }
}

/**
 * One announcement: the author's avatar, name, role and time, the scope and pinned chips, the title and a three line preview.
 * A coloured edge marks what is unread or pinned (and the unread title is bold), so a glance shows what is new.
 */
@Composable
internal fun FlashAnnouncementCard(
    announcement: HubAnnouncementUi,
    scopeLabel: String,
    hasMenu: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val a = announcement
    val roleChip = FlashHubMath.authorRoleChip(a.authorRole)
    val time = FlashHubMath.relativeTime(a.createdAtMs, nowMs)
    var truncated by remember(a.id, a.body) { mutableStateOf(false) }
    val shape = FlashShapes.attachment
    val spoken = buildString {
        append(scopeLabel).append(", ").append(a.title).append(", ").append(a.authorName)
        if (roleChip != null) append(", ").append(roleChip)
        append(", ").append(time)
        if (a.pinned) append(", ").append(FlashHubCopy.ANN_PINNED)
        if (!a.isRead) append(", ").append(FlashHubCopy.ANN_UNREAD)
    }
    val edge = when {
        !a.isRead -> colors.accentPrimary
        a.pinned -> colors.accentPrimary.copy(alpha = 0.45f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, if (a.pinned) colors.accentPrimary else colors.borderSubtle, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (hasMenu) customActions = listOf(CustomAccessibilityAction("More actions") { onMenu(); true })
            },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(edge))
        Column(
            modifier = Modifier.weight(1f).padding(FlashSpacing.space12),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                FlashAvatar(initials = FlashHubMath.initials(a.authorName), size = 36.dp, seed = a.authorName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                        FlashText(a.authorName, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (roleChip != null) FlashHubChip(roleChip)
                    }
                    FlashText(
                        if (a.edited) "$time · ${FlashHubCopy.ANN_EDITED}" else time,
                        style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1,
                    )
                }
                if (hasMenu) FlashHubIconButton(FlashIcons.More, "More actions", onMenu)
            }
            FlashText(
                a.title,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = a.body,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
                maxLines = FlashHubMath.CARD_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (truncated || a.body.lines().size > FlashHubMath.CARD_PREVIEW_LINES) {
                FlashText(FlashHubCopy.ANN_READ_MORE, style = FlashTheme.typography.captionEmphasis, color = colors.accentPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashHubChip(scopeLabel, emphasised = true, modifier = Modifier.weight(1f, fill = false))
                if (a.pinned) FlashHubChip(FlashHubCopy.ANN_PINNED)
                Box(Modifier.weight(1f))
                if (!a.isRead) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accentPrimary))
                    FlashText(FlashHubCopy.ANN_UNREAD, style = FlashTheme.typography.metadataEmphasis, color = colors.accentPrimary, maxLines = 1)
                }
            }
        }
    }
}


/** Full text of one announcement. Used as a full-screen reader (compact) and in the detail pane (wide). */
@Composable
internal fun FlashAnnouncementReader(
    announcement: HubAnnouncementUi,
    scopeLabel: String,
    rights: FlashHubAnnouncementRights,
    pinned: Boolean,
    nowMs: Long,
    onBack: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val a = announcement
    val roleChip = FlashHubMath.authorRoleChip(a.authorRole)
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            if (onBack != null) FlashHubIconButton(FlashIcons.Back, FlashHubCopy.ANN_READER_BACK, onBack)
            FlashHubChip(scopeLabel, emphasised = true, modifier = Modifier.weight(1f, fill = false))
            if (a.pinned) FlashHubChip(FlashHubCopy.ANN_PINNED)
        }
        FlashText(a.title, style = FlashTheme.typography.headingMedium, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            FlashText(a.authorName, style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (roleChip != null) FlashHubChip(roleChip)
            val time = FlashHubMath.relativeTime(a.createdAtMs, nowMs)
            FlashText(if (a.edited) "$time · ${FlashHubCopy.ANN_EDITED}" else time, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
        }
        FlashHubLinkedText(a.body, onLink = onLink)
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), modifier = Modifier.fillMaxWidth()) {
            FlashHubButton(FlashHubCopy.ANN_COPY, onCopy, icon = FlashIcons.Share, primary = false)
            if (rights.canEdit) FlashHubButton(FlashHubCopy.ANN_EDIT, onEdit, icon = FlashIcons.Edit, primary = false)
        }
        if (rights.canPin || rights.canDelete) {
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), modifier = Modifier.fillMaxWidth()) {
                if (rights.canPin) FlashHubButton(if (pinned) FlashHubCopy.ANN_UNPIN else FlashHubCopy.ANN_PIN, onTogglePin, icon = FlashIcons.Pin, primary = false)
                if (rights.canDelete) FlashHubButton(FlashHubCopy.ANN_DELETE, onDelete, icon = FlashIcons.Delete, primary = false)
            }
        }
        FlashText(FlashHubCopy.ANN_HONESTY, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
    }
}

/** Body text where only http/https links are tappable (and also offered as TalkBack actions). Everything else is plain. */
@Composable
internal fun FlashHubLinkedText(text: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val parts = remember(text) { FlashHubMath.parseLinks(text) }
    val links = remember(parts) { parts.filterIsInstance<FlashHubMath.TextPart.Link>() }
    val linkColor = colors.textLink
    val annotated: AnnotatedString = remember(parts, linkColor) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is FlashHubMath.TextPart.Plain -> append(part.text)
                    is FlashHubMath.TextPart.Link -> {
                        pushStringAnnotation(tag = "url", annotation = part.url)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(part.text) }
                        pop()
                    }
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = FlashTheme.typography.bodyDefault,
        color = colors.textPrimary,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(annotated, links.isNotEmpty()) {
                if (links.isEmpty()) return@pointerInput
                detectTapGestures { pos ->
                    val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onLink(it.item) }
                }
            }
            .semantics {
                if (links.isNotEmpty()) {
                    customActions = links.map { l ->
                        CustomAccessibilityAction("Open link ${FlashHubMath.linkHost(l.url) ?: l.url}") { onLink(l.url); true }
                    }
                }
            },
    )
}

/** What the composer holds. [audienceIndex] indexes the audience list given to the composer. */
internal data class FlashHubComposerState(
    val title: String = "",
    val body: String = "",
    val audienceIndex: Int = 0,
    val pinned: Boolean = false,
    val sending: Boolean = false,
    val failure: FlashHubComposerFailure? = null,
    val editingId: String? = null,
)

/** The announcement composer. Offline posting is not queued: the draft stays and the banner says why (Plan A3). */
@Composable
internal fun FlashAnnouncementComposer(
    state: FlashHubComposerState,
    audiences: List<FlashHubAudience>,
    canPin: Boolean,
    onChange: (FlashHubComposerState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val titleCount = FlashHubMath.codePoints(state.title)
    val bodyCount = FlashHubMath.codePoints(state.body)
    val titleTone = FlashHubMath.counterTone(titleCount, FlashHubMath.TITLE_MAX)
    val bodyTone = FlashHubMath.counterTone(bodyCount, FlashHubMath.BODY_MAX)
    val editing = state.editingId != null
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16).padding(bottom = FlashSpacing.space24),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText(
            if (editing) FlashHubCopy.ANN_COMPOSER_TITLE_EDIT else FlashHubCopy.ANN_COMPOSER_TITLE_NEW,
            style = FlashTheme.typography.headingMedium, color = colors.textPrimary, modifier = Modifier.semantics { heading() },
        )
        if (!editing && audiences.size > 1) {
            FlashText(FlashHubCopy.ANN_AUDIENCE, style = FlashTheme.typography.metadataEmphasis, color = colors.textSecondary)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                audiences.forEachIndexed { i, aud ->
                    FlashHubFilterChip(aud.label, i == state.audienceIndex) { onChange(state.copy(audienceIndex = i, failure = null)) }
                }
            }
        }
        audiences.getOrNull(state.audienceIndex)?.takeIf { !editing }?.let { aud ->
            FlashText(FlashHubCopy.annVisibleTo(aud.label), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
        }
        FlashHubField(
            value = state.title, hint = FlashHubCopy.ANN_TITLE_HINT, singleLine = true, minHeight = FlashDimensions.minTouchTarget,
            onChange = { onChange(state.copy(title = it, failure = null)) },
        )
        FlashHubCounter(titleCount, FlashHubMath.TITLE_MAX, titleTone)
        FlashHubField(
            value = state.body, hint = FlashHubCopy.ANN_BODY_HINT, singleLine = false, minHeight = 140.dp,
            onChange = { onChange(state.copy(body = it, failure = null)) },
        )
        FlashHubCounter(bodyCount, FlashHubMath.BODY_MAX, bodyTone)
        if (canPin) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = FlashDimensions.minTouchTarget)
                    .clickable(role = Role.Switch) { onChange(state.copy(pinned = !state.pinned, failure = null)) }
                    .semantics(mergeDescendants = true) { stateDescription = if (state.pinned) FlashHubCopy.SWITCH_ON else FlashHubCopy.SWITCH_OFF },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                FlashText(FlashHubCopy.ANN_PIN_TOGGLE, modifier = Modifier.weight(1f), style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
                FlashHubSwitch(state.pinned)
            }
        }
        state.failure?.let { f ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(FlashShapes.attachment).background(colors.textError.copy(alpha = 0.10f))
                    .padding(FlashSpacing.space12).semantics { liveRegion = LiveRegionMode.Assertive },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(icon = FlashIcons.Failed, contentDescription = null, tint = colors.textError, size = FlashDimensions.iconSm)
                FlashText(f.text, modifier = Modifier.weight(1f), style = FlashTheme.typography.bodyDefault, color = colors.textError)
            }
        }
        FlashText(FlashHubCopy.ANN_HONESTY, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), modifier = Modifier.fillMaxWidth()) {
            FlashHubButton(FlashHubCopy.ANN_CANCEL, onCancel, primary = false, modifier = Modifier.weight(1f))
            FlashHubButton(
                label = when {
                    state.sending -> FlashHubCopy.REFRESHING
                    state.failure?.retryable == true -> FlashHubCopy.RETRY
                    editing -> FlashHubCopy.ANN_SAVE
                    else -> FlashHubCopy.ANN_POST
                },
                onClick = onSubmit,
                enabled = FlashHubMath.canSend(state.title, state.body, state.sending),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FlashHubCounter(used: Int, max: Int, tone: FlashHubMath.CounterTone) {
    val colors = FlashTheme.colors
    FlashText(
        FlashHubCopy.annCounter(used, max),
        modifier = Modifier.fillMaxWidth(),
        style = FlashTheme.typography.numericDefault,
        color = when (tone) {
            FlashHubMath.CounterTone.NORMAL -> colors.textTertiary
            FlashHubMath.CounterTone.WARNING -> colors.textPrimary
            FlashHubMath.CounterTone.ERROR -> colors.textError
        },
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
internal fun FlashHubField(
    value: String,
    hint: String,
    singleLine: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(FlashShapes.attachment)
            .background(colors.composerInputBackground)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accentPrimary),
            decorationBox = { inner ->
                if (value.isEmpty()) FlashText(hint, style = FlashTheme.typography.bodyDefault, color = colors.textTertiary)
                inner()
            },
        )
    }
}
