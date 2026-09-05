package com.transfer.flash.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi

/**
 * Backward-compatible bridge delegating to [FlashImageGrid] (UI-017).
 */
@Composable
fun FlashAttachmentGrid(
    imageCountLabel: String?,
    modifier: Modifier = Modifier,
) {
    val sampleImages = remember(imageCountLabel) {
        val count = when {
            imageCountLabel != null && imageCountLabel.startsWith("+") -> {
                (imageCountLabel.removePrefix("+").toIntOrNull() ?: 1) + 3
            }
            else -> 4
        }
        val seedColors = listOf(
            0xFF4D5055,
            0xFFAD7450,
            0xFF4579A8,
            0xFF696B70,
            0xFF3E6B5C,
            0xFF8A5A44,
        )
        (0 until count).map { index ->
            FlashImageAttachmentUi(
                id = "sample_img_$index",
                seedColor = seedColors[index % seedColors.size],
                width = 800,
                height = 600,
            )
        }
    }

    FlashImageGrid(
        images = sampleImages,
        modifier = modifier,
    )
}
