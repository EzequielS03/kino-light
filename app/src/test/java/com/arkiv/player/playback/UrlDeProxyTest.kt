package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

/**
 * Desarmar la URL del proxy local en su ORIGEN y sus HEADERS, una sola vez y bien.
 *
 * Ese desarmado estaba escrito tres veces con tres criterios distintos: en el proxy
 * (`substringAfter("u=").substringBefore('&')`), en [formatoAvformatDe] (partiendo por `&`, que es
 * la única correcta) y advertido a mano en los KDoc de `conFraccion`/`conVentanaDesde` ("va antes de
 * `u=` porque hay código que saca el origen con substringAfter"). El `h=` es base64 y su relleno son
 * `=`, así que un blob terminado en `u==` hace que la versión ingenua se lleve el relleno en vez del
 * parámetro — un bug que ya se pagó una vez.
 */
class UrlDeProxyTest {

    private val headers = mapOf("Content-Auth" to "abc", "Content-License" to "xyz")
    private val origen = "http://yuwc.swzablvpm.com/vod/ABC_media.ts"

    private fun proxy(h: String = HeaderCodec.encode(headers), extra: String = "d=1&") =
        "http://127.0.0.1:37103/s?" +
            (if (h.isEmpty()) "" else "h=$h&") + extra +
            "u=" + URLEncoder.encode(origen, "UTF-8")

    @Test fun `saca el origen de una url del proxy`() {
        assertEquals(origen, UrlDeProxy.origenDe(proxy()))
    }

    @Test fun `saca los headers de una url del proxy`() {
        assertEquals(headers, UrlDeProxy.headersDe(proxy()))
    }

    @Test fun `sin headers devuelve un mapa vacio`() {
        assertEquals(emptyMap<String, String>(), UrlDeProxy.headersDe(proxy(h = "")))
    }

    /** El relleno del base64: la razón por la que esto no puede ser un `substringAfter("u=")`. */
    @Test fun `el relleno del base64 no se confunde con el parametro u`() {
        val url = "http://127.0.0.1:37103/s?h=YWJjZGVmZ2u==&d=1&u=" +
            URLEncoder.encode(origen, "UTF-8")
        assertEquals(origen, UrlDeProxy.origenDe(url))
    }

    /** Los parámetros de ventana van ANTES de `u=`; el origen tiene que salir igual. */
    @Test fun `los parametros de ventana no estorban`() {
        val url = "http://127.0.0.1:37103/s?h=${HeaderCodec.encode(headers)}&d=1&f=0.5&w=1024&u=" +
            URLEncoder.encode(origen, "UTF-8")
        assertEquals(origen, UrlDeProxy.origenDe(url))
        assertEquals(headers, UrlDeProxy.headersDe(url))
    }

    /** Un origen con querystring propio: va URL-encodeado, así que sus `&` no parten nada. */
    @Test fun `un origen con querystring propio sobrevive`() {
        val conQuery = "https://cdn.com/v/ABC.mp4?token=a&exp=99"
        val url = "http://127.0.0.1:37103/s?d=1&u=" + URLEncoder.encode(conQuery, "UTF-8")
        assertEquals(conQuery, UrlDeProxy.origenDe(url))
    }

    // ---- Lo que NO es una URL del proxy ----

    @Test fun `una url que no es del proxy no tiene origen`() {
        assertNull(UrlDeProxy.origenDe("https://archive.org/download/x/peli.mp4"))
        assertNull(UrlDeProxy.origenDe("http://127.0.0.1:37103/s"))
        assertNull(UrlDeProxy.origenDe(""))
    }

    @Test fun `una url que no es del proxy no tiene headers`() {
        assertEquals(emptyMap<String, String>(), UrlDeProxy.headersDe("https://archive.org/x.mp4"))
    }

    @Test fun `reconoce si una url sale de nuestro proxy`() {
        assert(UrlDeProxy.esDelProxy(proxy()))
        assert(!UrlDeProxy.esDelProxy("https://archive.org/download/x/peli.mp4"))
        assert(!UrlDeProxy.esDelProxy("http://192.168.3.20:8080/video"))
    }
}
