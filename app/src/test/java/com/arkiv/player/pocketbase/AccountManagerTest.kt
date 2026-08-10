package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagerTest {
    private fun clientFor(server: MockWebServer) =
        PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun seededAuth(client: PocketBaseClient, store: DeviceStore) =
        DeviceAuthManager(client, store)

    @Test
    fun register_creaUsersConAccountIdDelDeviceYQuedaConectado() = runBlocking {
        val server = MockWebServer()
        // ensureBootstrapped -> authExisting (devices auth)
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        // register -> createRecord(users)
        server.enqueue(MockResponse().setBody("""{"id":"usr-1"}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        var merged = false
        val mgr = AccountManager(client, deviceAuth, store, onAccountSwitched = { merged = true }, onLocalWipe = {})

        mgr.register("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co"), mgr.state.value)
        assertEquals("a@b.co", store.personEmail())
        // register NO dispara merge (accountId no cambia)
        assertTrue(!merged)
        // el body de createRecord(users) lleva el accountId del device
        server.takeRequest() // auth
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().contains("\"accountId\":\"A_anon\""))
        server.shutdown()
    }

    @Test
    fun login_adoptaAccountIdDeLaPersonaYDisparaMerge() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}""")) // switchAccount PATCH
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        var merged = false
        val mgr = AccountManager(client, deviceAuth, store, onAccountSwitched = { merged = true }, onLocalWipe = {})

        mgr.login("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co"), mgr.state.value)
        assertEquals("A_person", deviceAuth.session.value?.accountId)
        assertTrue("login debe disparar el merge", merged)
        server.shutdown()
    }

    @Test
    fun login_conCredencialesInvalidas_lanzaAccountException() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, onAccountSwitched = {}, onLocalWipe = {})

        var threw = false
        try { mgr.login("a@b.co", "bad") } catch (e: AccountException) { threw = true }
        assertTrue(threw)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    @Test
    fun logout_limpiaLocalYVuelveAnonimo() = runBlocking {
        val server = MockWebServer()
        // resetToAnonymous -> createNewAccount: createRecord(devices) + authWithPassword(devices)
        server.enqueue(MockResponse().setBody("""{"id":"devrec2"}"""))
        server.enqueue(MockResponse().setBody("""{"token":"dtok2","record":{"id":"devrec2"}}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        store.savePersonEmail("a@b.co")
        var wiped = false
        val mgr = AccountManager(client, seededAuth(client, store), store, onAccountSwitched = {}, onLocalWipe = { wiped = true })

        mgr.logout()

        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertTrue("logout debe limpiar local", wiped)
        assertEquals(null, store.personEmail())
        server.shutdown()
    }
}
