package com.transfer.flash.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop: accepted and dropped. Desktop audio synthesis is not in scope for the migration —
 * PHASE-18 step 1b: "A future phase can add javax.sound.sampled or a similar backend."
 *
 * This is a stub in the sense that it plays nothing, but it is **not** a stub in the sense R2
 * forbids: no API was deleted and nothing was weakened to force a `commonMain` compile.
 * [FlashSoundSynth] already renders the PCM for every [FlashSound] in `commonMain` and its
 * 16 tests run on `jvmTest`, so the synthesis half is live on desktop today; only the output
 * device is missing. Callers cannot distinguish silence-by-platform from the silence they
 * already have to tolerate — sounds are opt-in and default OFF, and [FlashSoundPolicy] mutes
 * them under silent/DND — so no call site needs a desktop branch.
 *
 * `remember` with no keys returns the same lambda for the life of the composition, matching the
 * Android `actual`'s stability contract: a caller may pass this into `key`-less `remember`
 * blocks or `LaunchedEffect` without causing restarts.
 */
@Composable
actual fun rememberFlashSounds(): (FlashSound) -> Unit = remember { { } }
