package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Snapshot view of an individual swarm transfer (§4o).
 */
public data class ContentSnapshot(
    public val groupId: String,
    public val root: ContentRoot,
    public val state: SwarmLifecycleState,
    public val waitReason: SwarmWaitReason?,
    public val bytesDone: Long,
    public val totalBytes: Long,
    public val piecesDone: Int,
    public val totalPieces: Int,
    public val holdersOnline: Int,
    public val distributedCopies: Int,
    public val canGoOffline: Boolean,
    public val deliveredTo: Set<String>,
)

/**
 * Point-in-time snapshot of the whole swarm engine state (§4o, §5.1).
 */
public data class SwarmSnapshot(
    public val contents: Map<Pair<String, ContentRoot>, ContentSnapshot>,
)
