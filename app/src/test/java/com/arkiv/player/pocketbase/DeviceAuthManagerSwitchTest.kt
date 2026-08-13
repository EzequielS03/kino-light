package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthManagerSwitchTest {
    @Test
    fun switchAccount_adoptaElAccountIdEnSesionStoreYServidor() = runBlocking {
        val server = MockWebServer()
        // ensureBootstrapped() -> authExisting() -> auth-with-password (devices)
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        // switchAccount() -> updateRecord (PATCH devices/devrec)
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}"""))
        server.start()

        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val seed = DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone")
        val store = FakeDeviceStore(seed)
        // Identidad ya seedeada -> ensureBootstrapped() usa authExisting(), nunca
        // createNewAccount(): este CuentaApi apuntado a un host inalcanzable jamás se llama
        // (Task 7, altaAparato es SOLO del alta anónima).
        val mgr = DeviceAuthManager(client, store, cuentaApiSinUsarParaBootstrap(client, store))

        mgr.ensureBootstrapped()
        val session = mgr.switchAccount("A_person")

        assertEquals("A_person", session.accountId)
        assertEquals("A_person", mgr.session.value?.accountId)
        assertEquals("A_person", store.load()?.accountId)     // persistido
        server.takeRequest() // auth
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/collections/devices/records/devrec", patch.path)
        server.shutdown()
    }
}
