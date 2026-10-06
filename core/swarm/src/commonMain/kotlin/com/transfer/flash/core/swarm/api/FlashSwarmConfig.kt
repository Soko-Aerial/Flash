package com.transfer.flash.core.swarm.api

import com.transfer.flash.core.swarm.engine.SwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmProfile

public enum class FlashSwarmProfile {
    LOW,
    MEDIUM,
    HIGH;

    public fun toSwarmProfile(): SwarmProfile = when (this) {
        LOW -> SwarmProfile.LOW
        MEDIUM -> SwarmProfile.MEDIUM
        HIGH -> SwarmProfile.HIGH
    }
}

/**
 * Public configuration for swarm transfer execution (§5.2, SW-8).
 */
public data class FlashSwarmConfig(
    public val profile: FlashSwarmProfile = FlashSwarmProfile.MEDIUM,
    public val autoAccept: Boolean = false,
    public val servingEnabled: Boolean = true,
) {
    public fun toEngineConfig(): SwarmConfig = SwarmConfig(
        profile = profile.toSwarmProfile(),
        autoAcceptIncoming = autoAccept,
        servingEnabled = servingEnabled,
    )
}
