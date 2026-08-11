package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hallazgo F2 de la revisión final: no había NI UN test que construyera [FirmaDelGateway]. Estos
 * cubren los dos bugs que esa ausencia dejó pasar:
 *
 * 1. Con `lote = 1` (el valor de producción, ver `AppGraph`) la cola nunca se drenaba
 *    (`if (pendientes.size == 1) pendientes.first() else pendientes.removeFirst()` -- con un solo
 *    elemento siempre entraba por la rama `first()`, que solo espía sin sacar), así que
 *    `firmar()` devolvía SIEMPRE la primera firma pedida, con un `start_moment` que el CDN
 *    empieza a rechazar a los pocos segundos.
 * 2. `rechazada()` mutaba el `ArrayDeque` (no thread-safe) desde el hilo plano de la conexión de
 *    `LiveHlsProxy`, sin ningún candado en común con `firmar()` (que usaba un `Mutex` de
 *    corrutina, inutilizable desde `fun` corriente).
 *
 * Sigue el mismo patrón que `LiveApiTest` (MockWebServer + `LiveApi` real): `LiveApi` es una
 * clase FINAL a propósito (ver su KDoc), así que no hay forma de fakearla sin pegarle a un
 * servidor -aunque sea uno de prueba local-.
 */
class FirmaDelGatewayTest {
    private fun api(server: MockWebServer) = LiveApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        apiKey = { "k" },
        http = OkHttpClient(),
    )

    private fun respuestaConFirmas(vararg pares: Pair<Long, String>) = MockResponse().setBody(
        """{"firmas":[${pares.joinToString(",") { (m, s) -> """{"moment":$m,"sign2":"$s"}""" }}]}""",
    )

    @Test
    fun `con lote 1 cada firmar pide una firma nueva al gateway`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(respuestaConFirmas(1L to "aa"))
        server.enqueue(respuestaConFirmas(2L to "bb"))
        server.enqueue(respuestaConFirmas(3L to "cc"))
        server.start()

        val f = FirmaDelGateway(api(server), lote = 1)
        val f1 = f.firmar("t")
        val f2 = f.firmar("t")
        val f3 = f.firmar("t")

        // Antes del fix, f2 y f3 hubieran repetido f1 ("aa") para siempre: la cola nunca drenaba.
        assertEquals("aa", f1.sign2)
        assertEquals("bb", f2.sign2)
        assertEquals("cc", f3.sign2)
        assertEquals(3, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `con lote mayor a 1 se sirve del mismo lote hasta agotarlo, y recien ahi pide otro`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(respuestaConFirmas(1L to "aa", 2L to "bb"))
        server.enqueue(respuestaConFirmas(3L to "cc", 4L to "dd"))
        server.start()

        val f = FirmaDelGateway(api(server), lote = 2)
        assertEquals("aa", f.firmar("t").sign2)
        assertEquals("bb", f.firmar("t").sign2)  // del mismo lote: sin pedido de red nuevo
        assertEquals(1, server.requestCount)
        assertEquals("cc", f.firmar("t").sign2)  // lote agotado: pide el próximo
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `rechazada descarta el lote pendiente y el proximo firmar pide uno nuevo`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(respuestaConFirmas(1L to "aa", 2L to "bb", 3L to "cc"))
        server.enqueue(respuestaConFirmas(4L to "dd"))
        server.start()

        val f = FirmaDelGateway(api(server), lote = 3)
        assertEquals("aa", f.firmar("t").sign2)
        f.rechazada()  // el CDN rechazó "aa": "bb"/"cc" (mismo lote) tampoco sirven
        assertEquals(1, server.requestCount)

        assertEquals("dd", f.firmar("t").sign2)  // no repite "bb": pidió un lote nuevo
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    /**
     * No reproduce la carrera de memoria de un `ArrayDeque` sin sincronizar (eso necesitaría un
     * detector como jcstress, fuera del alcance del proyecto -mismo criterio que documenta
     * `LiveController` para su propio `ConcurrentHashMap`-), pero sí prueba el contrato que le
     * importa a producción: `rechazada()` llamada desde un hilo PLANO (como hace
     * `LiveHlsProxy.pedirAlOrigen`, nunca desde una corrutina) mientras otros hilos están en medio
     * de `firmar()` no debe perder ni corromper firmas -cada firma que sale de `firmar()` es una
     * de las que devolvió el servidor, sin duplicados ni basura-.
     */
    @Test
    fun `rechazada desde un hilo plano concurrente con firmar no corrompe la cola`() = runBlocking {
        val server = MockWebServer()
        val entregadas = 40
        repeat(entregadas) { i -> server.enqueue(respuestaConFirmas(i.toLong() to "s$i")) }
        server.start()

        val f = FirmaDelGateway(api(server), lote = 1)
        val vistas = java.util.concurrent.CopyOnWriteArrayList<String>()
        val hilosFirmando = (1..entregadas).map {
            Thread { vistas.add(runBlocking { f.firmar("t") }.sign2) }
        }
        val hilosRechazando = (1..10).map {
            Thread { repeat(5) { f.rechazada(); Thread.sleep(0, 100) } }
        }
        (hilosFirmando + hilosRechazando).forEach { it.start() }
        (hilosFirmando + hilosRechazando).forEach { it.join() }

        // Cada firma que salió de firmar() vino del servidor (formato "sN"); ninguna quedó
        // repetida por una lectura corrupta del ArrayDeque.
        assertEquals(entregadas, vistas.size)
        assertEquals(entregadas, vistas.toSet().size)
        server.shutdown()
    }
}
