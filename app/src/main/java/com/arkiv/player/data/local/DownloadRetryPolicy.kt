package com.arkiv.player.data.local

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Un código HTTP que no fue 2xx. Tipada para poder clasificarla sin parsear el mensaje. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** La respuesta se cortó antes de completar el `Content-Length` declarado. */
class IncompleteDownloadException(val written: Long, val total: Long) :
    IOException("descarga incompleta: $written de $total bytes")

/**
 * Qué fallos vale la pena reintentar solos y cuántas veces.
 *
 * La distinción es entre "el mundo se movió y en un rato puede andar" (red) y "esto va a fallar
 * igual mañana" (fuente no soportada, sin espacio, película web). Reintentar lo segundo gasta datos
 * y batería sin ninguna chance de éxito; NO reintentar lo primero deja una descarga de 4 GB al 80%
 * en `failed` porque el WiFi parpadeó 30 segundos, con el `.part` intacto y nadie que lo retome.
 *
 * Puro a propósito (no toca WorkManager ni Room): así se testea en la JVM sin device.
 */
object DownloadRetryPolicy {

    /**
     * Tope de intentos automáticos de UNA fila. Sin tope, una fuente que devuelve 503 para siempre
     * reintentaría en bucle hasta que el usuario borre la fila. Agotado el tope la fila queda en
     * `failed` con su motivo y el botón "Reintentar" de la pantalla sigue disponible.
     */
    const val MAX_ATTEMPTS = 4

    /**
     * Códigos HTTP que sí vale reintentar: 408 (timeout del request), 429 (nos frenaron) y todo 5xx
     * (el server está mal ahora). Un 403/404 en cambio significa que el enlace caducó o no existe:
     * reintentarlo con el mismo `Range` va a fallar exactamente igual.
     */
    fun isTransientStatus(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    /**
     * Fallo de red o de servidor (reintentable) vs fallo de contenido/entorno (definitivo).
     *
     * Todo lo que no sea una `IOException` cuenta como definitivo: una excepción inesperada de
     * lógica no se arregla esperando 30 segundos.
     */
    fun isTransient(t: Throwable): Boolean = when (t) {
        is HttpStatusException -> isTransientStatus(t.code)
        // Corte a mitad de la transferencia: es exactamente el caso del `.part` + `Range`.
        is IncompleteDownloadException -> true
        is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException -> true
        is IOException -> true
        else -> false
    }

    /**
     * [attempt] es el número de intentos YA hechos de esta fila (0 en el primero). WorkManager lo
     * expone como `runAttemptCount` y aplica el backoff exponencial entre uno y otro.
     */
    fun shouldRetry(transient: Boolean, attempt: Int): Boolean = transient && attempt + 1 < MAX_ATTEMPTS
}
