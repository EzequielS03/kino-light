package com.arkiv.player.playback

/**
 * Cuánto colchón de red (`:network-caching`) pedirle a libVLC, según de dónde salgan los bytes.
 *
 * Vive acá y no suelto dentro de `loadMedia` porque cada número es una MEDICIÓN y no una opinión, y
 * no había dónde escribir cuál fue. Lo que decide el valor no es la fuente en abstracto sino la
 * FORMA DEL CAMINO: cuántos saltos hay entre libVLC y los bytes, y qué tan variable es la latencia
 * de cada uno.
 *
 * El decompilado respalda el criterio de tratar el vivo aparte: su reproductor lo configura distinto
 * del VOD (`live_mode=1` cuando `buss == "live"`, más `live-streaming` y `delay-optimization`) en
 * vez de dejarlo caer en el caso general. Esas tres son opciones de SU fork de ijkplayer —tiene
 * `_setSharedBuffer`, `ijklivehook`, `ijksegment` propios— y no tienen equivalente literal en
 * libVLC: lo que se porta es la decisión de separar el vivo, no el nombre de la opción.
 */
object CachingDeRed {

    /**
     * Origen ESTABLE de archivo (archive.org, un archivo local, la NUC). Un solo salto y latencia
     * pareja: con 1,5 s alcanza y de paso el arranque es el más corto posible.
     *
     * MAGIS se queda acá a propósito, aunque su CDN tarde entre 0,2 s y 20 s por rango: VLC lee de
     * corrido sobre un rango abierto, así que esa latencia se paga al abrir y al saltar (para lo que
     * está [AvisoDeSalto]) y no en cada lectura. Subirle el colchón alargaría el arranque, que es lo
     * que más costó bajar. Sin una medición que lo justifique, no se toca.
     */
    private const val ORIGEN_ESTABLE_MS = 1_500

    /**
     * Torrent: el origen es VARIABLE por definición (piezas llegando de peers distintos). Medido en
     * device: con 2,5 s VLC se quedaba sin datos y estancaba; con 6 s el arranque sale limpio. Subir
     * más no arregla el bache del arranque, que es de CPU (el decoder por hardware de la TV) y no de
     * datos — solo agrega latencia.
     */
    private const val TORRENT_MS = 6_000

    /**
     * Dos saltos hasta los bytes: libVLC → un proxy nuestro → un CDN por internet. La latencia por
     * segmento es variable y con 1,5 s el colchón se drena y la reproducción alcanza al buffer
     * ("se va pasando", medido contra el proxy de blog, que corre en 2 CPU detrás de Cloudflare).
     *
     * Lo comparten WEB y LIVE porque el camino es el mismo; en el vivo el proxy es [LiveHlsProxy],
     * que además firma cada segmento contra el CDN. Y en el vivo el costo de este colchón es gratis:
     * arrancar unos segundos más atrás del borde no se nota, cortarse sí.
     */
    private const val PROXY_MAS_CDN_MS = 8_000

    fun msPara(kind: SourceKind?): Int = when (kind) {
        SourceKind.TORRENT -> TORRENT_MS
        SourceKind.WEB, SourceKind.LIVE -> PROXY_MAS_CDN_MS
        else -> ORIGEN_ESTABLE_MS
    }
}
