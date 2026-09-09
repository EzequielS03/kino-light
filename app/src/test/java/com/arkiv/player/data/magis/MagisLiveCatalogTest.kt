package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisLiveCatalogTest {

    private fun catalogoDeVivo(
        fake: FakePortalClient,
        ahoraMs: () -> Long = { 0L },
    ): MagisLiveCatalog {
        val session = sesionDeTest(fake)
        return MagisLiveCatalog(MagisCatalog(fake, session), fake, session, ahoraMs)
    }

    private fun categoriasDelPortal() = MagisResult.Ok(
        JSONObject(
            """{"recommendList":[
                {"columnId":76182,"name":"ChannelList"},
                {"columnId":76183,"name":"Deportes"},
                {"columnId":76184,"name":"18+"},
                {"name":"sin columnId"}
            ]}""",
        ),
    )

    private fun canalesDelPortal(vararg codigos: String) = MagisResult.Ok(
        JSONObject(
            """{"channelList":[${codigos.joinToString(",") { c ->
                """{"channelCode":"$c","name":"Canal $c","channelNumber":"7",
                    "posterList":[{"fileType":"poster","fileUrl":"https://p/$c.jpg"},
                                  {"fileType":"icon","fileUrl":"https://i/$c.png"}]}"""
            }}]}""",
        ),
    )

    @Test
    fun `ChannelList se muestra como Todos y el 18+ no sale sin pedirlo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())

        val cats = catalogoDeVivo(fake).categorias()

        assertEquals(listOf("Todos", "Deportes"), cats.map { it.nombre })
        assertEquals(listOf(76182, 76183), cats.map { it.id })
    }

    @Test
    fun `con incluirAdultos sale tambien la de adultos`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())

        val cats = catalogoDeVivo(fake).categorias(incluirAdultos = true)

        assertEquals(listOf("Todos", "Deportes", "18+"), cats.map { it.nombre })
    }

    @Test
    fun `las categorias se piden con pageSize 200, no con el default`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())

        catalogoDeVivo(fake).categorias()

        val (_, bean) = fake.llamadas.first { it.first == "getNextColumns" }
        assertEquals("masnew_live", bean["columnCode"])
        assertEquals(200, bean["pageSize"])
    }

    @Test
    fun `el logo sale del posterList con fileType icon, no del primero de la lista`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal("A"))

        val canales = catalogoDeVivo(fake).canales(76183)

        assertEquals("https://i/A.png", canales.single().logo)
        assertEquals("Canal A", canales.single().nombre)
        assertEquals(7, canales.single().numero)
    }

    @Test
    fun `sin icon en posterList se cae al posterUrl suelto`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta(
            "v6/getLiveData",
            MagisResult.Ok(
                JSONObject(
                    """{"channelList":[
                        {"channelCode":"B","name":"B","posterUrl":"https://suelta/b.png",
                         "posterList":[{"fileType":"poster","fileUrl":"https://p/b.jpg"}]},
                        {"channelCode":"C","name":"C"}
                    ]}""",
                ),
            ),
        )

        val canales = catalogoDeVivo(fake).canales(76183)

        assertEquals("https://suelta/b.png", canales[0].logo)
        assertNull(canales[1].logo)
    }

    @Test
    fun `los canales de una categoria de adultos quedan marcados uno por uno`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal("X"))

        val canales = catalogoDeVivo(fake).canales(76184)

        assertTrue(canales.single().adulto)
    }

    @Test
    fun `una categoria normal no marca sus canales`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal("X"))

        assertTrue(!catalogoDeVivo(fake).canales(76183).single().adulto)
    }

    @Test
    fun `pagina hasta que el portal devuelve una pagina incompleta`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal(*(1..500).map { "p1-$it" }.toTypedArray()))
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal(*(1..40).map { "p2-$it" }.toTypedArray()))

        val canales = catalogoDeVivo(fake).canales(76183)

        assertEquals(540, canales.size)
        assertEquals(2, fake.vecesLlamado("v6/getLiveData"))
        assertEquals(listOf(1, 2), fake.llamadas.filter { it.first == "v6/getLiveData" }.map { it.second["pageNum"] })
    }

    @Test
    fun `si el portal ignorara pageNum no se duplica el catalogo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        val pagina = canalesDelPortal(*(1..500).map { "rep-$it" }.toTypedArray())
        fake.encolarRespuesta("v6/getLiveData", pagina)
        fake.encolarRespuesta("v6/getLiveData", pagina)

        val canales = catalogoDeVivo(fake).canales(76183)

        assertEquals(500, canales.size)
        assertEquals(2, fake.vecesLlamado("v6/getLiveData"))
    }

    @Test
    fun `el catalogo se cachea y no vuelve al portal hasta que vence`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal("A"))
        var ahora = 0L
        val catalogo = catalogoDeVivo(fake) { ahora }

        catalogo.canales(76183)
        catalogo.canales(76183)
        catalogo.categorias()

        assertEquals(1, fake.vecesLlamado("v6/getLiveData"))
        assertEquals(1, fake.vecesLlamado("getNextColumns"))

        // Pasadas las 6 h vuelve a preguntar.
        ahora = 7 * 60 * 60 * 1000L
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())
        fake.encolarRespuesta("v6/getLiveData", canalesDelPortal("A"))
        catalogo.canales(76183)

        assertEquals(2, fake.vecesLlamado("v6/getLiveData"))
    }

    @Test
    fun `un error del portal no se cachea como un catalogo vacio`() = runTest {
        val fake = FakePortalClient()
        // Una sola: un RedError no dispara reintento (el portal no dijo nada, esta caido).
        fake.encolarRespuesta("getNextColumns", MagisResult.RedError(java.io.IOException("sin red")))
        val catalogo = catalogoDeVivo(fake)

        assertTrue(catalogo.categorias().isEmpty())
        fake.encolarRespuesta("getNextColumns", categoriasDelPortal())

        assertEquals(listOf("Todos", "Deportes"), catalogo.categorias().map { it.nombre })
    }

    @Test
    fun `el portal no tiene EPG y se dice asi, sin inventar horarios`() = runTest {
        val fake = FakePortalClient()

        val (guia, faltan) = catalogoDeVivo(fake).epg(listOf("c1", "c2"))

        assertTrue(guia.isEmpty())
        assertEquals(listOf("c1", "c2"), faltan)
        assertTrue(fake.llamadas.isEmpty())
    }
}
