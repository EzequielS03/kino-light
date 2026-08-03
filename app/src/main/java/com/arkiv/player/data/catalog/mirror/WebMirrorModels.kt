package com.arkiv.player.data.catalog.mirror

/** Una fuente web (no-torrent) tal como la devuelve el backend Mirror API en `web_sources[]`. */
data class MirrorWebSource(
    val siteId: String,
    val pageUrl: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val quality: String,
    val langNorm: String,
)

/**
 * La serie COMPLETA de un sitio, tal como la tiene nuestro backend: sirve para ofrecerla como
 * "paquete" (agregar todos los capitulos de una) igual que se hace con los packs de torrent.
 */
data class MirrorWebPack(
    val siteId: String,
    val showTitle: String,
    val episodes: List<MirrorWebSource>,
) {
    val episodeCount: Int get() = episodes.size
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    /** Capitulos por temporada, en orden. El dialogo de guardar los pinta asi: un pack real trae
     *  cientos de episodios (Naruto: 220 en 4 temporadas) y una lista plana no deja ni ubicarse ni
     *  marcar "toda la temporada 2". */
    val bySeason: List<Pair<Int, List<MirrorWebSource>>>
        get() = episodes.groupBy { it.season }.toSortedMap().map { (s, eps) -> s to eps }

    /** ¿Este pack tiene un episodio que matchea (season, episode)? Mismo criterio que
     *  [MirrorWebFilter]: TV exige temporada exacta (seasonStrict=true); anime solo exige que el
     *  episodio esté en el set, sin importar season (seasonStrict=false). */
    fun coversEpisode(season: Int, episode: Int, seasonStrict: Boolean): Boolean =
        episodes.any { (!seasonStrict || season <= 0 || it.season == season) && it.episode == episode }

    companion object {
        /** Un pack por sitio, con sus episodios ordenados y el sitio mas completo primero. */
        fun groupBySite(showTitle: String, sources: List<MirrorWebSource>): List<MirrorWebPack> =
            sources.groupBy { it.siteId }
                .map { (site, eps) ->
                    MirrorWebPack(site, showTitle, eps.sortedWith(compareBy({ it.season }, { it.episode })))
                }
                .sortedByDescending { it.episodeCount }
    }
}
