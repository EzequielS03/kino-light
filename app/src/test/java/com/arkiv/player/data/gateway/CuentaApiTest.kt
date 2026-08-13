package com.arkiv.player.data.gateway

import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CuentaApiTest {

    private lateinit var server: MockWebServer
    private lateinit var deviceStore: FakeDeviceStore
    private lateinit var sesion: SesionDePersona

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        deviceStore = FakeDeviceStore()
        // `SesionDePersona` no necesita hablarle nunca a este PocketBaseClient en estos tests: solo
        // se usa `.token()`, que lee directo del store. La URL es un relleno que jamás se llama.
        sesion = SesionDePersona(PocketBaseClient(baseUrl = "http://unused.invalid"), deviceStore)
    }

    @After
    fun tearDown() {
        // Un test apaga el server a mano para simular "sin red"; evitar el doble shutdown ruidoso.
        runCatching { server.shutdown() }
    }

    private fun cuentaApi(deviceTok: String? = "device-tok"): CuentaApi = CuentaApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        apiKey = { "LLAVE" },
        deviceToken = { deviceTok },
        sesion = sesion,
        http = OkHttpClient(),
    )

    // --- no mezclar el token del aparato con el de la persona (el agujero de suplantacion) -----

    @Test
    fun `registrar manda el token del APARATO en Authorization, no el de la persona`() = runBlocking {
        deviceStore.savePersonToken("person-tok")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"a1"}"""))

        cuentaApi(deviceTok = "device-tok").registrar("a@b.co", "secret12", "LIC-1")

        assertEquals("device-tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `adoptarAparato manda el token de la PERSONA en Authorization, no el del aparato`() = runBlocking {
        deviceStore.savePersonToken("person-tok")
        server.enqueue(MockResponse().setBody("""{"kind":"phone","usados":1,"tope":2,"yaEra":false}"""))

        cuentaApi(deviceTok = "device-tok-que-no-deberia-viajar").adoptarAparato("otro-device-tok")

        assertEquals("person-tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `listarAparatos manda el token de la persona`() = runBlocking {
        deviceStore.savePersonToken("person-tok")
        server.enqueue(MockResponse().setBody("""{"aparatos":[]}"""))

        cuentaApi().listarAparatos()

        assertEquals("person-tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sacarAparato manda el token de la persona y pega al id correcto`() = runBlocking {
        deviceStore.savePersonToken("person-tok")
        server.enqueue(MockResponse().setResponseCode(204))

        cuentaApi().sacarAparato("dev-9")

        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.endsWith("/aparatos/dev-9"))
    }

    @Test
    fun `todos los pedidos mandan la llave X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"a1"}"""))

        cuentaApi().registrar("a@b.co", "secret12", "LIC-1")

        assertEquals("LLAVE", server.takeRequest().getHeader("X-Arkiv-Key"))
    }

    // --- caminos felices: parseo de cada respuesta -----------------------------------------

    @Test
    fun `registrar parsea userId y accountId, y manda email password licencia en el body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"a1"}"""))

        val r = cuentaApi().registrar("a@b.co", "secret12", "LIC-1")

        assertEquals("u1", r.userId)
        assertEquals("a1", r.accountId)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("a@b.co", body.getString("email"))
        assertEquals("secret12", body.getString("password"))
        assertEquals("LIC-1", body.getString("licencia"))
    }

    @Test
    fun `adoptarAparato parsea kind usados tope y yaEra, y manda deviceToken en el body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"kind":"phone","usados":1,"tope":2,"yaEra":true}"""))

        val a = cuentaApi().adoptarAparato("dev-1")

        assertEquals("phone", a.kind)
        assertEquals(1, a.usados)
        assertEquals(2, a.tope)
        assertEquals(true, a.yaEra)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("dev-1", body.getString("deviceToken"))
    }

    @Test
    fun `listarAparatos parsea la lista de aparatos`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"aparatos":[{"id":"d1","kind":"phone","nombre":"Pixel","ultimoUso":"2026-08-12"}]}""",
            ),
        )

        val lista = cuentaApi().listarAparatos()

        assertEquals(1, lista.size)
        assertEquals("d1", lista[0].id)
        assertEquals("phone", lista[0].kind)
        assertEquals("Pixel", lista[0].nombre)
        assertEquals("2026-08-12", lista[0].ultimoUso)
    }

    @Test
    fun `sacarAparato en 204 sin cuerpo no lanza`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        cuentaApi().sacarAparato("dev-1")   // no debe lanzar
    }

    // --- la tabla completa del contrato: cada codigo mapea a SU rama, no a otra ------------

    @Test
    fun `cada codigo del contrato mapea a su rama, con su propio codigo y mensaje`() = runBlocking {
        val tabla: List<Triple<Int, String, KClass<out ErrorDeCuenta>>> = listOf(
            Triple(401, "sesion_invalida", ErrorDeCuenta.SesionInvalida::class),
            Triple(403, "licencia_no_vigente", ErrorDeCuenta.LicenciaNoVigente::class),
            Triple(403, "identidad_invalida", ErrorDeCuenta.IdentidadInvalida::class),
            Triple(503, "backend_no_disponible", ErrorDeCuenta.BackendNoDisponible::class),
            Triple(400, "licencia_invalida", ErrorDeCuenta.LicenciaInvalida::class),
            Triple(409, "email_en_uso", ErrorDeCuenta.EmailEnUso::class),
            Triple(400, "datos_invalidos", ErrorDeCuenta.DatosInvalidos::class),
            Triple(401, "sin_device", ErrorDeCuenta.SinDevice::class),
            Triple(409, "device_ya_registrado", ErrorDeCuenta.DeviceYaRegistrado::class),
            Triple(403, "tope_alcanzado", ErrorDeCuenta.TopeAlcanzado::class),
            Triple(403, "aparato_de_otra_cuenta", ErrorDeCuenta.AparatoDeOtraCuenta::class),
            Triple(404, "aparato_no_encontrado", ErrorDeCuenta.AparatoNoEncontrado::class),
            Triple(400, "tipo_invalido", ErrorDeCuenta.TipoInvalido::class),
            Triple(409, "candado_ocupado", ErrorDeCuenta.CandadoOcupado::class),
        )

        for ((http, codigo, esperado) in tabla) {
            server.enqueue(
                MockResponse().setResponseCode(http)
                    .setBody("""{"detail":{"codigo":"$codigo","mensaje":"boom $codigo"}}"""),
            )
            try {
                cuentaApi().listarAparatos()
                fail("$codigo (HTTP $http) debia lanzar ErrorDeCuenta")
            } catch (e: ErrorDeCuenta) {
                assertTrue(
                    "$codigo debia mapear a ${esperado.simpleName}, dio ${e::class.simpleName}",
                    esperado.isInstance(e),
                )
                assertEquals(codigo, e.codigo)
                assertEquals("boom $codigo", e.mensaje)
            }
        }
    }

    // --- la red de seguridad: Desconocido para todo lo que el contrato no previo -----------

    @Test
    fun `un codigo que la app no conoce cae en Desconocido, no explota`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"detail":{"codigo":"algo_nuevo_del_deploy","mensaje":"texto nuevo"}}"""),
        )

        try {
            cuentaApi().listarAparatos()
            fail("se esperaba ErrorDeCuenta")
        } catch (e: ErrorDeCuenta) {
            assertTrue(e is ErrorDeCuenta.Desconocido)
            assertEquals("algo_nuevo_del_deploy", e.codigo)
            assertEquals("texto nuevo", e.mensaje)
        }
    }

    @Test
    fun `un detail que no es el objeto codigo-mensaje cae en Desconocido, NO en sesion_invalida`() = runBlocking {
        // Lo que FastAPI manda por default (antes de pasar por el manejador de errores propio del
        // gateway): `detail` es un STRING ("Not authenticated"), no el objeto {codigo, mensaje} del
        // contrato. Interpretar esto como sesion_invalida cerraria la sesion sobre una respuesta
        // que en realidad no dice nada sobre si el token vale o no.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Not authenticated"}"""))

        try {
            cuentaApi().listarAparatos()
            fail("se esperaba ErrorDeCuenta")
        } catch (e: ErrorDeCuenta) {
            assertTrue(e is ErrorDeCuenta.Desconocido)
        }
    }

    @Test
    fun `un cuerpo que no es JSON (proxy en el medio) cae en Desconocido`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>502 Bad Gateway</html>"))

        try {
            cuentaApi().listarAparatos()
            fail("se esperaba ErrorDeCuenta")
        } catch (e: ErrorDeCuenta) {
            assertTrue(e is ErrorDeCuenta.Desconocido)
        }
    }

    @Test
    fun `sin red (backend inalcanzable) cae en BackendNoDisponible, no en Desconocido`() = runBlocking {
        val urlMuerta = server.url("/").toString().trimEnd('/')
        server.shutdown()   // a partir de aca, cualquier pedido revienta con IOException (sin red)
        val api = CuentaApi(
            baseUrl = { urlMuerta },
            apiKey = { "LLAVE" },
            deviceToken = { "device-tok" },
            sesion = sesion,
            http = OkHttpClient(),
        )

        try {
            api.listarAparatos()
            fail("se esperaba ErrorDeCuenta")
        } catch (e: ErrorDeCuenta) {
            assertTrue(e is ErrorDeCuenta.BackendNoDisponible)
        }
    }

    @Test
    fun `un 503 con el codigo del contrato cae en BackendNoDisponible`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":{"codigo":"backend_no_disponible","mensaje":"caido"}}"""),
        )

        try {
            cuentaApi().listarAparatos()
            fail("se esperaba ErrorDeCuenta")
        } catch (e: ErrorDeCuenta) {
            assertTrue(e is ErrorDeCuenta.BackendNoDisponible)
        }
    }
}
