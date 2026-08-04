package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.web.WebResult

/**
 * Episodio canónico de una fuente web SUELTA (un [WebResult]), hermano de [WebSourceSeason] --
 * mismo problema (fila local indexada por hash de `pageUrl`, last-write-wins), aplicado a
 * `episode` en vez de `season`.
 *
 * Para anime, los caminos de "episodio suelto" (`playWebEp`/`downloadEpisode`/`SearchPlayback
 * .playWeb`) guardaban el episodio de AniList que el usuario tocó, mientras que los caminos de
 * pack guardan `MirrorWebSource.episode`, que para series de larga duración puede ser absoluto y
 * no coincidir. A diferencia de [WebSourceSeason] (fallback fijo en 1, la convención histórica de
 * season), acá no hay una convención universal para "episodio desconocido": cada llamador decide
 * su propio [fallback] -- normalmente el episodio que el usuario tocó.
 *
 * Igual que [WebSourceSeason], preferir [WebResult.episode] NO es un detalle de eficiencia: en el
 * buscador (`SearchScreen`/`TvSearchScreen`) los packs NUNCA están disponibles junto a los
 * episodios sueltos, así que ahí la búsqueda por packs (`forPageUrl`) siempre caía al fallback.
 */
object WebSourceEpisode {

    /**
     * Episodio de [result]: el que trae el propio resultado (mirror) si lo tiene; si no, el de los
     * [packs] ya cargados que conozcan su `pageUrl`; si tampoco, [fallback].
     */
    fun forResult(result: WebResult, packs: List<MirrorWebPack> = emptyList(), fallback: Int): Int =
        result.episode ?: forPageUrl(packs, result.pageUrl, fallback)

    /**
     * Episodio real de [pageUrl] según los packs del mirror ya cargados; [fallback] si ningún pack
     * la conoce -- caso de las fuentes scrapeadas en vivo, que no vienen del mirror y por lo tanto
     * tampoco tienen fila de pack que las contradiga.
     */
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.episode ?: fallback
}
