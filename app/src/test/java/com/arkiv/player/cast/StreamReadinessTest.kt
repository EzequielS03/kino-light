package com.arkiv.player.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Saber si un servidor local ya está entregando datos.
 *
 * Existe por un fallo medido contra el receptor real: a libVLC se le pedía transcodificar y 36 ms
 * después se le decía al Chromecast que cargara la URL. libVLC todavía no había abierto el origen ni
 * bindeado el puerto, el receptor recibía conexión rechazada, se iba a idle y **no reintenta**. En
 * `/proc/net/tcp` del celu se veía el socket en LISTEN sin una sola conexión establecida.
 */
class StreamReadinessTest {

    @Test
    fun `un puerto donde no hay nada no esta listo`() {
        // Puerto tomado y liberado: nadie escuchando.
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(StreamReadiness.servesData("127.0.0.1", port, connectTimeoutMs = 300, readTimeoutMs = 300))
    }

    @Test
    fun `escuchar sin mandar nada tampoco alcanza`() {
        // El caso peligroso: el puerto acepta pero el transcodificador todavía no produjo un byte.
        // Si esto diera "listo", volveríamos a mandarle al receptor un stream vacío.
        val server = ServerSocket(0)
        val accepter = Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
        try {
            assertFalse(StreamReadiness.servesData("127.0.0.1", server.localPort, connectTimeoutMs = 300, readTimeoutMs = 300))
        } finally {
            accepter.interrupt()
            server.close()
        }
    }

    @Test
    fun `un servidor que entrega bytes esta listo`() {
        val server = ServerSocket(0)
        val serving = Thread {
            runCatching {
                while (true) {
                    val s = server.accept()
                    s.getOutputStream().write("MPEGTS-ish".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(StreamReadiness.servesData("127.0.0.1", server.localPort, connectTimeoutMs = 1000, readTimeoutMs = 1000))
        } finally {
            serving.interrupt()
            server.close()
        }
    }
}
