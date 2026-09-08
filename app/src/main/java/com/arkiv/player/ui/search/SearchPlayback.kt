package com.arkiv.player.ui.search

import com.arkiv.player.AppGraph
import com.arkiv.player.data.SeriesItemIds
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.ui.catalog.PlaySource

/** Resultado de intentar preparar una reproducción: listo con episodeId, o falló con un mensaje
 *  para mostrar al usuario (mismos textos que mostraba SearchScreen antes de la extracción). */
sealed class PlaybackResult {
    data class Ready(val episodeId: String) : PlaybackResult()
    data class Failed(val message: String) : PlaybackResult()
}

/**
 * Resuelve una fuente elegida en el buscador (archive/magis/ditu), la guarda en la biblioteca vía
 * [AppGraph.repository] y devuelve el episodeId listo para reproducir. Extraído VERBATIM de las
 * funciones locales que vivían en `SearchScreen` (playDirect, playArchiveResult, seriesIdFor) para
 * que TV pueda reusar exactamente la misma lógica sin duplicarla. No-Compose a propósito: solo
 * necesita el grafo de dependencias, no estado de UI.
 *
 * `preparing`/`playError`/`onPlay(epId)` siguen viviendo en el composable que llama a estos métodos:
 * este helper solo resuelve+guarda y devuelve el resultado.
 */
class SearchPlayback(private val graph: AppGraph) {

    /**
     * "Resultados directos" (archive) de la fase QUERY: sin card/season/episode todavía
     * elegidos. Molde: `playDirect` original.
     */
    suspend fun playDirect(source: PlaySource): PlaybackResult {
        val epId: String? = when (source) {
            is PlaySource.Magis -> magisEpisodeId(source.result)
            is PlaySource.Ditu -> graph.repository.addDituSource(
                ref = source.result.ref,
                contentId = source.result.extra["content_id"].orEmpty(),
                title = source.result.title,
                posterUrl = source.result.extra["poster"].orEmpty(),
            )
        }
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo preparar la reproducción.")
    }

    /**
     * Guarda un resultado de Magis y devuelve su episodeId.
     *
     * El id sale del `contentId` del portal, no del ref: el ref se re-emite en cada búsqueda y un id
     * derivado de él perdería la posición de reproducción. El ref se guarda al lado y se refresca.
     *
     * La temporada sale del propio resultado (`GatewayResult.season`, que el portal manda en la
     * búsqueda) y NO se deja en null: un episodio sin `season` en un ítem donde los demás sí la
     * tienen hace que `ArkivRepository.ensureEpisodeStills` caiga a su rama de aplanar desde la
     * temporada 1 y pise los stills buenos de toda la serie (ver el KDoc de `MagisEntities.build`).
     * `0` es "el portal no la dijo", no la temporada cero, de ahí el `takeIf`.
     */
    suspend fun magisEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? {
        val contentId = r.extra["content_id"].orEmpty()
        return graph.repository.addMagisSource(
            ref = r.ref, contentId = contentId, title = r.title, episode = r.episode,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
            season = r.season.takeIf { it > 0 },
        )
    }

    /**
     * Reproduce un capítulo suelto de una temporada de Magis.
     *
     * El capítulo entra como episodio del ítem de la TEMPORADA (una tarjeta por serie, marcada como
     * serie desde el primer capítulo; ver `MagisEntities`), con su propia marca de "voy por aquí".
     */
    /**
     * Guarda el capítulo y devuelve su episodeId, sin navegar. Lo usa el guardado en lote (el botón
     * "Guardar" del diálogo de temporada, en el celu y en el TV) y el respaldo de [playMagisSeason].
     *
     * [serie] es el bloque `series` de la misma respuesta que trajo [capitulo] (null si el gateway
     * no lo pudo resolver contra TMDB), y **no tiene default a propósito**: guardar sin él era el
     * bug. `upsertEpisodes` es un `@Insert(onConflict = REPLACE)`, así que esta llamada reescribe la
     * fila ENTERA del episodio; sin `season`, marcar tres capítulos de una temporada ya guardada por
     * [playMagisSeason] les borraba el número de temporada, y desde ahí `ensureEpisodeStills` cruza
     * aplanando desde la T1 y pisa los stills de toda la serie (ver el KDoc de
     * `MagisEntities.build`). Además `episodes` es tabla sincronizada: ese `season = null` viajaba al
     * otro dispositivo.
     *
     * Los tres campos enriquecidos del capítulo (still, nombre real y sinopsis) viajan por lo mismo
     * que la temporada: el diálogo YA los tiene en la mano, y sin pasarlos un capítulo guardado sin
     * haberlo reproducido nunca quedaba sin fila en `episode_still` — o sea, tarjeta negra en la
     * biblioteca hasta que alguien abriera la serie.
     */
    suspend fun magisEpisodeIdDe(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): String? = graph.repository.addMagisSource(
        ref = capitulo.ref,
        contentId = temporada.extra["content_id"].orEmpty(),
        // El título del ítem es el de la TEMPORADA, no el del capítulo: el ítem es la serie, y el
        // capítulo se nombra aparte adentro. Pegarlos dejaba tarjetas "Daima T1 · Daima T1_1".
        title = temporada.title,
        episode = capitulo.number,
        episodeTitle = capitulo.title,
        // El capítulo hereda las imágenes de SU temporada: un GatewayEpisode no trae propias.
        posterUrl = temporada.extra["poster"].orEmpty(),
        backdropUrl = temporada.extra["backdrop"].orEmpty(),
        // El ref de la temporada es el que responde /v1/episodes; el del capítulo no.
        seriesRef = temporada.ref,
        season = serie?.seasonNumber,
        // Mismo blindaje que en [playMagisSeason]: `tmdbId` sale de un `optInt`, así que un campo
        // ausente daría 0 y ese 0 le ganaría al `?:` que preserva el tmdbId ya guardado.
        tmdbId = serie?.tmdbId?.takeIf { it > 0 },
        // El nombre de TMDB, para que la tarjeta no se quede con el del portal.
        tituloCanonico = serie?.titulo,
        still = capitulo.still,
        tmdbTitle = capitulo.tmdbTitle,
        overview = capitulo.overview,
    )

    suspend fun playMagisEpisode(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val epId = magisEpisodeIdDe(temporada, capitulo, serie)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo.")
    }

    /**
     * Guarda la temporada ENTERA y devuelve el capítulo que se tocó, para reproducirlo.
     *
     * Es el gemelo de `playPackRow` (torrent) y `saveWebPack` (web): tocar un capítulo trae la serie
     * completa a la biblioteca, no solo ese capítulo. La lista Y la serie ya las cargó la pantalla
     * con `client.episodesConSerie` al abrirse, así que esto no cuesta ninguna llamada de red.
     * **No descarga nada**: eso lo sigue haciendo el botón "Guardar".
     *
     * [serie] es el bloque `series` de esa misma respuesta (null si el gateway no pudo resolver la
     * serie contra TMDB): de ahí sale el `tmdbId` que se guarda en el ítem. Viaja como parámetro y
     * no se vuelve a pedir acá adentro — este es el camino por el que se reproduce, así que un
     * round-trip redundante es justo lo que no puede haber.
     *
     * Si la temporada no se pudo guardar (el portal no mandó `content_id`), cae al camino de
     * siempre —guardar solo el capítulo— antes que dejar al usuario sin reproducir nada.
     */
    suspend fun playMagisSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        elegido: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val guardados = graph.repository.addMagisSeason(
            contentId = temporada.extra["content_id"].orEmpty(),
            title = temporada.title,
            capitulos = capitulos.map {
                com.arkiv.player.data.CapituloDeTemporada(
                    number = it.number, title = it.title, ref = it.ref,
                    still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
                )
            },
            seriesRef = temporada.ref,
            posterUrl = temporada.extra["poster"].orEmpty(),
            backdropUrl = temporada.extra["backdrop"].orEmpty(),
            // `GatewaySerie.tmdbId` sale de un `optInt` (GatewayModels.kt): si el campo faltara daría
            // 0, no null, y ese 0 le ganaría al `?:` de `buildSeason` y borraría un tmdbId válido que
            // ya estuviera guardado. Hoy el gateway solo manda `series` cuando SÍ resolvió, así que no
            // es alcanzable, pero blindarlo acá no cuesta nada.
            tmdbId = serie?.tmdbId?.takeIf { it > 0 },
        // El nombre de TMDB, para que la tarjeta no se quede con el del portal.
        tituloCanonico = serie?.titulo,
            seasonNumber = serie?.seasonNumber,
        )
        val epId = guardados[elegido.number] ?: return playMagisEpisode(temporada, elegido, serie)
        return PlaybackResult.Ready(epId)
    }

    /** Reproduce un resultado de Magis: lo guarda y devuelve a dónde navegar. */
    suspend fun playMagis(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = magisEpisodeId(r)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Magis.")
    }

    /** Reproduce un resultado de Ditu (Caracol Streaming): lo guarda y devuelve a dónde navegar. */
    suspend fun playDitu(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = graph.repository.addDituSource(
            ref = r.ref,
            contentId = r.extra["content_id"].orEmpty(),
            title = r.title,
            posterUrl = r.extra["poster"].orEmpty(),
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Caracol.")
    }

    /** Reproduce un episodio suelto de una serie de Ditu elegido desde el diálogo. */
    suspend fun playDituEpisode(
        serie: com.arkiv.player.data.gateway.GatewayResult,
        ep: com.arkiv.player.data.gateway.GatewayEpisode,
        epIndex: Int,
        serieInfo: com.arkiv.player.data.gateway.GatewaySerie? = null,
        posterOverride: String = "",
        backdropOverride: String = "",
    ): PlaybackResult {
        val bundleId = serie.extra["content_id"].orEmpty()
        val epId = graph.repository.addDituEpisode(
            bundleId = bundleId,
            serieTitle = serie.title,
            posterUrl = serie.extra["poster"].orEmpty().ifBlank { serieInfo?.posterUrl.orEmpty().ifBlank { posterOverride } },
            backdropUrl = serie.extra["backdrop"].orEmpty().ifBlank { serieInfo?.backdropUrl.orEmpty().ifBlank { backdropOverride } },
            epRef = ep.ref,
            epTitle = ep.title,
            epNumber = ep.number,
            epSeason = serieInfo?.seasonNumber ?: 1,
            orderIndex = epIndex,
            tmdbId = serieInfo?.tmdbId?.takeIf { it > 0 },
            tituloCanonico = serieInfo?.titulo?.takeIf { it.isNotBlank() },
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el episodio de Caracol.")
    }

    /**
     * Guarda en biblioteca la selección de capítulos de una serie de Ditu.
     * Los refs son estables (IDs de Caracol), así que se pueden persistir sin problema
     * y el gateway re-resuelve una URL fresca en cada reproducción.
     */
    suspend fun saveDituSeason(
        serieResult: com.arkiv.player.data.gateway.GatewayResult,
        elegidos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        serieInfo: com.arkiv.player.data.gateway.GatewaySerie?,
        posterOverride: String = "",
        backdropOverride: String = "",
    ) {
        val bundleId = serieResult.extra["content_id"].orEmpty()
        if (bundleId.isBlank()) return
        graph.repository.addDituSeason(
            bundleId = bundleId,
            serieTitle = serieResult.title,
            posterUrl = serieResult.extra["poster"].orEmpty().ifBlank { serieInfo?.posterUrl.orEmpty().ifBlank { posterOverride } },
            backdropUrl = serieResult.extra["backdrop"].orEmpty().ifBlank { serieInfo?.backdropUrl.orEmpty().ifBlank { backdropOverride } },
            seriesRef = serieResult.ref,
            capitulos = elegidos.map {
                com.arkiv.player.data.CapituloDeTemporada(
                    number = it.number, title = it.title, ref = it.ref,
                    still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
                )
            },
            tmdbId = serieInfo?.tmdbId?.takeIf { it > 0 },
            tituloCanonico = serieInfo?.titulo?.takeIf { it.isNotBlank() },
            seasonNumber = serieInfo?.seasonNumber ?: 1,
        )
    }

}

/** id estable de "serie" TMDB para agrupar episodios (imdb si hay, si no tmdb id), delegando el
 *  criterio en [SeriesItemIds.canonicalSeriesId], que es donde vive para toda la app.
 *  internal (no private): SearchScreen.kt (mismo paquete) necesita la MISMA lógica para el seriesId
 *  de la descarga NUC (downloadWholeSeries) que ya usa addWholeWebSeries -- divergir acá reintroduce
 *  el bug de season/seriesId arreglado en el Task 11. */
internal fun seriesIdFor(card: TitleCard, detail: TmdbDetail?): String = when {
    detail != null -> SeriesItemIds.canonicalSeriesId(detail.imdbId, detail.id)
    else -> "tmdb${card.tmdbId}"
}

/**
 * seriesId canónico de una card del buscador, para anime y para el resto: **el mismo id para el
 * mismo show entre por donde entre el usuario**.
 *
 * El anime también resuelve imdb/tmdb (por el mapeo cruzado de [SeriesItemIds.animeSeriesId]) y solo
 * cae a "anilist<id>" si no hay mapeo. Antes armaba "anilist<id>" siempre, así que la misma serie
 * quedaba en la biblioteca como DOS ítems según hubiera entrado por "Anime" o por "Películas y
 * series" -- y con descargas locales eso son los mismos GB bajados dos veces.
 *
 * `suspend` porque el mapeo puede tocar disco o red; todos los llamadores ya están en corrutina.
 */
internal suspend fun seriesIdOf(
    graph: AppGraph,
    card: TitleCard,
    detail: TmdbDetail?,
    animeShow: AnimeShow?,
): String = if (card.kind == "anime") {
    SeriesItemIds.animeSeriesId(graph.animeMappingRepository, card.anilistId ?: animeShow?.id)
} else {
    seriesIdFor(card, detail)
}
