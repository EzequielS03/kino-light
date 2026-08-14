package com.arkiv.player.playback

import java.net.URLDecoder

/**
 * Qué formato declararle a avformat (`:avformat-format`) para una fuente de magis, mirando el
 * contenedor REAL en vez de suponerlo.
 *
 * Magis no es todo MPEG-TS. El gateway elige el contenedor por título y prefiere mp4 cuando el
 * portal lo ofrece (`ext = "ts" if formato == "ts" else "mp4"`, ver el adaptador de magis). Decirle
 * `mpegts` a un mp4 hace que ffmpeg recorra el archivo entero buscando un byte de sincronismo que
 * no existe: `max resync size reached` → `No streams found` → `pistas=v0/a0` → duración 0 y la
 * reproducción clavada en 0:00. Eso es exactamente lo que pasaba con los títulos servidos en mp4,
 * mientras los de ts —donde la suposición coincidía con la realidad— reproducían bien.
 *
 * Devuelve null cuando el contenedor no se reconoce: ahí es mucho mejor dejar que avformat sondee
 * que arriesgar otra vez un formato equivocado.
 */
fun formatoAvformatDe(uri: String): String? =
    when (extensionDe(origenDe(uri))) {
        "ts" -> "mpegts"
        "mp4" -> "mp4"
        else -> null
    }

/**
 * La URL del origen que hay detrás de la del proxy local (`/s?h=…&d=1&u=<origen>`); si no viene por
 * el proxy, la URI tal cual.
 *
 * Los parámetros se parten por `&` en vez de buscar `"u="` a secas: el `h=` es base64 y su relleno
 * son `=`, así que un blob terminado en `u==` haría que la búsqueda ingenua se lleve el relleno.
 */
private fun origenDe(uri: String): String {
    val u = uri.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("u=") }
        ?.removePrefix("u=")
        ?: return uri
    return runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(uri)
}

/** Extensión del último tramo de la ruta, en minúsculas y sin querystring. "" si no tiene. */
private fun extensionDe(url: String): String =
    url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
