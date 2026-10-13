package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.serialization.json.Json

/**
 * The two [Json] configurations of the hub contract. Every DTO and frame is encoded and decoded through one of them
 * (SEC-15: no hand-rolled JSON in a security path).
 *
 * Shared choices, deliberate and documented:
 * - `encodeDefaults = true`: a default value (for example the wire version `v`) is always written, so a peer never
 *   has to guess what an absent field meant.
 * - `explicitNulls = false`: a null is omitted on the wire and an absent nullable field decodes to null.
 * - `isLenient = false`, `allowSpecialFloatingPointValues = false`: standard JSON only.
 * - `ignoreUnknownKeys = true`: FORWARD COMPATIBILITY. A newer peer may add a field; an older peer ignores it. This is
 *   safe for security because no field is ever read from a frame that the receiving side has not declared (the
 *   server never reads a client-supplied `from`: `RelayFrame.Send` has no such field).
 * - `coerceInputValues = true` (relay frames only): an unknown enum value falls back to the field's default, so a
 *   newer server's new ack status does not make an older client drop the whole frame.
 */
@FlashInternalApi
public object HubJson {

    /** Configuration for relay frames (`classDiscriminator = "t"`, because `type` is a real field of `FLASH_RELAY`). */
    public val frames: Json = Json {
        classDiscriminator = "t"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /** Configuration for REST bodies and for locally persisted credentials. */
    public val rest: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
        allowSpecialFloatingPointValues = false
    }
}

/** Thrown by [com.transfer.flash.core.hubprotocol.rest.HubRestCodec.decode] for a body that is too large or not valid. */
@FlashInternalApi
public class HubDecodeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
