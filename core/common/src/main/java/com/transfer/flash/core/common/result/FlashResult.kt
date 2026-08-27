package com.transfer.flash.core.common.result

/**
 * Functional monadic result wrapper representing either a [Success] with value [T]
 * or a [Failure] with a typed [FlashError].
 */
public sealed interface FlashResult<out T> {
    public data class Success<out T>(val value: T) : FlashResult<T>
    public data class Failure(val error: FlashError) : FlashResult<Nothing>

    public val isSuccess: Boolean get() = this is Success
    public val isFailure: Boolean get() = this is Failure

    public companion object {
        public inline fun <T> runCatching(block: () -> T): FlashResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(FlashError.Unknown(t.message ?: "Unknown exception occurred", t))
        }
    }
}

public fun <T> FlashResult<T>.getOrNull(): T? = when (this) {
    is FlashResult.Success -> value
    is FlashResult.Failure -> null
}

public inline fun <T> FlashResult<T>.getOrElse(default: (FlashError) -> T): T = when (this) {
    is FlashResult.Success -> value
    is FlashResult.Failure -> default(error)
}

public inline fun <T, R> FlashResult<T>.map(transform: (T) -> R): FlashResult<R> = when (this) {
    is FlashResult.Success -> FlashResult.Success(transform(value))
    is FlashResult.Failure -> this
}

public inline fun <T, R> FlashResult<T>.flatMap(transform: (T) -> FlashResult<R>): FlashResult<R> = when (this) {
    is FlashResult.Success -> transform(value)
    is FlashResult.Failure -> this
}

public inline fun <T> FlashResult<T>.onSuccess(action: (T) -> Unit): FlashResult<T> {
    if (this is FlashResult.Success) action(value)
    return this
}

public inline fun <T> FlashResult<T>.onFailure(action: (FlashError) -> Unit): FlashResult<T> {
    if (this is FlashResult.Failure) action(error)
    return this
}

public inline fun <T, R> FlashResult<T>.fold(onSuccess: (T) -> R, onFailure: (FlashError) -> R): R = when (this) {
    is FlashResult.Success -> onSuccess(value)
    is FlashResult.Failure -> onFailure(error)
}
