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

    @Test
    fun `sendRegistrationCode returns the pending registration on success`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v3/snToken", portalOk("snToken" to "TOK"))
        portal.queueResponse("v8/active", portalOk("userId" to "reg-u", "userToken" to "reg-t"))
        portal.queueResponse("v2/sendEmailVerifyCode", portalOk())
        val c = account(portal)

        val pending = c.sendRegistrationCode("nueva@ejemplo.com")

        assertEquals("reg-u", pending.userId)
        assertEquals("reg-t", pending.userToken)
        // The account itself isn't linked yet -- only confirmRegistration does that.
        assertEquals(MagisAccountState.None, c.state.value)
    }

    @Test
    fun `sendRegistrationCode surfaces a rejection with a message pointing at the email`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v3/snToken", portalOk("snToken" to "TOK"))
        portal.queueResponse("v8/active", portalOk("userId" to "reg-u", "userToken" to "reg-t"))
        portal.queueResponse("v2/sendEmailVerifyCode", MagisResult.PortalError("aaa100090", "ya registrado"))
        val c = account(portal)

        val e = runCatching { c.sendRegistrationCode("existente@ejemplo.com") }.exceptionOrNull()

        assertTrue(e is MagisException)
        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("email"))
    }

    @Test
    fun `confirmRegistration on success leaves the account Linked with the new email`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v2/validateVerifyCode", portalOk())
        portal.queueResponse("v2/bindEmail", portalOk())
        portal.queueResponse("v8/login", portalOk("userId" to "final-u", "userToken" to "final-t"))
        val c = account(portal)
        val pending = MagisSession.PendingRegistration(userId = "reg-u", userToken = "reg-t", sn = "reg-sn")

        c.confirmRegistration(pending, "nueva@ejemplo.com", "ClaveNueva123", "123456")

        assertEquals(MagisAccountState.Linked("nueva@ejemplo.com"), c.state.value)
    }

    @Test
    fun `confirmRegistration with a bad code doesn't change the state and says so`() = runTest {
        val portal = FakePortalClient()
        portal.queueResponse("v2/validateVerifyCode", MagisResult.PortalError("aaa100091", "código inválido"))
        val c = account(portal)
        val pending = MagisSession.PendingRegistration(userId = "reg-u", userToken = "reg-t", sn = "reg-sn")

        val e = runCatching {
            c.confirmRegistration(pending, "nueva@ejemplo.com", "ClaveNueva123", "000000")
        }.exceptionOrNull()

        assertTrue(e is MagisException)
        assertEquals(MagisAccountState.None, c.state.value)
    }
}
