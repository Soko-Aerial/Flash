package com.transfer.flash.core.discovery.nsd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serializes NSD service resolution requests to avoid Android NSD manager daemon concurrency crashes.
 *
 * A resolve that neither resolves nor fails (the peer left mid-resolve; `NsdManager` is known to hang
 * there) is abandoned after [resolveTimeoutMs], so one dead peer cannot hold the single in-flight slot
 * and starve every service queued behind it (RVW22 F21). Both that timeout and an ordinary
 * `onResolveFailed` are reported through [onFailed], so the transport can demote the service and retry
 * it instead of leaving it marked "monitored" forever (RVW22 F20).
 */
internal class NsdResolveQueue(
    private val nsdManager: NsdManager,
    private val tag: String = "NSD-RESOLVE",
    private val resolveTimeoutMs: Long = RESOLVE_TIMEOUT_MS,
    private val onFailed: (serviceName: String, errorCode: Int) -> Unit = { _, _ -> },
    private val onResolvedCallback: (NsdServiceInfo) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val queuedServiceNames = mutableSetOf<String>()
    private var resolvingServiceName: String? = null
    private var resolveSeq = 0L
    private var timeoutFuture: ScheduledFuture<*>? = null
    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "nsd-resolve-timeout").apply { isDaemon = true }
        }
    }

    fun enqueue(serviceInfo: NsdServiceInfo, isCurrentGeneration: () -> Boolean) {
        val next = synchronized(lock) {
            if (!isCurrentGeneration() ||
                resolvingServiceName == serviceInfo.serviceName ||
                !queuedServiceNames.add(serviceInfo.serviceName)
            ) {
                null
            } else {
                queue.addLast(serviceInfo)
                nextResolveLocked(isCurrentGeneration)
            }
        }
        next?.let { pendingInfo -> resolve(pendingInfo, isCurrentGeneration) }
    }

    private fun nextResolveLocked(isCurrentGeneration: () -> Boolean): NsdServiceInfo? {
        if (!isCurrentGeneration() || resolvingServiceName != null || queue.isEmpty()) return null
        val next = queue.removeFirst()
        queuedServiceNames.remove(next.serviceName)
        resolvingServiceName = next.serviceName
        resolveSeq += 1
        return next
    }

    private fun resolve(
        serviceInfo: NsdServiceInfo,
        isCurrentGeneration: () -> Boolean,
    ) {
        val attempt = synchronized(lock) { resolveSeq }
        if (!isCurrentGeneration()) {
            finishResolve(serviceInfo.serviceName, attempt, isCurrentGeneration)
            return
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (isCurrentGeneration()) {
                    Log.w(tag, "NSD resolve failed name=${info.serviceName} error=$errorCode")
                    onFailed(serviceInfo.serviceName, errorCode)
                }
                finishResolve(serviceInfo.serviceName, attempt, isCurrentGeneration)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (isCurrentGeneration()) {
                    onResolvedCallback(info)
                }
                finishResolve(serviceInfo.serviceName, attempt, isCurrentGeneration)
            }
        }

        synchronized(lock) {
            timeoutFuture?.cancel(false)
            timeoutFuture = timer.schedule({
                val abandoned = synchronized(lock) {
                    resolvingServiceName == serviceInfo.serviceName && resolveSeq == attempt
                }
                if (abandoned) {
                    Log.w(tag, "NSD resolve timed out name=${serviceInfo.serviceName} after ${resolveTimeoutMs}ms")
                    if (isCurrentGeneration()) onFailed(serviceInfo.serviceName, ERROR_TIMEOUT)
                    finishResolve(serviceInfo.serviceName, attempt, isCurrentGeneration)
                }
            }, resolveTimeoutMs, TimeUnit.MILLISECONDS)
        }

        runCatching {
            nsdManager.resolveService(serviceInfo, listener)
        }.onFailure { error ->
            Log.w(tag, "Failed to initiate service resolve", error)
            if (isCurrentGeneration()) onFailed(serviceInfo.serviceName, ERROR_TIMEOUT)
            finishResolve(serviceInfo.serviceName, attempt, isCurrentGeneration)
        }
    }

    /** Frees the in-flight slot, but only for the resolve [attempt] that still owns it. */
    private fun finishResolve(
        serviceName: String,
        attempt: Long,
        isCurrentGeneration: () -> Boolean,
    ) {
        val next = synchronized(lock) {
            if (resolvingServiceName == serviceName && resolveSeq == attempt) {
                resolvingServiceName = null
                timeoutFuture?.cancel(false)
                timeoutFuture = null
            }
            nextResolveLocked(isCurrentGeneration)
        }
        next?.let { pendingInfo -> resolve(pendingInfo, isCurrentGeneration) }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            queuedServiceNames.clear()
            resolvingServiceName = null
            timeoutFuture?.cancel(false)
            timeoutFuture = null
        }
    }

    companion object {
        const val RESOLVE_TIMEOUT_MS: Long = 6_000L

        /** Reported to [onFailed] when no platform error code exists (timeout, or the call itself threw). */
        const val ERROR_TIMEOUT: Int = -1
    }
}
