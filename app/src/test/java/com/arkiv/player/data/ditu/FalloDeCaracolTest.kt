package com.arkiv.player.data.ditu

import androidx.media3.common.PlaybackException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Que ningún error técnico de Caracol le llegue crudo a la persona. */
class FalloDeCaracolTest {

    /** Como llega a la búsqueda y al reproductor: `DituFuente` envuelve lo de `DituCliente`, que envuelve lo de OkHttp. */
    private fun envuelto(causa: Throwable): Throwable {
        val delCliente = DituException("Caracol no responde: ${causa.message}", causa)
        return GatewayException(delCliente.message!!, delCliente)
    }

    // --- la búsqueda y la resolución ---------------------------------------------------------

    @Test fun `sin conexion a internet`() {
        val sinDns = envuelto(UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""))
        assertEquals("Caracol no respondió: sin conexión a internet", FalloDeCaracol.enLaBusqueda(sinDns, sinDns.message))
        assertEquals("Caracol no respondió: sin conexión a internet", FalloDeCaracol.alAbrir(sinDns))
    }

    /** Una conexión rechazada puede pasar con internet andando: no se le dice "sin internet". */
    @Test fun `una conexion rechazada no es sin internet`() {
        for (causa in listOf(ConnectException("Failed to connect to /10.0.0.1:443"), NoRouteToHostException("No route to host"))) {
            val e = envuelto(causa)
            assertEquals("Caracol no respondió", FalloDeCaracol.enLaBusqueda(e, e.message))
            assertEquals("Caracol no respondió", FalloDeCaracol.alAbrir(e))
        }
        assertEquals(
            "Caracol no respondió",
            FalloDeCaracol.enLaBusqueda(null, "Caracol no responde: Failed to connect to /10.0.0.1:443"),
        )
    }

    @Test fun `una demora`() {
        for (causa in listOf(SocketTimeoutException("Read timed out"), InterruptedIOException("timeout"))) {
            val e = envuelto(causa)
            assertEquals("Caracol tardó demasiado en responder", FalloDeCaracol.enLaBusqueda(e, e.message))
            assertEquals("Caracol tardó demasiado en responder", FalloDeCaracol.alAbrir(e))
        }
    }

    @Test fun `un 5xx es que Caracol esta fallando y un 4xx no`() {
        val cinco = GatewayException("x", DituException("Caracol respondió 503 en TRAY/SEARCH/VOD", codigoHttp = 503))
        assertEquals("Caracol está fallando en este momento", FalloDeCaracol.enLaBusqueda(cinco, null))
        assertEquals("Caracol está fallando en este momento", FalloDeCaracol.alAbrir(cinco))

        val cuatro = GatewayException("x", DituException("Caracol respondió 404 en TRAY/SEARCH/VOD", codigoHttp = 404))
        assertEquals("Caracol no respondió", FalloDeCaracol.enLaBusqueda(cuatro, null))
        assertEquals("No se pudo reproducir en Caracol", FalloDeCaracol.alAbrir(cuatro))
    }

    /** Los motivos de bloqueo ya vienen para la persona: el texto no cambia en nada. */
    @Test fun `un bloqueo de Caracol se ve igual que antes`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/42", """
        {"resultObj":{"containers":[{"entitlement":{"isGeoBlocked":true}}]}}
        """)
        val deResolve = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()!!
        // Como lo envuelve `DituFuente.resolve`.
        val e = GatewayException(deResolve.message!!, deResolve)

        assertEquals("Caracol: solo disponible en Colombia", e.message)
        assertEquals("Caracol: solo disponible en Colombia", FalloDeCaracol.alAbrir(e))
    }

    @Test fun `lo que no se reconoce es un generico`() {
        val e = envuelto(IllegalStateException("JSONObject[\"resultObj\"] not found"))
        assertEquals("Caracol no respondió", FalloDeCaracol.enLaBusqueda(e, e.message))
        assertEquals("No se pudo reproducir en Caracol", FalloDeCaracol.alAbrir(e))
        assertEquals("Caracol no respondió", FalloDeCaracol.enLaBusqueda(null, null))
        assertEquals("No se pudo reproducir en Caracol", FalloDeCaracol.alAbrir(null))
    }

    /** `FuenteCompuesta` puede mandar el error de una fuente sin la excepción: queda el texto. */
    @Test fun `solo con el texto tambien se entiende`() {
        assertEquals(
            "Caracol no respondió: sin conexión a internet",
            FalloDeCaracol.enLaBusqueda(null, "Caracol no responde: Unable to resolve host \"x\""),
        )
        assertEquals("Caracol tardó demasiado en responder", FalloDeCaracol.enLaBusqueda(null, "Caracol no responde: timeout"))
        assertEquals("Caracol está fallando en este momento", FalloDeCaracol.enLaBusqueda(null, "Caracol respondió 502 en TRAY/SEARCH/VOD"))
    }

    @Test fun `nunca el texto crudo`() {
        val crudos = listOf(
            "Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            "timeout",
            "Caracol respondió 503 en TRAY/SEARCH/VOD",
            "java.lang.NullPointerException: boom",
        )
        for (crudo in crudos) {
            val busqueda = FalloDeCaracol.enLaBusqueda(RuntimeException(crudo), crudo)
            assertFalse(busqueda, busqueda.contains(crudo))
            val abrir = FalloDeCaracol.alAbrir(RuntimeException(crudo))
            assertFalse(abrir, abrir.contains(crudo))
        }
    }

    // --- el reproductor ----------------------------------------------------------------------

    @Test fun `el reproductor habla por la familia del codigo`() {
        val red = listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        for (c in red) assertEquals("Se cortó la conexión con Caracol", FalloDeCaracol.alReproducir(c, esTelevision = true))

        val drm = listOf(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, PlaybackException.ERROR_CODE_DRM_UNSPECIFIED)
        for (c in drm) assertEquals("Caracol no autorizó la reproducción", FalloDeCaracol.alReproducir(c, esTelevision = true))

        val aparato = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        for (c in aparato) {
            assertEquals("El televisor no pudo reproducir este video", FalloDeCaracol.alReproducir(c, esTelevision = true))
            assertEquals("Este celular no pudo reproducir este video", FalloDeCaracol.alReproducir(c, esTelevision = false))
        }

        assertEquals(
            "No se pudo reproducir en Caracol",
            FalloDeCaracol.alReproducir(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, esTelevision = true),
        )
    }

    @Test fun `el reproductor nunca muestra el nombre del codigo`() {
        for (c in listOf(1000, 1002, 2001, 3002, 4003, 5001, 6004, 7000, 123456)) {
            for (tv in listOf(true, false)) {
                val texto = FalloDeCaracol.alReproducir(c, tv)
                assertFalse(texto, texto.contains("ERROR_CODE"))
                assertFalse(texto, texto.contains(c.toString()))
            }
        }
    }
}
