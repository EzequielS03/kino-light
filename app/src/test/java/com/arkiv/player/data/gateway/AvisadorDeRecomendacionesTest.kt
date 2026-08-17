package com.arkiv.player.data.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * El punto de esta clase es UNO SOLO: `avisar()` nunca puede tirar, pase lo que pase con el
 * gateway. Es la garantía que hace seguro llamarla desde `ArkivRepository.savePlayback`/
 * `setWatched` DESPUÉS de guardar el progreso -- un fallo acá no puede deshacer ni bloquear un
 * guardado que ya terminó (ver el doc de [AvisadorDeRecomendaciones] y el spec
 * `2026-08-16-recomendaciones-por-historial`, sección "Manejo de errores").
 */
class AvisadorDeRecomendacionesTest {

    private lateinit var server: MockWebServer
    private lateinit var avisador: AvisadorDeRecomendaciones

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val client = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
        )
        avisador = AvisadorDeRecomendaciones(client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `un 500 del gateway no tira`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        avisador.avisar() // si tirara, el test fallaría acá mismo
    }

    @Test
    fun `un 202 exitoso tampoco tira`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        avisador.avisar()
    }

    @Test
    fun `sin servidor (conexion rechazada) no tira`() = runBlocking {
        server.shutdown() // nadie escucha: la conexión falla, no hay respuesta que devolver
        avisador.avisar()
    }

    @Test
    fun `pega al POST correcto`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        avisador.avisar()
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/recomendaciones/refrescar", req.path)
    }
}
