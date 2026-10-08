package com.transfer.flash.core.swarm.api

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Snapshot of a swarm transfer's progress and health for the UI (§5.5, SW-8).
 */
public data class FlashSwarmStatus(
    val root: ContentRoot,
    val groupId: String,
    val holdersOnline: Int,
    val distributedCopies: Int,
    val canGoOffline: Boolean,
    val waitReason: SwarmWaitReason?,
    val piecesDone: Int,
    val totalPieces: Int,
    val bytesDone: Long,
    val totalBytes: Long,
    val isComplete: Boolean,
    val pieceBlocks: List<Int> = emptyList(),
    /** Sender only: each member's progress on this file (empty on a receiver). */
    val recipients: List<com.transfer.flash.core.swarm.engine.RecipientSnapshot> = emptyList(),
)
