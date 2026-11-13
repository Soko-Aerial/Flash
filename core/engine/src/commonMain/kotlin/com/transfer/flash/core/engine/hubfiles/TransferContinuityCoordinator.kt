@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.engine.concurrent.PlatformLock
import com.transfer.flash.core.enterprise.files.HubDownloadRequest
import com.transfer.flash.core.enterprise.files.HubFileTransfers
import com.transfer.flash.core.security.enterprise.HubFileLayout
import com.transfer.flash.core.transfer.TransferRouteContinuityPort
import com.transfer.flash.core.transfer.chunked.IncrementalSha256
import com.transfer.flash.core.transfer.chunked.Sha256
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Stable cross-route identity for a single logical file transfer that may traverse LAN and Hub.
 */
public data class LogicalTransferIdentity(
    public val logicalTransferId: String,
    public val senderDeviceId: String,
    public val recipientDeviceId: String,
    public val fileName: String,
    public val plainBytes: Long,
    public val fileSha256Hex: String? = null,
) {
    public fun isValid(): Boolean {
        if (logicalTransferId.isBlank() || senderDeviceId.isBlank() || recipientDeviceId.isBlank()) return false
        if (fileName.isBlank() || plainBytes <= 0L) return false
        if (fileSha256Hex != null && !Sha256.isValidHex(fileSha256Hex)) return false
        return true
    }

    public fun normalized(): LogicalTransferIdentity = copy(
        fileSha256Hex = fileSha256Hex?.let(Sha256::normalizeHex),
    )

    /**
     * Checks whether [candidate] represents the exact same source file and peer binding.
     * Refuses changed file sizes, peers, or changed whole-file SHA-256 digests.
     */
    public fun matches(candidate: LogicalTransferIdentity): Boolean {
        val a = normalized()
        val b = candidate.normalized()
        if (!a.isValid() || !b.isValid()) return false
        if (a.logicalTransferId != b.logicalTransferId) return false
        if (a.senderDeviceId != b.senderDeviceId) return false
        if (a.recipientDeviceId != b.recipientDeviceId) return false
        if (a.plainBytes != b.plainBytes) return false
        if (a.fileSha256Hex != null && b.fileSha256Hex != null && a.fileSha256Hex != b.fileSha256Hex) {
            return false
        }
        return true
    }
}

public enum class RouteOwner {
    NONE,
    LAN,
    HUB,
}

public enum class ContinuityStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    INCOMPATIBLE,
}

public data class RouteOwnershipClaim(
    public val route: RouteOwner,
    public val epoch: Long,
)

public data class VerifiedByteSpan(
    public val offset: Long,
    public val length: Long,
) {
    public val endExclusive: Long get() = offset + length
}

public data class ContinuityCheckpoint(
    public val identity: LogicalTransferIdentity,
    public val verifiedSpans: List<VerifiedByteSpan>,
    public val activeRoute: RouteOwner,
    public val routeEpoch: Long,
    public val status: ContinuityStatus,
    public val completedPath: String? = null,
    public val finalDigestHex: String? = null,
) {
    public val verifiedBytes: Long
        get() = verifiedSpans.sumOf { it.length }
}

public data class ResumePlan(
    public val compatible: Boolean,
    public val reason: String? = null,
    public val coveredTargetChunks: Set<Int> = emptySet(),
    public val verifiedBytes: Long = 0L,
    public val routeEpoch: Long = 0L,
)

/**
 * Pure byte-range alignment utilities for converting verified chunk bitmaps across incompatible chunk sizes
 * (for example 1,048,560-byte Hub plaintext chunks <-> 65,536-byte LAN chunks).
 */
public object TransferByteAlignment {
    /**
     * Clamps, sorts, and merges overlapping or adjacent verified byte spans within `[0, totalBytes)`.
     */
    public fun normalizeSpans(spans: List<VerifiedByteSpan>, totalBytes: Long): List<VerifiedByteSpan> {
        if (totalBytes <= 0L || spans.isEmpty()) return emptyList()
        val clamped = spans.mapNotNull { span ->
            if (span.length <= 0L) return@mapNotNull null
            val start = max(0L, span.offset)
            val end = min(totalBytes, span.offset + span.length)
            if (end <= start) null else VerifiedByteSpan(start, end - start)
        }.sortedBy { it.offset }

        if (clamped.isEmpty()) return emptyList()
        val merged = ArrayList<VerifiedByteSpan>(clamped.size)
        var current = clamped[0]
        for (i in 1 until clamped.size) {
            val next = clamped[i]
            if (next.offset <= current.endExclusive) {
                val newEnd = max(current.endExclusive, next.endExclusive)
                current = VerifiedByteSpan(current.offset, newEnd - current.offset)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Converts a set of verified chunk indices from a source layout into merged verified byte spans.
     */
    public fun chunkRanges(
        chunkIndexes: Set<Int>,
        chunkSize: Int,
        totalBytes: Long,
    ): List<VerifiedByteSpan> {
        if (chunkSize <= 0 || totalBytes <= 0L || chunkIndexes.isEmpty()) return emptyList()
        val totalChunks = chunkCount(totalBytes, chunkSize)
        val raw = ArrayList<VerifiedByteSpan>(chunkIndexes.size)
        for (idx in chunkIndexes) {
            if (idx !in 0 until totalChunks) continue
            val start = idx.toLong() * chunkSize.toLong()
            val end = min(totalBytes, start + chunkSize.toLong())
            if (end > start) {
                raw.add(VerifiedByteSpan(start, end - start))
            }
        }
        return normalizeSpans(raw, totalBytes)
    }

    /**
     * Returns only those target chunk indices whose entire `[chunkStart, chunkEnd)` range is covered by
     * [verifiedSpans]. Partial tail/head overlaps are excluded so unverified bytes are never treated as complete.
     */
    public fun intersectChunksCoveredBySpans(
        verifiedSpans: List<VerifiedByteSpan>,
        targetChunkSize: Int,
        totalBytes: Long,
    ): Set<Int> {
        if (targetChunkSize <= 0 || totalBytes <= 0L) return emptySet()
        val normalized = normalizeSpans(verifiedSpans, totalBytes)
        if (normalized.isEmpty()) return emptySet()
        val totalChunks = chunkCount(totalBytes, targetChunkSize)
        val covered = LinkedHashSet<Int>()
        for (idx in 0 until totalChunks) {
            val start = idx.toLong() * targetChunkSize.toLong()
            val end = min(totalBytes, start + targetChunkSize.toLong())
            if (isRangeCovered(normalized, start, end)) {
                covered.add(idx)
            }
        }
        return covered
    }

    public fun chunkCount(totalBytes: Long, chunkSize: Int): Int {
        if (totalBytes <= 0L || chunkSize <= 0) return 0
        return ceil(totalBytes.toDouble() / chunkSize.toDouble()).toInt()
    }

    private fun isRangeCovered(merged: List<VerifiedByteSpan>, start: Long, endExclusive: Long): Boolean {
        for (span in merged) {
            if (span.offset <= start && span.endExclusive >= endExclusive) return true
            if (span.offset > start) break
        }
        return false
    }
}

/**
 * Coordinates single active-route ownership, monotonic fencing epochs, verified byte adoption across
 * LAN <-> Hub routes, and atomic single-completion guarantees for a logical transfer.
 */
public class TransferRouteCoordinator {
    private val lock = PlatformLock()
    private val checkpoints = HashMap<String, ContinuityCheckpoint>()

    public fun registerOrGet(identity: LogicalTransferIdentity): ContinuityCheckpoint? {
        val norm = identity.normalized()
        if (!norm.isValid()) return null
        return lock.withLock {
            val existing = checkpoints[norm.logicalTransferId]
            if (existing == null) {
                val created = ContinuityCheckpoint(
                    identity = norm,
                    verifiedSpans = emptyList(),
                    activeRoute = RouteOwner.NONE,
                    routeEpoch = 0L,
                    status = ContinuityStatus.ACTIVE,
                )
                checkpoints[norm.logicalTransferId] = created
                created
            } else if (!existing.identity.matches(norm)) {
                val incompatible = existing.copy(
                    status = ContinuityStatus.INCOMPATIBLE,
                    activeRoute = RouteOwner.NONE,
                )
                checkpoints[norm.logicalTransferId] = incompatible
                null
            } else {
                existing
            }
        }
    }

    public fun snapshot(logicalTransferId: String): ContinuityCheckpoint? =
        lock.withLock { checkpoints[logicalTransferId] }

    public fun tryAcquireRoute(
        identity: LogicalTransferIdentity,
        route: RouteOwner,
    ): RouteOwnershipClaim? {
        if (route == RouteOwner.NONE) return null
        val norm = identity.normalized()
        if (!norm.isValid()) return null
        return lock.withLock {
            val current = checkpoints[norm.logicalTransferId] ?: ContinuityCheckpoint(
                identity = norm,
                verifiedSpans = emptyList(),
                activeRoute = RouteOwner.NONE,
                routeEpoch = 0L,
                status = ContinuityStatus.ACTIVE,
            )
            if (!current.identity.matches(norm)) {
                checkpoints[norm.logicalTransferId] = current.copy(
                    status = ContinuityStatus.INCOMPATIBLE,
                    activeRoute = RouteOwner.NONE,
                )
                return@withLock null
            }
            if (current.status != ContinuityStatus.ACTIVE) return@withLock null
            if (current.activeRoute != RouteOwner.NONE && current.activeRoute != route) {
                return@withLock null
            }
            // Promote known digest if previous state did not have one yet.
            val mergedIdentity = if (current.identity.fileSha256Hex == null && norm.fileSha256Hex != null) {
                current.identity.copy(fileSha256Hex = norm.fileSha256Hex)
            } else {
                current.identity
            }
            val nextEpoch = current.routeEpoch + 1L
            checkpoints[norm.logicalTransferId] = current.copy(
                identity = mergedIdentity,
                activeRoute = route,
                routeEpoch = nextEpoch,
            )
            RouteOwnershipClaim(route = route, epoch = nextEpoch)
        }
    }

    public fun handoverRoute(
        logicalTransferId: String,
        expectedEpoch: Long,
        fromRoute: RouteOwner,
        toRoute: RouteOwner,
    ): RouteOwnershipClaim? {
        if (fromRoute == RouteOwner.NONE || toRoute == RouteOwner.NONE) return null
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock null
            if (current.status != ContinuityStatus.ACTIVE) return@withLock null
            if (current.activeRoute != fromRoute || current.routeEpoch != expectedEpoch) return@withLock null
            val nextEpoch = current.routeEpoch + 1L
            checkpoints[logicalTransferId] = current.copy(
                activeRoute = toRoute,
                routeEpoch = nextEpoch,
            )
            RouteOwnershipClaim(route = toRoute, epoch = nextEpoch)
        }
    }

    public fun releaseRoute(
        logicalTransferId: String,
        expectedEpoch: Long,
        route: RouteOwner,
    ): Boolean {
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.activeRoute != route || current.routeEpoch != expectedEpoch) return@withLock false
            checkpoints[logicalTransferId] = current.copy(activeRoute = RouteOwner.NONE)
            true
        }
    }

    public fun recordVerifiedSpan(
        logicalTransferId: String,
        expectedEpoch: Long,
        span: VerifiedByteSpan,
    ): Boolean {
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.status != ContinuityStatus.ACTIVE) return@withLock false
            if (current.activeRoute == RouteOwner.NONE || current.routeEpoch != expectedEpoch) return@withLock false
            val updated = TransferByteAlignment.normalizeSpans(
                current.verifiedSpans + span,
                current.identity.plainBytes,
            )
            checkpoints[logicalTransferId] = current.copy(verifiedSpans = updated)
            true
        }
    }

    public fun recordVerifiedChunks(
        logicalTransferId: String,
        expectedEpoch: Long,
        chunkIndexes: Set<Int>,
        chunkSize: Int,
    ): Boolean {
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.status != ContinuityStatus.ACTIVE) return@withLock false
            if (current.activeRoute == RouteOwner.NONE || current.routeEpoch != expectedEpoch) return@withLock false
            val spans = TransferByteAlignment.chunkRanges(chunkIndexes, chunkSize, current.identity.plainBytes)
            val updated = TransferByteAlignment.normalizeSpans(
                current.verifiedSpans + spans,
                current.identity.plainBytes,
            )
            checkpoints[logicalTransferId] = current.copy(verifiedSpans = updated)
            true
        }
    }

    public fun planResume(
        candidateIdentity: LogicalTransferIdentity,
        targetChunkSize: Int,
    ): ResumePlan {
        val norm = candidateIdentity.normalized()
        if (!norm.isValid() || targetChunkSize <= 0) {
            return ResumePlan(compatible = false, reason = "invalid_identity_or_layout")
        }
        return lock.withLock {
            val current = checkpoints[norm.logicalTransferId]
                ?: return@withLock ResumePlan(compatible = false, reason = "unknown_transfer")
            if (current.status == ContinuityStatus.CANCELLED) {
                return@withLock ResumePlan(compatible = false, reason = "cancelled")
            }
            if (current.status == ContinuityStatus.COMPLETED) {
                return@withLock ResumePlan(compatible = false, reason = "already_completed")
            }
            if (current.status == ContinuityStatus.INCOMPATIBLE || !current.identity.matches(norm)) {
                return@withLock ResumePlan(compatible = false, reason = "source_identity_mismatch")
            }
            val covered = TransferByteAlignment.intersectChunksCoveredBySpans(
                verifiedSpans = current.verifiedSpans,
                targetChunkSize = targetChunkSize,
                totalBytes = current.identity.plainBytes,
            )
            ResumePlan(
                compatible = true,
                coveredTargetChunks = covered,
                verifiedBytes = current.verifiedBytes,
                routeEpoch = current.routeEpoch,
            )
        }
    }

    /**
     * Adopts verified bytes from an interrupted Hub download onto a LAN [ReceivePipeline] chunk layout.
     * Validates identity, reads plaintext spans via [HubFileTransfers.readPartSpan], checks optional chunk/byte
     * integrity via [verifyChunkPlaintext], and invokes [writeLanChunk] only for 100%-covered LAN chunks.
     */
    public suspend fun adoptHubToLan(
        transfers: HubFileTransfers,
        fileId: String,
        candidateIdentity: LogicalTransferIdentity,
        lanChunkSize: Int,
        writeLanChunk: (chunkIndex: Int, offset: Long, bytes: ByteArray) -> Boolean,
        verifyChunkPlaintext: ((chunkIndex: Int, bytes: ByteArray) -> Boolean)? = null,
    ): ResumePlan {
        val norm = candidateIdentity.normalized()
        if (!norm.isValid() || lanChunkSize <= 0) {
            return ResumePlan(compatible = false, reason = "invalid_identity_or_layout")
        }
        val hubSnapshot = transfers.exportVerifiedChunks(fileId)
            ?: return ResumePlan(compatible = false, reason = "no_verified_hub_state")
        if (hubSnapshot.plainBytes != norm.plainBytes) {
            return ResumePlan(compatible = false, reason = "size_mismatch")
        }

        val current = lock.withLock { checkpoints[norm.logicalTransferId] }
        if (current != null) {
            if (current.status == ContinuityStatus.CANCELLED) {
                return ResumePlan(compatible = false, reason = "cancelled")
            }
            if (current.status == ContinuityStatus.COMPLETED) {
                return ResumePlan(compatible = false, reason = "already_completed")
            }
            if (current.status == ContinuityStatus.INCOMPATIBLE || !current.identity.matches(norm)) {
                return ResumePlan(compatible = false, reason = "source_identity_mismatch")
            }
        }

        val hubSpans = TransferByteAlignment.chunkRanges(
            chunkIndexes = hubSnapshot.doneChunks,
            chunkSize = hubSnapshot.plainChunkBytes,
            totalBytes = hubSnapshot.plainBytes,
        )
        val mergedSpans = lock.withLock {
            val base = current?.verifiedSpans.orEmpty()
            TransferByteAlignment.normalizeSpans(base + hubSpans, hubSnapshot.plainBytes)
        }
        val candidateChunks = TransferByteAlignment.intersectChunksCoveredBySpans(
            verifiedSpans = mergedSpans,
            targetChunkSize = lanChunkSize,
            totalBytes = hubSnapshot.plainBytes,
        )
        if (candidateChunks.isEmpty()) {
            return ResumePlan(compatible = true, coveredTargetChunks = emptySet(), verifiedBytes = 0L)
        }

        val acceptedChunks = LinkedHashSet<Int>()
        for (idx in candidateChunks.sorted()) {
            val offset = idx.toLong() * lanChunkSize.toLong()
            val len = min(lanChunkSize.toLong(), hubSnapshot.plainBytes - offset).toInt()
            val bytes = transfers.readPartSpan(fileId, offset, len) ?: continue
            if (bytes.size != len) continue
            if (verifyChunkPlaintext != null && !verifyChunkPlaintext(idx, bytes)) {
                // Tampered partial file on disk: refuse adopting unverified/tampered bytes cleanly.
                return ResumePlan(compatible = false, reason = "tampered_partial_bytes")
            }
            if (writeLanChunk(idx, offset, bytes)) {
                acceptedChunks.add(idx)
            }
        }

        val finalSpans = TransferByteAlignment.chunkRanges(acceptedChunks, lanChunkSize, hubSnapshot.plainBytes)
        lock.withLock {
            val latest = checkpoints[norm.logicalTransferId]
            if (latest != null && latest.status == ContinuityStatus.ACTIVE && latest.identity.matches(norm)) {
                checkpoints[norm.logicalTransferId] = latest.copy(
                    verifiedSpans = TransferByteAlignment.normalizeSpans(
                        latest.verifiedSpans + finalSpans,
                        norm.plainBytes,
                    ),
                )
            }
        }

        val verifiedTotal = finalSpans.sumOf { it.length }
        return ResumePlan(
            compatible = true,
            coveredTargetChunks = acceptedChunks,
            verifiedBytes = verifiedTotal,
            routeEpoch = lock.withLock { checkpoints[norm.logicalTransferId]?.routeEpoch ?: 0L },
        )
    }

    /**
     * Adopts verified bytes already stored by a LAN transfer into [HubFileTransfers] partial storage
     * so Hub downloads skip already-verified 1 MiB plaintext chunks.
     */
    public suspend fun adoptLanToHub(
        transfers: HubFileTransfers,
        candidateIdentity: LogicalTransferIdentity,
        request: HubDownloadRequest,
        readLanSpan: (offset: Long, length: Int) -> ByteArray?,
        verifySpanIntegrity: ((offset: Long, bytes: ByteArray) -> Boolean)? = null,
    ): ResumePlan {
        val norm = candidateIdentity.normalized()
        if (!norm.isValid()) {
            return ResumePlan(compatible = false, reason = "invalid_identity")
        }
        val ticket = request.ticket
        if (ticket.plainBytes != norm.plainBytes) {
            return ResumePlan(compatible = false, reason = "size_mismatch")
        }
        val layout = HubFileLayout.of(ticket.plainBytes, ticket.cipherChunkBytes)
            ?: return ResumePlan(compatible = false, reason = "invalid_hub_layout")
        if (layout.chunkCount != ticket.chunkCount) {
            return ResumePlan(compatible = false, reason = "chunk_count_mismatch")
        }

        val current = lock.withLock { checkpoints[norm.logicalTransferId] }
            ?: return ResumePlan(compatible = false, reason = "unknown_transfer")
        if (current.status == ContinuityStatus.CANCELLED) {
            return ResumePlan(compatible = false, reason = "cancelled")
        }
        if (current.status == ContinuityStatus.COMPLETED) {
            return ResumePlan(compatible = false, reason = "already_completed")
        }
        if (current.status == ContinuityStatus.INCOMPATIBLE || !current.identity.matches(norm)) {
            return ResumePlan(compatible = false, reason = "source_identity_mismatch")
        }
        val reqPlainSha = request.plainSha256
        if (reqPlainSha != null &&
            current.identity.fileSha256Hex != null &&
            !Sha256.hexEqualsConstantTime(
                Sha256.normalizeHex(reqPlainSha),
                current.identity.fileSha256Hex,
            )
        ) {
            return ResumePlan(compatible = false, reason = "source_digest_mismatch")
        }

        val coveredHubChunks = TransferByteAlignment.intersectChunksCoveredBySpans(
            verifiedSpans = current.verifiedSpans,
            targetChunkSize = layout.plainChunkBytes,
            totalBytes = ticket.plainBytes,
        )
        if (coveredHubChunks.isEmpty()) {
            return ResumePlan(compatible = true, coveredTargetChunks = emptySet(), verifiedBytes = 0L)
        }

        var tamperDetected = false
        val validHubChunks = LinkedHashSet<Int>()
        val cache = HashMap<Int, ByteArray>()
        for (idx in coveredHubChunks.sorted()) {
            val offset = layout.plainOffset(idx)
            val len = layout.plainLength(idx)
            val bytes = readLanSpan(offset, len) ?: continue
            if (bytes.size != len) continue
            if (verifySpanIntegrity != null && !verifySpanIntegrity(offset, bytes)) {
                tamperDetected = true
                break
            }
            validHubChunks.add(idx)
            cache[idx] = bytes
        }
        if (tamperDetected) {
            return ResumePlan(compatible = false, reason = "tampered_partial_bytes")
        }

        val seeded = transfers.seedDownloadFromVerifiedBytes(
            request = request,
            verifiedChunkIndexes = validHubChunks,
            readChunkPlaintext = { idx, _, _ -> cache[idx] },
        )
        if (seeded <= 0 && validHubChunks.isNotEmpty()) {
            return ResumePlan(compatible = false, reason = "seed_storage_failed")
        }

        val adoptedSpans = TransferByteAlignment.chunkRanges(validHubChunks, layout.plainChunkBytes, ticket.plainBytes)
        return ResumePlan(
            compatible = true,
            coveredTargetChunks = validHubChunks,
            verifiedBytes = adoptedSpans.sumOf { it.length },
            routeEpoch = current.routeEpoch,
        )
    }

    /**
     * Marks the logical transfer as completed atomically.
     * Enforces single completion: stale/losing routes receive `false` so any duplicate temp output is discarded.
     */
    public fun completeTransfer(
        logicalTransferId: String,
        expectedEpoch: Long,
        route: RouteOwner,
        publishedPath: String,
        actualSha256Hex: String,
    ): Boolean {
        if (!Sha256.isValidHex(actualSha256Hex) || publishedPath.isBlank()) return false
        val normalizedDigest = Sha256.normalizeHex(actualSha256Hex)
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.status != ContinuityStatus.ACTIVE) return@withLock false
            if (current.activeRoute != route || current.routeEpoch != expectedEpoch) return@withLock false
            val expectedDigest = current.identity.fileSha256Hex
            if (expectedDigest != null && !Sha256.hexEqualsConstantTime(expectedDigest, normalizedDigest)) {
                return@withLock false
            }
            checkpoints[logicalTransferId] = current.copy(
                activeRoute = RouteOwner.NONE,
                status = ContinuityStatus.COMPLETED,
                completedPath = publishedPath,
                finalDigestHex = normalizedDigest,
                verifiedSpans = listOf(VerifiedByteSpan(0L, current.identity.plainBytes)),
            )
            true
        }
    }

    public fun cancelTransfer(logicalTransferId: String): Boolean {
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.status == ContinuityStatus.COMPLETED) return@withLock false
            checkpoints[logicalTransferId] = current.copy(
                activeRoute = RouteOwner.NONE,
                status = ContinuityStatus.CANCELLED,
            )
            true
        }
    }

    /**
     * Marks an outbound logical transfer completed after the sender confirms completion on [route].
     */
    public fun completeOutboundTransfer(
        logicalTransferId: String,
        expectedEpoch: Long,
        route: RouteOwner,
    ): Boolean {
        return lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock false
            if (current.status != ContinuityStatus.ACTIVE) return@withLock false
            if (current.activeRoute != route || current.routeEpoch != expectedEpoch) return@withLock false
            checkpoints[logicalTransferId] = current.copy(
                activeRoute = RouteOwner.NONE,
                status = ContinuityStatus.COMPLETED,
                verifiedSpans = listOf(VerifiedByteSpan(0L, current.identity.plainBytes)),
            )
            true
        }
    }

    /**
     * Discards unverified partial byte spans after an integrity failure and releases active route ownership.
     */
    public fun failIntegrity(logicalTransferId: String) {
        lock.withLock {
            val current = checkpoints[logicalTransferId] ?: return@withLock
            if (current.status != ContinuityStatus.ACTIVE) return@withLock
            checkpoints[logicalTransferId] = current.copy(
                activeRoute = RouteOwner.NONE,
                verifiedSpans = emptyList(),
            )
        }
    }
}

/**
 * Production bridge between `:core:transfer` ([TransferRouteContinuityPort]), [TransferRouteCoordinator],
 * and [HubFileTransfers] on both Android and Desktop engines.
 */
public class EngineTransferRouteContinuityPort(
    private val localDeviceId: () -> String,
    private val coordinator: TransferRouteCoordinator,
    private val transfersProvider: () -> HubFileTransfers?,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : TransferRouteContinuityPort {

    private data class LanTrackedTransfer(
        val fileId: String,
        val transferId: String,
        val epoch: Long,
        val chunkSize: Int,
        val localPath: String?,
        val fileSha256Hex: String?,
    )

    private val trackLock = PlatformLock()
    private val trackedByFileId = HashMap<String, LanTrackedTransfer>()

    override fun tryAcquireLanRoute(
        transferId: String,
        fileId: String,
        fileName: String,
        plainBytes: Long,
        peerDeviceId: String?,
        isSending: Boolean,
        chunkSize: Int,
        localPath: String?,
        fileSha256Hex: String?,
    ): Boolean {
        val normFileId = fileId.ifBlank { transferId }
        val selfId = localDeviceId().ifBlank { "local" }
        val remoteId = peerDeviceId?.takeIf { it.isNotBlank() } ?: "peer"
        val identity = LogicalTransferIdentity(
            logicalTransferId = normFileId,
            senderDeviceId = if (isSending) selfId else remoteId,
            recipientDeviceId = if (isSending) remoteId else selfId,
            fileName = fileName,
            plainBytes = plainBytes,
            fileSha256Hex = fileSha256Hex,
        )
        val claim = coordinator.tryAcquireRoute(identity, RouteOwner.LAN) ?: return false
        trackLock.withLock {
            val prev = trackedByFileId[normFileId]
            trackedByFileId[normFileId] = LanTrackedTransfer(
                fileId = normFileId,
                transferId = transferId,
                epoch = claim.epoch,
                chunkSize = if (chunkSize > 0) chunkSize else (prev?.chunkSize ?: 65_536),
                localPath = localPath ?: prev?.localPath,
                fileSha256Hex = fileSha256Hex ?: prev?.fileSha256Hex,
            )
        }
        return true
    }

    override fun recordLanVerifiedChunks(
        transferId: String,
        fileId: String,
        chunkIndexes: Collection<Int>,
    ) {
        if (chunkIndexes.isEmpty()) return
        val normFileId = fileId.ifBlank { transferId }
        val tracked = trackLock.withLock { trackedByFileId[normFileId] } ?: return
        coordinator.recordVerifiedChunks(
            logicalTransferId = normFileId,
            expectedEpoch = tracked.epoch,
            chunkIndexes = chunkIndexes.toSet(),
            chunkSize = tracked.chunkSize,
        )
    }

    override fun coveredLanChunksFromHub(
        fileId: String,
        fileName: String,
        plainBytes: Long,
        senderDeviceId: String?,
        lanChunkSize: Int,
    ): List<Int> {
        if (fileId.isBlank() || plainBytes <= 0L || lanChunkSize <= 0) return emptyList()
        val existing = coordinator.snapshot(fileId) ?: return emptyList()
        val identity = LogicalTransferIdentity(
            logicalTransferId = fileId,
            senderDeviceId = senderDeviceId?.takeIf { it.isNotBlank() } ?: existing.identity.senderDeviceId,
            recipientDeviceId = existing.identity.recipientDeviceId,
            fileName = fileName,
            plainBytes = plainBytes,
        )
        val plan = coordinator.planResume(identity, lanChunkSize)
        return if (plan.compatible) plan.coveredTargetChunks.sorted() else emptyList()
    }

    override suspend fun adoptHubBytesIntoLanFile(
        transferId: String,
        fileId: String,
        fileName: String,
        plainBytes: Long,
        senderDeviceId: String?,
        lanChunkSize: Int,
        destPath: String,
    ): List<Int> {
        val transfers = transfersProvider() ?: return emptyList()
        if (fileId.isBlank() || plainBytes <= 0L || lanChunkSize <= 0 || destPath.isBlank()) return emptyList()
        val existing = coordinator.snapshot(fileId) ?: return emptyList()
        val selfId = localDeviceId().ifBlank { "local" }
        val identity = LogicalTransferIdentity(
            logicalTransferId = fileId,
            senderDeviceId = senderDeviceId?.takeIf { it.isNotBlank() } ?: existing.identity.senderDeviceId,
            recipientDeviceId = if (existing.identity.recipientDeviceId.isNotBlank()) existing.identity.recipientDeviceId else selfId,
            fileName = fileName,
            plainBytes = plainBytes,
        )
        var handle: okio.FileHandle? = null
        return try {
            val plan = coordinator.adoptHubToLan(
                transfers = transfers,
                fileId = fileId,
                candidateIdentity = identity,
                lanChunkSize = lanChunkSize,
                writeLanChunk = { _, offset, bytes ->
                    val opened = handle ?: fileSystem.openReadWrite(destPath.toPath()).also { handle = it }
                    opened.write(offset, bytes, 0, bytes.size)
                    true
                },
            )
            handle?.flush()
            if (plan.compatible) plan.coveredTargetChunks.sorted() else emptyList()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { handle?.close() }
        }
    }

    /** Reads [length] bytes at [offset] from the tracked LAN partial file for LAN -> Hub resumption. */
    public fun readLanPartialSpan(fileId: String, offset: Long, length: Int): ByteArray? {
        if (fileId.isBlank() || offset < 0L || length <= 0) return null
        val path = trackLock.withLock { trackedByFileId[fileId]?.localPath } ?: return null
        return try {
            val p = path.toPath()
            if (!fileSystem.exists(p)) return null
            fileSystem.openReadOnly(p).use { handle ->
                val out = ByteArray(length)
                var totalRead = 0
                while (totalRead < length) {
                    val read = handle.read(offset + totalRead, out, totalRead, length - totalRead)
                    if (read <= 0) break
                    totalRead += read
                }
                if (totalRead == length) out else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun releaseLanRoute(transferId: String, fileId: String) {
        val normFileId = fileId.ifBlank { transferId }
        val tracked = trackLock.withLock { trackedByFileId[normFileId] } ?: return
        coordinator.releaseRoute(normFileId, tracked.epoch, RouteOwner.LAN)
    }

    override fun completeLanTransfer(
        transferId: String,
        fileId: String,
        isSending: Boolean,
        publishedPath: String?,
        actualSha256Hex: String?,
    ): Boolean {
        val normFileId = fileId.ifBlank { transferId }
        val tracked = trackLock.withLock { trackedByFileId[normFileId] } ?: return !isTerminalElsewhere(normFileId)
        val won = if (isSending) {
            coordinator.completeOutboundTransfer(normFileId, tracked.epoch, RouteOwner.LAN)
        } else {
            val resolvedPath = publishedPath?.takeIf { it.isNotBlank() } ?: tracked.localPath ?: return false
            val resolvedDigest = actualSha256Hex?.takeIf { Sha256.isValidHex(it) } ?: computeSha256Hex(resolvedPath)
                ?: return false
            coordinator.completeTransfer(normFileId, tracked.epoch, RouteOwner.LAN, resolvedPath, resolvedDigest)
        }
        if (won) trackLock.withLock { trackedByFileId.remove(normFileId) }
        return won
    }

    override fun failLanIntegrity(transferId: String, fileId: String) {
        val normFileId = fileId.ifBlank { transferId }
        coordinator.failIntegrity(normFileId)
    }

    override fun cancelLogicalTransfer(transferId: String, fileId: String?) {
        val normFileId = fileId?.takeIf { it.isNotBlank() } ?: transferId
        coordinator.cancelTransfer(normFileId)
        trackLock.withLock { trackedByFileId.remove(normFileId) }
    }

    override fun isTerminalElsewhere(fileId: String): Boolean {
        val status = coordinator.snapshot(fileId)?.status
        return status == ContinuityStatus.COMPLETED || status == ContinuityStatus.CANCELLED
    }

    private fun computeSha256Hex(path: String): String? = try {
        val acc = IncrementalSha256()
        val buf = ByteArray(65_536)
        fileSystem.source(path.toPath()).buffer().use { src ->
            while (true) {
                val read = src.read(buf)
                if (read < 0) break
                if (read > 0) acc.update(buf, 0, read)
            }
        }
        acc.digestHex()
    } catch (_: Throwable) {
        null
    }
}
