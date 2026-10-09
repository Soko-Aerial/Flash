package com.transfer.flash.core.network.radio

import com.transfer.flash.core.network.kiss.Ax25Address

/** Tunables of a [RadioSession]. Defaults follow radio plan section 6.0 (Profile M). */
public class RadioSessionConfig(
    /** Maximum length of the AX.25 information field Flash may use. */
    public val infoBudget: Int = RadioWire.DEFAULT_INFO_BUDGET,
    /** TTL put on frames this station originates. */
    public val defaultTtl: Int = 3,
    /** Largest TTL a receiver honours; a higher value on the wire is clamped (TTL is not authenticated). */
    public val maxTtl: Int = 3,
    /** Length of the sender-tag epoch. Tags and station labels change every epoch. */
    public val epochMs: Long = 15 * 60 * 1000L,
    /** Epochs either side of "now" a receiver accepts (clock skew tolerance = this many epochs). */
    public val epochSkew: Int = 1,
    /** Largest segmented message in segments. */
    public val maxSegments: Int = 16,
    /** Reassembly timeout. */
    public val reassemblyTimeoutMs: Long = 10 * 60 * 1000L,
) {
    init {
        require(infoBudget in 64..255) { "infoBudget must be 64..255" }
        require(epochMs >= 1000 && epochSkew >= 0 && maxSegments in 2..255)
    }
}

/** Why a received frame was not delivered. These are the `reason=` values of the radio probes. */
public enum class RadioDrop {
    /** Not a Flash frame (first byte differs). Silent: the channel carries other traffic. */
    NOT_FLASH,
    MALFORMED,
    UNKNOWN_SENDER,
    AUTH_FAILED,
    REPLAY,
    TOO_OLD,
    DUPLICATE_MESSAGE,
    BAD_SEGMENT,
    SIGNED_NOT_ENABLED,
}

/** Result of [RadioSession.ingest]. */
public sealed interface RadioIngest {
    /** A whole authenticated message. [firstCounter] is what an ACK names. */
    public class Delivered(
        public val peerId: String,
        public val kind: RadioKind,
        public val body: ByteArray,
        public val firstCounter: Long,
        public val segments: Int,
        public val ttl: Int,
        public val signed: Boolean,
    ) : RadioIngest

    /** An authenticated segment was stored; more are expected. */
    public class Partial(public val peerId: String, public val received: Int, public val total: Int) : RadioIngest

    /** The frame was not accepted. */
    public class Dropped(public val reason: RadioDrop, public val detail: String? = null) : RadioIngest
}

/** Result of [RadioSession.encode]. */
public sealed interface RadioEncode {
    /** Frames to put in AX.25 information fields, in send order. [firstCounter] names the message in an ACK. */
    public class Frames(public val frames: List<ByteArray>, public val firstCounter: Long) : RadioEncode

    /** The request cannot be sent. */
    public class Refused(public val reason: String) : RadioEncode
}

/**
 * Sans-IO sender and receiver of Flash radio frames for one local station and any number of paired peers.
 *
 * Pairwise frames are AES-256-GCM sealed with a per-direction key derived (HKDF-SHA-256) from the existing pairing session
 * key, a counter nonce, an anti-replay window and a rotating sender tag. The class owns no clock and no I/O: every call takes
 * `nowMs`. Persistent state goes through [RadioReplayStore] and [RadioCounterStore].
 *
 * Thread-safety: not synchronised; use from one coroutine or guard externally.
 *
 * Status: unit-tested with the JDK crypto implementation; never exercised over a real radio.
 */
public class RadioSession(
    private val localId: String,
    private val crypto: RadioCrypto,
    private val replayStore: RadioReplayStore,
    counterStore: RadioCounterStore,
    private val config: RadioSessionConfig = RadioSessionConfig(),
    private val signer: RadioSignatureScheme? = null,
    private val localPublicKey: ByteArray? = null,
) {
    private class DirectionKeys(val key: ByteArray, val nonceSalt: ByteArray, val tagKey: ByteArray)

    private class PeerContext(val peerId: String, val send: DirectionKeys, val recv: DirectionKeys) {
        val tagCache = HashMap<Long, ByteArray>()
    }

    private val counters = RadioSendCounter(counterStore)
    private val peers = LinkedHashMap<String, PeerContext>()
    private val windows = HashMap<String, ReplayWindow>()
    private val reassembler = SegmentReassembler(maxSegments = config.maxSegments, timeoutMs = config.reassemblyTimeoutMs)
    private val broadcastSenders = LinkedHashMap<String, ByteArray>()

    /** Registers (or replaces) a paired peer and its 32-byte pairing session key. */
    public fun addPeer(peerId: String, pairKey: ByteArray) {
        require(pairKey.size == 32) { "pair key must be 32 bytes" }
        peers[peerId] = PeerContext(peerId, derive(pairKey, localId, peerId), derive(pairKey, peerId, localId))
    }

    /** Forgets [peerId] (revocation). Its replay state is kept so a re-add cannot reopen the window. */
    public fun removePeer(peerId: String) {
        peers.remove(peerId)
    }

    /** True if [peerId] is registered. */
    public fun hasPeer(peerId: String): Boolean = peers.containsKey(peerId)

    /** Registers a peer whose signed broadcast frames are accepted (public key in X.509 SubjectPublicKeyInfo form). */
    public fun addBroadcastSender(peerId: String, publicKey: ByteArray) {
        broadcastSenders[peerId] = publicKey.copyOf()
    }

    /**
     * The neutral, rotating AX.25 source label to use when talking to [peerId] at [nowMs]: six characters from a restricted
     * alphabet plus an SSID, derived from the shared key and the current epoch. Not a callsign, not linkable across epochs
     * by an observer, and recomputable by the peer.
     */
    public fun stationLabel(peerId: String, nowMs: Long): Ax25Address {
        val ctx = peers[peerId] ?: error("unknown peer $peerId")
        val mac = crypto.hmacSha256(ctx.send.tagKey, LABEL_PREFIX + epochBytes(nowMs / config.epochMs))
        val chars = CharArray(6) { LABEL_ALPHABET[(mac[it].toInt() and 0xFF) % LABEL_ALPHABET.length] }
        return Ax25Address(chars.concatToString(), mac[6].toInt() and 0x0F)
    }

    /** Builds the frames for one message to [peerId]. Segments automatically above [RadioWire.maxBodyUnsegmented]. */
    public fun encode(peerId: String, kind: RadioKind, body: ByteArray, nowMs: Long, ttl: Int = config.defaultTtl): RadioEncode {
        val ctx = peers[peerId] ?: return RadioEncode.Refused("unknown_peer")
        if (ttl !in 0..255) return RadioEncode.Refused("bad_ttl") // R7: a typed refusal, not an exception from the header
        val single = RadioWire.maxBodyUnsegmented(config.infoBudget)
        if (body.size <= single) {
            val counter = nextCounter("tx/$peerId", nowMs) ?: return RadioEncode.Refused("counter_exhausted")
            return RadioEncode.Frames(listOf(sealFrame(ctx, kind, 0, ttl, counter, body, nowMs)), counter)
        }
        val per = RadioWire.maxBodyPerSegment(config.infoBudget)
        val total = (body.size + per - 1) / per
        if (total > config.maxSegments) return RadioEncode.Refused("too_large_max_${config.maxSegments * per}_bytes")
        val first = nextCounter("tx/$peerId", nowMs) ?: return RadioEncode.Refused("counter_exhausted")
        val msgId = (first and 0xFFFF).toInt()
        val frames = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val counter = if (i == 0) first else nextCounter("tx/$peerId", nowMs) ?: return RadioEncode.Refused("counter_exhausted")
            val from = i * per
            val chunk = body.copyOfRange(from, minOf(body.size, from + per))
            val plain = ByteArray(RadioWire.SEGMENT_HEADER_SIZE + chunk.size)
            plain[0] = (msgId ushr 8).toByte()
            plain[1] = msgId.toByte()
            plain[2] = i.toByte()
            plain[3] = total.toByte()
            chunk.copyInto(plain, RadioWire.SEGMENT_HEADER_SIZE)
            frames += sealFrame(ctx, kind, RadioWire.FLAG_SEGMENTED, ttl, counter, plain, nowMs)
        }
        return RadioEncode.Frames(frames, first)
    }

    /** The ACK for the message whose first frame carried [ackedCounter]. */
    public fun encodeAck(peerId: String, ackedCounter: Long, nowMs: Long): RadioEncode {
        val b = ByteArray(4)
        b[0] = (ackedCounter ushr 24).toByte()
        b[1] = (ackedCounter ushr 16).toByte()
        b[2] = (ackedCounter ushr 8).toByte()
        b[3] = ackedCounter.toByte()
        return encode(peerId, RadioKind.ACK, b, nowMs)
    }

    /**
     * Builds a signed clear-text broadcast frame (Profile A only; needs a [RadioSignatureScheme] and the local public key).
     * Never segmented: the body must fit [RadioWire.HEADER_SIZE] + [RadioWire.SIGNATURE_SIZE] below the budget.
     */
    public fun encodeSigned(kind: RadioKind, body: ByteArray, nowMs: Long, ttl: Int = config.defaultTtl): RadioEncode {
        val s = signer ?: return RadioEncode.Refused("no_signer")
        if (ttl !in 0..255) return RadioEncode.Refused("bad_ttl")
        val pub = localPublicKey ?: return RadioEncode.Refused("no_public_key")
        if (body.size > config.infoBudget - RadioWire.HEADER_SIZE - RadioWire.SIGNATURE_SIZE) return RadioEncode.Refused("too_large_for_signed")
        val counter = nextCounter("tx/" + BROADCAST_KEY, nowMs) ?: return RadioEncode.Refused("counter_exhausted")
        val header = RadioHeader(kind, RadioWire.AUTH_SIGNED, ttl, crypto.sha256(pub).copyOf(4), counter)
        val sig = s.sign(header.authenticatedBytes() + body)
        if (sig.size != RadioWire.SIGNATURE_SIZE) return RadioEncode.Refused("bad_signature_size")
        return RadioEncode.Frames(listOf(header.encode() + body + sig), counter)
    }

    /** Processes the information field of one received AX.25 UI frame. Never throws on hostile input. */
    public fun ingest(info: ByteArray, nowMs: Long): RadioIngest {
        val parsed = RadioHeader.parse(info)
        if (parsed is RadioHeader.Parsed.Bad) {
            return when (parsed.problem) {
                RadioHeader.Problem.BAD_MAGIC -> RadioIngest.Dropped(RadioDrop.NOT_FLASH)
                else -> RadioIngest.Dropped(RadioDrop.MALFORMED, parsed.problem.name)
            }
        }
        parsed as RadioHeader.Parsed.Ok
        val header = parsed.header
        val ttl = minOf(header.ttl, config.maxTtl)
        return if (header.signed) ingestSigned(header, info, ttl, nowMs) else ingestAead(header, info, ttl, nowMs)
    }

    private fun ingestAead(header: RadioHeader, info: ByteArray, ttl: Int, nowMs: Long): RadioIngest {
        if (info.size < RadioWire.HEADER_SIZE + RadioWire.TAG_SIZE) return RadioIngest.Dropped(RadioDrop.MALFORMED, "short_body")
        val sealed = info.copyOfRange(RadioWire.HEADER_SIZE, info.size)
        val aad = header.authenticatedBytes()
        val epoch = nowMs / config.epochMs
        var tagMatched = false
        for (ctx in peers.values) {
            var matches = false
            for (e in (epoch - config.epochSkew)..(epoch + config.epochSkew)) {
                if (tagFor(ctx, e).contentEquals(header.tag)) {
                    matches = true
                    break
                }
            }
            if (!matches) continue
            tagMatched = true
            val window = windowFor(ctx.peerId)
            val plain = crypto.aeadOpen(ctx.recv.key, nonce(ctx.recv.nonceSalt, header.counter), aad, sealed) ?: continue
            // Authenticated from here on.
            when (window.check(header.counter)) {
                ReplayVerdict.DUPLICATE -> return RadioIngest.Dropped(RadioDrop.REPLAY, "duplicate")
                ReplayVerdict.TOO_OLD -> return RadioIngest.Dropped(RadioDrop.TOO_OLD)
                ReplayVerdict.FRESH -> Unit
            }
            window.commit(header.counter)
            replayStore.store(replayKey(ctx.peerId), window.highest, window.bitmap)
            return if (header.segmented) {
                deliverSegment(ctx.peerId, header, plain, ttl, nowMs)
            } else {
                RadioIngest.Delivered(ctx.peerId, header.kind, plain, header.counter, 1, ttl, signed = false)
            }
        }
        if (!tagMatched) return RadioIngest.Dropped(RadioDrop.UNKNOWN_SENDER)
        return RadioIngest.Dropped(RadioDrop.AUTH_FAILED)
    }

    private fun deliverSegment(peerId: String, header: RadioHeader, plain: ByteArray, ttl: Int, nowMs: Long): RadioIngest {
        if (plain.size < RadioWire.SEGMENT_HEADER_SIZE) return RadioIngest.Dropped(RadioDrop.BAD_SEGMENT, "short")
        val msgId = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        val idx = plain[2].toInt() and 0xFF
        val total = plain[3].toInt() and 0xFF
        val chunk = plain.copyOfRange(RadioWire.SEGMENT_HEADER_SIZE, plain.size)
        return when (val r = reassembler.add(peerId, msgId, idx, total, header.counter, chunk, nowMs)) {
            is SegmentResult.Incomplete -> RadioIngest.Partial(peerId, r.received, r.total)
            is SegmentResult.Complete -> RadioIngest.Delivered(peerId, header.kind, r.body, r.firstCounter, r.segments, ttl, signed = false)
            is SegmentResult.Rejected -> RadioIngest.Dropped(RadioDrop.BAD_SEGMENT, r.reason)
        }
    }

    private fun ingestSigned(header: RadioHeader, info: ByteArray, ttl: Int, nowMs: Long): RadioIngest {
        if (broadcastSenders.isEmpty()) return RadioIngest.Dropped(RadioDrop.SIGNED_NOT_ENABLED)
        if (header.segmented) return RadioIngest.Dropped(RadioDrop.MALFORMED, "signed_segmented")
        val verifier = signer ?: return RadioIngest.Dropped(RadioDrop.SIGNED_NOT_ENABLED)
        if (info.size < RadioWire.HEADER_SIZE + RadioWire.SIGNATURE_SIZE) return RadioIngest.Dropped(RadioDrop.MALFORMED, "short_body")
        val body = info.copyOfRange(RadioWire.HEADER_SIZE, info.size - RadioWire.SIGNATURE_SIZE)
        val sig = info.copyOfRange(info.size - RadioWire.SIGNATURE_SIZE, info.size)
        val signedBytes = header.authenticatedBytes() + body
        var known = false
        for ((peerId, pub) in broadcastSenders) {
            if (!crypto.sha256(pub).copyOf(4).contentEquals(header.tag)) continue
            known = true
            if (!verifier.verify(pub, signedBytes, sig)) continue
            val window = windowFor("bc/$peerId")
            when (window.check(header.counter)) {
                ReplayVerdict.DUPLICATE -> return RadioIngest.Dropped(RadioDrop.REPLAY, "duplicate")
                ReplayVerdict.TOO_OLD -> return RadioIngest.Dropped(RadioDrop.TOO_OLD)
                ReplayVerdict.FRESH -> Unit
            }
            window.commit(header.counter)
            replayStore.store(replayKey("bc/$peerId"), window.highest, window.bitmap)
            return RadioIngest.Delivered(peerId, header.kind, body, header.counter, 1, ttl, signed = true)
        }
        return if (known) RadioIngest.Dropped(RadioDrop.AUTH_FAILED) else RadioIngest.Dropped(RadioDrop.UNKNOWN_SENDER)
    }

    private fun sealFrame(ctx: PeerContext, kind: RadioKind, flags: Int, ttl: Int, counter: Long, plain: ByteArray, nowMs: Long): ByteArray {
        val tag = tagFor(ctx.send, nowMs / config.epochMs)
        val header = RadioHeader(kind, flags, ttl, tag, counter)
        val sealed = crypto.aeadSeal(ctx.send.key, nonce(ctx.send.nonceSalt, counter), header.authenticatedBytes(), plain)
        return header.encode() + sealed
    }

    private fun nextCounter(key: String, nowMs: Long): Long? =
        try {
            counters.next(key, nowMs)
        } catch (_: IllegalStateException) {
            null
        }

    private fun windowFor(key: String): ReplayWindow =
        windows.getOrPut(key) {
            val saved = replayStore.load(replayKey(key))
            if (saved == null) ReplayWindow() else ReplayWindow(saved.first, saved.second)
        }

    private fun replayKey(key: String): String = "rx/$localId/$key"

    private fun tagFor(ctx: PeerContext, epoch: Long): ByteArray = ctx.tagCache.getOrPut(epoch) {
        if (ctx.tagCache.size > 8) ctx.tagCache.clear()
        crypto.hmacSha256(ctx.recv.tagKey, TAG_PREFIX + epochBytes(epoch)).copyOf(4)
    }

    private fun tagFor(keys: DirectionKeys, epoch: Long): ByteArray =
        crypto.hmacSha256(keys.tagKey, TAG_PREFIX + epochBytes(epoch)).copyOf(4)

    private fun derive(pairKey: ByteArray, sender: String, receiver: String): DirectionKeys {
        val s = sender.encodeToByteArray()
        val r = receiver.encodeToByteArray()
        require(s.size in 1..255 && r.size in 1..255) { "device ids must be 1..255 bytes" }
        val info = "flash-radio-v1/dir".encodeToByteArray() + byteArrayOf(s.size.toByte()) + s + byteArrayOf(r.size.toByte()) + r
        val okm = RadioHkdf.derive(crypto, pairKey, "flash-radio-v1".encodeToByteArray(), info, 32 + 4 + 32)
        return DirectionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 36), okm.copyOfRange(36, 68))
    }

    private fun nonce(salt: ByteArray, counter: Long): ByteArray {
        val n = ByteArray(12)
        salt.copyInto(n, 0)
        n[8] = (counter ushr 24).toByte()
        n[9] = (counter ushr 16).toByte()
        n[10] = (counter ushr 8).toByte()
        n[11] = counter.toByte()
        return n
    }

    private fun epochBytes(epoch: Long): ByteArray = ByteArray(8) { (epoch ushr (56 - 8 * it)).toByte() }

    private companion object {
        const val BROADCAST_KEY: String = "bc"
        const val LABEL_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val TAG_PREFIX: ByteArray = "tag".encodeToByteArray()
        val LABEL_PREFIX: ByteArray = "lbl".encodeToByteArray()
    }
}
