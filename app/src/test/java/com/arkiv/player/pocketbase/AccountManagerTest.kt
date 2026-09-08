package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))

    private fun sesionFor(client: PocketBaseClient, store: DeviceStore) =
        SesionDePersona(client, store)

    /** Mismo MockWebServer que PocketBase: el gateway Magis es otro dominio en prod, pero para el
     *  test alcanza con apuntar ambos clientes al mismo server (más simple que fakear una interfaz). */
    private fun magisLinkFor(server: MockWebServer) =
        MagisLinkClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
        )

    /** `login` llama al gateway desde que el aparato se adopta por ahi (unico camino que cuenta
     *  contra el cupo de la licencia), asi que quien lo necesite le pasa un baseUrl de verdad. */
    private fun cuentaApiSinUsar(sesion: SesionDePersona) = CuentaApi(
        baseUrl = { "http://unused.invalid" },
        deviceToken = { null },
        sesion = sesion,
        http = OkHttpClient(),
    )

    /** Para `login`, que adopta el aparato por el gateway. */
    private fun cuentaApiDe(server: MockWebServer, sesion: SesionDePersona) = CuentaApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        deviceToken = { "dtok" },
        sesion = sesion,
        http = OkHttpClient(),
    )

    @Test
    fun login_conPocketBaseOk_quedaConectado() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth (probe: ¿PB la conoce?)
        // POST /v1/cuenta/entrar: el login adopta el aparato por ahi, no por /aparatos -- ver
        // login_adoptaElAparatoPorEntrar_noPorAdoptarQueExigeSesionPrevia mas abajo.
        server.enqueue(MockResponse().setBody("""{"userId":"usr-1","accountId":"A_person","kind":"phone","usados":1,"tope":1,"yaEra":false,"desvinculado":null}"""))
        server.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar (Task 1): persiste el token de la persona
        server.enqueue(MockResponse().setBody("""{"linked":true}""")) // magisVinculadoSeguro -> status
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        val sesion = sesionFor(client, store)
        var merged = false
        val mgr = AccountManager(
            client, deviceAuth, store, magisLinkFor(server), cuentaApiDe(server, sesion), sesion,
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
        val magisLink = MagisLinkClient(baseUrl = { "http://unused.invalid" })
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

    /** `vincularMagis` con credenciales que Magis acepta: la cuenta de Kino sigue conectada con el
     *  mismo email y `magisLinked` pasa a true (Task 10, camino que usa la nueva oferta al entrar a
     *  la TV además de `TvVincularMagisSection`/`VincularMagisSection` en Ajustes). */
    @Test
    fun vincularMagis_ok_dejaMagisLinkedEnTrue() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}")) // POST /v1/magis/link -> 200 sin cuerpo relevante
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        store.savePersonEmail("a@b.co")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.vincularMagis("magis@x.co", "magispw12")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        server.shutdown()
    }

    /**
     * El requisito central de la Task 10 (la oferta al entrar a la TV): que Magis rechace unas
     * credenciales NO dice nada sobre la cuenta de Kino -son dos identidades distintas (ver KDoc de
     * `AccountManager`)-. Este test fija esa garantía en el lugar de donde depende TODO llamador
     * (la nueva pantalla, `TvVincularMagisSection` y `VincularMagisSection`): un 422 de Magis lanza
     * `AccountException` y listo, sin tocar ni `AccountState` ni `SesionDePersona.estado` -si
     * `vincularMagis` alguna vez llamara `sesion.cerrar()` o pisara el estado en la rama de error,
     * este test lo agarra-.
     */
    @Test
    fun vincularMagis_credencialesInvalidas_noTocaLaSesionDeKino() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"credenciales invalidas"}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        store.savePersonEmail("a@b.co")
        store.savePersonToken("ptok")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value) // arranca conectada a Kino

        var msg: String? = null
        try {
            mgr.vincularMagis("magis@x.co", "malacontrasena")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertTrue("mensaje: $msg", msg?.contains("inválidas") == true)
        assertEquals("la cuenta de Kino sigue conectada", AccountState.Conectado("a@b.co", false), mgr.state.value)
        assertEquals("la sesion de Kino NO se cierra por un rechazo de Magis", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        server.shutdown()
    }

    // --- vincularMagisEnviarCodigo / vincularMagisConfirmar (Task 11): el alta de una cuenta de
    // Magis nueva desde la TV, en dos pasos. Mismo criterio que `vincularMagis_*` de arriba: un
    // rechazo de Magis (email ya registrado, código incorrecto, portal caído) no dice nada de la
    // cuenta de Kino, así que tiene que quedar intacta.

    @Test
    fun vincularMagisEnviarCodigo_falloDeMagis_noTocaLaSesionDeKino() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"ese email ya esta registrado"}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        store.savePersonEmail("a@b.co")
        store.savePersonToken("ptok")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value) // arranca conectada a Kino

        var msg: String? = null
        try {
            mgr.vincularMagisEnviarCodigo("magis@x.co")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertTrue("mensaje: $msg", msg?.contains("registrado") == true)
        assertEquals("la cuenta de Kino sigue conectada", AccountState.Conectado("a@b.co", false), mgr.state.value)
        assertEquals("la sesion de Kino NO se cierra por un rechazo de Magis", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        server.shutdown()
    }

    @Test
    fun vincularMagisConfirmar_ok_dejaMagisLinkedEnTrue() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}")) // POST /v1/magis/register/confirm -> 200
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        store.savePersonEmail("a@b.co")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.vincularMagisConfirmar("magis@x.co", "clavenueva1", "123456")

        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        server.shutdown()
    }

    @Test
    fun vincularMagisConfirmar_codigoIncorrecto_noTocaLaSesionDeKino() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"codigo invalido"}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        store.savePersonEmail("a@b.co")
        store.savePersonToken("ptok")
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value) // arranca conectada a Kino

        var msg: String? = null
        try {
            mgr.vincularMagisConfirmar("magis@x.co", "clavenueva1", "000000")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertTrue("mensaje: $msg", msg?.contains("incorrecto") == true)
        assertEquals("la cuenta de Kino sigue conectada, y sin Magis", AccountState.Conectado("a@b.co", false), mgr.state.value)
        assertEquals("la sesion de Kino NO se cierra por un codigo incorrecto", EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
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
        val deviceAuth = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))
        val sesion = sesionFor(client, store)
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)   // arranca conectada
        var wiped = false
        val mgr = AccountManager(
            client, deviceAuth, store, MagisLinkClient(baseUrl = { "http://unused.invalid" }),
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
        val deviceAuth = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, deviceAuth, store, MagisLinkClient(baseUrl = { "http://unused.invalid" }),
            cuentaApiSinUsar(sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.logout()

        assertNotNull("logout no debe re-bootstrapear (eso borraría la identidad del device)", store.load())
        assertEquals(identidad, store.load())
    }
    /**
     * EL BUG DEL 2026-08-14. En el Google TV, con la cuenta recien creada, el login volvia siempre
     * a la pantalla de login. En el gateway:
     *
     * ```
     * POST /v1/cuenta/aparatos/alta  -> 201 Created
     * POST /v1/cuenta/aparatos       -> 401 Unauthorized
     * ```
     *
     * `POST /aparatos` exige que el aparato que llama YA sea de la cuenta, y meterlo en la cuenta
     * es lo que ese endpoint viene a hacer: en un aparato nuevo no sale nunca. El login tiene que
     * ir por `/entrar`, que corre sin sesion previa y usa la contrasena como prueba.
     *
     * Este test mira la RUTA del pedido, no solo que login termine bien: con `/aparatos` el
     * MockWebServer contestaria igual y el test pasaria sobre el camino roto.
     */
    @Test
    fun login_adoptaElAparatoPorEntrar_noPorAdoptarQueExigeSesionPrevia() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        server.enqueue(MockResponse().setBody("""{"userId":"usr-1","accountId":"A_person","kind":"tv","usados":1,"tope":1,"yaEra":false,"desvinculado":"tv_vieja"}"""))
        server.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        server.enqueue(MockResponse().setBody("""{"linked":true}""")) // magisVinculadoSeguro
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","tv"))
        val deviceAuth = seededAuth(client, store)
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, deviceAuth, store, magisLinkFor(server), cuentaApiDe(server, sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.login("a@b.co", "secret12")

        val rutas = (1..4).map { server.takeRequest().path }
        assertTrue(
            "el login tiene que pasar por /v1/cuenta/entrar; rutas=$rutas",
            rutas.any { it == "/v1/cuenta/entrar" },
        )
        assertTrue(
            "no puede seguir yendo por /aparatos, que en un aparato nuevo es un 401 eterno; rutas=$rutas",
            rutas.none { it == "/v1/cuenta/aparatos" },
        )
        assertEquals(AccountState.Conectado("a@b.co", true), mgr.state.value)
        server.shutdown()
    }

    /**
     * MEDIDO EN EL GOOGLE TV el 2026-08-14, con `/entrar` ya desplegado y andando. El gateway
     * decia que si -- adopto el aparato y desalojo la TV vieja -- y la app te devolvia al login
     * igual. En el log del servidor, en 15 segundos:
     *
     * ```
     * 19:07:11.645  GET /v1/catalog/... -> 401     <- la app YA estaba adentro
     * 19:07:12.070  POST /v1/cuenta/entrar -> 200  <- recien aca el aparato entra a la cuenta
     * ...  82 pedidos, TODOS 401 ...
     * ```
     *
     * El portero de la app (`MainActivity`) mira `SesionDePersona.estado`, no el estado de
     * AccountManager. Y `login()` persistia la sesion ANTES de adoptar el aparato: la puerta se
     * abria medio segundo antes de que el aparato tuviera derecho a pedir nada, el home disparaba
     * decenas de pedidos autenticados contra un aparato todavia huerfano, y el interceptor de
     * sesion -- que cierra la sesion cuando ve un 401 -- echaba a la persona de vuelta al login.
     *
     * Este test fija el orden por su consecuencia observable: si la adopcion NO sale, la puerta
     * no se abre. Con el orden viejo la sesion quedaba persistida igual y la persona entraba a
     * una app que la iba a expulsar sola.
     */
    @Test
    fun login_siLaAdopcionFalla_noDejaLaSesionPersistidaAbriendoLaPuerta() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        // POST /v1/cuenta/entrar: el aparato NO entra. Ninguna respuesta de `sesion.iniciar`
        // encolada a proposito -- ese pedido ya no tiene que llegar a ocurrir.
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"tope_alcanzado","mensaje":"sin cupo"}}"""),
        )
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","tv"))
        val sesion = sesionFor(client, store)
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiDe(server, sesion), sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var threw = false
        try { mgr.login("a@b.co", "secret12") } catch (e: AccountException) { threw = true }

        assertTrue("la adopcion fallo, login tiene que fallar", threw)
        assertEquals(
            "sin aparato adoptado la puerta NO se puede abrir: la app entraria al home para que la echen a 401",
            EstadoDeSesion.Sin, sesion.estado.value,
        )
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    /**
     * La otra mitad del mismo dia. `login()` lo llama la pantalla de entrada desde un
     * `rememberCoroutineScope()`, y persistir la sesion SACA esa pantalla de la composicion --
     * o sea que el scope desde el que corre `login()` se muere en medio de `login()`. En el
     * aparato se veia asi:
     *
     * ```
     * ArkivSync: sync -> Error(message=The coroutine scope left the composition)
     * ```
     *
     * Consecuencia: el merge no corria y `_state` se quedaba en Anonimo aunque la persona
     * estuviera adentro -- Ajustes le mostraba "no tenes cuenta" a alguien logueado.
     *
     * El test reproduce exactamente eso: cancela el scope de quien llama en cuanto la sesion
     * queda persistida, que es cuando la pantalla real desaparece.
     */
    @Test
    fun login_siLaPantallaSeVaAlAbrirLaPuerta_elMergeYElEstadoTerminanIgual() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        server.enqueue(MockResponse().setBody("""{"userId":"usr-1","accountId":"A_person","kind":"tv","usados":1,"tope":1,"yaEra":false,"desvinculado":null}""")) // /entrar
        server.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        server.enqueue(MockResponse().setBody("""{"linked":false}""")) // magisVinculadoSeguro
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","tv"))
        val sesion = sesionFor(client, store)
        var merged = false
        val mgr = AccountManager(
            client, seededAuth(client, store), store, magisLinkFor(server), cuentaApiDe(server, sesion), sesion,
            // `delay` a proposito: el merge real suspende (push + pull contra la nube) y por eso
            // una cancelacion lo corta. Un lambda sin suspension no puede observarla.
            onAccountSwitched = { delay(50); merged = true }, onLocalWipe = {},
        )

        val scopeDeLaPantalla = CoroutineScope(Dispatchers.Default)
        // El portero real: `persistirSesion` deja `EstadoDeSesion.Con`, MainActivity recompone y la
        // pantalla de entrada -con su scope- deja de existir. `Unconfined` para que el cancel corra
        // EN la misma emision, no cuando otro hilo se despierte: sin eso la carrera decide el
        // resultado y el test pasa igual con el codigo roto (comprobado).
        val portero = scopeDeLaPantalla.launch(Dispatchers.Unconfined) {
            sesion.estado.first { it is EstadoDeSesion.Con }
            scopeDeLaPantalla.cancel()
        }
        val login = scopeDeLaPantalla.launch { mgr.login("a@b.co", "secret12") }
        withTimeout(10_000) { login.join(); portero.join() }

        assertTrue("el merge tiene que correr aunque la pantalla ya no este", merged)
        assertEquals(AccountState.Conectado("a@b.co", false), mgr.state.value)
        server.shutdown()
    }

}
