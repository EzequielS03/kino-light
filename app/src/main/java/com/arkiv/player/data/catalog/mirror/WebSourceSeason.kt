package com.arkiv.player.data.catalog.mirror

/**
 * Temporada canónica de una fuente web SUELTA (un `WebResult`, que no la trae: el mapper del mirror
 * la descarta al convertir, ver [MirrorWebMapper]).
 *
 * Por qué existe: la fila local del episodio se guarda con clave = hash de la `pageUrl` (ver
 * `ArkivRepository.addWebSeriesEpisode`), así que TODOS los caminos que tocan la misma `pageUrl`
 * escriben la MISMA fila, y gana el último. El camino de packs guarda/descarga con la temporada
 * real del mirror, mientras que los caminos de "un episodio suelto" inventaban `season = 1`: tocar
 * play sobre un capítulo ya guardado desde un pack le revertía la temporada a 1 y
 * `PlaybackPreferenceStore.decide(seriesId, season, episode)` -- que busca en `nuc_library_items`
 * con la temporada de esa misma fila -- dejaba de encontrar el capítulo bajado (o peor, encontraba
 * el episodio homónimo de la temporada 1).
 *
 * La solución no es inventar una temporada sino MIRARLA donde sí está: los packs del mirror ya
 * cargados en pantalla son la misma consulta (`titleWebSources` del mismo slug) de la que salió el
 * `WebResult`, así que la `pageUrl` identifica su `MirrorWebSource` y con él su temporada real.
 */
object WebSourceSeason {

    /**
     * Temporada real de [pageUrl] según los packs del mirror ya cargados; [fallback] (1, la
     * convención histórica) si ningún pack la conoce -- caso de las fuentes scrapeadas en vivo, que
     * no vienen del mirror y por lo tanto tampoco tienen fila de pack que las contradiga.
     */
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int = 1): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.season ?: fallback
}
