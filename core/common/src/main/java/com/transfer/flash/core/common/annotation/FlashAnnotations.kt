package com.transfer.flash.core.common.annotation

/**
 * Marks declarations that are internal to the Flash library architecture across modules,
 * but are not intended for public/external consumption.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal Flash API and should not be used outside the Flash library modules."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class FlashInternalApi

/**
 * Marks declarations that are experimental, under active evaluation, and subject to change.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This Flash API is experimental and subject to change in future releases."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class FlashExperimentalApi
