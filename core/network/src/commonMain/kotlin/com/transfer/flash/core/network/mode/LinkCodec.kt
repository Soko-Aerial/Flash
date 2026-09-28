@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.mode

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

/** Link-control frames (PC5, ADR-048). See `docs/protocol.md` "Link control". */
public enum class LinkFrame(internal val wire: String) {
    /** "I dialed this session and no longer need it. May I close it?" */
    Park("park"),

    /** "I do not need it either, and I will not redial you when it closes." */
    ParkOk("park-ok"),

    /** "I still want this session." */
    Keep("keep"),
}

/**
 * `FLASH_LINK v=1 t=<park|park-ok|keep>`. Old clients drop the unknown prefix and never answer, so
 * a park request to them is never granted and the session stays, as before PC5.
 */
public object LinkCodec {
    public const val PREFIX: String = "FLASH_LINK"
    public const val VERSION: Int = 1

    public fun isLinkFrame(text: String): Boolean =
        text.startsWith(PREFIX) && (text.length == PREFIX.length || text[PREFIX.length] == ' ')

    public fun encode(frame: LinkFrame): String =
        FlashTextFraming.encodeFields(PREFIX, "v" to VERSION.toString(), "t" to frame.wire)

    /** Null for a malformed frame, another version or an unknown type. */
    public fun decode(text: String): LinkFrame? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        if (fields["v"]?.toIntOrNull() != VERSION) return null
        return LinkFrame.entries.firstOrNull { it.wire == fields["t"] }
    }
}
