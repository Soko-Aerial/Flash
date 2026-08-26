package com.transfer.flash.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.R
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashTheme

enum class FlashIconState {
    Default,
    Active,
    Disabled,
    Error,
}

@Immutable
data class FlashIconSpec(
    @param:DrawableRes val drawableRes: Int,
    val contentDescription: String,
)

/**
 * Typed accessors for Flash-owned vector icons (`flash_ic_*`). Do not use Material Icons in chat chrome.
 */
object FlashIcons {
    val Send = FlashIconSpec(R.drawable.flash_ic_send, "Send message")
    val Attach = FlashIconSpec(R.drawable.flash_ic_attach, "Add attachment")
    val Camera = FlashIconSpec(R.drawable.flash_ic_camera, "Camera")
    val Gallery = FlashIconSpec(R.drawable.flash_ic_gallery, "Gallery")
    val Microphone = FlashIconSpec(R.drawable.flash_ic_microphone, "Voice message")
    val Stop = FlashIconSpec(R.drawable.flash_ic_stop, "Stop")
    val Play = FlashIconSpec(R.drawable.flash_ic_play, "Play")
    val Pause = FlashIconSpec(R.drawable.flash_ic_pause, "Pause")
    val Download = FlashIconSpec(R.drawable.flash_ic_download, "Download")
    val Upload = FlashIconSpec(R.drawable.flash_ic_upload, "Upload")
    val Share = FlashIconSpec(R.drawable.flash_ic_share, "Share")
    val Reply = FlashIconSpec(R.drawable.flash_ic_reply, "Reply")
    val Forward = FlashIconSpec(R.drawable.flash_ic_forward, "Forward")
    val React = FlashIconSpec(R.drawable.flash_ic_react, "Add reaction")
    val Search = FlashIconSpec(R.drawable.flash_ic_search, "Search")
    val Call = FlashIconSpec(R.drawable.flash_ic_call, "Voice call")
    val VideoCall = FlashIconSpec(R.drawable.flash_ic_video_call, "Video call")
    val More = FlashIconSpec(R.drawable.flash_ic_more, "More options")
    val Back = FlashIconSpec(R.drawable.flash_ic_back, "Back")
    val Close = FlashIconSpec(R.drawable.flash_ic_close, "Close")
    val Edit = FlashIconSpec(R.drawable.flash_ic_edit, "Edit")
    val Delete = FlashIconSpec(R.drawable.flash_ic_delete, "Delete")
    val Pin = FlashIconSpec(R.drawable.flash_ic_pin, "Pin")
    val Mute = FlashIconSpec(R.drawable.flash_ic_mute, "Mute")
    val Archive = FlashIconSpec(R.drawable.flash_ic_archive, "Archive")
    val Group = FlashIconSpec(R.drawable.flash_ic_group, "Group")
    val Device = FlashIconSpec(R.drawable.flash_ic_device, "Device")
    val Connection = FlashIconSpec(R.drawable.flash_ic_connection, "Connection")
    val Retry = FlashIconSpec(R.drawable.flash_ic_retry, "Retry")
    val Clock = FlashIconSpec(R.drawable.flash_ic_clock, "Sending")
    val Check = FlashIconSpec(R.drawable.flash_ic_check, "Sent")
    val Verified = FlashIconSpec(R.drawable.flash_ic_verified, "Verified")
    val Delivered = FlashIconSpec(R.drawable.flash_ic_read, "Delivered")
    val Read = FlashIconSpec(R.drawable.flash_ic_read, "Read")
    val Failed = FlashIconSpec(R.drawable.flash_ic_failed, "Failed")
    val Encryption = FlashIconSpec(R.drawable.flash_ic_encryption, "Encrypted")
    val Relay = FlashIconSpec(R.drawable.flash_ic_relay, "Relay")
    val Wifi = FlashIconSpec(R.drawable.flash_ic_wifi, "Wi-Fi")
    val WifiDirect = FlashIconSpec(R.drawable.flash_ic_wifi_direct, "Wi-Fi Direct")

    /** UI-046 bottom-navigation tab glyphs (docs/ui/bottom-nav.md). */
    val Chat = FlashIconSpec(R.drawable.flash_ic_chat, "Chats")
    val Transfer = FlashIconSpec(R.drawable.flash_ic_transfer, "Transfers")
    val Nearby = FlashIconSpec(R.drawable.flash_ic_nearby, "Nearby")
    val Settings = FlashIconSpec(R.drawable.flash_ic_settings, "Settings")

    // Provisional reaction / action icons (UI-009 will refine)
    val Thread = FlashIconSpec(R.drawable.flash_ic_thread, "Thread reply")
    val Flag = FlashIconSpec(R.drawable.flash_ic_flag, "Flag message")
    val ThumbUp = FlashIconSpec(R.drawable.flash_ic_thumb_up, "Like")
    val Heart = FlashIconSpec(R.drawable.flash_ic_heart, "Love")
    val Bolt = FlashIconSpec(R.drawable.flash_ic_bolt, "Wow")
    val Sliders = FlashIconSpec(R.drawable.flash_ic_sliders, "More reactions")
    val ThumbDown = FlashIconSpec(R.drawable.flash_ic_thumb_down, "Dislike")

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
