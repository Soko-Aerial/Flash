package com.transfer.flash.ui.shims

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.IOException
import java.net.URI

internal interface MediaFoundationBridge : Library {
    fun flash_video_open(path: WString, result: IntByReference): Pointer?
    fun flash_video_control(player: Pointer, playing: Int, muted: Int, seekSeconds: Double): Int
    fun flash_video_poll(player: Pointer, status: Pointer, pixels: Pointer, capacity: Int): Int
    fun flash_video_close(player: Pointer)
}

/** Every call, including close, runs on the same COM worker thread. */
internal class WindowsVideoPlayer private constructor(private val handle: Pointer) : AutoCloseable {
    private val status = Memory(32)
    private val pixels = Memory(MAX_FRAME_BYTES.toLong())
    private val frameBytes = ByteArray(MAX_FRAME_BYTES)

    fun control(playing: Boolean, muted: Boolean, seekToMs: Long?) {
        checkResult(
            bridge.flash_video_control(handle, if (playing) 1 else 0, if (muted) 1 else 0, seekToMs?.div(1000.0) ?: -1.0),
        )
    }

    fun poll(): PlaybackSample {
        val result = bridge.flash_video_poll(handle, status, pixels, MAX_FRAME_BYTES)
        checkResult(result)
        val flags = status.getInt(16)
        val frame = if (result == 0) {
            val width = status.getInt(20)
            val height = status.getInt(24)
            check(width in 1..MAX_FRAME_EDGE && height in 1..MAX_FRAME_EDGE)
            pixels.read(0, frameBytes, 0, width * height * 4)
            VideoFrame(width, height, frameBytes)
        } else {
            null
        }
        return PlaybackSample(status.getLong(0), status.getLong(8), flags and 1 != 0, flags and 2 != 0, frame)
    }

    override fun close() {
        try {
            bridge.flash_video_close(handle)
        } finally {
            status.close()
            pixels.close()
        }
    }

    internal data class PlaybackSample(
        val durationMs: Long,
        val positionMs: Long,
        val ended: Boolean,
        val buffering: Boolean,
        val frame: VideoFrame?,
    )

    /** Frame bytes are borrowed until the next poll. */
    internal data class VideoFrame(val width: Int, val height: Int, val bytes: ByteArray)

    companion object {
        private const val MAX_FRAME_EDGE = 1280
        private const val MAX_FRAME_BYTES = MAX_FRAME_EDGE * MAX_FRAME_EDGE * 4
        private const val LIBRARY = "/com/transfer/flash/ui/shims/win32/flash_media_foundation.dll"
        private val bridge by lazy {
            val loader = MediaFoundationBridge::class.java.classLoader
            Native.load(LIBRARY, MediaFoundationBridge::class.java, mapOf(Library.OPTION_CLASSLOADER to loader))
        }

        fun open(uri: String): WindowsVideoPlayer {
            check(Platform.isWindows())
            val file = if (uri.startsWith("file:", ignoreCase = true)) File(URI(uri)) else File(uri)
            require(file.isAbsolute && file.isFile)
            val result = IntByReference()
            val handle = bridge.flash_video_open(WString(file.absolutePath), result)
            checkResult(result.value)
            checkNotNull(handle)
            return try {
                WindowsVideoPlayer(handle)
            } catch (failure: Throwable) {
                bridge.flash_video_close(handle)
                throw failure
            }
        }

        private fun checkResult(result: Int) {
            if (result < 0) throw IOException("Media Foundation HRESULT 0x${Integer.toHexString(result)}")
        }
    }
}
