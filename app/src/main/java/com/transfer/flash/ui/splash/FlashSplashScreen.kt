package com.transfer.flash.ui.splash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.theme.FlashBrandAnimation

/**
 * Flash launch splash — the full-brand [FlashBrandAnimation] on the dark gradient backdrop.
 *
 * It is meant to be shown while the app boots and removed the instant the engine is ready
 * (see [MainActivity]/`AppEngine.ready`): a fast phone shows a fraction of one cycle, a
 * slow phone shows many. Nothing here enforces a minimum duration — the caller decides when
 * to stop by removing this composable. The animation itself now lives in
 * [FlashBrandAnimation] (ui:theme) so the same motion can be reused by branded loading and
 * empty-state surfaces (Bug 4 fix).
 */
@Composable
fun FlashSplashScreen(modifier: Modifier = Modifier) {
    FlashBrandAnimation(modifier = modifier)
}
