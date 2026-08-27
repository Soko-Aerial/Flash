package com.transfer.flash.sample.consumergranular

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.network.FlashNetwork

/**
 * Compile-only proof of publishing shape B (granular): depending on ONLY
 * `:core:network` still exposes `:core:common`'s `FlashDevice`, because Task 2.1
 * promoted `core:common` to `api(project(":core:common"))` inside `core:network`.
 * `FlashDevice` is referenced directly below, so it MUST be resolvable from the
 * network artifact alone. Before the flip this file would not compile.
 *
 * This module is a test harness and is never published.
 */
internal object GranularConsumer {

    /** References the network artifact's own public interface. */
    fun sameNetwork(network: FlashNetwork): FlashNetwork = network

    /** References a `:core:common` type that must arrive transitively via network's api. */
    fun deviceLabel(device: FlashDevice): String = device.toString()
}
