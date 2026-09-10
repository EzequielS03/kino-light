package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituCatalogoTest {

    private val TRAY = "TRAY/SEARCH/VOD"

    @Test fun `el catalogo se pide con query vacio`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).catalogo()

        val (path, params) = fake.llamadas.first()
        assertEquals(TRAY, path)
        assertEquals("", params["query"])
    }

    @Test fun `la busqueda manda el texto`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).buscar("rigo")

        assertEquals("rigo", fake.llamadas.first().second["query"])
    }

    @Test fun `se quedan series y peliculas, y nada mas`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Serie A","contentType":"BUNDLE","pictureUrl":"pa"}},
          {"id":"2","metadata":{"title":"Grupo B","contentType":"GROUP_OF_BUNDLES","pictureUrl":"pb"}},
          {"id":"3","metadata":{"title":"Peli C","contentType":"VOD","contentSubtype":"MOVIE","pictureUrl":"pc"}},
          {"id":"4","metadata":{"title":"Clip D","contentType":"VOD","contentSubtype":"CLIP"}},
          {"id":"5","metadata":{"title":"Vivo E","contentType":"LIVE"}}
        ]}}
        """)

        val items = DituCatalogo(fake).catalogo()

        assertEquals(listOf("1", "2", "3"), items.map { it.contentId })
        assertEquals(listOf("BUNDLE", "GROUP_OF_BUNDLES", "VOD"), items.map { it.contentType })
        assertEquals(listOf(false, false, true), items.map { it.esPelicula })
    }

    @Test fun `sin id o sin titulo el item se descarta`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"metadata":{"title":"Sin id","contentType":"BUNDLE"}},
          {"id":"9","metadata":{"title":"","contentType":"BUNDLE"}},
          {"id":"10","metadata":{"title":"Buena","contentType":"BUNDLE"}}
        ]}}
        """)

        assertEquals(listOf("10"), DituCatalogo(fake).catalogo().map { it.contentId })
    }

    /** El póster se arma a mano contra el CDN propio de Caracol desde `pictureUrl`. */
    @Test fun `el poster sale del CDN de Caracol`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","pictureUrl":"carpeta/img"}}
        ]}}
        """)

        assertEquals(
            "https://image-registry.ditu.caracoltv.com/carpeta/img/portrait-thin-promotional-tablet.jpg",
            DituCatalogo(fake).catalogo().first().posterUrl,
        )
    }

    /** Sin `pictureUrl` se cae al posterList del propio container antes que quedarse sin imagen. */
    @Test fun `sin pictureUrl usa el posterList`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE"},
           "posterList":[{"fileType":"otro","fileUrl":"https://x/no.jpg"},
                         {"fileType":"icon","fileUrl":"https://x/si.jpg"}]}
        ]}}
        """)

        assertEquals("https://x/si.jpg", DituCatalogo(fake).catalogo().first().posterUrl)
    }

    @Test fun `el anio sale del primer campo de fecha que tenga cuatro digitos`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","releaseDate":"2019-04-02"}},
          {"id":"2","metadata":{"title":"B","contentType":"BUNDLE","releaseYear":"2021"}},
          {"id":"3","metadata":{"title":"C","contentType":"BUNDLE","releaseDate":"nada"}}
        ]}}
        """)

        assertEquals(listOf("2019", "2021", ""), DituCatalogo(fake).catalogo().map { it.anio })
    }

    // --- canales en vivo -------------------------------------------------------

    @Test fun `los canales se piden ordenados por orderId`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).canales()

        val (path, params) = fake.llamadas.first()
        assertEquals("TRAY/LIVECHANNELS", path)
        assertEquals("orderId", params["orderBy"])
        assertEquals("asc", params["sortOrder"])
    }

    /**
     * El assetId sale de ESTA respuesta y no del EPG: el EPG devuelve `assets` vacío para el
     * programa en curso, así que pedirlo ahí es un viaje que vuelve sin nada.
     */
    @Test fun `de cada canal salen id, nombre, logo y assetId del MASTER`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true,"orderId":1},
           "assets":[{"assetType":"OTRO","assetId":11,"logoSmall":"s.png"},
                     {"assetType":"MASTER","assetId":22,"logoMedium":"m.png"}]}
        ]}}
        """)

        val canal = DituCatalogo(fake).canales().single()
        assertEquals(7, canal.channelId)
        assertEquals("Caracol", canal.nombre)
        assertEquals(22, canal.assetId)
        assertEquals("m.png", canal.logoUrl)
    }

    @Test fun `sin MASTER se usa el primer asset con id`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true},
           "assets":[{"assetType":"OTRO","assetId":11}]}
        ]}}
        """)

        assertEquals(11, DituCatalogo(fake).canales().single().assetId)
    }

    @Test fun `los canales inactivos o sin nombre no entran`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":1,"channelName":"Apagado","isActive":false},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":2,"channelName":"","isActive":true},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":3,"channelName":"Bueno","isActive":true},"assets":[{"assetId":9}]}
        ]}}
        """)

        assertEquals(listOf(3), DituCatalogo(fake).canales().map { it.channelId })
    }

    /** Un canal sin ningún assetId no se puede reproducir: no tiene sentido ofrecerlo. */
    @Test fun `un canal sin assetId no entra`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":4,"channelName":"Sin asset","isActive":true},"assets":[]}
        ]}}
        """)

        assertTrue(DituCatalogo(fake).canales().isEmpty())
    }
}
