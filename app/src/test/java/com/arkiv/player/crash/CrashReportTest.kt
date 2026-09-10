package com.arkiv.player.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El payload del reporte de error local (ver el KDoc de `CrashReport`: tiene la forma que tenía la
 * colección `crash_logs` de PocketBase, aunque ya no sube a ningún lado).
 *
 * Lo importante acá es la CAUSA encadenada: casi todo lo que revienta en el app llega envuelto
 * (`RuntimeException` alrededor de la de verdad), así que un stacktrace que corte en la de afuera
 * no dice nada de por qué falló.
 */
class CrashReportTest {
    private fun reporte(
        stacktrace: String = "java.lang.RuntimeException: algo",
        mensaje: String = "java.lang.RuntimeException: algo",
        logcat: String = "",
    ) = CrashReport(
        kind = "phone",
        appVersion = "1.4.2 (142) release",
        sistema = "Android 14 (SDK 34) · samsung SM-S926B",
        fatal = true,
        contexto = "main",
        mensaje = mensaje,
        stacktrace = stacktrace,
        logcat = logcat,
        ocurridoEn = "2026-08-19T21:00:00Z",
    )

    @Test
    fun `el stacktrace lleva la excepcion de afuera y su causa`() {
        val raiz = IllegalStateException("no habia stream")
        val envuelta = RuntimeException("se cayo el player", raiz)

        val texto = CrashReport.stacktraceDe(envuelta)

        assertTrue(texto, texto.contains("java.lang.RuntimeException: se cayo el player"))
        assertTrue(texto, texto.contains("Caused by: java.lang.IllegalStateException: no habia stream"))
        assertTrue("falta el marco de la pila", texto.contains("CrashReportTest"))
    }

    @Test
    fun `el mensaje resume la excepcion en una linea`() {
        val t = IllegalArgumentException("tmdbId vacio")

        assertEquals("java.lang.IllegalArgumentException: tmdbId vacio", CrashReport.mensajeDe(t))
    }

    @Test
    fun `el mensaje de una excepcion sin texto igual dice de que clase es`() {
        assertEquals("java.lang.NullPointerException", CrashReport.mensajeDe(NullPointerException()))
    }

    @Test
    fun `toJson escribe los campos con los nombres de la coleccion`() {
        val json = JSONObject(reporte(logcat = "linea de log").toJson())

        assertEquals("phone", json.getString("kind"))
        assertEquals("1.4.2 (142) release", json.getString("app_version"))
        assertEquals("Android 14 (SDK 34) · samsung SM-S926B", json.getString("android"))
        assertEquals(true, json.getBoolean("fatal"))
        assertEquals("main", json.getString("contexto"))
        assertEquals("java.lang.RuntimeException: algo", json.getString("mensaje"))
        assertEquals("java.lang.RuntimeException: algo", json.getString("stacktrace"))
        assertEquals("linea de log", json.getString("logcat"))
        assertEquals("2026-08-19T21:00:00Z", json.getString("ocurrido_en"))
    }

    /**
     * El recorte de emergencia: si el reporte entero no entra, el logcat es lo primero que se
     * suelta. El stacktrace es lo único que no se puede reconstruir después.
     */
    @Test
    fun `sinLogcat vacia el logcat y deja el resto intacto`() {
        val recortado = JSONObject(CrashReport.sinLogcat(reporte(logcat = "cuarenta mil lineas").toJson()))

        assertEquals("", recortado.getString("logcat"))
        assertEquals("java.lang.RuntimeException: algo", recortado.getString("stacktrace"))
        assertEquals("phone", recortado.getString("kind"))
    }

    @Test
    fun `sinLogcat sobre algo que no es json lo devuelve tal cual`() {
        assertEquals("esto no es json", CrashReport.sinLogcat("esto no es json"))
    }

    /**
     * Mismo tope que tenía la colección `crash_logs` de PocketBase (ver el KDoc de
     * `CrashReport.toJson`). Ya pasó una vez con el logcat sin recortar.
     */
    @Test
    fun `toJson recorta los campos que no entrarian en la coleccion`() {
        val json = JSONObject(
            reporte(
                mensaje = "x".repeat(9_000),
                stacktrace = "y".repeat(90_000),
                logcat = "z".repeat(400_000),
            ).toJson(),
        )

        assertEquals(1_000, json.getString("mensaje").length)
        assertEquals(40_000, json.getString("stacktrace").length)
        assertEquals(200_000, json.getString("logcat").length)
    }

    /** Del logcat interesa lo ÚLTIMO que pasó, no lo primero: se recorta por delante. */
    @Test
    fun `al recortar el logcat se queda con el final`() {
        val json = JSONObject(reporte(logcat = "viejo".padEnd(400_000, 'x') + "LO ULTIMO").toJson())

        assertTrue(json.getString("logcat").endsWith("LO ULTIMO"))
    }

    /** Del stacktrace interesa la cabeza: la excepción y los marcos de arriba. */
    @Test
    fun `al recortar el stacktrace se queda con el principio`() {
        val json = JSONObject(reporte(stacktrace = "LA EXCEPCION" + "y".repeat(90_000)).toJson())

        assertTrue(json.getString("stacktrace").startsWith("LA EXCEPCION"))
    }
}
