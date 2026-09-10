package com.arkiv.player.data.ditu

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DituFuenteTest {

    private fun fuente(fake: FakeDituCliente, tmdb: com.arkiv.player.data.catalog.TmdbApi? = null) =
        DituFuente(DituCatalogo(fake), DituEpisodios(fake), DituResolve(fake), tmdb)

    @Test fun `reconoce sus refs y no los ajenos`() {
        val f = fuente(FakeDituCliente())
        assertTrue(f.reconoce("ditu1:VOD:42"))
        assertFalse(f.reconoce("magis1:movie:0:C42"))
        assertFalse(f.reconoce(""))
    }

    @Test fun `la busqueda emite arranque, resultados y fin`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(DituCatalogo.TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Rigo","contentType":"BUNDLE","pictureUrl":"p"}},
          {"id":"2","metadata":{"title":"Peli","contentType":"VOD","contentSubtype":"MOVIE"}}
        ]}}
        """)

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "rigo")).toList()

        assertTrue(eventos.first() is SearchEvent.SourceStart)
        val resultados = eventos.filterIsInstance<SearchEvent.ResultEvent>()
        assertEquals(listOf("Rigo", "Peli"), resultados.map { it.item.title })
        assertEquals(listOf("series", "movie"), resultados.map { it.item.kind })
        assertEquals(listOf("ditu1:BUNDLE:1", "ditu1:VOD:2"), resultados.map { it.item.ref })
        assertEquals("ditu", resultados.first().item.source)
        assertTrue(eventos.any { it is SearchEvent.SourceDone })
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    /** Una fuente que se cae emite su error y termina: no puede dejar el Flow colgado. */
    @Test fun `si Caracol falla la busqueda emite SourceError y Done`() = runTest {
        val fake = FakeDituCliente()
        fake.falla = DituException("Caracol no responde")

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "x")).toList()

        val error = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("ditu", error.source)
        assertTrue(error.error.contains("Caracol"))
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    @Test fun `resolve traduce el ref y devuelve el playable con DRM`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/x.mpd"}}""")
        fake.token = "t1"

        val play = fuente(fake).resolve("ditu1:VOD:42")

        assertEquals("https://cdn/x.mpd", play.url)
        assertTrue(play.drmLicenseUrl.endsWith("/CONTENT/LICENSE"))
        assertEquals("playback_token=t1", play.drmLicenseHeaders["Cookie"])
    }

    @Test fun `un ref que no es de Caracol es un GatewayException`() = runTest {
        val e = runCatching { fuente(FakeDituCliente()).resolve("magis1:movie:0:C1") }.exceptionOrNull()
        assertTrue(e is com.arkiv.player.data.gateway.GatewayException)
    }

    @Test fun `los capitulos llegan con su ref y la serie con sus imagenes`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)

        val (eps, serie) = fuente(fake).episodesConSerie("ditu1:BUNDLE:99")

        assertEquals(1, eps.single().number)
        assertEquals("Uno", eps.single().title)
        assertEquals("ditu1:VOD:e1", eps.single().ref)
        assertEquals("Rigo", serie!!.titulo)
        assertTrue(serie.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        assertTrue(serie.backdropUrl.endsWith("landscape-regular-clean-tablet.jpg"))
        // Sin TMDB cableado no hay id: el bloque igual viaja, con lo que Caracol sí sabe.
        assertEquals(0, serie.tmdbId)
    }

    /**
     * En un GROUP_OF_BUNDLES cada temporada puede traer su propio capítulo 1. La temporada tiene que
     * viajar en cada capítulo: `GatewaySerie.seasonNumber` es una sola para toda la lista, y sin
     * esto quien guarde en la biblioteca no puede distinguir el 1 de la T1 del 1 de la T2.
     */
    @Test fun `en un grupo cada capitulo trae su temporada`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(DituCatalogo.TRAY, """{"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}""")
        for ((bundle, cap) in listOf("b1" to "e1", "b2" to "e2")) {
            fake.responde("CONTENT/DETAIL/BUNDLE/$bundle", """
            {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
              {"id":"$cap","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
               "assets":[{"assetType":"MASTER","assetId":1}]}
            ]}]}}
            """)
        }

        val (eps, _) = fuente(fake).episodesConSerie("ditu1:GROUP_OF_BUNDLES:g9")

        assertEquals(listOf(1, 1), eps.map { it.number })
        assertEquals(listOf(1, 2), eps.map { it.season })
        assertEquals(listOf("ditu1:VOD:e1", "ditu1:VOD:e2"), eps.map { it.ref })
    }

    /**
     * `TmdbApi` con un servidor real que se cae ANTES de la llamada (mismo patrón que
     * `MagisFuenteTest."si TMDB se cae, los capitulos salen igual"`): a diferencia de `tmdb = null`,
     * acá sí se intenta cruzar contra TMDB y la llamada falla de verdad — es la garantía central de
     * `episodesConSerie` (que un TMDB caído no cueste los capítulos) y hasta ahora ningún test la
     * ejercitaba, porque todos usaban `tmdb = null`.
     */
    @Test fun `si TMDB se cae, los capitulos salen igual`() = runTest {
        val servidor = MockWebServer().also { it.start() }
        val tmdb = TmdbApi(apiKey = "x", baseUrl = servidor.url("/3").toString().trimEnd('/'), client = OkHttpClient())
        servidor.shutdown()

        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)

        val (eps, serie) = fuente(fake, tmdb).episodesConSerie("ditu1:BUNDLE:99")

        assertEquals(1, eps.size)
        assertEquals("Uno", eps.single().title)
        assertEquals("Rigo", serie!!.titulo)
        assertTrue(serie.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        // TMDB caído no aporta id: el título y las imágenes son los de Caracol, que sí respondió.
        assertEquals(0, serie.tmdbId)
    }
}
