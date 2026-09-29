package com.transfer.flash.core.network.sweep

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Probes a list of hosts with bounded concurrency and returns the ones whose port answered.
 *
 * At most [concurrency] probes are in flight, so a /24 with nothing on it takes about
 * `ceil(253 / 32) * timeout` (about 2.4 s at the defaults) and the radio never sees a burst of 254
 * simultaneous SYNs. Hosts are taken from a queue in the order given, so [SubnetSweepPlan]'s
 * nearest-first order decides which are probed first.
 *
 * Cancelling the caller stops new probes at once; a probe already in flight ends within its own
 * timeout. A probe that throws (other than cancellation) counts as a miss.
 */
internal class SubnetSweeper(
    private val probe: HostProbe,
    private val concurrency: Int = SweepController.DEFAULT_CONCURRENCY,
    private val timeoutMs: Int = SweepController.DEFAULT_TIMEOUT_MS,
) {
    init {
        require(concurrency > 0) { "concurrency must be positive" }
    }

    /**
     * @param onProgress called after each finished probe with the number finished so far.
     * @return the hosts that answered, in the order they were given.
     */
    suspend fun sweep(hosts: List<String>, port: Int, onProgress: (scanned: Int) -> Unit = {}): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val queue = Channel<String>(Channel.UNLIMITED)
        for (host in hosts) queue.trySend(host)
        queue.close()

        val answered = MutableStateFlow<Set<String>>(emptySet())
        val finished = MutableStateFlow(0)
        coroutineScope {
            List(minOf(concurrency, hosts.size)) {
                launch {
                    for (host in queue) {
                        val open = try {
                            probe.isOpen(host, port, timeoutMs)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (open) answered.update { it + host }
                        onProgress(finished.updateAndGet { it + 1 })
                    }
                }
            }.joinAll()
        }
        val hits = answered.value
        return hosts.filter { it in hits }
    }
}
