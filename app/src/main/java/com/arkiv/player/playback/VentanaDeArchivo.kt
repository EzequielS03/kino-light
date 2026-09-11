package com.arkiv.player.playback

/**
 * Servir un TRAMO del archivo como si fuera el archivo entero.
 *
 * Existe para reanudar un MPEG-TS de magis sin usar el seek de libVLC, que es el que rompe la
 * reproducción. Con TS por HTTP libVLC no conoce la duración, así que el único salto que responde
 * es el de FRACCIÓN, que busca por byte; y al hacerlo el demuxer queda con el reloj del punto
 * viejo mientras le entran datos del nuevo. Medido en device: tras el salto VLC se tragó 752 MB en
 * 330 s (17× tiempo real, o sea descartándolo todo) y el reloj no se movió ni un ms — pantalla
 * negra y "buffering 0%" para siempre.
 *
 * Abrir directamente EN el punto no tiene ese problema: es el caso que sí funciona (una película
 * que nunca se había visto arranca perfecto). Así que en vez de abrir en 0 y saltar, el proxy abre
 * una ventana que EMPIEZA en el punto pedido y le miente al reproductor sobre el tamaño: para VLC
 * es un archivo nuevo que empieza en 0, con reloj limpio y sin un solo seek.
 *
 * El desfase en tiempo lo pone el reproductor encima de lo que reporta el origen (parámetro
 * `baseOffsetMs` de [posicionAbsolutaMs], abajo).
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
     * Si conviene abrir una ventana en vez de dejar que el reproductor salte.
     *
     * Solo cuando el reproductor NO conoce la duración por su cuenta ([lengthMs] == 0, que es el
     * caso del TS por HTTP) pero nosotros sí ([duracionMs] > 0, del gateway o de la sonda), y hay
     * adónde ir. Con duración propia el seek por tiempo de libVLC es exacto y no hay nada que
     * arreglar; sin ninguna duración no se puede calcular la fracción.
     */
    fun hayQueAbrirVentana(destinoMs: Long, lengthMs: Long, duracionMs: Long): Boolean =
        destinoMs > 0L && lengthMs <= 0L && duracionMs > 0L

    /** Fracción del archivo que corresponde a [destinoMs]. Asume tasa de bits pareja. */
    fun fraccionDe(destinoMs: Long, duracionMs: Long): Float =
        if (duracionMs <= 0L) 0f else (destinoMs.toDouble() / duracionMs).coerceIn(0.0, 1.0).toFloat()

    /**
     * En qué milisegundo del CONTENIDO va el reproductor, estando dentro de una ventana.
     *
     * Se usa la FRACCIÓN por byte, no el reloj de libVLC, y las dos alternativas están medidas:
     *
     *  - El reloj no sirve. Sobre estos TS avanza a ~50× el tiempo real (medido en device:
     *    +151 s de reloj en 3 s de reloj de pared, sostenido), porque el PCR viene discontinuo y
     *    libVLC no puede armar una línea de tiempo. Ni en valor absoluto ni como diferencia contra
     *    un ancla: si la pendiente está mal, restar un origen no la arregla.
     *  - La fracción sí sigue el contenido. libVLC la calcula por byte sobre el tamaño que anuncia
     *    el proxy, y la ventana va de [baseOffsetMs] al final. Es la misma suposición de tasa de
     *    bits pareja que usa [fraccionDe] para elegir dónde abrir, así que ir y volver es coherente.
     *
     * Su defecto conocido: avanza a tirones y va por delante del punto que se está viendo, porque
     * se mueve con lo que libVLC LEE, no con lo que muestra.
     */
    fun posicionAbsolutaMs(baseOffsetMs: Long, fraccionEnVentana: Float, duracionMs: Long): Long {
        val restante = duracionMs - baseOffsetMs
        if (restante <= 0L) return baseOffsetMs.coerceAtLeast(0L)
        val f = fraccionEnVentana.coerceIn(0f, 1f).toDouble()
        return baseOffsetMs + (restante * f).toLong()
    }

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
