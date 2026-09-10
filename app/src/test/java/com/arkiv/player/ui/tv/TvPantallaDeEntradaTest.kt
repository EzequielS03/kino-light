package com.arkiv.player.ui.tv

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.FakeDeviceStore
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
 * [entrarDesdeTv] se prueba igual que `EntradaViewModelTest`/`AccountManagerTest` -con
 * `MockWebServer`, sin mocks-: el objetivo no es reprobar lo que `AccountManager` ya prueba de
 * sobra, sino confirmar que el camino nuevo (la pantalla de la TV) llama al método que corresponde
 * y no reimplementa nada por su cuenta. Hasta Task 7 esta clase también probaba `normalizarLicencia`
 * (pura) y el camino de registro de [entrarDesdeTv]; se sacaron junto con el alta de cuentas nuevas
 * con licencia (poda "Arkiv Light").
 */
class TvPantallaDeEntradaTest {

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
            magisSession = com.arkiv.player.data.magis.MagisSession(
                com.arkiv.player.data.magis.FakePortalClient(),
                com.arkiv.player.data.magis.FakeCredentialStore(),
            ),
            cuentaApi = cuentaApi,
            sesion = sesion,
            onAccountSwitched = {},
            onLocalWipe = {},
        )
        return account to sesion
    }

    @Test
    fun `entrarDesdeTv llama a login`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        pb.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        // POST /v1/cuenta/entrar: desde el 2026-08-14 el login adopta el aparato por ahi y no por
        // /aparatos, que en un aparato nuevo es un 401 eterno (ver AccountManager.login).
        gw.enqueue(MockResponse().setBody("""{"userId":"usr-1","accountId":"A1","kind":"tv","usados":1,"tope":1,"yaEra":false,"desvinculado":null}"""))
        gw.enqueue(MockResponse().setBody("""{"linked":false}""")) // magisVinculadoSeguro
        val (account, _) = armarCuenta(pb, gw)

        entrarDesdeTv(account, "a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co", false), account.state.value)
        assertEquals(
            "el gateway tiene que ver la adopcion de aparato del login",
            "/v1/cuenta/entrar",
            gw.takeRequest().path,
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
            entrarDesdeTv(account, "a@b.co", "secret12")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertTrue("mensaje: $msg", msg?.contains("Mis aparatos") == true)
        pb.shutdown(); gw.shutdown()
    }
}
