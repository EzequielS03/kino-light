package com.arkiv.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.esTabletHorizontal
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.RecentTitle
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.SourceRow
import com.arkiv.player.ui.catalog.posterDe
import com.arkiv.player.ui.catalog.SourceCard
import com.arkiv.player.ui.catalog.SourceSectionHeader
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.ui.catalog.ArkivMagisBlue
import com.arkiv.player.ui.catalog.ArkivCaracolVerde
import com.arkiv.player.ui.catalog.esSerie
import com.arkiv.player.ui.catalog.MetaChip
import com.arkiv.player.ui.home.buildRowSpecs
import com.arkiv.player.ui.home.matchCategoryRow
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Unified search wizard: QUERY phase (search box + TMDB/anime cards), REFINE step (optional
 * season/chapter) and RESULTS phase (multi-source search in Magis and Caracol for the chosen
 * card, with S/E injected if given, or by name alone otherwise — the latter surfaces
 * whole-season/series packs).
 */
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseRow: ((rowId: String, title: String) -> Unit)? = null,
    shortcutKind: String? = null,
    shortcutTmdbId: Int? = null,
    shortcutAnilistId: Long? = null,
) {
    val graph = rememberGraph()
    // Filas fijas siempre disponibles (sin API): anime, cartelera, tendencias, series, etc.
    val fixedRows = remember { buildRowSpecs(emptyList(), emptyList(), emptyList()) }
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    graph.tmdbApi, graph.aniListApi,
                    graph.settings, graph.fuenteDeContenido,
                    graph.searchHistory,
                )
            }
        },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val titleResults by vm.titleResults.collectAsStateWithLifecycle()
    val loadingTitles by vm.loadingTitles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val fuentesBuscando by vm.fuentesBuscando.collectAsStateWithLifecycle()
    val estadoDeFuentes by vm.estadoDeFuentes.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val animeShow by vm.animeShow.collectAsStateWithLifecycle()
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val recentTitles by vm.recentTitles.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide al disparar una descarga (el worker de descargas
    // locales también notifica). Ver rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    val playback = remember { SearchPlayback(graph) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    // Temporada de Magis abierta: un resultado de serie del portal ES una temporada entera,
    // así que en vez de reproducir se abre su lista de capítulos.
    var magisSeason by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Serie de Caracol abierta: igual que Magis, se eligen los capítulos antes de reproducir. Es un
    // estado APARTE del de Magis a propósito: lo que se toca en su ventana solo llega a
    // `playback.playDituSeason`, así que un capítulo de Caracol nunca cae en el guardado de Magis.
    var dituSeason by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }

    // Atajo desde el home: entra ya posicionado en un título. Se dispara una sola vez por
    // combinación de args (LaunchedEffect no re-ejecuta en recomposiciones sin cambios), y
    // startFromShortcut() además se protege con selected.value != null.
    LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
        val k = shortcutKind ?: return@LaunchedEffect
        vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
    }

    // Metadata "enriquecida" de la card elegida, para guardar título/póster/descripción reales
    // (no el nombre crudo del torrent) — mismo criterio que CineDetailScreen.
    val resultTitle = detail?.title ?: animeShow?.title ?: selected?.title ?: ""
    val resultPoster = detail?.posterUrl ?: animeShow?.posterUrl ?: selected?.posterUrl ?: ""
    val resultDescription = detail?.overview ?: animeShow?.description

    // Aplica el PlaybackResult devuelto por SearchPlayback: onPlay(epId) si quedó listo, o setea el
    // mensaje de error tal cual lo mostraba la lógica original antes de extraerse al helper.
    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        // Serie → abrir la temporada para elegir capítulo. Película → reproducir directo.
        if (r.extra["program_type"] in MAGIS_SERIES) { magisSeason = r; return }
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    fun playDituResult(source: PlaySource.Ditu) {
        // Serie → abrir sus capítulos. Película → reproducir directo (y queda en la biblioteca).
        if (source.esSerie()) { dituSeason = source.result; return }
        preparing = true; playError = null
        scope.launch { applyResult(playback.playDitu(source.result)) }
    }

    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Magis -> playMagisResult(source.result)
        is PlaySource.Ditu -> playDituResult(source)
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = {
                    if (phase == SearchPhase.REFINE || phase == SearchPhase.RESULTS) vm.back() else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                }
                Text(
                    "Buscar",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
            }

            if (playError != null) {
                Text(
                    playError!!,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when (phase) {
                SearchPhase.REFINE -> selected?.let { card ->
                    RefineContent(card = card, onContinue = { season, episode -> vm.runSourceSearch(season, episode) })
                }
                SearchPhase.RESULTS -> ResultsContent(
                    title = resultTitle,
                    posterUrl = resultPoster,
                    // Fondo del hero: backdrop de TMDB o banner de AniList. Si no hay ninguno el
                    // hero cae a fondo liso, no a un hueco.
                    backdropUrl = detail?.backdropUrl?.ifBlank { null } ?: animeShow?.bannerUrl.orEmpty(),
                    metaChips = buildList {
                        (detail?.year?.ifBlank { null } ?: animeShow?.year?.takeIf { it > 0 }?.toString())
                            ?.let { add(it) }
                        detail?.seasons?.size?.takeIf { it > 0 }?.let { add(if (it == 1) "1 temporada" else "$it temporadas") }
                        animeShow?.episodes?.takeIf { it > 0 }?.let { add("$it episodios") }
                        animeShow?.scorePct?.takeIf { it > 0 }?.let { add("★ $it%") }
                        animeShow?.genres?.firstOrNull()?.let { add(it) }
                    },
                    season = refineSeason,
                    episode = refineEpisode,
                    sources = sources,
                    fuentesBuscando = fuentesBuscando,
                    estadoDeFuentes = estadoDeFuentes,
                    enabled = !preparing,
                    onPlay = { playResult(it) },
                )
                else -> QueryContent(
                    titleResults = titleResults,
                    loadingTitles = loadingTitles,
                    onBuscarFuentesTexto = { q -> vm.buscarFuentesPorTexto(q) },
                    recentQueries = recentQueries,
                    recentTitles = recentTitles,
                    onSearch = { q ->
                        val match = if (onBrowseRow != null) matchCategoryRow(q, fixedRows) else null
                        if (match != null) onBrowseRow?.invoke(match.id, match.title)
                        else vm.search(q)
                    },
                    onPickTitle = { card -> vm.pickTitle(card) },
                    onForgetQuery = { vm.forgetQuery(it) },
                    onForgetTitle = { vm.forgetTitle(it) },
                    onClearHistory = { vm.clearHistory() },
                )
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ArkivRed)
                    Text("Preparando…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    magisSeason?.let { temporada ->
        com.arkiv.player.ui.catalog.MagisSeasonDialog(
            season = temporada,
            client = graph.fuenteDeContenido,
            onDismiss = { magisSeason = null },
            onPlay = { capitulos, capitulo, serie ->
                magisSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playMagisSeason(temporada, capitulos, capitulo, serie)) }
            },
            onSave = { elegidos, serie ->
                askNotifications()
                scope.launch {
                    // Se guarda capítulo por capítulo: cada uno es un archivo aparte en el CDN y
                    // la cola ya sabe agrupar por serie para mostrarlos juntos en Descargas.
                    var encolados = 0
                    for (capitulo in elegidos) {
                        val epId = playback.magisEpisodeIdDe(temporada, capitulo, serie) ?: continue
                        if (graph.localDownloads.enqueue(epId, "magis") ==
                            com.arkiv.player.data.local.EnqueueOutcome.QUEUED
                        ) encolados++
                    }
                    playError = when {
                        encolados == 0 -> "Esos capítulos ya estaban guardados."
                        encolados == elegidos.size -> null
                        else -> "Se encolaron $encolados de ${elegidos.size} (el resto ya estaba)."
                    }
                }
            },
        )
    }

    dituSeason?.let { serieDeCaracol ->
        com.arkiv.player.ui.catalog.MagisSeasonDialog(
            season = serieDeCaracol,
            // La fuente compuesta: con un ref de Caracol, `episodesConSerie` llega a `DituFuente`.
            client = graph.fuenteDeContenido,
            onDismiss = { dituSeason = null },
            // Guarda en la biblioteca todos los capítulos que la ventana ya cargó, y reproduce el tocado.
            onPlay = { capitulos, capitulo, serie ->
                dituSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playDituSeason(serieDeCaracol, capitulos, capitulo, serie)) }
            },
            // Sin casillas de "Guardar": en esta ventana guardar es bajar al dispositivo, y Caracol no
            // se baja (Widevine, ver `FuenteDeDescarga`). A la biblioteca entra al reproducir.
            onSave = null,
            etiqueta = "Caracol",
            acento = ArkivCaracolVerde,
        )
    }

}

/**
 * QUERY phase: search box + TMDB/anime title grid. Shows the history instead of an empty results
 * state while nothing has been searched yet.
 */
@Composable
private fun QueryContent(
    titleResults: List<TitleCard>,
    loadingTitles: Boolean,
    /** Manda el texto TAL CUAL al wizard de fuentes, sin pasar por el catálogo (mismo camino
     *  que el botón "Buscar" del TV): para cuando uno se acuerda de un pedazo del nombre y no
     *  del título exacto con el que TMDB lo tiene. */
    onBuscarFuentesTexto: (String) -> Unit,
    recentQueries: List<String>,
    recentTitles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Mientras no se haya buscado nada se muestra el historial en vez de dos "Sin resultados" que
    // no informan nada. Es estado local: salir de la pantalla y volver muestra el historial otra vez.
    var haBuscado by remember { mutableStateOf(false) }

    // Sube en cada búsqueda: es la llave para devolver la grilla al principio. Sin esto la lista
    // conserva el scroll de la búsqueda anterior y la nueva aparece empezada por la mitad.
    var busquedaNro by remember { mutableStateOf(0) }
    val gridState = rememberLazyGridState()
    // Mismo caso que en el TV: los resultados llegan en dos tandas y el ViewModel publica
    // `tmdb + anime`, así que la segunda se inserta ARRIBA y la grilla se queda anclada donde
    // estaba. Se mantiene arriba hasta que la scrolleés vos.
    var grillaTocada by remember(busquedaNro) { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) grillaTocada = true
    }
    LaunchedEffect(busquedaNro, titleResults) {
        if (!grillaTocada) gridState.scrollToItem(0)
    }

    val buscar: (String) -> Unit = { q ->
        text = q
        haBuscado = true
        busquedaNro++
        onSearch(q)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnasDeGrilla(3, esTabletHorizontal())),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Buscar…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                // Limpiar devuelve al historial. Sin esto, una vez buscada la primera cosa el
                // historial no vuelve hasta salir y entrar de nuevo a la pantalla.
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; haBuscado = false; onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { buscar(text) }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (text.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // La paridad con el TV: buscar en las fuentes con el texto tal cual, sin
                // atarse al título exacto del catálogo de arriba.
                OutlinedButton(
                    onClick = { onBuscarFuentesTexto(text.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Buscar \"$text\" en las fuentes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (!haBuscado) {
            historialItems(
                queries = recentQueries,
                titles = recentTitles,
                onSearch = buscar,
                onPickTitle = onPickTitle,
                onForgetQuery = onForgetQuery,
                onForgetTitle = onForgetTitle,
                onClearHistory = onClearHistory,
            )
            return@LazyVerticalGrid
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadingTitles) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (titleResults.isEmpty() && !loadingTitles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        items(titleResults, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { card ->
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }
    }
}

/**
 * Historial: los textos buscados como chips y los títulos abiertos como pósters. Va aparte de
 * [QueryContent] para no engordarlo; es una extensión de LazyGridScope porque vive dentro de la
 * misma grilla (los pósters tienen que caer en las mismas 3 columnas que los resultados).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LazyGridScope.historialItems(
    queries: List<String>,
    titles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    // Primera vez que se abre la app: ni encabezados. Solo el buscador y nada más.
    if (queries.isEmpty() && titles.isEmpty()) return

    if (queries.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Búsquedas recientes",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                queries.forEach { q ->
                    InputChip(
                        selected = false,
                        onClick = { onSearch(q) },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Quitar $q",
                                modifier = Modifier.size(16.dp).clickable { onForgetQuery(q) },
                            )
                        },
                    )
                }
            }
        }
    }

    if (titles.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Títulos recientes",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(titles, key = { "recent-${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { reciente ->
            val card = reciente.toTitleCard()
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        TextButton(onClick = onClearHistory, modifier = Modifier.padding(top = 8.dp)) {
            Text("Borrar historial", color = ArkivTextSecondary)
        }
    }
}

@Composable
private fun TitleCardItem(card: TitleCard, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
        ) {
            AsyncImage(
                model = card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(kindColor(card.kind)).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { Text(kindLabel(card.kind), color = Color.Black, style = MaterialTheme.typography.labelSmall) }
        }
        Text(
            card.title,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (card.year.isNotBlank()) {
            Text(card.year, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "movie" -> "PELÍCULA"
    "series" -> "SERIE"
    else -> "ANIME"
}

private fun kindColor(kind: String): Color = when (kind) {
    "movie" -> Color(0xFF64B5F6)
    "series" -> Color(0xFF4CAF50)
    else -> Color(0xFFBA68C8)
}

/** Fase REFINE: temporada/capítulo opcional (series) o episodio opcional (anime) antes de RESULTS. */
@Composable
private fun RefineContent(card: TitleCard, onContinue: (season: Int?, episode: Int?) -> Unit) {
    var seasonText by remember(card) { mutableStateOf("") }
    var episodeText by remember(card) { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Box(
                modifier = Modifier.height(180.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = card.posterUrl,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (card.year.isNotBlank()) {
                    Text(card.year, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                }
            }
        }

        Spacer(Modifier.size(24.dp))

        when (card.kind) {
            "series" -> {
                OutlinedTextField(
                    value = seasonText,
                    onValueChange = { seasonText = it.filter(Char::isDigit) },
                    label = { Text("Temporada (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = episodeText,
                    onValueChange = { episodeText = it.filter(Char::isDigit) },
                    label = { Text("Capítulo (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                Button(
                    onClick = { onContinue(seasonText.toIntOrNull(), episodeText.toIntOrNull()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }
            "anime" -> {
                OutlinedTextField(
                    value = episodeText,
                    onValueChange = { episodeText = it.filter(Char::isDigit) },
                    label = { Text("Episodio (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                Button(
                    onClick = { onContinue(null, episodeText.toIntOrNull()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }
            else -> Unit // "movie" no llega a REFINE: pickTitle() la manda directo a RESULTS.
        }
    }
}

/**
 * RESULTS phase: multi-source search (Magis/Caracol) for the chosen card, with S/E injected if it
 * came from REFINE or by name alone otherwise. Reuses SourceSectionHeader (same collapsible
 * pattern as CineDetailScreen's bottom sheet).
 */
@Composable
private fun ResultsContent(
    title: String,
    posterUrl: String,
    backdropUrl: String,
    metaChips: List<String>,
    season: Int?,
    episode: Int?,
    sources: List<PlaySource>,
    fuentesBuscando: FuentesBuscando,
    estadoDeFuentes: EstadoDeLasFuentes,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
) {
    // Las dos entran abiertas por defecto: una sección que arranca colapsada parece vacía aunque
    // traiga resultados.
    var expandedSections by remember { mutableStateOf(setOf("MAGIS", "CARACOL")) }
    fun toggle(k: String) { expandedSections = if (k in expandedSections) expandedSections - k else expandedSections + k }
    // `rememberSaveable` y no `remember`: al abrir el reproductor esta pantalla se destruye, y con
    // `remember` el origen elegido se perdía — volvías de ver algo por Magis y la lista estaba
    // otra vez en "Todo", con el ítem que acababas de tocar enterrado entre decenas de resultados.
    var tab by rememberSaveable { mutableStateOf(SourceTab.TODO) }

    val magis = sources.filterIsInstance<PlaySource.Magis>()
    val caracol = sources.filterIsInstance<PlaySource.Ditu>()
    val anyLoading = fuentesBuscando.alguna
    val counts = countsByTab(sources)
    // Cada chip gira mientras su fuente siga buscando, y "Todo" mientras falte cualquiera: ver
    // [FuentesBuscando].
    val loadingOf = SourceTab.entries.associateWith { fuentesBuscando.buscando(it) }

    // El hero va a sangre (sin margen lateral) para que el backdrop llegue a los bordes; por eso el
    // padding horizontal lo pone cada ítem en vez del contentPadding de la lista.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            ResultsHero(title, posterUrl, backdropUrl, metaChips, season, episode, sources.size, anyLoading)
        }

        item(key = "filters") {
            SourceTabRow(tab, counts, loadingOf, Modifier.padding(horizontal = HPAD, vertical = 12.dp)) { tab = it }
        }

        // Una línea por fuente caída, haya o no resultados: no tapa lo que las otras trajeron.
        avisosDeFuentesCaidas(estadoDeFuentes, tab).forEachIndexed { i, aviso ->
            item(key = "aviso-$i") {
                Text(
                    aviso,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HPAD, vertical = 4.dp),
                )
            }
        }

        if (!anyLoading && sources.isEmpty()) {
            item(key = "empty") {
                Text(
                    textoSinFuentes(estadoDeFuentes),
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(horizontal = HPAD, vertical = 12.dp),
                )
            }
        } else if (tab == SourceTab.TODO) {
            // "Todo": una sección colapsable por origen, en el orden de [SourceTab].
            sourceSection(this, "MAGIS", ArkivMagisBlue, magis, fuentesBuscando.buscando(SourceTab.MAGIS), "MAGIS" in expandedSections, { toggle("MAGIS") }, enabled, onPlay, textoSeccionVacia(SourceTab.MAGIS, estadoDeFuentes))
            sourceSection(this, "CARACOL", ArkivCaracolVerde, caracol, fuentesBuscando.buscando(SourceTab.CARACOL), "CARACOL" in expandedSections, { toggle("CARACOL") }, enabled, onPlay, textoSeccionVacia(SourceTab.CARACOL, estadoDeFuentes))
        } else {
            // Con un origen elegido la cabecera de sección sobra: la lista va plana.
            val shown = filterByTab(sources, tab)
            val vacia = if (shown.isEmpty()) textoPestanaVacia(tab, loadingOf[tab] == true, estadoDeFuentes) else null
            if (vacia != null) {
                item(key = "empty-tab") {
                    Text(
                        vacia,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(horizontal = HPAD, vertical = 16.dp),
                    )
                }
            }
            if (shown.any { posterDe(it).isNotBlank() }) {
                tarjetasEnDosColumnas("tab", shown, enabled, onPlay)
            } else {
                items(shown, key = { sourceKey(it) }) { s ->
                    Box(Modifier.padding(horizontal = HPAD)) {
                        SourceRow(s, enabled = enabled) { onPlay(s) }
                    }
                }
            }
        }
    }
}

private val HPAD = 16.dp

/**
 * Cabecera de la fase RESULTS: backdrop a sangre con degradado al negro, y encima el póster y los
 * datos del título. El degradado es lo que hace que la imagen se funda con la lista en vez de
 * quedar como un recuadro pegado arriba; sin él el backdrop corta en seco contra el fondo.
 *
 * Si no hay backdrop (AniList a veces no trae banner) queda el fondo liso y el póster manda — por
 * eso el degradado arranca opaco desde arriba y no depende de que haya imagen.
 */
@Composable
private fun ResultsHero(
    title: String,
    posterUrl: String,
    backdropUrl: String,
    metaChips: List<String>,
    season: Int?,
    episode: Int?,
    total: Int,
    loading: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter),
            )
        }
        // Doble velo: uno vertical que funde la imagen con el fondo de la lista, y uno horizontal
        // desde la izquierda para que el texto se lea sobre cualquier backdrop.
        Box(
            Modifier.fillMaxSize().background(
                // Oscuro arriba y abajo, claro en la franja del medio: sin el oscurecido de arriba
                // la imagen corta en seco contra la barra "Buscar", que es negra.
                Brush.verticalGradient(
                    0f to ArkivBlack.copy(alpha = 0.85f),
                    0.22f to ArkivBlack.copy(alpha = 0.30f),
                    0.62f to ArkivBlack.copy(alpha = 0.80f),
                    1f to ArkivBlack,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to ArkivBlack.copy(alpha = 0.75f), 0.75f to Color.Transparent),
            ),
        )

        Row(
            Modifier.align(Alignment.BottomStart).padding(start = HPAD, end = HPAD, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.height(130.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)).background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = posterUrl, contentDescription = title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(start = 14.dp, bottom = 6.dp)) {
                Text(
                    title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (metaChips.isNotEmpty()) {
                    Text(
                        metaChips.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium, color = ArkivTextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (season != null && episode != null) {
                        MetaChip("T$season · E$episode", ArkivRed, strong = true)
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            color = ArkivTextSecondary, strokeWidth = 1.5.dp,
                            modifier = Modifier.size(12.dp),
                        )
                        Text("Buscando…", style = MaterialTheme.typography.labelMedium, color = ArkivTextSecondary)
                    } else {
                        Text(
                            "$total fuentes", style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** Una sección (cabecera + filas) dentro del LazyColumn, para que las filas se compongan on-demand
 *  en vez de todas de golpe: una búsqueda por nombre trae fácil 60+ torrents. */
/**
 * Los resultados como grilla de carátulas de dos columnas, para las fuentes que traen imagen.
 *
 * Va por pares dentro del LazyColumn en vez de un LazyVerticalGrid: una grilla perezosa anidada en
 * una lista perezosa del mismo eje no tiene altura contra la cual medirse y revienta. Con veinte
 * resultados el costo de no ser perezosa por columna es nulo.
 */
private fun LazyListScope.tarjetasEnDosColumnas(
    tag: String,
    items: List<PlaySource>,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
) {
    items(items.chunked(2), key = { par -> "$tag-grid-${sourceKey(par.first())}" }) { par ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HPAD, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            par.forEach { s ->
                Box(Modifier.weight(1f)) {
                    SourceCard(s, enabled = enabled) { onPlay(s) }
                }
            }
            // Impar: el hueco lo ocupa un espaciador para que la última tarjeta no se estire al ancho.
            if (par.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun sourceSection(
    scope: LazyListScope,
    tag: String,
    tagColor: Color,
    items: List<PlaySource>,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
    /** Lo que se dice bajo la sección si no trajo nada ([textoSeccionVacia]). */
    vacio: String,
) {
    scope.item(key = "sec-$tag") {
        Box(Modifier.padding(horizontal = HPAD)) {
            SourceSectionHeader(tag, tagColor, items.size, loading, expanded, onToggle)
        }
    }
    if (expanded) {
        if (items.any { posterDe(it).isNotBlank() }) {
            scope.tarjetasEnDosColumnas(tag, items, enabled, onPlay)
        } else {
            scope.items(items, key = { "$tag-${sourceKey(it)}" }) { s ->
                Box(Modifier.padding(horizontal = HPAD)) {
                    SourceRow(s, enabled = enabled) { onPlay(s) }
                }
            }
        }
        if (items.isEmpty() && !loading) {
            scope.item(key = "sec-$tag-empty") {
                Text(
                    vacio, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = HPAD + 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Identidad estable de una fuente, para las keys del LazyColumn (dos resultados distintos con el
 *  mismo nombre romperían la lista si compartieran key). Mismo criterio que usa el buscador del TV. */
private fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Magis -> "m-${s.result.extra["content_id"] ?: s.result.ref}"
    // El ref de Caracol ya es único por contenido: `ditu1:<contentType>:<contentId>`.
    is PlaySource.Ditu -> "d-${s.result.ref}"
}

/**
 * Chips de filtro por origen (el orden lo fija [SourceTab]), con su contador.
 *
 * La fila SCROLLEA en horizontal: con más fuentes de las que caben en el ancho de un teléfono, un
 * Row sin scroll repartía el faltante achicando el último chip y el texto salía partido letra por
 * letra en vertical. Scrolleando, cada chip conserva su ancho natural y se lee entero.
 */
@Composable
private fun SourceTabRow(
    selected: SourceTab,
    counts: Map<SourceTab, Int>,
    loading: Map<SourceTab, Boolean>,
    modifier: Modifier = Modifier,
    onSelect: (SourceTab) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceTab.entries.forEach { t ->
            val accent = when (t) {
                SourceTab.TODO -> Color.White
                SourceTab.MAGIS -> ArkivMagisBlue
                SourceTab.CARACOL -> ArkivCaracolVerde
            }
            val on = t == selected
            Row(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (on) accent.copy(alpha = 0.22f) else ArkivSurfaceHigh.copy(alpha = 0.5f))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium,
                    color = if (on) accent else ArkivTextSecondary,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (loading[t] == true) {
                    CircularProgressIndicator(
                        color = if (on) accent else ArkivTextSecondary,
                        strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp),
                    )
                } else {
                    Text(
                        "${counts[t] ?: 0}", style = MaterialTheme.typography.labelSmall,
                        color = if (on) accent else ArkivTextSecondary,
                    )
                }
            }
        }
    }
}
