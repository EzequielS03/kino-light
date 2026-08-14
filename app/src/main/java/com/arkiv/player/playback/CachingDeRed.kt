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
     * El proxy de blog corre en 2 CPU detrás de Cloudflare, y esa es la parte lenta.
     */
    private const val PROXY_MAS_CDN_MS = 8_000

    /**
     * VIVO: su CDN es OTRO, y mucho mejor. Medido en el Fire TV el 2026-08-14 sobre 15 minutos de
     * canal: playlist en 141 ms de mediana (p95 283, max 355) y segmento en 206 ms (p95 360, max
     * 596), con cero 403 de firma, cero 502 y cero segmentos cortados.
     *
     * Compartía los 8000 ms de WEB por precaución, de cuando no sabíamos nada de él. Contra un peor
     * segmento de 596 ms, eso son ~13× de colchón: puro retardo de arranque sobre los 4,1 s que
     * tarda un canal en empezar. 3000 ms siguen siendo 5× ese peor caso.
     *
     * Ojo con bajarlo más: acá el proxy le pide cada segmento al CDN en el momento (no hay
     * read-ahead propio, ver [LiveHlsProxy.servirSegmento]), así que el colchón es lo ÚNICO que
     * separa un hipo del CDN de un corte en pantalla.
     */
    private const val VIVO_MS = 3_000

    fun msPara(kind: SourceKind?): Int = when (kind) {
        SourceKind.TORRENT -> TORRENT_MS
        SourceKind.WEB -> PROXY_MAS_CDN_MS
        SourceKind.LIVE -> VIVO_MS
        else -> ORIGEN_ESTABLE_MS
    }
}
