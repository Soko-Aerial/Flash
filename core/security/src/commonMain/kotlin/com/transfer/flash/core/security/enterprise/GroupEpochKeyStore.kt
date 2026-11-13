@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Durable storage for Hub group E2EE epoch keys (HR10).
 *
 * Implementations protect keys at rest alongside the device's Hub sealing keys:
 * Android Keystore AES-256-GCM wrapping ([AndroidHubGroupEpochKeyStore]), Desktop DPAPI/file vault
 * ([FileHubGroupEpochKeyStore]), and [MemoryHubGroupEpochKeyStore] for tests.
 */
@FlashInternalApi
public interface GroupEpochKeyStore {
    public suspend fun loadAll(): List<GroupEpochKey>
    public suspend fun saveAll(keys: List<GroupEpochKey>)
    public suspend fun clear()
}

/** Binary-safe multi-line codec for persisted [GroupEpochKey] records. */
@FlashInternalApi
public object GroupEpochKeyCodec {
    private const val HEADER = "FLASH_GROUP_KEYS_V1"

    public fun encode(keys: List<GroupEpochKey>): ByteArray {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (k in keys) {
            val payload = GroupRelaySealing.encodeKeyDist(
                GroupKeyDistPayload(
                    version = GroupRelaySealing.FORMAT_VERSION,
                    groupId = k.groupId,
                    epoch = k.epoch,
                    keyId = k.keyId,
                    keyBytes = k.keyBytes,
                    rosterVersion = k.rosterVersion,
                    memberDeviceIds = k.memberDeviceIds,
                    creatorDeviceId = k.creatorDeviceId,
                    createdAtMs = k.createdAtMs,
                ),
            )
            sb.append(payload).append('|').append(if (k.canSend) '1' else '0').append('\n')
        }
        return sb.toString().encodeToByteArray()
    }

    public fun decode(bytes: ByteArray): List<GroupEpochKey> {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return emptyList()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull() != HEADER) return emptyList()
        val out = ArrayList<GroupEpochKey>(lines.size)
        for (line in lines.drop(1)) {
            val sep = line.lastIndexOf('|')
            if (sep <= 0 || sep >= line.length - 1) continue
            val flag = line.substring(sep + 1)
            if (flag != "0" && flag != "1") continue
            val dist = GroupRelaySealing.decodeKeyDist(line.substring(0, sep)) ?: continue
            out += GroupEpochKey(
                groupId = dist.groupId,
                epoch = dist.epoch,
                keyId = dist.keyId,
                keyBytes = dist.keyBytes,
                rosterVersion = dist.rosterVersion,
                memberDeviceIds = dist.memberDeviceIds,
                creatorDeviceId = dist.creatorDeviceId,
                createdAtMs = dist.createdAtMs,
                canSend = flag == "1",
            )
        }
        return out
    }
}

/** In-memory [GroupEpochKeyStore] for unit/integration tests and default construction. */
@FlashInternalApi
public class MemoryHubGroupEpochKeyStore(
    initial: List<GroupEpochKey> = emptyList(),
) : GroupEpochKeyStore {
    private var stored: List<GroupEpochKey> = initial.map { it.copy() }
    public var failSaves: Boolean = false

    override suspend fun loadAll(): List<GroupEpochKey> = stored.map { it.copy() }

    override suspend fun saveAll(keys: List<GroupEpochKey>) {
        if (failSaves) error("Simulated GroupEpochKeyStore save failure")
        stored = keys.map { it.copy() }
    }

    override suspend fun clear() {
        stored = emptyList()
    }
}
