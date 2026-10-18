package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import org.jetbrains.compose.resources.painterResource

/**
 * The glyph vocabulary of the deck.
 *
 * Every glyph is one of the project's own `flash_ic_*` vectors ([FlashIcons]); the deck adds no new drawables and no
 * Material icon, so it cannot drift from the UI-002 icon system. A glyph is always drawn *decorative* — the meaning
 * lives on the surrounding composable's own `contentDescription`/text, which is why [HcGlyph] is not a semantics
 * shortcut.
 */
internal enum class HcGlyph(val icon: FlashIconSpec) {
    HUB(FlashIcons.Hub),
    ANNOUNCEMENT(FlashIcons.Announcement),
    PERSON(FlashIcons.Device),
    GROUP(FlashIcons.Group),
    DEPARTMENT(FlashIcons.Hub),
    CHAT(FlashIcons.Chat),
    TRANSFER(FlashIcons.Transfer),
    SEARCH(FlashIcons.Search),
    CHECK(FlashIcons.Check),
    VERIFIED(FlashIcons.Verified),
    WARNING(FlashIcons.Failed),
    RETRY(FlashIcons.Retry),
    CLOCK(FlashIcons.Clock),
    SHIELD(FlashIcons.Encryption),
    RELAY(FlashIcons.Relay),
    LINK_OFF(FlashIcons.Connection),
    WIFI(FlashIcons.Wifi),
    DEVICE(FlashIcons.Device),
    SETTINGS(FlashIcons.Settings),
    MORE(FlashIcons.More),
    BACK(FlashIcons.Back),
    CLOSE(FlashIcons.Close),
    CHEVRON(FlashIcons.ChevronDown),
    PIN(FlashIcons.Pin),
    EDIT(FlashIcons.Edit),
    DELETE(FlashIcons.Delete),
    SHARE(FlashIcons.Share),
    DOWNLOAD(FlashIcons.Download),
    UPLOAD(FlashIcons.Upload),
    CALL(FlashIcons.Call),
    VIDEO_CALL(FlashIcons.VideoCall),
    ARCHIVE(FlashIcons.Archive),
    MUTED(FlashIcons.NotificationOff),
    KEY(FlashIcons.Encryption),
    CERTIFICATE(FlashIcons.Verified),
    ROSTER(FlashIcons.Group),
    MAILBOX(FlashIcons.Tray),
    SLIDERS(FlashIcons.Sliders),
    FLAG(FlashIcons.Flag),
    BOLT(FlashIcons.Bolt),
    PLAY(FlashIcons.Play),
    PAUSE(FlashIcons.Pause),
}

/** Decorative glyph at [tint]. Uses the painter overload so no `contentDescription` leaks into the semantics tree. */
@Composable
internal fun HcGlyphIcon(
    glyph: HcGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = FlashDimensions.iconSm,
) {
    FlashIcon(
        painter = painterResource(glyph.icon.drawableRes),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
        size = size,
    )
}
