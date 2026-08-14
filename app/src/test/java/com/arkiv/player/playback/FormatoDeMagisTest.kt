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
    /**
     * LO QUE DICE LA FUENTE le gana a la extensión, porque la extensión de magis NO es un dato de la
     * fuente: la arma el gateway colapsando a `mp4` todo lo que el portal no llame `ts` (es la clave
     * del objeto en el CDN, no la elegimos). Un tercer formato llegaba entonces disfrazado de mp4 y
     * hacía forzar el demuxer equivocado — el mismo fallo que este archivo documenta al revés.
     *
     * Es lo mismo que hace la app original: el `format` de su backend va tal cual al `iformat` de su
     * ijkplayer, sin mirar nunca el nombre del archivo.
     */
    @Test fun `el contenedor que declara la fuente le gana a la extension`() {
        val urlMp4 = proxy("http://cdn.example.com/vod/ABC_media.mp4")
        assertEquals("mpegts", formatoAvformatDe(urlMp4, contenedorDeLaFuente = "ts"))
        assertEquals("mp4", formatoAvformatDe(urlMp4, contenedorDeLaFuente = "mp4"))
    }

    /**
     * Un contenedor que la fuente declara y NO sabemos traducir se sondea, no se adivina. Es la
     * rama que hasta ahora era inalcanzable: el gateway solo podía emitir `.ts` o `.mp4`, así que un
     * `flv` del portal se veía desde acá como un mp4 y se forzaba como tal.
     */
    @Test fun `un contenedor desconocido de la fuente manda a sondear`() {
        val urlMp4 = proxy("http://cdn.example.com/vod/ABC_media.mp4")
        assertNull(formatoAvformatDe(urlMp4, contenedorDeLaFuente = "flv"))
        assertNull(formatoAvformatDe(urlMp4, contenedorDeLaFuente = "hls"))
        assertNull(formatoAvformatDe(urlMp4, contenedorDeLaFuente = "matroska"))
    }

    @Test fun `no distingue mayusculas ni espacios en lo que declara la fuente`() {
        val url = proxy("http://cdn.example.com/vod/ABC_media.mp4")
        assertEquals("mpegts", formatoAvformatDe(url, contenedorDeLaFuente = " TS "))
        assertEquals("mpegts", formatoAvformatDe(url, contenedorDeLaFuente = "mpegts"))
    }

    /**
     * Sin contenedor declarado se sigue deduciendo de la extensión. Es lo que pasa contra un gateway
     * viejo (todavía no desplegado) y con lo que quedó cacheado antes de subir la versión de caché:
     * el camino nuevo no puede romper al que ya andaba.
     */
    @Test fun `sin contenedor declarado se cae a la extension de siempre`() {
        assertEquals("mp4", formatoAvformatDe(proxy("http://cdn/v/ABC_media.mp4"), ""))
        assertEquals("mpegts", formatoAvformatDe(proxy("http://cdn/v/ABC_media.ts"), ""))
        assertNull(formatoAvformatDe(proxy("http://cdn/v/sin-extension"), ""))
    }

    @Test fun `el relleno del base64 no se confunde con el parametro u`() {
        val conRelleno = "http://127.0.0.1:37103/s?h=YWJjZGVmZ2u==&d=1&u=" +
            java.net.URLEncoder.encode("http://cdn.example.com/vod/ABC_media.mp4", "UTF-8")
        assertEquals("mp4", formatoAvformatDe(conRelleno))
    }
}
