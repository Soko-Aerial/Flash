package com.transfer.flash.desktop

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Windows' documented SPI_GETCLIENTAREAANIMATION query, using the existing JNA dependency.
 * Other desktops keep Flash's own motion setting; no unsupported AWT property is treated as
 * an OS preference. The application collects [preferences] off the UI thread, including while
 * its window is in the tray. A running Windows preference change is observed within one poll.
 */
public object DesktopOsReduceMotion {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    internal interface SystemParameters : StdCallLibrary {
        fun SystemParametersInfoW(action: Int, parameter: Int, value: IntByReference, flags: Int): Boolean
    }

    private val windowsApi: SystemParameters by lazy {
        Native.load("user32", SystemParameters::class.java, W32APIOptions.UNICODE_OPTIONS)
    }

    public val preferences: Flow<Boolean> = desktopReduceMotionPreferences(::queryOsPreference)

    /** Compatibility query; composition uses [preferences], not this synchronous native call. */
    public fun isReduceMotion(): Boolean = queryOsPreference() ?: false

    internal fun queryOsPreference(): Boolean? {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return null
        return try {
            val animationsEnabled = IntByReference()
            clientAreaAnimationsToReduceMotion(
                windowsApi.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, animationsEnabled, 0),
                animationsEnabled.value,
            )
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}

/** A failed query is not "animations disabled" and does not replace the last learned preference. */
internal fun clientAreaAnimationsToReduceMotion(querySucceeded: Boolean, enabled: Int): Boolean? =
    if (querySucceeded) enabled == 0 else null

internal fun desktopReduceMotionPreferences(
    query: () -> Boolean?,
    pollIntervalMs: Long = 5_000L,
): Flow<Boolean> = flow {
    require(pollIntervalMs > 0)
    var current = false
    while (true) {
        val learned = try {
            query()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (learned != null) current = learned
        emit(current)
        delay(pollIntervalMs)
    }
}.distinctUntilChanged().flowOn(Dispatchers.IO)
