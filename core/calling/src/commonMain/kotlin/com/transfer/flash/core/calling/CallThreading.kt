package com.transfer.flash.core.calling

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher every native WebRTC call runs on.
 *
 * Windows' native audio backend (WASAPI) needs COM initialized as MTA on a consistent thread
 * for capture/render callbacks; coroutine pool dispatchers (`Dispatchers.IO`/`Default`) resume
 * a suspend function on a different OS thread after every suspension, which silently breaks
 * that affinity — garbled/buzzing playout plus a capture side that produces nothing, with no
 * Java exception. The JVM actual is therefore one dedicated daemon thread for the process's
 * whole call lifetime; the Android actual is `Dispatchers.Default` (the native engine drives
 * its own audio threads there, so pool hopping is harmless and current behavior is kept).
 *
 * Rule: any code that touches `PeerConnectionFactory`, `AudioDeviceModule`, `MediaDevices`,
 * a `PeerConnection`, a media track, or an `RtpSender`'s native parameters must run on this
 * dispatcher. Both sessions enforce it internally (`onMediaThread`), so hosts keep passing
 * whatever scope they already pass.
 */
public expect val callMediaDispatcher: CoroutineDispatcher
