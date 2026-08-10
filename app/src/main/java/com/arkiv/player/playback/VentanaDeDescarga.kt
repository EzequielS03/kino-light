package com.arkiv.player.playback

/**
 * Dónde empieza a bajar el proxy cuando la reproducción NO arranca en cero.
 *
 * El porqué, medido el 2026-08-10 con dos reproducciones del mismo ítem separadas por minutos:
 *
 * | arranque | resultado |
 * |---|---|
 * | reanudar en 10:59 de 24:05 | reproduce 2:15 y se queda sin datos |
 * | desde el principio | reproduce continuo |
 *
 * La causa es que la descarga secuencial a caché **siempre empezaba en el byte 0**. Reanudando en
 * el minuto 11 de una película de 24, hay que leer a partir del ~45% del archivo, o sea muy por
 * delante de lo descargado: `serveGrowing` tomaba entonces la rama `origen` para CADA lectura, y la
 * reproducción quedaba colgada por completo de archive.org, sin el colchón de la caché que crece.
 * Con el origen rápido no se nota; con el origen de ese día aguantaba dos minutos y se secaba.
 *
 * La ventana lo arregla haciendo que la descarga empiece cerca del punto que se va a leer. Ojo con
 * la diferencia contra [VentanaDeArchivo], que resuelve otra cosa: aquella le MIENTE al reproductor
 * (le sirve un tramo como si fuera el archivo entero, empezando en 0) y solo sirve para MPEG-TS,
 * que no tiene índice. Un MP4/MKV necesita su cabecera y su índice en los offsets reales, así que
 * acá los bytes conservan su posición verdadera y lo único que cambia es qué pedazo tenemos en
 * disco.
 */
object VentanaDeDescarga {

    /**
     * Por debajo de esto se baja desde el byte 0 igual que siempre.
     *
     * Dos razones: la descarga desde 0 alcanza un arranque cercano en pocos segundos, y solo lo que
     * empieza en 0 queda cacheado para la próxima vez (ver [esCacheable]). Ventanear por 20 s sería
     * perder la caché a cambio de nada.
     */
    const val MINIMO_MS = 60_000L

    /**
     * Cuánto ANTES del punto exacto se empieza a bajar.
     *
     * El byte calculado por regla de tres no es el byte donde el reproductor va a leer: los
     * contenedores no reparten el bitrate de forma pareja y libVLC además lee un poco hacia atrás
     * para sincronizar. Arrancar un megabyte antes cubre esa diferencia; quedarse corto significa
     * que la primera lectura ya cae fuera de la ventana y volvemos al problema que esto arregla.
     */
    const val MARGEN_ATRAS = 1L * 1024 * 1024

    /** De dónde sale un tramo pedido. */
    enum class Rama { DISCO, CRECIENDO, ORIGEN }

    /**
     * Byte donde conviene empezar la descarga para reanudar en [startMs], o 0 si no vale la pena
     * (o si falta algún dato para calcularlo con sentido).
     */
    fun byteDeArranque(startMs: Long, duracionMs: Long, total: Long): Long {
        if (startMs < MINIMO_MS || duracionMs <= 0L || total <= 0L) return 0L
        val fraccion = (startMs.toDouble() / duracionMs.toDouble()).coerceIn(0.0, 1.0)
        val crudo = (total.toDouble() * fraccion).toLong() - MARGEN_ATRAS
        return crudo.coerceIn(0L, (total - 1).coerceAtLeast(0L))
    }

    /**
     * De dónde servir el tramo `[start,end]`.
     *
     * [descargado] es el byte ABSOLUTO hasta el que hay datos (arranca valiendo [inicio], no 0):
     * gracias a eso las comparaciones son las mismas de siempre y una descarga sin ventana
     * (`inicio = 0`) se comporta exactamente como antes de que esto existiera.
     */
    fun rama(
        inicio: Long,
        descargado: Long,
        completo: Boolean,
        start: Long,
        end: Long,
        umbralAdelante: Long,
    ): Rama = when {
        // Antes de la ventana no hay NADA en disco, ni siquiera si la descarga terminó: `completo`
        // significa "llegué al final", no "tengo el archivo entero". Leerlo de disco devolvería el
        // pedazo equivocado, que es el peor error posible acá — datos válidos pero de otro lugar.
        start < inicio -> Rama.ORIGEN
        completo || descargado > end -> Rama.DISCO
        start > descargado + umbralAdelante -> Rama.ORIGEN
        else -> Rama.CRECIENDO
    }

    /** Posición dentro del archivo de caché para un byte absoluto. */
    fun offsetEnArchivo(inicio: Long, byteAbsoluto: Long): Long = byteAbsoluto - inicio

    /**
     * Si al terminar se puede marcar como cacheado. Solo lo que empieza en 0 es el archivo
     * completo; marcar una ventana haría que la próxima reproducción la sirviera entera desde
     * disco y saliera cortada.
     */
    fun esCacheable(inicio: Long): Boolean = inicio == 0L
}
