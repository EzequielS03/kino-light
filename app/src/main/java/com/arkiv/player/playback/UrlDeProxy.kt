package com.arkiv.player.playback

import java.net.URLDecoder

/**
 * Desarma la URL del proxy local (`http://127.0.0.1:<port>/s?h=…&d=1&f=…&w=…&u=<origen>`).
 *
 * Existe para que ese desarmado esté escrito UNA vez. Estaba en tres lugares con tres criterios
 * distintos —el propio proxy con `substringAfter("u=")`, el conversor de contenedor de magis
 * partiendo por `&`, y advertido a mano en los KDoc de `conFraccion`/`conVentanaDesde` ("va antes de `u=` porque hay
 * código que saca el origen con substringAfter")— y la diferencia entre esos criterios ya costó un
 * bug: el `h=` es base64 y su relleno son `=`, así que un blob terminado en `u==` hace que la
 * búsqueda ingenua se lleve el relleno en vez del parámetro.
 *
 * Pura y sin Android: los bordes de esto son exactamente donde se rompe.
 */
object UrlDeProxy {

    /** Prefijo de las URLs que sirve nuestro proxy. */
    const val PREFIJO = "http://127.0.0.1"

    /** Si [url] la sirve nuestro proxy local (o sea, si tiene origen y headers adentro). */
    fun esDelProxy(url: String): Boolean = url.startsWith(PREFIJO) && origenDe(url) != null

    /**
     * La URL del ORIGEN que hay detrás, o null si [url] no viene por el proxy.
     *
     * Los parámetros se parten por `&` y se busca el que EMPIEZA con `u=`, en vez de buscar `"u="`
     * a secas en cualquier posición: ver el relleno del base64 arriba.
     */
    fun origenDe(url: String): String? {
        val crudo = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("u=") }
            ?.removePrefix("u=")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { URLDecoder.decode(crudo, "UTF-8") }.getOrNull()
    }

    /** Los headers que van empaquetados en `h=`. Mapa vacío si no hay o si no se pueden leer. */
    fun headersDe(url: String): Map<String, String> {
        val blob = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("h=") }
            ?.removePrefix("h=")
            ?: return emptyMap()
        return HeaderCodec.decode(blob)
    }
}
