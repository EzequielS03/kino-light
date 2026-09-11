package com.arkiv.player.crash

import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Un reporte de error, con la forma de campos que tenía la colección `crash_logs` de PocketBase
 * -Task 9 (sub-proyecto 2B) se llevó la subida (ver el KDoc de `Crash`), pero los nombres se
 * quedaron así porque el consumidor sigue siendo el mismo: leer el JSON a mano-.
 *
 * Es data pura a propósito: nada de acá toca Android ni la red, así que se puede armar dentro del
 * handler de excepciones no atrapadas (donde el proceso ya se está muriendo y no hay margen para
 * inicializar nada).
 */
data class CrashReport(
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
     * Los campos van recortados con el mismo tope que tenía la colección `crash_logs` de
     * PocketBase -aunque el reporte ya no suba a ningún lado (ver el KDoc de `Crash`), el tope
     * sigue evitando un JSON local gigante-. Ya pasó una vez con el logcat sin recortar.
     */
    fun toJson(): String = JSONObject()
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
         * The same report with the logcat emptied out. Written for retrying an upload that was too
         * big to fit -- that upload path (`CrashUploader`, PocketBase) was removed in Task 9
         * (sub-project 2B, see `Crash`'s KDoc), so this function currently has no caller outside
         * its own test. If it's ever wired to something new, keep the non-JSON passthrough: losing
         * the retry is worse than passing along something the new consumer can't use either.
         */
        fun sinLogcat(json: String): String =
            runCatching { JSONObject(json).put("logcat", "").toString() }.getOrDefault(json)

        /** One line summarizing the error -- meant for scanning a list of reports at a glance
         *  without opening each one (originally the PocketBase admin's list, now `adb logcat`). */
        fun mensajeDe(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() }
                ?.let { "${t.javaClass.name}: $it" }
                ?: t.javaClass.name
    }
}
