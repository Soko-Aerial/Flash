package com.transfer.flash.core.transfer.chunked

import okio.BufferedSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer

/**
 * Incremental SHA-256 helpers for the chunked transfer pipelines (C5.3–C5.6).
 *
 * Decision D3 (docs/core-upgrade-plan.md §1) chose SHA-256 via `java.security` — zero deps and
 * ARMv8 crypto-extension accelerated on modern Android. Phase 13B-3 (migration D10 = Option A)
 * re-expressed that choice on okio's [HashingSink] so this file can live in `commonMain`. D3's
 * rationale is preserved rather than overridden: okio's JVM/Android [HashingSink] is a thin
 * wrapper around a `java.security.MessageDigest`, so on Android the digest still comes from the
 * same accelerated provider, byte for byte. Used for three distinct purposes:
 *
 * 1. **Whole-file digest** — computed once by the sender (single streaming pass, see
 *    `Chunker.hashOnly`) and carried in `FILE_START.fileSha256Hex`, mirroring how LocalSend
 *    supplies the file-level `sha256` in `/prepare-upload` metadata and the receiver answers
 *    HTTP `422` on mismatch: https://github.com/localsend/protocol (§4.1, §4.2).
 * 2. **Per-chunk digest** — independent SHA-256 over each chunk payload, carried raw (32 B)
 *    in every `CHUNK` frame and verified **before** the receive sink writes (C5.5
 *    verify-before-write). Independent per-piece hashes are the established resumable-transfer
 *    practice (BitTorrent piece hashes: https://bittorrent.org/bittorrentecon.pdf; BitTorrent v2
 *    fixed 16 KiB hash-tree leaves so corrupt data is detected at block granularity and only the
 *    damaged block is re-requested: https://blog.libtorrent.org/2020/09/bittorrent-v2/). A single
 *    running digest cannot localize corruption and would force full re-transfer on resume.
 * 3. **Constant-time comparisons** of digest material to avoid timing side channels on hash
 *    checks ([rawEqualsConstantTime], [hexEqualsConstantTime]). Both delegated to
 *    `java.security.MessageDigest.isEqual`; 13B-3 ports that method's algorithm to common Kotlin
 *    line for line rather than inventing a comparison — see [rawEqualsConstantTime].
 */
public object Sha256 {

    /** Length of a lowercase hexadecimal SHA-256 string ("abc" digest form). */
    public const val HEX_LENGTH: Int = 64

    /** Length of a raw (binary) SHA-256 digest. */
    public const val RAW_LENGTH: Int = 32

    /** One-shot digest over one or concatenated byte arrays. */
    public fun digest(vararg chunks: ByteArray): ByteArray {
        val accumulator = IncrementalSha256()
        for (chunk in chunks) accumulator.update(chunk)
        return accumulator.digestRaw()
    }

    /** One-shot digest over a sub-range of a byte array without intermediate copying. */
    public fun digest(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val accumulator = IncrementalSha256()
        accumulator.update(bytes, offset, length)
        return accumulator.digestRaw()
    }

    /** One-shot digest formatted as lowercase hex. */
    public fun digestHex(bytes: ByteArray): String = hex(digest(bytes))

    /** Lowercase hex formatting, consistent with the project's `*Hex` naming convention. */
    public fun hex(raw: ByteArray): String {
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Constant-time equality of two raw digests (length-safe).
     *
     * A line-for-line port of `java.security.MessageDigest.isEqual`, which this used to delegate
     * to: identity fast path, empty-[b] special case, `result |= lenA - lenB`, then a full pass
     * over [a] indexing [b] through `((i - lenB) ushr 31) * i` so an out-of-range read folds to
     * index 0 instead of branching. The loop always runs `a.size` times and never exits early,
     * which is the property being preserved. Ported rather than rewritten because the exact truth
     * table matters as much as the timing: a length mismatch is folded into the same accumulator
     * as a byte mismatch, so a caller cannot tell the two apart by either result or duration.
     */
    public fun rawEqualsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a === b) return true
        val lenA = a.size
        val lenB = b.size
        if (lenB == 0) return lenA == 0
        var result = 0
        result = result or (lenA - lenB)
        for (i in 0 until lenA) {
            val indexB = ((i - lenB) ushr 31) * i
            result = result or (a[i].toInt() xor b[indexB].toInt())
        }
        return result == 0
    }

    /**
     * Constant-time equality of two hex-formatted digests (length-safe).
     *
     * Was `MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), …)`. `Charsets` is JVM-only, so
     * [asciiBytes] reproduces that encoder and [rawEqualsConstantTime] the comparison.
     */
    public fun hexEqualsConstantTime(a: String, b: String): Boolean =
        rawEqualsConstantTime(asciiBytes(a), asciiBytes(b))

    /**
     * True iff [value] is exactly [HEX_LENGTH] lowercase-or-uppercase hex characters.
     * Used to validate wire-supplied `FILE_START.fileSha256Hex` before trust.
     */
    public fun isValidHex(value: String): Boolean {
        if (value.length != HEX_LENGTH) return false
        for (c in value) {
            val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!ok) return false
        }
        return true
    }

    /** Normalizes a valid hex digest string to the canonical lowercase wire form. */
    public fun normalizeHex(value: String): String {
        require(isValidHex(value)) { "not a SHA-256 hex digest: length=${value.length}" }
        return value.lowercase()
    }

    /**
     * US-ASCII encoding of [value]: one byte per UTF-16 code unit, unmappable units replaced by
     * `'?'` (0x3F), which is the JDK `US_ASCII` encoder's substitution byte.
     *
     * Byte-identical to `value.toByteArray(Charsets.US_ASCII)` for every BMP character, which
     * covers every input [hexEqualsConstantTime] can legitimately receive — both production call
     * sites pass [normalizeHex] or [hex] output, i.e. 64 hex characters. The single difference is
     * a surrogate PAIR: the JDK encoder sees one unmappable code point and emits one `'?'`, this
     * emits two. A hex digest contains no surrogates, so no caller can reach that case.
     */
    private fun asciiBytes(value: String): ByteArray =
        ByteArray(value.length) { i ->
            val code = value[i].code
            if (code < 0x80) code.toByte() else ASCII_SUBSTITUTE
        }

    private const val ASCII_SUBSTITUTE: Byte = 0x3F

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Streaming SHA-256 accumulator for single-pass hashing while chunks flow through a pipeline.
 * Not thread-safe; scope it to the owning pipeline loop.
 *
 * 13B-3: was a bare `java.security.MessageDigest`. It is now okio's [HashingSink] draining into
 * [blackholeSink], which on Android/JVM holds that same `MessageDigest`, so the digest bytes are
 * unchanged. Both fields are `var` because [HashingSink] has no `reset()`; [reset] rebuilds the
 * pair, discarding buffered-but-unhashed bytes exactly as `MessageDigest.reset()` did.
 */
public class IncrementalSha256 {

    private var hashing: HashingSink = HashingSink.sha256(blackholeSink())

    /**
     * Buffers [update] input into segments before it reaches [hashing]. This costs one segment-wise
     * copy per update that `MessageDigest.update(ByteArray)` did not: okio's digest reads from
     * `Buffer` segments, and no public okio entry point hashes a caller's array in place.
     */
    private var sink: BufferedSink = hashing.buffer()

    public fun update(bytes: ByteArray) {
        sink.write(bytes)
    }

    public fun update(bytes: ByteArray, offset: Int, length: Int) {
        sink.write(bytes, offset, length)
    }

    /**
     * Finishes the digest and returns the raw 32-byte result.
     *
     * The [BufferedSink.flush] is load-bearing — [sink] holds up to a segment of unhashed bytes,
     * and reading [HashingSink.hash] without it would digest only what has already drained.
     *
     * Keeps `MessageDigest.digest()`'s finish-and-reset semantics: the accumulator is empty
     * afterwards, so a second call returns the digest of no input rather than this one again. (The
     * KDoc this replaces said "does not reset", which was never true of `MessageDigest.digest()`.)
     */
    public fun digestRaw(): ByteArray {
        sink.flush()
        return hashing.hash.toByteArray()
    }

    /** Finishes the digest and returns the lowercase-hex result. See [digestRaw]. */
    public fun digestHex(): String = Sha256.hex(digestRaw())

    public fun reset() {
        hashing = HashingSink.sha256(blackholeSink())
        sink = hashing.buffer()
    }
}
