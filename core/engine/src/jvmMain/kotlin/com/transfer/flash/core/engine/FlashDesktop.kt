@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * What a desktop engine exposes so a caller that holds only a [FlashEngine] can wait for the boot.
 *
 * [FlashDesktop.create] returns as soon as the engine is *constructed and started*, not when it has finished
 * assembling its transport: the accessors ([FlashEngine.transfers], [FlashEngine.network], ...) work from the first
 * call (they forward to the real implementation as soon as it exists), but nothing is connected until [ready] is true.
 * Use [awaitReady] when a flow has to be live, e.g. before reading the bound port.
 */
public interface FlashReadiness {
    /** True once the stack is assembled and bound; false before the boot finishes and again after close. */
    public val ready: StateFlow<Boolean>

    /** The reason the boot failed, or the engine was closed before it finished; null while booting or healthy. */
    public val startError: StateFlow<Throwable?>
}

/**
 * Suspends until this engine is ready.
 *
 * Returns immediately for an engine that does not report readiness ([FlashReadiness]).
 *
 * @throws IllegalStateException when the boot failed or the engine was closed first (the cause is [FlashReadiness.startError]).
 */
public suspend fun FlashEngine.awaitReady() {
    val readiness = this as? FlashReadiness ?: return
    val outcome = combine(readiness.ready, readiness.startError) { ready, error -> ready to error }
        .first { (ready, error) -> ready || error != null }
    val error = outcome.second
    if (!outcome.first && error != null) {
        throw IllegalStateException("Flash engine did not start: ${error.message}", error)
    }
}

/**
 * JVM Desktop platform factory for creating [FlashEngine] instances (ADR-010 / Phase 21).
 * Parallel to Android's [Flash.create].
 *
 * **One engine per state directory.** The registered factory refuses a second concurrent engine on the same state
 * directory (it would share the identity, the encrypted chat database and the WebSocket port); close the first one
 * (`FlashEngine.close()`) to release the directory. This guard lives in the factory the desktop app registers, so a
 * `DesktopEngine` built directly with its constructor is not covered by it.
 */
public object FlashDesktop {

    @Volatile
    private var defaultFactory: ((FlashConfig) -> FlashEngine)? = null

    /** The class that registers the platform factory in its static initialiser. */
    private const val DESKTOP_ENGINE_CLASS: String = "com.transfer.flash.desktop.DesktopEngine"

    /**
     * Registers the platform engine factory (typically wired by `:desktop`'s DesktopEngine).
     */
    public fun registerFactory(factory: (FlashConfig) -> FlashEngine) {
        defaultFactory = factory
    }

    /**
     * Forgets the registered factory. Test hook so tests do not leak a factory (or the absence of one) into each
     * other; production code never calls this. The registering side must call [registerFactory] again, because the
     * class-load trigger below runs only once per class loader.
     */
    @com.transfer.flash.core.common.annotation.FlashInternalApi
    public fun resetForTesting() {
        defaultFactory = null
    }

    /**
     * Creates and starts a [FlashEngine] for the JVM desktop platform.
     *
     * The engine is returned as soon as it is started; see [FlashReadiness] / [awaitReady] to wait for the boot.
     *
     * @throws IllegalStateException when no desktop engine factory has been registered, when the engine class is on
     * the classpath but failed to initialise (the original error is the cause), or when the factory refuses
     * (e.g. another engine already owns the state directory).
     */
    public fun create(config: FlashConfig = FlashConfig()): FlashEngine {
        if (defaultFactory == null) {
            ensureFactoryRegistered(DESKTOP_ENGINE_CLASS)
        }
        val factory = defaultFactory ?: throw IllegalStateException(
            "No FlashDesktop factory registered. In the desktop application, instantiate DesktopEngine or call FlashDesktop.registerFactory."
        )
        return factory(config)
    }

    /**
     * Loads [className] with static initialisation so its initialiser can call [registerFactory].
     *
     * - The class missing from the classpath is fine (a host that registers its own factory does not ship it).
     * - A class that is present but fails to initialise ([ExceptionInInitializerError], [NoClassDefFoundError], any
     *   other [LinkageError] or exception) is **not** swallowed: it is rethrown as an [IllegalStateException] that
     *   keeps the original as its cause. It used to be dropped, which turned a real startup bug into "no factory
     *   registered".
     * - JVM-fatal errors ([VirtualMachineError], so OutOfMemoryError and StackOverflowError) are rethrown unchanged.
     */
    @com.transfer.flash.core.common.annotation.FlashInternalApi
    public fun ensureFactoryRegistered(className: String) {
        val loader = Thread.currentThread().contextClassLoader ?: FlashDesktop::class.java.classLoader
        try {
            Class.forName(className, true, loader)
        } catch (_: ClassNotFoundException) {
            // Not on the classpath: the caller registers its own factory, or create() reports there is none.
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "The desktop engine class $className is on the classpath but failed to initialise: $failure",
                failure,
            )
        }
    }
}
