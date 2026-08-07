package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.PackDetector
import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.catalog.PackResolver
import com.arkiv.player.data.catalog.QualityLabel
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TmdbSeason
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.langColor
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPhase
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.SearchViewModel
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Clave del historial de búsquedas del TV (separado del catálogo del teléfono). */
private const val SEARCH_HISTORY_KIND = "tv"

/**
 * Buscador del TV: dos columnas navegables por control remoto — teclado en pantalla a la
 * izquierda, y a la derecha las búsquedas recientes (antes de buscar) o la grilla de títulos
 * (TMDB/anime). La búsqueda se dispara con el botón "Buscar", no al teclear. REFINE agrega el
 * selector visual de temporada/capítulo; RESULTS muestra las fuentes (packs primero) con
 * reproducción inmediata, y la lista de capítulos cuando se elige un pack.
 */
@Composable
fun TvSearchScreen(
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    shortcutKind: String? = null,
    shortcutTmdbId: Int? = null,
    shortcutAnilistId: Long? = null,
) {
    val graph = rememberGraph()
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    graph.tmdbApi, graph.aniListApi, graph.torrentSearchApi, graph.api,
                    graph.mirrorApiClient, graph.animeSourceProvider, graph.webSourceEngine,
                    graph.settings, graph.torrentEngine,
                )
            }
        },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val titleResults by vm.titleResults.collectAsStateWithLifecycle()
    val loadingTitles by vm.loadingTitles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val vmDetail by vm.detail.collectAsStateWithLifecycle()
    val vmAnimeShow by vm.animeShow.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val loadingTorrent by vm.loadingTorrent.collectAsStateWithLifecycle()
    val loadingWeb by vm.loadingWeb.collectAsStateWithLifecycle()
    val loadingArchive by vm.loadingArchive.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }

    // Reproducción/guardado de la fuente elegida en RESULTS: reusa SearchPlayback (Task 2) tal cual
    // lo hace el celu, para no duplicar la lógica de resolución/guardado. `packFor` es el sub-estado
    // "eligió un pack" dentro de la misma fase RESULTS (lista de capítulos en vez de lista de fuentes).
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }

    // Metadata "enriquecida" de la card elegida, para guardar título/póster/descripción reales
    // (no el nombre crudo del torrent) — mismo criterio que SearchScreen (teléfono).
    val resultTitle = vmDetail?.title ?: vmAnimeShow?.title ?: selected?.title ?: ""
    val resultPoster = vmDetail?.posterUrl ?: vmAnimeShow?.posterUrl ?: selected?.posterUrl ?: ""
    val resultDescription = vmDetail?.overview ?: vmAnimeShow?.description

    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    // Elegir una fuente que NO es pack reproduce de una: sin diálogo de "dónde ver" (eso es
    // solo del teléfono, que puede ir a detalle o reproducir).
    fun playTorrent(result: TorrentResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        preparing = true; playError = null
        scope.launch {
            applyResult(playback.playTorrent(result, card, vmDetail, resultTitle, resultPoster, resultDescription, season, episode))
        }
    }

    fun playArchiveResult(item: ArchiveSearchResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playArchive(item)) }
    }

    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        // Misma resolución de temporada/episodio que SearchScreen.playWebResult (los trae el propio
        // WebResult del mirror): sin esto, tocar play sobre un capítulo ya guardado desde un pack le
        // revierte la temporada o el episodio a los de AniList en la fila local y
        // PlaybackPreferenceStore.decide() deja de encontrar el capítulo bajado a la NUC. Buscarlos
        // en los packs de `sources` no funciona en este camino: esa lista siempre está vacía cuando
        // hay episodios sueltos (ver WebSourceSeason/WebSourceEpisode).
        val mirrorSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forResult(r)
        val animeEpisode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forResult(r, fallback = episode ?: 1)
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, vmDetail, vmAnimeShow, resultTitle, resultPoster, season, episode, mirrorSeason, animeEpisode),
            )
        }
    }

    // Packs no reproducen directo: abren la lista de capítulos (TvPackContent) en vez de resolver.
    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Torrent -> if (PackDetector.isPack(source.result.name)) packFor = source.result else playTorrent(source.result)
        is PlaySource.Archive -> playArchiveResult(source.item)
        is PlaySource.Web -> playWebResult(source.result)
        is PlaySource.WebPack -> webPackFor = source.pack
    }

    // Guarda los capítulos del pack web y reproduce uno: [playEpisode] si el usuario eligió uno
    // puntual, si no el primero. Gemelo de playPackRow/saveAllPack, pero sin resolver nada por red.
    fun saveWebPack(pack: MirrorWebPack, playEpisode: MirrorWebSource? = null) {
        val card = selected ?: return
        preparing = true; playError = null
        scope.launch {
            val title = resultTitle.ifBlank { pack.showTitle }
            val result = playback.addWholeWebSeries(
                pack, card, vmDetail, vmAnimeShow, resultPoster, title, pack.episodes, playEpisode,
            )
            preparing = false
            webPackFor = null
            applyResult(result)
        }
    }

    // Elegir un capítulo del pack: lo guarda TODO como serie (savePackAsSeries, mismo patrón que
    // onPlayOne del celu) y reproduce ESE capítulo puntual ("$itemId::${row.index}").
    fun playPackRow(pTitle: String, contents: PackResolver.PackContents, row: PackFileRow) {
        preparing = true; playError = null
        scope.launch {
            val id = playback.savePack(pTitle, resultPoster, resultDescription, contents, contents.rows)
            preparing = false
            packFor = null
            onPlay("$id::${row.index}")
        }
    }

    // "Guardar toda la serie": mismo guardado, pero reproduce el primer episodio.
    fun saveAllPack(pTitle: String, contents: PackResolver.PackContents) {
        preparing = true; playError = null
        scope.launch {
            val id = playback.savePack(pTitle, resultPoster, resultDescription, contents, contents.rows)
            preparing = false
            packFor = null
            val epId = graph.repository.firstEpisodeId(id)
            if (epId != null) onPlay(epId) else playError = "No se pudo preparar la reproducción."
        }
    }

    fun packFailed() {
        packFor = null
        playError = "No se pudo leer el pack (sin seeds ahora)."
    }

    // La búsqueda NO se dispara al teclear: con el control cada letra costaba una vuelta completa
    // de red (TMDB + AniList + torrents) que casi siempre se descartaba. Se busca con el botón.
    // `searched` distingue "todavía no buscó nada" (mostramos recientes) de "buscó y no hubo nada".
    var searched by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(emptyList<String>()) }
    val historyDao = remember { graph.database.searchHistoryDao() }

    suspend fun refreshRecents() {
        recents = runCatching { historyDao.recent(SEARCH_HISTORY_KIND, 12).map { it.query } }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refreshRecents() }

    fun runSearch(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        text = query
        searched = true
        vm.search(query)
        scope.launch {
            runCatching {
                historyDao.upsert(SearchHistoryEntity(query, SEARCH_HISTORY_KIND, System.currentTimeMillis()))
            }
            refreshRecents()
        }
    }

    // Atajo desde el home: entra ya posicionado en un título (mismo patrón que el teléfono).
    LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
        val k = shortcutKind ?: return@LaunchedEffect
        vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
    }

    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

    // Foco inicial en la primera tecla del teclado. Clave en `phase` (no `Unit`): REFINE/RESULTS/
    // PACK reenfocan solos al entrar, pero volver a QUERY con vm.back() destruye el nodo que
    // tenía el foco y, si esto corriera una sola vez al entrar a la pantalla, nada lo devolvería
    // — el D-pad quedaría "muerto". Repetir el efecto cada vez que se vuelve a QUERY lo evita.
    val firstKeyFocus = remember { FocusRequester() }
    LaunchedEffect(phase) {
        if (phase == SearchPhase.QUERY) {
            delay(200)
            runCatching { firstKeyFocus.requestFocus() }
        }
    }

    // Si estamos en REFINE/RESULTS (Tasks 5/6), atrás retrocede una fase dentro del wizard;
    // en la fase de títulos, atrás sale de la pantalla. Con un pack abierto dentro de RESULTS,
    // atrás vuelve primero a la lista de fuentes (no sale de la fase).
    BackHandler {
        when {
            phase == SearchPhase.RESULTS && webPackFor != null -> webPackFor = null
            phase == SearchPhase.RESULTS && packFor != null -> packFor = null
            phase != SearchPhase.QUERY -> vm.back()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        when (phase) {
            SearchPhase.QUERY -> Row(Modifier.fillMaxSize()) {
                Column(
                    // 380dp: con 24dp de padding a cada lado quedan ~332 útiles, así las 6 teclas
                    // por fila salen de ~48dp (cómodas de ver a distancia) sin cortarse.
                    modifier = Modifier.fillMaxHeight().width(380.dp).padding(24.dp),
                ) {
                    Text(
                        text.ifBlank { "Buscar…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (text.isBlank()) ArkivTextSecondary else androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    TvKeyboard(
                        text = text,
                        onTextChange = { text = it },
                        firstKeyFocus = firstKeyFocus,
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = { runSearch(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = ArkivRed,
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = ArkivRed,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Buscar", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                Column(Modifier.fillMaxSize().padding(top = 24.dp, end = 24.dp)) {
                    // Antes de buscar, este espacio (que si no queda vacío) muestra lo último que
                    // se buscó: con el control, volver a una búsqueda anterior es mucho más barato
                    // que volver a teclearla letra por letra.
                    if (!searched && recents.isNotEmpty()) {
                        Text(
                            "Búsquedas recientes",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp, end = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(recents, key = { it }) { q ->
                                Surface(
                                    onClick = { runSearch(q) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = ArkivSurfaceHigh,
                                        contentColor = ArkivTextPrimary,
                                        focusedContainerColor = ArkivRed,
                                        focusedContentColor = Color.White,
                                    ),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) { Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1) }
                                }
                            }
                        }
                        return@Row
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        if (loadingTitles) {
                            Spacer(Modifier.width(8.dp))
                            Text("Buscando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                        }
                    }
                    if (titleResults.isEmpty() && !loadingTitles && searched) {
                        Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        // Aire alrededor para que el zoom al enfocar (1.1x) no se recorte contra
                        // los bordes de la grilla ni contra las cards vecinas.
                        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(titleResults, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { card ->
                            TvPosterCard(
                                title = card.title,
                                posterUrl = card.posterUrl,
                                cardHeight = 180.dp,
                                onClick = { vm.pickTitle(card) },
                            )
                        }
                    }
                }
            }
            // Task 5: selector visual de temporada/capítulo. Task 6 reemplaza el placeholder de RESULTS.
            SearchPhase.REFINE -> selected?.let { card ->
                TvRefineContent(
                    card = card,
                    vmDetail = vmDetail,
                    vmAnimeShow = vmAnimeShow,
                    tmdbApi = graph.tmdbApi,
                    aniListApi = graph.aniListApi,
                    onAllSeries = { vm.runSourceSearch(null, null) },
                    onPickEpisode = { season, episode -> vm.runSourceSearch(season, episode) },
                )
            }
            // Task 6: lista de fuentes (packs primero) con reproducción inmediata al elegir una
            // suelta; un pack abre TvPackContent (lista de capítulos) en vez de reproducir directo.
            SearchPhase.RESULTS -> {
                val currentPack = packFor
                val currentWebPack = webPackFor
                if (currentWebPack != null) {
                    TvWebPackContent(
                        pack = currentWebPack,
                        title = resultTitle.ifBlank { currentWebPack.showTitle },
                        posterUrl = resultPoster,
                        preparing = preparing,
                        onSaveAll = { saveWebPack(currentWebPack) },
                        onPlayOne = { ep -> saveWebPack(currentWebPack, ep) },
                    )
                } else if (currentPack != null) {
                    TvPackContent(
                        result = currentPack,
                        packResolver = graph.packResolver,
                        defaultTitle = "$resultTitle — Pack",
                        posterUrl = resultPoster,
                        preparing = preparing,
                        onSaveAll = { title, contents -> saveAllPack(title, contents) },
                        onPlayOne = { title, contents, row -> playPackRow(title, contents, row) },
                        onFailed = { packFailed() },
                    )
                } else {
                    TvResultsContent(
                        title = resultTitle,
                        posterUrl = resultPoster,
                        season = refineSeason,
                        episode = refineEpisode,
                        sources = sources,
                        loadingTorrent = loadingTorrent,
                        loadingWeb = loadingWeb,
                        loadingArchive = loadingArchive,
                        preparing = preparing,
                        playError = playError,
                        onSelect = { source -> playResult(source) },
                    )
                }
            }
        }
    }
}

/**
 * Fase REFINE del TV: selector visual navegable por control remoto — temporadas en fila
 * horizontal (series TMDB) o lista de episodios (anime), con "Toda la serie" siempre arriba
 * para saltar directo a los packs. Nada de teclado numérico: en el control, escribir un número
 * es tedioso, así que todo se elige con foco/click.
 *
 * Reutiliza `vm.detail`/`vm.animeShow` cuando el ViewModel ya los cargó (p. ej. al volver de
 * RESULTS con `back()`); si todavía están vacíos —primera vez que se entra a REFINE, porque
 * [SearchViewModel.runSourceSearch] recién los llena cuando se dispara la búsqueda de fuentes—
 * los pide acá mismo con `tmdbApi.detail`/`aniListApi.details` para no bloquear el selector.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRefineContent(
    card: TitleCard,
    vmDetail: TmdbDetail?,
    vmAnimeShow: AnimeShow?,
    tmdbApi: TmdbApi,
    aniListApi: AniListApi,
    onAllSeries: () -> Unit,
    onPickEpisode: (season: Int?, episode: Int) -> Unit,
) {
    var localDetail by remember(card) { mutableStateOf<TmdbDetail?>(null) }
    var localAnimeShow by remember(card) { mutableStateOf<AnimeShow?>(null) }

    val effectiveDetail = vmDetail?.takeIf { it.id == card.tmdbId } ?: localDetail
    val effectiveAnimeShow = vmAnimeShow?.takeIf { it.id == card.anilistId } ?: localAnimeShow

    // Solo pide lo que el VM no tenga ya (evita refetch al volver de RESULTS con back()).
    LaunchedEffect(card.tmdbId, vmDetail) {
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        if (card.kind != "series") return@LaunchedEffect
        if (vmDetail?.id == tmdbId) return@LaunchedEffect
        localDetail = runCatching { tmdbApi.detail("tv", tmdbId) }.getOrNull()
    }
    LaunchedEffect(card.anilistId, vmAnimeShow) {
        val anilistId = card.anilistId ?: return@LaunchedEffect
        if (card.kind != "anime") return@LaunchedEffect
        if (vmAnimeShow?.id == anilistId) return@LaunchedEffect
        localAnimeShow = runCatching { aniListApi.details(anilistId) }.getOrNull()
    }

    var selectedSeason by remember(card) { mutableStateOf<Int?>(null) }
    var episodesBySeason by remember(card) { mutableStateOf<Map<Int, List<TmdbEpisode>>>(emptyMap()) }
    var loadingEpisodes by remember(card) { mutableStateOf(false) }

    // Preselecciona la primera temporada "real" (salta especiales = temporada 0) apenas se conocen.
    LaunchedEffect(effectiveDetail) {
        if (selectedSeason == null) {
            val seasons = effectiveDetail?.seasons.orEmpty()
            selectedSeason = seasons.firstOrNull { it.seasonNumber >= 1 }?.seasonNumber
                ?: seasons.firstOrNull()?.seasonNumber
        }
    }

    // Carga los capítulos de la temporada enfocada/elegida; cachea por temporada para no repetir
    // el fetch al ir y volver entre temporadas ya vistas.
    // `selectedSeason` cambia con el FOCO (onFocus del chip), así que recorrer las temporadas con
    // el D-pad reinicia este efecto una vez por chip. Dos fixes acá:
    //  - delay(250) al principio, ANTES de tocar `loadingEpisodes` o el caché: si el usuario sigue
    //    scrubbeando, cada reinicio cancela la corrutina anterior durante el delay y nunca llega a
    //    disparar el fetch de TMDB — evita una llamada por chip.
    //  - `loadingEpisodes` solo se pone en true DESPUÉS del chequeo de caché, y el fetch va en
    //    try/finally: si la temporada ya estaba cacheada, nunca se toca el flag; si el fetch se
    //    cancela a mitad de camino (foco se movió a otra temporada), el finally lo vuelve a false
    //    igual, así nunca queda pegado en "Cargando…".
    LaunchedEffect(selectedSeason, card.tmdbId) {
        val season = selectedSeason ?: return@LaunchedEffect
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        delay(250)
        if (episodesBySeason.containsKey(season)) return@LaunchedEffect
        loadingEpisodes = true
        try {
            val eps = runCatching { tmdbApi.seasonEpisodes(tmdbId, season) }.getOrDefault(emptyList())
            episodesBySeason = episodesBySeason + (season to eps)
        } finally {
            loadingEpisodes = false
        }
    }

    // Foco inicial en "Toda la serie" (Step 2 del brief).
    val allSeriesFocus = remember(card) { FocusRequester() }
    LaunchedEffect(card) {
        delay(200)
        runCatching { allSeriesFocus.requestFocus() }
    }

    val seasons = effectiveDetail?.seasons.orEmpty()
    val currentEpisodes = selectedSeason?.let { episodesBySeason[it] }.orEmpty()
    val animeTotal = effectiveAnimeShow?.episodes ?: 0

    LazyColumn(
        // El margen va DENTRO de la lista (contentPadding), no como padding externo: al enfocar,
        // las filas hacen zoom (1.1x) y con el margen por fuera la lista las recortaba contra su
        // propio borde. Así el zoom se dibuja sobre ese margen en vez de cortarse.
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row {
                Box(
                    modifier = Modifier.height(160.dp).width(160.dp * 2f / 3f)
                        .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                ) {
                    if (card.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = card.posterUrl,
                            contentDescription = card.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                    Text(card.title, style = MaterialTheme.typography.headlineMedium, color = ArkivTextPrimary)
                    if (card.year.isNotBlank()) {
                        Text(
                            card.year,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onAllSeries,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(allSeriesFocus),
            ) { Text("Toda la serie") }
        }

        when (card.kind) {
            "series" -> {
                if (seasons.isNotEmpty()) {
                    item {
                        Text(
                            "Temporadas",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(seasons, key = { it.seasonNumber }) { season ->
                                TvSeasonChip(
                                    season = season,
                                    selected = season.seasonNumber == selectedSeason,
                                    onFocus = { selectedSeason = season.seasonNumber },
                                    onClick = { selectedSeason = season.seasonNumber },
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                        ) {
                            Text("Capítulos", style = MaterialTheme.typography.titleMedium, color = ArkivTextPrimary)
                            if (loadingEpisodes) {
                                Spacer(Modifier.width(8.dp))
                                Text("Cargando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                            }
                        }
                    }
                    items(currentEpisodes, key = { it.episode }) { ep ->
                        TvRefineRow(
                            label = "E${ep.episode} · ${ep.name}",
                            onClick = { onPickEpisode(selectedSeason, ep.episode) },
                        )
                    }
                } else {
                    item {
                        Text(
                            if (effectiveDetail == null) "Cargando temporadas…" else "Sin temporadas disponibles.",
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            "anime" -> {
                if (animeTotal > 0) {
                    item {
                        Text(
                            "Episodios",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items((1..animeTotal).toList(), key = { it }) { n ->
                        TvRefineRow(
                            label = "Episodio $n",
                            onClick = { onPickEpisode(null, n) },
                        )
                    }
                } else {
                    item {
                        Text(
                            if (effectiveAnimeShow == null) "Cargando episodios…" else "Cantidad de episodios desconocida — usá \"Toda la serie\".",
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            else -> Unit // "movie" no llega a REFINE: pickTitle() la manda directo a RESULTS.
        }
    }
}

/** Chip de temporada de la fila horizontal ("T1", "T2"… o "Especiales" para la temporada 0). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeasonChip(
    season: TmdbSeason,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ArkivRed else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White)),
        ),
    ) {
        Text(
            text = if (season.seasonNumber == 0) "Especiales" else "T${season.seasonNumber}",
            style = MaterialTheme.typography.titleSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Fila navegable de un capítulo/episodio elegible ("E3 · Nombre" o "Episodio 12"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRefineRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White)),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

/**
 * Fase RESULTS del TV: lista vertical ÚNICA de fuentes (torrent/web/archive) — a diferencia del
 * teléfono, que las agrupa en secciones colapsables por tipo, acá van todas juntas porque el
 * D-pad navega mejor una sola lista que saltar entre secciones. Los packs van primero
 * (sortedByDescending es estable: conserva el orden de relevancia recibido dentro de cada grupo,
 * mismo criterio que packsFirst() del celu). Elegir una fuente suelta reproduce YA
 * (SearchPlayback vía onSelect, sin diálogo de "dónde ver"); un pack lo maneja el padre
 * (TvSearchScreen) mostrando TvPackContent en su lugar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvResultsContent(
    title: String,
    posterUrl: String,
    season: Int?,
    episode: Int?,
    sources: List<PlaySource>,
    loadingTorrent: Boolean,
    loadingWeb: Boolean,
    loadingArchive: Boolean,
    preparing: Boolean,
    playError: String?,
    onSelect: (PlaySource) -> Unit,
) {
    // distinctBy(sourceKey) es belt-and-braces: el pipeline de arriba ya debería llegar sin
    // duplicados, pero WebSourceEngine no dedupea y el pack/anime tampoco pasa por finalize(),
    // así que esto es lo que evita el crash de Compose por keys repetidas si algo se cuela.
    val ordered = remember(sources) {
        sources
            .sortedByDescending { it is PlaySource.Torrent && PackDetector.isPack(it.result.name) }
            .distinctBy { sourceKey(it) }
    }
    val anyLoading = loadingTorrent || loadingWeb || loadingArchive

    // Foco inicial en la primera fuente apenas aparece la primera tanda (progresiva: no le vuelve
    // a robar el foco al usuario cuando llegan más resultados después).
    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(ordered.isNotEmpty()) {
        if (ordered.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Un intento de reproducción fallido deja `preparing` en false pero, como `focusedOnce` ya es
    // true, el efecto de arriba no vuelve a disparar: la fila queda deshabilitada durante
    // `preparing` (Surface no-focusable) y al reactivarse nada pide el foco de nuevo — el usuario
    // ve el error y el D-pad no responde. Reenfocar acá cuando aparece un error nuevo lo arregla.
    LaunchedEffect(playError) {
        if (playError != null && ordered.isNotEmpty()) {
            runCatching { firstFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            // El margen va DENTRO de la lista (contentPadding), no como padding externo: al enfocar,
            // las filas hacen zoom (1.1x) y con el margen por fuera la lista las recortaba contra su
            // propio borde. Así el zoom se dibuja sobre ese margen en vez de cortarse.
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row {
                    Box(
                        modifier = Modifier.height(140.dp).width(140.dp * 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                    ) {
                        if (posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArkivTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (season != null && episode != null) {
                            Text(
                                "T$season · E$episode",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (playError != null) {
                item {
                    Text(
                        playError,
                        color = ArkivRed,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                    Text("Fuentes", style = MaterialTheme.typography.titleMedium, color = ArkivTextPrimary)
                    if (anyLoading) {
                        Spacer(Modifier.width(8.dp))
                        Text("Buscando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                    }
                }
            }

            if (ordered.isEmpty() && !anyLoading) {
                item {
                    Text(
                        "No se encontraron fuentes. Volvé atrás y probá con otra temporada/capítulo, o sin especificar ninguno.",
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            itemsIndexed(ordered, key = { _, s -> sourceKey(s) }) { index, source ->
                TvSourceRow(
                    source = source,
                    enabled = !preparing,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onSelect(source) },
                )
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("Preparando…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Key estable y ÚNICA para la lista de fuentes (evita "saltos" de foco al llegar resultados
 *  nuevos, y evita el crash de Compose por keys duplicadas en un lazy list).
 *  - Torrent: `dedupKey` (infoHash normalizado, o magnet/downloadUrl/name como fallback) — es la
 *    misma identidad que ya usa el pipeline de arriba para deduplicar (`distinctBy { it.dedupKey }`
 *    en TorrentSearchApi), así que dos torrents con el mismo release name pero infoHash distinto
 *    siguen teniendo keys distintas.
 *  - Archive: `identifier` — id único de archive.org por definición.
 *  - Web: `pageUrl` — URL de la página de origen, única por resultado (es lo que WebSourceEngine
 *    usa como identidad del resultado); los resultados web no pasan por ningún de-dup upstream. */
private fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Torrent -> "torrent-${s.result.dedupKey}"
    is PlaySource.Archive -> "archive-${s.item.identifier}"
    is PlaySource.Web -> "web-${s.result.pageUrl}"
    is PlaySource.WebPack -> "webpack-${s.pack.siteId}-${s.pack.showTitle}"
}

/** Fila de una fuente: etiqueta de origen (TORRENT/WEB/ARCHIVE), nombre, idioma/calidad/seeds/tamaño
 *  y badge PACK cuando corresponde — mismo contenido que SourceRow del celu, con estilos de TV. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSourceRow(
    source: PlaySource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (tag, tagColor) = when (source) {
        is PlaySource.Torrent -> "TORRENT" to ArkivRed
        is PlaySource.Archive -> "ARCHIVE" to Color(0xFF80CBC4)
        is PlaySource.Web -> "WEB" to Color(0xFFB39DDB)
        is PlaySource.WebPack -> "WEB" to Color(0xFFB39DDB)
    }
    val isPack = source is PlaySource.WebPack ||
        (source is PlaySource.Torrent && PackDetector.isPack(source.result.name))

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(tagColor.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Text(tag, color = tagColor, style = MaterialTheme.typography.labelSmall) }

            Column(Modifier.weight(1f)) {
                when (source) {
                    is PlaySource.Torrent -> {
                        val r = source.result
                        Text(r.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val q = QualityLabel.extract(r.name)
                        Text(
                            "${r.lang.label}${if (q.isNotBlank()) "  ·  $q" else ""}  ·  ${r.seeders} seeds${if (r.sizeLabel.isNotBlank()) "  ·  ${r.sizeLabel}" else ""}",
                            color = langColor(r.lang), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is PlaySource.Archive -> {
                        Text(source.item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Archive.org${if (source.item.year.isNotBlank()) "  ·  ${source.item.year}" else ""}",
                            color = Color(0xFF80CBC4), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is PlaySource.Web -> {
                        val r = source.result
                        val extra = buildString {
                            append(r.siteName)
                            if (r.language.isNotBlank()) append("  ·  ").append(r.language)
                            if (r.quality.isNotBlank()) append("  ·  ").append(r.quality)
                        }
                        Text(r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(extra, color = Color(0xFFB39DDB), style = MaterialTheme.typography.labelSmall)
                    }
                    is PlaySource.WebPack -> {
                        val p = source.pack
                        Text(p.showTitle, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${p.episodeCount} capítulos" +
                                (if (p.seasons.size > 1) "  ·  ${p.seasons.size} temporadas" else "") +
                                "  ·  ${p.siteId}",
                            color = Color(0xFFB39DDB), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            if (isPack) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFB74D).copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) { Text("PACK", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * Fase RESULTS · pack elegido: resuelve el pack (torrent/magnet, puede tardar ~45s buscando peers)
 * y muestra sus capítulos navegables con el D-pad — SIN checkboxes (eso es solo del teléfono).
 * Elegir un capítulo guarda el pack COMPLETO como serie (savePackAsSeries) y reproduce ESE
 * capítulo puntual; "Guardar toda la serie" hace el mismo guardado y reproduce el primero. Si no
 * se puede leer el pack, [onFailed] avisa al padre para que muestre el mensaje y vuelva a la lista
 * de fuentes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPackContent(
    result: TorrentResult,
    packResolver: PackResolver,
    defaultTitle: String,
    posterUrl: String,
    preparing: Boolean,
    onSaveAll: (title: String, contents: PackResolver.PackContents) -> Unit,
    onPlayOne: (title: String, contents: PackResolver.PackContents, row: PackFileRow) -> Unit,
    onFailed: () -> Unit,
) {
    var contents by remember(result) { mutableStateOf<PackResolver.PackContents?>(null) }
    var failed by remember(result) { mutableStateOf(false) }

    LaunchedEffect(result) {
        val c = try {
            packResolver.resolve(result)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            null
        }
        if (c == null) failed = true else contents = c
    }
    LaunchedEffect(failed) { if (failed) onFailed() }

    val c = contents

    // Foco inicial en "Guardar toda la serie" apenas se resuelve el pack (mismo patrón que el
    // foco en "Toda la serie" de TvRefineContent).
    val saveAllFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(c != null) {
        if (c != null && !focusedOnce) {
            focusedOnce = true
            delay(150)
            runCatching { saveAllFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            // El margen va DENTRO de la lista (contentPadding), no como padding externo: al enfocar,
            // las filas hacen zoom (1.1x) y con el margen por fuera la lista las recortaba contra su
            // propio borde. Así el zoom se dibuja sobre ese margen en vez de cortarse.
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row {
                    Box(
                        modifier = Modifier.height(140.dp).width(140.dp * 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                    ) {
                        if (posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = defaultTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            defaultTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArkivTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (c == null) {
                item {
                    Text(
                        "Leyendo el pack…",
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                item {
                    Button(
                        onClick = { onSaveAll(defaultTitle, c) },
                        enabled = !preparing,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(saveAllFocus),
                    ) { Text("Guardar toda la serie") }
                }
                item {
                    Text(
                        "Capítulos",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArkivTextPrimary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                }
                items(c.rows, key = { it.index }) { row ->
                    TvPackRow(row = row, enabled = !preparing, onClick = { onPlayOne(defaultTitle, c, row) })
                }
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("Preparando…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Fase RESULTS · pack WEB elegido: gemelo de [TvPackContent] para la serie completa que ya tiene
 * nuestro backend. No resuelve nada (los capítulos vienen en el propio pack), así que abre al
 * instante y no puede fallar — de ahí que no tenga `onFailed` ni estado de carga. Sin checkboxes,
 * igual que el de torrent: elegir un capítulo guarda la serie COMPLETA y reproduce ESE capítulo;
 * "Guardar toda la serie" hace el mismo guardado y reproduce el primero.
 */
@Composable
private fun TvWebPackContent(
    pack: MirrorWebPack,
    title: String,
    posterUrl: String,
    preparing: Boolean,
    onSaveAll: () -> Unit,
    onPlayOne: (MirrorWebSource) -> Unit,
) {
    val saveAllFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(pack) {
        if (!focusedOnce) {
            focusedOnce = true
            delay(150)
            runCatching { saveAllFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row {
                    Box(
                        modifier = Modifier.height(140.dp).width(140.dp * 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                    ) {
                        if (posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArkivTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pack.episodeCount} capítulos" +
                                (if (pack.seasons.size > 1) "  ·  ${pack.seasons.size} temporadas" else "") +
                                "  ·  ${pack.siteId}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onSaveAll,
                    enabled = !preparing,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(saveAllFocus),
                ) { Text("Guardar toda la serie") }
            }
            pack.bySeason.forEach { (season, eps) ->
                item(key = "season-$season") {
                    Text(
                        if (pack.seasons.size > 1) "Temporada $season" else "Capítulos",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArkivTextPrimary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                }
                items(eps, key = { it.pageUrl }) { ep ->
                    TvWebPackRow(episode = ep, enabled = !preparing, onClick = { onPlayOne(ep) })
                }
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("Preparando…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Fila navegable de un capítulo del pack web ("E12 · Título", calidad e idioma). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvWebPackRow(episode: MirrorWebSource, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "E${episode.episode}  ${episode.name.ifBlank { "Capítulo ${episode.episode}" }}",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                episode.quality.ifBlank { null },
                episode.langNorm.ifBlank { null },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(meta, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Fila navegable de un capítulo del pack ("T1 · E2" | "Ep 1085", calidad y tamaño). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPackRow(row: PackFileRow, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(row.label, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                row.quality.ifBlank { null },
                if (row.sizeBytes > 0) "%.0f MB".format(row.sizeBytes / 1_048_576.0) else null,
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(meta, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
