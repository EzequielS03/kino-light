package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituEpisodiosTest {

    private fun bundleCon(vararg eps: String) = """
    {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},
      "containers":[${eps.joinToString(",")}]}]}}
    """

    private fun ep(id: String, num: Int, temporada: Int?, titulo: String, asset: Int? = 1): String {
        val season = temporada?.let { ""","season":$it""" } ?: ""
        val assets = asset?.let { ""","assets":[{"assetType":"MASTER","assetId":$it}]""" } ?: ""
        return """{"id":"$id","metadata":{"episodeNumber":$num,"episodeTitle":"$titulo"$season}$assets}"""
    }

    @Test fun `un BUNDLE lista sus capitulos con su temporada`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(
            ep("e1", 1, 2, "Uno"),
            ep("e2", 2, 2, "Dos"),
        ))

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))

        assertEquals(listOf(1, 2), t.episodios.map { it.numero })
        assertEquals(listOf(2, 2), t.episodios.map { it.temporada })
        assertEquals(listOf("Uno", "Dos"), t.episodios.map { it.titulo })
        assertEquals("Rigo", t.tituloSerie)
        assertEquals(2, t.temporada)
    }

    @Test fun `sin season el capitulo es de la temporada 1`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 1, null, "Uno")))

        assertEquals(1, DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.single().temporada)
    }

    @Test fun `sin titulo el capitulo se llama por su numero`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 7, 1, "")))

        assertEquals("Episodio 7", DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.single().titulo)
    }

    /** Sin assetId no se puede reproducir: mostrarlo sería ofrecer algo que falla al tocarlo. */
    @Test fun `un capitulo sin assetId no entra`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(
            ep("e1", 1, 1, "Sin asset", asset = null),
            ep("e2", 2, 1, "Con asset"),
        ))

        assertEquals(listOf("e2"), DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.map { it.contentId })
    }

    /**
     * LA TRAMPA. En un grupo, la temporada de cada capítulo es la POSICIÓN de su bundle en la lista
     * de hijos, no el `season` que traiga el episodio: los bundles de un grupo suelen venir todos
     * con `season: 1` y sin esto las cuatro temporadas se pisarían entre sí.
     */
    @Test fun `en un grupo la temporada es la posicion del bundle`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        fake.responde("CONTENT/DETAIL/BUNDLE/b1", bundleCon(ep("e1", 1, 1, "T1E1")))
        fake.responde("CONTENT/DETAIL/BUNDLE/b2", bundleCon(ep("e2", 1, 1, "T2E1")))

        val t = DituEpisodios(fake).de(DituRef("g9", "GROUP_OF_BUNDLES"))

        assertEquals(listOf(1, 2), t.episodios.map { it.temporada })
        assertEquals(listOf("e1", "e2"), t.episodios.map { it.contentId })
    }

    @Test fun `los hijos del grupo se piden filtrando por parentId`() = runTest {
        val fake = FakeDituCliente()
        DituEpisodios(fake).de(DituRef("g9", "GROUP_OF_BUNDLES"))

        val (path, params) = fake.llamadas.first()
        assertEquals("TRAY/SEARCH/VOD", path)
        assertEquals("g9", params["filter_parentId"])
        assertEquals("BUNDLE", params["filter_contentType"])
    }

    @Test fun `un bundle sin containers no explota, devuelve vacio`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """{"resultObj":{"containers":[]}}""")

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))
        assertTrue(t.episodios.isEmpty())
    }

    @Test fun `las imagenes salen del CDN de Caracol`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 1, 1, "Uno")))

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/portrait-thin-promotional-tablet.jpg", t.posterUrl)
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/landscape-regular-clean-tablet.jpg", t.fondoUrl)
    }
}
