package com.arkiv.player.pairing

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.pocketbase.SesionDePersona
import com.arkiv.player.pocketbase.cuentaApiSinUsarParaBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PairingManager.startTvPairing` (rol TV, Task 5): antes de esta tarea la fila del pair_request
 * no llevaba ningún `payload` hasta que el celu la escaneaba; ahora la TV escribe SU PROPIO
 * deviceToken cifrado desde el vamos -- es una credencial, tiene que viajar por el mismo canal
 * cifrado que el resto, nunca en claro en la fila.
 *
 * Solo se prueba acá la creación del registro (lo único determinista sin abrir una conexión SSE
 * real): la escucha realtime que arranca después se lanza en background y no bloquea el retorno
 * de `startTvPairing`, así que el server se puede apagar apenas se verifica el `createRecord`
 * -- ver KDoc de la clase para más contexto.
 */
class PairingManagerTvPairingTest {

    private fun pairingManager(server: MockWebServer, deviceAuth: DeviceAuthManager): PairingManager {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = PocketBaseClient(baseUrl = baseUrl)
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(client, store)
        val cuentaApi = CuentaApi(
            baseUrl = { baseUrl },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesion,
            http = OkHttpClient(),
        )
        return PairingManager(
            client = client,
            realtime = PocketBaseRealtime(baseUrl = baseUrl),
            deviceAuth = deviceAuth,
            cuentaApi = cuentaApi,
            sesion = sesion,
            gatewayUrl = { "" },
            applySyncedGatewayConfig = { _ -> false },
            setTvLinked = { },
            scope = CoroutineScope(Job()),
        )
    }

    @Test
    fun `startTvPairing manda su PROPIO deviceToken cifrado, nunca en claro en la fila`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        // 1) bootstrap del device de la TV (auth-with-password, identidad seedeada)
        server.enqueue(MockResponse().setBody("""{"token":"tv-dev-tok","record":{"id":"tv-devrec"}}"""))
        // 2) createRecord del pair_request
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"req-tv-1"}"""))

        val deviceStore = FakeDeviceStore(
            DeviceIdentity("A_anon", "dev-tv", "dev-tv@arkiv.local", "pw12345678", "tv"),
        )
        val deviceClient = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val deviceAuth = DeviceAuthManager(deviceClient, deviceStore, cuentaApiSinUsarParaBootstrap(deviceClient, deviceStore))
        val pairing = pairingManager(server, deviceAuth)

        val qr = pairing.startTvPairing("Mi TV")

        assertTrue(qr != null)
        assertEquals(PairingState.WaitingScan, pairing.state.value)

        server.takeRequest() // bootstrap, ya verificado por DeviceAuthManagerSwitchTest-style tests
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertTrue(createReq.path!!.endsWith("/collections/pair_requests/records"))
        val body = JSONObject(createReq.body.readUtf8())
        assertEquals("pending", body.getString("status"))
        assertEquals("Mi TV", body.getString("tvName"))
        val payloadCipher = body.getString("payload")

        // El payload NO es el token en claro -- viaja cifrado.
        assertNotEquals("tv-dev-tok", payloadCipher)
        assertTrue("no debería aparecer el token en claro en ningún campo del create", !body.toString().contains("tv-dev-tok"))

        // Se descifra con el `code` embebido en el QR devuelto -- solo quien escaneó el QR puede leerlo.
        val code = QrPayloadCodec.decode(qr!!)!!.code
        val decrypted = JSONObject(PairCrypto.decrypt(payloadCipher, code))
        assertEquals("tv-dev-tok", deviceTokenDeTv(decrypted))

        // Apagar el server ahora: la suscripción realtime lanzada en background intentará
        // conectar a /api/realtime y fallará (conexión rechazada) -- PocketBaseRealtime ya la
        // maneja con reintento/backoff, no propaga la excepción a este test.
        server.shutdown()
    }
}
