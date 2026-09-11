package com.arkiv.player.data.model

/** Un episodio/video lógico. */
data class Episode(
    val id: String,          // estable: "<identifier>::<claveBase>"
    val itemId: String,      // identifier del ítem
    val section: String,     // carpeta (vacío si está en la raíz)
    val displayName: String, // nombre limpio para mostrar
    val orderIndex: Int,     // orden natural dentro del ítem
    val durationSeconds: Double,
    val thumbPath: String?,  // ruta de miniatura en .thumbs (o null)
    /**
     * Where this episode came from, when the source records it: Ditu's `ref` (see
     * [com.arkiv.player.data.ditu.DituRef]) today, plus the page URL or magnet left by the
     * now-removed web/torrent sources in rows saved before this branch's pruning. It's the
     * `torrentData` of [com.arkiv.player.data.db.EpisodeEntity] exposed to the domain, and the UI
     * needs it to tell apart TWO rows of the same (season, chapter) saved from different sites --
     * the number alone can't tell them apart. Null for archive.org and for old episodes saved
     * without this data.
     */
    val sourceRef: String? = null,
    /**
     * Season and chapter number, set by the source when it builds the episode (see
     * `MagisEntities`/`DituEntities`). Null when the source doesn't provide them. Used to ask
     * TMDB for the chapter's real title: neither Magis nor Caracol return the episode name, only
     * its number.
     */
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Temporada/capítulo de un episodio de serie, deducidos de los textos con los que se guardó
 * (`section` = "Temporada N", `displayName` = "TN · EM …" — ver `ArkivRepository.addWebSeriesEpisode`
 * y `addSeriesEpisodeMagnet`). `section`/`displayName` no tienen columna int propia, así que este
 * parseo es la fuente de esos dos números cuando hace falta mostrarlos.
 *
 * Verificado con `grep -rn "seasonOf(\|episodeOf(" app/src/main/java`: además de [displayLabel] (vía
 * `ArkivRepository.headerInfo`, para el rótulo "T1 · E3" del encabezado del player, que reusa
 * [seasonOf] como uno de sus fallbacks de temporada), hoy los llama directo
 * `ArkivRepository.obraParaDatos`, para deducir la temporada y el capítulo de un episodio cuando
 * `EpisodeEntity.season`/`.episode` no los trae.
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
     * Tiene sus propios regexes (SXE/TEMPORADA/CAPITULO) para el capítulo y para la temporada
     * cuando el nombre la trae pegada; solo cae a [seasonOf] como último recurso, cuando ninguno
     * de esos encuentra temporada y `section` sí trae algo. Por eso no conviene ensancharle el
     * regex a [seasonOf] para que trague más formatos: sería tocar también este fallback, no solo
     * un consumidor externo — acá el peor caso de equivocarse es un rótulo raro, no perder o
     * inventar el número real.
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
