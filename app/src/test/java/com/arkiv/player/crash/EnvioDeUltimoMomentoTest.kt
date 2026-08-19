package com.arkiv.player.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * El intento de mandar el reporte EN EL ACTO, mientras el proceso se muere.
 *
 * Hace falta para el caso que más duele: un app que revienta apenas abre. Ahí el drenado de fondo
 * del arranque siguiente puede no llegar a terminar nunca, porque el proceso se muere antes. Este
 * intento corre igual y, si pega, el reporte ya está del otro lado.
 *
 * La cola en disco sigue siendo la red de seguridad: si este intento falla, el archivo queda.
 */
class EnvioDeUltimoMomentoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var carpetas = 0

    private fun store() = CrashStore(dir = tmp.newFolder("cola-${carpetas++}"), maxPendientes = 20)

    @Test
    fun `sube el reporte y lo saca de la cola`() {
        val store = store()
        val archivo = store.guardar("""{"mensaje":"se cayo"}""")
        var enviado: String? = null

        EnvioDeUltimoMomento(store = store, subir = { enviado = it; true })(archivo)

        assertEquals("""{"mensaje":"se cayo"}""", enviado)
        assertTrue(store.pendientes().isEmpty())
    }

    @Test
    fun `si el envio falla el reporte queda en la cola`() {
        val store = store()
        val archivo = store.guardar("""{"mensaje":"se cayo"}""")

        EnvioDeUltimoMomento(store = store, subir = { false })(archivo)

        assertEquals(1, store.pendientes().size)
    }

    @Test
    fun `si el envio revienta el reporte queda en la cola`() {
        val store = store()
        val archivo = store.guardar("""{"mensaje":"se cayo"}""")

        EnvioDeUltimoMomento(store = store, subir = { error("sin red") })(archivo)

        assertEquals(1, store.pendientes().size)
    }

    /**
     * En Android, red en el hilo principal es `NetworkOnMainThreadException` — y el handler de
     * crash casi siempre corre ahí. Si esto no saliera del hilo que llama, el envío nunca pegaría.
     */
    @Test
    fun `el envio corre fuera del hilo que revento`() {
        val store = store()
        val archivo = store.guardar("{}")
        val queLlama = Thread.currentThread()
        var queEnvia: Thread? = null

        EnvioDeUltimoMomento(store = store, subir = { queEnvia = Thread.currentThread(); true })(archivo)

        assertNotEquals(queLlama, queEnvia)
    }

    /**
     * Sin tope, un envío colgado (red que acepta la conexión y no contesta nunca) dejaría el app
     * congelado muriéndose. Se le da un rato y se sigue.
     */
    @Test
    fun `no espera mas de lo permitido si el envio se cuelga`() {
        val store = store()
        val archivo = store.guardar("{}")
        val colgado = CountDownLatch(1)

        val arranque = System.nanoTime()
        EnvioDeUltimoMomento(
            store = store,
            subir = { colgado.await(30, TimeUnit.SECONDS); true },
            esperaMaximaMs = 300,
        )(archivo)
        val tardo = (System.nanoTime() - arranque) / 1_000_000

        // Mirar la cola ANTES de soltar el envío: el hilo abandonado sigue vivo y, si llegara a
        // terminar, borraría el archivo (cosa que está bien, pero no es lo que se mide acá).
        assertTrue("tardó ${tardo}ms", tardo < 5_000)
        assertEquals(1, store.pendientes().size)
        colgado.countDown()
    }
}
