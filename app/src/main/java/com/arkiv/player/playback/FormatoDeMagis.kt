package com.arkiv.player.playback

/**
 * Qué formato declararle a avformat (`:avformat-format`) para una fuente de magis, mirando el
 * contenedor REAL en vez de suponerlo.
 *
 * Magis no es todo MPEG-TS. Decirle `mpegts` a un mp4 hace que ffmpeg recorra el archivo entero
 * buscando un byte de sincronismo que no existe: `max resync size reached` → `No streams found` →
 * `pistas=v0/a0` → duración 0 y la reproducción clavada en 0:00. Eso es exactamente lo que pasaba
 * con los títulos servidos en mp4, mientras los de ts —donde la suposición coincidía con la
 * realidad— reproducían bien.
 *
 * Hay DOS fuentes de verdad posibles y no valen lo mismo:
 *
 * 1. **Lo que declara la fuente** (`container` del gateway, que es el `videoFormat` crudo del
 *    portal). Es el dato bueno, y es el mismo camino que usa la app original de magis: el `format`
 *    de su backend va tal cual al `iformat` de su ijkplayer, sin mirar nunca el nombre del archivo.
 * 2. **La extensión de la URL**, que para magis NO es un dato de la fuente: la arma el gateway
 *    colapsando a `.mp4` todo lo que el portal no llame `ts`, porque la extensión es la clave del
 *    objeto en el CDN y solo existe en esos dos sabores. O sea que un tercer formato del portal
 *    llegaba disfrazado de mp4 y hacía forzar el demuxer equivocado — el mismo fallo, al revés.
 *
 * Se usa (2) solo cuando (1) no viene: contra un gateway todavía sin desplegar, y contra lo que
 * quedó cacheado antes de subir su versión de caché.
 *
 * Devuelve null cuando el contenedor no se reconoce, venga de donde venga: ahí es mucho mejor dejar
 * que avformat sondee que arriesgar otra vez un formato equivocado. Es lo mismo que hace la app
 * original, que ante un `format` vacío directamente no setea `iformat`.
 */
fun formatoAvformatDe(uri: String, contenedorDeLaFuente: String = ""): String? {
    val declarado = contenedorDeLaFuente.trim().lowercase()
    if (declarado.isNotEmpty()) return demuxerDe(declarado)
    // El origen detrás del proxy lo saca [UrlDeProxy], que es donde vive ese desarmado para toda la
    // app; si la URI no viene por el proxy se le mira la extensión tal cual.
    return demuxerDe(extensionDe(UrlDeProxy.origenDe(uri) ?: uri))
}

/**
 * Nombre del demuxer de ffmpeg para un contenedor, o null si no lo sabemos traducir.
 *
 * La tabla es corta A PROPÓSITO: solo lo verificado contra lo que el portal devuelve de verdad.
 * Agregar traducciones especulativas (`hls`, `flv`, `matroska`…) sería volver a apostar, que es lo
 * que este archivo existe para no hacer — y el costo de no traducir es solo que avformat sondee.
 */
private fun demuxerDe(contenedor: String): String? = when (contenedor) {
    "ts", "mpegts", "m2ts" -> "mpegts"
    "mp4", "m4v", "mov" -> "mp4"
    else -> null
}

/** Extensión del último tramo de la ruta, en minúsculas y sin querystring. "" si no tiene. */
private fun extensionDe(url: String): String =
    url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
