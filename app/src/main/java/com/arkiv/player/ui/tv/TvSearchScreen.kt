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
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TmdbSeason
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.ui.catalog.CapitulosPorTemporada
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.esSerie
import com.arkiv.player.ui.home.buildRowSpecs
import com.arkiv.player.ui.home.matchCategoryRow
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
 * (TMDB/anime). Nada se dispara al teclear: con el control cada letra costaba una vuelta de red.
 *
 * Debajo del teclado hay dos botones. "Autocompletar" trae la grilla de títulos del catálogo, que
 * funciona como sugerencias: al elegir una card se puede escribir su nombre en el buscador (y
 * editarlo) en vez de arrancar una búsqueda. "Buscar" manda el texto —autocompletado o tecleado a
 * mano— derecho a las fuentes, sin atarse al título exacto del catálogo.
 *
 * REFINE agrega el selector visual de temporada/capítulo; RESULTS muestra las fuentes (packs
 * primero) con reproducción inmediata, y la lista de capítulos cuando se elige un pack.
 */
@Composable
fun TvSearchScreen(
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseRow: ((rowId: String, title: String) -> Unit)? = null,
    shortcutKind: String? = null,
    shortcutTmdbId: Int? = null,
    shortcutAnilistId: Long? = null,
) {
    val graph = rememberGraph()
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
    val vmDetail by vm.detail.collectAsStateWithLifecycle()
    val vmAnimeShow by vm.animeShow.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
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
    // Temporada de Magis elegida: un resultado de serie del portal ES una temporada entera, así
    // que abre la lista de capítulos en vez de reproducir el primero.
    var magisSeasonFor by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Serie de Caracol elegida: igual que Magis, abre sus capítulos. Es un estado APARTE a
    // propósito: su lista de capítulos solo llama a `playback.playDituEpisode`, así que un capítulo
    // de Caracol nunca cae en el guardado de Magis.
    var dituSeasonFor by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }

    // Metadata "enriquecida" de la card elegida, para guardar título/póster reales — mismo
    // criterio que SearchScreen (teléfono).
    val resultTitle = vmDetail?.title ?: vmAnimeShow?.title ?: selected?.title ?: ""
    val resultPoster = vmDetail?.posterUrl ?: vmAnimeShow?.posterUrl ?: selected?.posterUrl ?: ""

    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    fun playDituResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playDitu(r)) }
    }

    // Guarda una temporada entera de Magis. Capítulo por capítulo, igual que el celu: cada uno es
    // un archivo aparte en el CDN y la cola ya los agrupa por serie en Descargas.
    fun saveMagisSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        // Igual que en el celu: la serie viaja también en el guardado, porque guardar reescribe la
        // fila del episodio entera. Ver `SearchPlayback.magisEpisodeIdDe`.
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ) {
        preparing = true; playError = null
        scope.launch {
            var encolados = 0
            for (capitulo in capitulos) {
                val epId = playback.magisEpisodeIdDe(temporada, capitulo, serie) ?: continue
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

    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Magis ->
            if (source.result.extra["program_type"] in com.arkiv.player.data.gateway.MAGIS_SERIES) {
                magisSeasonFor = source.result
            } else {
                playMagisResult(source.result)
            }
        is PlaySource.Ditu ->
            if (source.esSerie()) {
                dituSeasonFor = source.result
            } else {
                playDituResult(source.result)
            }
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

    fun recordarConsulta(query: String) {
        scope.launch {
            runCatching {
                historyDao.upsert(SearchHistoryEntity(query, SEARCH_HISTORY_KIND, System.currentTimeMillis()))
            }
            refreshRecents()
        }
    }

    fun buscarTitulos(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        val match = if (onBrowseRow != null) matchCategoryRow(query, fixedRows) else null
        if (match != null) { onBrowseRow?.invoke(match.id, match.title); return }
        text = query
        searched = true
        busquedaNro++
        vm.search(query)
        recordarConsulta(query)
    }

    fun buscarFuentes() {
        val query = text.trim()
        if (query.isBlank()) return
        val match = if (onBrowseRow != null) matchCategoryRow(query, fixedRows) else null
        if (match != null) { onBrowseRow?.invoke(match.id, match.title); return }
        text = query
        vm.buscarFuentesPorTexto(query)
        recordarConsulta(query)
    }

    /**
     * Botón "Buscar": manda el texto tal cual a las fuentes, sin pasar por la ficha del catálogo.
     *
     * El camino por card ata la búsqueda al título EXACTO de TMDB; acá va lo que haya en el
     * buscador, venga de una sugerencia o del teclado.
     *
     * A propósito no toca `searched`: la columna derecha se queda como estaba, así que volver de
     * las fuentes no deja la pantalla en "Sin resultados" por una búsqueda de títulos que nunca
     * corrió.
     */

    /**
     * "Usar este nombre" de una sugerencia: escribe el título de la card en el buscador y deja el
     * foco en "Buscar", que es lo único que falta hacer. Sin esto el foco se queda en la grilla y
     * hay que cruzar toda la columna con el D-pad para rematar la búsqueda.
     */
    val buscarFuentesFocus = remember { FocusRequester() }
    fun usarNombre(nombre: String) {
        text = nombre
        scope.launch {
            delay(150)
            runCatching { buscarFuentesFocus.requestFocus() }
        }
    }

    // Atajo desde el home: entra ya posicionado en un título (mismo patrón que el teléfono).
    LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
        val k = shortcutKind ?: return@LaunchedEffect
        vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
    }

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

    // En REFINE/RESULTS atrás retrocede una fase dentro del wizard; en la fase de títulos, atrás
    // sale de la pantalla. Con los capítulos de una serie abiertos dentro de RESULTS (de Magis o de
    // Caracol), atrás vuelve primero a la lista de fuentes (no sale de la fase).
    BackHandler {
        when {
            phase == SearchPhase.RESULTS && magisSeasonFor != null -> magisSeasonFor = null
            phase == SearchPhase.RESULTS && dituSeasonFor != null -> dituSeasonFor = null
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
                    // Izquierda a derecha, el orden del flujo: "Autocompletar" trae las sugerencias
                    // del catálogo y "Buscar" remata con el texto que quedó. Mitad y mitad porque
                    // los dos se usan, y se navegan entre sí con izquierda/derecha.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            onClick = { buscarTitulos(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Autocompletar", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            }
                        }
                        Surface(
                            onClick = { buscarFuentes() },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp).focusRequester(buscarFuentesFocus),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Buscar", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            }
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
                                    onClick = { buscarTitulos(q) },
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
                                // Ninguna card arranca una búsqueda sola: las pelis también
                                // preguntan. La grilla es tanto el catálogo como el autocompletador
                                // del buscador, y cuál de las dos cosas querés no se puede adivinar.
                                onClick = { preguntarModo = card },
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
            // Lista de fuentes con reproducción inmediata al elegir una; una temporada de Magis o una
            // serie de Caracol abre TvMagisSeasonContent (lista de capítulos) en vez de reproducir.
            SearchPhase.RESULTS -> {
                val currentMagis = magisSeasonFor
                val currentDitu = dituSeasonFor
                if (currentMagis != null) {
                    TvMagisSeasonContent(
                        season = currentMagis,
                        client = graph.fuenteDeContenido,
                        posterUrl = resultPoster,
                        preparing = preparing,
                        onPlayOne = { capitulos, capitulo, serie ->
                            magisSeasonFor = null
                            preparing = true; playError = null
                            scope.launch {
                                applyResult(playback.playMagisSeason(currentMagis, capitulos, capitulo, serie))
                            }
                        },
                        onSaveAll = { capitulos, serie -> saveMagisSeason(currentMagis, capitulos, serie) },
                    )
                } else if (currentDitu != null) {
                    TvMagisSeasonContent(
                        season = currentDitu,
                        // La fuente compuesta: con un ref de Caracol, `episodesConSerie` llega a
                        // `DituFuente`.
                        client = graph.fuenteDeContenido,
                        posterUrl = currentDitu.extra["poster"].orEmpty().ifBlank { resultPoster },
                        preparing = preparing,
                        onPlayOne = { _, capitulo, serie ->
                            dituSeasonFor = null
                            preparing = true; playError = null
                            scope.launch {
                                applyResult(playback.playDituEpisode(currentDitu, capitulo, serie))
                            }
                        },
                        // Sin "Guardar toda la temporada": ahí guardar es bajar al dispositivo, y
                        // Caracol no se baja (Widevine, ver `FuenteDeDescarga`). A la biblioteca
                        // entra al reproducir.
                        onSaveAll = null,
                        etiqueta = "Caracol",
                    )
                } else {
                    TvResultsContent(
                        title = resultTitle,
                        posterUrl = resultPoster,
                        season = refineSeason,
                        episode = refineEpisode,
                        sources = sources,
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
        TvQueHacerConLaCardDialog(
            titulo = card.title,
            esSerie = card.kind != "movie",
            onUsarNombre = {
                preguntarModo = null
                usarNombre(card.title)
            },
            onSerieCompleta = {
                preguntarModo = null
                // pickTitle deja la card como `selected` (y la guarda en el historial); recién
                // entonces runSourceSearch puede buscar sus fuentes. Sin S/E se surfacean los packs.
                vm.pickTitle(card)
                vm.runSourceSearch(null, null)
            },
            // Una peli no tiene temporadas que elegir: pickTitle la manda derecho a RESULTS.
            onPorTemporada = {
                preguntarModo = null
                vm.pickTitle(card)
            },
            onDismiss = { preguntarModo = null },
        )
    }
}

/**
 * Qué hacer con la card recién elegida: usar su nombre como autocompletado del buscador, o buscar
 * sus fuentes por la ficha del catálogo.
 *
 * La grilla hace dos trabajos a la vez —es el catálogo y es el autocompletador— y cuál de los dos
 * querés no se puede adivinar desde el click, así que se pregunta. "Usar este nombre" va primero
 * y con el foco porque es la razón de ser de la grilla cuando uno solo se acuerda de un pedazo del
 * nombre: TMDB completa el título y de ahí la búsqueda sigue por texto, sin atarse al `tmdb_id`
 * (que es justo lo que a veces no encuentra nada en el mirror).
 *
 * Para las series se conservan las dos entradas de siempre: la serie entera (donde salen los packs)
 * y el selector de temporada/capítulo. Una película no tiene nada que elegir, así que su único
 * camino por catálogo es "Ver fuentes".
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQueHacerConLaCardDialog(
    titulo: String,
    esSerie: Boolean,
    onUsarNombre: () -> Unit,
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
                "¿Qué querés hacer?",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Button(
                onClick = onUsarNombre,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth().focusRequester(primero),
            ) { Text("Usar este nombre") }
            Text(
                "Lo escribe en el buscador para que lo edites si querés, y con \"Buscar\" va tal cual a las fuentes.",
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
            )
            if (esSerie) {
                Button(
                    onClick = onSerieCompleta,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier.fillMaxWidth(),
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
                ) { Text("Buscar por temporada") }
                Text(
                    "Abre el selector de temporadas y capítulos.",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                )
            } else {
                Button(
                    onClick = onPorTemporada,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver fuentes") }
                Text(
                    "Busca por la ficha del catálogo, con su título exacto.",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                )
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
            // `.orEmpty()`: esto solo pinta la lista de capítulos; un fallo de red se ve igual que
            // una temporada vacía y se reintenta con solo volver a entrar.
            val eps = runCatching { tmdbApi.seasonEpisodes(tmdbId, season) }.getOrNull().orEmpty()
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
 * Fila de chips por origen ("Todo 12 · Magis 12"), equivalente a la de la app de móvil
 * ([com.arkiv.player.ui.search.SourceTabRow]).
 *
 * Con un solo origen real (Magis) el filtro ya no separa nada, pero se mantiene por si vuelve a
 * haber más de una fuente a la vez.
 *
 * Siempre se pintan los dos chips, incluso en 0: si aparecieran y desaparecieran según van
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
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceTab.entries.forEach { t ->
            val accent = when (t) {
                SourceTab.TODO -> androidx.compose.ui.graphics.Color.White
                SourceTab.MAGIS -> com.arkiv.player.ui.catalog.ArkivMagisBlue
                SourceTab.CARACOL -> com.arkiv.player.ui.catalog.ArkivCaracolVerde
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
 * Fase RESULTS del TV: lista vertical ÚNICA de fuentes de Magis — a diferencia del
 * teléfono, que las agrupa en secciones colapsables por tipo, acá van todas juntas porque el
 * D-pad navega mejor una sola lista que saltar entre secciones. Elegir una fuente reproduce YA
 * (SearchPlayback vía onSelect, sin diálogo de "dónde ver"); una temporada de Magis la maneja el
 * padre (TvSearchScreen) mostrando TvMagisSeasonContent en su lugar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvResultsContent(
    title: String,
    posterUrl: String,
    season: Int?,
    episode: Int?,
    sources: List<PlaySource>,
    loadingMagis: Boolean,
    preparing: Boolean,
    playError: String?,
    onSelect: (PlaySource) -> Unit,
) {
    // distinctBy(sourceKey) es belt-and-braces: el pipeline de arriba ya debería llegar sin
    // duplicados, pero esto evita el crash de Compose por keys repetidas si algo se cuela.
    val ordered = remember(sources) { sources.distinctBy { sourceKey(it) } }
    val anyLoading = loadingMagis

    // Filtro por origen. Los contadores salen de `ordered` (ya deduplicado), no de `sources`, para
    // que el número del chip sea exactamente el de filas que se van a ver al elegirlo.
    var tab by remember { mutableStateOf(SourceTab.TODO) }
    val counts = countsByTab(ordered)
    // `loadingMagis` cubre la búsqueda ENTERA (la fuente compuesta pregunta a Magis y a Caracol a
    // la vez; ver `SearchViewModel.runSourceSearch`), así que sirve igual para Caracol.
    val loadingOf = mapOf(
        SourceTab.TODO to anyLoading,
        SourceTab.MAGIS to loadingMagis,
        SourceTab.CARACOL to loadingMagis,
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
                        "No se encontraron fuentes. Vuelve atrás y prueba con otra temporada/capítulo, o sin especificar ninguno.",
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
 *  Magis: `content_id` del portal, o el `ref` si no lo trae. Caracol: su `ref`, que ya es único por
 *  contenido (`ditu1:<contentType>:<contentId>`). */
internal fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Magis -> "magis-${s.result.extra["content_id"] ?: s.result.ref}"
    is PlaySource.Ditu -> "ditu-${s.result.ref}"
}

/**
 * Fase RESULTS · temporada de Magis elegida.
 *
 * Los capítulos NO vienen en el resultado de búsqueda —el portal los entrega en otra llamada—, así
 * que se piden al abrir. Mientras cargan se muestra el conteo que sí trae el resultado, para dar
 * idea del tamaño de la temporada.
 *
 * Sin checkboxes, a diferencia del celu: en el control remoto marcar 16 casillas es un suplicio.
 * "Guardar toda la temporada" baja todo y elegir un capítulo lo reproduce.
 *
 * También abre las series de Caracol, sin el botón de guardar ([onSaveAll] en null) y con su
 * [etiqueta].
 */
@Composable
private fun TvMagisSeasonContent(
    season: com.arkiv.player.data.gateway.GatewayResult,
    client: com.arkiv.player.data.gateway.FuenteDeContenido,
    posterUrl: String,
    preparing: Boolean,
    onPlayOne: (List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewayEpisode, com.arkiv.player.data.gateway.GatewaySerie?) -> Unit,
    // Null = sin botón de guardar (Caracol: no se baja al dispositivo, ver `FuenteDeDescarga`).
    onSaveAll: ((List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewaySerie?) -> Unit)?,
    // El nombre de la fuente, en la línea de datos de arriba.
    etiqueta: String = "Magis",
) {
    var capitulos by remember(season.ref) { mutableStateOf<List<com.arkiv.player.data.gateway.GatewayEpisode>?>(null) }
    // El bloque `series` de la misma respuesta: de ahí sale el `tmdbId` que necesita
    // `SearchPlayback.playMagisSeason` para guardarlo en el ítem, sin pedirlo de nuevo al tocar un
    // capítulo (ver su KDoc).
    var serie by remember(season.ref) { mutableStateOf<com.arkiv.player.data.gateway.GatewaySerie?>(null) }
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
        runCatching { client.episodesConSerie(season.ref) }
            .onSuccess { (caps, s) -> capitulos = caps; serie = s }
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
                                etiqueta,
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
                    onSaveAll?.let { guardar ->
                        item {
                            Button(
                                onClick = { guardar(caps, serie) },
                                enabled = !preparing,
                                colors = arkivTvButtonColors(),
                                border = arkivTvButtonBorder(),
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(saveAllFocus),
                            ) { Text("Guardar toda la temporada") }
                        }
                    }
                    // Con varias temporadas (una serie de Caracol), por temporada y número, y cada fila
                    // dice la suya. Sin temporada —Magis— queda como llegó. Ver [CapitulosPorTemporada].
                    val enOrden = CapitulosPorTemporada.ordenar(caps)
                    val variasTemporadas = CapitulosPorTemporada.variasTemporadas(caps)
                    items(enOrden, key = { it.ref }) { cap ->
                        // Sin botón de guardar, el foco inicial va al primer capítulo: si nadie lo
                        // pidiera, el control no tendría dónde arrancar.
                        val foco = if (onSaveAll == null && cap === enOrden.first()) {
                            Modifier.focusRequester(saveAllFocus)
                        } else {
                            Modifier
                        }
                        TvMagisEpisodeRow(
                            cap = cap,
                            etiqueta = CapitulosPorTemporada.etiqueta(cap, variasTemporadas, sinTemporada = "E"),
                            enabled = !preparing,
                            modifier = foco,
                            onClick = { onPlayOne(caps, cap, serie) },
                        )
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

/** Fila navegable de un capítulo de Magis o de Caracol ("E3 · Título"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMagisEpisodeRow(
    cap: com.arkiv.player.data.gateway.GatewayEpisode,
    // "E3", o "T2 · E3" con varias temporadas: ver [CapitulosPorTemporada].
    etiqueta: String = "E${cap.number}",
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
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
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!cap.still.isNullOrBlank()) {
                Box(
                    modifier = Modifier.height(56.dp).width(56.dp * 16f / 9f)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black),
                ) {
                    AsyncImage(
                        model = cap.still,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                // El número del portal MANDA: identifica el capítulo que se va a reproducir, y si
                // el cruce con TMDB quedara corrido para esta temporada, sigue siendo el dato cierto.
                // El nombre va al lado, nunca en su lugar. Prioridad: título de TMDB (el real) ->
                // título del portal (salvo que solo repita el nombre de la temporada, ver más abajo)
                // -> "Capítulo N" como último respaldo.
                "$etiqueta  " + (
                    cap.tmdbTitle?.takeIf { it.isNotBlank() }
                        ?: cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() }
                        ?: "Capítulo ${cap.number}"
                    ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
