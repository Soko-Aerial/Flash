package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.concurrent.SyncSet
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.security.group.GroupProofChallenge
import com.transfer.flash.core.security.group.GroupProofHello
import com.transfer.flash.core.security.group.GroupProofInitiator
import com.transfer.flash.core.security.group.GroupProofMac
import com.transfer.flash.core.security.group.GroupProofResponder
import com.transfer.flash.core.security.group.GroupProofTranscript
import com.transfer.flash.core.security.group.GroupSecretKdf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of a mutual group proof exchange (GM-3, ADR-073).
 */
public enum class GroupProofResult {
    OK,
    FAILED,
    STALE,
    TIMEOUT,
    UNSUPPORTED,
}

/**
 * Rate limiter for failed proof attempts: at most 5 failures per peer per 10 minutes (the VerifyBudget pattern).
 * The session is NEVER dropped on failure.
 */
@OptIn(FlashInternalApi::class)
internal class ProofRateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 10 * 60 * 1000L,
) {
    private class Window(val startedAtMs: Long, var failures: Int)

    private val windows = SyncMap<String, Window>()

    fun canAttempt(peerId: String, nowMs: Long): Boolean {
        val current = windows[peerId]?.takeIf { nowMs - it.startedAtMs < windowMs }
        return (current?.failures ?: 0) < maxFailures
    }

    fun recordFailure(peerId: String, nowMs: Long) {
        val current = windows[peerId]?.takeIf { nowMs - it.startedAtMs < windowMs }
        if (current == null) {
            windows[peerId] = Window(nowMs, 1)
        } else {
            current.failures++
        }
    }

    fun clear(peerId: String) {
        windows.remove(peerId)
    }
}

/**
 * Manages live session group membership mutual proofs (GM-3, ADR-073, protocol "The proof").
 *
 * Rules:
 * 1. Exactly one GroupProof per (peer, group) in flight; 20-second timeout.
 * 2. On success, `provedGroups[peer] += groupId`.
 * 3. It is cleared on session down, and NEVER persisted.
 * 4. Fingerprints come from the live session's key (peerIdentityKey) and local identity key.
 * 5. Rate limit: at most 5 failed proofs per peer per 10 minutes (ProofRateLimiter).
 * 6. Responder privacy: a responder that does not hold the group/epoch answers with random challenge
 *    and then result(ok=false, reason="failed"). Answers "stale" only after a valid proof at an
 *    older epoch it still holds.
 * 7. Peers without "gs1" in localFeatures never receive a FLASH_GMEM frame.
 */
@OptIn(FlashInternalApi::class)
public class GroupProofSessions(
    private val localDeviceId: String,
    private val groupCrypto: GroupCrypto?,
    private val peerIdentityKey: (String) -> ByteArray?,
    private val peerFeatures: (String) -> Set<String> = { emptySet() },
    private val groupSecretStore: GroupSecretStore,
    private val sendFrame: suspend (peerId: String, frame: GroupWireFrame) -> Boolean,
    private val timeSource: FlashTimeSource = SystemTimeSource,
    private val nonceSource: () -> ByteArray = { GroupProofTranscript.randomNonce() },
    private val randomMacSource: () -> ByteArray = { GroupProofTranscript.randomMac() },
    private val timeoutMs: Long = PROOF_TIMEOUT_MS,
    private val onStaleProof: (suspend (peerDeviceId: String, groupId: String) -> Unit)? = null,
) {
    public companion object {
        public const val FEATURE_GS1: String = "gs1"
        public const val PROOF_TIMEOUT_MS: Long = 20_000L
    }

    private val rateLimiter = ProofRateLimiter()

    // Live session in-memory state: peerId -> Set<groupId>. NEVER persisted.
    private val provedGroups = SyncMap<String, SyncSet<String>>()

    private sealed interface InFlightResponder {
        val startedAtMs: Long
    }

    private class RealResponder(
        val responder: GroupProofResponder,
        override val startedAtMs: Long,
    ) : InFlightResponder

    private class DummyResponder(
        override val startedAtMs: Long,
    ) : InFlightResponder

    private class InFlightInitiator(
        val initiator: GroupProofInitiator,
        val startedAtMs: Long,
        val deferred: CompletableDeferred<GroupProofResult>,
    )

    private val activeInitiators = SyncMap<Pair<String, String>, InFlightInitiator>()
    private val activeResponders = SyncMap<Pair<String, String>, InFlightResponder>()

    /**
     * Checks if [peerId] has proved knowledge of the group secret in this live session.
     * Used only for join requests and roster preview (GINV-2). Grants NO chat, call, or file traffic.
     */
    public fun hasProved(peerId: String, groupId: String): Boolean {
        return provedGroups[peerId]?.contains(groupId) == true
    }

    /**
     * Initiates mutual proof of group secret knowledge with [peerId] for [groupId] at [epoch].
     */
    public suspend fun initiateProof(
        peerId: String,
        groupId: String,
        epoch: Long,
    ): GroupProofResult {
        if (!peerFeatures(peerId).contains(FEATURE_GS1)) {
            return GroupProofResult.UNSUPPORTED
        }

        val crypto = groupCrypto ?: return GroupProofResult.FAILED
        val peerKey = peerIdentityKey(peerId) ?: return GroupProofResult.FAILED
        if (peerKey.isEmpty()) return GroupProofResult.FAILED

        val nowMs = timeSource.nowMs()
        val key = peerId to groupId

        val existing = activeInitiators[key]
        if (existing != null) {
            if (nowMs - existing.startedAtMs < timeoutMs) {
                // Already in flight
                return GroupProofResult.FAILED
            } else {
                activeInitiators.remove(key)?.deferred?.complete(GroupProofResult.TIMEOUT)
            }
        }

        val secretRecord = groupSecretStore.get(groupId, epoch) ?: return GroupProofResult.FAILED
        val authKey = GroupSecretKdf.authKey(secretRecord.secret, groupId, epoch)

        val fpI = crypto.sha256(crypto.publicKey)
        val fpR = crypto.sha256(peerKey)

        val initiator = GroupProofInitiator(
            groupId = groupId,
            epoch = epoch,
            authKey = authKey,
            localTlsFingerprint = fpI,
            remoteTlsFingerprint = fpR,
            nonceSource = nonceSource,
        )

        val hello = initiator.createHello()
        val deferred = CompletableDeferred<GroupProofResult>()
        activeInitiators[key] = InFlightInitiator(initiator, nowMs, deferred)

        val wireFrame = GroupWireFrame.GsHello(
            groupId = groupId,
            from = localDeviceId,
            epoch = epoch,
            nonce = hello.nonce,
        )

        val sent = sendFrame(peerId, wireFrame)
        if (!sent) {
            activeInitiators.remove(key)
            return GroupProofResult.FAILED
        }

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: run {
            activeInitiators.remove(key)
            GroupProofResult.TIMEOUT
        }

        return result
    }

    public suspend fun onHello(peerDeviceId: String, frame: GroupWireFrame.GsHello) {
        if (peerDeviceId != frame.from) return
        val nowMs = timeSource.nowMs()

        if (!rateLimiter.canAttempt(peerDeviceId, nowMs)) return
        if (!peerFeatures(peerDeviceId).contains(FEATURE_GS1)) return

        val crypto = groupCrypto ?: return
        val peerKey = peerIdentityKey(peerDeviceId) ?: return
        if (peerKey.isEmpty()) return

        val fpR = crypto.sha256(crypto.publicKey) // We are responder
        val fpI = crypto.sha256(peerKey)

        val key = peerDeviceId to frame.groupId
        val secretRecord = groupSecretStore.get(frame.groupId, frame.epoch)

        if (secretRecord != null) {
            val authKey = GroupSecretKdf.authKey(secretRecord.secret, frame.groupId, frame.epoch)
            val responder = GroupProofResponder(
                groupId = frame.groupId,
                epoch = frame.epoch,
                authKey = authKey,
                localTlsFingerprint = fpR,
                remoteTlsFingerprint = fpI,
                nonceSource = nonceSource,
            )

            val challenge = runCatching {
                responder.receiveHello(GroupProofHello(frame.groupId, frame.epoch, frame.nonce))
            }.getOrNull()

            if (challenge == null) {
                rateLimiter.recordFailure(peerDeviceId, nowMs)
                return
            }

            activeResponders[key] = RealResponder(responder, nowMs)
            sendFrame(
                peerDeviceId,
                GroupWireFrame.GsChallenge(
                    groupId = frame.groupId,
                    from = localDeviceId,
                    epoch = frame.epoch,
                    nonce = challenge.nonce,
                    mac = challenge.mac,
                ),
            )
        } else {
            // Responder privacy: answer with random challenge so a stranger cannot learn who is in the group.
            val fakeNonce = nonceSource()
            val fakeMac = randomMacSource()
            activeResponders[key] = DummyResponder(nowMs)
            sendFrame(
                peerDeviceId,
                GroupWireFrame.GsChallenge(
                    groupId = frame.groupId,
                    from = localDeviceId,
                    epoch = frame.epoch,
                    nonce = fakeNonce,
                    mac = fakeMac,
                ),
            )
        }
    }

    public suspend fun onChallenge(peerDeviceId: String, frame: GroupWireFrame.GsChallenge) {
        if (peerDeviceId != frame.from) return
        val nowMs = timeSource.nowMs()
        val key = peerDeviceId to frame.groupId

        val inFlight = activeInitiators[key] ?: return
        if (nowMs - inFlight.startedAtMs >= timeoutMs) {
            activeInitiators.remove(key)?.deferred?.complete(GroupProofResult.TIMEOUT)
            return
        }

        val proofMac = runCatching {
            inFlight.initiator.receiveChallenge(
                GroupProofChallenge(frame.groupId, frame.epoch, frame.nonce, frame.mac),
            )
        }.getOrNull()

        if (proofMac != null) {
            sendFrame(
                peerDeviceId,
                GroupWireFrame.GsProof(
                    groupId = frame.groupId,
                    from = localDeviceId,
                    epoch = frame.epoch,
                    mac = proofMac.mac,
                ),
            )
        } else {
            rateLimiter.recordFailure(peerDeviceId, nowMs)
            activeInitiators.remove(key)?.deferred?.complete(GroupProofResult.FAILED)
        }
    }

    public suspend fun onProof(peerDeviceId: String, frame: GroupWireFrame.GsProof) {
        if (peerDeviceId != frame.from) return
        val nowMs = timeSource.nowMs()
        val key = peerDeviceId to frame.groupId

        val inFlight = activeResponders.remove(key) ?: return
        if (nowMs - inFlight.startedAtMs >= timeoutMs) return

        when (inFlight) {
            is DummyResponder -> {
                rateLimiter.recordFailure(peerDeviceId, nowMs)
                sendFrame(
                    peerDeviceId,
                    GroupWireFrame.GsResult(
                        groupId = frame.groupId,
                        from = localDeviceId,
                        ok = false,
                        reason = "failed",
                    ),
                )
            }
            is RealResponder -> {
                val valid = inFlight.responder.receiveProof(
                    GroupProofMac(frame.groupId, frame.epoch, frame.mac),
                )

                if (valid) {
                    val currentEpoch = groupSecretStore.current(frame.groupId)?.epoch ?: frame.epoch
                    if (currentEpoch > frame.epoch) {
                        // Stale epoch! Answer stale.
                        sendFrame(
                            peerDeviceId,
                            GroupWireFrame.GsResult(
                                groupId = frame.groupId,
                                from = localDeviceId,
                                ok = false,
                                reason = "stale",
                            ),
                        )
                        onStaleProof?.invoke(peerDeviceId, frame.groupId)
                    } else {
                        // Successful proof!
                        addProved(peerDeviceId, frame.groupId)
                        sendFrame(
                            peerDeviceId,
                            GroupWireFrame.GsResult(
                                groupId = frame.groupId,
                                from = localDeviceId,
                                ok = true,
                                reason = "ok",
                            ),
                        )
                    }
                } else {
                    rateLimiter.recordFailure(peerDeviceId, nowMs)
                    sendFrame(
                        peerDeviceId,
                        GroupWireFrame.GsResult(
                            groupId = frame.groupId,
                            from = localDeviceId,
                            ok = false,
                            reason = "failed",
                        ),
                    )
                }
            }
        }
    }

    public fun onResult(peerDeviceId: String, frame: GroupWireFrame.GsResult) {
        if (peerDeviceId != frame.from) return
        val key = peerDeviceId to frame.groupId
        val inFlight = activeInitiators.remove(key) ?: return

        when (frame.reason) {
            "ok" -> {
                if (frame.ok) {
                    addProved(peerDeviceId, frame.groupId)
                    inFlight.deferred.complete(GroupProofResult.OK)
                } else {
                    inFlight.deferred.complete(GroupProofResult.FAILED)
                }
            }
            "stale" -> {
                inFlight.deferred.complete(GroupProofResult.STALE)
            }
            else -> {
                rateLimiter.recordFailure(peerDeviceId, timeSource.nowMs())
                inFlight.deferred.complete(GroupProofResult.FAILED)
            }
        }
    }

    /**
     * Session down callback: clears in-memory proved groups and cancels active sessions for [peerId].
     */
    public fun onSessionDown(peerId: String) {
        provedGroups.remove(peerId)

        val initiatorsToCancel = activeInitiators.keysSnapshot().filter { it.first == peerId }
        for (k in initiatorsToCancel) {
            activeInitiators.remove(k)?.deferred?.complete(GroupProofResult.FAILED)
        }

        val respondersToRemove = activeResponders.keysSnapshot().filter { it.first == peerId }
        for (k in respondersToRemove) {
            activeResponders.remove(k)
        }
    }

    private fun addProved(peerId: String, groupId: String) {
        provedGroups.getOrPut(peerId) { SyncSet() }.add(groupId)
    }
}
