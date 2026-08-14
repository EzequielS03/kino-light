package com.arkiv.player.playback

/**
 * El FINAL del archivo, servido desde memoria.
 *
 * Al abrir un TS por HTTP, libVLC sondea el final: pide rangos a pocos KB del EOF para sacar el
 * último PCR y deducir la duración. Esas peticiones son las que se llevaban el arranque — medido el
 * 2026-08-11 en el Fire TV, con el video ya en `Playing` pero clavado en `pos=0`:
 *
 * ```
 * bytes=859421696-   (a 940 B del final)    → 206 con 0 KB
 * bytes=859419628-   (a 3 008 B del final)  → 206 con 2 KB
 * bytes=859415492-   (a 7 144 B del final)  → tres intentos vencidos seguidos
 * ```
 *
 * Y no es que el CDN esté roto ahí: pedidos desde el Mac, esos MISMOS offsets contestaron 24 de 24
 * veces en 0,14-0,37 s. Es la latencia del camino real la que a veces se pasa de cualquier plazo
 * razonable, y ajustar el plazo solo mueve el problema — con uno corto se corta a alguien que iba a
 * contestar, con uno largo se espera a alguien que no.
 *
 * Por eso esto no juega a los dados: la cola se baja UNA vez junto con el arranque (ver
 * [ArchiveCacheProxy.precalentar]) y desde ahí los sondeos de VLC se contestan sin tocar la red.
 * Es la misma idea del arranque caliente del byte 0, aplicada a la otra punta.
 *
 * Es una función pura para poder fijar por test los bordes, que es donde esto sería peligroso:
 * entregar un cuerpo más corto que el `Content-Length` deja al reproductor esperando para siempre,
 * y sin error visible.
 */
object ColaCaliente {

    /**
     * Si al contenedor [contenedor] le hace falta que le precalentemos la cola.
     *
     * Medido en el Fire TV el 2026-08-14 sobre siete reproducciones: los tres títulos en **mp4**
     * bajaron su cola y NO la usaron ni una vez (cero líneas `cola caliente`), mientras que los
     * mpegts la usaron en todas sus aperturas y varias veces cada una. Y bajarla no sale gratis: en
     * uno de esos mp4 costó **8284 ms y tres rechazos del CDN**, en paralelo con la apertura del
     * video y peleándole el ancho de banda al mismo origen que tiene que servirlo.
     *
     * Solo se saltea el mp4, que es el caso medido. **Ante la duda se precalienta**: un contenedor
     * desconocido puede tener su índice al final —el Matroska guarda ahí los Cues, que es de donde
     * salió el bug del buffering infinito en los torrents `.mkv`— y ahorrarse 256 KB no vale volver
     * a ese fallo.
     *
     * Ojo con el riesgo residual: un mp4 SIN faststart lleva el `moov` al final y sí necesitaría la
     * cola. Los de magis —los únicos que pasan por acá, [ArchiveCacheProxy.precalentar] tiene un
     * solo llamador— no lo hacen. Si alguno lo hiciera, se vería en el log como un
     * `pide rango=bytes=<cerca del final>` sobre un mp4, y lo peor que pasa es que ese rango va al
     * origen como iba antes de que la cola existiera.
     */
    fun hayQuePrecalentar(contenedor: String?): Boolean =
        contenedor?.trim()?.lowercase() !in SIN_INDICE_AL_FINAL

    /** Contenedores que abren sin tocar el final del archivo. */
    private val SIN_INDICE_AL_FINAL = setOf("mp4", "m4v", "mov")

    /**
     * Los bytes de [rango] si caen ENTEROS dentro de la cola guardada, o null para que lo resuelva
     * el origen.
     *
     * [inicioDeLaCola] es el byte absoluto donde arranca [cola]; [total] el tamaño del archivo. Se
     * exige tener las dos cosas y que el rango entre completo: servir de menos sería peor que ir a
     * la red.
     */
    fun servir(inicioDeLaCola: Long, cola: ByteArray, rango: ByteRange?, total: Long): ByteArray? {
        if (cola.isEmpty() || total <= 0L || rango == null) return null
        if (rango.start < inicioDeLaCola) return null
        // El final pedido: `bytes=N-` es "hasta el EOF".
        val fin = rango.end ?: (total - 1)
        if (fin > total - 1 || fin < rango.start) return null
        // Y el tramo pedido tiene que estar cubierto por lo que guardamos.
        val ultimoQueTenemos = inicioDeLaCola + cola.size - 1
        if (fin > ultimoQueTenemos) return null
        val desde = (rango.start - inicioDeLaCola).toInt()
        val hasta = (fin - inicioDeLaCola).toInt() + 1
        return cola.copyOfRange(desde, hasta)
    }
}
