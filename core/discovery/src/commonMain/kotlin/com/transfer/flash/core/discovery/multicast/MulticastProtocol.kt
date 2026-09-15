// FlashTextFraming/FlashProtocol/FlashLog are @FlashInternalApi — library-internal, opted into here
// exactly as NsdTransport and JmdsTransport do: this is a radio transport, not published API.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.TxtCodec

/**
 * Wire format of the UDP-multicast announcement (see [MulticastTransport]).
 *
 * One datagram carries the WHOLE peer: identity keys (the same `TxtCodec` vocabulary every other
 * radio uses), the peer's Flash protocol version, and the port its WebSocket server listens on. That
 * is the entire point of this transport — there is no second resolution step whose failure can
 * strand a peer:
 *
 * - **The address is the datagram's source IP.** Nothing to resolve, so nothing to go stale.
 * - **The identity is in the payload**, not in a record some resolver may not have cached yet. The
 *   failure this avoids is concrete: on 2026-09-13/14 the desktop resolved the phone's DNS-SD
 *   record as a hollow `ServiceInfo` (`txtKeys=[] txtBytes=1`, JmDNS 3.5.12's `EMPTY_TXT`), had no
 *   `device_id` to build an endpoint from, and therefore never dialed it — while the phone could see
 *   the desktop perfectly. There is no equivalent state here: a datagram either arrives complete or
 *   not at all.
 *
 * Format is one [FlashTextFraming] line, so escaping (`%20`/`%3D`/`%25`) is the vocabulary the rest
 * of the project already uses for peer-controlled text:
 *
 * ```text
 * FLASH_MCAST v=1 device_id=<uuid> name=<escaped> model=<escaped> proto=2 port=45822 [caps=kiosk] [fp8=ab12cd34]
 * ```
 *
 * Forward compatibility: unknown keys are ignored, and a datagram whose [KEY_FORMAT] is not
 * [FORMAT_VERSION] is rejected rather than misread — the format version is what lets this evolve
 * without breaking a peer that has not been updated.
 */
internal object MulticastProtocol {

    /** Line prefix; distinct from `FLASH_PAIR`/`FLASH_XFER`/`FLASH_WS_HELLO` by construction. */
    const val PREFIX: String = "FLASH_MCAST"

    /** Announcement-format version. Bump only for a breaking change to the field set. */
    const val FORMAT_VERSION: Int = 1

    internal const val KEY_FORMAT: String = "v"
    internal const val KEY_PORT: String = "port"

    /**
     * Largest datagram we will emit, and the buffer size we receive into.
     *
     * 1400 keeps one announcement inside a single Ethernet frame on a 1500-byte MTU (1500 − 20 IP −
     * 8 UDP = 1472), so it is never IP-fragmented — a fragmented announcement is exactly the kind
     * that disappears when one fragment is lost. UDP gives no retransmission, so the announcement
     * cadence and its start-up burst are the reliability mechanism, not a larger payload.
     */
    const val MAX_DATAGRAM_BYTES: Int = 1400

    /**
     * Longest friendly name / model carried on the wire. A display name is attacker-controlled
     * input and needs a bound for the same reason [TxtCodec.truncateFlags] exists: it is the only
     * unbounded field a peer can inflate.
     */
    internal const val MAX_TEXT_FIELD_CHARS: Int = 64

    /** A decoded announcement: who the peer is, and where its WebSocket server listens. */
    data class Announcement(
        val identity: FlashAdvertisedIdentity,
        val port: Int,
    )

    /** Encodes this device's announcement. Never throws; degrades rather than exceeding the MTU. */
    fun encode(identity: FlashAdvertisedIdentity, port: Int): String {
        val bounded = identity.copy(
            friendlyName = identity.friendlyName.take(MAX_TEXT_FIELD_CHARS),
            deviceModel = identity.deviceModel.take(MAX_TEXT_FIELD_CHARS),
        )
        val base = TxtCodec.encode(bounded).toList()
        val tail = listOf(
            KEY_FORMAT to FORMAT_VERSION.toString(),
            KEY_PORT to port.toString(),
        )
        val full = FlashTextFraming.encodeFields(PREFIX, base + tail)
        if (full.encodeToByteArray().size <= MAX_DATAGRAM_BYTES) return full
        // Too big only because of the optional human-readable fields: drop model, then the friendly
        // name, keeping every field discovery actually needs (device_id, proto, port).
        val withoutModel = base.filterNot { it.first == TxtCodec.KEY_MODEL }
        val trimmed = FlashTextFraming.encodeFields(PREFIX, withoutModel + tail)
        if (trimmed.encodeToByteArray().size <= MAX_DATAGRAM_BYTES) return trimmed
        return FlashTextFraming.encodeFields(
            PREFIX,
            base.filter { it.first == TxtCodec.KEY_DEVICE_ID || it.first == TxtCodec.KEY_PROTO } + tail,
        )
    }

    /**
     * Decodes an announcement, or returns null when it is not one: wrong prefix, a format version
     * this build does not understand, a missing/invalid port, or an incomplete identity.
     *
     * Hostile input never throws — a datagram arrives from anyone on the LAN.
     */
    fun decode(text: String): Announcement? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        if (fields[KEY_FORMAT]?.trim()?.toIntOrNull() != FORMAT_VERSION) return null
        val port = fields[KEY_PORT]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        // The identity vocabulary is TxtCodec's, shared with every other radio; the announcement's
        // own keys are extra entries it ignores.
        val identity = TxtCodec.decode(fields) ?: return null
        return Announcement(identity = identity, port = port)
    }
}
