package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El `accountId` del record en el servidor manda sobre el guardado en el dispositivo.
 *
 * Cuando el celu adopta un TV, el GATEWAY escribe el `accountId` nuevo en el record del TV y el TV
 * se entera por un único evento SSE (`PairingManager`). Si ese evento se pierde -y se perdía cada
 * ~2 min, porque Cloudflare cortaba el stream inactivo-, el TV se queda con su `accountId` anónimo
 * viejo mientras el servidor ya tiene el nuevo. A partir de ahí la regla
 * `accountId = @request.auth.accountId` rechaza TODAS las filas que empuja el sync, y
 * `CloudSyncManager.pushRows` las pone en cuarentena y avanza el cursor por encima: la biblioteca
 * se pierde en silencio (1.095 `episodes` + 307 `progress` rechazados en 25 h de logs reales).
 *
 * Reautenticar es el momento natural para reconciliar: la respuesta de auth YA trae el record.
 */
class DeviceAuthManagerReconciliacionAccountIdTest {

    @Test
    fun authExisting_adoptaElAccountIdDelServidorCuandoDifiereDelGuardado() = runBlocking {
        val server = MockWebServer()
        // El gateway ya movió el aparato a "A_persona"; el store local se quedó en "A_anonimo".
        server.enqueue(
            MockResponse().setBody(
                """{"token":"dtok","record":{"id":"devrec","accountId":"A_persona"}}""",
            ),
        )
        server.start()

        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore(
            DeviceIdentity("A_anonimo", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"),
        )
        val mgr = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))

        val session = mgr.ensureBootstrapped()

        assertEquals("A_persona", session?.accountId)
        assertEquals("A_persona", mgr.session.value?.accountId)
        // Persistido: si no, el próximo arranque sin red vuelve a empujar con el accountId viejo.
        assertEquals("A_persona", store.load()?.accountId)
        server.shutdown()
    }

    @Test
    fun authExisting_conservaElAccountIdGuardadoSiElServidorNoLoDevuelve() = runBlocking {
        val server = MockWebServer()
        // Un record sin `accountId` (o con el campo vacío) no debe borrar el que ya teníamos:
        // preferir un valor ausente por encima de uno bueno dejaría al aparato sin cuenta.
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        server.start()

        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore(
            DeviceIdentity("A_anonimo", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"),
        )
        val mgr = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))

        val session = mgr.ensureBootstrapped()

        assertEquals("A_anonimo", session?.accountId)
        assertEquals("A_anonimo", store.load()?.accountId)
        server.shutdown()
    }
}
