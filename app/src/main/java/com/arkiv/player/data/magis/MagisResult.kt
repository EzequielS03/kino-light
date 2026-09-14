package com.arkiv.player.data.magis

/**
 * Result of a call to the Magis portal. Distinguishes the three things the Python port returned as
 * magic dicts (`_error` / `_exception` / bare JSON): a rejection from the portal (it answered, but
 * said no) isn't the same as the portal not answering — the first calls for reauthentication, the
 * second for a retry or a "no connection" notice.
 */
internal sealed class MagisResult<out T> {
    data class Ok<out T>(val data: T) : MagisResult<T>()

    /** The portal answered with `returnCode != "0"` (e.g. `aaa100028` = "not logged in"). */
    data class PortalError(val code: String, val msg: String?) : MagisResult<Nothing>()

    /** No portal host answered (timeout, DNS, TLS, or JSON that can't be parsed). */
    data class RedError(val cause: Throwable) : MagisResult<Nothing>()

    /**
     * The data if it was [Ok], `null` if not. Exists because `Ok` is generic: without this every
     * call site would have to write `(r as MagisResult.Ok<Something>).data` — Kotlin doesn't infer
     * the type argument in an `as`, and with `Ok<*>` the `data` ends up as `Any?`.
     */
    fun getOrNull(): T? = if (this is Ok<T>) data else null

    /**
     * Re-types a result that ISN'T [Ok] so it can be returned from a function that produces
     * something else. Fails on purpose if used on an [Ok]: that means there's a value someone is
     * throwing away.
     */
    fun <R> asError(): MagisResult<R> = when (this) {
        is PortalError -> this
        is RedError -> this
        is Ok<T> -> error("asError() on an Ok: the data was being discarded")
    }

    /** Maps the data while keeping the error as-is — so the layers above (catalog, resolution)
     *  can translate JSON into their models without repeating the three-case `when`. */
    inline fun <R> map(transform: (T) -> R): MagisResult<R> = when (this) {
        is Ok<T> -> Ok(transform(data))
        is PortalError -> this
        is RedError -> this
    }
}
