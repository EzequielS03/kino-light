package com.arkiv.player.data.gateway

import com.arkiv.player.pocketbase.EstadoDeSesion
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [InterceptorDeSesion]: el punto único que le faltaba a los clientes de contenido (brief de la
 * Task 7b) para reaccionar a un rechazo de identidad real, reusando -no reinventando- la regla que
 * ya prueban `EntradaViewModelTest`/`MisAparatosViewModelTest`.
 *
 * Los tests que más importan acá son los que verifican que el interceptor se QUEDA QUIETO -sin red,
 * timeout, 503, `/v1/cuenta/registrar`, un host que no es el gateway-: confundir cualquiera de esos
 * con un rechazo de identidad real ya costó tres rondas de corrección en este proyecto (ver el
 * brief). Por eso cada uno arranca con una sesión YA guardada: lo que se prueba es que sigue viva
 * al final, no que nunca lo estuvo.
 */
class InterceptorDeSesionTest {

    private lateinit var gateway: MockWebServer
    private lateinit var otroHost: MockWebServer
    private lateinit var store: FakeDeviceStore

    @Before
    fun setUp() {
        gateway = MockWebServer().also { it.start() }
        otroHost = MockWebServer().also { it.start() }
        store = FakeDeviceStore()
    }

    @After
    fun tearDown() {
        runCatching { gateway.shutdown() }
        runCatching { otroHost.shutdown() }
    }

    /** Sesión con token+email ya guardados, para distinguir "el interceptor la cerró" de "nunca
     *  hubo nada que cerrar". `SesionDePersona` lee el store UNA vez al construirse, así que hay
     *  que guardar ANTES de instanciarla. */
    private fun conSesionGuardada(): SesionDePersona {
        store.savePersonToken("ptok")
        store.savePersonEmail("a@b.co")
        return SesionDePersona(PocketBaseClient(baseUrl = "http://unused.invalid"), store)
    }

    private fun clienteConInterceptor(
        sesion: SesionDePersona,
        gatewayUrl: String = gateway.url("/").toString().trimEnd('/'),
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(InterceptorDeSesion(gatewayUrl = { gatewayUrl }, sesion = sesion))
            .build()

    private fun pedir(client: OkHttpClient, server: MockWebServer, path: String): okhttp3.Response =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    // --- Rechazo de identidad REAL: cierra ------------------------------------------------------

    @Test
    fun `401 sesion_invalida del gateway cierra la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"codigo":"sesion_invalida","mensaje":"el token ya no vale"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/search").close()

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        assertEquals(null, store.personToken())
    }

    @Test
    fun `403 licencia_no_vigente del gateway cierra la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"licencia_no_vigente","mensaje":"licencia revocada"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/resolve").close()

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
    }

    @Test
    fun `403 identidad_invalida del gateway cierra la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"identidad_invalida","mensaje":"datos corruptos"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/live/categories").close()

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
    }

    // --- Fallo de TRANSPORTE: nunca cierra (la regla que ya se rompió tres veces) ----------------

    @Test
    fun `503 backend_no_disponible del gateway NO cierra la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":{"codigo":"backend_no_disponible","mensaje":"caido"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/search").close()

        assertEquals("un 503 no es un rechazo de identidad", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    @Test
    fun `sin red (host inalcanzable) NO cierra la sesion`() {
        val sesion = conSesionGuardada()
        val urlMuerta = gateway.url("/").toString().trimEnd('/')
        gateway.shutdown() // desde acá cualquier pedido revienta con IOException (sin red)

        try {
            pedir(clienteConInterceptor(sesion, gatewayUrl = urlMuerta), gateway, "/v1/search")
            fail("se esperaba IOException")
        } catch (e: IOException) {
            // esperado: el interceptor no atrapa fallos de transporte, solo reacciona a la Response.
        }

        assertEquals("sin red no es un rechazo de identidad", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    @Test
    fun `timeout del gateway NO cierra la sesion`() {
        val sesion = conSesionGuardada()
        // NO_RESPONSE: el server acepta la conexión y nunca contesta -el cliente debe agotar su
        // propio timeout esperando, que es justo lo que se quiere reproducir acá.
        gateway.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient.Builder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .addInterceptor(
                InterceptorDeSesion(
                    gatewayUrl = { gateway.url("/").toString().trimEnd('/') },
                    sesion = sesion,
                ),
            )
            .build()

        try {
            client.newCall(Request.Builder().url(gateway.url("/v1/search")).build()).execute()
            fail("se esperaba timeout")
        } catch (e: IOException) {
            // esperado
        }

        assertEquals("un timeout no es un rechazo de identidad", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    // --- Las dos trampas del brief: 401 que NO debe dispararlo -----------------------------------

    @Test
    fun `401 de _v1_cuenta_registrar NO toca la sesion -- ahi todavia no hay ninguna`() {
        val sesion = conSesionGuardada()
        // El cuerpo dice `sesion_invalida` A PROPOSITO -no `sin_device`-: con un codigo que el
        // interceptor ignora igual, este test pasaria aunque se borrara el guard de la ruta, y no
        // estaria probando nada. Asi, lo unico que salva la sesion es la exclusion de /registrar.
        gateway.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"codigo":"sesion_invalida","mensaje":"volve a entrar"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/cuenta/registrar").close()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    @Test
    fun `401 de un host que no es el gateway NO toca la sesion`() {
        val sesion = conSesionGuardada()
        // gatewayUrl apunta a `gateway`, pero el pedido real sale hacia `otroHost` (p.ej.
        // archive.org, un tracker, un host de video): el interceptor debe ignorarlo por completo.
        // Mismo cuidado que arriba: el cuerpo es uno que SI cerraria la sesion, asi lo unico que
        // la salva es el filtro de host. Con un "Unauthorized" pelado este test pasaba igual sin
        // el filtro -no probaba nada-.
        otroHost.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"codigo":"sesion_invalida","mensaje":"volve a entrar"}}"""),
        )

        pedir(clienteConInterceptor(sesion), otroHost, "/algo").close()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    // --- El resto: nada que ver con un rechazo de identidad ---------------------------------------

    @Test
    fun `200 normal no toca la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(MockResponse().setBody("""{"ok":true}"""))

        pedir(clienteConInterceptor(sesion), gateway, "/v1/search").close()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    @Test
    fun `otro codigo 403 del gateway (tope_alcanzado) no cierra la sesion`() {
        val sesion = conSesionGuardada()
        gateway.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"tope_alcanzado","mensaje":"sin cupo"}}"""),
        )

        pedir(clienteConInterceptor(sesion), gateway, "/v1/cuenta/aparatos").close()

        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
    }

    @Test
    fun `peekBody no consume el cuerpo -- el llamador real todavia puede leerlo entero`() {
        val sesion = conSesionGuardada()
        val cuerpo = """{"detail":{"codigo":"tope_alcanzado","mensaje":"sin cupo"}}"""
        gateway.enqueue(MockResponse().setResponseCode(403).setBody(cuerpo))

        val respuesta = pedir(clienteConInterceptor(sesion), gateway, "/v1/cuenta/aparatos")

        assertEquals(cuerpo, respuesta.body?.string())
        respuesta.close()
    }
}
