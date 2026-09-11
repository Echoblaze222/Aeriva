package com.aeriva.core.result

/**
 * Outcome of a repository/use-case operation that can genuinely fail
 * (a call to a platform API, backend, or something else outside this
 * process's control). This is NOT the same thing as a screen's action
 * state (architecture doc Section 5.2, IDLE/PROCESSING/SUCCESS/ERROR/
 * CANCELLED) -- that models a user-triggered operation's lifecycle in
 * the presentation layer. [AerivaResult] models what a single call
 * returned. A ViewModel typically drives one from the other; they are
 * not interchangeable.
 */
sealed interface AerivaResult<out T> {
    data class Success<out T>(val value: T) : AerivaResult<T>
    data class Failure(val error: AerivaError) : AerivaResult<Nothing>
}

/**
 * Deliberately small and closed. Per architecture doc Section 2.3
 * (capability truth) and engineering standards ("do not silently
 * swallow errors"), every caller of something that returns
 * [AerivaResult] must be able to explain a failure to the user in one
 * of these terms -- not in "developer exception message" terms. Add a
 * case here only when a real caller needs to distinguish it; do not
 * pre-build cases nothing produces yet.
 */
sealed interface AerivaError {
    data class Unsupported(val reason: String) : AerivaError
    data class PermissionRequired(val permission: String) : AerivaError
    data class Unknown(val throwable: Throwable) : AerivaError
}

inline fun <T, R> AerivaResult<T>.map(transform: (T) -> R): AerivaResult<R> = when (this) {
    is AerivaResult.Success -> AerivaResult.Success(transform(value))
    is AerivaResult.Failure -> this
}

inline fun <T> AerivaResult<T>.onSuccess(action: (T) -> Unit): AerivaResult<T> {
    if (this is AerivaResult.Success) action(value)
    return this
}

inline fun <T> AerivaResult<T>.onFailure(action: (AerivaError) -> Unit): AerivaResult<T> {
    if (this is AerivaResult.Failure) action(error)
    return this
}
