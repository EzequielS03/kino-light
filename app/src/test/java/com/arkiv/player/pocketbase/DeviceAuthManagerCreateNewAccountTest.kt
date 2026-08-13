package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DeviceAuthManager.createNewAccount` (Task 7): el alta anónima de un aparato con store vacío
 * ya no escribe `devices` directo contra PocketBase (`devices.createRule` cerrada) -- pasa por
 * [CuentaApi.altaAparato], que lo crea con credenciales de admin del lado del gateway.
 *
 * Ningún test existente de este archivo (`DeviceAuthManagerSwitchTest`,
 * `DeviceAuthManagerAplicarAccountIdAdoptadoTest`, y los de `AccountManager`/`PairingManager`)
 * ejercitaba este camino: todos seedean una identidad ya existente en el `store`, así que
 * `ensureBootstrapped()` siempre tomaba `authExisting()`, nunca `createNewAccount()`. Dos
 * `MockWebServer` -uno de PocketBase, otro del gateway-, mismo criterio que
 * `AccountManagerRegistroTest`.
 */
class DeviceAuthManagerCreateNewAccountTest {
    private lateinit var pb: MockWebServer
    private lateinit var gw: MockWebServer

    @After
    fun tearDown() {
        runCatching { pb.shutdown() }
        runCatching { gw.shutdown() }
    }

    private fun cuentaApi(client: PocketBaseClient, store: DeviceStore) = CuentaApi(
        baseUrl = { gw.url("/").toString().trimEnd('/') },
        apiKey = { "LLAVE" },
        // altaAparato no manda ninguna credencial propia (ver su KDoc): este provider nunca
        // se llega a invocar.
        deviceToken = { null },
        sesion = SesionDePersona(client, store),
        http = OkHttpClient(),
    )

    @Test
    fun `store vacio da de alta contra el gateway ANTES de auth-with-password, y guarda la sesion`() = runBlocking {
        pb = MockWebServer().also { it.start() }
        gw = MockWebServer().also { it.start() }
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"dev-nuevo","accountId":"lo-que-sea"}"""))
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))

        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore()
        val mgr = DeviceAuthManager(client, store, cuentaApi(client, store))

        val session = mgr.ensureBootstrapped()

        assertNotNull("bootstrap debe terminar con sesion", session)
        assertEquals("dtok", session!!.token)
        assertEquals("devrec", session.recordId)

        val altaReq = gw.takeRequest()
        assertEquals("POST", altaReq.method)
        assertEquals("/v1/cuenta/aparatos/alta", altaReq.path)
        val body = JSONObject(altaReq.body.readUtf8())
        assertEquals("phone", body.getString("kind"))
        assertTrue("accountId generado local, no vacio", body.getString("accountId").isNotBlank())
        // El accountId de la sesion es el que el APARATO generó localmente -Task 7 no cambia
        // de dónde sale (ver DeviceIdentityFactory)-, no el que el gateway devolvió en la
        // respuesta (acá a propósito distinto, para probar que no se confunden).
        assertEquals(body.getString("accountId"), session.accountId)
        assertEquals(body.getString("accountId"), store.load()?.accountId)

        // Un solo pedido contra PocketBase (auth-with-password): jamás un POST a
        // /collections/devices/records -- ese create directo es justo lo que Task 7 reemplaza.
        val authReq = pb.takeRequest()
        assertTrue(authReq.path!!.contains("auth-with-password"))
        assertEquals(1, pb.requestCount)
    }

    @Test
    fun `si el gateway rechaza el alta, no se guarda ninguna identidad local`() = runBlocking {
        gw = MockWebServer().also { it.start() }
        pb = MockWebServer().also { it.start() } // no se llega a usar
        gw.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"detail":{"codigo":"backend_no_disponible","mensaje":"caido"}}"""),
        )

        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore()
        val mgr = DeviceAuthManager(client, store, cuentaApi(client, store))

        val session = mgr.ensureBootstrapped()

        assertNull("offline-first: sin sesion, no crashea (Log.w + null, no propaga)", session)
        assertNull("sin alta exitosa, no debe quedar identidad local a medias", store.load())
        assertEquals("nunca se llega a auth-with-password si el alta fallo antes", 0, pb.requestCount)
    }

    @Test
    fun `una TV se da de alta con kind tv, no como celular`() = runBlocking {
        // El gateway cuenta el cupo de la licencia por este campo. Darse de alta siempre como
        // "phone" hacia que una TV recien instalada consumiera el cupo de CELULARES y el pareo
        // fallara con "tope alcanzado" sin que hubiera ninguna otra TV. Verificado en el Fire TV.
        pb = MockWebServer().also { it.start() }
        gw = MockWebServer().also { it.start() }
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"dev-tv","accountId":"x"}"""))
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))

        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore()
        val mgr = DeviceAuthManager(client, store, cuentaApi(client, store), esTv = { true })

        assertNotNull(mgr.ensureBootstrapped())

        val body = JSONObject(gw.takeRequest().body.readUtf8())
        assertEquals("tv", body.getString("kind"))
        assertEquals("tv", store.load()?.kind)
    }
}
