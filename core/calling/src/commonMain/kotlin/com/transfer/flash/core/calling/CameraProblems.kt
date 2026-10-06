package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCameraProblem
import com.transfer.flash.core.calling.model.FlashCallUiState

/** ERROR-105: how long "Couldn't switch camera" stays on screen before it clears itself. */
internal const val SWITCH_PROBLEM_CLEAR_MS = 5_000L

/**
 * Records [problem] on the call state. A stopped camera ([FlashCameraProblem.FAILED]) is also an off camera, so the
 * button, the peer's tile and the state agree; a failed switch leaves the working camera running.
 */
internal fun FlashCallUiState.withCameraProblem(problem: FlashCameraProblem?): FlashCallUiState = when (problem) {
    FlashCameraProblem.FAILED -> copy(cameraOff = true, cameraProblem = problem)
    else -> copy(cameraProblem = problem)
}

/** The camera button means "start the camera again" (not "unmute the old track") after the camera stopped. */
internal fun FlashCallUiState.cameraNeedsRestart(): Boolean = cameraOff && cameraProblem == FlashCameraProblem.FAILED
