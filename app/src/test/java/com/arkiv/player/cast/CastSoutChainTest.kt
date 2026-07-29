package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cadena `sout` que se le pasa a libVLC para transcodificar hacia el Chromecast. Es un string
 * suelto que libVLC parsea en runtime: un typo no rompe la compilación, solo hace que no salga nada
 * y sin error claro. Por eso se fija acá.
 */
class CastSoutChainTest {

    private fun soutOf(options: List<String>): String =
        options.first { it.startsWith(":sout=") }

    @Test
    fun `el audio se transcodifica a AAC estereo`() {
        val sout = soutOf(CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null))
        assertTrue(sout, sout.contains("acodec=mp4a"))
        assertTrue(sout, sout.contains("channels=2"))
    }

    @Test
    fun `el video NO se toca`() {
        // El corazón del diseño: el H.264 ya funciona en el receptor. Si acá se cuela un vcodec, se
        // re-encodea video y el celu se funde. Este test es el que protege eso.
        val sout = soutOf(CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null))
        assertFalse(sout, sout.contains("vcodec"))
        assertFalse(sout, sout.contains("venc"))
    }

    @Test
    fun `sale como Matroska en vivo por HTTP en el puerto pedido`() {
        val sout = soutOf(CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null))
        assertTrue(sout, sout.contains("mux=avformat{mux=matroska,options={live=1},reset-ts}"))
        assertTrue(sout, sout.contains("access=http"))
        assertTrue(sout, sout.contains("dst=:8099/"))
    }

    @Test
    fun `el muxer reencera los timestamps`() {
        // Medido en el receptor real: arrancando con :start-time el muxer TS veía TODO como
        // "late buffer" (~8s de desfase) y el stream salía inservible. `reset-ts` rebasa los
        // timestamps a cero, que es justo lo que hace el módulo de Chromecast de VLC.
        val sout = soutOf(CastSoutChain.mediaOptions(port = 8099, startAtMs = 600_000, audioTrackIndex = null))
        assertTrue(sout, sout.contains("reset-ts"))
    }

    @Test
    fun `el servidor declara el contenedor real`() {
        // Verificado contra el receptor real: sin esto el access_output de VLC manda
        // "application/octet-stream" y el Chromecast bufferea unos segundos y se va a idle SIN
        // disparar ningún error. El receptor decide por la cabecera del servidor, no por el mime que
        // le declaramos a media3.
        val sout = soutOf(CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null))
        assertTrue(sout, sout.contains("access=http{mime=video/x-matroska}"))
    }

    @Test
    fun `arrancar en un punto se pasa en SEGUNDOS`() {
        // libVLC espera segundos; pasarle los milisegundos manda el arranque a las 11 horas.
        val options = CastSoutChain.mediaOptions(port = 8099, startAtMs = 90_000, audioTrackIndex = null)
        assertTrue(options.toString(), options.contains(":start-time=90"))
    }

    @Test
    fun `arrancar desde cero no pasa start-time`() {
        val options = CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null)
        assertFalse(options.toString(), options.any { it.startsWith(":start-time") })
    }

    @Test
    fun `respeta la pista de audio elegida en el celu`() {
        // Un release con audio latino + inglés: si no se pasa, el transcodificador manda la primera
        // y la TV suena en otro idioma del que elegiste.
        val options = CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = 1)
        assertTrue(options.toString(), options.contains(":audio-track=1"))
    }

    @Test
    fun `sin pista elegida no fuerza ninguna`() {
        val options = CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null)
        assertFalse(options.toString(), options.any { it.startsWith(":audio-track") })
    }

    @Test
    fun `manda solo las pistas elegidas y no todas`() {
        // Un MKV con tres audios transcodificaría los tres: tres veces el trabajo, para nada.
        val options = CastSoutChain.mediaOptions(port = 8099, startAtMs = 0, audioTrackIndex = null)
        assertTrue(options.toString(), options.contains(":no-sout-all"))
    }

    @Test
    fun `la url que se castea apunta al celu por LAN`() {
        assertEquals(
            "http://192.168.3.20:8099/cast.mkv",
            CastSoutChain.streamUrl(lanIp = "192.168.3.20", port = 8099),
        )
    }

    @Test
    fun `el mime es el de Matroska`() {
        // Es lo que se le declara al receptor. Es el mismo que manda el módulo de Chromecast de VLC.
        assertEquals("video/x-matroska", CastSoutChain.MIME)
    }
}
