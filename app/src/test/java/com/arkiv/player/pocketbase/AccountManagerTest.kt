package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagerTest {
    private fun clientFor(server: MockWebServer) =
        PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun seededAuth(client: PocketBaseClient, store: DeviceStore) =
        DeviceAuthManager(client, store)

    private fun sesionFor(client: PocketBaseClient, store: DeviceStore) =
        SesionDePersona(client, store)

    /** Mismo MockWebServer que PocketBase: el gateway Magis es otro dominio en prod, pero para el
     *  test alcanza con apuntar ambos clientes al mismo server (más simple que fakear una interfaz). */
    private fun magisLinkFor(server: MockWebServer) =
        MagisLinkClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            apiKey = { "LLAVE" },
            accountId = { "A_anon" },
        )

    /** Ninguno de estos tests pasa por `registrar` (eso lo cubre AccountManagerRegistroTest): esta
     *  URL nunca se llama, mismo criterio que usa CuentaApiTest con SesionDePersona cuando no le
     *  hace falta hablarle a nadie. */
    private fun cuentaApiSinUsar(sesion: SesionDePersona) = CuentaApi(
        baseUrl = { "http://unused.invalid" },
        apiKey = { "LLAVE" },
        deviceToken = { null },
        sesion = sesion,
        http = OkHttpClient(),
    )

    @Test
    fun login_conPocketBaseOk_quedaConectado() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth (probe: ¿PB la conoce?)
        server.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar (Task 1): persiste el token de la persona
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}""")) // switchAccount PATCH
        server.enqueue(MockResponse().setBody("""{"linked":true}""")) // magisVinculadoSeguro -> status
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        val sesion = sesionFor(client, store)
        var merged = false
        val mgr = AccountManager(
            client, deviceAuth, store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = { merged = true }, onLocalWipe = {},
        )

        mgr.login("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        assertEquals("A_person", deviceAuth.session.value?.accountId)
        assertEquals("login debe persistir la sesion de la persona (Task 1)", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        assertEquals("ptok", sesion.token())
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
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var threw = false
        try { mgr.login("a@b.co", "secret12") } catch (e: AccountException) { threw = true }
        assertTrue(threw)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        server.shutdown()
    }

    /**
     * Reemplaza a los tres tests viejos de "PocketBase no la tiene -> validar contra Magis": ese
     * camino se sacó (era el agujero: cualquiera con credenciales de Magis válidas se fabricaba una
     * cuenta de Arkiv sin licencia). Ahora un email que PocketBase no conoce es simplemente un
     * rechazo — "registrate con tu código" — sin tocar Magis para nada.
     */
    @Test
    fun login_pocketBaseNoConoceElEmail_lanzaAccountExceptionSinCrearNada() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth: PB no la tiene
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val sesion = sesionFor(client, store)
        // Si login() todavía cayera a Magis, esto explotaría (host inexistente) en vez de pasar en
        // silencio: es la red de seguridad de este test, no solo el requestCount de abajo.
        val magisLink = MagisLinkClient(baseUrl = { "http://unused.invalid" }, apiKey = { "LLAVE" }, accountId = { null })
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLink, cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var msg: String? = null
        try { mgr.login("a@b.co", "secret12") } catch (e: AccountException) { msg = e.message }

        assertTrue("mensaje: $msg", msg?.contains("registrate") == true)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        assertEquals("solo bootstrap + el intento de auth; nada de Magis ni de crear nada", 2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun desvincularMagis_ok_quedaConectadoSinMagis() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"linked":true}""")) // refrescarMagis -> status
        server.enqueue(MockResponse().setBody("{}")) // magis unlink OK
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        store.savePersonEmail("a@b.co")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )
        mgr.refrescarMagis()
        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)

        mgr.desvincularMagis()

        assertEquals(AccountState.Conectado("a@b.co", false), mgr.state.value)
        server.shutdown()
    }

    @Test
    fun desvincularMagis_503_lanzaAccountExceptionNoDisponible() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"caido"}""")) // magis unlink caído
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        store.savePersonEmail("a@b.co")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var msg: String? = null
        try { mgr.desvincularMagis() } catch (e: AccountException) { msg = e.message }
        assertTrue("mensaje: $msg", msg?.contains("no disponible") == true)
        server.shutdown()
    }

    @Test
    fun logout_limpiaLocalYVuelveAnonimo() = runBlocking {
        val store = FakeDeviceStore(DeviceIdentity("A_person","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        store.savePersonEmail("a@b.co")
        store.savePersonToken("ptok")
        // Sin MockWebServer: con el fix, logout() no hace NINGÚN pedido de red (ver el test de abajo,
        // que sí necesita uno para probar la mutación contraria).
        val client = PocketBaseClient(baseUrl = "http://unused.invalid")
        val deviceAuth = DeviceAuthManager(client, store)
        val sesion = sesionFor(client, store)
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)   // arranca conectada
        var wiped = false
        val mgr = AccountManager(
            client, deviceAuth, store, MagisLinkClient(baseUrl = { "http://unused.invalid" }, apiKey = { "LLAVE" }, accountId = { null }),
            cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = { wiped = true },
        )

        mgr.logout()

        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        assertTrue("logout debe limpiar local", wiped)
        assertEquals(null, store.personEmail())
        assertEquals(null, store.personToken())
    }

    /**
     * La identidad del DEVICE (no la de la persona) es lo que distingue "logout limpio" de
     * "logout que re-bootstrapea anónimo": `resetToAnonymous()` empieza con `store.clear()`, que
     * borra la identidad del device incondicionalmente (haya o no red para la recreación). Si logout
     * volviera a llamarlo, `store.load()` daría null acá — con el fix, la identidad del device queda
     * intacta (la persona vuelve a la pantalla de entrada, pero el device sigue siendo el mismo).
     */
    @Test
    fun logout_noTocaLaIdentidadDelDevice() = runBlocking {
        val identidad = DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone")
        val store = FakeDeviceStore(identidad)
        store.savePersonEmail("a@b.co")
        val client = PocketBaseClient(baseUrl = "http://unused.invalid")
        val deviceAuth = DeviceAuthManager(client, store)
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, deviceAuth, store, MagisLinkClient(baseUrl = { "http://unused.invalid" }, apiKey = { "LLAVE" }, accountId = { null }),
            cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.logout()

        assertNotNull("logout no debe re-bootstrapear (eso borraría la identidad del device)", store.load())
        assertEquals(identidad, store.load())
    }
}
