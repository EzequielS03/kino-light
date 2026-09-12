package com.arkiv.player.playback

/**
 * Serve a CHUNK of the file as if it were the whole file.
 *
 * Exists to resume an MPEG-TS from magis without using libVLC's seek, which used to break
 * playback. Over HTTP, libVLC didn't know the duration, so the only seek that responded was by
 * FRACTION, which sought by byte; and doing so left the demuxer with the clock at the old point
 * while data from the new one came in. Measured on device: after the seek, VLC swallowed 752 MB
 * in 330s (17x real time, i.e. discarding all of it) and the clock didn't move a single ms --
 * black screen and "buffering 0%" forever.
 *
 * Opening directly AT the point doesn't have that problem: it's the case that does work (a movie
 * never watched before starts perfectly). So instead of opening at 0 and seeking, the proxy opens
 * a window that STARTS at the requested point and lies to the player about the size: for VLC it
 * was a new file starting at 0, with a clean clock and not a single seek.
 */
object VentanaDeArchivo {

    /** Tamaño de un paquete TS. Invariante del formato. */
    private const val PAQUETE = 188

    /**
     * Byte donde arranca la ventana para la [fraccion] pedida, alineado HACIA ABAJO a paquete TS.
     *
     * La alineación es lo que le ahorra al demuxer tener que resincronizar a ciegas: cayendo en el
     * 0x47 el primer paquete ya es válido. Se acota para que siempre quede al menos un paquete por
     * delante (una ventana vacía sería un archivo de 0 bytes).
     */
    fun inicio(total: Long, fraccion: Float): Long {
        if (total <= PAQUETE || fraccion <= 0f) return 0L
        // En Double a propósito: un Float tiene 24 bits de mantisa y un archivo de 1 GB no le
        // entra, así que el byte calculado se iba hasta ~64 bytes del pedido (y con él, el
        // desfase de tiempo que se le suma al reloj).
        val crudo = (total.toDouble() * fraccion.coerceIn(0f, 1f).toDouble()).toLong()
        val tope = total - PAQUETE
        return (crudo.coerceIn(0L, tope) / PAQUETE) * PAQUETE
    }

    /** Tamaño del archivo virtual que ve el reproductor: lo que queda desde [inicio]. */
    fun tamanoVisible(total: Long, inicio: Long): Long = (total - inicio).coerceAtLeast(0L)

    /**
     * El `Range` que hay que pedirle al origen para el [rango] que pidió el reproductor.
     *
     * El reproductor pide en coordenadas del archivo virtual (que empieza en 0) y el origen solo
     * entiende las del archivo real: acá se le suma el desfase. Sin rango pedido se pide de
     * [inicio] hasta el final, que es el archivo virtual completo.
     */
    fun rangoAlOrigen(rango: ByteRange?, inicio: Long): String {
        val desde = inicio + (rango?.start ?: 0L)
        val hasta = rango?.end?.let { inicio + it }
        return if (hasta == null) "bytes=$desde-" else "bytes=$desde-$hasta"
    }

    /**
     * El `Content-Range` que hay que devolverle al reproductor, traducido del que dio el origen.
     *
     * Devuelve null si el del origen no se entiende: en ese caso mejor no mandar ninguno que
     * mandar uno en coordenadas reales, que le haría creer al reproductor que el archivo empieza
     * en un byte que para él no existe.
     */
    fun contentRangeVisible(contentRangeDelOrigen: String?, inicio: Long): String? {
        val m = RE_CONTENT_RANGE.find(contentRangeDelOrigen?.trim().orEmpty()) ?: return null
        val desde = m.groupValues[1].toLongOrNull() ?: return null
        val hasta = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].toLongOrNull() ?: return null
        if (desde < inicio) return null
        return "bytes ${desde - inicio}-${hasta - inicio}/${tamanoVisible(total, inicio)}"
    }

    /** Total real del archivo, leído del `Content-Range` del origen (`bytes a-b/TOTAL`). */
    fun totalDelContentRange(contentRange: String?): Long {
        val m = RE_CONTENT_RANGE.find(contentRange?.trim().orEmpty()) ?: return 0L
        return m.groupValues[3].toLongOrNull() ?: 0L
    }

    private val RE_CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
}
