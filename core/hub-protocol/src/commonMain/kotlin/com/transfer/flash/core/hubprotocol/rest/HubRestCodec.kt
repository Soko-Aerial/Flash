package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubDecodeException
import com.transfer.flash.core.hubprotocol.HubJson
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/**
 * The one JSON codec for hub REST bodies and for locally persisted credentials (SEC-15). Every decode checks the body
 * size first ([HubContract.MAX_REST_BODY_BYTES]) and reports failure as [HubDecodeException], never as a raw parser
 * exception (SEC-16).
 */
@FlashInternalApi
public object HubRestCodec {

    public inline fun <reified T> encode(value: T): String = encode(serializer<T>(), value)

    public inline fun <reified T> decode(text: String, maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): T =
        decode(serializer<T>(), text, maxBytes)

    public inline fun <reified T> decodeOrNull(text: String, maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): T? =
        decodeOrNull(serializer<T>(), text, maxBytes)

    public inline fun <reified T> decodeList(text: String, maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): List<T> =
        decode(ListSerializer(serializer<T>()), text, maxBytes)

    public fun <T> encode(serializer: KSerializer<T>, value: T): String = HubJson.rest.encodeToString(serializer, value)

    public fun <T> decode(serializer: KSerializer<T>, text: String, maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): T {
        if (text.length > maxBytes || RelayFrameCodec.utf8Size(text) > maxBytes) {
            throw HubDecodeException("body larger than $maxBytes bytes")
        }
        return try {
            HubJson.rest.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw HubDecodeException("malformed body", e)
        } catch (e: IllegalArgumentException) {
            throw HubDecodeException("malformed body", e)
        }
    }

    public fun <T> decodeOrNull(serializer: KSerializer<T>, text: String, maxBytes: Int = HubContract.MAX_REST_BODY_BYTES): T? =
        try {
            decode(serializer, text, maxBytes)
        } catch (_: HubDecodeException) {
            null
        }
}
