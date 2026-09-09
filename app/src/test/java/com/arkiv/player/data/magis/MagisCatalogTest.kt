package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisCatalogTest {

    @Test
    fun `search arma el bean correcto para searchByName`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        catalog.search("batman")

        val (path, bean) = fake.llamadas.first()
        assertEquals("v3/searchByName", path)
        assertEquals("batman", bean["value"])
        assertEquals("0", bean["type"])
        assertEquals(1, bean["pageNum"])
        assertEquals(20, bean["pageSize"])
    }

    @Test
    fun `detail manda contentId y tipo`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        catalog.detail("C42", tipo = "0")

        val (path, bean) = fake.llamadas.first()
        assertEquals("v4/getItemData", path)
        assertEquals("C42", bean["contentId"])
        assertEquals("0", bean["type"])
        assertEquals("0", bean["sortType"])
    }

    @Test
    fun `nextColumns pide la raiz con su tamano de pagina`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        catalog.nextColumns("masnew_live", tamano = 200)

        val (path, bean) = fake.llamadas.first()
        assertEquals("getNextColumns", path)
        assertEquals("masnew_live", bean["columnCode"])
        assertEquals(200, bean["pageSize"])
        assertEquals("", bean["version"])
    }

    @Test
    fun `las llamadas de catalogo viajan con la sesion del aparato`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        catalog.search("batman")

        assertEquals("u-test" to "t-test", fake.sesiones.first())
    }

    @Test
    fun `sin sesion activa una antes de pedir catalogo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u-nuevo", "userToken" to "t-nuevo"))
        val catalog = MagisCatalog(fake, MagisSession(fake, FakeCredentialStore()))

        catalog.search("batman")

        assertEquals(listOf("v3/snToken", "v8/active", "v3/searchByName"), fake.llamadas.map { it.first })
        assertEquals("u-nuevo" to "t-nuevo", fake.sesiones.last())
    }

    @Test
    fun `si no se puede activar, no pide catalogo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", MagisResult.RedError(java.io.IOException("sin red")))
        val catalog = MagisCatalog(fake, MagisSession(fake, FakeCredentialStore()))

        val r = catalog.search("batman")

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
        assertEquals(0, fake.vecesLlamado("v3/searchByName"))
    }

    @Test
    fun `el reintento tras reautenticar viaja con el token NUEVO`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", MagisResult.PortalError("aaa100028", "未登录！"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u-fresco", "userToken" to "t-fresco"))
        fake.encolarRespuesta("v3/searchByName", portalOk("resultado" to "ok"))
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        val r = catalog.search("batman")

        assertEquals("ok", r.dato()?.getString("resultado"))
        assertEquals(2, fake.vecesLlamado("v3/searchByName"))
        val sesionesDeBusqueda = fake.llamadas.withIndex()
            .filter { it.value.first == "v3/searchByName" }
            .map { fake.sesiones[it.index] }
        assertEquals("u-test" to "t-test", sesionesDeBusqueda[0])
        assertEquals("u-fresco" to "t-fresco", sesionesDeBusqueda[1])
    }
}
