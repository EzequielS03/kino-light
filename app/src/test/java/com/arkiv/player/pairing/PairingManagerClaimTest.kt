package com.arkiv.player.pairing

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.pocketbase.SesionDePersona
import com.arkiv.player.pocketbase.cuentaApiSinUsarParaBootstrap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `PairingManager.claimFromQr` (rol celu), el corazón de la Task 5: antes de esta tarea, este
 * método FABRICABA un device TV nuevo con un `createRecord` directo a PocketBase -- el gateway
 * nunca se enteraba, así que el tope de "1 TV por licencia" no se aplicaba. Ahora descifra el
 * deviceToken que la propia TV dejó al crear el pair_request y lo adopta vía
 * [CuentaApi.adoptarAparato]: es el único camino que cuenta contra `maxTvs`, con el candado de
 * Redis del lado del servidor.
 *
 * `PairingManager` recibe la config de gateway como lambdas (no `SettingsStore` directo) a
 * propósito: `SettingsStore` pide un Context real y este módulo no tiene Robolectric (ver
 * `GatewayConfigPrecedenceTest`) -- sin ese cambio, `claimFromQr` sería imposible de probar acá.
 */
class PairingManagerClaimTest {
    private lateinit var server: MockWebServer
    private lateinit var deviceStore: FakeDeviceStore
    private lateinit var deviceAuth: DeviceAuthManager
    private lateinit var sesion: SesionDePersona
    private lateinit var cuentaApi: CuentaApi
    private var tvLinked: Boolean? = null

    private val codigoPareo = "CODIGO-DE-PRUEBA-123"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val baseUrl = server.url("/").toString().trimEnd('/')

        // El celu ya tiene identidad de aparato Y sesión de persona (post Task 4: sin sesión de
        // persona el gate de MainActivity ni deja llegar al escáner) -- se seedea directo en el
        // store para no gastar pedidos de red en cosas que no son lo que este test verifica.
        deviceStore = FakeDeviceStore(
            DeviceIdentity("acc-persona", "dev-celu", "dev-celu@arkiv.local", "pw12345678", "phone"),
        ).apply {
            savePersonToken("person-tok")
            savePersonEmail("persona@x.co")
        }
        val client = PocketBaseClient(baseUrl = baseUrl)
        deviceAuth = DeviceAuthManager(client, deviceStore, cuentaApiSinUsarParaBootstrap(client, deviceStore))
        sesion = SesionDePersona(client, deviceStore)
        cuentaApi = CuentaApi(
            baseUrl = { baseUrl },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesion,
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun pairingManager(): PairingManager = PairingManager(
        client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/')),
        realtime = PocketBaseRealtime(baseUrl = "http://unused.invalid"), // rol celu no usa realtime
        deviceAuth = deviceAuth,
        cuentaApi = cuentaApi,
        sesion = sesion,
        gatewayUrl = { "https://gw.example" },
        applySyncedGatewayConfig = { _ -> false }, // solo lo usa el rol TV
        setTvLinked = { tvLinked = it },
        scope = CoroutineScope(Job()),
    )

    private fun qrCon(code: String = codigoPareo) = QrPayloadCodec.encode(QrPayload("http://db.example", code))

    /** `claimFromQr` arranca con `deviceAuth.ensureBootstrapped()`: como el device ya tiene
     *  identidad seedeada en el store, eso dispara UN auth-with-password contra `devices` --
     *  tiene que ser la PRIMERA respuesta encolada en cada test. */
    private fun encolarBootstrapDelCelu() {
        server.enqueue(MockResponse().setBody("""{"token":"celu-dev-tok","record":{"id":"celu-devrec"}}"""))
    }

    /** Encola la respuesta de `GET pair_requests` con el deviceToken de la TV cifrado, como lo
     *  deja `startTvPairing` al crear la fila. */
    private fun encolarPairRequestPendiente(
        id: String = "req-1",
        tvDeviceToken: String = "tv-dev-tok",
        expiresAt: String = "",
        code: String = codigoPareo,
    ) {
        val payloadCipher = PairCrypto.encrypt(payloadDeviceTokenTv(tvDeviceToken), code)
        val item = JSONObject(
            mapOf(
                "id" to id,
                "codeHash" to PairCrypto.codeHash(code),
                "status" to "pending",
                "expiresAt" to expiresAt,
                "payload" to payloadCipher,
            ),
        )
        val itemsArray = org.json.JSONArray().put(item)
        val body = JSONObject().put("items", itemsArray).put("totalPages", 1)
        server.enqueue(MockResponse().setBody(body.toString()))
    }

    // --- camino feliz: adopta vía el gateway, nunca crea un device a mano --------------------

    @Test
    fun `claimFromQr descifra el deviceToken de la TV y lo adopta via CuentaApi, sin crear ningun device`() = runBlocking {
        encolarBootstrapDelCelu()
        encolarPairRequestPendiente(tvDeviceToken = "tv-dev-tok")
        server.enqueue(MockResponse().setBody("""{"kind":"tv","usados":1,"tope":1,"yaEra":false}""")) // adoptarAparato
        server.enqueue(MockResponse().setBody("""{"id":"req-1"}""")) // updateRecord de respuesta

        val pairing = pairingManager()
        val ok = pairing.claimFromQr(qrCon())

        assertTrue(ok)
        assertTrue(pairing.state.value is PairingState.Paired)
        assertEquals(true, tvLinked)

        // request 1: bootstrap del device del celu (auth-with-password)
        val r1 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(r1.path!!.contains("auth-with-password"))
        // request 2: listRecords de pair_requests
        val r2 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(r2.path!!.contains("/collections/pair_requests/records"))
        assertEquals("GET", r2.method)
        // request 3: POST /v1/cuenta/aparatos -- el UNICO camino que cuenta contra el tope
        val r3 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/cuenta/aparatos", r3.path)
        assertEquals("POST", r3.method)
        assertEquals("tv-dev-tok", JSONObject(r3.body.readUtf8()).getString("deviceToken"))
        // request 4: PATCH del pair_request con la respuesta para la TV
        val r4 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(r4.path!!.endsWith("/collections/pair_requests/records/req-1"))
        assertEquals("PATCH", r4.method)

        // Mutación 1 (brief): NO debe haber un 5to pedido creando un device a mano.
        assertNull("no debería haber más pedidos de red", server.takeRequest(300, TimeUnit.MILLISECONDS))

        // La respuesta que le queda a la TV viaja cifrada -- se descifra con el MISMO code del QR.
        val patchBody = JSONObject(r4.body.readUtf8())
        assertEquals("claimed", patchBody.getString("status"))
        val respuesta = interpretarRespuestaDePareo(
            JSONObject(PairCrypto.decrypt(patchBody.getString("payload"), codigoPareo)),
        ) as RespuestaDePareo.Ok
        assertEquals("acc-persona", respuesta.accountId)
        assertEquals("person-tok", respuesta.personToken)
        assertEquals("persona@x.co", respuesta.personEmail)
        assertEquals("https://gw.example", respuesta.gatewayUrl)
    }

    // --- tope_alcanzado: NO se traga en silencio ----------------------------------------------

    @Test
    fun `tope_alcanzado NO se traga -- se muestra en el celu y se le avisa a la TV`() = runBlocking {
        encolarBootstrapDelCelu()
        encolarPairRequestPendiente(tvDeviceToken = "tv-dev-tok")
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"tope_alcanzado","mensaje":"ya tenes 1 de 1 aparatos de tipo tv"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"id":"req-1"}""")) // PATCH de error hacia la TV

        val pairing = pairingManager()
        val ok = pairing.claimFromQr(qrCon())

        assertEquals(false, ok)
        val estado = pairing.state.value
        assertTrue("esperaba TopeAlcanzado, dio $estado", estado is PairingState.TopeAlcanzado)
        assertTrue((estado as PairingState.TopeAlcanzado).msg.contains("Mis aparatos"))
        assertNull("un tope_alcanzado no marca tvLinked", tvLinked)

        server.takeRequest(2, TimeUnit.SECONDS) // bootstrap
        server.takeRequest(2, TimeUnit.SECONDS) // listRecords
        server.takeRequest(2, TimeUnit.SECONDS) // adoptarAparato (403)
        val r4 = server.takeRequest(2, TimeUnit.SECONDS)!! // PATCH informando el error a la TV
        assertEquals("PATCH", r4.method)
        val patchBody = JSONObject(r4.body.readUtf8())
        assertEquals("claimed", patchBody.getString("status")) // "claimed" = el celu ya contestó
        val respuesta = interpretarRespuestaDePareo(
            JSONObject(PairCrypto.decrypt(patchBody.getString("payload"), codigoPareo)),
        ) as RespuestaDePareo.Falla
        assertEquals("tope_alcanzado", respuesta.codigo)
    }

    // --- candado_ocupado: se reintenta solo y puede terminar en exito ------------------------

    @Test
    fun `candado_ocupado se reintenta solo y termina pareando`() = runBlocking {
        encolarBootstrapDelCelu()
        encolarPairRequestPendiente(tvDeviceToken = "tv-dev-tok")
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":{"codigo":"candado_ocupado","mensaje":"reintenta"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"kind":"tv","usados":1,"tope":1,"yaEra":false}"""))
        server.enqueue(MockResponse().setBody("""{"id":"req-1"}"""))

        val pairing = pairingManager()
        val ok = pairing.claimFromQr(qrCon())

        assertTrue(ok)
        assertTrue(pairing.state.value is PairingState.Paired)
    }

    // --- expirado: no llega a adoptar nada -----------------------------------------------------

    @Test
    fun `un pair_request expirado no intenta adoptar nada`() = runBlocking {
        encolarBootstrapDelCelu()
        encolarPairRequestPendiente(tvDeviceToken = "tv-dev-tok", expiresAt = "2000-01-01T00:00:00Z")

        val pairing = pairingManager()
        val ok = pairing.claimFromQr(qrCon())

        assertEquals(false, ok)
        assertEquals(
            "El código expiró",
            (pairing.state.value as PairingState.Error).msg,
        )
        // bootstrap + listRecords, nada más -- ni adoptarAparato ni ningún PATCH.
        server.takeRequest(2, TimeUnit.SECONDS)
        server.takeRequest(2, TimeUnit.SECONDS)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    // --- QR de una version vieja de la TV (sin payload) ----------------------------------------

    @Test
    fun `un pair_request sin payload (TV vieja) da error sin crashear`() = runBlocking {
        encolarBootstrapDelCelu()
        val item = JSONObject(mapOf("id" to "req-1", "expiresAt" to "", "payload" to ""))
        val itemsArray = org.json.JSONArray().put(item)
        server.enqueue(MockResponse().setBody(JSONObject().put("items", itemsArray).put("totalPages", 1).toString()))

        val pairing = pairingManager()
        val ok = pairing.claimFromQr(qrCon())

        assertEquals(false, ok)
        assertTrue(pairing.state.value is PairingState.Error)
    }
}
