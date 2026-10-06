package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.SwarmWaitReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaitClassifierTest {

    private fun createContext(
        systemSuspended: Boolean = false,
        networkUp: Boolean = true,
        freeBytes: Long = 10L * 1024 * 1024 * 1024,
        remainingBytes: Long = 100L * 1024 * 1024,
        storageUnavailable: Boolean = false,
        isOrigin: Boolean = false,
        isOriginConnected: Boolean = true,
        isOriginSourceLost: Boolean = false,
        hasConnectedHoldersForMissing: Boolean = true,
        hasDisconnectedHoldersForMissing: Boolean = false,
    ) = WaitClassificationContext(
        systemSuspended = systemSuspended,
        networkUp = networkUp,
        freeBytes = freeBytes,
        remainingBytes = remainingBytes,
        storageUnavailable = storageUnavailable,
        isOrigin = isOrigin,
        isOriginConnected = isOriginConnected,
        isOriginSourceLost = isOriginSourceLost,
        hasConnectedHoldersForMissing = hasConnectedHoldersForMissing,
        hasDisconnectedHoldersForMissing = hasDisconnectedHoldersForMissing,
    )

    @Test
    fun `table 8_1 classifications match expected reasons`() {
        // System suspend
        assertEquals(
            SwarmWaitReason.WAITING_FOR_SYSTEM,
            WaitClassifier.classify(createContext(systemSuspended = true))
        )

        // Network down
        assertEquals(
            SwarmWaitReason.WAITING_FOR_NETWORK,
            WaitClassifier.classify(createContext(networkUp = false))
        )

        // Low space
        assertEquals(
            SwarmWaitReason.WAITING_FOR_SPACE,
            WaitClassifier.classify(createContext(freeBytes = 50L, remainingBytes = 100L))
        )

        // Storage unavailable
        assertEquals(
            SwarmWaitReason.WAITING_FOR_STORAGE,
            WaitClassifier.classify(createContext(storageUnavailable = true))
        )

        // Origin offline, missing pieces held only by origin
        assertEquals(
            SwarmWaitReason.WAITING_FOR_SENDER,
            WaitClassifier.classify(
                createContext(
                    isOriginConnected = false,
                    isOriginSourceLost = false,
                    hasConnectedHoldersForMissing = false,
                )
            )
        )

        // Origin source lost and no holders
        assertEquals(
            SwarmWaitReason.WAITING_FOR_HOLDERS,
            WaitClassifier.classify(
                createContext(
                    isOriginConnected = true,
                    isOriginSourceLost = true,
                    hasConnectedHoldersForMissing = false,
                )
            )
        )

        // Disconnected holders known, no connected holders
        assertEquals(
            SwarmWaitReason.WAITING_FOR_SESSION,
            WaitClassifier.classify(
                createContext(
                    isOriginConnected = false,
                    isOriginSourceLost = true,
                    hasConnectedHoldersForMissing = false,
                    hasDisconnectedHoldersForMissing = true,
                )
            )
        )

        // Active download possible
        assertNull(
            WaitClassifier.classify(
                createContext(
                    hasConnectedHoldersForMissing = true,
                )
            )
        )

        // Origin role does not wait for download holders
        assertNull(
            WaitClassifier.classify(
                createContext(
                    isOrigin = true,
                    hasConnectedHoldersForMissing = false,
                )
            )
        )
    }
}
