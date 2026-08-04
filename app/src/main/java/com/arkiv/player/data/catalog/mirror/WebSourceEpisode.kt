package com.arkiv.player.data.catalog.mirror

/**
 * Episodio canónico de una fuente web SUELTA (un `WebResult`, que no lo trae: viene ya resuelto a
 * nivel de episodio por `episodeSourcesWeb`), hermano de [WebSourceSeason] -- mismo problema
 * (fila local indexada por hash de `pageUrl`, last-write-wins), aplicado a `episode` en vez de
 * `season`.
 *
 * Para anime, los caminos de "episodio suelto" (`playWebEp`/`downloadEpisode`/`SearchPlayback
 * .playWeb`) guardaban el episodio de AniList que el usuario tocó, mientras que los caminos de
 * pack guardan `MirrorWebSource.episode`, que para series de larga duración puede ser absoluto y
 * no coincidir. A diferencia de [WebSourceSeason] (fallback fijo en 1, la convención histórica de
 * season), acá no hay una convención universal para "episodio desconocido": cada llamador decide
 * su propio [fallback] -- normalmente el episodio que el usuario tocó.
 */
object WebSourceEpisode {

    /**
     * Episodio real de [pageUrl] según los packs del mirror ya cargados; [fallback] si ningún pack
     * la conoce -- caso de las fuentes scrapeadas en vivo, que no vienen del mirror y por lo tanto
     * tampoco tienen fila de pack que las contradiga.
     */
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.episode ?: fallback
}
