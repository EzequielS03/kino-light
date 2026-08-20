package com.arkiv.player.data.marcadores

import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.ArkivApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BuscadorDeMarcadoresTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeSkipMarkerDao
    private lateinit var buscador: BuscadorDeMarcadores

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val gateway = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
        )
        dao = FakeSkipMarkerDao()
        buscador = BuscadorDeMarcadores(dao = dao, gateway = gateway, clock = { 555L })
    }

    @After
    fun tearDown() = server.shutdown()

    /**
     * El requisito que no está en ningún test del gateway: lo que trae AniSkip se guarda como
     * AUTO, nunca como MANUAL. `MarcadorDeCapitulo.elegir` hace que lo manual le gane siempre a lo
     * automático -- si esto se guardara como manual, un tiempo malo del gateway ya no se podría
     * corregir a mano nunca.
     */
    @Test fun lo_que_trae_el_gateway_se_guarda_como_automatico() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"openingStartMs":0,"openingEndMs":90000,"endingStartMs":1319000}"""),
        )
        buscador.asegurar("item1", "item1::e1", tmdbId = 42, temporada = 1, episodio = 1)
        val guardado = dao.getById(MarcadorDeCapitulo.idDe("item1", "item1::e1"))
        assertEquals(MarcadorDeCapitulo.ORIGEN_AUTO, guardado!!.origen)
        assertEquals(90_000L, guardado.openingEndMs)
        assertEquals(1_319_000L, guardado.endingStartMs)
    }

    @Test fun no_pregunta_de_nuevo_si_ya_hay_fila_para_ese_capitulo() = runBlocking {
        val id = MarcadorDeCapitulo.idDe("item1", "item1::e1")
        dao.upsert(
            SkipMarkerEntity(
                id = id, itemId = "item1", episodeId = "item1::e1",
                openingStartMs = null, openingEndMs = null, endingStartMs = null,
            ),
        )
        buscador.asegurar("item1", "item1::e1", tmdbId = 42, temporada = 1, episodio = 1)
        assertEquals(0, server.requestCount)
    }

    @Test fun un_objeto_vacio_del_gateway_no_guarda_nada() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        buscador.asegurar("item1", "item1::e1", tmdbId = 42, temporada = 1, episodio = 1)
        assertNull(dao.getById(MarcadorDeCapitulo.idDe("item1", "item1::e1")))
    }

    @Test fun un_500_del_gateway_no_tira() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        buscador.asegurar("item1", "item1::e1", tmdbId = 42, temporada = 1, episodio = 1)
        assertNull(dao.getById(MarcadorDeCapitulo.idDe("item1", "item1::e1")))
    }

    @Test fun sin_tmdbId_no_pega_al_gateway() = runBlocking {
        buscador.asegurar("item1", "item1::e1", tmdbId = 0, temporada = 1, episodio = 1)
        assertEquals(0, server.requestCount)
    }

    @Test fun pega_al_get_correcto() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        buscador.asegurar("item1", "item1::e1", tmdbId = 42, temporada = 2, episodio = 5)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/marcadores?tmdbId=42&temporada=2&episodio=5", req.path)
    }
}
