package com.arkiv.player.ui.entrada

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.EstadoDeSesion
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.MagisLinkClient
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import com.arkiv.player.pocketbase.cuentaApiSinUsarParaBootstrap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El gate de arranque (Task 4): sin sesión pide entrada, con sesión abre directo -sin tocar la
 * red-, y [EntradaViewModel.manejarErrorDeCuenta] es la regla que decide qué error de cuenta puede
 * cerrar esa sesión y cuál no. Este último es "el test que más importa" del brief: confundir un
 * backend caído con un rechazo de identidad ya costó dos rondas de corrección en este proyecto.
 */
class EntradaViewModelTest {

    /** Ninguno de los tests de acá que no sean el de registro necesita hablarle a nadie: si algo
     *  intentara, esta URL inalcanzable lo delata. */
    private fun sesionSinRed(store: FakeDeviceStore) =
        SesionDePersona(PocketBaseClient(baseUrl = "http://unused.invalid"), store)

    /** `AccountManager` completo pero con todas sus dependencias apuntando a hosts inalcanzables:
     *  los tests de `manejarErrorDeCuenta`/`estado` no lo usan para nada, solo lo necesita el
     *  constructor de [EntradaViewModel]. */
    private fun accountManagerSinUsar(store: FakeDeviceStore, sesion: SesionDePersona): AccountManager {
        val client = PocketBaseClient(baseUrl = "http://unused.invalid")
        val cuentaApi = CuentaApi(
            baseUrl = { "http://unused.invalid" },
            deviceToken = { null },
            sesion = sesion,
            http = OkHttpClient(),
        )
        return AccountManager(
            client = client,
            deviceAuth = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store)),
            store = store,
            magisLink = MagisLinkClient(baseUrl = { "http://unused.invalid" }, accountId = { null }),
            cuentaApi = cuentaApi,
            sesion = sesion,
            onAccountSwitched = {},
            onLocalWipe = {},
        )
    }

    private fun vmSinSesion(): EntradaViewModel {
        val store = FakeDeviceStore()
        val sesion = sesionSinRed(store)
        return EntradaViewModel(sesion, accountManagerSinUsar(store, sesion))
    }

    private fun vmConSesion(email: String = "a@b.co", token: String = "ptok"): EntradaViewModel {
        val store = FakeDeviceStore()
        store.savePersonToken(token)
        store.savePersonEmail(email)
        val sesion = sesionSinRed(store)
        return EntradaViewModel(sesion, accountManagerSinUsar(store, sesion))
    }

    @Test
    fun `sin sesion el estado es Entrada`() {
        val vm = vmSinSesion()

        assertEquals(EstadoDeSesion.Sin, vm.sesionEstado.value)
        assertEquals(EstadoDeEntrada.Entrada(null), estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
    }

    /**
     * `refrescar()` traga en silencio cualquier fallo de transporte (por diseño, Task 1), así que
     * apuntar a un host inalcanzable NO alcanza para detectar un gate que igual llama a la red -el
     * pedido fallaría callado y el estado no cambiaría de todos modos-. Por eso este test mide
     * `requestCount` contra un servidor real: si el gate llega a pedir algo, queda una marca aunque
     * la respuesta nunca se use para nada.
     */
    @Test
    fun `con sesion guardada el estado es Adentro, sin pedir nada a la red`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"nuevo","record":{"id":"usr-1"}}""")) // por si el gate igual pidiera algo
        server.start()
        val store = FakeDeviceStore()
        store.savePersonToken("ptok")
        store.savePersonEmail("a@b.co")
        val sesion = SesionDePersona(PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/')), store)

        val vm = EntradaViewModel(sesion, accountManagerSinUsar(store, sesion))

        assertEquals(EstadoDeSesion.Con("a@b.co"), vm.sesionEstado.value)
        assertEquals(EstadoDeEntrada.Adentro, estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
        assertEquals("el gate no debe pedir nada a la red para abrir con sesion guardada", 0, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `sesion_invalida cierra la sesion guardada y deja el aviso`() {
        val vm = vmConSesion()

        vm.manejarErrorDeCuenta(ErrorDeCuenta.SesionInvalida("el token ya no vale"))

        assertEquals(EstadoDeSesion.Sin, vm.sesionEstado.value)
        assertEquals("el token ya no vale", vm.aviso.value)
        assertEquals(EstadoDeEntrada.Entrada("el token ya no vale"), estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
    }

    @Test
    fun `licencia_no_vigente cierra la sesion guardada`() {
        val vm = vmConSesion()

        vm.manejarErrorDeCuenta(ErrorDeCuenta.LicenciaNoVigente("licencia revocada"))

        assertEquals(EstadoDeSesion.Sin, vm.sesionEstado.value)
    }

    @Test
    fun `identidad_invalida cierra la sesion guardada`() {
        val vm = vmConSesion()

        vm.manejarErrorDeCuenta(ErrorDeCuenta.IdentidadInvalida("datos corruptos"))

        assertEquals(EstadoDeSesion.Sin, vm.sesionEstado.value)
    }

    /**
     * El test que más importa de la tarea (ver brief): un backend caído NO es un rechazo de
     * identidad. Si esto cerrara la sesión, la persona quedaría afuera -la pantalla de entrada
     * tampoco funciona sin backend- Y ENCIMA sin la sesión que tenía.
     */
    @Test
    fun `backend_no_disponible NO cierra una sesion guardada, solo avisa`() {
        val vm = vmConSesion()

        vm.manejarErrorDeCuenta(ErrorDeCuenta.BackendNoDisponible("no se pudo conectar con el gateway"))

        assertEquals("la sesion tiene que seguir viva", EstadoDeSesion.Con("a@b.co"), vm.sesionEstado.value)
        assertEquals("no se pudo conectar con el gateway", vm.aviso.value)
        assertEquals(EstadoDeEntrada.Adentro, estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
    }

    @Test
    fun `backend_no_disponible sin sesion previa deja Entrada con aviso, no crashea`() {
        val vm = vmSinSesion()

        vm.manejarErrorDeCuenta(ErrorDeCuenta.BackendNoDisponible("no se pudo conectar con el gateway"))

        assertEquals(EstadoDeSesion.Sin, vm.sesionEstado.value)
        assertEquals(
            EstadoDeEntrada.Entrada("no se pudo conectar con el gateway"),
            estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value),
        )
    }

    @Test
    fun `limpiarAviso descarta el aviso sin tocar la sesion`() {
        val vm = vmConSesion()
        vm.manejarErrorDeCuenta(ErrorDeCuenta.BackendNoDisponible("no se pudo conectar"))

        vm.limpiarAviso()

        assertEquals(null, vm.aviso.value)
        assertEquals(EstadoDeSesion.Con("a@b.co"), vm.sesionEstado.value)
    }

    @Test
    fun `registro exitoso desde la entrada termina Adentro`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"A_anon"}""")) // /v1/cuenta/registrar
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar

        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore(DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        val deviceAuth = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))
        val sesion = SesionDePersona(client, store)
        val cuentaApi = CuentaApi(
            baseUrl = { gw.url("/").toString().trimEnd('/') },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesion,
            http = OkHttpClient(),
        )
        val account = AccountManager(
            client = client,
            deviceAuth = deviceAuth,
            store = store,
            magisLink = MagisLinkClient(baseUrl = { "http://unused.invalid" }, accountId = { null }),
            cuentaApi = cuentaApi,
            sesion = sesion,
            onAccountSwitched = {},
            onLocalWipe = {},
        )
        val vm = EntradaViewModel(sesion, account)
        assertEquals(EstadoDeEntrada.Entrada(null), estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))

        vm.account.registrar("a@b.co", "secret12", "LIC-1")

        assertEquals(EstadoDeSesion.Con("a@b.co"), vm.sesionEstado.value)
        assertEquals(EstadoDeEntrada.Adentro, estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `licencia invalida durante el registro deja el estado en Entrada`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"detail":{"codigo":"licencia_invalida","mensaje":"el codigo no existe"}}"""),
        )

        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore(DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        val deviceAuth = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))
        val sesion = SesionDePersona(client, store)
        val cuentaApi = CuentaApi(
            baseUrl = { gw.url("/").toString().trimEnd('/') },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesion,
            http = OkHttpClient(),
        )
        val account = AccountManager(
            client = client,
            deviceAuth = deviceAuth,
            store = store,
            magisLink = MagisLinkClient(baseUrl = { "http://unused.invalid" }, accountId = { null }),
            cuentaApi = cuentaApi,
            sesion = sesion,
            onAccountSwitched = {},
            onLocalWipe = {},
        )
        val vm = EntradaViewModel(sesion, account)

        var threw = false
        try {
            vm.account.registrar("a@b.co", "secret12", "LIC-MALA")
        } catch (e: com.arkiv.player.pocketbase.AccountException) {
            threw = true
        }

        assertTrue("una licencia invalida debe rechazar el registro", threw)
        assertEquals(EstadoDeEntrada.Entrada(null), estadoDeEntrada(vm.sesionEstado.value, vm.aviso.value))
        pb.shutdown(); gw.shutdown()
    }
}
