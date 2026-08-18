package com.arkiv.player.ui.search

import com.arkiv.player.AppGraph
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.SeriesItemIds
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.catalog.PackResolver
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.TorrentSource
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.torrent.EpisodeFilePicker
import com.arkiv.player.ui.catalog.PlaySource

/** Resultado de intentar preparar una reproducción: listo con episodeId, o falló con un mensaje
 *  para mostrar al usuario (mismos textos que mostraba SearchScreen antes de la extracción). */
sealed class PlaybackResult {
    data class Ready(val episodeId: String) : PlaybackResult()
    data class Failed(val message: String) : PlaybackResult()
}

/**
 * Resuelve una fuente elegida en el buscador (torrent/archive/web), la guarda en la biblioteca vía
 * [AppGraph.repository] y devuelve el episodeId listo para reproducir. Extraído VERBATIM de las
 * funciones locales que vivían en `SearchScreen` (playTorrent, playDirect, playArchiveResult,
 * playWebResult, episodeNameFor, seriesIdFor) para que TV pueda reusar exactamente la misma lógica
 * sin duplicarla. No-Compose a propósito: solo necesita el grafo de dependencias, no estado de UI.
 *
 * `preparing`/`playError`/`onPlay(epId)` siguen viviendo en el composable que llama a estos métodos:
 * este helper solo resuelve+guarda y devuelve el resultado.
 */
class SearchPlayback(private val graph: AppGraph) {

    /**
     * "Resultados directos" (torrent/archive) de la fase QUERY: sin card/season/episode todavía
     * elegidos. Molde: `playDirect` original. Web no aplica en directos de Fase 1 (fiel al original).
     */
    suspend fun playDirect(source: PlaySource): PlaybackResult {
        val epId: String? = when (source) {
            is PlaySource.Torrent -> {
                val r = source.result
                when (val src = graph.torrentSearchApi.resolveSource(r)) {
                    is TorrentSource.Magnet ->
                        // addTorrentMagnet ya devuelve el id de episodio ("torrent:<hash>::0"), no el
                        // itemId — envolverlo en firstEpisodeId() consulta WHERE itemId = "<episodeId>"
                        // y siempre da null (ver CineDetailScreen.kt, que usa el valor tal cual).
                        graph.repository.addTorrentMagnet(r.name, src.uri)
                    is TorrentSource.TorrentFile -> {
                        val meta = graph.torrentEngine.resolveTorrent(src.bytes)
                        if (meta == null) null else {
                            val videos = graph.torrentEngine.videoFiles(meta)
                                .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
                            if (videos.isEmpty()) null
                            else graph.repository.addTorrent(r.name, meta.infoHashHex, meta.infoBytes, videos)
                                .let { graph.repository.firstEpisodeId(it) }
                        }
                    }
                    null -> null
                }
            }
            is PlaySource.Archive -> graph.repository.addItem(source.item.identifier).getOrNull()
                ?.let { graph.repository.firstEpisodeId(it.identifier) }
            is PlaySource.Web -> null // no aplica en directos de Fase 1
            is PlaySource.WebPack -> null // no aplica en directos de Fase 1 (igual que Web)
            is PlaySource.Magis -> magisEpisodeId(source.result)
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

    /**
     * Reproduce un resultado de la fase RESULTS: mismo patrón que CineDetailScreen.play, salvo para
     * anime, que usa SU PROPIO agrupador (torrent:anime:<anilistId>, numeración absoluta con
     * season=1) — el mismo que AnimeShowDetailScreen.play, para no romper el agrupado por show.
     * Molde: `playTorrent` original (llamado solo cuando `selected` ya es una card, de ahí que
     * [card] no sea nullable acá — ese guard sigue viviendo en el composable).
     */
    suspend fun playTorrent(
        result: TorrentResult,
        card: TitleCard,
        detail: TmdbDetail?,
        resultTitle: String,
        resultPoster: String,
        resultDescription: String?,
        season: Int?,
        episode: Int?,
    ): PlaybackResult {
        val src = graph.torrentSearchApi.resolveSource(result)
            ?: return PlaybackResult.Failed("No se pudo obtener el torrent")

        if (card.kind == "anime" && episode != null) {
            val anilistId = card.anilistId
                ?: return PlaybackResult.Failed("No se pudo obtener el torrent")
            val meta = when (src) {
                is TorrentSource.Magnet -> graph.torrentEngine.resolveMagnet(src.uri)
                is TorrentSource.TorrentFile -> graph.torrentEngine.resolveTorrent(src.bytes)
            } ?: return PlaybackResult.Failed("No se pudo abrir el torrent (puede no tener seeds ahora)")
            val videos = graph.torrentEngine.videoFiles(meta)
                .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
            val video = EpisodeFilePicker.pick(videos.map { it.name }, season = 1, episode = episode, absoluteEpisode = episode)
                ?.let { videos[it] } ?: videos.maxByOrNull { it.sizeBytes } ?: videos.firstOrNull()
                ?: return PlaybackResult.Failed("El torrent no tiene video reproducible")
            val epId = graph.repository.addAnimeEpisode(
                anilistId = anilistId,
                showTitle = resultTitle,
                posterUrl = resultPoster,
                episodeName = result.name,
                infoHashHex = meta.infoHashHex,
                infoBytes = meta.infoBytes,
                fileIndex = video.index,
                fileSizeBytes = video.sizeBytes,
            )
            return PlaybackResult.Ready(epId)
        }

        val seriesId = seriesIdFor(card, detail)
        val epId: String? = when (src) {
            is TorrentSource.Magnet ->
                if (season != null && episode != null) {
                    val epName = episodeNameFor(detail, season, episode)
                    graph.repository.addSeriesEpisodeMagnet(seriesId, resultTitle, resultPoster, season, episode, epName, src.uri, description = resultDescription)
                } else graph.repository.addTorrentMagnet(resultTitle, src.uri, resultPoster, description = resultDescription)
            is TorrentSource.TorrentFile -> {
                val meta = graph.torrentEngine.resolveTorrent(src.bytes)
                    ?: return PlaybackResult.Failed("No se pudo leer el .torrent")
                val videos = graph.torrentEngine.videoFiles(meta)
                    .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
                val video = if (season != null && episode != null) {
                    EpisodeFilePicker.pick(videos.map { it.name }, season, episode)?.let { videos[it] }
                } else {
                    videos.maxByOrNull { it.sizeBytes } ?: videos.firstOrNull()
                } ?: return PlaybackResult.Failed("El torrent no tiene video reproducible")
                if (season != null && episode != null) {
                    val epName = episodeNameFor(detail, season, episode)
                    graph.repository.addSeriesEpisode(
                        seriesId = seriesId, showTitle = resultTitle, posterUrl = resultPoster,
                        season = season, episode = episode, episodeName = epName,
                        infoHashHex = meta.infoHashHex, infoBytes = meta.infoBytes,
                        fileIndex = video.index, fileSizeBytes = video.sizeBytes,
                        description = resultDescription,
                    )
                } else graph.repository.addTorrent(resultTitle, meta.infoHashHex, meta.infoBytes, videos, resultPoster, description = resultDescription)
                    .let { graph.repository.firstEpisodeId(it) }
            }
        }
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo obtener el torrent")
    }

    /** Molde: `playArchiveResult` original (fase RESULTS). */
    suspend fun playArchive(item: ArchiveSearchResult): PlaybackResult {
        // Para nuestras subidas el título del ítem en archive.org es el hash: le pasamos el que
        // trae el mirror para que la biblioteca no muestre "f75163…_s01e01".
        val titleOverride = item.title.takeIf { item.fromLibrary }
        val added = graph.repository
            .addItem(item.identifier, titleOverride, item.tmdbId, item.overview)
            .getOrNull()
            ?: return PlaybackResult.Failed("No se pudo abrir el ítem de archive.org")
        val epId = graph.repository.firstEpisodeId(added.identifier)
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo preparar la reproducción.")
    }

    /**
     * Reproduce una fuente web: el anime usa la numeración absoluta (igual que
     * AnimeShowDetailScreen.playWebEp) y las series TMDB la season real, pero el seriesId sale del
     * MISMO lugar para los dos ([seriesIdOf]). Molde: CineDetailScreen.playWeb / `playWebResult`.
     *
     * [mirrorSeason]: la fila local se guarda por hash de `pageUrl` -- la MISMA fila que escribe
     * [addWholeWebSeries] con la temporada real del mirror. El llamador la resuelve con
     * `WebSourceSeason.forResult`, que la toma del propio [WebResult] cuando vino del mirror
     * (`WebResult.season`), para no revertirle la temporada a esa fila y romper la búsqueda de
     * `PlaybackPreferenceStore.decide()`; queda en 1 (convención histórica) solo cuando el resultado
     * no la trae (scraping en vivo), o sea cuando tampoco hay nadie que la contradiga.
     *
     * Para series TMDB la temporada elegida en el REFINE ([season]) manda; si el usuario no eligió
     * ninguna (buscar "capítulo 5" a secas), se usa la del mirror por el mismo motivo.
     *
     * [animeEpisode]: mismo problema pero de número de episodio -- el mirror puede numerar absoluto
     * y distinto al episodio de AniList que el usuario tocó. El llamador la resuelve con
     * `WebSourceEpisode.forResult` (mismo patrón que `mirrorSeason`), con el episodio de AniList
     * como fallback cuando el resultado no lo trae y ningún pack lo conoce. Sin default: a
     * diferencia de la temporada no hay una convención universal para "episodio desconocido".
     */
    suspend fun playWeb(
        result: WebResult,
        card: TitleCard,
        detail: TmdbDetail?,
        animeShow: AnimeShow?,
        resultTitle: String,
        resultPoster: String,
        season: Int?,
        episode: Int?,
        mirrorSeason: Int = 1,
        animeEpisode: Int,
    ): PlaybackResult {
        val tvSeason = season ?: result.season
        // Los resultados del gateway no traen pageUrl: la resolución ocurre al reproducir vía
        // /v1/resolve con el ref. Se guarda el ref como fuente para que loadWeb lo detecte.
        val urlOrRef = result.gatewayRef?.takeIf { result.pageUrl.isBlank() } ?: result.pageUrl
        val epId = if (card.kind == "anime" && episode != null) {
            graph.repository.addWebSeriesEpisode(seriesIdOf(graph, card, detail, animeShow), resultTitle, resultPoster, mirrorSeason, animeEpisode, "$resultTitle - Ep $animeEpisode", urlOrRef)
        } else if (tvSeason != null && episode != null) {
            val epName = episodeNameFor(detail, tvSeason, episode)
            graph.repository.addWebSeriesEpisode(seriesIdOf(graph, card, detail, animeShow), resultTitle, resultPoster, tvSeason, episode, epName, urlOrRef)
        } else {
            graph.repository.addWebSource(urlOrRef, result.title.ifBlank { resultTitle }, resultPoster)
        }
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo abrir la fuente web")
    }

    /**
     * Agrega la serie de un pack web: un episodio de biblioteca por cada capitulo del mirror. Usa el
     * MISMO seriesId que [playWeb] (los dos por [seriesIdOf]) para no crear un item duplicado del
     * mismo show.
     *
     * Season: a diferencia de [playWeb] (episodio suelto, sin season real disponible -> fijo en 1),
     * acá SÍ hay season real por episodio (`MirrorWebSource.season`), así que se usa tal cual --
     * igual que `AnimeShowDetailScreen.addWebPack`/`downloadPack` y `CineDetailScreen.addWebPack`.
     * Necesario para que el season guardado localmente calce con el que guarda la descarga a la NUC
     * (`nuc_library_items`, ver Task 8/11): con season=1 fijo acá, un pack de anime con más de una
     * temporada guardado desde Search pisaba (`upsertEpisodes` es last-write-wins) el season real que
     * hubiera guardado la pantalla de detalle, y `PlaybackPreferenceStore.decide()` dejaba de
     * encontrar el capítulo bajado para siempre.
     *
     * [title] y [episodes] vienen del dialogo (nombre editable y seleccion), igual que `onSave` de
     * [PackDialog] para packs de torrent. Devuelve el id de [playEpisode] si se pidio uno puntual
     * (el usuario toco un capitulo), si no el del primero agregado.
     */
    suspend fun addWholeWebSeries(
        pack: MirrorWebPack,
        card: TitleCard,
        detail: TmdbDetail?,
        animeShow: AnimeShow?,
        resultPoster: String,
        title: String = pack.showTitle,
        episodes: List<MirrorWebSource> = pack.episodes,
        playEpisode: MirrorWebSource? = null,
    ): PlaybackResult {
        val seriesId = seriesIdOf(graph, card, detail, animeShow)
        var first: String? = null
        var wanted: String? = null
        for (ep in episodes) {
            val season = ep.season
            val name = ep.name.ifBlank { "Ep ${ep.episode}" }
            val id = graph.repository.addWebSeriesEpisode(
                seriesId, title, resultPoster, season, ep.episode, name, ep.pageUrl,
            )
            if (first == null) first = id
            if (playEpisode != null && ep.pageUrl == playEpisode.pageUrl) wanted = id
        }
        val target = wanted ?: first
        return if (target != null) PlaybackResult.Ready(target)
               else PlaybackResult.Failed("No se pudo agregar la serie")
    }

    /**
     * Guarda un pack (temporada/serie completa) como serie. Devuelve el itemId (no un episodeId):
     * el llamador decide si quiere el primer episodio ([graph.repository.firstEpisodeId]) o un
     * episodio puntual ("$itemId::$fileIndex"), igual que hacían `onSave`/`onPlayOne` de PackDialog
     * en SearchScreen originalmente.
     */
    suspend fun savePack(
        title: String,
        posterUrl: String,
        description: String?,
        contents: PackResolver.PackContents,
        rows: List<PackFileRow>,
    ): String = graph.repository.savePackAsSeries(title, posterUrl, description, contents.infoHashHex, contents.infoBytes, rows)

    /** Nombre real del capítulo (TMDB) para el label "Serie · SxxExx · Título" — solo aplica a
     *  series TMDB (con season+episode conocidos); anime no tiene esta fuente de nombres. */
    private suspend fun episodeNameFor(detail: TmdbDetail?, season: Int, episode: Int): String {
        val d = detail ?: return ""
        // `?.`: `seasonEpisodes` devuelve null si no se pudo consultar. Acá da lo mismo que una
        // temporada sin ese capítulo — el label cae al nombre del archivo y no se cachea nada.
        return runCatching { graph.tmdbApi.seasonEpisodes(d.id, season)?.firstOrNull { it.episode == episode }?.name }
            .getOrNull().orEmpty()
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
