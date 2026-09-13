package com.arkiv.player.playback

/**
 * The END of the file, served from memory.
 *
 * When opening a TS over HTTP, libVLC used to probe the end: it asked for ranges a few KB from
 * the EOF to get the last PCR and deduce the duration. Those requests were what ate up startup --
 * measured on 2026-08-11 on the Fire TV, with the video already in `Playing` but stuck at `pos=0`:
 *
 * ```
 * bytes=859421696-   (940 B from the end)    → 206 with 0 KB
 * bytes=859419628-   (3,008 B from the end)  → 206 with 2 KB
 * bytes=859415492-   (7,144 B from the end)  → three attempts timed out in a row
 * ```
 *
 * And it's not that the CDN is broken there: requested from the Mac, those SAME offsets answered
 * 24 out of 24 times in 0.14-0.37s. It's the real path's latency that sometimes blows past any
 * reasonable deadline, and adjusting the deadline only moves the problem -- a short one cuts off
 * someone who was about to answer, a long one waits for someone who won't.
 *
 * That's why this doesn't roll the dice: the tail is downloaded ONCE together with startup (see
 * [ArchiveCacheProxy.preWarm]) and from there the player's probes are answered without
 * touching the network. It's the same idea as the hot startup at byte 0, applied to the other end.
 *
 * It's a pure function so the edges can be pinned by test, which is where this would be dangerous:
 * handing over a body shorter than the `Content-Length` leaves the player waiting forever, with
 * no visible error.
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
     * cola. Los de magis —los únicos que pasan por acá, [ArchiveCacheProxy.preWarm] tiene un
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
