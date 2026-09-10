package com.arkiv.player.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El pegamento: agarra la excepción, arma el reporte y lo deja en la cola.
 *
 * La regla que gobierna todo este archivo: **nada de lo que pase acá adentro puede impedir que el
 * stacktrace se guarde, ni cambiar cómo muere el app**. Si falla leer la identidad, o el logcat, o
 * el disco, el reporte sale igual (con lo que se pueda) y el crash sigue su curso normal.
 */
class CrashGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val datos = DatosDelAparato(
        accountId = "cuenta-1",
        deviceId = "aparato-1",
        kind = "tv",
        appVersion = "1.4.2 (142) release",
        sistema = "Android 14 (SDK 34) · samsung SM-S926B",
    )

    private fun guard(
        store: CrashStore,
        datos: () -> DatosDelAparato = { this.datos },
        logcat: () -> String = { "linea de logcat" },
    ) = CrashGuard(store = store, datos = datos, logcat = logcat, ahora = { "2026-08-19T21:00:00Z" })

    private var carpetas = 0

    private fun store() = CrashStore(dir = tmp.newFolder("cola-${carpetas++}"), maxPendientes = 20)

    private fun unicoReporte(store: CrashStore) = JSONObject(store.pendientes().single().readText())

    @Test
    fun `reportar deja en la cola un reporte con la identidad del aparato`() {
        val store = store()

        guard(store).reportar(IllegalStateException("no habia stream"), "resolviendo el capitulo")

        val json = unicoReporte(store)
        assertEquals("cuenta-1", json.getString("account_id"))
        assertEquals("aparato-1", json.getString("device_id"))
        assertEquals("tv", json.getString("kind"))
        assertEquals("1.4.2 (142) release", json.getString("app_version"))
        assertEquals("resolviendo el capitulo", json.getString("contexto"))
        assertEquals("linea de logcat", json.getString("logcat"))
        assertEquals("2026-08-19T21:00:00Z", json.getString("ocurrido_en"))
    }

    @Test
    fun `lo reportado a mano no es fatal y lo del handler si`() {
        val aMano = store()
        guard(aMano).reportar(RuntimeException("atrapada"), "un runCatching")
        assertFalse(unicoReporte(aMano).getBoolean("fatal"))

        val fatal = store()
        guard(fatal).atajar(Thread.currentThread(), RuntimeException("no atrapada"))
        assertTrue(unicoReporte(fatal).getBoolean("fatal"))
    }

    @Test
    fun `el reporte fatal dice en que hilo revento`() {
        val store = store()
        val hilo = Thread(null, {}, "hilo-del-player")

        guard(store).atajar(hilo, RuntimeException("revento"))

        assertEquals("hilo-del-player", unicoReporte(store).getString("contexto"))
    }

    @Test
    fun `el stacktrace guardado lleva la causa encadenada`() {
        val store = store()
        val envuelta = RuntimeException("se cayo el player", IllegalStateException("no habia stream"))

        guard(store).atajar(Thread.currentThread(), envuelta)

        val stacktrace = unicoReporte(store).getString("stacktrace")
        assertTrue(stacktrace, stacktrace.contains("Caused by: java.lang.IllegalStateException: no habia stream"))
    }

    @Test
    fun `si no se puede leer la identidad, el stacktrace se guarda igual`() {
        val store = store()

        guard(store, datos = { error("la sesion no arranco") })
            .atajar(Thread.currentThread(), IllegalStateException("lo que de verdad importa"))

        val json = unicoReporte(store)
        assertTrue(json.getString("stacktrace").contains("lo que de verdad importa"))
        assertEquals("", json.getString("account_id"))
    }

    @Test
    fun `si no se puede leer el logcat, el stacktrace se guarda igual`() {
        val store = store()

        guard(store, logcat = { error("logcat no disponible") })
            .atajar(Thread.currentThread(), IllegalStateException("lo que de verdad importa"))

        val json = unicoReporte(store)
        assertTrue(json.getString("stacktrace").contains("lo que de verdad importa"))
        assertEquals("", json.getString("logcat"))
    }

    /**
     * Lo más importante del archivo: instalar esto NO puede cambiar cómo muere el app. Si el
     * handler se comiera la excepción, un crash pasaría a ser un cuelgue mudo.
     */
    @Test
    fun `el handler guarda y le pasa la pelota al handler anterior`() {
        val store = store()
        var recibida: Throwable? = null
        val previo = Thread.UncaughtExceptionHandler { _, e -> recibida = e }
        val explosion = RuntimeException("boom")

        CrashHandler(previo = previo, guard = guard(store)).uncaughtException(Thread.currentThread(), explosion)

        assertTrue(unicoReporte(store).getString("stacktrace").contains("boom"))
        assertEquals(explosion, recibida)
    }

    @Test
    fun `si guardar revienta, igual le pasa la pelota al handler anterior`() {
        var recibida: Throwable? = null
        val previo = Thread.UncaughtExceptionHandler { _, e -> recibida = e }
        val discoRoto = CrashStore(dir = tmp.newFile("no-es-carpeta"), maxPendientes = 20)
        val explosion = RuntimeException("boom")

        CrashHandler(previo = previo, guard = guard(discoRoto)).uncaughtException(Thread.currentThread(), explosion)

        assertEquals(explosion, recibida)
    }

    @Test
    fun `sin handler anterior no revienta`() {
        val store = store()

        CrashHandler(previo = null, guard = guard(store)).uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(unicoReporte(store).getString("stacktrace").contains("boom"))
    }

    /**
     * `reportar` se llama desde `runCatching` repartidos por el app. Si reventara, convertiría un
     * error ya atrapado en un crash nuevo — el reportero matando al app que vino a diagnosticar.
     */
    @Test
    fun `reportar no revienta aunque el disco falle`() {
        val discoRoto = CrashStore(dir = tmp.newFile("tampoco-es-carpeta"), maxPendientes = 20)

        guard(discoRoto).reportar(RuntimeException("atrapada"), "un runCatching")
    }

    /** El archivo que dejó en la cola, para quien quiera hacer algo con él después de guardarlo. */
    @Test
    fun `atajar devuelve el archivo que dejo en la cola`() {
        val store = store()

        val archivo = store.let { guard(it).atajar(Thread.currentThread(), RuntimeException("boom")) }

        assertEquals(store.pendientes().single(), archivo)
    }
}
