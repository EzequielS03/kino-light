package com.arkiv.player.data.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.arkiv.player.data.catalog.providers.ContentType

/** Un release de anime resuelto, con el episodio de la entrada AniList al que pertenece. */
data class AnimeSourceResult(val result: TorrentResult, val episode: Int?)

/**
 * Coordina las capas de metadata (Fribb + Simkl + TMDB + traversal AniList) para producir la spec
 * del resolver y delegar el fan-out multi-backend a [TorrentSearchApi.searchAnime]. Toda capa es
 * best-effort: si falla, se degrada a lo que haya (títulos de AniList + numeración de la entrada).
 */
class AnimeSourceProvider(
    private val aniListApi: AniListApi,
    private val simklApi: SimklApi,
    private val mappingRepo: AnimeMappingRepository,
    private val tmdbApi: TmdbApi,
    private val torrentSearchApi: TorrentSearchApi,
) {
    // Metadata constante por show (títulos + temporada TVDB + offset absoluto): cara de calcular
    // (Simkl + TMDB + hasta 12 llamadas de traversal AniList), idéntica para todos los episodios.
    private data class ShowMeta(val titles: List<String>, val tvdbSeason: Int?, val offset: Int, val tmdbId: Int?)
    private val metaCache = ConcurrentHashMap<Long, ShowMeta>()
    private val metaMutex = Mutex()

    private suspend fun showMeta(show: AnimeShow): ShowMeta {
        metaCache[show.id]?.let { return it }
        return metaMutex.withLock {
            metaCache[show.id]?.let { return it }
            val mapping = runCatching { mappingRepo.mappingFor(show.id) }.getOrNull()
            val simkl = runCatching { simklApi.infoByAniList(show.id) }.getOrNull()
            val titles = buildTitles(show, mapping, simkl)
            val offset = runCatching { aniListApi.absoluteOffset(show.id) }.getOrDefault(0)
            val tmdbId = mapping?.tmdbId ?: simkl?.tmdbId
            ShowMeta(titles, mapping?.tvdbSeason, offset, tmdbId).also { metaCache[show.id] = it }
        }
    }

    /** Fuentes de UN episodio concreto, con numeración absoluta resuelta y ranking de idioma (ruta B). */
    suspend fun episodeSources(
        show: AnimeShow,
        episode: Int,
        langs: Set<TorrentLang>,
    ): List<AnimeSourceResult> {
        val meta = showMeta(show)
        val absolute = if (meta.offset > 0) meta.offset + episode else null
        val spec = AnimeEpisodeResolver.spec(
            AnimeQueryInput(
                titles = meta.titles,
                episode = episode,
                absoluteEpisode = absolute,
                tvdbSeason = meta.tvdbSeason,
            ),
        )
        return torrentSearchApi.searchAnime(spec, langs, tmdbId = meta.tmdbId)
            .map { AnimeSourceResult(it, spec.canonicalEpisode(it.name)) }
    }

    /** Fuentes WEB del mirror para UN episodio (server-side, sin scraping on-device). Reusa la
     *  misma spec (numeración absoluta/relativa) que [episodeSources] usa para torrents. */
    suspend fun episodeSourcesWeb(show: AnimeShow, episode: Int): List<com.arkiv.player.data.catalog.web.WebResult> {
        val meta = showMeta(show)
        val absolute = if (meta.offset > 0) meta.offset + episode else null
        val spec = AnimeEpisodeResolver.spec(
            AnimeQueryInput(
                titles = meta.titles,
                episode = episode,
                absoluteEpisode = absolute,
                tvdbSeason = meta.tvdbSeason,
            ),
        )
        return torrentSearchApi.searchAnimeWeb(spec, tmdbId = meta.tmdbId)
    }

    /** Igual que [episodeSources] pero PROGRESIVO (2 fases): emite chunks apenas llegan. */
    fun episodeSourcesFlow(show: AnimeShow, episode: Int, langs: Set<TorrentLang>, maxSizeBytes: Long = 0): Flow<List<AnimeSourceResult>> = flow {
        val meta = showMeta(show)
        val absolute = if (meta.offset > 0) meta.offset + episode else null
        val spec = AnimeEpisodeResolver.spec(
            AnimeQueryInput(titles = meta.titles, episode = episode, absoluteEpisode = absolute, tvdbSeason = meta.tvdbSeason),
        )
        emitAll(
            torrentSearchApi.searchAnimeFlow(spec, langs, maxSizeBytes, tmdbId = meta.tmdbId)
                .map { chunk -> chunk.map { AnimeSourceResult(it, spec.canonicalEpisode(it.name)) } },
        )
    }

    /** Browse robusto: TODAS las fuentes del show agrupadas por episodio (para shows en emisión, ruta A). */
    suspend fun browseSources(show: AnimeShow, langs: Set<TorrentLang>): List<AnimeSourceResult> {
        val meta = showMeta(show)
        return torrentSearchApi.searchAnimeBrowse(meta.titles, langs, tmdbId = meta.tmdbId)
            .map { AnimeSourceResult(it, AnimeText.primaryEpisodeNumber(it.name)) }
    }

    /** Igual que [browseSources] pero PROGRESIVO (2 fases). */
    fun browseSourcesFlow(show: AnimeShow, langs: Set<TorrentLang>, maxSizeBytes: Long = 0): Flow<List<AnimeSourceResult>> = flow {
        val meta = showMeta(show)
        emitAll(
            torrentSearchApi.searchAnimeBrowseFlow(meta.titles, langs, maxSizeBytes, tmdbId = meta.tmdbId)
                .map { chunk -> chunk.map { AnimeSourceResult(it, AnimeText.primaryEpisodeNumber(it.name)) } },
        )
    }

    /** Conjunto de títulos para buscar: AniList (display + romaji) + español (TMDB) + alt (Simkl). */
    suspend fun browseTitles(show: AnimeShow): List<String> = showMeta(show).titles

    /** Packs web (serie completa por sitio) del mirror para este show — reusa los mismos títulos y
     *  tmdbId que [episodeSourcesWeb], sin filtrar por episodio. */
    suspend fun seriesWebPacks(show: AnimeShow): List<com.arkiv.player.data.catalog.mirror.MirrorWebPack> {
        val meta = showMeta(show)
        return torrentSearchApi.seriesWebPacks(meta.titles, ContentType.ANIME, tmdbId = meta.tmdbId, showTitle = show.title)
    }

    private suspend fun buildTitles(
        show: AnimeShow,
        mapping: AnimeMapping?,
        simkl: SimklAnimeInfo?,
    ): List<String> = buildList {
        add(show.title)
        add(show.searchTitle)
        // Título en español desde TMDB (aflora latino/castellano en los trackers on-device). tmdbId de Fribb o Simkl.
        val tmdbId = mapping?.tmdbId ?: simkl?.tmdbId
        if (tmdbId != null) {
            runCatching { tmdbApi.detail("tv", tmdbId) }.getOrNull()?.let { addAll(it.searchTitles) }
        }
        simkl?.altTitles?.let { addAll(it) }
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
