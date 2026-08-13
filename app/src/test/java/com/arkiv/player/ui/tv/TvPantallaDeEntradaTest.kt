package com.arkiv.player.ui.tv

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
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
 * Task 9: entrar desde la propia TV, sin pareo con un celular.
 *
 * [normalizarLicencia] es pura, se prueba directo. [entrarDesdeTv] se prueba igual que
 * `EntradaViewModelTest`/`AccountManagerTest` -con `MockWebServer`, sin mocks-: el objetivo no es
 * reprobar lo que `AccountManager` ya prueba de sobra, sino confirmar que el camino nuevo (la
 * pantalla de la TV) llama al método que corresponde y no reimplementa nada por su cuenta.
 */
class TvPantallaDeEntradaTest {

    // --- normalizarLicencia ---

    @Test fun `normalizarLicencia deja igual un codigo que ya tiene guiones`() {
        assertEquals("GS9W-SH8C-YC6Y", normalizarLicencia("GS9W-SH8C-YC6Y"))
    }

    @Test fun `normalizarLicencia agrega los guiones cuando no los tipearon`() {
        assertEquals("GS9W-SH8C-YC6Y", normalizarLicencia("GS9WSH8CYC6Y"))
    }

    @Test fun `normalizarLicencia sube a mayusculas`() {
        assertEquals("GS9W-SH8C-YC6Y", normalizarLicencia("gs9w-sh8c-yc6y"))
    }

    @Test fun `normalizarLicencia saca espacios sueltos`() {
        assertEquals("GS9W-SH8C-YC6Y", normalizarLicencia("gs9w sh8c yc6y"))
    }

    @Test fun `normalizarLicencia con vacio da vacio, no rompe`() {
        assertEquals("", normalizarLicencia(""))
    }

    // --- entrarDesdeTv ---

    private fun devStore() = FakeDeviceStore(DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "tv"))

    private fun armarCuenta(pb: MockWebServer, gw: MockWebServer): Pair<AccountManager, SesionDePersona> {
        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = devStore()
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
            magisLink = MagisLinkClient(baseUrl = { gw.url("/").toString().trimEnd('/') }),
            cuentaApi = cuentaApi,
            sesion = sesion,
            onAccountSwitched = {},
            onLocalWipe = {},
        )
        return account to sesion
    }

    @Test
    fun `entrarDesdeTv con registrando=false llama a login, no a registrar`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        pb.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        gw.enqueue(MockResponse().setBody("""{"kind":"tv","usados":1,"tope":1,"yaEra":false}""")) // POST /v1/cuenta/aparatos
        gw.enqueue(MockResponse().setBody("""{"linked":false}""")) // magisVinculadoSeguro
        val (account, _) = armarCuenta(pb, gw)

        entrarDesdeTv(account, "a@b.co", "secret12", licencia = "", registrando = false)

        assertEquals(AccountState.Conectado("a@b.co", false), account.state.value)
        assertEquals(
            "el gateway tiene que ver la adopcion de aparato del login, no /v1/cuenta/registrar",
            "/v1/cuenta/aparatos",
            gw.takeRequest().path,
        )
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `entrarDesdeTv con registrando=true llama a registrar, no a login, y normaliza la licencia`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"A_anon"}""")) // /v1/cuenta/registrar
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        val (account, _) = armarCuenta(pb, gw)

        entrarDesdeTv(account, "a@b.co", "secret12", licencia = "gs9w-sh8c-yc6y", registrando = true)

        assertEquals(AccountState.Conectado("a@b.co", false), account.state.value)
        val pedido = gw.takeRequest()
        assertEquals("/v1/cuenta/registrar", pedido.path)
        assertTrue(
            "el cuerpo debe llevar la licencia YA normalizada, no la que tipeo la persona",
            pedido.body.readUtf8().contains("GS9W-SH8C-YC6Y"),
        )
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `tope_alcanzado durante el login desde la TV dice que hacer, no un error tecnico`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        pb.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        gw.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":{"codigo":"tope_alcanzado","mensaje":"limite de aparatos"}}"""),
        )
        val (account, _) = armarCuenta(pb, gw)

        var msg: String? = null
        try {
            entrarDesdeTv(account, "a@b.co", "secret12", licencia = "", registrando = false)
        } catch (e: AccountException) {
            msg = e.message
        }

        assertTrue("mensaje: $msg", msg?.contains("Mis aparatos") == true)
        pb.shutdown(); gw.shutdown()
    }
}
