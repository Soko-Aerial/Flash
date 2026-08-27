package com.transfer.flash.core.transfer.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * Control messages for WebSocket and LAN transfer tracks, exchanged as text frames.
 * File bytes travel as binary frames between [fileStart] and [fileEnd];
 * message order is guaranteed per connection.
 */
@OptIn(FlashInternalApi::class)
internal object WsTransferMessages {

    const val PROTOCOL_VERSION = 1

    private const val HELLO_PREFIX = "FLASH_WS_HELLO"
    private const val FILE_START_PREFIX = "FLASH_FILE_START"
    private const val FILE_END_PREFIX = "FLASH_FILE_END"
    private const val FILE_ACK_PREFIX = "FLASH_FILE_ACK"

    data class Hello(
        val protocolVersion: Int,
        val deviceId: String,
        val friendlyName: String,
    )

    data class FileStart(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
    )

    data class FileEnd(
        val transferId: String,
        val bytesSent: Long,
    )

    data class FileAck(
        val transferId: String,
        val bytesReceived: Long,
        val ok: Boolean,
    )

    fun hello(deviceId: String, friendlyName: String): String {
        return FlashTextFraming.encodeFields(
            HELLO_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "deviceId" to deviceId,
            "name" to friendlyName,
        )
    }

    fun fileStart(transferId: String, fileName: String, fileSize: Long): String {
        return FlashTextFraming.encodeFields(
            FILE_START_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "transferId" to transferId,
            "name" to fileName,
            "size" to fileSize.toString(),
        )
    }

    fun fileEnd(transferId: String, bytesSent: Long): String {
        return FlashTextFraming.encodeFields(
            FILE_END_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "transferId" to transferId,
            "bytes" to bytesSent.toString(),
        )
    }

    fun fileAck(transferId: String, bytesReceived: Long, ok: Boolean): String {
        return FlashTextFraming.encodeFields(
            FILE_ACK_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "transferId" to transferId,
            "received" to bytesReceived.toString(),
            "ok" to ok.toString(),
        )
    }

    fun parseHello(text: String): Hello? {
        val fields = FlashTextFraming.parseFields(text, HELLO_PREFIX) ?: return null
        val version = fields["version"]?.toIntOrNull() ?: return null
        val deviceId = fields["deviceId"].orEmpty()
        val name = fields["name"].orEmpty()
        if (deviceId.isBlank() || name.isBlank()) return null
        return Hello(protocolVersion = version, deviceId = deviceId, friendlyName = name)
    }

    fun parseFileStart(text: String): FileStart? {
        val fields = FlashTextFraming.parseFields(text, FILE_START_PREFIX) ?: return null
        val transferId = fields["transferId"].orEmpty()
        val name = fields["name"].orEmpty()
        val size = fields["size"]?.toLongOrNull() ?: return null
        if (transferId.isBlank() || name.isBlank()) return null
        return FileStart(transferId = transferId, fileName = name, fileSize = size)
    }

    fun parseFileEnd(text: String): FileEnd? {
        val fields = FlashTextFraming.parseFields(text, FILE_END_PREFIX) ?: return null
        val transferId = fields["transferId"].orEmpty()
        val bytes = fields["bytes"]?.toLongOrNull() ?: return null
        if (transferId.isBlank()) return null
        return FileEnd(transferId = transferId, bytesSent = bytes)
    }

    fun parseFileAck(text: String): FileAck? {
        val fields = FlashTextFraming.parseFields(text, FILE_ACK_PREFIX) ?: return null
        val transferId = fields["transferId"].orEmpty()
        val received = fields["received"]?.toLongOrNull() ?: return null
        val ok = fields["ok"]?.toBooleanStrictOrNull() ?: return null
        if (transferId.isBlank()) return null
        return FileAck(transferId = transferId, bytesReceived = received, ok = ok)
    }
}
