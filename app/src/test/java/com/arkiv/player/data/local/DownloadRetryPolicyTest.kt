package com.arkiv.player.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadRetryPolicyTest {

    @Test
    fun `un corte de red es transitorio`() {
        assertTrue(DownloadRetryPolicy.isTransient(UnknownHostException("sin DNS")))
        assertTrue(DownloadRetryPolicy.isTransient(SocketTimeoutException("timeout")))
        assertTrue(DownloadRetryPolicy.isTransient(IOException("connection reset")))
    }

    @Test
    fun `una descarga cortada a mitad es transitoria`() {
        assertTrue(DownloadRetryPolicy.isTransient(IncompleteDownloadException(written = 400, total = 1000)))
    }

    @Test
    fun `5xx y frenadas del server se reintentan`() {
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(500)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(503)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(429)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(408)))
    }

    @Test
    fun `un enlace caducado o inexistente no se reintenta`() {
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(403)))
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(404)))
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(410)))
    }

    @Test
    fun `lo que no es de red es definitivo`() {
        // "Fuente no soportada", "sin espacio", "película web no soportada": errores de lógica y de
        // entorno que van a fallar exactamente igual dentro de 30 segundos.
        assertFalse(DownloadRetryPolicy.isTransient(IllegalStateException("Fuente no soportada")))
        assertFalse(DownloadRetryPolicy.isTransient(IllegalArgumentException("sin espacio")))
    }

    @Test
    fun `lo definitivo nunca se reintenta aunque haya intentos de sobra`() {
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = false, attempt = 0))
    }

    @Test
    fun `lo transitorio se reintenta hasta el tope y no mas`() {
        assertTrue(DownloadRetryPolicy.shouldRetry(transient = true, attempt = 0))
        assertTrue(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 2))
        // Con MAX_ATTEMPTS intentos ya hechos la fila se da por perdida: queda `failed` con su
        // motivo y el botón "Reintentar" de la pantalla sigue disponible.
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS))
    }
}
