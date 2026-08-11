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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.RecentTitle
import com.arkiv.player.data.catalog.PackDetector
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.data.local.TorrentSizeGate
import com.arkiv.player.ui.catalog.PackDialog
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.SourceRow
import com.arkiv.player.ui.catalog.posterDe
import com.arkiv.player.ui.catalog.SourceCard
import com.arkiv.player.ui.catalog.SourceSection
import com.arkiv.player.ui.catalog.SourceSectionHeader
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.ui.catalog.ArkivMagisBlue
import com.arkiv.player.ui.catalog.ArkivWebViolet
import com.arkiv.player.ui.catalog.ArkivArchiveTeal
import com.arkiv.player.ui.catalog.MetaChip
import com.arkiv.player.ui.catalog.WebPackDialog
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Wizard de búsqueda unificada: Fase QUERY (buscador + cards TMDB/anime + resultados directos
 * torrent/archive), paso REFINE (temporada/capítulo opcional) y fase RESULTS (búsqueda multi-fuente
 * torrent/web/archive de la card elegida, con S/E inyectado si se dio o solo por nombre si no —
 * esto último surfacea packs de temporada/serie completa).
 */
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
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
    val directResults by vm.directResults.collectAsStateWithLifecycle()
    val loadingTitles by vm.loadingTitles.collectAsStateWithLifecycle()
    val loadingDirect by vm.loadingDirect.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val loadingTorrent by vm.loadingTorrent.collectAsStateWithLifecycle()
    val loadingWeb by vm.loadingWeb.collectAsStateWithLifecycle()
    val loadingMagis by vm.loadingMagis.collectAsStateWithLifecycle()
    val loadingArchive by vm.loadingArchive.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val animeShow by vm.animeShow.collectAsStateWithLifecycle()
    val processingNow by vm.processingNow.collectAsStateWithLifecycle()
    val processNowMessage by vm.processNowMessage.collectAsStateWithLifecycle()
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val recentTitles by vm.recentTitles.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide al disparar una descarga (el worker de descargas
    // locales también notifica). Ver rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    // Avisa "eso ya lo tenés bajado" cuando la cola saltea una descarga duplicada (ver
    // DuplicateDownloadPolicy): si no, el botón parecería no hacer nada.
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()
    val playback = remember { SearchPlayback(graph) }
    // seriesId canónico de la card elegida, para lo que lo necesita en COMPOSICIÓN (el badge de "ya
    // descargado" del diálogo de packs). Resolverlo es suspend cuando es anime (mapeo cruzado), así
    // que los caminos que GUARDAN lo piden ellos mismos dentro de su corrutina; acá se muestra "" —
    // el badge no encuentra nada, sin romper el diálogo — hasta que resuelve.
    var canonicalSeriesId by remember { mutableStateOf("") }
    LaunchedEffect(selected, detail, animeShow) {
        val card = selected
        canonicalSeriesId = if (card == null) "" else seriesIdOf(graph, card, detail, animeShow)
    }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }
    // Temporada de Magis abierta: un resultado de serie del portal ES una temporada entera,
    // así que en vez de reproducir se abre su lista de capítulos.
    var magisSeason by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Aviso inline de torrent pesado (ATAJO de UX, ver saveLocally): episodeId ya guardado + tamaño.
    var pendingBig by remember { mutableStateOf<Pair<String, Long>?>(null) }

    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

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

    fun playDirect(source: PlaySource) {
        preparing = true
        playError = null
        scope.launch { applyResult(playback.playDirect(source)) }
    }

    // Reproduce un resultado de la fase RESULTS: mismo patrón que CineDetailScreen.play, salvo para
    // anime, que usa SU PROPIO agrupador (torrent:anime:<anilistId>, numeración absoluta con
    // season=1) — el mismo que AnimeShowDetailScreen.play, para no romper el agrupado por show.
    fun playTorrent(result: TorrentResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        preparing = true; playError = null
        scope.launch {
            applyResult(playback.playTorrent(result, card, detail, resultTitle, resultPoster, resultDescription, season, episode))
        }
    }

    fun playArchiveResult(item: ArchiveSearchResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playArchive(item)) }
    }

    // Reproduce una fuente web: el anime usa la numeración absoluta (igual que
    // AnimeShowDetailScreen.playWebEp) y las series TMDB la season real, pero el seriesId sale del
    // mismo lugar para los dos (seriesIdOf). Molde: CineDetailScreen.playWeb.
    //
    // mirrorSeason/animeEpisode: la fila local se guarda por hash de pageUrl -- la misma que escribe
    // addWholeWebSeries con la temporada/episodio REAL del mirror. Un WebResult del mirror YA los
    // trae (WebResult.season/episode), así que se usan esos; 1 o el episodio de AniList solo cuando
    // no los trae (scraping en vivo), o sea cuando tampoco hay pack que los contradiga. Ojo: acá NO
    // sirve buscarlos en los packs de `sources` -- runSourceSearch emite WebPack solo sin capítulo
    // elegido y Web solo con capítulo, nunca ambos, así que esa lista siempre está vacía en este
    // camino (ver WebSourceSeason/WebSourceEpisode).
    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        // Serie → abrir la temporada para elegir capítulo. Película → reproducir directo.
        if (r.extra["program_type"] in MAGIS_SERIES) { magisSeason = r; return }
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        val mirrorSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forResult(r)
        val animeEpisode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forResult(r, fallback = episode ?: 1)
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, detail, animeShow, resultTitle, resultPoster, season, episode, mirrorSeason, animeEpisode),
            )
        }
    }

    // Agrega los capítulos elegidos del pack web a la biblioteca y reproduce uno, igual que
    // onSave/onPlayOne de PackDialog para packs de torrent. Molde: playWebResult.
    fun addWholeSeries(
        pack: MirrorWebPack,
        title: String,
        episodes: List<MirrorWebSource>,
        playEpisode: MirrorWebSource? = null,
    ) {
        val card = selected ?: return
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.addWholeWebSeries(pack, card, detail, animeShow, resultPoster, title, episodes, playEpisode),
            )
        }
    }

    // Guarda en el dispositivo los capítulos elegidos del pack web: mismo camino que addWholeSeries
    // (playback.addWholeWebSeries, el "Guardar" del diálogo) -- addWebSeriesEpisode por capítulo, sin
    // reproducir. Mismo seriesId que ese camino -- los dos lo piden a `seriesIdOf` (Task 11: NUNCA
    // season=1 fijo) -- si el seriesId de acá divergiera del que ya usa el guardado local
    // (onSave/onPlayOne, arriba), la descarga quedaría bajo un id distinto y
    // PlaybackPreferenceStore.decide() nunca encontraría el capítulo bajado. Antes esto
    // mandaba un job a la NUC (arkiv-offline); ahora encola la descarga al propio dispositivo (tamaño
    // WEB siempre desconocido, no hay aviso de torrent pesado que mostrar acá -- ver TorrentSizeGate
    // en CineDetailScreen). Molde: saveWebPackLocally en AnimeShowDetailScreen/CineDetailScreen.
    fun downloadWholeSeries(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>) {
        val card = selected ?: return
        playError = null
        askNotifications()
        scope.launch {
            // Adentro de la corrutina: resolver el seriesId de un anime es suspend (consulta el
            // mapeo cruzado, que puede tocar disco o red). Ver SeriesItemIds.animeSeriesId.
            val seriesId = seriesIdOf(graph, card, detail, animeShow)
            val outcomes = mutableListOf<com.arkiv.player.data.local.EnqueueOutcome>()
            for (ep in episodes) {
                val id = graph.repository.addWebSeriesEpisode(
                    seriesId, title, resultPoster, ep.season, ep.episode,
                    ep.name.ifBlank { "Ep ${ep.episode}" }, ep.pageUrl,
                )
                if (id != null) outcomes += graph.localDownloads.enqueue(id, "web")
            }
            // Un solo aviso para todo el pack, no uno por capítulo.
            notifyDuplicates(outcomes)
        }
    }

    // --- Guardar en el dispositivo desde la búsqueda -------------------------------------------
    //
    // Todo esto reusa `playback.*`, que es el MISMO camino de guardado local que usa reproducir
    // (resuelve la fuente y la agrega a la biblioteca devolviendo el episodeId): guardar la descarga
    // bajo un episodeId sacado de un camino paralelo la dejaría apuntando a un episodio que el
    // player nunca pide. La única diferencia con reproducir es que acá no se llama a `onPlay`.

    // Encola una descarga al dispositivo. Atajo de UX igual que en CineDetailScreen: si el tamaño ya
    // se conoce (torrent) y supera el umbral, pide confirmación antes de encolar; la compuerta que
    // garantiza el comportamiento sigue viviendo en el worker, que es la única que ve el tamaño del
    // ARCHIVO elegido y no el del pack. NO pide el permiso de notificaciones acá adentro: el
    // llamador que encola en loop (downloadWholeSeries) lo pide UNA vez antes del loop.
    fun saveLocally(episodeId: String, source: String, knownSizeBytes: Long) {
        if (TorrentSizeGate.needsConfirmation(knownSizeBytes, alreadyConfirmed = false)) {
            pendingBig = episodeId to knownSizeBytes
        } else {
            scope.launch { notifyDuplicates(listOf(graph.localDownloads.enqueue(episodeId, source))) }
        }
    }

    /** Encola el resultado de un `playback.*` (o muestra su error), sin navegar al reproductor. */
    fun enqueueResolved(result: PlaybackResult, source: String, knownSizeBytes: Long) {
        when (result) {
            is PlaybackResult.Ready -> saveLocally(result.episodeId, source, knownSizeBytes)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    // Fase QUERY ("resultados directos": torrent/archive sin card elegida todavía).
    fun saveDirect(source: PlaySource) {
        playError = null
        val size = (source as? PlaySource.Torrent)?.result?.sizeBytes ?: 0L
        val tag = if (source is PlaySource.Torrent) "torrent" else "archive"
        // El permiso se pide UNA vez por acción del usuario y solo si esto encola de una: si el
        // tamaño va a disparar el diálogo de "Descarga pesada", lo pide el botón de ESE diálogo.
        if (!TorrentSizeGate.needsConfirmation(size, alreadyConfirmed = false)) askNotifications()
        scope.launch { enqueueResolved(playback.playDirect(source), tag, size) }
    }

    // Fase RESULTS. Cada rama usa el mismo helper de `playback` que su equivalente de reproducir.
    fun saveResult(source: PlaySource) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        playError = null
        when (source) {
            is PlaySource.Torrent -> {
                val size = source.result.sizeBytes
                if (!TorrentSizeGate.needsConfirmation(size, alreadyConfirmed = false)) askNotifications()
                scope.launch {
                    enqueueResolved(
                        playback.playTorrent(source.result, card, detail, resultTitle, resultPoster, resultDescription, season, episode),
                        "torrent", size,
                    )
                }
            }
            is PlaySource.Archive -> {
                askNotifications()
                scope.launch { enqueueResolved(playback.playArchive(source.item), "archive", 0) }
            }
            is PlaySource.Web -> {
                askNotifications()
                // Mismos mirrorSeason/animeEpisode que playWebResult: la fila local se guarda por
                // hash de pageUrl y las dos rutas tienen que escribir la MISMA fila.
                val r = source.result
                val mirrorSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forResult(r)
                val animeEpisode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forResult(r, fallback = episode ?: 1)
                scope.launch {
                    enqueueResolved(
                        playback.playWeb(r, card, detail, animeShow, resultTitle, resultPoster, season, episode, mirrorSeason, animeEpisode),
                        "web", 0,
                    )
                }
            }
            // El pack completo: mismo camino (y mismo permiso pedido una sola vez) que el botón
            // "Guardar en el dispositivo" del diálogo del pack.
            is PlaySource.WebPack ->
                downloadWholeSeries(source.pack, resultTitle.ifBlank { source.pack.showTitle }, source.pack.episodes)
            // Magis no se guarda en el dispositivo: el CDN sirve con un token que vence a las ~48 h,
            // así que el archivo bajado dejaría de reproducirse.
            is PlaySource.Magis -> playError = "Magis no se puede guardar: el enlace vence."
        }
    }

    // Los packs (torrent y web) NO reproducen directo: abren su diálogo para elegir nombre y
    // capítulos. Sin esto un pack agregaba cientos de episodios en silencio.
    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Torrent -> if (PackDetector.isPack(source.result.name)) packFor = source.result else playTorrent(source.result)
        is PlaySource.Archive -> playArchiveResult(source.item)
        is PlaySource.Web -> playWebResult(source.result)
        is PlaySource.WebPack -> webPackFor = source.pack
        is PlaySource.Magis -> playMagisResult(source.result)
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
                if (phase == SearchPhase.RESULTS && selected?.tmdbId != null) {
                    IconButton(onClick = { vm.processNow() }, enabled = !processingNow) {
                        if (processingNow) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Procesar ahora", tint = Color.White)
                        }
                    }
                }
            }
            processNowMessage?.let { msg ->
                Text(
                    msg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { vm.dismissProcessNowMessage() },
                )
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
                    loadingTorrent = loadingTorrent,
                    loadingWeb = loadingWeb,
                loadingMagis = loadingMagis,
                    loadingArchive = loadingArchive,
                    enabled = !preparing,
                    onPlay = { playResult(it) },
                    onDownload = { saveResult(it) },
                )
                else -> QueryContent(
                    titleResults = titleResults,
                    directResults = directResults,
                    loadingTitles = loadingTitles,
                    loadingDirect = loadingDirect,
                    recentQueries = recentQueries,
                    recentTitles = recentTitles,
                    onSearch = { vm.search(it) },
                    onPickTitle = { card -> vm.pickTitle(card) },
                    onPlayDirect = { playDirect(it) },
                    onDownloadDirect = { saveDirect(it) },
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
            client = graph.arkivApiClient,
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

    webPackFor?.let { p ->
        WebPackDialog(
            pack = p,
            // Mismo cálculo que downloadWholeSeries (más abajo): los dos salen de `seriesIdOf`. Si
            // `selected` fuera null (no debería pasar -- webPackFor solo se llena a partir de un
            // resultado de una card elegida), o si el mapeo del anime todavía no resolvió, queda ""
            // y el badge simplemente no encuentra nada descargado, sin romper el diálogo.
            seriesId = canonicalSeriesId,
            // El nombre del show manda sobre el del pack: el título scrapeado del sitio suele traer
            // ruido (sinopsis concatenada en sololatino), el de TMDB/AniList está limpio.
            defaultTitle = resultTitle.ifBlank { p.showTitle },
            posterUrl = resultPoster,
            onDismiss = { webPackFor = null },
            onSave = { title, episodes ->
                webPackFor = null
                addWholeSeries(p, title, episodes)
            },
            onPlayOne = { title, ep ->
                webPackFor = null
                addWholeSeries(p, title, p.episodes, ep)
            },
            onDownload = { title, episodes ->
                webPackFor = null
                downloadWholeSeries(p, title, episodes)
            },
        )
    }

    packFor?.let { r ->
        PackDialog(
            result = r,
            packResolver = graph.packResolver,
            defaultTitle = "$resultTitle — Pack",
            posterUrl = resultPoster,
            onDismiss = { packFor = null },
            onSave = { title, contents, rows ->
                scope.launch {
                    val id = playback.savePack(title, resultPoster, resultDescription, contents, rows)
                    packFor = null
                    graph.repository.firstEpisodeId(id)?.let { onPlay(it) }
                }
            },
            onPlayOne = { title, contents, row ->
                scope.launch {
                    val id = playback.savePack(title, resultPoster, resultDescription, contents, contents.rows)
                    packFor = null
                    onPlay("$id::${row.index}")
                }
            },
        )
    }

    // Aviso inline de torrent pesado. Igual que en CineDetailScreen: es un ATAJO para no encolar
    // algo que vas a descartar; la compuerta real (por archivo, no por pack) vive en el worker.
    pendingBig?.let { (episodeId, bytes) ->
        AlertDialog(
            onDismissRequest = { pendingBig = null },
            title = { Text("Descarga pesada") },
            text = { Text("Este torrent pesa ${TorrentSizeGate.formatSize(bytes)}. ¿Lo bajás igual?") },
            confirmButton = {
                TextButton(onClick = {
                    askNotifications()
                    scope.launch {
                        val outcome = graph.localDownloads.enqueue(episodeId, "torrent")
                        // Si la cola lo salteó por duplicado NO se confirma: markConfirmed devuelve
                        // la fila a `queued` y volvería a bajar lo que ya está en disco.
                        if (outcome != com.arkiv.player.data.local.EnqueueOutcome.ALREADY_DOWNLOADED) {
                            graph.localDownloads.confirmSize(episodeId)
                        }
                        notifyDuplicates(listOf(outcome))
                    }
                    pendingBig = null
                }) { Text("Descargar") }
            },
            dismissButton = { TextButton(onClick = { pendingBig = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Fase QUERY: buscador + grilla de títulos (TMDB/anime) + resultados directos (torrent/archive).
 * Mientras no se haya buscado nada muestra el historial en lugar de los resultados vacíos.
 */
@Composable
private fun QueryContent(
    titleResults: List<TitleCard>,
    directResults: List<PlaySource>,
    loadingTitles: Boolean,
    loadingDirect: Boolean,
    recentQueries: List<String>,
    recentTitles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onPlayDirect: (PlaySource) -> Unit,
    /** Guarda el resultado directo en el dispositivo (botón de descarga de cada fila). */
    onDownloadDirect: (PlaySource) -> Unit,
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
        columns = GridCells.Fixed(3),
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

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Text("Resultados directos", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadingDirect) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (directResults.isEmpty() && !loadingDirect) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (directResults.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    directResults.forEach { source ->
                        SourceRow(
                            source, enabled = true,
                            onDownload = { onDownloadDirect(source) },
                        ) { onPlayDirect(source) }
                    }
                }
            }
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
                "Seguí buscando",
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

/** Ordena dejando los packs primero (estable: conserva el orden de relevancia dentro de cada grupo). */
internal fun packsFirst(items: List<PlaySource.Torrent>): List<PlaySource.Torrent> =
    items.sortedByDescending { PackDetector.isPack(it.result.name) }

/**
 * Fase RESULTS: búsqueda multi-fuente (torrent/web/archive) de la card elegida, con S/E inyectado
 * si vino del REFINE o solo por nombre si no (esto último surfacea packs). Reutiliza SourceSection
 * (mismo patrón colapsable que el bottom sheet de CineDetailScreen).
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
    loadingTorrent: Boolean,
    loadingWeb: Boolean,
    loadingMagis: Boolean,
    loadingArchive: Boolean,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
    /** Guarda la fuente en el dispositivo (botón de descarga de cada fila). */
    onDownload: (PlaySource) -> Unit,
) {
    // MAGIS entra en las abiertas por defecto: es la primera sección, y arrancar colapsada la haría
    // parecer vacía justo arriba de todo.
    var expandedSections by remember { mutableStateOf(setOf("MAGIS", "TORRENT", "WEB", "ARCHIVE")) }
    fun toggle(k: String) { expandedSections = if (k in expandedSections) expandedSections - k else expandedSections + k }
    // `rememberSaveable` y no `remember`: al abrir el reproductor esta pantalla se destruye, y con
    // `remember` el origen elegido se perdía — volvías de ver algo por Torrent y la lista estaba
    // otra vez en "Todo", con el ítem que acababas de tocar enterrado entre 210 resultados.
    var tab by rememberSaveable { mutableStateOf(SourceTab.TODO) }

    // Los packs primero: el usuario busca por nombre justamente para encontrar temporadas completas.
    // sortedByDescending es estable, así que dentro de cada grupo se conserva el orden de relevancia.
    val torrents = packsFirst(sources.filterIsInstance<PlaySource.Torrent>())
    // WEB incluye tanto capítulos sueltos (Web) como packs de serie completa (WebPack): son
    // mutuamente excluyentes por búsqueda (el mirror devuelve uno u otro, ver SearchViewModel), pero
    // ambos se listan en la misma sección — sin esto un WebPack nunca aparece en pantalla.
    val webs = sources.filter { it is PlaySource.Web || it is PlaySource.WebPack }
    val archives = sources.filterIsInstance<PlaySource.Archive>()
    val magis = sources.filterIsInstance<PlaySource.Magis>()
    val anyLoading = loadingTorrent || loadingWeb || loadingArchive || loadingMagis
    val counts = countsByTab(sources)
    val loadingOf = mapOf(
        SourceTab.TODO to anyLoading, SourceTab.TORRENT to loadingTorrent,
        SourceTab.WEB to loadingWeb, SourceTab.MAGIS to loadingMagis,
        SourceTab.ARCHIVE to loadingArchive,
    )

    // El hero va a sangre (sin margen lateral) para que el backdrop llegue a los bordes; por eso el
    // padding horizontal lo pone cada ítem en vez del contentPadding de la lista.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            ResultsHero(title, posterUrl, backdropUrl, metaChips, season, episode, sources.size, anyLoading)
        }

        item(key = "filters") {
            SourceTabRow(tab, counts, loadingOf, Modifier.padding(horizontal = HPAD, vertical = 12.dp)) { tab = it }
        }

        if (!anyLoading && sources.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No se encontraron fuentes. Volvé atrás y probá con otra temporada/capítulo, o sin especificar ninguno.",
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(horizontal = HPAD, vertical = 12.dp),
                )
            }
        } else if (tab == SourceTab.TODO) {
            // "Todo" mantiene las secciones colapsables: son la única forma de ver los tres orígenes
            // a la vez sin que uno con 60 resultados entierre a los otros.
            sourceSection(this, "MAGIS", ArkivMagisBlue, magis, loadingMagis, "MAGIS" in expandedSections, { toggle("MAGIS") }, enabled, onPlay, onDownload)
            sourceSection(this, "TORRENT", ArkivRed, torrents, loadingTorrent, "TORRENT" in expandedSections, { toggle("TORRENT") }, enabled, onPlay, onDownload)
            sourceSection(this, "WEB", ArkivWebViolet, webs, loadingWeb, "WEB" in expandedSections, { toggle("WEB") }, enabled, onPlay, onDownload)
            sourceSection(this, "ARCHIVE", ArkivArchiveTeal, archives, loadingArchive, "ARCHIVE" in expandedSections, { toggle("ARCHIVE") }, enabled, onPlay, onDownload)
        } else {
            // Con un origen elegido la cabecera de sección sobra: la lista va plana.
            val shown = when (tab) {
                SourceTab.TORRENT -> torrents
                SourceTab.WEB -> webs
                SourceTab.MAGIS -> magis
                else -> archives
            }
            if (shown.isEmpty()) {
                item(key = "empty-tab") {
                    Text(
                        if (loadingOf[tab] == true) "Buscando en ${tab.label}…" else "Sin resultados en ${tab.label}.",
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(horizontal = HPAD, vertical = 16.dp),
                    )
                }
            }
            if (shown.any { posterDe(it).isNotBlank() }) {
                tarjetasEnDosColumnas("tab", shown, enabled, onPlay, onDownload)
            } else {
                items(shown, key = { sourceKey(it) }) { s ->
                    Box(Modifier.padding(horizontal = HPAD)) {
                        SourceRow(s, enabled = enabled, onDownload = { onDownload(s) }) { onPlay(s) }
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
    onDownload: (PlaySource) -> Unit,
) {
    items(items.chunked(2), key = { par -> "$tag-grid-${sourceKey(par.first())}" }) { par ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HPAD, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            par.forEach { s ->
                Box(Modifier.weight(1f)) {
                    SourceCard(s, enabled = enabled, onDownload = { onDownload(s) }) { onPlay(s) }
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
    onDownload: (PlaySource) -> Unit,
) {
    scope.item(key = "sec-$tag") {
        Box(Modifier.padding(horizontal = HPAD)) {
            SourceSectionHeader(tag, tagColor, items.size, loading, expanded, onToggle)
        }
    }
    if (expanded) {
        if (items.any { posterDe(it).isNotBlank() }) {
            scope.tarjetasEnDosColumnas(tag, items, enabled, onPlay, onDownload)
        } else {
            scope.items(items, key = { "$tag-${sourceKey(it)}" }) { s ->
                Box(Modifier.padding(horizontal = HPAD)) {
                    SourceRow(s, enabled = enabled, onDownload = { onDownload(s) }) { onPlay(s) }
                }
            }
        }
        if (items.isEmpty() && !loading) {
            scope.item(key = "sec-$tag-empty") {
                Text(
                    "Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = HPAD + 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Identidad estable de una fuente, para las keys del LazyColumn (dos resultados distintos con el
 *  mismo nombre romperían la lista si compartieran key). Mismo criterio que usa el buscador del TV. */
private fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Torrent -> "t-${s.result.identity}"
    is PlaySource.Archive -> "a-${s.item.identifier}"
    is PlaySource.Web -> "w-${s.result.identity}"
    is PlaySource.WebPack -> "wp-${s.pack.siteId}-${s.pack.showTitle}"
    is PlaySource.Magis -> "m-${s.result.extra["content_id"] ?: s.result.ref}"
}

/**
 * Chips de filtro por origen (el orden lo fija [SourceTab]), con su contador.
 *
 * La fila SCROLLEA en horizontal. Con las cinco fuentes ya no caben en el ancho de un teléfono: el
 * Row repartía el faltante achicando el último chip y "Archive" salía partido letra por letra en
 * vertical. Scrolleando, cada chip conserva su ancho natural y se lee entero.
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
                SourceTab.TORRENT -> ArkivRed
                SourceTab.WEB -> ArkivWebViolet
                SourceTab.MAGIS -> ArkivMagisBlue
                SourceTab.ARCHIVE -> ArkivArchiveTeal
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
