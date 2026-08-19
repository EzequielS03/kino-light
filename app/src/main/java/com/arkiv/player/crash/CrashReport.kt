package com.arkiv.player.crash

import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Un reporte de error listo para viajar a la colección `crash_logs` de PocketBase.
 *
 * Es data pura a propósito: nada de acá toca Android ni la red, así que se puede armar dentro del
 * handler de excepciones no atrapadas (donde el proceso ya se está muriendo y no hay margen para
 * inicializar nada).
 */
data class CrashReport(
    val accountId: String,
    val deviceId: String,
    val kind: String,
    val appVersion: String,
    /** Versión de Android + marca y modelo. Viaja al campo `android` de la colección. */
    val sistema: String,
    /** `true` si el error mató el proceso; `false` si lo reportamos a mano estando vivos. */
    val fatal: Boolean,
    /** Hilo donde reventó, o la etiqueta que le pasó quien reportó a mano. */
    val contexto: String,
    val mensaje: String,
    val stacktrace: String,
    val logcat: String,
    val ocurridoEn: String,
) {
    /**
     * Los campos van recortados a lo que entra en la colección.
     *
     * PocketBase rechaza el registro ENTERO si uno solo se pasa de largo, y el reporte se pierde.
     * Ya pasó una vez con el logcat. Se recorta acá para no depender de que los topes del servidor
     * estén bien puestos.
     */
    fun toJson(): String = JSONObject()
        .put("account_id", accountId.take(200))
        .put("device_id", deviceId.take(200))
        .put("kind", kind.take(50))
        .put("app_version", appVersion.take(200))
        .put("android", sistema.take(300))
        .put("fatal", fatal)
        .put("contexto", contexto.take(400))
        .put("mensaje", mensaje.take(TOPE_MENSAJE))
        // La cabeza del stacktrace: la excepción y los marcos de arriba.
        .put("stacktrace", stacktrace.take(TOPE_STACKTRACE))
        // La cola del logcat: interesa lo ÚLTIMO que pasó antes de reventar.
        .put("logcat", logcat.takeLast(TOPE_LOGCAT))
        .put("ocurrido_en", ocurridoEn.take(60))
        .toString()

    companion object {
        private const val TOPE_MENSAJE = 1_000
        private const val TOPE_STACKTRACE = 40_000
        private const val TOPE_LOGCAT = 200_000

        /**
         * La pila completa CON las causas encadenadas ("Caused by:").
         *
         * `printStackTrace` y no `Log.getStackTraceString`: el segundo es de Android, así que en un
         * test JVM devuelve vacío y no habría forma honesta de probar que la causa viaja.
         */
        fun stacktraceDe(t: Throwable): String {
            val sw = StringWriter()
            PrintWriter(sw).use { t.printStackTrace(it) }
            return sw.toString()
        }

        /**
         * El mismo reporte con el logcat vacío, para reintentar cuando el entero no entra.
         *
         * Si lo que llega no es JSON, vuelve tal cual: perder el intento es peor que mandar algo
         * que el servidor va a rechazar igual.
         */
        fun sinLogcat(json: String): String =
            runCatching { JSONObject(json).put("logcat", "").toString() }.getOrDefault(json)

        /** Una línea para poder barrer la lista en el admin de PocketBase sin abrir cada registro. */
        fun mensajeDe(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() }
                ?.let { "${t.javaClass.name}: $it" }
                ?: t.javaClass.name
    }
}
