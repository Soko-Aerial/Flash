package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/** Keeps group policy tied to the current enrollment and relay connection. */
@FlashInternalApi
public class HubGroupPolicyMonitor(
    private val credentials: StateFlow<EnrollmentCredentials?>,
    private val connected: () -> Boolean,
    private val fetchPolicy: suspend (hubUrl: String) -> FlashResult<HubGroupPolicy>,
) {
    private data class Snapshot(
        val credentials: EnrollmentCredentials,
        val generation: Long,
        val policy: HubGroupPolicy,
    )

    private val generation = MutableStateFlow(0L)
    private val snapshot = MutableStateFlow<Snapshot?>(null)

    public fun invalidate() {
        generation.update { it + 1 }
    }

    public fun currentPolicy(): HubGroupPolicy {
        val current = snapshot.value ?: return SAFE_DEFAULT
        return if (connected() && current.credentials == credentials.value && current.generation == generation.value) {
            current.policy
        } else {
            SAFE_DEFAULT
        }
    }

    public suspend fun watch(connectedUpdates: Flow<Boolean>, publish: (HubGroupPolicy) -> Unit) {
        try {
            combine(credentials, connectedUpdates, generation) { enrolled, online, revision ->
                Triple(enrolled, online, revision)
            }.collectLatest { (enrolled, online, revision) ->
                snapshot.value = null
                publish(SAFE_DEFAULT)
                if (enrolled == null || !online) return@collectLatest
                while (true) {
                    if (!connected()) {
                        snapshot.value = null
                        publish(SAFE_DEFAULT)
                        delay(RETRY_MS)
                        continue
                    }
                    val policy = fetchPolicy(enrolled.hubUrl).getOrNull()
                    if (credentials.value != enrolled || generation.value != revision) break
                    snapshot.value = policy?.takeIf { connected() }?.let { Snapshot(enrolled, revision, it) }
                    publish(currentPolicy())
                    delay(if (snapshot.value == null) RETRY_MS else REFRESH_MS)
                }
            }
        } finally {
            snapshot.value = null
            publish(SAFE_DEFAULT)
        }
    }

    public companion object {
        public val SAFE_DEFAULT: HubGroupPolicy = HubGroupPolicy(allowPersonalHubLink = false)
        private const val RETRY_MS = 5_000L
        private const val REFRESH_MS = 60_000L
    }
}
