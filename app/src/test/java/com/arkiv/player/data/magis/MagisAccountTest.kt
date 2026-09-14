package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisAccountTest {

    private fun account(portal: FakePortalClient, store: FakeCredentialStore = FakeCredentialStore()) =
        MagisAccount(MagisSession(portal, store))

    @Test
    fun `starts at None and refresh reads what's saved`() = runTest {
        val store = FakeCredentialStore()
        val c = account(FakePortalClient(), store)
        assertEquals(MagisAccountState.None, c.state.value)

        store.saveAccount("persona@ejemplo.com", "clave123")
        c.refresh()

        assertEquals(MagisAccountState.Linked("persona@ejemplo.com"), c.state.value)
    }

    @Test
    fun `linking with credentials the portal accepts leaves Linked with the email`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v8/login", portalOk("userId" to "u1", "userToken" to "t1"))
        val c = account(portal)

        c.link("persona@ejemplo.com", "clave123")

        assertEquals(MagisAccountState.Linked("persona@ejemplo.com"), c.state.value)
    }

    @Test
    fun `rejected credentials don't change the state and the message says so`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v8/login", MagisResult.PortalError("aaa100015", "clave mala"))
        val c = account(portal)

        val e = runCatching { c.link("persona@ejemplo.com", "mala") }.exceptionOrNull()

        assertTrue(e is MagisException)
        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("inválidas"))
        assertEquals(MagisAccountState.None, c.state.value)
    }

    @Test
    fun `a portal that's down is distinguished from a bad password`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v8/login", MagisResult.RedError(java.io.IOException("sin red")))

        val e = runCatching { account(portal).link("a@b.com", "x") }.exceptionOrNull()

        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("no disponible"))
    }

    @Test
    fun `unlink goes back to None`() = runTest {
        val portal = FakePortalClient()
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u", "t", "", "sn"))
        store.saveAccount("persona@ejemplo.com", "clave123")
        val c = account(portal, store)
        c.refresh()
        assertEquals(MagisAccountState.Linked("persona@ejemplo.com"), c.state.value)

        c.unlink()

        assertEquals(MagisAccountState.None, c.state.value)
    }

    @Test
    fun `unlink deletes the credentials even if the portal is down`() = runTest {
        val portal = FakePortalClient()
        portal.defaultResponse = MagisResult.RedError(java.io.IOException("sin red"))
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u", "t", "", "sn"))
        store.saveAccount("persona@ejemplo.com", "clave123")
        val c = account(portal, store)
        c.refresh()
        assertEquals(MagisAccountState.Linked("persona@ejemplo.com"), c.state.value)

        c.unlink()

        assertEquals(null, store.readAccount())
        assertEquals(MagisAccountState.None, c.state.value)
    }

    @Test
    fun `unlink with an active session notifies the portal before deleting`() = runTest {
        val portal = FakePortalClient()
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u", "t", "", "sn"))
        store.saveAccount("persona@ejemplo.com", "clave123")
        val c = account(portal, store)
        c.refresh()

        c.unlink()

        assertEquals(1, portal.timesCalled("v5/loginOut"))
    }
}
