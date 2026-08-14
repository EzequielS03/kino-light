package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Magis NO es todo MPEG-TS: el gateway elige el contenedor por título y PREFIERE mp4 cuando existe
 * (`ext = "ts" if formato == "ts" else "mp4"`). Forzarle `mpegts` a avformat en un mp4 lo deja
 * buscando el byte de sincronismo por los 113 MB del archivo: `No streams found`, `pistas=v0/a0`,
 * duración 0 y la reproducción muerta en 0:00.
 */
class FormatoDeMagisTest {

    private val h = "eyJDb250ZW50LUF1dGgiOiJ4In0"

    private fun proxy(origen: String) =
        "http://127.0.0.1:37103/s?h=$h&d=1&u=" + java.net.URLEncoder.encode(origen, "UTF-8")

    @Test fun `mp4 detras del proxy`() {
        assertEquals("mp4", formatoAvformatDe(proxy("http://yuwc.swzablvpm.com/vod/ABC_media.mp4")))
    }

    @Test fun `ts detras del proxy`() {
        assertEquals("mpegts", formatoAvformatDe(proxy("http://yuwc.swzablvpm.com/vod/ABC_media.ts")))
    }

    @Test fun `mp4 directo, sin proxy`() {
        assertEquals("mp4", formatoAvformatDe("http://cdn.example.com/vod/ABC_media.mp4"))
    }

    @Test fun `ts directo, sin proxy`() {
        assertEquals("mpegts", formatoAvformatDe("http://cdn.example.com/vod/ABC_media.ts"))
    }

    /** Si no se reconoce el contenedor NO se fuerza nada: que avformat sondee es mucho mejor que
     *  decirle un formato equivocado, que es exactamente lo que rompía. */
    @Test fun `contenedor desconocido no fuerza formato`() {
        assertNull(formatoAvformatDe(proxy("http://cdn.example.com/live/canal.m3u8")))
        assertNull(formatoAvformatDe(proxy("http://cdn.example.com/vod/sin-extension")))
        assertNull(formatoAvformatDe("http://127.0.0.1:37103/s"))
    }

    @Test fun `la extension puede venir con querystring detras`() {
        assertEquals("mp4", formatoAvformatDe(proxy("http://cdn.example.com/v/ABC_media.mp4?token=x&t=1")))
    }

    @Test fun `no distingue mayusculas`() {
        assertEquals("mp4", formatoAvformatDe(proxy("http://cdn.example.com/v/ABC_MEDIA.MP4")))
    }

    /**
     * El `h=` es base64 y su relleno son `=`: un blob que termine en `u==` haría que un
     * `substringAfter("u=")` ingenuo se lleve el relleno en vez del parámetro de la URL de origen.
     */
    @Test fun `el relleno del base64 no se confunde con el parametro u`() {
        val conRelleno = "http://127.0.0.1:37103/s?h=YWJjZGVmZ2u==&d=1&u=" +
            java.net.URLEncoder.encode("http://cdn.example.com/vod/ABC_media.mp4", "UTF-8")
        assertEquals("mp4", formatoAvformatDe(conRelleno))
    }
}
