package com.arkiv.player.crash

import com.arkiv.player.data.gateway.InterceptorDeSesion
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La app se echa sola: ante un 401/403 de identidad, [SesionDePersona] y [InterceptorDeSesion]
 * cierran la sesión. Está bien que lo hagan — pero desde afuera se ve como "la app dejó de
 * funcionar" sin ninguna explicación, y en una TV es terminal, porque ahí solo se entra por pareo.
 *
 * Es el sospechoso número uno de un usuario al que "le falla la app y no se sabe por qué", así que
 * cada expulsión tiene que dejar un reporte con el código y el motivo.
 */
class ReporteDeExpulsionTest {
    private lateinit var server: MockWebServer
    private val reportados = mutableListOf<Pair<Throwable, String>>()

    @Before
    fun arrancar() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun apagar() {
        server.shutdown()
    }

    private fun sesion(store: FakeDeviceStore = FakeDeviceStore()) = SesionDePersona(
        client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/')),
        store = store,
        reportar = { e, etiqueta -> reportados += e to etiqueta },
    )

    @Test
    fun `refrescar con 401 cierra la sesion y la reporta`() {
        val store = FakeDeviceStore().apply { savePersonToken("token-viejo") }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401}"""))

        val ok = runBlocking { sesion(store).refrescar() }

        assertEquals(false, ok)
        assertEquals(1, reportados.size)
        assertTrue(reportados.single().first.message!!, reportados.single().first.message!!.contains("401"))
    }

    /** Sin red no hay expulsión, así que tampoco hay nada que reportar: sería puro ruido. */
    @Test
    fun `un fallo de transporte no reporta nada`() {
        val store = FakeDeviceStore().apply { savePersonToken("token-vivo") }
        server.shutdown()

        runBlocking { sesion(store).refrescar() }

        assertTrue(reportados.toString(), reportados.isEmpty())
    }

    @Test
    fun `el interceptor reporta la expulsion con el cuerpo de la respuesta`() {
        val store = FakeDeviceStore().apply { savePersonToken("token-viejo") }
        val gateway = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"codigo":"sesion_invalida","mensaje":"el aparato ya no existe"}}"""),
        )
        val cliente = OkHttpClient.Builder()
            .addInterceptor(
                InterceptorDeSesion(
                    gatewayUrl = { gateway },
                    sesion = sesion(store),
                    reportar = { e, etiqueta -> reportados += e to etiqueta },
                ),
            )
            .build()

        cliente.newCall(Request.Builder().url("$gateway/v1/catalogo").build()).execute().close()

        assertEquals(1, reportados.size)
        val mensaje = reportados.single().first.message!!
        assertTrue(mensaje, mensaje.contains("401"))
        assertTrue(mensaje, mensaje.contains("sesion_invalida"))
    }

    /** Un 401 que NO es de cuenta (p. ej. de archive.org) no cierra sesión ni reporta. */
    @Test
    fun `un 401 ajeno al gateway no reporta nada`() {
        val gateway = "https://gateway.invalido"
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val cliente = OkHttpClient.Builder()
            .addInterceptor(
                InterceptorDeSesion(
                    gatewayUrl = { gateway },
                    sesion = sesion(),
                    reportar = { e, etiqueta -> reportados += e to etiqueta },
                ),
            )
            .build()

        cliente.newCall(Request.Builder().url(server.url("/algo")).build()).execute().close()

        assertTrue(reportados.toString(), reportados.isEmpty())
    }
}
