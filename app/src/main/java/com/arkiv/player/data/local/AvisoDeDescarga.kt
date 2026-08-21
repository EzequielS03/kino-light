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
     * Título: la serie y el capítulo, lo que se sepa de los dos. Nunca el id crudo
     * (`magis:2AD2591D…::e1`), que es lo que mostraba antes y no le dice nada a nadie.
     */
    fun titulo(serie: String?, capitulo: String?): String {
        val nombre = listOf(serie, capitulo).filterNot { it.isNullOrBlank() }.joinToString(" · ")
        return if (nombre.isBlank()) "Bajando un capítulo" else "Bajando $nombre"
    }

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
