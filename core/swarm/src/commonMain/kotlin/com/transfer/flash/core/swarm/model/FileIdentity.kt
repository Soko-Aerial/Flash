package com.transfer.flash.core.swarm.model

/**
 * Physical file attributes used to detect file modification / corruption on restart (E-22).
 */
public data class FileIdentity(
    public val sizeBytes: Long,
    public val lastModifiedMs: Long,
) {
    init {
        require(sizeBytes >= 0) { "sizeBytes must be >= 0, got $sizeBytes" }
    }
}
