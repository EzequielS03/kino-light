package com.arkiv.player.ui.settings

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.pocketbase.EstadoDeSesion
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Mis aparatos" (Task 6): listar, marcar cuál es ESTE aparato comparando localmente contra el
 * `recordId` de [com.arkiv.player.pocketbase.DeviceSession], y sacar uno con confirmación.
 *
 * El test que más importa acá es el mismo que en `EntradaViewModelTest`: un `backend_no_disponible`
 * NO puede cerrar la sesión -es la falla que ya costó tres rondas de corrección en este proyecto
 * (ver el brief de la Task 6)-, ni al listar ni al sacar.
 */
class MisAparatosViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeDeviceStore
    private lateinit var sesion: SesionDePersona

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        store = FakeDeviceStore()
        store.savePersonToken("person-tok")
        store.savePersonEmail("a@b.co")
        // `SesionDePersona` acá solo se usa para `.cerrar()`/`.estado`, nunca habla de red -- misma
        // nota que en CuentaApiTest.
        sesion = SesionDePersona(PocketBaseClient(baseUrl = "http://unused.invalid"), store)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun cuentaApi(): CuentaApi = CuentaApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        deviceToken = { "device-tok" },
        sesion = sesion,
        http = OkHttpClient(),
    )

    private fun vm(recordId: String? = "este-record-id") =
        MisAparatosViewModel(cuentaApi(), sesion) { recordId }

    private fun EstadoMisAparatos.aparatosOFail(): List<AparatoUi> =
        (this as? EstadoMisAparatos.Cargado)?.aparatos ?: error("se esperaba Cargado, dio $this")

    // --- marca cuál es este aparato, comparando localmente contra el recordId --------------

    @Test
    fun `cargar marca como este aparato el que coincide con el recordId local`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"aparatos":[
                    {"id":"este-record-id","kind":"phone","nombre":"Mi celu","ultimoUso":"2026-08-12T10:00:00Z"},
                    {"id":"otro","kind":"tv","nombre":"TV living","ultimoUso":"2026-08-11T09:00:00Z"}
                ]}""",
            ),
        )
        val v = vm()

        v.cargar()

        val aparatos = v.estado.value.aparatosOFail()
        assertTrue(aparatos.first { it.id == "este-record-id" }.esEsteAparato)
        assertFalse(aparatos.first { it.id == "otro" }.esEsteAparato)
    }

    @Test
    fun `sin recordId local (bootstrap sin terminar) ningun aparato se marca como este`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"aparatos":[{"id":"d1","kind":"phone","nombre":"X","ultimoUso":""}]}"""),
        )
        val v = vm(recordId = null)

        v.cargar()

        assertFalse(v.estado.value.aparatosOFail()[0].esEsteAparato)
    }

    // --- sacar pide confirmacion: sin confirmar, NINGUN pedido HTTP sale --------------------

    @Test
    fun `pedirSacar sin confirmar no manda ningun pedido HTTP`() {
        val v = vm()
        val aparato = AparatoUi("d1", "phone", "X", "", esEsteAparato = false)

        v.pedirSacar(aparato)

        assertEquals(aparato, v.pendienteDeSacar.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelarSacar descarta el pendiente sin pedir nada a la red`() {
        val v = vm()
        v.pedirSacar(AparatoUi("d1", "phone", "X", "", esEsteAparato = false))

        v.cancelarSacar()

        assertNull(v.pendienteDeSacar.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `confirmarSacar sin nada pendiente no hace nada`() = runBlocking {
        val v = vm()

        v.confirmarSacar()

        assertEquals(0, server.requestCount)
        assertNull(v.sacandoId.value)
    }

    // --- sacar un aparato AJENO: refresca la lista, no toca la sesion propia ----------------

    @Test
    fun `confirmarSacar un aparato ajeno refresca la lista y no cierra la sesion`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"aparatos":[{"id":"d1","kind":"phone","nombre":"X","ultimoUso":""}]}"""),
        ) // cargar() inicial
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE
        server.enqueue(MockResponse().setBody("""{"aparatos":[]}""")) // refresco tras sacar

        val v = vm()
        v.cargar()
        v.pedirSacar(v.estado.value.aparatosOFail()[0])

        v.confirmarSacar()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        assertTrue(v.estado.value.aparatosOFail().isEmpty())
        assertNull(v.pendienteDeSacar.value)
        assertNull(v.sacandoId.value)
    }

    // --- sacar ESTE aparato: cierra la sesion aca mismo, sin esperar un rechazo del gateway --

    @Test
    fun `confirmarSacar este aparato cierra la sesion sin esperar un 401`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"aparatos":[{"id":"este-record-id","kind":"phone","nombre":"Mi celu","ultimoUso":""}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE

        val v = vm()
        v.cargar()
        v.pedirSacar(v.estado.value.aparatosOFail()[0])

        v.confirmarSacar()

        assertEquals(
            "sacar el propio aparato tiene que cerrar la sesion ya mismo",
            EstadoDeSesion.Sin,
            sesion.estado.value,
        )
        // GET inicial + DELETE == 2: sacar el propio aparato NO dispara un refresco (cargar()
        // volvería a pedir con la sesion ya cerrada, que ya no tiene sentido).
        assertEquals(2, server.requestCount)
    }

    // --- aparato_no_encontrado (404): no rompe la pantalla, refresca y sigue ---------------

    @Test
    fun `confirmarSacar con 404 aparato_no_encontrado no rompe, refresca la lista`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"aparatos":[{"id":"d1","kind":"phone","nombre":"X","ultimoUso":""}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"detail":{"codigo":"aparato_no_encontrado","mensaje":"no existe"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"aparatos":[]}""")) // refresco

        val v = vm()
        v.cargar()
        v.pedirSacar(v.estado.value.aparatosOFail()[0])

        v.confirmarSacar() // no debe lanzar

        assertTrue(v.estado.value.aparatosOFail().isEmpty())
        assertNull("un 404 al sacar no es un error que haya que avisar", v.avisoError.value)
    }

    // --- backend_no_disponible: NUNCA cierra la sesion (el test que mas importa) -----------

    @Test
    fun `backend_no_disponible al cargar no cierra la sesion, solo pasa a Error`() = runBlocking {
        val urlMuerta = server.url("/").toString().trimEnd('/')
        server.shutdown() // a partir de aca, cualquier pedido revienta con IOException (sin red)
        val v = MisAparatosViewModel(
            CuentaApi(
                baseUrl = { urlMuerta }, deviceToken = { "device-tok" },
                sesion = sesion, http = OkHttpClient(),
            ),
            sesion,
        ) { "este-record-id" }

        v.cargar()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        assertTrue(v.estado.value is EstadoMisAparatos.Error)
    }

    @Test
    fun `backend_no_disponible al sacar no cierra la sesion, solo avisa el error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"aparatos":[{"id":"d1","kind":"phone","nombre":"X","ultimoUso":""}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":{"codigo":"backend_no_disponible","mensaje":"caido"}}"""),
        )
        val v = vm()
        v.cargar()
        v.pedirSacar(v.estado.value.aparatosOFail()[0])

        v.confirmarSacar()

        assertEquals("un backend caido NO puede cerrar la sesion", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        assertEquals("caido", v.avisoError.value)
        assertNull(v.sacandoId.value)
    }

    // --- sesion_invalida / licencia_no_vigente / identidad_invalida: SI cierran la sesion --

    @Test
    fun `sesion_invalida al sacar cierra la sesion`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"aparatos":[{"id":"d1","kind":"phone","nombre":"X","ultimoUso":""}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"codigo":"sesion_invalida","mensaje":"token vencido"}}"""),
        )
        val v = vm()
        v.cargar()
        v.pedirSacar(v.estado.value.aparatosOFail()[0])

        v.confirmarSacar()

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        assertEquals("token vencido", v.avisoError.value)
    }

    @Test
    fun `licencia_no_vigente al listar cierra la sesion`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"licencia_no_vigente","mensaje":"licencia revocada"}}"""),
        )
        val v = vm()

        v.cargar()

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        assertTrue(v.estado.value is EstadoMisAparatos.Error)
    }

    // --- copia de la confirmacion: funciones puras, sin Compose -----------------------------

    @Test
    fun `mensaje de confirmacion para este aparato avisa que cierra la sesion aca`() {
        val aparato = AparatoUi("este-record-id", "phone", "Mi celu", "", esEsteAparato = true)

        val msg = mensajeDeConfirmacion(aparato, esElUltimo = false)

        assertTrue(msg.contains("sesión") || msg.contains("sesion"))
        assertTrue(msg.contains("volver a entrar"))
    }

    @Test
    fun `mensaje de confirmacion para un aparato ajeno no habla de cerrar la sesion propia`() {
        val aparato = AparatoUi("otro", "tv", "TV living", "", esEsteAparato = false)

        val msg = mensajeDeConfirmacion(aparato, esElUltimo = false)

        assertFalse(msg.contains("volver a entrar"))
        assertTrue(msg.contains("TV living"))
    }

    @Test
    fun `mensaje de confirmacion del ultimo aparato no da a entender que se pierde la cuenta`() {
        val aparato = AparatoUi("este-record-id", "phone", "Mi celu", "", esEsteAparato = true)

        val msg = mensajeDeConfirmacion(aparato, esElUltimo = true)

        assertTrue(msg.contains("volver a entrar"))
        assertTrue("no debe sonar a que la cuenta se pierde", !msg.contains("perdés la cuenta"))
    }

    // --- render tolerante a nombre/ultimoUso vacios o nulos (el gateway los manda "") ------

    @Test
    fun `nombreParaMostrar usa un generico por tipo cuando el nombre viene vacio`() {
        assertEquals("Celular", nombreParaMostrar(AparatoUi("d1", "phone", "", "", esEsteAparato = false)))
        assertEquals("TV", nombreParaMostrar(AparatoUi("d1", "tv", "", "", esEsteAparato = false)))
    }

    @Test
    fun `nombreParaMostrar usa el nombre real cuando vino`() {
        assertEquals("Pixel 8", nombreParaMostrar(AparatoUi("d1", "phone", "Pixel 8", "", esEsteAparato = false)))
    }

    @Test
    fun `ultimoUsoParaMostrar vacio o invalido no rompe, devuelve string vacio`() {
        assertEquals("", ultimoUsoParaMostrar(""))
        assertEquals("", ultimoUsoParaMostrar("no-es-una-fecha"))
    }

    @Test
    fun `ultimoUsoParaMostrar formatea un instante ISO-8601 valido`() {
        assertTrue(ultimoUsoParaMostrar("2026-08-12T10:00:00Z").isNotBlank())
    }

    @Test
    fun `etiquetaDeTipo tolera un kind que la app todavia no conoce`() {
        assertEquals("Aparato", etiquetaDeTipo("smartwatch"))
    }
}
