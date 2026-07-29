package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType

/** Selección de torrents del backend por tipo/temporada/episodio + corte por tamaño (packs exentos). */
object MirrorFilter {

    fun select(
        torrents: List<MirrorTorrent>,
        type: ContentType,
        season: Int,
        episodeNumbers: Set<Int>,
        maxSizeBytes: Long,
    ): List<MirrorTorrent> {
        val byType = when (type) {
            ContentType.MOVIE -> torrents
            ContentType.TV -> torrents.filter { matches(it, season, episodeNumbers, seasonStrict = true) }
            ContentType.ANIME -> torrents.filter { matches(it, season, episodeNumbers, seasonStrict = false) }
        }
        return byType.filter { sizeOk(it, maxSizeBytes) }
    }

    /** Browse: todas las fuentes del título (sin filtro de episodio), respetando el corte de tamaño. */
    fun selectAll(torrents: List<MirrorTorrent>, maxSizeBytes: Long): List<MirrorTorrent> =
        torrents.filter { sizeOk(it, maxSizeBytes) }

    private fun sizeOk(t: MirrorTorrent, maxSizeBytes: Long): Boolean =
        maxSizeBytes <= 0 || t.sizeBytes <= 0 || t.sizeBytes <= maxSizeBytes || t.isPack

    private fun matches(t: MirrorTorrent, season: Int, epNums: Set<Int>, seasonStrict: Boolean): Boolean {
        if (t.isPack) {
            val start = t.episode; val end = t.episodeEnd
            if (start != null && end != null) {
                if (seasonStrict && season > 0 && t.season != null && t.season != season) return false
                return epNums.any { it in start..end }
            }
            // pack de temporada/serie completa sin rango: acepta si la temporada casa (o no se exige)
            return !seasonStrict || season <= 0 || t.season == null || t.season == season
        }
        val e = t.episode ?: return false
        if (seasonStrict && season > 0 && t.season != null && t.season != season) return false
        return e in epNums
    }
}
