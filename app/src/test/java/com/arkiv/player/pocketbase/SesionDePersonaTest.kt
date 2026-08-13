package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class SesionDePersonaTest {
    private fun clientFor(server: MockWebServer) =
        PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `guarda el token al iniciar y lo devuelve`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}""")) // auth-with-password
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)

        sesion.iniciar("a@b.co", "secret12")

        assertEquals("tok-1", sesion.token())
        assertEquals("tok-1", store.personToken())          // en las prefs cifradas, no solo en memoria
        assertEquals("a@b.co", store.personEmail())
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        server.shutdown()
    }

    @Test
    fun `cerrar borra el token y el email`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}"""))
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        sesion.iniciar("a@b.co", "secret12")

        sesion.cerrar()

        assertEquals(null, sesion.token())
        assertEquals(null, store.personToken())
        assertEquals(null, store.personEmail())
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        server.shutdown()
    }

    @Test
    fun `refrescar cambia el token cuando PocketBase da uno nuevo`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}""")) // iniciar
        server.enqueue(MockResponse().setBody("""{"token":"tok-2","record":{"id":"usr-1"}}""")) // auth-refresh
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        sesion.iniciar("a@b.co", "secret12")

        val ok = sesion.refrescar()

        assertEquals(true, ok)
        assertEquals("tok-2", sesion.token())
        assertEquals("tok-2", store.personToken())
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)   // sigue conectada
        server.shutdown()
    }

    @Test
    fun `refrescar devuelve false y NO borra la sesion si no hay red`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}""")) // iniciar
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        sesion.iniciar("a@b.co", "secret12")
        server.shutdown()   // a partir de acá, cualquier pedido revienta con IOException (sin red)

        val ok = sesion.refrescar()

        assertEquals(false, ok)
        assertEquals("tok-1", sesion.token())                              // el token viejo sigue ahí
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)    // la sesión sigue viva
    }

    @Test
    fun `refrescar cierra la sesion cuando PocketBase dice que el token no vale`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}""")) // iniciar
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"token invalido"}""")) // auth-refresh
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        sesion.iniciar("a@b.co", "secret12")

        val ok = sesion.refrescar()

        assertEquals(false, ok)
        assertEquals(null, sesion.token())
        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
        server.shutdown()
    }

    @Test
    fun `aplicarSesionCompartida persiste token y email SIN hablarle a PocketBase`() = runBlocking {
        val server = MockWebServer()
        server.start() // sin encolar ninguna respuesta: cualquier pedido de red revienta
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)

        sesion.aplicarSesionCompartida("tok-compartido", "a@b.co")

        assertEquals("tok-compartido", sesion.token())
        assertEquals("tok-compartido", store.personToken())
        assertEquals("a@b.co", store.personEmail())
        assertEquals(EstadoDeSesion.Con("a@b.co"), sesion.estado.value)
        // Evidencia, no solo el estado resultante (misma lección que la mutación 2 de la Task 4):
        // la TV nunca tiene la contraseña de la persona, así que este camino NO puede autenticar.
        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `aplicarSesionCompartida pisa una sesion anterior (re-pareo con otra cuenta)`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"tok-1","record":{"id":"usr-1"}}"""))
        server.start()
        val store = FakeDeviceStore()
        val sesion = SesionDePersona(clientFor(server), store)
        sesion.iniciar("viejo@b.co", "secret12")

        sesion.aplicarSesionCompartida("tok-nuevo", "nuevo@b.co")

        assertEquals("tok-nuevo", sesion.token())
        assertEquals(EstadoDeSesion.Con("nuevo@b.co"), sesion.estado.value)
        server.shutdown()
    }

    @Test
    fun `estado inicial es Sin si hay email guardado pero no token (instalacion previa a este cambio)`() {
        val store = FakeDeviceStore()
        store.savePersonEmail("a@b.co")   // simula una instalación de antes de este cambio: solo el email
        val sesion = SesionDePersona(PocketBaseClient(baseUrl = "http://localhost:1"), store)

        assertEquals(EstadoDeSesion.Sin, sesion.estado.value)
    }
}
