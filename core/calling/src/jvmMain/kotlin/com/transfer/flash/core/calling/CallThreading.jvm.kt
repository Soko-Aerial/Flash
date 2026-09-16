package com.transfer.flash.core.calling

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * JVM actual: one dedicated thread for ALL native WebRTC work, process-wide.
 *
 * webrtc-java drives Windows WASAPI, whose COM apartment affinity silently breaks when
 * capture/render/factory calls arrive from varying pool threads (buzz on playout, zeros on
 * capture, no exception). A single thread for the factory, the ADM, every `PeerConnection`,
 * every track/sender touch and every `getStats()` keeps that affinity by construction —
 * including across sequential calls, which share the factory singleton anyway.
 *
 * Daemon so it can never hold the JVM open; never shut down (same lifetime as the factory).
 * Nesting is safe: `withContext` on this dispatcher from its own thread suspends and
 * re-queues rather than blocking, so helpers may call helpers without deadlock.
 */
private val callMediaExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "flash-call-media").apply { isDaemon = true }
}

public actual val callMediaDispatcher: CoroutineDispatcher =
    callMediaExecutor.asCoroutineDispatcher()
