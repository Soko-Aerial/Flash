package com.transfer.flash.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlin.time.TimeSource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-043 — Deterministic synthetic conversation generator for the stress-test harness.
 *
 * Pure logic (no Android/Compose types) so it is unit-testable under `ui/chat/src/test`.
 * Generation is O(n): one pass builds messages, [computeMessageGroupPositions] does a second
 * O(n) pass. 2000 messages generate in well under 100 ms on mid-range hardware.
 */
object FlashStressMath {

    private const val REPLY_EVERY = 7

    private val peerSenders = listOf(
        Triple("Alex Rivera", "AR", "Alex"),
        Triple("Belal Khan", "BK", "Belal"),
        Triple("Mira Haddad", "MH", "Mira"),
    )

    private val reactionPool = listOf("👍", "🔥", "❤️", "😮", "😂")

    private val seedColors = listOf(
        0xFF4579A8, 0xFF3E6B5C, 0xFFAD7450, 0xFF8A5A44, 0xFF4D5055, 0xFF5C4A78,
    )

    /** Preset conversation sizes exposed as selector chips on the stress screen. */
    fun stressPresets(): List<Int> = listOf(100, 500, 1000, 2000)

    /**
     * Rough retained-size estimate per rendered message item (model objects + strings +
     * amplitude lists), useful for predicting heap pressure before device profiling.
     */
    fun estimatedItemBytes(): Int = 640

    /**
     * Generates [count] deterministic messages mixing text, reactions, replies (every
     * [REPLY_EVERY]th), images without real bitmaps (null URI → gradient fallback),
     * voice notes with procedural amplitudes, and file cards.
     *
     * Same [count] + [seed] always produce identical ids, order, and content.
     */
    fun generateMessages(count: Int, seed: Long = 42L): List<FlashMessageUi> {
        require(count >= 0) { "count must be non-negative" }
        if (count == 0) return emptyList()

        // xorshift64 — deterministic across JVMs, O(1) per draw.
        var state = seed or 1L
        fun nextBits(bits: Int): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 1) and ((1L shl bits) - 1)).toInt()
        }

        val raw = ArrayList<FlashMessageUi>(count)
        // Sliding window of recent (id, snippet) pairs for reply quoting. O(1) per step.
        val recentSnippets = ArrayDeque<Pair<String, String>>()

        var baseTimestamp = 1_700_000_000_000L
        for (index in 0 until count) {
            val isMine = when {
                index % 11 == 0 -> true
                index % 3 == 0 -> false
                else -> nextBits(1) == 0
            }
            val sender = if (isMine) {
                Triple("You", "YO", "You")
            } else {
                peerSenders[nextBits(2).coerceAtMost(peerSenders.lastIndex)]
            }
            baseTimestamp += 15_000L + nextBits(12) * 1_000L
            val timeLabel = formatTimeLabel(baseTimestamp)

            val kind = if (index % REPLY_EVERY == 0 && index > 0) {
                StressKind.Reply
            } else {
                StressKind.entries[nextBits(8) % StressKind.entries.size]
            }

            val message = when (kind) {
                StressKind.Text -> FlashMessageUi(
                    id = "stress-$index",
                    senderName = sender.first,
                    senderInitials = sender.second,
                    timeLabel = timeLabel,
                    text = stressSentence(index, nextBits(4)),
                    isMine = isMine,
                )

                StressKind.Reacted -> FlashMessageUi(
                    id = "stress-$index",
                    senderName = sender.first,
                    senderInitials = sender.second,
                    timeLabel = timeLabel,
                    text = stressSentence(index, nextBits(4)),
                    isMine = isMine,
                    reactions = listOf(
                        com.transfer.flash.core.messaging.model.FlashReaction(
                            emoji = reactionPool[nextBits(8) % reactionPool.size],
                            count = 1 + nextBits(4),
                            isSelfReacted = isMine,
                        ),
                    ),
                )

                StressKind.Images -> FlashMessageUi(
                    id = "stress-$index",
                    senderName = sender.first,
                    senderInitials = sender.second,
                    timeLabel = timeLabel,
                    text = "",
                    isMine = isMine,
                    images = List(1 + nextBits(2)) { imageIdx ->
                        com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                            id = "stress-$index-img-$imageIdx",
                            uri = null,
                            width = 1080,
                            height = 1440,
                            seedColor = seedColors[nextBits(8) % seedColors.size],
                        )
                    },
                )

                StressKind.Voice -> FlashMessageUi(
                    id = "stress-$index",
                    senderName = sender.first,
                    senderInitials = sender.second,
                    timeLabel = timeLabel,
                    text = "",
                    isMine = isMine,
                    voiceAttachments = listOf(
                        com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi(
                            id = "stress-$index-voice",
                            durationMs = 8_000L + nextBits(10) * 1_000L,
                            amplitudes = proceduralAmplitudes(index),
                        ),
                    ),
                )

                StressKind.FileCard -> FlashMessageUi(
                    id = "stress-$index",
                    senderName = sender.first,
                    senderInitials = sender.second,
                    timeLabel = timeLabel,
                    text = "",
                    isMine = isMine,
                    fileAttachments = listOf(
                        com.transfer.flash.core.messaging.model.FlashFileAttachmentUi(
                            id = "stress-$index-file",
                            name = "flash-assets-$index.zip",
                            sizeBytes = 1_000_000L + nextBits(16) * 1_000L,
                            mimeType = "application/zip",
                        ),
                    ),
                )

                StressKind.Reply -> {
                    val quoted = recentSnippets.lastOrNull()
                    FlashMessageUi(
                        id = "stress-$index",
                        senderName = sender.first,
                        senderInitials = sender.second,
                        timeLabel = timeLabel,
                        text = stressSentence(index, nextBits(4)),
                        isMine = isMine,
                        replyTo = quoted?.let { (quotedId, snippet) ->
                            com.transfer.flash.core.messaging.model.FlashQuotedReplyUi(
                                messageId = quotedId,
                                senderName = "Earlier",
                                textSnippet = snippet.take(80),
                            )
                        },
                    )
                }
            }

            val summary = message.text.ifBlank {
                when {
                    message.images.isNotEmpty() -> "${message.images.size} photos"
                    message.voiceAttachments.isNotEmpty() -> "Voice message"
                    else -> message.fileAttachments.firstOrNull()?.name ?: ""
                }
            }
            recentSnippets.addLast(message.id to summary)
            if (recentSnippets.size > 8) recentSnippets.removeFirst()

            raw.add(message)
        }

        return computeMessageGroupPositions(raw)
    }

    /** Procedural waveform: smooth pseudo-random envelope derived from [index]. O(1). */
    internal fun proceduralAmplitudes(index: Int): List<Int> {
        val samples = 40
        return List(samples) { i ->
            val wave = kotlin.math.sin((i / 3.1) + index) * 30.0
            val jitter = ((index * 31 + i * 17) % 23) - 11
            (50 + wave + jitter).toInt().coerceIn(8, 100)
        }
    }

    internal fun stressSentence(index: Int, variant: Int): String {
        val subjects = listOf("Flash sync", "The transfer queue", "Peer pairing", "Chunk resume")
        val verbs = listOf("finished early", "is running smoothly", "paused briefly", "hit full speed")
        val tails = listOf("over LAN.", "via Wi‑Fi Direct.", "on both devices.", "without dropping frames.")
        return "${subjects[index % subjects.size]} ${verbs[(index + variant) % verbs.size]} ${tails[variant % tails.size]}"
    }

    /** Deterministic HH:MM label — no locale dependence. */
    internal fun formatTimeLabel(timestampMs: Long): String {
        val minutesOfDay = ((timestampMs / 60_000L) % (24L * 60L)).toInt()
        val hour = minutesOfDay / 60
        val minute = minutesOfDay % 60
        return "%02d:%02d".format(hour, minute)
    }

    private enum class StressKind { Text, Reacted, Images, Voice, FileCard, Reply }
}

/**
 * UI-043 — Debug/QA stress-test screen rendering the REAL [FlashMessageList] with
 * synthetic conversations of preset sizes (100–2000 messages).
 *
 * Not part of production navigation; wire it from a hidden entry point such as a
 * long-press on the chat-list header behind `BuildConfig.DEBUG`:
 *
 * ```kotlin
 * var showStress by remember { mutableStateOf(false) }
 * FlashChatHeader(
 *     state = header,
 *     onBack = onBack,
 *     modifier = Modifier.combinedClickable(
 *         onClick = onAvatarClick,
 *         onLongClick = { if (BuildConfig.DEBUG) showStress = true },
 *     ),
 * )
 * if (showStress) {
 *     FlashStressTestScreen(onBack = { showStress = false })
 * }
 * ```
 *
 * Reuses the production list/bubble pipeline unchanged — no list logic is forked here.
 */
@Composable
fun FlashStressTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlashStressTestScreenContent(
        initialCount = FlashStressMath.stressPresets().last(),
        title = "UI-043 Stress Test",
        subtitle = "Debug-only synthetic conversation",
        onBack = onBack,
        modifier = modifier,
    )
}

/** Internal body parameterized by preset so previews can render a small dataset cheaply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashStressTestScreenContent(
    initialCount: Int,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCount by remember { mutableIntStateOf(initialCount) }
    val seed = 42L

    val generated = remember(selectedCount, seed) {
        // `System.nanoTime()` is `java.lang` and does not exist in `commonMain`.
        // `kotlin.time.TimeSource.Monotonic` is the common stdlib's steady clock — same
        // monotonic guarantee, and `inWholeMilliseconds` truncates exactly as the previous
        // `(nanos / 1_000_000L)` integer division did.
        val startedAt = TimeSource.Monotonic.markNow()
        val msgs = FlashStressMath.generateMessages(selectedCount, seed)
        msgs to startedAt.elapsedNow().inWholeMilliseconds
    }
    val messages = generated.first
    val generationMs = generated.second

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FlashTheme.colors.backgroundChat)
                    .statusBarsPadding()
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.avatar)
                        .clickable(onClick = onBack)
                        .padding(FlashSpacing.space8),
                ) {
                    FlashText(text = "←", style = FlashTheme.typography.bodyDefault)
                }
                Column(modifier = Modifier.weight(1f)) {
                    FlashText(text = title, style = FlashTheme.typography.bodyEmphasis)
                    FlashText(
                        text = subtitle,
                        style = FlashTheme.typography.metadataDefault,
                        color = FlashTheme.colors.textSecondary,
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            FlashMessageList(
                messages = messages,
                onOpenMessageActions = {},
                modifier = Modifier.fillMaxSize(),
            )

            FlashStressStatsChip(
                label = "${messages.size} msgs · gen ${generationMs}ms",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = FlashSpacing.space8),
            )

            FlowRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(FlashTheme.colors.backgroundSurface.copy(alpha = 0.92f))
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashStressMath.stressPresets().forEach { preset ->
                    FlashStressPresetChip(
                        label = "$preset",
                        selected = selectedCount == preset,
                        onClick = { selectedCount = preset },
                    )
                }
            }
        }
    }
}

/** Small overlay chip reporting the active dataset size and generation cost. */
@Composable
private fun FlashStressStatsChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(FlashShapes.avatar)
            .background(FlashTheme.colors.backgroundSurface)
            .semantics { contentDescription = "Stress stats: $label" }
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textPrimary,
        )
    }
}

/** Preset-size selector chip (custom surface — no Material Button). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlashStressPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .clip(FlashShapes.avatar)
            .background(if (selected) colors.accentPrimary else colors.backgroundSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataEmphasis,
            color = if (selected) colors.textOnAccent else colors.textPrimary,
        )
    }
}

@Preview(name = "Stress Test Screen — 50 messages", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashStressTestScreenPreview() {
    FlashTheme {
        FlashStressTestScreenContent(
            initialCount = 50,
            title = "UI-043 Stress Test",
            subtitle = "Preview sample (50 messages)",
            onBack = {},
        )
    }
}
