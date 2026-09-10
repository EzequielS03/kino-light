package com.arkiv.player.crash

import java.time.Instant

/**
 * Lo que va en cada reporte además del error en sí: si es celu o TV, y la versión del app y del
 * sistema. Vacío si no se pudo averiguar.
 */
data class DatosDelAparato(
    val kind: String,
    val appVersion: String,
    val sistema: String,
) {
    companion object {
        val DESCONOCIDO = DatosDelAparato(kind = "", appVersion = "", sistema = "")
    }
}

/**
 * Arma el reporte y lo deja en la cola.
 *
 * Regla de la casa: **nada de acá adentro puede impedir que se guarde el stacktrace**. Si falla
 * leer la identidad, o el logcat, o el reloj, el reporte sale igual con lo que se pueda — porque
 * el stacktrace es lo único que no se puede reconstruir después.
 *
 * Sin dependencias de Android a propósito: lo específico del sistema entra por [datos] y [logcat]
 * (ver `CrashAndroid.kt`), así que todo esto se prueba en la JVM.
 */
class CrashGuard(
    private val store: CrashStore,
    private val datos: () -> DatosDelAparato,
    private val logcat: () -> String,
    private val ahora: () -> String = { Instant.now().toString() },
) {
    /** Un error que mató el proceso. Lo llama [CrashHandler]. Devuelve el archivo encolado. */
    fun atajar(hilo: Thread, error: Throwable): java.io.File =
        escribir(error, contexto = hilo.name, fatal = true)

    /**
     * Un error atrapado a mano, con el proceso vivo. Para los `runCatching` que hoy se tragan la
     * excepción en silencio. Nunca revienta: convertir un error ya atrapado en un crash nuevo
     * sería exactamente lo contrario de lo que vino a hacer.
     */
    fun reportar(error: Throwable, etiqueta: String) {
        runCatching { escribir(error, contexto = etiqueta, fatal = false) }
    }

    private fun escribir(error: Throwable, contexto: String, fatal: Boolean): java.io.File {
        val aparato = runCatching { datos() }.getOrDefault(DatosDelAparato.DESCONOCIDO)
        val reporte = CrashReport(
            kind = aparato.kind,
            appVersion = aparato.appVersion,
            sistema = aparato.sistema,
            fatal = fatal,
            contexto = contexto,
            mensaje = CrashReport.mensajeDe(error),
            stacktrace = CrashReport.stacktraceDe(error),
            logcat = runCatching { logcat() }.getOrDefault(""),
            ocurridoEn = runCatching { ahora() }.getOrDefault(""),
        )
        return store.guardar(reporte.toJson())
    }
}

/**
 * El handler de excepciones no atrapadas.
 *
 * Guarda el reporte y **le pasa la pelota al handler anterior**: sin eso, el app dejaría de
 * morirse como se muere hoy y un crash pasaría a ser un cuelgue mudo. Si guardar falla, se ignora
 * y se delega igual — la excepción original manda.
 */
class CrashHandler(
    private val previo: Thread.UncaughtExceptionHandler?,
    private val guard: CrashGuard,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(hilo: Thread, error: Throwable) {
        runCatching { guard.atajar(hilo, error) }
        previo?.uncaughtException(hilo, error)
    }
}
