package com.arkiv.player.data.caracol

/**
 * Una representación del manifiesto DASH, reducida a lo que hace falta para elegir.
 *
 * No es `androidx.media3...Representation` a propósito: así la decisión se prueba en la JVM, sin
 * manifiesto, sin red y sin Android.
 */
data class PistaDeCaracol(
    /** Índice del AdaptationSet dentro del período. */
    val grupo: Int,
    /** Índice de la Representation dentro de su AdaptationSet. */
    val pista: Int,
    val esVideo: Boolean,
    /** Alto en píxeles; 0 en audio. */
    val alto: Int,
    val bitsPorSegundo: Int,
)

/**
 * Qué calidad se baja de un capítulo de Caracol.
 *
 * Existe porque el manifiesto ofrece seis calidades y bajarlas todas no tiene sentido: hay que
 * elegir UNA de video, y esa elección es la diferencia entre 97 MB y 1,4 GB. Medido en el celular
 * de Cristian el 2026-09-13, sobre un capítulo de 47 minutos: la más chica (256x144) pesó 97 MB y
 * bajó en 229 s; la más grande, a los ~3,4 Mbps que dio esa red contra el CDN, habría tardado casi
 * lo mismo que ver el capítulo entero. Por eso el techo por defecto no es "la mejor".
 *
 * Y la elección no es solo del momento de bajar: al REPRODUCIR hay que volver a declarar estas
 * mismas pistas o el selector elige por ancho de banda sobre un menú que miente —el manifiesto
 * anuncia las seis estén o no en disco— y pide una que nadie bajó. Eso pasó en la primera medición:
 * `init-f4-v1-x3` contra un disco que tenía la f1. Ver [DescargaDeCaracol], que es donde quedan
 * guardadas.
 */
object CalidadDeCaracol {

    /**
     * Techo de alto por defecto, en píxeles.
     *
     * 720 y no 1080: en un celular la diferencia se nota poco y el peso casi se duplica. Es un
     * techo, no un objetivo — si la serie solo tiene 480p, se baja 480p sin quejarse.
     */
    const val ALTO_OBJETIVO = 720

    /**
     * Las pistas a bajar: UNA de video y UNA de audio.
     *
     * Video: la de mayor alto que no pase de [altoObjetivo]. Si TODAS lo superan (una fuente que
     * solo publique 1080p), la más chica de todas — mejor bajar algo pesado que no ofrecer la
     * descarga.
     *
     * Audio: la de más bits. Al lado del video el audio es un error de redondeo (medido: 98 kbps
     * contra 164 kbps del video MÁS CHICO), así que ahorrar ahí no compra nada y sí se oye.
     *
     * Lista vacía si no hay video: un capítulo sin pista de video no es algo que se pueda ofrecer.
     */
    fun elegir(pistas: List<PistaDeCaracol>, altoObjetivo: Int = ALTO_OBJETIVO): List<PistaDeCaracol> {
        val videos = pistas.filter { it.esVideo }
        if (videos.isEmpty()) return emptyList()
        val video = videos.filter { it.alto <= altoObjetivo }.maxByOrNull { it.alto }
            ?: videos.minByOrNull { it.alto }
            ?: return emptyList()
        val audio = pistas.filter { !it.esVideo }.maxByOrNull { it.bitsPorSegundo }
        return listOfNotNull(video, audio)
    }

    /**
     * Cuánto va a pesar, en bytes, a partir de los bitrates declarados y la duración.
     *
     * Es una estimación y se usa para AVISAR antes de empezar, no para reservar disco: el bitrate
     * del manifiesto es el promedio declarado, y el archivo real anda cerca pero no clavado.
     */
    fun bytesEstimados(elegidas: List<PistaDeCaracol>, duracionMs: Long): Long {
        if (duracionMs <= 0L) return 0L
        val bits = elegidas.sumOf { it.bitsPorSegundo.toLong() }
        return bits * duracionMs / 1000L / 8L
    }
}
