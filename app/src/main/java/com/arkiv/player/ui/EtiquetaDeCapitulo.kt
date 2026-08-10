package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.model.Episode

/**
 * Cómo se nombra un capítulo y por dónde vas, en el detalle del TV y en el del celu.
 *
 * Vive acá, compartido, porque las dos pantallas TIENEN que decir lo mismo: antes esto era una
 * función privada del detalle del TV y el del celu no numeraba nada.
 */
object EtiquetaDeCapitulo {

    /**
     * "T1 · E5" / "E5".
     *
     * Se omite el tramo que no se sepa en vez de inventarlo: es preferible "E5" solo antes que un
     * "T1 · E5" que apunte al capítulo equivocado. El orden de preferencia importa: `episode` manda
     * aunque no haya `season` —los capítulos de Magis guardan `season = null`— y recién después se
     * cae al `orderIndex`, que en packs de torrent codifica temporada*1000 + episodio y en
     * archive.org es un correlativo 0..N-1.
     */
    fun numero(ep: Episode): String {
        val temporada = ep.season
        val capitulo = ep.episode
        return when {
            temporada != null && capitulo != null -> "T$temporada · E$capitulo"
            capitulo != null -> "E$capitulo"
            ep.orderIndex >= 1000 -> "T${ep.orderIndex / 1000} · E${ep.orderIndex % 1000}"
            else -> "E${ep.orderIndex + 1}"
        }
    }

    /**
     * "Vas en E5  ·  20 episodios", o "20 episodios" si todavía no empezaste.
     *
     * [unidad] es "episodios" (TV) o "videos" (celu), que es como los llama hoy cada pantalla.
     */
    fun avance(detail: ItemDetail, unidad: String): String {
        val total = "${detail.episodes.size} $unidad"
        if (detail.progress.isEmpty() || detail.episodes.size <= 1) return total
        val donde = detail.resumeEpisode ?: return total
        return "Vas en ${numero(donde)}  ·  $total"
    }

    /**
     * "Reproducir" o "Reproducir E5".
     *
     * Nombra el capítulo que el botón va a reproducir de verdad ([ItemDetail.resumeEpisode]), que no
     * siempre es el que estás viendo: si terminaste el E5, reproduce el E6. En una película (un solo
     * episodio) no hay nada que numerar, y sin progreso todavía tampoco -- ahí no alcanza con mirar
     * si `resumeEpisode` da null, porque en cuanto hay episodios SIEMPRE devuelve uno (el primero).
     */
    fun botonReproducir(detail: ItemDetail): String {
        if (detail.episodes.size <= 1 || detail.progress.isEmpty()) return "Reproducir"
        val donde = detail.resumeEpisode ?: return "Reproducir"
        return "Reproducir ${numero(donde)}"
    }
}
