package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AccountManager.registrar` (Task 3): `crearPocketBase()` escribía directo en la colección
 * `users` con el token del device — eso ya no funciona (`users.createRule` está en `null`), así
 * que el registro tiene que pasar por [CuentaApi.registrar] con una licencia.
 *
 * Dos `MockWebServer` separados -uno hace de PocketBase, el otro del gateway- porque en producción
 * son dos hosts distintos, y sobre todo porque así se puede tirar SOLO el gateway
 * ([backend no disponible durante el registro no deja la sesion a medias]) sin que el bootstrap del
 * device (que sigue yendo contra PocketBase) se vea afectado.
 */
class AccountManagerRegistroTest {
    private fun identidadSemilla() =
        DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone")

    /** `registrar` no toca Magis en absoluto -se vincula aparte y DESPUÉS, con
     *  `vincularMagisEnviarCodigo`/`vincularMagisConfirmar`-, así que esta URL nunca debería
     *  llamarse: si algo la invocara por error, esto tira en vez de pasar en silencio. */
    private fun magisLinkSinUsar() =
        MagisLinkClient(baseUrl = { "http://unused.invalid" }, apiKey = { "LLAVE" }, accountId = { null })

    private fun cuentaApi(gw: MockWebServer, sesion: SesionDePersona, deviceAuth: DeviceAuthManager) = CuentaApi(
        baseUrl = { gw.url("/").toString().trimEnd('/') },
        apiKey = { "LLAVE" },
        deviceToken = { deviceAuth.session.value?.token },
        sesion = sesion,
        http = OkHttpClient(),
    )

    /** Agrupa las piezas que casi todos los tests de acá arman igual (mismo PocketBaseClient
     *  apuntado al server de PocketBase, mismo device semilla), para no repetirlas en cada test. */
    private inner class Escenario(pb: MockWebServer) {
        val client = PocketBaseClient(baseUrl = pb.url("/").toString().trimEnd('/'))
        val store = FakeDeviceStore(identidadSemilla())
        val deviceAuth = DeviceAuthManager(client, store)
        val sesion = SesionDePersona(client, store)
    }

    @Test
    fun `registrar llama al gateway, nunca a PocketBase createRecord`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"A_anon"}""")) // /v1/cuenta/registrar
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar -> auth-with-password
        val e = Escenario(pb)
        val mgr = AccountManager(
            e.client, e.deviceAuth, e.store, magisLinkSinUsar(), cuentaApi(gw, e.sesion, e.deviceAuth), e.sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.registrar("a@b.co", "secret12", "LIC-1")

        val bootstrapReq = pb.takeRequest()
        assertTrue("bootstrap: auth-with-password", bootstrapReq.path!!.contains("auth-with-password"))
        val altaReq = gw.takeRequest()
        assertEquals("/v1/cuenta/registrar", altaReq.path)
        val sesionReq = pb.takeRequest()
        assertTrue("sesion.iniciar: auth-with-password, no createRecord", sesionReq.path!!.contains("auth-with-password"))
        assertFalse("jamas un POST a /records (createRecord)", sesionReq.path!!.contains("/records"))
        assertEquals("un solo pedido al gateway, sin reintentos", 1, gw.requestCount)
        assertEquals("bootstrap + sesion.iniciar, nada mas contra PocketBase", 2, pb.requestCount)
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `registrar ok deja la sesion guardada`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(MockResponse().setResponseCode(201).setBody("""{"userId":"u1","accountId":"A_anon"}""")) // registrar
        pb.enqueue(MockResponse().setBody("""{"token":"ptok","record":{"id":"usr-1"}}""")) // sesion.iniciar
        val e = Escenario(pb)
        val mgr = AccountManager(
            e.client, e.deviceAuth, e.store, magisLinkSinUsar(), cuentaApi(gw, e.sesion, e.deviceAuth), e.sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        mgr.registrar("a@b.co", "secret12", "LIC-1")

        assertEquals(AccountState.Conectado("a@b.co", false), mgr.state.value)
        assertEquals(EstadoDeSesion.Con("a@b.co"), e.sesion.estado.value)
        assertEquals("ptok", e.sesion.token())
        assertEquals("a@b.co", e.store.personEmail())
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `licencia invalida no deja nada creado - ni cuenta, ni sesion, ni Magis`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        gw.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"detail":{"codigo":"licencia_invalida","mensaje":"el codigo no existe"}}"""),
        )
        val e = Escenario(pb)
        val mgr = AccountManager(
            e.client, e.deviceAuth, e.store, magisLinkSinUsar(), cuentaApi(gw, e.sesion, e.deviceAuth), e.sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var msg: String? = null
        try { mgr.registrar("a@b.co", "secret12", "LIC-MALA") } catch (ex: AccountException) { msg = ex.message }

        assertEquals("el codigo no existe", msg)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertEquals(EstadoDeSesion.Sin, e.sesion.estado.value)
        assertNull(e.store.personToken())
        assertNull(e.store.personEmail())
        assertEquals("solo el bootstrap: sesion.iniciar nunca se llega a intentar", 1, pb.requestCount)
        assertEquals(1, gw.requestCount)   // el intento que fallo, sin reintentos
        pb.shutdown(); gw.shutdown()
    }

    @Test
    fun `backend no disponible durante el registro no deja la sesion a medias`() = runBlocking {
        val pb = MockWebServer().also { it.start() }
        val gw = MockWebServer().also { it.start() }
        val gwUrl = gw.url("/").toString().trimEnd('/')
        gw.shutdown()   // a partir de aca, cualquier pedido al gateway revienta con IOException (sin red)
        pb.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        val e = Escenario(pb)
        val gwMuerto = CuentaApi(
            baseUrl = { gwUrl },
            apiKey = { "LLAVE" },
            deviceToken = { e.deviceAuth.session.value?.token },
            sesion = e.sesion,
            http = OkHttpClient(),
        )
        val mgr = AccountManager(
            e.client, e.deviceAuth, e.store, magisLinkSinUsar(), gwMuerto, e.sesion,
            onAccountSwitched = {}, onLocalWipe = {},
        )

        var threw = false
        try { mgr.registrar("a@b.co", "secret12", "LIC-1") } catch (ex: AccountException) { threw = true }

        assertTrue("debia lanzar AccountException, no colgarse ni tirar otro tipo", threw)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertEquals(EstadoDeSesion.Sin, e.sesion.estado.value)
        assertNull(e.store.personToken())
        pb.shutdown()
    }
}
