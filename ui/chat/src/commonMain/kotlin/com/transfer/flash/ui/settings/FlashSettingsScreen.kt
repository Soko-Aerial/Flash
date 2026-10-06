package com.transfer.flash.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics

/**
 * P5 Settings tab (UI-049, docs/ui/settings-page.md): five grouped sections of Flash-owned
 * rows — no Material ListItems/switches. Demo model today; C1.4 DataStore substitutes at wiring.
 */
enum class FlashThemeMode { System, Light, Dark }


data class FlashSettingsModel(
    val displayName: String = "Flash device",
    val themeMode: FlashThemeMode = FlashThemeMode.System,
    val dynamicAccent: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val backgroundTransfers: Boolean = false,
    /** Discovery presence mode: STANDARD (Discoverable), GHOST (Hidden), ECO, BOOST. */
    val discoveryMode: String = "STANDARD",
    /** Whether Windows Explorer context menu ("Send with Flash") is enabled. */
    val windowsContextMenu: Boolean = true,
    val showWindowsContextMenu: Boolean = false,
    // Bug 3: per-MIME auto-download of inbound offers. Defaults: voice + images auto-download
    // (true); videos + files ask before downloading (false).
    val autoDownloadVoice: Boolean = true,
    val autoDownloadImage: Boolean = true,
    val autoDownloadVideo: Boolean = false,
    val autoDownloadFile: Boolean = false,
    /**
     * Whether the OS exempts Flash from battery optimisation (ERROR-031 / D7).
     *
     * On Transsion/Samsung builds this is the difference between the mesh surviving Doze and the
     * process being killed minutes after the screen goes off — and it is also what lets a refused
     * foreground-service promotion be retried at all, since the exemption *is* one of Android 12+'s
     * FGS-start exemptions while a screen-on broadcast is not. Surfaced read-only next to
     * "Background transfers"; tapping the row opens the system prompt.
     */
    val ignoringBatteryOptimizations: Boolean = false,
    /**
     * Spend a congested link on voice before video in a video call (ERROR-031 / D8). Default on.
     *
     * Off restores WebRTC's symmetric treatment of the two streams: video keeps its full share of
     * the bandwidth estimate and nothing steps it down when the audio starts breaking up.
     */
    val prioritiseVoiceQuality: Boolean = true,
    /**
     * "Send smaller video in groups" (ADR-053), default off: in a group video call this device
     * sends its video at 540p to two people and 360p to three or more, to save processor time.
     */
    val smallerVideoForMany: Boolean = false,
    /**
     * The tier the user pinned, or null for "Auto" — follow [detectedPerformanceMode] (ERROR-033).
     *
     * Null is the default and the normal state: the pin exists for the case auto-detect cannot see,
     * which is the *link* rather than the handset. A phone with the silicon for 1080p still stutters
     * behind a mesh AP that keeps handing it off, and only the person holding it knows that.
     */
    val performanceMode: FlashPerformanceMode? = null,
    /**
     * What auto-detect made of this device's RAM, cores, API level and codec support.
     *
     * Carried even when [performanceMode] pins a tier, because "Auto" has to be able to say which
     * tier it would pick — a segment labelled only "Auto" tells the user nothing about what their
     * device is actually doing.
     */
    val detectedPerformanceMode: FlashPerformanceMode = FlashPerformanceMode.HIGH,
    val trustedPeerCount: Int = 0,
    val saveLocationLabel: String? = null,
    /** Last successful received-files scan. Null means no successful scan is cached yet. */
    val receivedFilesBytes: Long? = null,
    val storageUsageLoading: Boolean = false,
    val storageUsageError: Boolean = false,
    val clearingReceivedFiles: Boolean = false,
    val appVersion: String = "dev",
    val protocolVersion: String = "FLASH_XFER/1",
    val deviceIdShort: String = "00000000",
    /** Help share group files: share received file pieces with other group members. Default TRUE. */
    val swarmHelpShare: Boolean = true,
    /** Keep finished files available for others in the swarm. Default TRUE. */
    val swarmKeepFinishedFiles: Boolean = true,
    /** Experimental: group file sharing via swarm transfer (SW-8). Default FALSE. */
    val swarmEnabled: Boolean = false,
    /** UI-056 / ADR-080: the handwritten launch animation on a cold start. Default TRUE. */
    val launchAnimation: Boolean = true,
)

/** Pure helpers backing the settings page (JVM-testable). */
object FlashSettingsMath {

    fun themeModeLabel(mode: FlashThemeMode): String = when (mode) {
        FlashThemeMode.System -> "System"
        FlashThemeMode.Light -> "Light"
        FlashThemeMode.Dark -> "Dark"
    }

    /**
     * Resolves the Appearance selection against the OS setting. The host feeds the result to
     * both `FlashTheme(darkTheme = …)` and `FlashMaterialTheme(darkTheme = …)`, which is what
     * makes the Light/Dark segments actually repaint the app.
     */
    fun resolveDarkTheme(mode: FlashThemeMode, systemDark: Boolean): Boolean = when (mode) {
        FlashThemeMode.System -> systemDark
        FlashThemeMode.Light -> false
        FlashThemeMode.Dark -> true
    }

    /**
     * The launch animation row (UI-056). Says when it plays, because "launch" alone does not tell a
     * user that switching back to Flash will not replay it, and says what turning it off gives them.
     */
    fun launchAnimationSubtitle(enabled: Boolean): String =
        if (enabled) {
            "Writes the Flash name when the app starts, not when you switch back to it"
        } else {
            "Flash opens straight away"
        }

    fun trustedPeersSubtitle(count: Int): String = when {
        count <= 0 -> "No verified devices yet"
        count == 1 -> "1 device verified"
        else -> "$count devices verified"
    }

    /**
     * Explains the battery-optimisation row (ERROR-031 / D7). Deliberately states the consequence
     * rather than the setting's name: "restricted" is the state that silently kills the mesh after
     * the screen goes off, and the user has no way to guess that from "battery optimisation".
     */
    fun batteryExemptionSubtitle(exempt: Boolean): String =
        if (exempt) {
            "Flash can stay connected while the screen is off"
        } else {
            "Android may disconnect Flash when the screen is off — tap to allow"
        }

    /** Trailing value for the same row: the state at a glance, with no jargon. */
    fun batteryExemptionValue(exempt: Boolean): String = if (exempt) "Allowed" else "Restricted"

    /**
     * Explains the calls row (ERROR-031 / D8) in terms of the trade the user is actually making.
     *
     * Both halves have to name *video* as the thing that gives, because the switch's title only
     * mentions voice: a user reading "Prioritise voice quality" cannot tell whether the cost is
     * their video, their battery, or nothing at all.
     */
    fun prioritiseVoiceSubtitle(enabled: Boolean): String =
        if (enabled) {
            "Video quality drops first when a call gets choppy"
        } else {
            "Voice and video share bandwidth equally"
        }

    /** Explains the "Send smaller video in groups" row (ADR-053): what shrinks, and when. */
    fun smallerVideoForManySubtitle(enabled: Boolean): String =
        if (enabled) {
            "Your video goes at 540p to 2 people and 360p to 3 or more. Uses less processor"
        } else {
            "Your video goes at full size to everyone in a group call"
        }

    /** Segment labels for the performance picker. Null is the Auto segment (ERROR-033). */
    fun performanceModeLabel(mode: FlashPerformanceMode?): String = when (mode) {
        null -> "Auto"
        FlashPerformanceMode.LOW -> "Low"
        FlashPerformanceMode.MEDIUM -> "Medium"
        FlashPerformanceMode.HIGH -> "High"
    }

    /**
     * Describes the tier that is actually in force, in the three terms the field testing turned on:
     * capture resolution, how often the radio has to wake for voice, and whether the UI animates.
     *
     * Derived from the profile tokens rather than written out per tier, so the row cannot drift away
     * from what the tier does. When nothing is pinned it names the tier auto-detect chose — "Auto"
     * on its own would leave the user unable to tell a misdetected device from a slow link.
     */
    fun performanceModeSubtitle(
        pinned: FlashPerformanceMode?,
        detected: FlashPerformanceMode,
    ): String {
        val effective = pinned ?: detected
        val parts = buildList {
            if (pinned == null) add("Matched to this device: ${performanceModeLabel(effective)}")
            add("video ${effective.video.label}")
            add("${effective.voice.packetsPerSecond} voice packets/s")
            if (effective.reduceMotion) add("animations off")
        }
        return parts.joinToString(" · ")
    }

    /**
     * The searchable sections of the page. [rows] are the row titles each section holds, so a query
     * for "battery" finds the section that has the battery row. Keep it in step with the screen.
     */
    enum class Section(val title: String, val subtitle: String, val rows: List<String>) {
        APPEARANCE(
            "Appearance & Feedback", "Theme, dynamic accent, haptics, and animation",
            listOf("Theme", "System", "Light", "Dark", "Dynamic accent", "Haptics", "Launch animation"),
        ),
        STORAGE(
            "Storage & Downloads", "Save destination, cache breakdown, and auto-download rules",
            listOf(
                "Save location", "Received files", "Clear received files", "Auto-download voice",
                "Auto-download images", "Auto-download videos", "Auto-download files",
            ),
        ),
        NETWORK(
            "Network & Discovery", "Presence mode, background transfers, and battery settings",
            listOf(
                "Discovery mode", "Standard", "Ghost", "Eco", "Boost", "Background transfers",
                "File Explorer context menu", "Unrestricted battery",
            ),
        ),
        CALLING(
            "Calling & Video", "Bandwidth prioritization and group video resolution",
            listOf("Prioritise voice quality", "Send smaller video in groups"),
        ),
        SECURITY(
            "Security & Trusted Devices", "Cryptographic protection and verified peers",
            listOf("Encryption", "Trusted peers"),
        ),
        PERFORMANCE(
            "Performance Tuning", "Hardware capability tier and UI motion budget",
            listOf("Performance mode", "Auto", "Low", "Medium", "High"),
        ),
        SWARM(
            "Group File Sharing & Diagnostics", "Swarm transfer sharing and log export",
            listOf(
                "Help share group files", "Keep finished files available for others",
                "Group file sharing", "Swarm", "Export logs",
            ),
        ),
    }

    /** True when [query] is blank or case-insensitively contained in any of [texts]. */
    fun matches(query: String, vararg texts: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return texts.any { it.contains(q, ignoreCase = true) }
    }

    /** Whether the section card shows at all for [query]: its own words or any of its rows match. */
    fun sectionVisible(query: String, section: Section): Boolean =
        matches(query, section.title, section.subtitle) || section.rows.any { matches(query, it) }

    /** Whether one row shows: everything in a section whose own title matches, else just the matching rows. */
    fun rowVisible(query: String, section: Section, vararg rowTexts: String): Boolean =
        matches(query, section.title, section.subtitle) || matches(query, *rowTexts)

    /**
     * True when every preference that "Reset to defaults" would change is already at its default.
     * Identity (display name), save location, trusted peers and read-only values are never reset.
     * [FlashSettingsModel.windowsContextMenu] only counts where the host shows that row.
     */
    fun isAtDefaults(model: FlashSettingsModel): Boolean {
        val d = FlashSettingsModel()
        return model.themeMode == d.themeMode &&
            model.dynamicAccent == d.dynamicAccent &&
            model.hapticsEnabled == d.hapticsEnabled &&
            model.launchAnimation == d.launchAnimation &&
            model.backgroundTransfers == d.backgroundTransfers &&
            model.discoveryMode.equals(d.discoveryMode, ignoreCase = true) &&
            (!model.showWindowsContextMenu || model.windowsContextMenu == d.windowsContextMenu) &&
            model.autoDownloadVoice == d.autoDownloadVoice &&
            model.autoDownloadImage == d.autoDownloadImage &&
            model.autoDownloadVideo == d.autoDownloadVideo &&
            model.autoDownloadFile == d.autoDownloadFile &&
            model.prioritiseVoiceQuality == d.prioritiseVoiceQuality &&
            model.smallerVideoForMany == d.smallerVideoForMany &&
            model.performanceMode == d.performanceMode &&
            model.swarmHelpShare == d.swarmHelpShare &&
            model.swarmKeepFinishedFiles == d.swarmKeepFinishedFiles &&
            model.swarmEnabled == d.swarmEnabled
    }

    fun discoveryModeShortLabel(mode: String): String = when (mode.uppercase()) {
        "GHOST" -> "Ghost"
        "ECO" -> "Eco"
        "BOOST" -> "Boost"
        else -> "Standard"
    }

    fun discoveryModeSubtitle(mode: String): String = when (mode.uppercase()) {
        "GHOST" -> "Browse only — other devices cannot see this phone"
        "ECO" -> "Battery saver — duty-cycled presence and browsing"
        "BOOST" -> "High responsiveness for crowded or flaky networks"
        else -> "Discoverable — nearby devices can find and reach this phone"
    }
}

@Composable
fun FlashSettingsScreen(
    model: FlashSettingsModel,
    onThemeModeSelected: (FlashThemeMode) -> Unit,
    // These three were the only REQUIRED callbacks on this screen while the other twenty-odd
    // default to `{}`. That asymmetry is what made a host's cheapest option a no-op — and a no-op is
    // exactly what makes a row "a control panel connected to nothing". Defaulting them costs
    // nothing and removes the pressure to write one.
    onDynamicAccentChanged: (Boolean) -> Unit = {},
    onHapticsChanged: (Boolean) -> Unit = {},
    /** UI-056: takes effect on the next cold start; the splash of this one has already played. */
    onLaunchAnimationChanged: (Boolean) -> Unit = {},
    onBackgroundTransfersChanged: (Boolean) -> Unit = {},
    onAutoDownloadVoiceChanged: (Boolean) -> Unit = {},
    onAutoDownloadImageChanged: (Boolean) -> Unit = {},
    onAutoDownloadVideoChanged: (Boolean) -> Unit = {},
    onAutoDownloadFileChanged: (Boolean) -> Unit = {},
    onPrioritiseVoiceQualityChanged: (Boolean) -> Unit = {},
    onSmallerVideoForManyChanged: (Boolean) -> Unit = {},
    /** Pins a performance tier, or null to hand the choice back to auto-detect (ERROR-033). */
    onPerformanceModeSelected: (FlashPerformanceMode?) -> Unit = {},
    /** Sets the active discovery presence mode (STANDARD, GHOST, ECO, BOOST). */
    onDiscoveryModeChanged: (String) -> Unit = {},
    /** Toggles Windows Explorer context menu integration. */
    onWindowsContextMenuChanged: (Boolean) -> Unit = {},
    onSwarmHelpShareChanged: (Boolean) -> Unit = {},
    onSwarmKeepFinishedFilesChanged: (Boolean) -> Unit = {},
    onSwarmEnabledChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; content scrolls under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onEditDisplayName: () -> Unit = {},
    onOpenEncryption: () -> Unit = {},
    onOpenTrustedPeers: () -> Unit = {},
    onPickSaveLocation: () -> Unit = {},
    onRefreshStorageUsage: () -> Unit = {},
    onClearReceivedFiles: () -> Unit = {},
    /**
     * Opens the system battery-optimisation prompt (ERROR-031 / D7). Host-side because the
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent needs an Activity.
     */
    onOpenBatterySettings: () -> Unit = {},
    /**
     * Relaunches the app. Null on a host that cannot (the swarm switch then only says it applies after a restart).
     * Offered right after the swarm switch changes, because the engine reads that setting once when it starts.
     */
    onRestartApp: (() -> Unit)? = null,
    /** Shares Flash's own log file(s). Null hides the row (a host with no persistent log). */
    onExportLogs: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val searching = query.isNotBlank()
    var showClearStorageConfirmation by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }

    if (showRestartPrompt && onRestartApp != null) {
        RestartForSwarmDialog(
            onRestart = {
                showRestartPrompt = false
                onRestartApp()
            },
            onLater = { showRestartPrompt = false },
        )
    }

    if (showResetConfirmation) {
        ResetDefaultsDialog(
            onConfirm = {
                showResetConfirmation = false
                val d = FlashSettingsModel()
                // Only calls a setter whose value differs, so an untouched preference is never rewritten.
                if (model.themeMode != d.themeMode) onThemeModeSelected(d.themeMode)
                if (model.dynamicAccent != d.dynamicAccent) onDynamicAccentChanged(d.dynamicAccent)
                if (model.hapticsEnabled != d.hapticsEnabled) onHapticsChanged(d.hapticsEnabled)
                if (model.launchAnimation != d.launchAnimation) onLaunchAnimationChanged(d.launchAnimation)
                if (model.backgroundTransfers != d.backgroundTransfers) onBackgroundTransfersChanged(d.backgroundTransfers)
                if (!model.discoveryMode.equals(d.discoveryMode, ignoreCase = true)) onDiscoveryModeChanged(d.discoveryMode)
                if (model.showWindowsContextMenu && model.windowsContextMenu != d.windowsContextMenu) {
                    onWindowsContextMenuChanged(d.windowsContextMenu)
                }
                if (model.autoDownloadVoice != d.autoDownloadVoice) onAutoDownloadVoiceChanged(d.autoDownloadVoice)
                if (model.autoDownloadImage != d.autoDownloadImage) onAutoDownloadImageChanged(d.autoDownloadImage)
                if (model.autoDownloadVideo != d.autoDownloadVideo) onAutoDownloadVideoChanged(d.autoDownloadVideo)
                if (model.autoDownloadFile != d.autoDownloadFile) onAutoDownloadFileChanged(d.autoDownloadFile)
                if (model.prioritiseVoiceQuality != d.prioritiseVoiceQuality) onPrioritiseVoiceQualityChanged(d.prioritiseVoiceQuality)
                if (model.smallerVideoForMany != d.smallerVideoForMany) onSmallerVideoForManyChanged(d.smallerVideoForMany)
                if (model.performanceMode != d.performanceMode) onPerformanceModeSelected(d.performanceMode)
                if (model.swarmHelpShare != d.swarmHelpShare) onSwarmHelpShareChanged(d.swarmHelpShare)
                if (model.swarmKeepFinishedFiles != d.swarmKeepFinishedFiles) onSwarmKeepFinishedFilesChanged(d.swarmKeepFinishedFiles)
                if (model.swarmEnabled != d.swarmEnabled) {
                    onSwarmEnabledChanged(d.swarmEnabled)
                    if (onRestartApp != null) showRestartPrompt = true
                }
            },
            onDismiss = { showResetConfirmation = false },
        )
    }

    if (showClearStorageConfirmation) {
        ClearReceivedFilesDialog(
            totalBytes = model.receivedFilesBytes,
            onConfirm = {
                showClearStorageConfirmation = false
                onClearReceivedFiles()
            },
            onDismiss = { showClearStorageConfirmation = false },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(
            start = FlashSpacing.space16,
            end = FlashSpacing.space16,
            top = FlashSpacing.space16,
            bottom = FlashSpacing.space16 + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        item(key = "header") {
            StaggerIn(0) {
                FlashText(
                    text = "Settings",
                    style = FlashTheme.typography.headingMedium,
                    color = FlashTheme.colors.textPrimary,
                )
            }
        }
        item(key = "search") {
            StaggerIn(1) { SettingsSearchField(query = query, onQueryChange = { query = it }) }
        }
        if (FlashSettingsMath.matches(query, "Display name", model.displayName, "Name")) {
            item(key = "identity") {
                StaggerIn(1) { IdentityRow(model.displayName, onEditDisplayName) }
            }
        }

        // 1. Appearance & Feedback
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.APPEARANCE)) item(key = "section-appearance") {
            StaggerIn(2) {
                ExpandableSettingsSection(
                    title = "Appearance & Feedback",
                    subtitle = "Theme, dynamic accent, haptics, and animation",
                    iconSpec = FlashIcons.Gallery,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, "Theme")) SettingsCard {
                        ThemeModeSegmented(
                            selected = model.themeMode,
                            onSelected = onThemeModeSelected,
                        )
                    }
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, "Dynamic accent")) SwitchRow(
                        title = "Dynamic accent",
                        subtitle = "Tint Flash with your wallpaper colors where supported",
                        checked = model.dynamicAccent,
                        onCheckedChange = onDynamicAccentChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, "Haptics")) SwitchRow(
                        title = "Haptics",
                        subtitle = "Subtle vibration feedback on actions",
                        checked = model.hapticsEnabled,
                        onCheckedChange = onHapticsChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, "Launch animation")) SwitchRow(
                        title = "Launch animation",
                        subtitle = FlashSettingsMath.launchAnimationSubtitle(model.launchAnimation),
                        checked = model.launchAnimation,
                        onCheckedChange = onLaunchAnimationChanged,
                    )
                }
            }
        }

        // 2. Storage & Downloads
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.STORAGE)) item(key = "section-storage") {
            StaggerIn(3) {
                ExpandableSettingsSection(
                    title = "Storage & Downloads",
                    subtitle = "Save destination, cache breakdown, and auto-download rules",
                    iconSpec = FlashIcons.Download,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Save location")) ValueRow(
                        iconSpec = FlashIcons.Download,
                        title = "Save location",
                        subtitle = model.saveLocationLabel ?: "Choose where received files go",
                        value = null,
                        onClick = onPickSaveLocation,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Received files")) StorageUsageCard(
                        model = model,
                        onRefresh = onRefreshStorageUsage,
                        onClear = { showClearStorageConfirmation = true },
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Auto-download voice")) SwitchRow(
                        title = "Auto-download voice",
                        subtitle = "Accept incoming voice messages automatically",
                        checked = model.autoDownloadVoice,
                        onCheckedChange = onAutoDownloadVoiceChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Auto-download images")) SwitchRow(
                        title = "Auto-download images",
                        subtitle = "Accept incoming images automatically",
                        checked = model.autoDownloadImage,
                        onCheckedChange = onAutoDownloadImageChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Auto-download videos")) SwitchRow(
                        title = "Auto-download videos",
                        subtitle = "Accept incoming videos automatically",
                        checked = model.autoDownloadVideo,
                        onCheckedChange = onAutoDownloadVideoChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, "Auto-download files")) SwitchRow(
                        title = "Auto-download files",
                        subtitle = "Accept incoming files automatically",
                        checked = model.autoDownloadFile,
                        onCheckedChange = onAutoDownloadFileChanged,
                    )
                }
            }
        }

        // 3. Network & Discovery
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.NETWORK)) item(key = "section-network") {
            StaggerIn(4) {
                ExpandableSettingsSection(
                    title = "Network & Discovery",
                    subtitle = "Presence mode, background transfers, and battery settings",
                    iconSpec = FlashIcons.Wifi,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, "Discovery mode")) SettingsCard {
                        DiscoveryModeSegmented(
                            selected = model.discoveryMode,
                            onSelected = onDiscoveryModeChanged,
                        )
                    }
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, "Background transfers")) SwitchRow(
                        title = "Background transfers",
                        subtitle = "Keep sending when you leave the app",
                        checked = model.backgroundTransfers,
                        onCheckedChange = onBackgroundTransfersChanged,
                    )
                    if (model.showWindowsContextMenu) {
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, "File Explorer context menu")) SwitchRow(
                            title = "File Explorer context menu",
                            subtitle = "Right-click any file or folder to send with Flash",
                            checked = model.windowsContextMenu,
                            onCheckedChange = onWindowsContextMenuChanged,
                        )
                    }
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, "Unrestricted battery")) ValueRow(
                        iconSpec = FlashIcons.Bolt,
                        title = "Unrestricted battery",
                        subtitle = FlashSettingsMath.batteryExemptionSubtitle(model.ignoringBatteryOptimizations),
                        value = FlashSettingsMath.batteryExemptionValue(model.ignoringBatteryOptimizations),
                        onClick = onOpenBatterySettings,
                    )
                }
            }
        }

        // 4. Calling & Video
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.CALLING)) item(key = "section-calling") {
            StaggerIn(5) {
                ExpandableSettingsSection(
                    title = "Calling & Video",
                    subtitle = "Bandwidth prioritization and group video resolution",
                    iconSpec = FlashIcons.VideoCall,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.CALLING, "Prioritise voice quality")) SwitchRow(
                        title = "Prioritise voice quality",
                        subtitle = FlashSettingsMath.prioritiseVoiceSubtitle(model.prioritiseVoiceQuality),
                        checked = model.prioritiseVoiceQuality,
                        onCheckedChange = onPrioritiseVoiceQualityChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.CALLING, "Send smaller video in groups")) SwitchRow(
                        title = "Send smaller video in groups",
                        subtitle = FlashSettingsMath.smallerVideoForManySubtitle(model.smallerVideoForMany),
                        checked = model.smallerVideoForMany,
                        onCheckedChange = onSmallerVideoForManyChanged,
                    )
                }
            }
        }

        // 5. Security & Trusted Devices
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.SECURITY)) item(key = "section-security") {
            StaggerIn(6) {
                ExpandableSettingsSection(
                    title = "Security & Trusted Devices",
                    subtitle = "Cryptographic protection and verified peers",
                    iconSpec = FlashIcons.Verified,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SECURITY, "Encryption")) ValueRow(
                        iconSpec = FlashIcons.Encryption,
                        title = "Encryption",
                        subtitle = "How Flash protects your transfers",
                        value = null,
                        onClick = onOpenEncryption,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SECURITY, "Trusted peers")) ValueRow(
                        iconSpec = FlashIcons.Verified,
                        title = "Trusted peers",
                        subtitle = FlashSettingsMath.trustedPeersSubtitle(model.trustedPeerCount),
                        value = null,
                        onClick = onOpenTrustedPeers,
                    )
                }
            }
        }

        // 6. Performance & Engine Tuning
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.PERFORMANCE)) item(key = "section-performance") {
            StaggerIn(7) {
                ExpandableSettingsSection(
                    title = "Performance Tuning",
                    subtitle = "Hardware capability tier and UI motion budget",
                    iconSpec = FlashIcons.Connection,
                    initiallyExpanded = false,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.PERFORMANCE, "Performance mode")) SettingsCard {
                        PerformanceModeSegmented(
                            selected = model.performanceMode,
                            detected = model.detectedPerformanceMode,
                            onSelected = onPerformanceModeSelected,
                        )
                    }
                }
            }
        }

        // 7. Group File Sharing (Swarm) & Diagnostics
        if (FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.SWARM)) item(key = "section-swarm") {
            StaggerIn(8) {
                ExpandableSettingsSection(
                    title = "Group File Sharing & Diagnostics",
                    subtitle = "Swarm transfer sharing and log export",
                    iconSpec = FlashIcons.Group,
                    initiallyExpanded = false,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SWARM, "Help share group files")) SwitchRow(
                        title = "Help share group files",
                        subtitle = "Share received file pieces with other group members on your local network",
                        checked = model.swarmHelpShare,
                        onCheckedChange = onSwarmHelpShareChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SWARM, "Keep finished files available for others")) SwitchRow(
                        title = "Keep finished files available for others",
                        subtitle = "Keep completed group files available to help members who come online later",
                        checked = model.swarmKeepFinishedFiles,
                        onCheckedChange = onSwarmKeepFinishedFilesChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SWARM, "Group file sharing (swarm, experimental)")) SwitchRow(
                        title = "Group file sharing (swarm, experimental)",
                        subtitle = "Enable multi-device cooperative transfers in groups. Applies after restart",
                        checked = model.swarmEnabled,
                        onCheckedChange = {
                            onSwarmEnabledChanged(it)
                            if (onRestartApp != null) showRestartPrompt = true
                        },
                    )
                    if (onExportLogs != null) {
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SWARM, "Export logs")) ValueRow(
                            iconSpec = FlashIcons.Share,
                            title = "Export logs",
                            subtitle = "Share Flash's recent log to help find a problem. It holds no message text or keys",
                            value = null,
                            onClick = onExportLogs,
                        )
                    }
                }
            }
        }

        val anySection = FlashSettingsMath.Section.entries.any { FlashSettingsMath.sectionVisible(query, it) }
        val showAbout = FlashSettingsMath.matches(query, "About", "Version", "Protocol", "Device id")
        if (searching && !anySection && !showAbout &&
            !FlashSettingsMath.matches(query, "Display name", model.displayName, "Name")
        ) {
            item(key = "no-results") {
                FlashText(
                    text = "No settings match \"${query.trim()}\"",
                    style = FlashTheme.typography.bodyDefault,
                    color = FlashTheme.colors.textTertiary,
                    modifier = Modifier.padding(vertical = FlashSpacing.space16),
                )
            }
        }
        if (!searching) {
            item(key = "reset-defaults") {
                StaggerIn(9) {
                    ResetDefaultsRow(
                        enabled = !FlashSettingsMath.isAtDefaults(model),
                        onClick = { showResetConfirmation = true },
                    )
                }
            }
        }
        if (showAbout) item(key = "about") { StaggerIn(10) { AboutCard(model) } }
    }
}

@Composable
private fun ExpandableSettingsSection(
    title: String,
    iconSpec: FlashIconSpec,
    subtitle: String? = null,
    initiallyExpanded: Boolean = true,
    /** While a search is active every section opens so a match is never hidden behind a collapsed card. */
    forceExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var userExpanded by remember { mutableStateOf(initiallyExpanded) }
    val expanded = userExpanded || forceExpanded
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motion.reduceMotion) androidx.compose.animation.core.snap() else motion.tweenNormalSpec(),
        label = "expandChevronRotation",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .background(colors.backgroundSurface)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = colors.borderSubtle.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(FlashShapes.radius12),
                )
                .clickable {
                    haptics(FlashHaptic.Tick)
                    userExpanded = !userExpanded
                }
                .padding(FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = iconSpec,
                    tint = colors.accentPrimary,
                    size = FlashDimensions.iconSm,
                )
            }
            Spacer(Modifier.width(FlashSpacing.space12))
            Column(modifier = Modifier.weight(1f)) {
                FlashText(
                    text = title,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                )
                if (subtitle != null) {
                    FlashText(
                        text = subtitle,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
            }
            FlashIcon(
                icon = FlashIcons.ChevronDown,
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                content()
            }
        }
    }
}

/**
 * UI-050 entrance: alpha + a short rise driven by the shared stagger progress, read inside
 * `graphicsLayer` so the reveal is a render pass and never recomposes the row.
 */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val progress = FlashTheme.motion.rememberStaggerProgress(index = index, key = Unit)
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * StaggerRise.toPx()
        },
    ) { content() }
}

private val StaggerRise = 12.dp

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(FlashTheme.colors.backgroundSurface)
            .padding(FlashSpacing.space12),
    ) { content() }
}

@Composable
private fun IdentityRow(name: String, onEdit: () -> Unit) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = "Edit display name",
                onClick = onEdit,
            )
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = "Display name $name, edit button"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FlashDimensions.avatarLg)
                .clip(CircleShape)
                .background(colors.accentPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = name.take(1).uppercase().ifBlank { "?" },
                style = FlashTheme.typography.headingSmall,
                color = colors.accentPrimary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(text = name, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary)
            FlashText(
                text = "Visible to nearby devices",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        FlashIcon(
            icon = FlashIcons.Edit,
            tint = colors.textSecondary,
            size = FlashDimensions.iconSm,
            contentDescription = null,
        )
    }
}

@Composable
private fun ThemeModeSegmented(
    selected: FlashThemeMode,
    onSelected: (FlashThemeMode) -> Unit,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val modes = FlashThemeMode.entries
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(SegmentTrackHeight)
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurfaceSubtle)
            .selectableGroup(),
    ) {
        val segmentWidth = maxWidth / modes.size
        // Read the animated Dp inside offset { } so the slide is a placement pass only —
        // no recomposition of the three labels on every frame.
        val indicatorOffset = animateDpAsState(
            targetValue = FlashSpacing.space4 + segmentWidth * selectedIndex,
            animationSpec = motion.springSnappySpec(),
            label = "flashThemeSegment",
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        indicatorOffset.value.roundToPx(),
                        FlashSpacing.space4.roundToPx(),
                    )
                }
                .width(segmentWidth - FlashSpacing.space8)
                .height(SegmentTrackHeight - FlashSpacing.space8)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.accentPrimary),
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val label = FlashSettingsMath.themeModeLabel(mode)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = mode == selected,
                            role = Role.RadioButton,
                            onClick = {
                                if (mode != selected) {
                                    haptics(FlashHaptic.Tick)
                                    onSelected(mode)
                                }
                            },
                        )
                        .semantics { contentDescription = "$label theme" },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (mode == selected) colors.textOnAccent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

private val SegmentTrackHeight = 40.dp

/**
 * Auto / Low / Medium / High tier picker (ERROR-033).
 *
 * Four segments rather than three because "Auto" is not a fourth tier — it is the absence of a pin,
 * and it has to be reachable again after the user has pinned something. The subtitle underneath
 * carries the consequences, since the labels themselves say nothing about what changes.
 *
 * No sliding indicator: the tiers this control exists for are the ones whose devices cannot afford
 * one, and an animated selection on a handset picking "Low" because it drops frames would be a poor
 * joke. The selected segment is painted directly.
 */
@Composable
private fun PerformanceModeSegmented(
    selected: FlashPerformanceMode?,
    detected: FlashPerformanceMode,
    onSelected: (FlashPerformanceMode?) -> Unit,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    val options: List<FlashPerformanceMode?> =
        listOf(null, FlashPerformanceMode.LOW, FlashPerformanceMode.MEDIUM, FlashPerformanceMode.HIGH)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SegmentTrackHeight)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.backgroundSurfaceSubtle)
                .selectableGroup(),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val label = FlashSettingsMath.performanceModeLabel(option)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(FlashSpacing.space4)
                        .clip(FlashShapes.bubbleGrouped)
                        .background(if (isSelected) colors.accentPrimary else colors.backgroundSurfaceSubtle)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                if (!isSelected) {
                                    haptics(FlashHaptic.Tick)
                                    onSelected(option)
                                }
                            },
                        )
                        .semantics { contentDescription = "$label performance" },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (isSelected) colors.textOnAccent else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashText(
            text = FlashSettingsMath.performanceModeSubtitle(pinned = selected, detected = detected),
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun DiscoveryModeSegmented(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    val options = listOf("STANDARD", "GHOST", "ECO", "BOOST")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SegmentTrackHeight)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.backgroundSurfaceSubtle)
                .selectableGroup(),
        ) {
            options.forEach { option ->
                val isSelected = option.equals(selected, ignoreCase = true)
                val label = FlashSettingsMath.discoveryModeShortLabel(option)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(FlashSpacing.space4)
                        .clip(FlashShapes.bubbleGrouped)
                        .background(if (isSelected) colors.accentPrimary else colors.backgroundSurfaceSubtle)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                if (!isSelected) {
                                    haptics(FlashHaptic.Tick)
                                    onSelected(option)
                                }
                            },
                        )
                        .semantics { contentDescription = "$label discovery mode" },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (isSelected) colors.textOnAccent else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashText(
            text = FlashSettingsMath.discoveryModeSubtitle(selected),
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            // Role.Switch + toggleable gives TalkBack the real on/off state and the
            // "double-tap to toggle" affordance; a clickable + contentDescription pair
            // announced the state only at first read and never on change.
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { next ->
                    haptics(FlashHaptic.Tick)
                    onCheckedChange(next)
                },
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = if (checked) "on" else "off"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlashText(text = title, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        FlashSwitch(checked = checked)
    }
}

/** Flash-drawn switch: track + spring thumb, no Material Switch. */
@Composable
private fun FlashSwitch(checked: Boolean) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) SwitchThumbTravel else FlashSpacing.space2,
        animationSpec = motion.springSnappySpec(),
        label = "flashSwitchThumb",
    )
    val trackTint by animateColorAsState(
        targetValue = if (checked) colors.accentPrimary else colors.borderStrong,
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchTrack",
    )
    val thumbTint by animateColorAsState(
        targetValue = if (checked) colors.textOnAccent else colors.backgroundSurface,
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchThumbTint",
    )
    Box(
        Modifier
            .width(SwitchTrackWidth)
            .height(SwitchTrackHeight)
            .clip(FlashShapes.bubbleGrouped)
            .background(trackTint),
    ) {
        Box(
            Modifier
                .offset {
                    IntOffset(thumbOffset.value.roundToPx(), FlashSpacing.space2.roundToPx())
                }
                .size(SwitchTrackHeight - FlashSpacing.space4)
                .clip(CircleShape)
                .background(thumbTint),
        )
    }
}

private val SwitchTrackWidth = 44.dp
private val SwitchTrackHeight = 24.dp
private val SwitchThumbTravel = SwitchTrackWidth - SwitchTrackHeight + FlashSpacing.space2

@Composable
private fun ValueRow(
    iconSpec: com.transfer.flash.ui.icons.FlashIconSpec,
    title: String,
    subtitle: String,
    value: String?,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, subtitle, value).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = iconSpec,
            tint = colors.textSecondary,
            size = FlashDimensions.iconMd,
            contentDescription = null,
        )
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(text = title, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        if (value != null) {
            FlashText(
                text = value,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun StorageUsageCard(
    model: FlashSettingsModel,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = FlashTheme.colors
    val clearEnabled = FlashStorageMath.canClearReceivedFiles(
        totalBytes = model.receivedFilesBytes,
        isLoading = model.storageUsageLoading,
        hasError = model.storageUsageError,
        isClearing = model.clearingReceivedFiles,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlashIcon(
                icon = FlashIcons.Archive,
                tint = colors.textSecondary,
                size = FlashDimensions.iconMd,
                contentDescription = null,
            )
            Spacer(Modifier.width(FlashSpacing.space12))
            Column(Modifier.weight(1f)) {
                FlashText(
                    text = "Received files",
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = FlashStorageMath.usageSummary(
                        totalBytes = model.receivedFilesBytes,
                        isLoading = model.storageUsageLoading,
                        hasError = model.storageUsageError,
                    ),
                    style = FlashTheme.typography.metadataDefault,
                    color = if (model.storageUsageError) colors.textError else colors.textTertiary,
                )
            }
            if (model.storageUsageLoading || model.clearingReceivedFiles) {
                CircularProgressIndicator(
                    modifier = Modifier.size(FlashDimensions.iconMd),
                    color = colors.accentPrimary,
                    strokeWidth = 2.dp,
                )
            }
        }
        FlashText(
            text = "Per-conversation storage details are not available yet.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.End),
        ) {
            TextButton(
                onClick = onRefresh,
                enabled = !model.storageUsageLoading && !model.clearingReceivedFiles,
                modifier = Modifier.semantics {
                    contentDescription = if (model.storageUsageLoading) {
                        "Refreshing received files storage usage"
                    } else {
                        "Refresh received files storage usage"
                    }
                },
            ) {
                Text("Refresh", color = colors.accentPrimary)
            }
            TextButton(
                onClick = onClear,
                enabled = clearEnabled,
                modifier = Modifier.semantics {
                    contentDescription = if (clearEnabled) {
                        "Clear all received files"
                    } else {
                        "Clear received files unavailable"
                    }
                },
            ) {
                Text("Clear received files", color = if (clearEnabled) colors.textError else colors.textTertiary)
            }
        }
    }
}

@Composable
private fun ClearReceivedFilesDialog(
    totalBytes: Long?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val amount = totalBytes?.let(FlashStorageMath::formatBytes) ?: "these files"
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Clear received files?",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            FlashText(
                text = "Permanently delete $amount from Flash's received-files storage? " +
                    "This clears all received files and cannot be undone.",
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete files", color = colors.textError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.composerInput)
            .background(colors.composerInputBackground)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle,
                shape = FlashShapes.composerInput,
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).semantics { contentDescription = "Search settings" }) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        FlashText(
                            text = "Search settings",
                            style = typography.bodyDefault,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = { onQueryChange("") })
                    .semantics { role = Role.Button; contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(icon = FlashIcons.Close, size = FlashDimensions.iconSm, tint = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun ResetDefaultsRow(enabled: Boolean, onClick: () -> Unit) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = if (enabled) "Reset settings to defaults" else "Settings are already at their defaults"
            },
        ) {
            Text("Reset to defaults", color = if (enabled) colors.textError else colors.textTertiary)
        }
    }
}

@Composable
private fun ResetDefaultsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Reset to defaults?",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            FlashText(
                text = "Appearance, downloads, network, calling, performance and group sharing options go back to " +
                    "how Flash first shipped. Your name, save location and trusted devices stay as they are.",
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset", color = colors.textError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun RestartForSwarmDialog(onRestart: () -> Unit, onLater: () -> Unit) {
    val colors = FlashTheme.colors
    FlashConfirmHost(
        onDismiss = onLater,
        containerColor = colors.backgroundSurface,
        title = {
            FlashText(
                text = "Restart Flash?",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
        },
        text = {
            FlashText(
                text = "Group file sharing changes when Flash starts. Restart now to apply it; " +
                    "transfers and calls in progress will stop.",
                style = FlashTheme.typography.bodyDefault,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onRestart) {
                Text("Restart now", color = colors.textPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("Later", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun AboutCard(model: FlashSettingsModel) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        AboutLine("Version", model.appVersion)
        AboutLine("Protocol", model.protocolVersion)
        AboutLine("Device id", model.deviceIdShort.take(8))
        FlashText(
            text = "Flash keeps your files on your network — no cloud, no accounts.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val colors = FlashTheme.colors
    Row {
        FlashText(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = colors.textTertiary,
            modifier = Modifier.width(FlashSpacing.space40 * 2),
        )
        FlashText(
            text = value,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textTertiary,
        modifier = Modifier.padding(top = FlashSpacing.space8),
    )
}
