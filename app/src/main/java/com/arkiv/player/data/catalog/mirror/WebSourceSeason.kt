package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.web.WebResult

/**
 * Temporada canónica de una fuente web SUELTA (un [WebResult]).
 *
 * Por qué existe: la fila local del episodio se guarda con clave = hash de la `pageUrl` (ver
 * `ArkivRepository.addWebSeriesEpisode`), así que TODOS los caminos que tocan la misma `pageUrl`
 * escriben la MISMA fila, y gana el último. El camino de packs guarda/descarga con la temporada
 * real del mirror (`MirrorWebSource.season`), mientras que los caminos de "un episodio suelto"
 * inventaban `season = 1`: tocar play sobre un capítulo ya guardado desde un pack le revertía la
 * temporada a 1 y `PlaybackPreferenceStore.decide(seriesId, season, episode)` -- que busca en
 * `nuc_library_items` con la temporada de esa misma fila -- dejaba de encontrar el capítulo bajado
 * (o peor, encontraba el episodio homónimo de la temporada 1).
 *
 * La solución no es inventar una temporada sino MIRARLA donde sí está: si el resultado vino del
 * mirror, `WebResult.season` YA es la temporada real (la pone [MirrorWebMapper] desde el mismo
 * `MirrorWebSource` del que sale el pack), y esa es la fuente de verdad — funciona en cualquier
 * pantalla, haya o no packs cargados. Solo si el resultado no la trae (scraping en vivo) se mira en
 * los packs del mirror ya listados, que son la misma consulta (`titleWebSources` del mismo slug).
 */
object WebSourceSeason {

    /**
     * Temporada de [result]: la que trae el propio resultado (mirror) si la tiene; si no, la de los
     * [packs] ya cargados que conozcan su `pageUrl`; si tampoco, [fallback].
     *
     * Preferir el campo del resultado NO es un detalle de eficiencia: en el buscador
     * (`SearchScreen`/`TvSearchScreen`) los packs NUNCA están disponibles junto a los episodios
     * sueltos -- `SearchViewModel.runSourceSearch` emite `PlaySource.WebPack` solo cuando NO hay
     * capítulo elegido y `PlaySource.Web` solo cuando SÍ lo hay, y `_sources` se vacía en cada
     * búsqueda -- así que ahí la búsqueda por packs siempre caía al fallback.
     */
    fun forResult(result: WebResult, packs: List<MirrorWebPack> = emptyList(), fallback: Int = 1): Int =
        result.season ?: forPageUrl(packs, result.pageUrl, fallback)

    /**
     * Temporada real de [pageUrl] según los packs del mirror ya cargados; [fallback] (1, la
     * convención histórica) si ningún pack la conoce -- caso de las fuentes scrapeadas en vivo, que
     * no vienen del mirror y por lo tanto tampoco tienen fila de pack que las contradiga.
     */
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int = 1): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.season ?: fallback
}
