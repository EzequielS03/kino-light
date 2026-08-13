package com.arkiv.player.pairing

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `adoptarConReintento` (Task 5): la parte "candado_ocupado -> reintentar solo, sin molestar a
 * nadie" del brief. Top-level y con [CuentaApi] pasado explícito para poder probarla contra
 * `MockWebServer` sin instanciar el resto del pareo (`PairingManager` necesita `SettingsStore`
 * o, tras el refactor de esta tarea, sus lambdas -- este helper no necesita ninguna).
 */
class AdoptarConReintentoTest {
    private lateinit var server: MockWebServer
    private lateinit var sesion: SesionDePersona

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val store = FakeDeviceStore().apply { savePersonToken("person-tok") }
        // Igual que en CuentaApiTest: sesion.token() lee directo del store, nunca habla con
        // este PocketBaseClient -- la URL es un relleno que jamás se llama.
        sesion = SesionDePersona(PocketBaseClient(baseUrl = "http://unused.invalid"), store)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun cuentaApi(): CuentaApi = CuentaApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        deviceToken = { "device-tok" },
        sesion = sesion,
        http = OkHttpClient(),
    )

    @Test
    fun `un candado_ocupado se reintenta solo y termina en exito`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":{"codigo":"candado_ocupado","mensaje":"reintenta"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"kind":"tv","usados":1,"tope":1,"yaEra":false}"""))

        val r = adoptarConReintento(cuentaApi(), "tv-tok", esperaMs = 1L)

        assertEquals("tv", r.kind)
        assertEquals(2, server.requestCount) // el 1er intento choco con el candado, el 2do entro
    }

    @Test
    fun `un tope_alcanzado NO se reintenta -- se relanza de inmediato, sin gastar tiempo`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail":{"codigo":"tope_alcanzado","mensaje":"ya tenes 1 de 1"}}"""),
        )

        val error = runCatching { adoptarConReintento(cuentaApi(), "tv-tok", esperaMs = 1L) }.exceptionOrNull()

        assertTrue(error is ErrorDeCuenta.TopeAlcanzado)
        assertEquals(1, server.requestCount) // ningun reintento: no es transitorio
    }

    @Test
    fun `si el candado nunca se libera, se agotan los intentos y se relanza el ultimo CandadoOcupado`() = runBlocking {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"detail":{"codigo":"candado_ocupado","mensaje":"reintenta"}}"""),
            )
        }

        val error = runCatching {
            adoptarConReintento(cuentaApi(), "tv-tok", intentos = 4, esperaMs = 1L)
        }.exceptionOrNull()

        assertTrue(error is ErrorDeCuenta.CandadoOcupado)
        assertEquals(4, server.requestCount)
    }
}
