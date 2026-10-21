package com.transfer.flash.core.network.ws

/**
 * One table of WebSocket frames and what a reader must make of them, shared by both host codecs (brutal-review F10).
 *
 * `WebSocketCodec` exists twice (`androidMain` and `jvmMain`, D1 = Option B forbids a shared JVM tier) and the two copies
 * differ on purpose in one place: Android reads small frames through `ByteArrayPool`, the JVM copy allocates per frame.
 * Their behaviour on the wire must still be identical. The frames here are built by hand in this file, not by either
 * codec's own writer, so an encoder bug cannot hide a decoder bug. Each target runs the same table in its own suite
 * (`androidHostTest` and `jvmTest`); if one copy drifts, exactly that suite fails.
 */
internal object WebSocketFrameFixtures {

    const val OPCODE_TEXT: Int = 0x1
    const val OPCODE_BINARY: Int = 0x2

    /** What reading the frame must produce. */
    internal sealed interface Expect {
        class Binary(val payload: ByteArray) : Expect
        class Text(val text: String) : Expect
        /** The reader must throw an `IOException` (truncated or invalid frame). */
        object Failure : Expect
    }

    internal class Case(val name: String, val wire: ByteArray, val expect: Expect)

    fun payload(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { ((it * 31) + seed).toByte() }

    private val maskKey = byteArrayOf(0x12, 0x34, 0x56, 0x78)

    /** A single final frame, assembled by hand from RFC 6455 section 5.2. */
    fun frame(opcode: Int, payload: ByteArray, masked: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        out += (0x80 or opcode).toByte()
        val maskBit = if (masked) 0x80 else 0
        val length = payload.size
        when {
            length < 126 -> out += (maskBit or length).toByte()
            length <= 0xFFFF -> {
                out += (maskBit or 126).toByte()
                out += (length shr 8).toByte()
                out += length.toByte()
            }
            else -> {
                out += (maskBit or 127).toByte()
                for (shift in 56 downTo 0 step 8) out += ((length.toLong() shr shift) and 0xFF).toByte()
            }
        }
        if (masked) {
            maskKey.forEach { out += it }
            payload.forEachIndexed { index, byte -> out += (byte.toInt() xor maskKey[index and 3].toInt()).toByte() }
        } else {
            payload.forEach { out += it }
        }
        return out.toByteArray()
    }

    private fun binary(size: Int, masked: Boolean, label: String): Case =
        payload(size).let { Case("$label binary $size bytes", frame(OPCODE_BINARY, it, masked), Expect.Binary(it)) }

    val cases: List<Case> = buildList {
        // 7-bit, 16-bit and 64-bit length forms, and the boundaries between them.
        for (size in listOf(0, 1, 125, 126, 127, 65_535, 65_536, 70_000)) add(binary(size, masked = false, label = "unmasked"))
        // Exactly the sizes `ByteArrayPool` hands out, and one byte either side (F9: these must not alias a pool buffer).
        for (size in listOf(16_383, 16_384, 16_385, 32_767, 32_768, 32_769)) add(binary(size, masked = false, label = "unmasked"))
        for (size in listOf(125, 126, 16_384, 32_768, 70_000)) add(binary(size, masked = true, label = "masked"))
        add(Case("masked text", frame(OPCODE_TEXT, "héllo wörld".encodeToByteArray(), masked = true), Expect.Text("héllo wörld")))
        add(Case("unmasked text", frame(OPCODE_TEXT, "flash".encodeToByteArray(), masked = false), Expect.Text("flash")))
        // A frame cut off after its header, and one cut off inside the length field.
        add(Case("truncated payload", frame(OPCODE_BINARY, payload(16_384), masked = false).copyOf(100), Expect.Failure))
        add(Case("truncated length field", byteArrayOf((0x80 or OPCODE_BINARY).toByte(), 126, 0x40), Expect.Failure))
        add(Case("empty stream", ByteArray(0), Expect.Failure))
    }
}
