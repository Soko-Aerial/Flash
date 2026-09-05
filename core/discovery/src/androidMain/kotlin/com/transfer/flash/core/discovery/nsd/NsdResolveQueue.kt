package com.transfer.flash.core.discovery.nsd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Serializes NSD service resolution requests to avoid Android NSD manager daemon concurrency crashes.
 */
internal class NsdResolveQueue(
    private val nsdManager: NsdManager,
    private val tag: String = "NSD-RESOLVE",
    private val onResolvedCallback: (NsdServiceInfo) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val queuedServiceNames = mutableSetOf<String>()
    private var resolvingServiceName: String? = null

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
        return next
    }

    private fun resolve(
        serviceInfo: NsdServiceInfo,
        isCurrentGeneration: () -> Boolean,
    ) {
        if (!isCurrentGeneration()) {
            finishResolve(serviceInfo.serviceName, isCurrentGeneration)
            return
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (isCurrentGeneration()) {
                    Log.w(tag, "NSD resolve failed name=${info.serviceName} error=$errorCode")
                }
                finishResolve(info.serviceName, isCurrentGeneration)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (isCurrentGeneration()) {
                    onResolvedCallback(info)
                }
                finishResolve(info.serviceName, isCurrentGeneration)
            }
        }

        runCatching {
            nsdManager.resolveService(serviceInfo, listener)
        }.onFailure { error ->
            Log.w(tag, "Failed to initiate service resolve", error)
            finishResolve(serviceInfo.serviceName, isCurrentGeneration)
        }
    }

    private fun finishResolve(
        serviceName: String,
        isCurrentGeneration: () -> Boolean,
    ) {
        val next = synchronized(lock) {
            if (resolvingServiceName == serviceName) {
                resolvingServiceName = null
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
        }
    }
}
