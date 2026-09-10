package com.arkiv.player.data.model

/** Una variante concreta de archivo de video dentro de un ítem de archive.org. */
data class VideoVariant(
    val path: String,        // ruta dentro del ítem, ej "carpeta/video.mkv"
    val format: String,      // "Matroska", "h.264", ...
    val sizeBytes: Long,
)

/** Un episodio/video lógico: agrupa el original (mkv) y su derivado (mp4). */
data class Episode(
    val id: String,          // estable: "<identifier>::<claveBase>"
    val itemId: String,      // identifier del ítem
    val section: String,     // carpeta (vacío si está en la raíz)
    val displayName: String, // nombre limpio para mostrar
    val orderIndex: Int,     // orden natural dentro del ítem
    val durationSeconds: Double,
    val thumbPath: String?,  // ruta de miniatura en .thumbs (o null)
    val original: VideoVariant?,   // mejor calidad (mkv) — puede faltar
    val derivative: VideoVariant?, // mp4 h.264 (compatible con Cast) — puede faltar
    /**
     * De dónde salió este episodio, cuando la fuente lo identifica: la pageUrl del capítulo si es
     * web (`ArkivRepository.addWebSeriesEpisode`) o el magnet si es torrent
     * (`addSeriesEpisodeMagnet`). Es el `torrentData` de [com.arkiv.player.data.db.EpisodeEntity]
     * expuesto al dominio, y lo necesita la UI para distinguir DOS filas del mismo (temporada,
     * capítulo) guardadas desde sitios distintos — por número solo no se pueden diferenciar.
     * Null para archive.org y para episodios viejos guardados sin este dato.
     */
    val sourceRef: String? = null,
    /**
     * Temporada y capítulo, cuando el nombre del archivo los declara (ver
     * [com.arkiv.player.data.MetadataParser.episodeNumberOf]). Null cuando no se pueden deducir.
     * Sirven para pedirle a TMDB el título real del capítulo: el nombre del episodio no está ni
     * en archive.org ni en el mirror, solo su número.
     */
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** Variante preferida para reproducir localmente: original si existe. */
    val playbackVariant: VideoVariant?
        get() = original ?: derivative

    /** Variante para Chromecast: siempre el mp4 compatible si existe. */
    val castVariant: VideoVariant?
        get() = derivative ?: original
}

/**
 * Temporada/capítulo de un episodio de serie, deducidos de los textos con los que se guardó
 * (`section` = "Temporada N", `displayName` = "TN · EM …" — ver `ArkivRepository.addWebSeriesEpisode`
 * y `addSeriesEpisodeMagnet`). No hay columnas int en la tabla, así que este parseo ES la única
 * fuente de la numeración para todo lo que cruza episodios locales contra la NUC.
 *
 * Vive acá, y no duplicado en cada llamador, para que el número de temporada/capítulo sea el MISMO
 * en cualquier sitio que lo necesite. Hoy lo usa el tilde de "ya descargado" del detalle, que
 * compara episodios locales contra la NUC por (temporada, capítulo): si el parseo divergiera del
 * de acá, un capítulo podría mostrarse como bajado sin serlo, o al revés.
 */
object EpisodeNumbering {
    /** Primer número de la sección ("Temporada 2" → 2). Null si la sección no es de serie. */
    fun seasonOf(section: String): Int? = Regex("\\d+").find(section)?.value?.toIntOrNull()

    /** Número tras la "E" del nombre ("T1 · E7  Título" → 7). Null si no hay marca de capítulo. */
    fun episodeOf(displayName: String): Int? =
        Regex("(?i)E(\\d+)").find(displayName)?.groupValues?.get(1)?.toIntOrNull()

    private val SXE = Regex("(?i)s(\\d+)\\s*e(\\d+)")
    private val TEMPORADA = Regex("(?i)\\bT\\s*(\\d+)")
    private val CAPITULO = Regex("(?i)\\bE(?:pisodio|p)?\\.?\\s*(\\d+)")

    /**
     * Rótulo de temporada/capítulo para MOSTRAR en el player ("T1 · E3", o "E7" cuando no hay
     * temporada). Null si el nombre no declara capítulo: preferimos no mostrar nada antes que
     * inventar o volcar texto sucio — en la base real hay displayName con la sinopsis entera y la
     * fecha pegadas, y otros que son puro ruido ("TPO Neon Genesis Evangelion 04 · Trapo2019 …").
     *
     * A propósito NO reusa ni amplía seasonOf/episodeOf: esos alimentan una decisión real (ver el
     * KDoc de arriba, el tilde de "ya descargado") y ensancharles el regex para tragar formatos
     * sucios la movería a ella también. Acá el peor caso es quedarse sin rótulo.
     */
    fun displayLabel(section: String?, displayName: String): String? {
        SXE.find(displayName)?.let { m ->
            val e = m.groupValues[2].toIntOrNull()
            if (e != null) {
                val s = m.groupValues[1].toIntOrNull()
                return if (s != null) "T$s · E$e" else "E$e"
            }
        }
        val episodio = CAPITULO.find(displayName)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val temporada = TEMPORADA.find(displayName)?.groupValues?.get(1)?.toIntOrNull()
            ?: section?.takeIf { it.isNotBlank() }?.let { seasonOf(it) }
        return if (temporada != null) "T$temporada · E$episodio" else "E$episodio"
    }
}

/** Un ítem de archive.org con sus videos ya agrupados. */
data class ArchiveItem(
    val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val episodes: List<Episode>,
)

/** Archivo crudo del JSON de metadata (antes de agrupar). Facilita testear el parser. */
data class RawFile(
    val name: String,
    val source: String,      // "original" | "derivative" | "metadata"
    val format: String,
    val original: String?,   // para derivados: nombre del archivo original
    val sizeBytes: Long,
    val lengthSeconds: Double,
)
