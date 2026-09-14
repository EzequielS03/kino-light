package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisCatalogTest {

    @Test
    fun `search builds the right bean for searchByName`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, testSession(fake))

        catalog.search("batman")

        val (path, bean) = fake.calls.first()
        assertEquals("v3/searchByName", path)
        assertEquals("batman", bean["value"])
        assertEquals("0", bean["type"])
        assertEquals(1, bean["pageNum"])
        assertEquals(20, bean["pageSize"])
    }

    @Test
    fun `detail sends contentId and type`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, testSession(fake))

        catalog.detail("C42", type = "0")

        val (path, bean) = fake.calls.first()
        assertEquals("v4/getItemData", path)
        assertEquals("C42", bean["contentId"])
        assertEquals("0", bean["type"])
        assertEquals("0", bean["sortType"])
    }

    @Test
    fun `nextColumns asks for the root with its page size`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, testSession(fake))

        catalog.nextColumns("masnew_live", pageSize = 200)

        val (path, bean) = fake.calls.first()
        assertEquals("getNextColumns", path)
        assertEquals("masnew_live", bean["columnCode"])
        assertEquals(200, bean["pageSize"])
        assertEquals("", bean["version"])
    }

    @Test
    fun `catalog calls travel with the device's session`() = runTest {
        val fake = FakePortalClient()
        val catalog = MagisCatalog(fake, testSession(fake))

        catalog.search("batman")

        assertEquals("u-test" to "t-test", fake.sessions.first())
    }

    @Test
    fun `with no active session, activates one before asking for the catalog`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.queueResponse("v8/active", portalOk("userId" to "u-nuevo", "userToken" to "t-nuevo"))
        val catalog = MagisCatalog(fake, MagisSession(fake, FakeCredentialStore()))

        catalog.search("batman")

        assertEquals(listOf("v3/snToken", "v8/active", "v3/searchByName"), fake.calls.map { it.first })
        assertEquals("u-nuevo" to "t-nuevo", fake.sessions.last())
    }

    @Test
    fun `if it can't activate, it doesn't ask for the catalog`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", MagisResult.RedError(java.io.IOException("sin red")))
        val catalog = MagisCatalog(fake, MagisSession(fake, FakeCredentialStore()))

        val r = catalog.search("batman")

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
        assertEquals(0, fake.timesCalled("v3/searchByName"))
    }

    @Test
    fun `the retry after reauthenticating travels with the NEW token`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", MagisResult.PortalError("aaa100028", "未登录！"))
        fake.queueResponse("v8/active", portalOk("userId" to "u-fresco", "userToken" to "t-fresco"))
        fake.queueResponse("v3/searchByName", portalOk("resultado" to "ok"))
        val catalog = MagisCatalog(fake, testSession(fake))

        val r = catalog.search("batman")

        assertEquals("ok", r.getOrNull()?.getString("resultado"))
        assertEquals(2, fake.timesCalled("v3/searchByName"))
        val sesionesDeBusqueda = fake.calls.withIndex()
            .filter { it.value.first == "v3/searchByName" }
            .map { fake.sessions[it.index] }
        assertEquals("u-test" to "t-test", sesionesDeBusqueda[0])
        assertEquals("u-fresco" to "t-fresco", sesionesDeBusqueda[1])
    }
}
