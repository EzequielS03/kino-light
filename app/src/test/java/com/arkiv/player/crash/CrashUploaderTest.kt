package com.arkiv.player.crash

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El envío a PocketBase. Va SIN token a propósito: la colección `crash_logs` tiene `createRule`
 * público justamente para que un crash se pueda reportar aunque la sesión esté rota o vencida —
 * que es el escenario donde más falta hace.
 */
class CrashUploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var reloj = 1_000L

    @Before
    fun arrancar() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun apagar() {
        server.shutdown()
    }

    private fun uploader(base: String = server.url("/").toString().trimEnd('/')) =
        CrashUploader(baseUrl = base, client = OkHttpClient())

    private fun store() = CrashStore(dir = tmp.newFolder("cola-$reloj"), maxPendientes = 20, ahora = { reloj })

    @Test
    fun `subir postea el json tal cual a la coleccion crash_logs`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        runBlocking { uploader().subir("""{"mensaje":"se cayo"}""") }

        val pedido = server.takeRequest()
        assertEquals("POST", pedido.method)
        assertEquals("/api/collections/crash_logs/records", pedido.path)
        assertEquals("se cayo", JSONObject(pedido.body.readUtf8()).getString("mensaje"))
    }

    @Test
    fun `subir no manda Authorization`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        runBlocking { uploader().subir("{}") }

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `subir avisa que si con 200`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertTrue(runBlocking { uploader().subir("{}") })
    }

    @Test
    fun `subir avisa que no con 500`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertFalse(runBlocking { uploader().subir("{}") })
    }

    @Test
    fun `subir avisa que no en vez de reventar cuando no hay red`() {
        server.shutdown()

        assertFalse(runBlocking { uploader().subir("{}") })
    }

    @Test
    fun `drenar sube los pendientes y los borra`() {
        val store = store()
        store.guardar("""{"mensaje":"uno"}""")
        reloj = 2_000L
        store.guardar("""{"mensaje":"dos"}""")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }

        runBlocking { uploader().drenar(store) }

        assertEquals(2, server.requestCount)
        assertTrue(store.pendientes().isEmpty())
    }

    @Test
    fun `drenar manda primero el mas viejo`() {
        val store = store()
        store.guardar("""{"mensaje":"viejo"}""")
        reloj = 2_000L
        store.guardar("""{"mensaje":"nuevo"}""")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }

        runBlocking { uploader().drenar(store) }

        assertEquals("viejo", JSONObject(server.takeRequest().body.readUtf8()).getString("mensaje"))
        assertEquals("nuevo", JSONObject(server.takeRequest().body.readUtf8()).getString("mensaje"))
    }

    /**
     * Sin este corte, un arranque sin red gasta una petición por cada pendiente para fallar todas
     * igual. La cola queda intacta y se reintenta en el arranque siguiente.
     */
    @Test
    fun `drenar corta al primer fallo y conserva la cola`() {
        val store = store()
        store.guardar("""{"mensaje":"uno"}""")
        reloj = 2_000L
        store.guardar("""{"mensaje":"dos"}""")
        server.enqueue(MockResponse().setResponseCode(500))

        runBlocking { uploader().drenar(store) }

        assertEquals(1, server.requestCount)
        assertEquals(2, store.pendientes().size)
    }

    /**
     * El descarte en seco de un 4xx nos costó caro: PocketBase rechazaba el reporte por un campo
     * pasado de largo y el reporte se perdía en silencio, que es justo lo contrario de para qué
     * existe esto. Antes de darlo por perdido se reintenta con lo imprescindible.
     */
    @Test
    fun `si el servidor rechaza el reporte, lo reintenta sin el logcat`() {
        val store = store()
        store.guardar("""{"stacktrace":"la pila entera","logcat":"cuarenta mil lineas"}""")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"data":{"logcat":{}}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        runBlocking { uploader().drenar(store) }

        assertEquals(2, server.requestCount)
        server.takeRequest()
        val reintento = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("", reintento.getString("logcat"))
        assertEquals("la pila entera", reintento.getString("stacktrace"))
        assertTrue(store.pendientes().isEmpty())
    }

    @Test
    fun `un 500 no dispara el reintento sin logcat`() {
        val store = store()
        store.guardar("""{"logcat":"algo"}""")
        server.enqueue(MockResponse().setResponseCode(500))

        runBlocking { uploader().drenar(store) }

        assertEquals(1, server.requestCount)
        assertEquals(1, store.pendientes().size)
    }

    /** Si ni siquiera pelado entra, no hay nada que hacer: se descarta para no tapar la cola. */
    @Test
    fun `si el reintento sin logcat tambien lo rechazan, ahi si lo descarta`() {
        val store = store()
        store.guardar("""{"logcat":"algo"}""")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("{}")) }

        runBlocking { uploader().drenar(store) }

        assertEquals(2, server.requestCount)
        assertTrue(store.pendientes().isEmpty())
    }

    @Test
    fun `subir avisa que si cuando pega el reintento sin logcat`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertTrue(runBlocking { uploader().subir("""{"logcat":"algo"}""") })
    }

    @Test
    fun `drenar con la cola vacia no pega ninguna peticion`() {
        runBlocking { uploader().drenar(store()) }

        assertEquals(0, server.requestCount)
    }
}
