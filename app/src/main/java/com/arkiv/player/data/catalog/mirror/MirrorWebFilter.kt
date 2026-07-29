package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType

/** Selección de fuentes web del mirror por tipo/temporada/episodio (gemela de MirrorFilter, sin
 *  packs: MirrorWebSource no tiene isPack/episodeEnd). Misma estrategia que ya usan los torrents:
 *  TV exige temporada exacta; anime solo exige que el episodio esté en el set (relativo o
 *  absoluto), sin exigir temporada — esto evita depender de que la numeración de temporadas del
 *  sitio scrapeado coincida con la canónica de TMDB (a veces no coincide, ver AnimeShowDetailScreen). */
object MirrorWebFilter {
    fun select(
        sources: List<MirrorWebSource>,
        type: ContentType,
        season: Int,
        episodeNumbers: Set<Int>,
    ): List<MirrorWebSource> = when (type) {
        ContentType.MOVIE -> sources
        ContentType.TV -> sources.filter { matches(it, season, episodeNumbers, seasonStrict = true) }
        ContentType.ANIME -> sources.filter { matches(it, season, episodeNumbers, seasonStrict = false) }
    }

    private fun matches(w: MirrorWebSource, season: Int, epNums: Set<Int>, seasonStrict: Boolean): Boolean {
        if (seasonStrict && season > 0 && w.season != season) return false
        return w.episode in epNums
    }
}
