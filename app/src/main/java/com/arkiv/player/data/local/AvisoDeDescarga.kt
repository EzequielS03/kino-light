package com.arkiv.player.data.local

/**
 * Los textos de la notificación de descarga.
 *
 * Antes la notificación se publicaba UNA vez al arrancar, decía "Descargando" y como texto el id
 * crudo del episodio (`magis:2AD2591D…::e1`), y no se volvía a tocar en los minutos que durara la
 * descarga: no había forma de saber si iba avanzando ni cuántos capítulos esperaban turno.
 */
object AvisoDeDescarga {

    /**
     * La serie y el capítulo, lo que se sepa de los dos, o null si no se sabe ninguno. Nunca el id
     * crudo (`magis:2AD2591D…::e1`), que es lo que mostraba antes y no le dice nada a nadie.
     */
    fun nombre(serie: String?, capitulo: String?): String? =
        listOf(serie, capitulo).filterNot { it.isNullOrBlank() }
            .joinToString(" · ")
            .ifBlank { null }

    /** Título de la notificación mientras baja. */
    fun titulo(serie: String?, capitulo: String?): String =
        nombre(serie, capitulo)?.let { "Bajando $it" } ?: "Bajando un capítulo"

    /**
     * Subtítulo del aviso de "Descarga completa": QUÉ capítulo terminó. El título ya dice que
     * terminó; sin esto, con varias descargas seguidas, no había forma de saber cuál era cuál.
     */
    fun listo(serie: String?, capitulo: String?): String =
        nombre(serie, capitulo) ?: "Ya lo puedes ver sin conexión"

    /**
     * Subtítulo: el porcentaje, y cuántos quedan esperando turno (la cola es de una a la vez, así
     * que sin ese dato parece que los demás capítulos se hubieran perdido).
     *
     * [fraccion] null = no se sabe cuánto falta; se dice, no se inventa un 0%.
     */
    fun subtitulo(fraccion: Float?, enCola: Int): String {
        val avance = fraccion?.let { "${(it * 100).toInt()}%" } ?: "Preparando…"
        val cola = if (enCola > 0) " · $enCola más en cola" else ""
        return avance + cola
    }
}
