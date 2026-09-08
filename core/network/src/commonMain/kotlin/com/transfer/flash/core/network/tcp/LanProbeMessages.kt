package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

public data class LanProbeHello(
    public val protocolVersion: Int,
    public val deviceId: String,
    public val friendlyName: String,
)

/**
 * Delivery-ACK frame payload (plan C4.8): sent by a receiver to confirm it
 * processed a specific [LanProbeData]-enveloped frame. Correlation is by
 * [ackedFrameId] — sender-chosen UUID echoed back verbatim.
 */
internal data class LanProbeAck(
    val protocolVersion: Int,
    val senderDeviceId: String,
    val senderFriendlyName: String,
    val ackedFrameId: String,
)

/**
 * Ack-request envelope (plan C4.8). The payload itself travels as the NEXT
 * line on the wire (single-line UTF-8 text, per existing line framing);
 * [frameId] lets the receiver echo a [LanProbeAck] for at-least-once
 * correlation. Additive: sessions that never emit FLASH_DATA keep the old
 * wire format byte-for-byte.
 */
internal data class LanProbeData(
    val protocolVersion: Int,
    val senderDeviceId: String,
    val senderFriendlyName: String,
    val frameId: String,
)

@OptIn(FlashInternalApi::class)
internal object LanProbeMessages {
    const val HELLO_PREFIX = "FLASH_HELLO"
    const val OK_PREFIX = "FLASH_OK"
    const val DISCONNECT_PREFIX = "FLASH_DISCONNECT"
    const val PING_PREFIX = "FLASH_PING"
    const val PONG_PREFIX = "FLASH_PONG"
    const val ACK_PREFIX = "FLASH_ACK"
    const val DATA_PREFIX = "FLASH_DATA"

    fun hello(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            HELLO_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun ok(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            OK_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun disconnect(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            DISCONNECT_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun ping(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            PING_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun pong(protocolVersion: Int, deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            PONG_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun parseHello(line: String): LanProbeHello? = parse(line, HELLO_PREFIX)

    fun parseOk(line: String): LanProbeHello? = parse(line, OK_PREFIX)

    fun parseDisconnect(line: String): LanProbeHello? = parse(line, DISCONNECT_PREFIX)

    fun parsePing(line: String): LanProbeHello? = parse(line, PING_PREFIX)

    fun parsePong(line: String): LanProbeHello? = parse(line, PONG_PREFIX)

    fun ack(protocolVersion: Int, deviceId: String, friendlyName: String, ackedFrameId: String): String {
        return FlashTextFraming.encodeFields(
            ACK_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
            "frameId" to ackedFrameId,
        )
    }

    fun data(protocolVersion: Int, deviceId: String, friendlyName: String, frameId: String): String {
        return FlashTextFraming.encodeFields(
            DATA_PREFIX,
            "version" to protocolVersion.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
            "frameId" to frameId,
        )
    }

    fun parseAck(line: String): LanProbeAck? {
        val fields = FlashTextFraming.parseFields(line, ACK_PREFIX) ?: return null
        val version = fields["version"]?.toIntOrNull() ?: return null
        val deviceId = fields["deviceId"].orEmpty()
        val name = fields["name"].orEmpty()
        val frameId = fields["frameId"].orEmpty()
        if (deviceId.isBlank() || name.isBlank() || frameId.isBlank()) return null

        return LanProbeAck(
            protocolVersion = version,
            senderDeviceId = deviceId,
            senderFriendlyName = name,
            ackedFrameId = frameId,
        )
    }

    fun parseData(line: String): LanProbeData? {
        val fields = FlashTextFraming.parseFields(line, DATA_PREFIX) ?: return null
        val version = fields["version"]?.toIntOrNull() ?: return null
        val deviceId = fields["deviceId"].orEmpty()
        val name = fields["name"].orEmpty()
        val frameId = fields["frameId"].orEmpty()
        if (deviceId.isBlank() || name.isBlank() || frameId.isBlank()) return null

        return LanProbeData(
            protocolVersion = version,
            senderDeviceId = deviceId,
            senderFriendlyName = name,
            frameId = frameId,
        )
    }

    private fun parse(line: String, prefix: String): LanProbeHello? {
        val fields = FlashTextFraming.parseFields(line, prefix) ?: return null
        val version = fields["version"]?.toIntOrNull() ?: return null
        val deviceId = fields["deviceId"].orEmpty()
        val name = fields["name"].orEmpty()
        if (deviceId.isBlank() || name.isBlank()) return null

        return LanProbeHello(
            protocolVersion = version,
            deviceId = deviceId,
            friendlyName = name,
        )
    }
}
