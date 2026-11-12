@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
     * "Keep listening in the background"; tapping the row opens the system prompt.
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
    /** Group file sharing via swarm transfer (SW-8). Default TRUE since 2026-10-19 (owner decision O-8). */
    val swarmEnabled: Boolean = true,
    /** UI-056 / ADR-080: the handwritten launch animation on a cold start. Default TRUE. */
    val launchAnimation: Boolean = true,
    val isEnterpriseEnrolled: Boolean = false,
    val enterpriseHubUrl: String? = null,
    val enterpriseDepartment: String? = null,
    val enterpriseRole: String? = null,
    val enterpriseRootFingerprint: String? = null,
    /** Live state of the organisation hub link (CLI-07). Null on a host that has no hub. */
    val hubStatus: com.transfer.flash.core.enterprise.HubStatus? = null,
    /** The user's opt-in to let the hub carry readable chat text (ADR-121). Off by default; never reset by "Reset to defaults". */
    val hubContentViaHub: Boolean = false,
    /** ADR-126: sealing key state and dropped-message counts for the connection details. Null on a host without a hub. */
    val hubSealing: FlashHubSealingUi? = null,
    /** Groups that were linked to this organisation before the last unenrol, restorable after enrolling again. */
    val hubRelinkableGroups: Int = 0,
    /** Plan B Phase B3: The evaluated organisation colleague trust state (null if not enrolled or unsupported). */
    val orgTrustState: FlashHubStatusMath.OrgTrustState? = null,
    /** Radio gateway enabled status (Plan R §1, §6). */
    val radioGatewayEnabled: Boolean = false,
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
            "Your video goes at 360p to everyone. Uses less processor"
        } else {
            "Your video goes at 540p (360p on lower-end devices) to everyone in a group call"
        }

    /** Segment labels for the performance picker. Null is the Auto segment (ERROR-033). */
    fun performanceModeLabel(mode: FlashPerformanceMode?): String = when (mode) {
        null -> "Auto"
        FlashPerformanceMode.ULTRA_LOW -> "Ultra Low"
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
        val voiceSummary = when {
            effective.voice.ptimeMs >= 60 && effective.voice.maxBitrateBps <= 12_000 -> "ultra-light voice"
            effective.voice.ptimeMs >= 60 -> "power-saving voice"
            effective.voice.maxBitrateBps >= 30_000 -> "full-quality voice"
            else -> "balanced voice"
        }
        val parts = buildList {
            if (pinned == null) add("Matched to this device: ${performanceModeLabel(effective)}")
            add("video ${effective.video.label}")
            add(voiceSummary)
            if (effective.reduceMotion) add("animations off")
        }
        return parts.joinToString(" · ")
    }

    /**
     * The searchable sections of the page. [rows] lists the baseline keywords for each section;
     * [sectionVisible] filters conditional rows via the same row terms used by [rowVisible] so
     * searching never opens an empty section card.
     */
    enum class Section(val title: String, val subtitle: String, val rows: List<String>) {
        APPEARANCE(
            "Appearance & Feedback",
            "Theme, accent, and launch animation",
            listOf("Theme", "System", "Light", "Dark", "Dynamic accent", "Haptics", "Launch animation"),
        ),
        STORAGE(
            "Storage & Downloads",
            "Received files and auto-download rules",
            listOf(
                "Save location", "Received files", "Clear received files", "Auto-download voice",
                "Auto-download images", "Auto-download videos", "Auto-download files",
            ),
        ),
        NETWORK(
            "Network & Background",
            "Background behaviour and system controls",
            listOf(
                "Keep listening in the background", "Close to tray", "Background transfers",
                "File Explorer context menu", "Unrestricted battery",
            ),
        ),
        CALLING(
            "Calling & Video",
            "Bandwidth prioritization and group video resolution",
            listOf("Prioritise voice quality", "Send smaller video in groups"),
        ),
        SECURITY(
            "Security & Trusted Devices",
            "Cryptographic protection and verified peers",
            listOf("Encryption", "Trusted peers"),
        ),
        ADVANCED(
            "Advanced & Power",
            "Discovery mode, performance, and group file sharing",
            listOf(
                "Discovery mode", "Standard", "Ghost", "Eco", "Boost",
                "Radio gateway (beta)",
                "Performance mode", "Auto", "Ultra Low", "Low", "Medium", "High",
                "Group file sharing (swarm, experimental)", "Swarm",
                "Help share group files", "Keep finished files available for others",
                "Export logs",
            ),
        ),
        ENTERPRISE(
            "Enterprise & Organization",
            "Corporate hub relay and account",
            listOf(
                "Organization hub", "Colleague trust", "Department", "Directory", "Role",
                "Enrolment", "Root fingerprint", "Disconnect", "Messages through the hub",
                "Connection", "Status", "Approve admin requests",
            ),
        ),
    }

    internal val themeRowTerms = listOf("Theme", "System", "Light", "Dark", "Appearance")
    internal val dynamicAccentRowTerms = listOf("Dynamic accent", "Tint Flash with your wallpaper colors where supported", "Wallpaper")
    internal val hapticsRowTerms = listOf("Haptics", "Subtle vibration feedback on actions", "Vibration")
    internal val launchAnimationRowTerms = listOf(
        "Launch animation",
        "Splash",
        "Writes the Flash name when the app starts",
        "Flash opens straight away",
    )

    internal fun saveLocationRowTerms(saveLocationLabel: String?): List<String> =
        listOfNotNull("Save location", "Choose where received files go", "Folder", saveLocationLabel?.takeIf { it.isNotBlank() })

    internal val receivedFilesRowTerms = listOf("Received files", "Clear received files", "Storage usage", "Refresh", "Delete files", "Cache")
    internal val autoVoiceRowTerms = listOf("Auto-download voice", "Accept incoming voice messages automatically")
    internal val autoImageRowTerms = listOf("Auto-download images", "Accept incoming images automatically")
    internal val autoVideoRowTerms = listOf("Auto-download videos", "Accept incoming videos automatically")
    internal val autoFileRowTerms = listOf("Auto-download files", "Accept incoming files automatically")

    internal fun backgroundRowTerms(backgroundListenTitle: String, backgroundListenSubtitle: String): List<String> =
        listOf(backgroundListenTitle, backgroundListenSubtitle, "Background transfers")

    internal val windowsMenuRowTerms = listOf("File Explorer context menu", "Right-click any file or folder to send with Flash")
    internal val batteryRowTerms = listOf("Unrestricted battery", "Battery optimization", "Battery optimisation", "Android may disconnect Flash when the screen is off")

    internal val prioritiseVoiceRowTerms = listOf("Prioritise voice quality", "Prioritize voice quality", "Video quality drops first")
    internal val smallerVideoRowTerms = listOf("Send smaller video in groups", "360p", "540p", "Group call")

    internal val encryptionRowTerms = listOf("Encryption", "How Flash protects your transfers")
    internal val trustedPeersRowTerms = listOf("Trusted peers", "Verified devices", "devices verified", "No verified devices yet")

    internal val discoveryRowTerms = listOf("Discovery mode", "Standard", "Ghost", "Eco", "Boost", "Presence mode", "Discoverable", "Browse only", "Battery saver")

    internal fun radioGatewayRowTerms(): List<String> = listOf(
        "Radio gateway (beta)",
        "Radio",
        "Gateway",
        com.transfer.flash.ui.radio.FlashRadioCopy.SETTINGS_ROW_TITLE,
        com.transfer.flash.ui.radio.FlashRadioCopy.SETTINGS_ROW_SUBTITLE,
    )

    internal val performanceRowTerms = listOf("Performance mode", "Performance tuning", "Auto", "Ultra Low", "Low", "Medium", "High", "Hardware capability")
    internal val swarmMasterRowTerms = listOf("Group file sharing (swarm, experimental)", "Group file sharing", "Swarm", "Cooperative transfers")
    internal val swarmHelpShareRowTerms = listOf("Help share group files", "Share received file pieces")
    internal val swarmKeepFinishedRowTerms = listOf("Keep finished files available for others", "Keep completed group files")
    internal val exportLogsRowTerms = listOf("Export logs", "Diagnostics", "Share Flash's recent log")

    internal fun enterpriseHubRowTerms(
        enrolled: Boolean,
        hubName: String? = null,
        hubUrl: String? = null,
        rootFingerprint: String? = null,
    ): List<String> = if (enrolled) {
        listOfNotNull(
            "Organization hub", "Organisation hub", "Status", "Connection",
            "Root fingerprint", "Organisation key",
            hubName?.takeIf { it.isNotBlank() },
            hubUrl?.takeIf { it.isNotBlank() },
            rootFingerprint?.takeIf { it.isNotBlank() },
        )
    } else {
        listOf("Organization hub", "Organisation hub", "Enrol", "Enrolment", "Enrollment", "Invitation link", "flash://enroll")
    }

    internal val enterpriseTrustRowTerms = listOf("Colleague trust")
    internal val enterpriseRelinkRowTerms = listOf("Restore", "Linked groups")
    internal val enterpriseContentRowTerms = listOf("Messages through the hub")
    internal fun enterpriseDepartmentRowTerms(department: String? = null): List<String> =
        listOfNotNull("Department", "Directory", "Authorized organization directory scope", department?.takeIf { it.isNotBlank() })

    internal fun enterpriseRoleRowTerms(role: String? = null): List<String> =
        listOfNotNull("Role", "Delegated permission level inside company", role?.takeIf { it.isNotBlank() })

    internal val enterpriseApprovalsRowTerms = listOf("Approve admin requests", "Approver")
    internal val enterpriseDisconnectRowTerms = listOf("Disconnect from Organization", "Disconnect", "Remove corporate certificates")

    /** True when [query] is blank or case-insensitively contained in any of [texts]. */
    fun matches(query: String, vararg texts: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return texts.any { it.contains(q, ignoreCase = true) }
    }

    /** Whether the section card shows at all for [query]: at least one row is visible and either the section or a visible row matches. */
    fun sectionVisible(
        query: String,
        section: Section,
        showRadioGateway: Boolean = true,
        showWindowsContextMenu: Boolean = true,
        showHaptics: Boolean = true,
        showBattery: Boolean = true,
        showSaveLocation: Boolean = true,
        showEncryption: Boolean = true,
        showTrustedPeers: Boolean = true,
        showExportLogs: Boolean = true,
        isEnterpriseEnrolled: Boolean = true,
        showColleagueTrust: Boolean = isEnterpriseEnrolled,
        showRelinkOffer: Boolean = false,
        showContentViaHub: Boolean = isEnterpriseEnrolled,
        showApprovals: Boolean = isEnterpriseEnrolled,
        saveLocationLabel: String? = null,
        backgroundListenTitle: String = "Keep listening in the background",
        backgroundListenSubtitle: String = "Stay reachable with nobody connected; the system may still stop it",
        enterpriseHubName: String? = null,
        enterpriseHubUrl: String? = null,
        enterpriseRootFingerprint: String? = null,
        enterpriseDepartment: String? = null,
        enterpriseRole: String? = null,
        bannerTerms: List<String> = emptyList(),
    ): Boolean {
        val rowTermGroups: List<List<String>> = when (section) {
            Section.APPEARANCE -> buildList {
                add(themeRowTerms)
                add(dynamicAccentRowTerms)
                if (showHaptics) add(hapticsRowTerms)
                add(launchAnimationRowTerms)
            }
            Section.STORAGE -> buildList {
                if (showSaveLocation) add(saveLocationRowTerms(saveLocationLabel))
                add(receivedFilesRowTerms)
                add(autoVoiceRowTerms)
                add(autoImageRowTerms)
                add(autoVideoRowTerms)
                add(autoFileRowTerms)
            }
            Section.NETWORK -> buildList {
                add(backgroundRowTerms(backgroundListenTitle, backgroundListenSubtitle))
                if (showWindowsContextMenu) add(windowsMenuRowTerms)
                if (showBattery) add(batteryRowTerms)
            }
            Section.CALLING -> listOf(prioritiseVoiceRowTerms, smallerVideoRowTerms)
            Section.SECURITY -> buildList {
                if (showEncryption) add(encryptionRowTerms)
                if (showTrustedPeers) add(trustedPeersRowTerms)
            }
            Section.ADVANCED -> buildList {
                add(discoveryRowTerms)
                if (showRadioGateway) add(radioGatewayRowTerms())
                add(performanceRowTerms)
                add(swarmMasterRowTerms)
                add(swarmHelpShareRowTerms)
                add(swarmKeepFinishedRowTerms)
                if (showExportLogs) add(exportLogsRowTerms)
            }
            Section.ENTERPRISE -> buildList {
                if (bannerTerms.isNotEmpty()) add(bannerTerms)
                if (isEnterpriseEnrolled) {
                    add(enterpriseHubRowTerms(true, enterpriseHubName, enterpriseHubUrl, enterpriseRootFingerprint))
                    if (showColleagueTrust) add(enterpriseTrustRowTerms)
                    if (showRelinkOffer) add(enterpriseRelinkRowTerms)
                    if (showContentViaHub) add(enterpriseContentRowTerms)
                    add(enterpriseDepartmentRowTerms(enterpriseDepartment))
                    add(enterpriseRoleRowTerms(enterpriseRole))
                    if (showApprovals) add(enterpriseApprovalsRowTerms)
                    add(enterpriseDisconnectRowTerms)
                } else {
                    add(enterpriseHubRowTerms(false))
                }
            }
        }
        if (rowTermGroups.isEmpty()) return false
        if (matches(query, section.title, section.subtitle)) return true
        return rowTermGroups.any { terms -> matches(query, *terms.toTypedArray()) }
    }

    /** Whether one row shows: everything in a section whose own title matches, else just the matching rows. */
    fun rowVisible(query: String, section: Section, vararg rowTexts: String): Boolean =
        matches(query, section.title, section.subtitle) || matches(query, *rowTexts)

    /**
     * True when every preference that "Reset to defaults" would change is already at its default.
     * Identity (display name), save location, trusted peers and read-only values are never reset.
     * [FlashSettingsModel.windowsContextMenu] only counts where the host shows that row.
     */
    fun isAtDefaults(model: FlashSettingsModel, showHaptics: Boolean = true): Boolean {
        val d = FlashSettingsModel()
        return model.themeMode == d.themeMode &&
            model.dynamicAccent == d.dynamicAccent &&
            (!showHaptics || model.hapticsEnabled == d.hapticsEnabled) &&
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
    onDynamicAccentChanged: (Boolean) -> Unit = {},
    onHapticsChanged: ((Boolean) -> Unit)? = null,
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
    onOpenEncryption: (() -> Unit)? = null,
    onOpenTrustedPeers: (() -> Unit)? = null,
    onPickSaveLocation: (() -> Unit)? = null,
    onRefreshStorageUsage: () -> Unit = {},
    onClearReceivedFiles: () -> Unit = {},
    /**
     * Opens the system battery-optimisation prompt (ERROR-031 / D7). Host-side because the
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent needs an Activity. Null on non-Android hosts.
     */
    onOpenBatterySettings: (() -> Unit)? = null,
    /**
     * Relaunches the app. Null on a host that cannot (the swarm switch then only says it applies after a restart).
     * Offered right after the swarm switch changes, because the engine reads that setting once when it starts.
     */
    onRestartApp: (() -> Unit)? = null,
    /** Shares Flash's own log file(s). Null hides the row (a host with no persistent log). */
    onExportLogs: (() -> Unit)? = null,
    /** Enrolls device into an enterprise hub using a flash://enroll link. */
    onEnrollDevice: ((String) -> Unit)? = null,
    /** Disconnects/un-enrolls device from enterprise hub. */
    onDisconnectEnterprise: (() -> Unit)? = null,
    /**
     * Lists the console actions waiting for this phone to approve (ADR-131, FO-14). Null on a host without a hub; the result is
     * null when the hub could not be asked.
     */
    onLoadApprovals: (suspend () -> List<com.transfer.flash.core.enterprise.EnterpriseApproval>?)? = null,
    /** Approves one listed request with this device's key; true when the hub accepted it. */
    onApprove: (suspend (com.transfer.flash.core.enterprise.EnterpriseApproval) -> Boolean)? = null,
    /** Connects to the hub now ("Retry now" on a banner). */
    onRetryHub: (() -> Unit)? = null,
    /** Forgets the "your organisation removed this device" notice. */
    onDismissHubRevoked: (() -> Unit)? = null,
    /** Turns readable chat content through the hub on or off (the disclosure is shown here before it turns on). */
    onSetHubContentViaHub: ((Boolean) -> Unit)? = null,
    /** Links the groups remembered from the previous enrolment again. */
    onRestoreHubGroups: (() -> Unit)? = null,
    /** Throws the remembered groups away. */
    onForgetHubGroups: (() -> Unit)? = null,
    /** Live indicator whether enrollment is in progress. */
    isEnrollingDevice: Boolean = false,
    /** Error message to display in the enrollment dialog. */
    enrollDeviceError: String? = null,
    onOpenRadioGateway: (() -> Unit)? = null,
    radioGatewayController: com.transfer.flash.ui.radio.RadioGatewayController? = null,
    /**
     * The row backed by [FlashSettingsModel.backgroundTransfers]. Android's wording names what the switch actually does
     * there — hold the foreground service up while the mesh is idle (ADR-232). Windows wires the same model field to
     * close-to-tray, where "the system may still stop it" describes nothing, so the host passes its own words.
     */
    backgroundListenTitle: String = "Keep listening in the background",
    backgroundListenSubtitle: String = "Stay reachable with nobody connected; the system may still stop it",
    /** Visibility flags; null callbacks hide or render read-only by default. */
    showHapticsToggle: Boolean = onHapticsChanged != null,
    showBatteryOptimization: Boolean = onOpenBatterySettings != null,
    showSaveLocation: Boolean = onPickSaveLocation != null || !model.saveLocationLabel.isNullOrBlank(),
    showEncryption: Boolean = onOpenEncryption != null,
    showTrustedPeers: Boolean = onOpenTrustedPeers != null,
) {
    var query by remember { mutableStateOf("") }
    var showRadioGateway by remember { mutableStateOf(false) }
    // The entry exists only when a host supplied a real controller (or its own opener): a build without a
    // radio backend must never show a screen that plays fake states. Fakes live in demo entry points only.
    val radioGatewayAvailable = com.transfer.flash.ui.radio.FlashRadioMath.isGatewayEntryAvailable(
        hasController = radioGatewayController != null,
        hasOpenHandler = onOpenRadioGateway != null,
    )
    if (showRadioGateway && radioGatewayController != null) {
        com.transfer.flash.ui.radio.FlashRadioGatewayScreen(
            controller = radioGatewayController,
            onBack = { showRadioGateway = false },
            bottomInset = bottomInset,
        )
        return
    }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val searching = query.isNotBlank()
    var showClearStorageConfirmation by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var showEnrollDialog by remember { mutableStateOf(false) }
    var showDisconnectEnterpriseConfirmation by remember { mutableStateOf(false) }
    var showApprovals by remember { mutableStateOf(false) }
    var showHubDetails by remember { mutableStateOf(false) }
    var showHubContentDisclosure by remember { mutableStateOf(false) }
    val hubStatus = model.hubStatus
    val hubNeedsClock = hubStatus != null && hubStatus.state != com.transfer.flash.core.enterprise.HubLinkState.NOT_ENROLLED
    val hubNow by rememberHubNow(hubNeedsClock)

    androidx.compose.runtime.LaunchedEffect(model.isEnterpriseEnrolled) {
        if (model.isEnterpriseEnrolled) {
            showEnrollDialog = false
        }
    }

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
                if (showHapticsToggle && onHapticsChanged != null && model.hapticsEnabled != d.hapticsEnabled) {
                    onHapticsChanged(d.hapticsEnabled)
                }
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

    if (showEnrollDialog) {
        FlashEnrollDialog(
            onDismiss = { showEnrollDialog = false },
            onEnroll = { link ->
                onEnrollDevice?.invoke(link)
            },
            isEnrolling = isEnrollingDevice,
            enrollError = enrollDeviceError,
        )
    }

    if (showHubDetails && hubStatus != null) {
        FlashHubDetailDialog(status = hubStatus, nowMs = hubNow, onClose = { showHubDetails = false }, sealing = model.hubSealing)
    }
    if (showHubContentDisclosure) {
        FlashHubContentDisclosureDialog(
            onConfirm = {
                showHubContentDisclosure = false
                onSetHubContentViaHub?.invoke(true)
            },
            onCancel = { showHubContentDisclosure = false },
        )
    }

    if (showApprovals && onLoadApprovals != null && onApprove != null) {
        FlashApprovalsDialog(load = onLoadApprovals, approve = onApprove, onDismiss = { showApprovals = false })
    }

    if (showDisconnectEnterpriseConfirmation && onDisconnectEnterprise != null) {
        val colors = FlashTheme.colors
        FlashConfirmHost(
            onDismiss = { showDisconnectEnterpriseConfirmation = false },
            containerColor = colors.backgroundSurface,
            title = {
                FlashText(
                    text = "Disconnect from Organization?",
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            },
            text = {
                FlashText(
                    text = "This will remove corporate certificates and stop hub relay messaging.",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                FlashSettingsTextButton(
                    label = "Disconnect",
                    color = colors.textError,
                    onClick = {
                        showDisconnectEnterpriseConfirmation = false
                        onDisconnectEnterprise()
                    },
                )
            },
            dismissButton = {
                FlashSettingsTextButton(
                    label = "Cancel",
                    color = colors.textSecondary,
                    onClick = { showDisconnectEnterpriseConfirmation = false },
                )
            },
        )
    }

    val hubBanner = hubStatus?.let { FlashHubStatusMath.banner(it, hubNow) }
        ?: model.orgTrustState?.let { FlashHubStatusMath.orgTrustBanner(it) }
    val showRelinkOffer = model.isEnterpriseEnrolled && model.hubRelinkableGroups > 0 && onRestoreHubGroups != null && onForgetHubGroups != null
    val showHapticsRow = showHapticsToggle && onHapticsChanged != null
    val showBatteryRow = showBatteryOptimization && onOpenBatterySettings != null
    val showEncryptionRow = showEncryption && onOpenEncryption != null
    val showTrustedPeersRow = showTrustedPeers
    val showAppearanceSection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.APPEARANCE,
        showHaptics = showHapticsRow,
    )
    val showStorageSection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.STORAGE,
        showSaveLocation = showSaveLocation,
        saveLocationLabel = model.saveLocationLabel,
    )
    val showNetworkSection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.NETWORK,
        showWindowsContextMenu = model.showWindowsContextMenu,
        showBattery = showBatteryRow,
        backgroundListenTitle = backgroundListenTitle,
        backgroundListenSubtitle = backgroundListenSubtitle,
    )
    val showCallingSection = FlashSettingsMath.sectionVisible(query, FlashSettingsMath.Section.CALLING)
    val showSecuritySection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.SECURITY,
        showEncryption = showEncryptionRow,
        showTrustedPeers = showTrustedPeersRow,
    )
    val showAdvancedSection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.ADVANCED,
        showRadioGateway = radioGatewayAvailable,
        showExportLogs = onExportLogs != null,
    )
    val bannerTerms = listOfNotNull(hubBanner?.title, hubBanner?.body)
    val showEnterpriseSection = FlashSettingsMath.sectionVisible(
        query,
        FlashSettingsMath.Section.ENTERPRISE,
        isEnterpriseEnrolled = model.isEnterpriseEnrolled,
        showColleagueTrust = model.orgTrustState != null,
        showRelinkOffer = showRelinkOffer,
        showContentViaHub = onSetHubContentViaHub != null,
        showApprovals = onLoadApprovals != null && onApprove != null,
        enterpriseHubName = hubStatus?.hubName,
        enterpriseHubUrl = model.enterpriseHubUrl,
        enterpriseRootFingerprint = model.enterpriseRootFingerprint,
        enterpriseDepartment = model.enterpriseDepartment,
        enterpriseRole = model.enterpriseRole,
        bannerTerms = bannerTerms,
    )
    val showIdentity = FlashSettingsMath.matches(query, "Display name", model.displayName, "Name")
    val showAbout = FlashSettingsMath.matches(
        query,
        "About",
        "Version",
        model.appVersion,
        "Protocol",
        model.protocolVersion,
        "Device id",
        model.deviceIdShort.take(8),
    )

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
        item(key = "header", contentType = "header") {
            StaggerIn(0) {
                FlashText(
                    text = "Settings",
                    style = FlashTheme.typography.headingMedium,
                    color = FlashTheme.colors.textPrimary,
                )
            }
        }
        item(key = "search", contentType = "search") {
            StaggerIn(1) { SettingsSearchField(query = query, onQueryChange = { query = it }) }
        }
        if (showIdentity) {
            item(key = "identity", contentType = "identity") {
                StaggerIn(1) { IdentityRow(model.displayName, onEditDisplayName) }
            }
        }

        // 1. Appearance & Feedback
        if (showAppearanceSection) item(key = "section-appearance", contentType = "section_appearance") {
            StaggerIn(2) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.APPEARANCE.title,
                    subtitle = FlashSettingsMath.Section.APPEARANCE.subtitle,
                    iconSpec = FlashIcons.Gallery,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.themeRowTerms.toTypedArray())) SettingsCard {
                        ThemeModeSegmented(
                            selected = model.themeMode,
                            onSelected = onThemeModeSelected,
                        )
                    }
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.dynamicAccentRowTerms.toTypedArray())) SwitchRow(
                        title = "Dynamic accent",
                        subtitle = "Tint Flash with your wallpaper colors where supported",
                        checked = model.dynamicAccent,
                        onCheckedChange = onDynamicAccentChanged,
                    )
                    if (showHapticsRow && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.hapticsRowTerms.toTypedArray())) SwitchRow(
                        title = "Haptics",
                        subtitle = "Subtle vibration feedback on actions",
                        checked = model.hapticsEnabled,
                        onCheckedChange = { onHapticsChanged?.invoke(it) },
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.launchAnimationRowTerms.toTypedArray())) SwitchRow(
                        title = "Launch animation",
                        subtitle = FlashSettingsMath.launchAnimationSubtitle(model.launchAnimation),
                        checked = model.launchAnimation,
                        onCheckedChange = onLaunchAnimationChanged,
                    )
                }
            }
        }

        // 2. Storage & Downloads
        if (showStorageSection) item(key = "section-storage", contentType = "section_storage") {
            StaggerIn(3) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.STORAGE.title,
                    subtitle = FlashSettingsMath.Section.STORAGE.subtitle,
                    iconSpec = FlashIcons.Download,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    val saveTerms = FlashSettingsMath.saveLocationRowTerms(model.saveLocationLabel)
                    if (showSaveLocation && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *saveTerms.toTypedArray())) ValueRow(
                        iconSpec = FlashIcons.Download,
                        title = "Save location",
                        subtitle = model.saveLocationLabel ?: "Choose where received files go",
                        value = null,
                        onClick = onPickSaveLocation,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.receivedFilesRowTerms.toTypedArray())) StorageUsageCard(
                        model = model,
                        onRefresh = onRefreshStorageUsage,
                        onClear = { showClearStorageConfirmation = true },
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.autoVoiceRowTerms.toTypedArray())) SwitchRow(
                        title = "Auto-download voice",
                        subtitle = "Accept incoming voice messages automatically",
                        checked = model.autoDownloadVoice,
                        onCheckedChange = onAutoDownloadVoiceChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.autoImageRowTerms.toTypedArray())) SwitchRow(
                        title = "Auto-download images",
                        subtitle = "Accept incoming images automatically",
                        checked = model.autoDownloadImage,
                        onCheckedChange = onAutoDownloadImageChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.autoVideoRowTerms.toTypedArray())) SwitchRow(
                        title = "Auto-download videos",
                        subtitle = "Accept incoming videos automatically",
                        checked = model.autoDownloadVideo,
                        onCheckedChange = onAutoDownloadVideoChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.autoFileRowTerms.toTypedArray())) SwitchRow(
                        title = "Auto-download files",
                        subtitle = "Accept incoming files automatically",
                        checked = model.autoDownloadFile,
                        onCheckedChange = onAutoDownloadFileChanged,
                    )
                }
            }
        }

        // 3. Network & Background (everyday background & system behaviour)
        if (showNetworkSection) item(key = "section-network", contentType = "section_network") {
            StaggerIn(4) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.NETWORK.title,
                    subtitle = FlashSettingsMath.Section.NETWORK.subtitle,
                    iconSpec = FlashIcons.Wifi,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    // ADR-232: the old row promised "keep sending when you leave the app", which no foreground service can
                    // guarantee on every handset (EXP-002: 90 % alive on Samsung, 4 % on Infinix). The row now names what
                    // the switch actually does — hold the service up while idle — and says so.
                    val bgTerms = FlashSettingsMath.backgroundRowTerms(backgroundListenTitle, backgroundListenSubtitle)
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, *bgTerms.toTypedArray())) SwitchRow(
                        title = backgroundListenTitle,
                        subtitle = backgroundListenSubtitle,
                        checked = model.backgroundTransfers,
                        onCheckedChange = onBackgroundTransfersChanged,
                    )
                    if (model.showWindowsContextMenu && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, *FlashSettingsMath.windowsMenuRowTerms.toTypedArray())) SwitchRow(
                        title = "File Explorer context menu",
                        subtitle = "Right-click any file or folder to send with Flash",
                        checked = model.windowsContextMenu,
                        onCheckedChange = onWindowsContextMenuChanged,
                    )
                    if (showBatteryRow && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.NETWORK, *FlashSettingsMath.batteryRowTerms.toTypedArray())) ValueRow(
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
        if (showCallingSection) item(key = "section-calling", contentType = "section_calling") {
            StaggerIn(5) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.CALLING.title,
                    subtitle = FlashSettingsMath.Section.CALLING.subtitle,
                    iconSpec = FlashIcons.VideoCall,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.CALLING, *FlashSettingsMath.prioritiseVoiceRowTerms.toTypedArray())) SwitchRow(
                        title = "Prioritise voice quality",
                        subtitle = FlashSettingsMath.prioritiseVoiceSubtitle(model.prioritiseVoiceQuality),
                        checked = model.prioritiseVoiceQuality,
                        onCheckedChange = onPrioritiseVoiceQualityChanged,
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.CALLING, *FlashSettingsMath.smallerVideoRowTerms.toTypedArray())) SwitchRow(
                        title = "Send smaller video in groups",
                        subtitle = FlashSettingsMath.smallerVideoForManySubtitle(model.smallerVideoForMany),
                        checked = model.smallerVideoForMany,
                        onCheckedChange = onSmallerVideoForManyChanged,
                    )
                }
            }
        }

        // 5. Security & Trusted Devices
        if (showSecuritySection) item(key = "section-security", contentType = "section_security") {
            StaggerIn(6) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.SECURITY.title,
                    subtitle = FlashSettingsMath.Section.SECURITY.subtitle,
                    iconSpec = FlashIcons.Verified,
                    initiallyExpanded = true,
                    forceExpanded = searching,
                ) {
                    if (showEncryptionRow && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SECURITY, *FlashSettingsMath.encryptionRowTerms.toTypedArray())) ValueRow(
                        iconSpec = FlashIcons.Encryption,
                        title = "Encryption",
                        subtitle = "How Flash protects your transfers",
                        value = null,
                        onClick = onOpenEncryption,
                    )
                    if (showTrustedPeersRow && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.SECURITY, *FlashSettingsMath.trustedPeersRowTerms.toTypedArray())) ValueRow(
                        iconSpec = FlashIcons.Verified,
                        title = "Trusted peers",
                        subtitle = FlashSettingsMath.trustedPeersSubtitle(model.trustedPeerCount),
                        value = null,
                        onClick = onOpenTrustedPeers,
                    )
                }
            }
        }

        // 6. Advanced & Power (collapsed by default for progressive disclosure)
        if (showAdvancedSection) item(key = "section-advanced", contentType = "section_advanced") {
            StaggerIn(7) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.ADVANCED.title,
                    subtitle = FlashSettingsMath.Section.ADVANCED.subtitle,
                    iconSpec = FlashIcons.Connection,
                    initiallyExpanded = false,
                    forceExpanded = searching,
                ) {
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.discoveryRowTerms.toTypedArray())) SettingsCard {
                        DiscoveryModeSegmented(
                            selected = model.discoveryMode,
                            onSelected = onDiscoveryModeChanged,
                        )
                    }
                    if (radioGatewayAvailable && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.radioGatewayRowTerms().toTypedArray())) ValueRow(
                        iconSpec = FlashIcons.Bluetooth,
                        title = com.transfer.flash.ui.radio.FlashRadioCopy.SETTINGS_ROW_TITLE,
                        subtitle = com.transfer.flash.ui.radio.FlashRadioCopy.SETTINGS_ROW_SUBTITLE,
                        value = if (model.radioGatewayEnabled) com.transfer.flash.ui.radio.FlashRadioCopy.VALUE_ON else com.transfer.flash.ui.radio.FlashRadioCopy.VALUE_OFF,
                        onClick = {
                            if (onOpenRadioGateway != null) onOpenRadioGateway() else showRadioGateway = radioGatewayController != null
                        },
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.performanceRowTerms.toTypedArray())) SettingsCard {
                        PerformanceModeSegmented(
                            selected = model.performanceMode,
                            detected = model.detectedPerformanceMode,
                            onSelected = onPerformanceModeSelected,
                        )
                    }
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.swarmMasterRowTerms.toTypedArray())) SwitchRow(
                        title = "Group file sharing (swarm, experimental)",
                        subtitle = "Enable multi-device cooperative transfers in groups. Applies after restart",
                        checked = model.swarmEnabled,
                        onCheckedChange = {
                            onSwarmEnabledChanged(it)
                            if (onRestartApp != null) showRestartPrompt = true
                        },
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.swarmHelpShareRowTerms.toTypedArray())) SwitchRow(
                        title = "Help share group files",
                        subtitle = "Share received file pieces with other group members on your local network",
                        checked = model.swarmHelpShare,
                        onCheckedChange = onSwarmHelpShareChanged,
                        enabled = model.swarmEnabled,
                        modifier = Modifier.padding(start = FlashSpacing.space16),
                    )
                    if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.swarmKeepFinishedRowTerms.toTypedArray())) SwitchRow(
                        title = "Keep finished files available for others",
                        subtitle = "Keep completed group files available to help members who come online later",
                        checked = model.swarmKeepFinishedFiles,
                        onCheckedChange = onSwarmKeepFinishedFilesChanged,
                        enabled = model.swarmEnabled,
                        modifier = Modifier.padding(start = FlashSpacing.space16),
                    )
                    if (onExportLogs != null && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.exportLogsRowTerms.toTypedArray())) ValueRow(
                        iconSpec = FlashIcons.Share,
                        title = "Export logs",
                        subtitle = "Share Flash's recent log to help find a problem. It holds no message text or keys",
                        value = null,
                        onClick = onExportLogs,
                    )
                }
            }
        }

        // 7. Enterprise & Organization
        if (showEnterpriseSection) item(key = "section-enterprise", contentType = "section_enterprise") {
            StaggerIn(8) {
                ExpandableSettingsSection(
                    title = FlashSettingsMath.Section.ENTERPRISE.title,
                    subtitle = FlashSettingsMath.Section.ENTERPRISE.subtitle,
                    iconSpec = FlashIcons.Relay,
                    initiallyExpanded = false,
                    forceExpanded = searching,
                ) {
                    if (hubBanner != null && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, hubBanner.title, hubBanner.body)) {
                        FlashHubBannerCard(
                            banner = hubBanner,
                            onPrimary = {
                                when (hubBanner.primary) {
                                    FlashHubBannerAction.RETRY_NOW -> onRetryHub?.invoke()
                                    FlashHubBannerAction.RE_ENROL -> {
                                        onDismissHubRevoked?.invoke()
                                        showEnrollDialog = true
                                    }
                                    FlashHubBannerAction.DISMISS -> onDismissHubRevoked?.invoke()
                                    FlashHubBannerAction.NONE -> Unit
                                }
                            },
                            onDismiss = { onDismissHubRevoked?.invoke() },
                        )
                    }
                    if (model.isEnterpriseEnrolled) {
                        val hubTerms = FlashSettingsMath.enterpriseHubRowTerms(
                            enrolled = true,
                            hubName = hubStatus?.hubName,
                            hubUrl = model.enterpriseHubUrl,
                            rootFingerprint = model.enterpriseRootFingerprint,
                        )
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *hubTerms.toTypedArray())) {
                            if (hubStatus != null) {
                                FlashHubStatusRow(status = hubStatus, nowMs = hubNow, onOpenDetails = { showHubDetails = true })
                            } else {
                                ValueRow(
                                    iconSpec = FlashIcons.Relay,
                                    title = "Organization hub",
                                    subtitle = "Connected to corporate relay for remote & offline messaging",
                                    value = model.enterpriseHubUrl.orEmpty(),
                                    onClick = null,
                                )
                            }
                        }
                        if (model.orgTrustState != null && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *FlashSettingsMath.enterpriseTrustRowTerms.toTypedArray())) {
                            FlashOrgTrustStatusRow(state = model.orgTrustState)
                        }
                        if (showRelinkOffer && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *FlashSettingsMath.enterpriseRelinkRowTerms.toTypedArray())) {
                            FlashHubRelinkOffer(count = model.hubRelinkableGroups, onRestore = onRestoreHubGroups!!, onForget = onForgetHubGroups!!)
                        }
                        if (onSetHubContentViaHub != null && FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *FlashSettingsMath.enterpriseContentRowTerms.toTypedArray())) SwitchRow(
                            title = "Messages through the hub",
                            subtitle = FlashHubStatusMath.CONTENT_VIA_HUB_SUBTITLE,
                            checked = model.hubContentViaHub,
                            onCheckedChange = { next ->
                                if (next) showHubContentDisclosure = true else onSetHubContentViaHub(false)
                            },
                            // Turning this on only asks for confirmation, so the row must not move until
                            // the setting really changed: a cancelled disclosure would otherwise leave the
                            // switch on while messages still do not go through the hub.
                            optimistic = false,
                        )
                        val deptTerms = FlashSettingsMath.enterpriseDepartmentRowTerms(model.enterpriseDepartment)
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *deptTerms.toTypedArray())) ValueRow(
                            iconSpec = FlashIcons.Device,
                            title = "Department",
                            subtitle = "Authorized organization directory scope",
                            value = model.enterpriseDepartment ?: "None",
                            onClick = null,
                        )
                        val roleTerms = FlashSettingsMath.enterpriseRoleRowTerms(model.enterpriseRole)
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *roleTerms.toTypedArray())) ValueRow(
                            iconSpec = FlashIcons.Verified,
                            title = "Role",
                            subtitle = "Delegated permission level inside company",
                            value = model.enterpriseRole ?: "Member",
                            onClick = null,
                        )
                        if (onLoadApprovals != null && onApprove != null &&
                            FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *FlashSettingsMath.enterpriseApprovalsRowTerms.toTypedArray())
                        ) ValueRow(
                            iconSpec = FlashIcons.Relay,
                            title = "Approve admin requests",
                            subtitle = "If you set this phone as your approver, approve dangerous admin console actions here",
                            value = "Open",
                            onClick = { showApprovals = true },
                        )
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *FlashSettingsMath.enterpriseDisconnectRowTerms.toTypedArray())) ValueRow(
                            iconSpec = FlashIcons.Close,
                            title = "Disconnect from Organization",
                            subtitle = "Remove corporate certificates and stop hub relay",
                            value = "Disconnect",
                            onClick = { showDisconnectEnterpriseConfirmation = true },
                        )
                    } else {
                        val unenrolledHubTerms = FlashSettingsMath.enterpriseHubRowTerms(enrolled = false)
                        if (FlashSettingsMath.rowVisible(query, FlashSettingsMath.Section.ENTERPRISE, *unenrolledHubTerms.toTypedArray())) ValueRow(
                            iconSpec = FlashIcons.Relay,
                            title = "Organization hub",
                            subtitle = "Enrol with an invitation link (flash://enroll) from your admin",
                            value = "Enrol",
                            onClick = { showEnrollDialog = true },
                        )
                    }
                }
            }
        }

        val anySection = showAppearanceSection || showStorageSection || showNetworkSection ||
            showCallingSection || showSecuritySection || showAdvancedSection || showEnterpriseSection
        if (searching && !anySection && !showAbout && !showIdentity) {
            item(key = "no-results", contentType = "no_results") {
                FlashText(
                    text = "No settings match \"${query.trim()}\"",
                    style = FlashTheme.typography.bodyDefault,
                    color = FlashTheme.colors.textTertiary,
                    modifier = Modifier.padding(vertical = FlashSpacing.space16),
                )
            }
        }
        if (!searching) {
            item(key = "reset-defaults", contentType = "reset_defaults") {
                StaggerIn(9) {
                    ResetDefaultsRow(
                        enabled = !FlashSettingsMath.isAtDefaults(model, showHaptics = showHapticsRow),
                        onClick = { showResetConfirmation = true },
                    )
                }
            }
        }
        if (showAbout) item(key = "about", contentType = "about") { StaggerIn(10) { AboutCard(model) } }
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
    val reduceMotion = motion.reduceMotion || FlashTheme.ultraLowUi
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else motion.tweenNormalSpec(),
        label = "expandChevronRotation",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FlashDimensions.minTouchTarget)
                .clip(FlashShapes.button)
                .background(colors.backgroundSurface)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = colors.borderSubtle.copy(alpha = 0.4f),
                    shape = FlashShapes.button,
                )
                .clickable(role = Role.Button) {
                    haptics(FlashHaptic.Tick)
                    userExpanded = !userExpanded
                }
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(FlashDimensions.avatarMd)
                    .clip(CircleShape)
                    .background(colors.accentFill.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = iconSpec,
                    tint = colors.textLink,
                    size = FlashDimensions.iconSm,
                    contentDescription = null,
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
                contentDescription = null,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }

        if (reduceMotion) {
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    content()
                }
            }
        } else {
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
}

/**
 * UI-050 entrance: alpha + a short rise driven by the shared stagger progress, read inside
 * `graphicsLayer` so the reveal is a render pass and never recomposes the row.
 */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    if (FlashTheme.ultraLowUi || FlashTheme.motion.reduceMotion) {
        content()
        return
    }
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
            .clip(FlashShapes.button)
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
            .heightIn(min = FlashDimensions.minTouchTarget)
            .flashPressScale(interactionSource)
            .clip(FlashShapes.button)
            .background(colors.backgroundSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
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
                .background(colors.accentFill.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = name.take(1).uppercase().ifBlank { "?" },
                style = FlashTheme.typography.headingSmall,
                color = colors.textLink,
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
            .clip(FlashShapes.navBar)
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
                .clip(FlashShapes.navBar)
                .background(colors.accentFill),
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
                        color = if (mode == selected) colors.onAccentFill else colors.textSecondary,
                    )
                }
            }
        }
    }
}

private val SegmentTrackHeight = FlashDimensions.minTouchTarget

/**
 * Adaptive wrapping option chips shared by [PerformanceModeSegmented] and [DiscoveryModeSegmented].
 * Keeps >= 48.dp touch targets and avoids horizontal clipping on narrow screens or large font scales.
 */
@Composable
private fun <T> SettingsOptionChipGroup(
    title: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    labelFor: (T) -> String,
    a11yLabelFor: (T) -> String,
    subtitle: String,
    onSelected: (T) -> Unit,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text = title,
            style = FlashTheme.typography.bodyDefault,
            color = colors.textPrimary,
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            options.forEach { option ->
                val selectedOption = isSelected(option)
                val label = labelFor(option)
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.button)
                        .background(if (selectedOption) colors.accentFill else colors.backgroundSurfaceSubtle)
                        .border(
                            width = FlashDimensions.borderHairline,
                            color = if (selectedOption) colors.accentFill else colors.borderSubtle,
                            shape = FlashShapes.button,
                        )
                        .selectable(
                            selected = selectedOption,
                            role = Role.RadioButton,
                            onClick = {
                                if (!selectedOption) {
                                    haptics(FlashHaptic.Tick)
                                    onSelected(option)
                                }
                            },
                        )
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
                        .semantics { contentDescription = a11yLabelFor(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (selectedOption) colors.onAccentFill else colors.textSecondary,
                    )
                }
            }
        }
        FlashText(
            text = subtitle,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

/**
 * Auto / Ultra Low / Low / Medium / High tier picker (ERROR-033).
 */
@Composable
private fun PerformanceModeSegmented(
    selected: FlashPerformanceMode?,
    detected: FlashPerformanceMode,
    onSelected: (FlashPerformanceMode?) -> Unit,
) {
    val options: List<FlashPerformanceMode?> = listOf(
        null,
        FlashPerformanceMode.ULTRA_LOW,
        FlashPerformanceMode.LOW,
        FlashPerformanceMode.MEDIUM,
        FlashPerformanceMode.HIGH,
    )
    SettingsOptionChipGroup(
        title = "Performance mode",
        options = options,
        isSelected = { it == selected },
        labelFor = { FlashSettingsMath.performanceModeLabel(it) },
        a11yLabelFor = { "${FlashSettingsMath.performanceModeLabel(it)} performance" },
        subtitle = FlashSettingsMath.performanceModeSubtitle(pinned = selected, detected = detected),
        onSelected = onSelected,
    )
}

@Composable
private fun DiscoveryModeSegmented(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val options = listOf("STANDARD", "GHOST", "ECO", "BOOST")
    SettingsOptionChipGroup(
        title = "Discovery mode",
        options = options,
        isSelected = { it.equals(selected, ignoreCase = true) },
        labelFor = { FlashSettingsMath.discoveryModeShortLabel(it) },
        a11yLabelFor = { "${FlashSettingsMath.discoveryModeShortLabel(it)} discovery mode" },
        subtitle = FlashSettingsMath.discoveryModeSubtitle(selected),
        onSelected = onSelected,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * OPT-O24-05: whether toggling this row writes the setting immediately.
     *
     * True (the normal case) moves the thumb on the spot and drops the local value as soon as the
     * stored value arrives. False is for a toggle whose change is not the write itself — a row that
     * only opens a confirmation dialog — because the switch must never show a state that no write is
     * behind: a cancelled or never-issued write would otherwise leave it on the new position for good
     * while the stored setting stayed where it was.
     */
    optimistic: Boolean = true,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    // The latch only exists for a row that writes; it re-syncs whenever the stored value arrives.
    var optimisticChecked by remember(checked) { mutableStateOf(checked) }
    val shownChecked = if (optimistic) optimisticChecked else checked
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .then(if (enabled) Modifier.flashPressScale(interactionSource) else Modifier)
            .clip(FlashShapes.button)
            .background(colors.backgroundSurface)
            // Role.Switch + toggleable gives TalkBack the real on/off state and the
            // "double-tap to toggle" affordance; a clickable + contentDescription pair
            // announced the state only at first read and never on change.
            .toggleable(
                value = shownChecked,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { next ->
                    if (optimistic) optimisticChecked = next
                    haptics(FlashHaptic.Tick)
                    onCheckedChange(next)
                },
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = if (shownChecked) "on" else "off"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlashText(
                text = title,
                style = FlashTheme.typography.bodyDefault,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            )
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        FlashSwitch(checked = shownChecked, enabled = enabled)
    }
}

/** Flash-drawn switch: pill track + spring thumb, no Material Switch. */
@Composable
private fun FlashSwitch(checked: Boolean, enabled: Boolean = true) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) SwitchThumbTravel else FlashSpacing.space2,
        animationSpec = motion.springSnappySpec(),
        label = "flashSwitchThumb",
    )
    val baseTrack = if (checked) colors.accentFill else colors.borderStrong
    val baseThumb = if (checked) colors.onAccentFill else colors.backgroundSurface
    val trackTint by animateColorAsState(
        targetValue = if (enabled) baseTrack else baseTrack.copy(alpha = 0.45f),
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchTrack",
    )
    val thumbTint by animateColorAsState(
        targetValue = if (enabled) baseThumb else baseThumb.copy(alpha = 0.75f),
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchThumbTint",
    )
    Box(
        Modifier
            .width(SwitchTrackWidth)
            .height(SwitchTrackHeight)
            .clip(FlashShapes.navBar)
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
    onClick: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.button)
            .background(colors.backgroundSurface)
            .then(clickModifier)
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
            Spacer(Modifier.width(FlashSpacing.space8))
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
    // The host no longer measures received storage during activity start-up (OPT-O01-02 moved that database work off the
    // main thread), so the card asks for a fresh figure when it appears instead of showing "0 B" until Refresh is tapped.
    LaunchedEffect(Unit) { onRefresh() }
    val clearEnabled = FlashStorageMath.canClearReceivedFiles(
        totalBytes = model.receivedFilesBytes,
        isLoading = model.storageUsageLoading,
        hasError = model.storageUsageError,
        isClearing = model.clearingReceivedFiles,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(FlashShapes.button)
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
                FlashSettingsSpinner(
                    size = FlashDimensions.iconMd,
                    tint = colors.accentFill,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.End),
        ) {
            val refreshEnabled = !model.storageUsageLoading && !model.clearingReceivedFiles
            FlashSettingsTextButton(
                label = "Refresh",
                color = if (refreshEnabled) colors.textLink else colors.textTertiary,
                enabled = refreshEnabled,
                onClick = onRefresh,
                modifier = Modifier.semantics {
                    contentDescription = if (model.storageUsageLoading) {
                        "Refreshing received files storage usage"
                    } else {
                        "Refresh received files storage usage"
                    }
                },
            )
            FlashSettingsTextButton(
                label = "Clear received files",
                color = if (clearEnabled) colors.textError else colors.textTertiary,
                enabled = clearEnabled,
                onClick = onClear,
                modifier = Modifier.semantics {
                    contentDescription = if (clearEnabled) {
                        "Clear all received files"
                    } else {
                        "Clear received files unavailable"
                    }
                },
            )
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
            FlashSettingsTextButton(label = "Delete files", color = colors.textError, onClick = onConfirm)
        },
        dismissButton = {
            FlashSettingsTextButton(label = "Cancel", color = colors.textSecondary, onClick = onDismiss)
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
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.composerInput)
            .background(colors.composerInputBackground)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle,
                shape = FlashShapes.composerInput,
            )
            .padding(start = FlashSpacing.space12, end = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = FlashIcons.Search,
            contentDescription = null,
            tint = colors.textTertiary,
            size = FlashDimensions.iconSm,
        )
        Spacer(Modifier.width(FlashSpacing.space8))
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = FlashSpacing.space10)
                .semantics { contentDescription = "Search settings" },
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentFill),
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
        // Always reserve the 48.dp trailing slot so showing the Clear button never changes height.
        Box(
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(FlashDimensions.minTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = { onQueryChange("") })
                        .semantics { contentDescription = "Clear search" },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        size = FlashDimensions.iconSm,
                        tint = colors.textSecondary,
                        contentDescription = null,
                    )
                }
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
        FlashSettingsTextButton(
            label = "Reset to defaults",
            color = if (enabled) colors.textError else colors.textTertiary,
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.semantics {
                contentDescription = if (enabled) "Reset settings to defaults" else "Settings are already at their defaults"
            },
        )
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
            FlashSettingsTextButton(label = "Reset", color = colors.textError, onClick = onConfirm)
        },
        dismissButton = {
            FlashSettingsTextButton(label = "Cancel", color = colors.textSecondary, onClick = onDismiss)
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
            FlashSettingsTextButton(label = "Restart now", color = colors.textLink, onClick = onRestart)
        },
        dismissButton = {
            FlashSettingsTextButton(label = "Later", color = colors.textSecondary, onClick = onLater)
        },
    )
}

@Composable
private fun AboutCard(model: FlashSettingsModel) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(FlashShapes.button)
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

/** Shared Flash-themed text button (`>= 48.dp` hit target, zero M3 `TextButton`/`Text` leakage). */
@Composable
internal fun FlashSettingsTextButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .then(if (enabled) Modifier.flashPressScale(interactionSource) else Modifier)
            .clip(FlashShapes.button)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        if (leading != null) leading()
        FlashText(
            text = label,
            style = FlashTheme.typography.bodyEmphasis,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shared Flash-owned progress spinner that respects `reduceMotion` and `ultraLowUi`. */
@Composable
internal fun FlashSettingsSpinner(
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val still = FlashTheme.motion.reduceMotion || FlashTheme.ultraLowUi
    val angle = if (still) {
        0f
    } else {
        rememberInfiniteTransition(label = "settingsSpinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "settingsSpinnerAngle",
        ).value
    }
    Canvas(modifier.size(size)) {
        val strokePx = 2.dp.toPx()
        drawArc(
            color = tint,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}
