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
     */
    suspend fun magisEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? {
        val contentId = r.extra["content_id"].orEmpty()
        return graph.repository.addMagisSource(
            ref = r.ref, contentId = contentId, title = r.title, episode = r.episode,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
        )
    }

    /**
     * Reproduce un capítulo suelto de una temporada de Magis.
     *
     * El id se arma con el contentId de la TEMPORADA más el número de capítulo, para que cada uno
     * tenga su propia marca de "voy por aquí" dentro de la temporada.
     */
    /** Guarda el capítulo y devuelve su episodeId, sin navegar. Lo usa el guardado en lote. */
    suspend fun magisEpisodeIdDe(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
    ): String? = graph.repository.addMagisSource(
        ref = capitulo.ref,
        contentId = temporada.extra["content_id"].orEmpty(),
        title = "${temporada.title} · ${capitulo.title}",
        episode = capitulo.number,
        // El capítulo hereda las imágenes de SU temporada: un GatewayEpisode no trae propias.
        posterUrl = temporada.extra["poster"].orEmpty(),
        backdropUrl = temporada.extra["backdrop"].orEmpty(),
    )

    suspend fun playMagisEpisode(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
    ): PlaybackResult {
        val epId = magisEpisodeIdDe(temporada, capitulo)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo.")
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
        val epId = if (card.kind == "anime" && episode != null) {
            graph.repository.addWebSeriesEpisode(seriesIdOf(graph, card, detail, animeShow), resultTitle, resultPoster, mirrorSeason, animeEpisode, "$resultTitle - Ep $animeEpisode", result.pageUrl)
        } else if (tvSeason != null && episode != null) {
            val epName = episodeNameFor(detail, tvSeason, episode)
            graph.repository.addWebSeriesEpisode(seriesIdOf(graph, card, detail, animeShow), resultTitle, resultPoster, tvSeason, episode, epName, result.pageUrl)
        } else {
            graph.repository.addWebSource(result.pageUrl, result.title.ifBlank { resultTitle }, resultPoster)
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
        return runCatching { graph.tmdbApi.seasonEpisodes(d.id, season).firstOrNull { it.episode == episode }?.name }
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
