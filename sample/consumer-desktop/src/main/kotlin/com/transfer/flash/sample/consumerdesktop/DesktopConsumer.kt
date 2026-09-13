package com.transfer.flash.sample.consumerdesktop

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashNetwork
import kotlinx.coroutines.flow.Flow

/**
 * Compile-only proof of the desktop tier of the published coordinates (Phase 24 Step 2 /
 * D9 = Option A). The desktop twin of `:sample:consumer`'s QuickStart and of
 * `:sample:consumer-granular`'s GranularConsumer, in three ways:
 *
 * 1. **Variant selection**: this module is `kotlin("jvm")` and depends on the ROOT coordinate
 *    `com.transfer.flash:core-engine:1.1.0` — the identical string an Android consumer writes.
 *    If Gradle module metadata were missing or wrong, resolution would fail or pick the
 *    `-android` variant and this file would not compile.
 * 2. **Umbrella scope**: `FlashEngine`'s api(...) exposure must make the public vocabulary —
 *    `FlashDeviceId`, `FlashResult`, `FlashNetwork`, `Flow` — compilable with no other core:*
 *    line, exactly as on Android.
 * 3. **Granular scope**: `core-network` alone must re-expose `:core:common`'s `FlashDevice`
 *    through its api edge (the Phase 2 Task 2.1 property, now proven on the JVM tier too).
 *
 * Never published; never run as an app. It exists so the compile gate
 * (`:sample:consumer-desktop:compileKotlin`) is the acceptance test.
 */
internal object DesktopConsumer {

    /** References the umbrella artifact's api-exposed vocabulary (engine tier). */
    fun resultLabel(result: FlashResult<*>): String = result.toString()

    /** References the engine-facing common vocabulary: ids, transports. */
    fun deviceLabel(id: FlashDeviceId): String = id.value.take(8)

    fun transportOf(device: FlashDevice): FlashTransportType = device.transportType

    /** `Flow` must arrive transitively (coroutines is api-scoped in core:common). */
    fun networkSessions(network: FlashNetwork): Flow<*> = network.activeSessions

    /** The granular property: FlashNetwork + FlashDevice both resolve from core-network alone. */
    fun sameNetwork(network: FlashNetwork): FlashNetwork = network
}
