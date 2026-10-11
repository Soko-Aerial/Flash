package com.transfer.flash.core.common.perf

/**
 * Coordinates background worker execution with UI scroll fling activity.
 *
 * When the user is scrolling or flinging a list on CPU-constrained devices (e.g. BelFone SCP810,
 * low-clock quad-core A53), background tasks like subnet sweepers yield execution or insert short delays
 * so the UI main thread can hit its 60Hz frame deadline without stutter or dropped frames.
 */
public object UiScrollGovernor {

    public interface StateFlow<out T> {
        public val value: T
    }

    @kotlin.jvm.Volatile
    private var _scrolling: Boolean = false

    private val flowInstance: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val value: Boolean
            get() = _scrolling
    }

    public val isScrolling: StateFlow<Boolean> get() = flowInstance

    /**
     * Updates the current scrolling state. Called from UI scroll listeners or LaunchedEffects.
     */
    public fun onScrollChanged(scrolling: Boolean) {
        _scrolling = scrolling
    }
}
