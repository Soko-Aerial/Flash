package com.transfer.flash.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.generated.resources.Res
// CMP generates each drawable as an EXTENSION property on `Res.drawable`
// (`internal val Res.drawable.flash_ic_send: DrawableResource`), not as a member, so importing
// `Res` alone leaves all 54 references unresolved — the extensions have to be imported too.
import com.transfer.flash.ui.theme.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class FlashIconState {
    Default,
    Active,
    Disabled,
    Error,
}

@Immutable
data class FlashIconSpec(
    val drawableRes: DrawableResource,
    val contentDescription: String,
)

/**
 * Typed accessors for Flash-owned vector icons (`flash_ic_*`). Do not use Material Icons in chat chrome.
 */
object FlashIcons {
    val Send = FlashIconSpec(Res.drawable.flash_ic_send, "Send message")
    val Attach = FlashIconSpec(Res.drawable.flash_ic_attach, "Add attachment")
    val Camera = FlashIconSpec(Res.drawable.flash_ic_camera, "Camera")
    val Gallery = FlashIconSpec(Res.drawable.flash_ic_gallery, "Gallery")
    val Microphone = FlashIconSpec(Res.drawable.flash_ic_microphone, "Voice message")
    val Stop = FlashIconSpec(Res.drawable.flash_ic_stop, "Stop")
    val Play = FlashIconSpec(Res.drawable.flash_ic_play, "Play")
    val Pause = FlashIconSpec(Res.drawable.flash_ic_pause, "Pause")
    val Download = FlashIconSpec(Res.drawable.flash_ic_download, "Download")
    val Upload = FlashIconSpec(Res.drawable.flash_ic_upload, "Upload")
    val Share = FlashIconSpec(Res.drawable.flash_ic_share, "Share")
    val Reply = FlashIconSpec(Res.drawable.flash_ic_reply, "Reply")
    val Forward = FlashIconSpec(Res.drawable.flash_ic_forward, "Forward")
    val React = FlashIconSpec(Res.drawable.flash_ic_react, "Add reaction")
    val Search = FlashIconSpec(Res.drawable.flash_ic_search, "Search")
    val Call = FlashIconSpec(Res.drawable.flash_ic_call, "Voice call")
    val VideoCall = FlashIconSpec(Res.drawable.flash_ic_video_call, "Video call")
    val More = FlashIconSpec(Res.drawable.flash_ic_more, "More options")
    val Back = FlashIconSpec(Res.drawable.flash_ic_back, "Back")
    val Close = FlashIconSpec(Res.drawable.flash_ic_close, "Close")
    val Edit = FlashIconSpec(Res.drawable.flash_ic_edit, "Edit")
    val Delete = FlashIconSpec(Res.drawable.flash_ic_delete, "Delete")
    val Pin = FlashIconSpec(Res.drawable.flash_ic_pin, "Pin")
    val Mute = FlashIconSpec(Res.drawable.flash_ic_mute, "Mute")
    val Archive = FlashIconSpec(Res.drawable.flash_ic_archive, "Archive")
    val Group = FlashIconSpec(Res.drawable.flash_ic_group, "Group")
    val Device = FlashIconSpec(Res.drawable.flash_ic_device, "Device")
    val Connection = FlashIconSpec(Res.drawable.flash_ic_connection, "Connection")
    val Retry = FlashIconSpec(Res.drawable.flash_ic_retry, "Retry")
    val Clock = FlashIconSpec(Res.drawable.flash_ic_clock, "Sending")
    val Check = FlashIconSpec(Res.drawable.flash_ic_check, "Sent")
    val Verified = FlashIconSpec(Res.drawable.flash_ic_verified, "Verified")
    val Delivered = FlashIconSpec(Res.drawable.flash_ic_read, "Delivered")
    val Read = FlashIconSpec(Res.drawable.flash_ic_read, "Read")
    val Failed = FlashIconSpec(Res.drawable.flash_ic_failed, "Failed")
    val Encryption = FlashIconSpec(Res.drawable.flash_ic_encryption, "Encrypted")
    val Relay = FlashIconSpec(Res.drawable.flash_ic_relay, "Relay")
    val Wifi = FlashIconSpec(Res.drawable.flash_ic_wifi, "Wi-Fi")
    val WifiDirect = FlashIconSpec(Res.drawable.flash_ic_wifi_direct, "Wi-Fi Direct")

    /** Calling icons (UI-050, docs/ui/calling-ui.md). */
    val Speaker = FlashIconSpec(Res.drawable.flash_ic_speaker, "Speakerphone")
    val Hangup = FlashIconSpec(Res.drawable.flash_ic_hangup, "End call")
    val CallAccept = FlashIconSpec(Res.drawable.flash_ic_call_accept, "Accept call")
    val CameraFlip = FlashIconSpec(Res.drawable.flash_ic_camera_flip, "Switch camera")

    /** UI-046 bottom-navigation tab glyphs (docs/ui/bottom-nav.md). */
    val Chat = FlashIconSpec(Res.drawable.flash_ic_chat, "Chats")
    val Transfer = FlashIconSpec(Res.drawable.flash_ic_transfer, "Transfers")
    val Nearby = FlashIconSpec(Res.drawable.flash_ic_nearby, "Nearby")
    val Settings = FlashIconSpec(Res.drawable.flash_ic_settings, "Settings")

    // Provisional reaction / action icons (UI-009 will refine)
    val Thread = FlashIconSpec(Res.drawable.flash_ic_thread, "Thread reply")
    val Flag = FlashIconSpec(Res.drawable.flash_ic_flag, "Flag message")
    val ThumbUp = FlashIconSpec(Res.drawable.flash_ic_thumb_up, "Like")
    val Heart = FlashIconSpec(Res.drawable.flash_ic_heart, "Love")
    val Bolt = FlashIconSpec(Res.drawable.flash_ic_bolt, "Wow")
    val Tray = FlashIconSpec(Res.drawable.flash_ic_tray, "Flash")
    val Sliders = FlashIconSpec(Res.drawable.flash_ic_sliders, "More reactions")
    val ThumbDown = FlashIconSpec(Res.drawable.flash_ic_thumb_down, "Dislike")

    /** Minimum MVP chat icon set for UI-002 verification. */
    val mvpChatSet: List<FlashIconSpec> = listOf(
        Send, Attach, Camera, Gallery, Microphone, Stop, Play, Pause,
        Download, Upload, Share, Reply, Forward, React, Search, Call, VideoCall,
        More, Back, Close, Edit, Delete, Pin, Mute, Archive, Group,
        Device, Connection, Retry, Verified, Delivered, Read, Failed,
        Encryption, Relay, Wifi, WifiDirect,
    )
}

fun FlashIconState.tint(colors: FlashColors, override: Color? = null): Color {
    override?.let { return it }
    return when (this) {
        FlashIconState.Default -> colors.textPrimary
        FlashIconState.Active -> colors.accentPrimary
        FlashIconState.Disabled -> colors.textTertiary
        FlashIconState.Error -> colors.textError
    }
}

@Composable
fun FlashIcon(
    icon: FlashIconSpec,
    modifier: Modifier = Modifier,
    contentDescription: String? = icon.contentDescription,
    state: FlashIconState = FlashIconState.Default,
    tint: Color? = null,
    size: Dp = FlashDimensions.iconMd,
) {
    FlashIcon(
        painter = painterResource(icon.drawableRes),
        contentDescription = contentDescription ?: icon.contentDescription,
        modifier = modifier,
        state = state,
        tint = tint,
        size = size,
    )
}

@Composable
fun FlashIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    state: FlashIconState = FlashIconState.Default,
    tint: Color? = null,
    size: Dp = FlashDimensions.iconMd,
) {
    val colors = FlashTheme.colors
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = state.tint(colors, tint),
    )
}

val flashIconDefaultSize: Dp = FlashDimensions.iconMd
