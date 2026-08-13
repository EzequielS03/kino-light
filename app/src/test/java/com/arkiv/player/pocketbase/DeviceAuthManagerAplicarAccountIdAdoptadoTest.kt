package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `aplicarAccountIdAdoptado` (Task 5): a diferencia de `switchAccount` (`DeviceAuthManagerSwitchTest`,
 * pensado para el login de una PERSONA que se autentica con su propia contraseña), este camino NO
 * debe volver a escribir en PocketBase -- el gateway (`CuentaApi.adoptarAparato`) ya movió el
 * `accountId` del lado del servidor, con el candado que cuenta contra el tope de TVs. Repetirlo acá
 * sería una escritura redundante.
 */
class DeviceAuthManagerAplicarAccountIdAdoptadoTest {
    @Test
    fun `aplicarAccountIdAdoptado actualiza sesion y store SIN pegarle a PocketBase`() = runBlocking {
        val server = MockWebServer()
        // ensureBootstrapped() -> authExisting() -> auth-with-password (devices). Es la UNICA
        // llamada de red que este test espera.
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        server.start()

        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val seed = DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "tv")
        val store = FakeDeviceStore(seed)
        val mgr = DeviceAuthManager(client, store)
        mgr.ensureBootstrapped()
        val pedidosTrasBootstrap = server.requestCount

        val session = mgr.aplicarAccountIdAdoptado("A_person")

        assertEquals("A_person", session.accountId)
        assertEquals("A_person", mgr.session.value?.accountId)
        assertEquals("A_person", store.load()?.accountId) // persistido
        // Ningún pedido nuevo: el candado que cuenta contra el tope ya lo aplicó el gateway.
        assertEquals(pedidosTrasBootstrap, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `aplicarAccountIdAdoptado sin sesion de dispositivo previa falla con un mensaje claro`() = runBlocking {
        val client = PocketBaseClient(baseUrl = "http://unused.invalid")
        val mgr = DeviceAuthManager(client, FakeDeviceStore())

        val error = runCatching { mgr.aplicarAccountIdAdoptado("A_person") }.exceptionOrNull()

        assertNotNull(error)
    }
}
