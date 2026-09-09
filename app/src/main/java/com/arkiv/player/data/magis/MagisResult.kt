package com.arkiv.player.data.magis

/**
 * Resultado de una llamada al portal de Magis. Distingue las tres cosas que el puerto de Python
 * devolvía como dicts mágicos (`_error` / `_exception` / el JSON pelado): un rechazo del portal
 * (respondió, pero dijo no) no es lo mismo que el portal no responder — ante lo primero hay que
 * reautenticar, ante lo segundo reintentar o avisar "sin conexión".
 */
internal sealed class MagisResult<out T> {
    data class Ok<out T>(val data: T) : MagisResult<T>()

    /** El portal contestó con `returnCode != "0"` (ej. `aaa100028` = "no has iniciado sesión"). */
    data class PortalError(val codigo: String, val msg: String?) : MagisResult<Nothing>()

    /** Ningún host del portal contestó (timeout, DNS, TLS, o JSON que no se puede parsear). */
    data class RedError(val causa: Throwable) : MagisResult<Nothing>()

    /**
     * El dato si fue [Ok], `null` si no. Existe porque `Ok` es genérico: sin esto cada call-site
     * tendría que escribir `(r as MagisResult.Ok<Algo>).data` — Kotlin no infiere el argumento de
     * tipo en un `as`, y con `Ok<*>` el `data` queda en `Any?`.
     */
    fun dato(): T? = if (this is Ok<T>) data else null

    /** Mapea el dato conservando el error tal cual — para que las capas de arriba (catálogo,
     *  resolución) traduzcan JSON a sus modelos sin repetir el `when` de los tres casos. */
    inline fun <R> map(transform: (T) -> R): MagisResult<R> = when (this) {
        is Ok<T> -> Ok(transform(data))
        is PortalError -> this
        is RedError -> this
    }
}
