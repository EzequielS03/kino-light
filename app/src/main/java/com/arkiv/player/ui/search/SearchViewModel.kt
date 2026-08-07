package com.arkiv.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArchiveApi
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.AnimeSourceProvider
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.TorrentSearchApi
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import com.arkiv.player.data.catalog.web.WebSourceEngine
import com.arkiv.player.ui.catalog.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Ruta de navegación a la pantalla de detalle según la card y el S/E opcional del REFINE.
 *  Ya NO se usa desde el wizard (Continuar ahora se queda en fase RESULTS), pero se conserva
 *  por si hace falta un handoff externo más adelante. */
fun handoffRouteFor(card: TitleCard, season: Int?, episode: Int?): String = when (card.kind) {
    "anime" -> buildString {
        append("catalog_anime/").append(card.anilistId)
        if (episode != null) append("?episode=").append(episode)
    }
    "movie" -> "cine/movie/${card.tmdbId}"
    else -> buildString { // "series"
        append("cine/tv/").append(card.tmdbId)
        if (season != null && episode != null) append("?season=").append(season).append("&episode=").append(episode)
    }
}

private val ALL_LANGS = setOf(
    TorrentLang.LATINO, TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.ENGLISH, TorrentLang.JAP_SUB,
)

/**
 * ViewModel del wizard de búsqueda unificada: Fase QUERY (TMDB + AniList + directos torrent/archive),
 * paso REFINE (S/E opcional) y fase RESULTS (búsqueda multi-fuente torrent/web/archive con S/E
 * inyectado si se dio, o solo por nombre — lo que también surface packs).
 */
class SearchViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
    private val torrentSearchApi: TorrentSearchApi,
    private val archiveApi: ArchiveApi,
    private val mirrorApiClient: com.arkiv.player.data.catalog.mirror.MirrorApiClient,
    private val animeSourceProvider: AnimeSourceProvider,
    private val webSourceEngine: WebSourceEngine,
    private val settings: SettingsStore,
    private val torrentEngine: com.arkiv.player.torrent.TorrentEngine,
) : ViewModel() {

    private val trackerScraper = com.arkiv.player.torrent.TrackerScraper()

    private val _phase = MutableStateFlow(SearchPhase.QUERY)
    val phase: StateFlow<SearchPhase> = _phase.asStateFlow()

    /**
     * Actualiza los seeders de [_sources] con el conteo REAL del swarm (scrape UDP a los trackers del
     * magnet). Muchas fuentes latino/castellano no reportan seeders y quedan en 1; esto trae el número
     * de la red. Concurrencia acotada; si un torrent no responde (solo-DHT/trackers muertos) conserva
     * el valor de la fuente. No re-ordena (respeta el orden por idioma del backend).
     */
    // Snapshot ÚNICO de seeds: cada fuente se scrapea una sola vez (no polling). Solo el tope visible
    // (~20) para no scrapear las 155. A medida que llega el número real, re-ordena por idioma→seeds
    // (los mejores suben, sin romper la preferencia de idioma).
    private val scrapedSeedKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun refreshSeeders() {
        val pending = _sources.value.filterIsInstance<PlaySource.Torrent>()
            .filter { scrapedSeedKeys.add(it.result.dedupKey) }   // solo los aún no scrapeados
            .take(20)                                             // solo el tope (lo visible al abrir)
        if (pending.isEmpty()) return
        val gate = kotlinx.coroutines.sync.Semaphore(12)
        for (ps in pending) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                gate.withPermit {
                    val r = ps.result
                    // Máximo entre scrape UDP de trackers y peers de la DHT (el scrape subcuenta: los
                    // peers suelen estar en la DHT, que es lo que ve el player al reproducir).
                    val tracker = runCatching {
                        trackerScraper.seeders(r.infoHash, com.arkiv.player.torrent.TrackerScraper.trackersFromMagnet(r.magnetUri))
                    }.getOrNull()
                    val dht = runCatching { torrentEngine.dhtPeerCount(r.infoHash) }.getOrNull()
                    val s = listOfNotNull(tracker, dht).maxOrNull() ?: return@withPermit
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val updated = _sources.value.map { x ->
                            if (x is PlaySource.Torrent && x.result.dedupKey == r.dedupKey && x.result.seeders != s)
                                PlaySource.Torrent(x.result.copy(seeders = s)) else x
                        }
                        // Re-orden: idioma primero (ordinal), luego más seeds. La UI regrupa por tipo,
                        // así que solo importa el orden relativo de los torrents.
                        val (torr, others) = updated.partition { it is PlaySource.Torrent }
                        val sorted = torr.filterIsInstance<PlaySource.Torrent>()
                            .sortedWith(compareBy<PlaySource.Torrent> { it.result.lang.ordinal }.thenByDescending { it.result.seeders })
                        _sources.value = sorted + others
                    }
                }
            }
        }
    }

    private val _titleResults = MutableStateFlow<List<TitleCard>>(emptyList())
    val titleResults: StateFlow<List<TitleCard>> = _titleResults.asStateFlow()

    private val _directResults = MutableStateFlow<List<PlaySource>>(emptyList())
    val directResults: StateFlow<List<PlaySource>> = _directResults.asStateFlow()

    private val _loadingTitles = MutableStateFlow(false)
    val loadingTitles: StateFlow<Boolean> = _loadingTitles.asStateFlow()

    private val _loadingDirect = MutableStateFlow(false)
    val loadingDirect: StateFlow<Boolean> = _loadingDirect.asStateFlow()

    private val _selected = MutableStateFlow<TitleCard?>(null)
    val selected: StateFlow<TitleCard?> = _selected.asStateFlow()

    // --- Fase RESULTS: resultados multi-fuente (torrent/web/archive) de la card elegida ---
    private val _sources = MutableStateFlow<List<PlaySource>>(emptyList())
    val sources: StateFlow<List<PlaySource>> = _sources.asStateFlow()

    private val _loadingTorrent = MutableStateFlow(false)
    val loadingTorrent: StateFlow<Boolean> = _loadingTorrent.asStateFlow()

    private val _loadingWeb = MutableStateFlow(false)
    val loadingWeb: StateFlow<Boolean> = _loadingWeb.asStateFlow()

    private val _loadingArchive = MutableStateFlow(false)
    val loadingArchive: StateFlow<Boolean> = _loadingArchive.asStateFlow()

    private val _processingNow = MutableStateFlow(false)
    val processingNow: StateFlow<Boolean> = _processingNow.asStateFlow()

    private val _processNowMessage = MutableStateFlow<String?>(null)
    val processNowMessage: StateFlow<String?> = _processNowMessage.asStateFlow()

    private val _refineSeason = MutableStateFlow<Int?>(null)
    val refineSeason: StateFlow<Int?> = _refineSeason.asStateFlow()

    private val _refineEpisode = MutableStateFlow<Int?>(null)
    val refineEpisode: StateFlow<Int?> = _refineEpisode.asStateFlow()

    private val _detail = MutableStateFlow<TmdbDetail?>(null)
    val detail: StateFlow<TmdbDetail?> = _detail.asStateFlow()

    private val _animeShow = MutableStateFlow<AnimeShow?>(null)
    val animeShow: StateFlow<AnimeShow?> = _animeShow.asStateFlow()

    private var searchJob: Job? = null
    private var sourceJob: Job? = null

    /** Lanza la búsqueda unificada de Fase 1: TMDB + anime (títulos) y torrent + archive (directos). */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _titleResults.value = emptyList()
            _directResults.value = emptyList()
            _loadingTitles.value = false
            _loadingDirect.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _loadingTitles.value = true
            _loadingDirect.value = true

            var tmdbCards: List<TitleCard> = emptyList()
            var animeCards: List<TitleCard> = emptyList()
            var tmdbDone = false
            var animeDone = false

            val tmdbJob = launch {
                tmdbCards = runCatching { tmdbApi.searchMulti(q) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                tmdbDone = true
                _titleResults.value = tmdbCards + animeCards
                if (animeDone) _loadingTitles.value = false
            }
            val animeJob = launch {
                animeCards = runCatching { aniListApi.browse(1, "SEARCH_MATCH", q, null) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                animeDone = true
                _titleResults.value = tmdbCards + animeCards
                if (tmdbDone) _loadingTitles.value = false
            }

            var torrentResults: List<PlaySource> = emptyList()
            var archiveResults: List<PlaySource> = emptyList()
            var torrentDone = false
            var archiveDone = false

            val torrentJob = launch {
                torrentResults = runCatching { torrentSearchApi.searchByText(q, ALL_LANGS, 0) }
                    .getOrDefault(emptyList())
                    .map { PlaySource.Torrent(it) }
                torrentDone = true
                _directResults.value = torrentResults + archiveResults
                if (archiveDone) _loadingDirect.value = false
            }
            val archiveJob = launch {
                archiveResults = runCatching { archiveApi.search(q, enrichEpisodes = true) }
                    .getOrDefault(emptyList())
                    .map { PlaySource.Archive(it) }
                archiveDone = true
                _directResults.value = torrentResults + archiveResults
                if (torrentDone) _loadingDirect.value = false
            }

            tmdbJob.join()
            animeJob.join()
            torrentJob.join()
            archiveJob.join()
        }
    }

    /** Entrada desde el home: arranca ya en un título, saltándose la fase de escribir. */
    fun startFromShortcut(kind: String, tmdbId: Int?, anilistId: Long?) {
        if (selected.value != null) return   // ya arrancado (no repetir en recomposición)
        viewModelScope.launch {
            val card = when {
                kind == "anime" && anilistId != null ->
                    runCatching { aniListApi.details(anilistId) }.getOrNull()?.toTitleCard()
                tmdbId != null -> {
                    val type = if (kind == "movie") "movie" else "tv"
                    runCatching { tmdbApi.detail(type, tmdbId) }.getOrNull()?.let { d ->
                        TitleCard(
                            kind = if (kind == "movie") "movie" else "series",
                            tmdbId = d.id, anilistId = null, title = d.title,
                            posterUrl = d.posterUrl, year = d.year, overview = d.overview,
                        )
                    }
                }
                else -> null
            } ?: return@launch
            pickTitle(card)   // película -> RESULTS; serie/anime -> REFINE
        }
    }

    /** Elige una card: las películas van directo a RESULTS; series/anime pasan a REFINE. */
    fun pickTitle(card: TitleCard) {
        _selected.value = card
        if (card.kind == "movie") {
            runSourceSearch(null, null)
        } else {
            _phase.value = SearchPhase.REFINE
        }
    }

    /**
     * Búsqueda multi-fuente (torrent/web/archive) de la card elegida: si viene season/episode se
     * inyectan en la búsqueda (capítulo concreto); si no, se busca solo por nombre (lo que además
     * hace salir packs de temporada/serie completa). Progresiva: cada fuente agrega resultados
     * apenas los tiene, igual que CineDetailScreen.runSearch.
     */
    fun runSourceSearch(season: Int?, episode: Int?) {
        val card = _selected.value ?: return
        sourceJob?.cancel()
        _phase.value = SearchPhase.RESULTS
        _sources.value = emptyList()
        scrapedSeedKeys.clear()   // búsqueda nueva → volver a scrapear seeds
        _loadingTorrent.value = true
        _loadingWeb.value = true
        _loadingArchive.value = true
        _refineSeason.value = season
        _refineEpisode.value = episode

        sourceJob = viewModelScope.launch {
            val maxBytes = settings.maxTorrentSizeGb.value.toLong().let { if (it <= 0) 0L else it shl 30 }

            var titles: List<String> = listOf(card.title)
            var d: TmdbDetail? = null
            var show: AnimeShow? = null

            if (card.kind == "anime") {
                show = card.anilistId?.let { id -> runCatching { aniListApi.details(id) }.getOrNull() }
                _animeShow.value = show
                titles = show?.let { runCatching { animeSourceProvider.browseTitles(it) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() } ?: listOf(card.title)
            } else {
                val tmdbType = if (card.kind == "movie") "movie" else "tv"
                d = card.tmdbId?.let { id -> runCatching { tmdbApi.detail(tmdbType, id) }.getOrNull() }
                _detail.value = d
                titles = d?.searchTitles?.takeIf { it.isNotEmpty() } ?: listOf(card.title)
            }

            fun append(new: List<PlaySource>) { _sources.value = _sources.value + new }

            launch {
                val flow: Flow<List<TorrentResult>> = when {
                    card.kind == "anime" && episode != null && show != null ->
                        animeSourceProvider.episodeSourcesFlow(show, episode, ALL_LANGS, maxBytes)
                            .map { chunk -> chunk.map { it.result } }
                    card.kind == "anime" && show != null ->
                        animeSourceProvider.browseSourcesFlow(show, ALL_LANGS, maxBytes)
                            .map { chunk -> chunk.map { it.result } }
                    card.kind == "movie" ->
                        torrentSearchApi.searchMovieFlow(titles, d?.year ?: "", ALL_LANGS, maxBytes, tmdbId = d?.id)
                    season != null && episode != null ->
                        torrentSearchApi.searchEpisodeFlow(titles, season, episode, ALL_LANGS, maxBytes, tmdbId = d?.id)
                    else ->
                        torrentSearchApi.searchSeriesBrowseFlow(titles, ALL_LANGS, maxBytes, tmdbId = d?.id)
                }
                runCatching { flow.collect { chunk -> append(chunk.map { PlaySource.Torrent(it) }) } }
                _loadingTorrent.value = false
                refreshSeeders()   // seeders REALES del swarm (las fuentes latino/cast reportan 1)
            }
            launch {
                // MIRROR primero (nuestro backend, ya crawleado): sin capitulo elegido ofrecemos la
                // serie completa como PACK; con capitulo, ese capitulo puntual. Si el titulo aun no
                // esta crawleado, caemos al scraping en vivo de siempre.
                val mirror: List<PlaySource> = when {
                    card.kind == "movie" -> emptyList()
                    episode != null -> {
                        val eps = if (card.kind == "anime") {
                            show?.let { s -> runCatching { animeSourceProvider.episodeSourcesWeb(s, episode) }.getOrDefault(emptyList()) } ?: emptyList()
                        } else {
                            // season=0 significa "cualquier temporada": MirrorWebFilter salta la
                            // exigencia de temporada y matchea solo por episodio. Sin esto, buscar
                            // "capítulo 1" sin elegir temporada NI consultaba el mirror (que ya tiene
                            // el capítulo indexado) y caía al scraping en vivo de los 10 sitios.
                            runCatching {
                                torrentSearchApi.searchEpisodeWeb(titles, season ?: 0, episode, tmdbId = d?.id)
                            }.getOrDefault(emptyList())
                        }
                        eps.map { PlaySource.Web(it) }
                    }
                    else -> {
                        val kindForMirror = if (card.kind == "anime") ContentType.ANIME else ContentType.TV
                        runCatching {
                            torrentSearchApi.seriesWebPacks(titles, kindForMirror, tmdbId = d?.id, showTitle = card.title)
                        }.getOrDefault(emptyList()).map { PlaySource.WebPack(it) }
                    }
                }
                if (mirror.isNotEmpty()) {
                    append(mirror)
                    _loadingWeb.value = false
                } else {
                    val ctx = SearchContext(
                        titles = titles,
                        type = when (card.kind) {
                            "movie" -> ContentType.MOVIE
                            "anime" -> ContentType.ANIME
                            else -> ContentType.TV
                        },
                        season = season ?: 0,
                        episode = episode ?: 0,
                        year = d?.year ?: "",
                    )
                    runCatching { webSourceEngine.searchFlow(ctx).collect { chunk -> append(chunk.map { PlaySource.Web(it) }) } }
                    _loadingWeb.value = false
                }
            }
            launch {
                // 1) BIBLIOTECA PROPIA primero: los capítulos que subimos nosotros quedan en
                //    archive.org con identificador y título hasheados, así que la búsqueda por
                //    título de abajo no los encuentra JAMÁS. El mirror los indexa por tmdb_id.
                //    Para anime el tmdb_id no viene en la card (AniList): lo resuelve el provider.
                val libTmdbId = if (card.kind == "anime") {
                    show?.let { s -> runCatching { animeSourceProvider.browseTmdbId(s) }.getOrNull() }
                } else {
                    d?.id ?: card.tmdbId
                }
                libTmdbId?.let { id ->
                    runCatching { mirrorApiClient.libraryItem(id) }.getOrNull()
                        ?.let { append(listOf(PlaySource.Archive(it))) }
                }
                // 2) archive.org público. El S/E NO se pega al texto: el Solr de archive.org exige
                //    TODOS los tokens de `title:(...)` y ningún ítem se titula "... 1x27", así que
                //    con capítulo elegido esto devolvía 0 resultados para CUALQUIER serie. Buscar
                //    solo por nombre además hace salir los packs, que traen el capítulo adentro.
                val a = runCatching { archiveApi.search(titles.first()) }.getOrDefault(emptyList())
                    .map { PlaySource.Archive(it) }
                append(a)
                _loadingArchive.value = false
            }
        }
    }

    /** Dispara el procesamiento manual (botón "Procesar ahora") del título de la fase RESULTS.
     * No-op si ya hay una corrida en curso o si la card no tiene tmdbId (anime puro de AniList
     * sin match en TMDB -- el mirror necesita tmdb_id, ver mirror/refresh.py). Al terminar
     * (éxito o error) siempre refresca la búsqueda de fuentes para que se vea lo que haya nuevo. */
    fun processNow() {
        val card = _selected.value ?: return
        val tmdbId = card.tmdbId ?: return
        if (_processingNow.value) return
        _processingNow.value = true
        viewModelScope.launch {
            val kind = when (card.kind) {
                "movie" -> ContentType.MOVIE
                "anime" -> ContentType.ANIME
                else -> ContentType.TV
            }
            val apiKey = settings.refreshApiKey.value
            val result = torrentSearchApi.refreshTitle(tmdbId, kind, card.title, card.year, apiKey)
            _processingNow.value = false
            _processNowMessage.value = if (result.ok) {
                "Listo: +${result.webSourcesAdded} web, +${result.torrentsAdded} torrents"
            } else {
                result.error ?: "No se pudo procesar"
            }
            runSourceSearch(refineSeason.value, refineEpisode.value)
        }
    }

    fun dismissProcessNowMessage() { _processNowMessage.value = null }

    /** Vuelve un paso: de RESULTS a REFINE (o QUERY si la card era película), de REFINE a QUERY. */
    fun back() {
        when (_phase.value) {
            SearchPhase.RESULTS -> {
                sourceJob?.cancel()
                _phase.value = if (_selected.value?.kind == "movie") SearchPhase.QUERY else SearchPhase.REFINE
            }
            SearchPhase.REFINE -> _phase.value = SearchPhase.QUERY
            SearchPhase.QUERY -> Unit
        }
        if (_phase.value == SearchPhase.QUERY) _selected.value = null
    }
}
