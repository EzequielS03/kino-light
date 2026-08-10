package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.window.Dialog
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
import com.arkiv.player.ui.catalog.ArkivArchiveTeal
import com.arkiv.player.ui.catalog.ArkivWebViolet
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.langColor
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPhase
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.SearchViewModel
import com.arkiv.player.ui.search.SourceTab
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.countsByTab
import com.arkiv.player.ui.search.filasVisibles
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
                    graph.settings, graph.torrentEngine, graph.arkivApiClient,
                    graph.searchHistory,
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
    val loadingMagis by vm.loadingMagis.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }

    // Reproducción/guardado de la fuente elegida en RESULTS: reusa SearchPlayback (Task 2) tal cual
    // lo hace el celu, para no duplicar la lógica de resolución/guardado. `packFor` es el sub-estado
    // "eligió un pack" dentro de la misma fase RESULTS (lista de capítulos en vez de lista de fuentes).
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    // Serie tocada que todavía no eligió cómo verse (completa o por temporada). null = sin diálogo.
    var preguntarModo by remember { mutableStateOf<TitleCard?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }
    // Temporada de Magis elegida. Mismo sub-estado que los packs: un resultado de serie del portal
    // ES una temporada entera, así que abre la lista de capítulos en vez de reproducir el primero.
    var magisSeasonFor by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }

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

    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    // Guarda una temporada entera de Magis. Capítulo por capítulo, igual que el celu: cada uno es
    // un archivo aparte en el CDN y la cola ya los agrupa por serie en Descargas.
    fun saveMagisSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
    ) {
        preparing = true; playError = null
        scope.launch {
            var encolados = 0
            for (capitulo in capitulos) {
                val epId = playback.magisEpisodeIdDe(temporada, capitulo) ?: continue
                if (graph.localDownloads.enqueue(epId, "magis") ==
                    com.arkiv.player.data.local.EnqueueOutcome.QUEUED
                ) encolados++
            }
            preparing = false
            magisSeasonFor = null
            playError = when {
                encolados == 0 -> "Esos capítulos ya estaban guardados."
                encolados == capitulos.size -> null
                else -> "Se encolaron $encolados de ${capitulos.size} (el resto ya estaba)."
            }
        }
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
        is PlaySource.Magis ->
            if (source.result.extra["program_type"] in com.arkiv.player.data.gateway.MAGIS_SERIES) {
                magisSeasonFor = source.result
            } else {
                playMagisResult(source.result)
            }
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
    // Sube en cada búsqueda ejecutada. Es la llave para devolver la grilla al principio:
    // sin esto, una lista lazy conserva el scroll de la búsqueda anterior y la nueva
    // aparece empezada por la mitad, con las primeras cards fuera de pantalla.
    var busquedaNro by remember { mutableStateOf(0) }
    var recents by remember { mutableStateOf(emptyList<String>()) }
    val historyDao = remember { graph.database.searchHistoryDao() }

    suspend fun refreshRecents() {
        recents = runCatching { historyDao.recent(SEARCH_HISTORY_KIND, 12).map { it.query } }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refreshRecents() }

    /**
     * Vuelve a la pantalla de recientes sin salir del buscador.
     *
     * Antes esto era un callejón sin salida: una vez buscado algo no había forma de volver a la
     * lista de recientes. Borrar todo el texto tampoco servía — el botón se apagaba y los
     * resultados seguían en pantalla.
     */
    fun nuevaBusqueda() {
        text = ""
        searched = false
        vm.search("")
        scope.launch { refreshRecents() }
    }

    fun runSearch(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        text = query
        searched = true
        busquedaNro++
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
            phase == SearchPhase.RESULTS && magisSeasonFor != null -> magisSeasonFor = null
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
                        // Borrar hasta dejarlo vacío vuelve a las recientes. Es el gesto que ya
                        // existía (⌫) y que hasta ahora no llevaba a ningún lado.
                        onTextChange = {
                            text = it
                            if (it.isBlank() && searched) nuevaBusqueda()
                        },
                        firstKeyFocus = firstKeyFocus,
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = { runSearch(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = arkivTvSurfaceColors(),
                        border = arkivTvSurfaceBorder(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Buscar", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    // No depende de borrar el texto letra por letra: con el control eso son diez
                    // clics. Aparece recién cuando hay algo que descartar.
                    if (searched) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            onClick = { nuevaBusqueda() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Nueva búsqueda", style = MaterialTheme.typography.titleMedium)
                            }
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
                                    colors = arkivTvSurfaceColors(),
                                    border = arkivTvSurfaceBorder(),
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
                    val gridTitulos = rememberLazyGridState()
                    // Los resultados llegan en dos tandas y el ViewModel publica `tmdb + anime`:
                    // si el anime llega primero, la tanda de TMDB se INSERTA ARRIBA. Con keys, la
                    // grilla se ancla a lo que ya estabas viendo y lo nuevo queda fuera de
                    // pantalla, por encima — se ve igual que si hubiera quedado scrolleada.
                    // Mientras no hayas movido el foco a la grilla, la mantenemos arriba.
                    var grillaTocada by remember(busquedaNro) { mutableStateOf(false) }
                    LaunchedEffect(busquedaNro, titleResults) {
                        if (!grillaTocada) gridTitulos.scrollToItem(0)
                    }
                    LazyVerticalGrid(
                        state = gridTitulos,
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
                                onFocus = { grillaTocada = true },
                                // Una peli no tiene nada que elegir: va derecho a las fuentes.
                                // Una serie sí, y hasta ahora caía siempre en el selector de
                                // temporadas — con el "Toda la serie" arriba, fácil de no ver.
                                onClick = {
                                    if (card.kind == "movie") vm.pickTitle(card) else preguntarModo = card
                                },
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
                val currentMagis = magisSeasonFor
                if (currentMagis != null) {
                    TvMagisSeasonContent(
                        season = currentMagis,
                        client = graph.arkivApiClient,
                        posterUrl = resultPoster,
                        preparing = preparing,
                        onPlayOne = { capitulos, capitulo ->
                            magisSeasonFor = null
                            preparing = true; playError = null
                            scope.launch {
                                applyResult(playback.playMagisSeason(currentMagis, capitulos, capitulo))
                            }
                        },
                        onSaveAll = { capitulos -> saveMagisSeason(currentMagis, capitulos) },
                    )
                } else if (currentWebPack != null) {
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
                        loadingMagis = loadingMagis,
                        preparing = preparing,
                        playError = playError,
                        onSelect = { source -> playResult(source) },
                    )
                }
            }
        }
    }

    preguntarModo?.let { card ->
        TvModoDeSerieDialog(
            titulo = card.title,
            onSerieCompleta = {
                preguntarModo = null
                // pickTitle deja la card como `selected` (y la guarda en el historial); recién
                // entonces runSourceSearch puede buscar sus fuentes. Sin S/E se surfacean los packs.
                vm.pickTitle(card)
                vm.runSourceSearch(null, null)
            },
            onPorTemporada = {
                preguntarModo = null
                vm.pickTitle(card)
            },
            onDismiss = { preguntarModo = null },
        )
    }
}

/**
 * Qué hacer con una serie recién elegida: verla entera o entrar a elegir temporada y capítulo.
 *
 * Antes tocar una serie caía siempre en el selector de temporadas, con un "Toda la serie" arriba
 * que es fácil de no ver. Preguntarlo de frente convierte una decisión escondida en dos botones.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvModoDeSerieDialog(
    titulo: String,
    onSerieCompleta: () -> Unit,
    onPorTemporada: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primero = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { primero.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.width(560.dp).clip(RoundedCornerShape(16.dp))
                .background(ArkivSurfaceHigh).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(titulo, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2)
            Text(
                "¿Cómo la querés buscar?",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Button(
                onClick = onSerieCompleta,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth().focusRequester(primero),
            ) { Text("Ver serie completa") }
            Text(
                "Busca la serie entera: es donde salen los packs de temporada.",
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
            )
            Button(
                onClick = onPorTemporada,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Buscar por temporada")
            }
            Text(
                "Abre el selector de temporadas y capítulos.",
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
            )
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
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
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

/**
 * Fila de chips por origen ("Todo 12 · Torrent 8 · Web 3 · Archive 1"), equivalente a la de la app
 * de móvil ([com.arkiv.player.ui.search.SourceTabRow]).
 *
 * En la TV es más necesaria que en el teléfono: la lista arranca con los torrents arriba (van
 * primero por el orden de `ordered`) y con el D-pad hay que bajar a ciegas por decenas de filas
 * para descubrir si además hay web o archive. El contador lo dice de entrada.
 *
 * Siempre se pintan los cuatro chips, incluso en 0: si aparecieran y desaparecieran según van
 * llegando los resultados, el foco saltaría de chip mientras el usuario navega. Por lo mismo, un
 * origen que todavía está buscando muestra un spinner en vez de "0" — un cero prematuro se lee
 * como "no hay nada acá" cuando en realidad todavía no terminó.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSourceTabRow(
    selected: SourceTab,
    counts: Map<SourceTab, Int>,
    loading: Map<SourceTab, Boolean>,
    modifier: Modifier = Modifier,
    onSelect: (SourceTab) -> Unit,
) {
    // Scrollea: con cinco fuentes los chips ya no entran a lo ancho y el Row le sacaba espacio
    // al ultimo, que salia partido letra por letra en vertical.
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceTab.entries.forEach { t ->
            val accent = when (t) {
                SourceTab.TODO -> androidx.compose.ui.graphics.Color.White
                SourceTab.TORRENT -> ArkivRed
                SourceTab.WEB -> ArkivWebViolet
                SourceTab.MAGIS -> com.arkiv.player.ui.catalog.ArkivMagisBlue
                SourceTab.ARCHIVE -> ArkivArchiveTeal
            }
            val on = t == selected
            Surface(
                onClick = { onSelect(t) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    // El seleccionado se tiñe con el color del origen; el foco siempre gana en
                    // contraste, que es lo que el usuario necesita ver desde el sofá.
                    containerColor = if (on) accent.copy(alpha = 0.28f) else ArkivSurfaceHigh,
                    focusedContainerColor = accent.copy(alpha = 0.55f),
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White),
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    if (loading[t] == true) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Text(
                            "${counts[t] ?: 0}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (on) androidx.compose.ui.graphics.Color.White else ArkivTextSecondary,
                        )
                    }
                }
            }
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
    loadingMagis: Boolean,
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
    val anyLoading = loadingTorrent || loadingWeb || loadingArchive || loadingMagis

    // Filtro por origen. Los contadores salen de `ordered` (ya deduplicado), no de `sources`, para
    // que el número del chip sea exactamente el de filas que se van a ver al elegirlo.
    var tab by remember { mutableStateOf(SourceTab.TODO) }
    val counts = countsByTab(ordered)
    val loadingOf = mapOf(
        SourceTab.TODO to anyLoading,
        SourceTab.TORRENT to loadingTorrent,
        SourceTab.WEB to loadingWeb,
        SourceTab.MAGIS to loadingMagis,
        SourceTab.ARCHIVE to loadingArchive,
    )

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

    // Al entrar a las fuentes de OTRO título la lista tiene que arrancar arriba, no donde había
    // quedado la anterior. La llave es el título + el capítulo: es lo que cambia entre búsquedas.
    val listaFuentes = rememberLazyListState()
    LaunchedEffect(title, season, episode) { listaFuentes.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listaFuentes,
            // El margen va DENTRO de la lista (contentPadding), no como padding externo: al enfocar,
            // las filas hacen zoom (1.1x) y con el margen por fuera la lista las recortaba contra su
            // propio borde. Así el zoom se dibuja sobre ese margen en vez de cortarse.
            modifier = Modifier.fillMaxSize(),
            // Sin margen horizontal en la LISTA: cada item pone el suyo, y las filas de fuente
            // ponen el suyo como contentPadding del LazyRow, para que las tarjetas scrolleen
            // hasta el borde de la pantalla en vez de cortarse contra el margen de la lista.
            contentPadding = PaddingValues(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                // Encabezado compacto: con filas horizontales abajo, cada dp que ocupa acá es una
                // fuente menos que se ve sin scrollear. Antes eran 140 dp de póster y dos líneas.
                Row(modifier = Modifier.padding(horizontal = 48.dp)) {
                    Box(
                        modifier = Modifier.height(90.dp).width(90.dp * 2f / 3f)
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
                            style = MaterialTheme.typography.titleLarge,
                            color = ArkivTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Una sola línea de contexto: capítulo, cuántas fuentes hay y si sigue buscando.
                        // El "Fuentes / Buscando…" que estaba aparte se fusionó acá.
                        val meta = buildString {
                            if (season != null && episode != null) append("T").append(season).append(" · E").append(episode)
                            if (ordered.isNotEmpty()) {
                                if (isNotEmpty()) append("  ·  ")
                                append(ordered.size).append(" fuentes")
                            }
                            if (anyLoading) {
                                if (isNotEmpty()) append("  ·  ")
                                append("buscando…")
                            }
                        }
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
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
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                    )
                }
            }

            item {
                TvSourceTabRow(
                    selected = tab,
                    counts = counts,
                    loading = loadingOf,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                    onSelect = { tab = it },
                )
            }

            val filas = filasVisibles(ordered, tab)

            if (ordered.isEmpty() && !anyLoading) {
                item {
                    Text(
                        "No se encontraron fuentes. Volvé atrás y probá con otra temporada/capítulo, o sin especificar ninguno.",
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }
            } else if (filas.isEmpty()) {
                // Hay resultados, pero no de este origen. Sin este aviso la lista queda en blanco y
                // parece que la app se colgó, cuando en realidad basta con volver a "Todo".
                item {
                    Text(
                        if (loadingOf[tab] == true) "Buscando en ${tab.label}…" else "Sin resultados en ${tab.label}.",
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }
            }

            // Una fila horizontal por fuente. El foco inicial va a la primera tarjeta de la PRIMERA
            // fila: si cada fila pidiera el foco, se lo robarían entre ellas al ir llegando.
            filas.forEach { (fuente, deLaFuente) ->
                tvFilaDeFuente(
                    fuente = fuente,
                    items = deLaFuente,
                    enabled = !preparing,
                    loading = loadingOf[fuente] == true,
                    primeraTarjeta = if (fuente == filas.first().first) firstFocus else null,
                    onPlay = { onSelect(it) },
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
 *  - Web: `identity` — el ref del gateway si vino de ahí, si no la URL de la página. Los del
 *    gateway llegan sin `pageUrl`, así que usar la URL a secas los colapsaba en un solo item. */
internal fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Torrent -> "torrent-${s.result.identity}"
    is PlaySource.Archive -> "archive-${s.item.identifier}"
    is PlaySource.Web -> "web-${s.result.identity}"
    is PlaySource.WebPack -> "webpack-${s.pack.siteId}-${s.pack.showTitle}"
    is PlaySource.Magis -> "magis-${s.result.extra["content_id"] ?: s.result.ref}"
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
                        colors = arkivTvButtonColors(),
                        border = arkivTvButtonBorder(),
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
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
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

/**
 * Fase RESULTS · temporada de Magis elegida: gemelo de [TvWebPackContent] para el portal.
 *
 * Los capítulos NO vienen en el resultado de búsqueda —el portal los entrega en otra llamada—, así
 * que se piden al abrir. Mientras cargan se muestra el conteo que sí trae el resultado, para dar
 * idea del tamaño de la temporada.
 *
 * Sin checkboxes, a diferencia del celu: en el control remoto marcar 16 casillas es un suplicio.
 * "Guardar toda la temporada" baja todo y elegir un capítulo lo reproduce.
 */
@Composable
private fun TvMagisSeasonContent(
    season: com.arkiv.player.data.gateway.GatewayResult,
    client: com.arkiv.player.data.gateway.ArkivApiClient,
    posterUrl: String,
    preparing: Boolean,
    onPlayOne: (List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewayEpisode) -> Unit,
    onSaveAll: (List<com.arkiv.player.data.gateway.GatewayEpisode>) -> Unit,
) {
    var capitulos by remember(season.ref) { mutableStateOf<List<com.arkiv.player.data.gateway.GatewayEpisode>?>(null) }
    var error by remember(season.ref) { mutableStateOf<String?>(null) }
    val saveAllFocus = remember { FocusRequester() }

    LaunchedEffect(season.ref) {
        // Mismo diagnóstico que en la ventana del celular (ver MagisSeasonDialog): sin esto el
        // motivo real del fallo no llega a ningún lado.
        android.util.Log.w(
            "ArkivGw",
            "temporada TV: pido capitulos titulo=${season.title} tipo=${season.extra["program_type"]} " +
                "esperados=${season.extra["episode_count"]} kind=${season.kind} ref=${season.ref.take(24)}…",
        )
        runCatching { client.episodes(season.ref) }
            .onSuccess { capitulos = it }
            .onFailure {
                android.util.Log.w("ArkivGw", "temporada TV: fallo ${it.javaClass.simpleName}: ${it.message}", it)
                error = "No se pudieron cargar los capítulos."
            }
    }
    LaunchedEffect(capitulos) {
        if (!capitulos.isNullOrEmpty()) {
            delay(150)
            runCatching { saveAllFocus.requestFocus() }
        }
    }

    val esperados = season.extra["episode_count"]?.toIntOrNull() ?: 0

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
                                contentDescription = season.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            season.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArkivTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                "Magis",
                                season.year.ifBlank { null },
                                (capitulos?.size ?: esperados).takeIf { it > 0 }?.let { "$it capítulos" },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                        )
                    }
                }
            }

            val caps = capitulos
            when {
                error != null -> item {
                    Text(error!!, color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                caps == null -> item {
                    Text("Cargando capítulos…", color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                caps.isEmpty() -> item {
                    Text("Esta temporada no trae capítulos.", color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                else -> {
                    item {
                        Button(
                            onClick = { onSaveAll(caps) },
                            enabled = !preparing,
                            colors = arkivTvButtonColors(),
                            border = arkivTvButtonBorder(),
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(saveAllFocus),
                        ) { Text("Guardar toda la temporada") }
                    }
                    items(caps, key = { it.ref }) { cap ->
                        TvMagisEpisodeRow(cap = cap, enabled = !preparing, onClick = { onPlayOne(caps, cap) })
                    }
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

/** Fila navegable de un capítulo de Magis ("E3 · Título"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMagisEpisodeRow(
    cap: com.arkiv.player.data.gateway.GatewayEpisode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
        Text(
            // El portal repite el nombre de la temporada en el capítulo ("Breaking Bad T5_8"):
            // cuando el título no aporta, se muestra solo el número.
            "E${cap.number}  " + (cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() } ?: "Capítulo ${cap.number}"),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}
