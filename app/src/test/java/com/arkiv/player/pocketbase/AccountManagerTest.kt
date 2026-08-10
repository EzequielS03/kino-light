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

    /** Mismo MockWebServer que PocketBase: el gateway Magis es otro dominio en prod, pero para el
     *  test alcanza con apuntar ambos clientes al mismo server (más simple que fakear una interfaz). */
    private fun magisLinkFor(server: MockWebServer) =
        MagisLinkClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            apiKey = { "LLAVE" },
            accountId = { "A_anon" },
        )

    @Test
    fun login_conPocketBaseOk_quedaConectado() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}""")) // switchAccount PATCH
        server.enqueue(MockResponse().setBody("""{"linked":true}""")) // magisVinculadoSeguro -> status
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        var merged = false
        val mgr = AccountManager(client, deviceAuth, store, magisLinkFor(server), onAccountSwitched = { merged = true }, onLocalWipe = {})

        mgr.login("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        assertEquals("A_person", deviceAuth.session.value?.accountId)
        assertTrue("login debe disparar el merge", merged)
        server.shutdown()
    }

    @Test
    fun login_conRecordSinAccountId_lanzaAccountException() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1"}}""")) // users auth, sin accountId
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        var threw = false
        try { mgr.login("a@b.co", "secret12") } catch (e: AccountException) { threw = true }
        assertTrue(threw)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    @Test
    fun login_pocketBaseMiss_linkOk_creaPocketBaseYQuedaConectadoConMagis() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth: PB no la tiene
        server.enqueue(MockResponse().setBody("{}")) // magis link OK
        server.enqueue(MockResponse().setBody("""{"id":"usr-1"}""")) // crearPocketBase -> createRecord(users)
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        val mgr = AccountManager(client, deviceAuth, store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        mgr.login("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        assertEquals("a@b.co", store.personEmail())
        // el body de createRecord(users) lleva el accountId del device y va autenticado con su token
        server.takeRequest() // bootstrap
        server.takeRequest() // users auth (400)
        server.takeRequest() // magis link
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().contains("\"accountId\":\"A_anon\""))
        assertEquals("dtok", create.getHeader("Authorization"))
        server.shutdown()
    }

    @Test
    fun login_pocketBaseMiss_link503_lanzaAccountExceptionNoDisponible() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth: PB no la tiene
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"caido"}""")) // magis caído
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        var msg: String? = null
        try { mgr.login("a@b.co", "secret12") } catch (e: AccountException) { msg = e.message }
        assertTrue("mensaje: $msg", msg?.contains("no disponible") == true)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    @Test
    fun login_pocketBaseMiss_link422_lanzaAccountExceptionInvalidas() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth: PB no la tiene
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"credenciales invalidas"}""")) // magis rechaza
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        var msg: String? = null
        try { mgr.login("a@b.co", "bad") } catch (e: AccountException) { msg = e.message }
        assertTrue("mensaje: $msg", msg?.contains("inválidos") == true)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    @Test
    fun registerSendCode_ok_devuelveCodigoEnviadoSinTocarPocketBase() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("{}")) // magis registerSendCode OK
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        val paso = mgr.registerSendCode("a@b.co", "secret12")

        assertEquals(RegistroPaso.CODIGO_ENVIADO, paso)
        assertEquals(AccountState.Anonimo, mgr.state.value)   // todavía no está creada la cuenta
        server.shutdown()
    }

    @Test
    fun registerSendCode_magis503_creaSoloPocketBaseYQuedaConectadoSinMagis() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"caido"}""")) // magis caído
        server.enqueue(MockResponse().setBody("""{"id":"usr-1"}""")) // crearPocketBase -> createRecord(users)
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        val paso = mgr.registerSendCode("a@b.co", "secret12")

        assertEquals(RegistroPaso.CREADA_SIN_MAGIS, paso)
        assertEquals(AccountState.Conectado("a@b.co", false), mgr.state.value)
        assertEquals("a@b.co", store.personEmail())
        server.shutdown()
    }

    @Test
    fun registerConfirm_ok_creaPocketBaseYQuedaConectadoConMagis() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("{}")) // magis registerConfirm OK
        server.enqueue(MockResponse().setBody("""{"id":"usr-1"}""")) // crearPocketBase -> createRecord(users)
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        mgr.registerConfirm("a@b.co", "secret12", "123456")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        assertEquals("a@b.co", store.personEmail())
        server.shutdown()
    }

    @Test
    fun registerConfirm_magis422_lanzaAccountExceptionCodigoIncorrecto() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"codigo invalido"}""")) // magis rechaza el código
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = {})

        var msg: String? = null
        try { mgr.registerConfirm("a@b.co", "secret12", "000000") } catch (e: AccountException) { msg = e.message }
        assertEquals("código incorrecto", msg)
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
        val mgr = AccountManager(client, seededAuth(client, store), store, magisLinkFor(server), onAccountSwitched = {}, onLocalWipe = { wiped = true })

        mgr.logout()

        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertTrue("logout debe limpiar local", wiped)
        assertEquals(null, store.personEmail())
        server.shutdown()
    }
}
