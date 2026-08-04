package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.TorrentSource
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.torrent.EpisodeFilePicker
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

private val ALL_LANGS = listOf(TorrentLang.LATINO, TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.ENGLISH, TorrentLang.JAP_SUB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CineDetailScreen(
    tmdbId: Int,
    type: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    deepLinkSeason: Int? = null,
    deepLinkEpisode: Int? = null,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide recién al disparar una descarga a la NUC, que es
    // lo único que notifica desde esta pantalla. Ver rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    var detail by remember { mutableStateOf<TmdbDetail?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var episodes by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var loadingEps by remember { mutableStateOf(false) }
    var langs by remember { mutableStateOf(ALL_LANGS.toSet()) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var sheetEpisode by remember { mutableStateOf<TmdbEpisode?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<PlaySource>>(emptyList()) }
    var webPacks by remember { mutableStateOf<List<MirrorWebPack>>(emptyList()) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }
    // Estado de carga por tipo (para el spinner de cada sección colapsable).
    var loadingTorrent by remember { mutableStateOf(false) }
    var loadingArchive by remember { mutableStateOf(false) }
    var loadingWeb by remember { mutableStateOf(false) }
    // Secciones expandidas (por defecto las tres abiertas para ver caer los resultados).
    var expandedSections by remember { mutableStateOf(setOf("TORRENT", "WEB", "ARCHIVE")) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // confirmValueChange bloquea el swipe-to-close (que se disparaba al scrollear); se cierra con la X.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
    )

    // Calienta la sesión + DHT del torrent mientras el usuario navega (arranque más rápido al reproducir).
    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

    LaunchedEffect(tmdbId, type) {
        loading = true
        val d = runCatching { graph.tmdbApi.detail(type, tmdbId) }.getOrNull()
        detail = d
        // Si viene un deep-link de temporada, respetarlo en vez de pisarlo con la temporada por
        // defecto (si no, este efecto se ejecuta después de LaunchedEffect(deepLinkSeason) y lo clobbers).
        selectedSeason = deepLinkSeason
            ?: d?.seasons?.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
            ?: d?.seasons?.firstOrNull()?.seasonNumber
        loading = false
    }

    // Refresca la caché local de "qué episodios ya están en la NUC" al abrir el detalle (solo
    // series: las películas no tienen season/episode ni se descargan vía este flujo web). Así
    // PlaybackPreferenceStore (Task 10) tiene datos frescos aunque la descarga se haya disparado
    // desde otro dispositivo o el usuario nunca haya visitado la pantalla de Descargas.
    // `replace = true`: la respuesta es la verdad completa de la serie (refleja también borrados).
    // Si la consulta falla, NucDownloads.refreshLibraryCache no toca nada (ver ahí el porqué).
    LaunchedEffect(detail) {
        val d = detail
        if (d == null || !d.isSeries) return@LaunchedEffect
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        scope.launch {
            com.arkiv.player.data.offline.NucDownloads.refreshLibraryCache(
                graph.arkivOfflineApi, graph.database.nucLibraryItemDao(),
                seriesId = seriesId, replace = true,
            )
        }
    }

    // Packs web (serie completa por sitio): un solo fetch por título, cacheado y reusado por todos
    // los episodios del sheet. Películas no tienen concepto de pack (queda vacío).
    LaunchedEffect(detail) {
        val d = detail
        webPacks = if (d != null && d.isSeries) {
            runCatching {
                graph.torrentSearchApi.seriesWebPacks(d.searchTitles, ContentType.TV, tmdbId = d.id, showTitle = d.title)
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    // Cargar los capítulos de la temporada elegida (bajo demanda).
    LaunchedEffect(selectedSeason, detail) {
        val s = selectedSeason
        val d = detail
        if (s == null || d == null || !d.isSeries) { episodes = emptyList(); return@LaunchedEffect }
        loadingEps = true
        episodes = runCatching { graph.tmdbApi.seasonEpisodes(d.id, s) }.getOrDefault(emptyList())
        loadingEps = false
    }

    fun runSearch(ep: TmdbEpisode?) {
        val d = detail ?: return
        searchJob?.cancel()
        loadingTorrent = true; loadingArchive = true; loadingWeb = true
        sources = emptyList()
        val maxBytes = graph.settings.maxTorrentSizeGb.value.toLong().let { if (it <= 0) 0L else it shl 30 }
        searchJob = scope.launch {
            // Búsqueda PROGRESIVA e INDEPENDIENTE: torrent/archive/web corren en paralelo y cada uno
            // agrega sus resultados a su sección apenas los tiene (ninguno espera a los otros).
            fun append(new: List<PlaySource>) { if (sheetEpisode == ep) sources = sources + new }

            launch {
                // Torrents progresivos (2 fases): apibay/knaben rápido, luego el tier on-device.
                val flow = if (ep != null) graph.torrentSearchApi.searchEpisodeFlow(d.searchTitles, ep.season, ep.episode, langs, maxBytes, tmdbId = d.id)
                           else graph.torrentSearchApi.searchMovieFlow(d.searchTitles, d.year, langs, maxBytes, tmdbId = d.id)
                runCatching { flow.collect { chunk -> append(chunk.map { PlaySource.Torrent(it) }) } }
                if (sheetEpisode == ep) loadingTorrent = false
            }
            launch {
                val q = if (ep != null) "${d.originalTitle} ${ep.season}x${"%02d".format(ep.episode)}" else d.originalTitle
                val a = runCatching { graph.api.search(q) }.getOrDefault(emptyList()).map { PlaySource.Archive(it) }
                append(a); if (sheetEpisode == ep) loadingArchive = false
            }
            launch {
                // Mirror primero (rapido, sin Cloudflare on-device): si el backend ya tiene fuentes
                // web para este episodio, las usamos. Si no (o es pelicula, fuera de alcance del
                // mirror web), caemos al scraping en vivo de siempre.
                val mirrorWeb = if (ep != null) {
                    runCatching {
                        graph.torrentSearchApi.searchEpisodeWeb(d.searchTitles, ep.season, ep.episode, tmdbId = d.id)
                    }.getOrDefault(emptyList())
                } else emptyList()
                if (mirrorWeb.isNotEmpty()) {
                    append(mirrorWeb.map { PlaySource.Web(it) })
                    if (sheetEpisode == ep) loadingWeb = false
                } else {
                    val ctx = com.arkiv.player.data.catalog.providers.SearchContext(
                        titles = d.searchTitles,
                        type = if (ep != null) com.arkiv.player.data.catalog.providers.ContentType.TV
                               else com.arkiv.player.data.catalog.providers.ContentType.MOVIE,
                        season = ep?.season ?: 0,
                        episode = ep?.episode ?: 0,
                        year = d.year,
                    )
                    // Streaming por-canal: cada web agrega sus resultados apenas termina (no espera a todas).
                    runCatching { graph.webSourceEngine.searchFlow(ctx).collect { chunk -> append(chunk.map { PlaySource.Web(it) }) } }
                    if (sheetEpisode == ep) loadingWeb = false
                }
            }
        }
    }

    fun openSources(ep: TmdbEpisode?) { sheetEpisode = ep; sheetOpen = true; runSearch(ep) }

    // Deep-link opcional (handoff desde la búsqueda por fases): si viene season/episode, seleccionar
    // la temporada y, apenas aparezca ese episodio en `episodes`, abrir sus resultados una sola vez.
    var deepLinkHandled by remember { mutableStateOf(false) }
    LaunchedEffect(deepLinkSeason) {
        if (deepLinkSeason != null && selectedSeason != deepLinkSeason) selectedSeason = deepLinkSeason
    }
    LaunchedEffect(episodes, deepLinkEpisode) {
        if (deepLinkHandled) return@LaunchedEffect
        val epNo = deepLinkEpisode ?: return@LaunchedEffect
        val ep = episodes.firstOrNull { it.episode == epNo } ?: return@LaunchedEffect
        deepLinkHandled = true
        openSources(ep)
    }

    fun play(result: TorrentResult) {
        val d = detail ?: return
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val ep = sheetEpisode
            val epId = when (val src = graph.torrentSearchApi.resolveSource(result)) {
                // Magnet → guardamos el magnet (streaming NO bloqueante en el player, sin fetchMagnet).
                is TorrentSource.Magnet ->
                    if (ep != null) graph.repository.addSeriesEpisodeMagnet(seriesId, d.title, d.posterUrl, ep.season, ep.episode, ep.name, src.uri, description = d.overview)
                    else graph.repository.addTorrentMagnet(d.title, src.uri, d.posterUrl, description = d.overview)
                // .torrent (bytes) → ya tenemos la metadata, elegimos el archivo e indexamos.
                is TorrentSource.TorrentFile -> {
                    val meta = graph.torrentEngine.resolveTorrent(src.bytes)
                    if (meta == null) { preparing = false; error = "No se pudo leer el .torrent"; return@launch }
                    val videos = graph.torrentEngine.videoFiles(meta)
                        .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
                    val video = ep?.let { e ->
                        EpisodeFilePicker.pick(videos.map { it.name }, e.season, e.episode)?.let { videos[it] }
                    } ?: videos.maxByOrNull { it.sizeBytes } ?: videos.firstOrNull()
                    if (video == null) { preparing = false; error = "El torrent no tiene video reproducible"; return@launch }
                    if (ep != null) graph.repository.addSeriesEpisode(
                        seriesId = seriesId, showTitle = d.title, posterUrl = d.posterUrl,
                        season = ep.season, episode = ep.episode, episodeName = ep.name,
                        infoHashHex = meta.infoHashHex, infoBytes = meta.infoBytes,
                        fileIndex = video.index, fileSizeBytes = video.sizeBytes,
                        description = d.overview,
                    ) else graph.repository.addTorrent(d.title, meta.infoHashHex, meta.infoBytes, videos, d.posterUrl, description = d.overview)
                        .let { graph.repository.firstEpisodeId(it) }
                }
                null -> null
            }
            preparing = false
            if (epId != null) onPlay(epId) else if (error == null) error = "No se pudo obtener el torrent"
        }
    }

    // Reproduce un ítem de archive.org: lo agrega a la biblioteca y usa el player normal.
    fun playArchive(item: ArchiveSearchResult) {
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val added = graph.repository.addItem(item.identifier).getOrNull()
            if (added == null) { preparing = false; error = "No se pudo abrir el ítem de archive.org"; return@launch }
            val epId = graph.repository.firstEpisodeId(added.identifier)
            preparing = false
            if (epId != null) onPlay(epId)
        }
    }

    // Reproduce una fuente web: crea el episodio web (guarda la pageUrl) y usa el player unificado,
    // que resuelve la pageUrl → stream al cargar (loadWeb). Molde: playArchive.
    fun playWeb(r: com.arkiv.player.data.catalog.web.WebResult) {
        val d = detail ?: return
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val ep = sheetEpisode
            val epId = if (ep != null) {
                val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
                graph.repository.addWebSeriesEpisode(seriesId, d.title, d.posterUrl, ep.season, ep.episode, ep.name, r.pageUrl)
            } else {
                graph.repository.addWebSource(r.pageUrl, r.title.ifBlank { d.title }, d.posterUrl)
            }
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo abrir la fuente web"
        }
    }

    // Agrega los capítulos elegidos de un pack web (serie completa de un sitio) a la biblioteca, uno
    // por episodio real (season/episode tal cual los trae el sitio) — mismo molde que playWeb pero
    // en loop. Devuelve al reproducir el episodio pedido si se tocó uno puntual, si no el primero.
    fun addWebPack(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>, playEpisode: MirrorWebSource? = null) {
        val d = detail ?: return
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            var first: String? = null
            var wanted: String? = null
            for (ep in episodes) {
                val id = graph.repository.addWebSeriesEpisode(
                    seriesId, title, d.posterUrl, ep.season, ep.episode,
                    ep.name.ifBlank { "Ep ${ep.episode}" }, ep.pageUrl,
                )
                if (first == null) first = id
                if (playEpisode != null && ep.pageUrl == playEpisode.pageUrl) wanted = id
            }
            preparing = false
            val target = wanted ?: first
            if (target != null) onPlay(target) else error = "No se pudo agregar la serie"
        }
    }

    // Dispara una descarga a la NUC (arkiv-offline) de los episodios elegidos del pack. MirrorWebSource
    // ya trae la temporada real por episodio (ver WebMirrorModels.kt), así que se usa tal cual.
    // `episodes` default = pack.episodes completo y `title` default = el título TMDB: mantiene el
    // llamado directo desde WebPackRow del sheet (Task 8) igual que antes; WebPackDialog pasa la
    // selección real del usuario y el título editado en el diálogo (mismo que ya usa onSave/addWebPack
    // -- si no, "Guardar" y "Descargar offline" quedan mostrando nombres distintos para el mismo pack).
    fun downloadPack(pack: MirrorWebPack, episodes: List<MirrorWebSource> = pack.episodes, title: String = detail?.title.orEmpty()) {
        val d = detail ?: return
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        askNotifications()
        scope.launch {
            val items = episodes.map {
                com.arkiv.player.data.offline.NucDownloadItem(it.season, it.episode, it.pageUrl)
            }
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = seriesId, showTitle = title.ifBlank { d.title },
                posterUrl = d.posterUrl, items = items,
            )
        }
    }

    // Descarga un único episodio web suelto (fuera de un pack) del episodio actualmente abierto en
    // el sheet -- WebResult no trae season/episode propios, así que se usan los del TmdbEpisode
    // que abrió el sheet (mismo molde que playWeb).
    fun downloadEpisode(r: com.arkiv.player.data.catalog.web.WebResult, ep: TmdbEpisode) {
        val d = detail ?: return
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        askNotifications()
        scope.launch {
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = seriesId, showTitle = d.title, posterUrl = d.posterUrl,
                items = listOf(com.arkiv.player.data.offline.NucDownloadItem(ep.season, ep.episode, r.pageUrl)),
            )
        }
    }

    // Solo tiene sentido para episodios de serie (movies no tienen season/episode ni packs web).
    fun downloadSource(s: PlaySource, ep: TmdbEpisode) = when (s) {
        is PlaySource.WebPack -> downloadPack(s.pack)
        is PlaySource.Web -> downloadEpisode(s.result, ep)
        else -> Unit
    }

    fun playSource(s: PlaySource) = when (s) {
        is PlaySource.Torrent ->
            if (com.arkiv.player.data.catalog.PackDetector.isPack(s.result.name)) packFor = s.result
            else play(s.result)
        is PlaySource.Archive -> playArchive(s.item)
        is PlaySource.Web -> playWeb(s.result)
        is PlaySource.WebPack -> webPackFor = s.pack
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val d = detail
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            d == null -> Text("No se pudo cargar.", color = ArkivTextSecondary, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                    AsyncImage(
                        model = d.backdropUrl.ifBlank { d.posterUrl },
                        contentDescription = d.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack))))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(d.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    if (d.year.isNotBlank()) {
                        Text(d.year, color = ArkivTextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (d.overview.isNotBlank()) {
                        Text(d.overview, color = ArkivTextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }

                    Text("Idiomas a buscar", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ALL_LANGS.forEach { l ->
                            FilterChip(
                                selected = l in langs,
                                onClick = { langs = if (l in langs) langs - l else langs + l },
                                label = { Text(l.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                            )
                        }
                    }

                    if (!d.isSeries) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                .clip(RoundedCornerShape(12.dp)).background(ArkivRed)
                                .clickable { openSources(null) }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Text("Buscar fuentes", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Text("Temporadas", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.seasons.forEach { s ->
                                FilterChip(
                                    selected = selectedSeason == s.seasonNumber,
                                    onClick = { selectedSeason = s.seasonNumber },
                                    label = { Text(if (s.seasonNumber == 0) "Especiales" else "T${s.seasonNumber}") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                                )
                            }
                        }
                        if (loadingEps) {
                            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                                Text("Cargando capítulos…", color = ArkivTextSecondary)
                            }
                        }
                        episodes.forEach { ep ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { openSources(ep) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivRed)
                                Column(Modifier.weight(1f)) {
                                    Text("${ep.episode}. ${ep.name}", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (ep.air.isNotBlank()) Text(ep.air, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (error != null) Text(error!!, color = ArkivRed, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x88000000)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White) }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ArkivRed)
                    Text("Abriendo el torrent…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState, containerColor = ArkivSurfaceHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                val ep = sheetEpisode
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        if (ep != null) "T${ep.season} · E${ep.episode} — ${ep.name}" else (detail?.title ?: "Fuentes"),
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { sheetOpen = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                }
                val torrents = sources.filterIsInstance<PlaySource.Torrent>()
                // Packs derivados de `webPacks` (estado leído en composición) en vez de inyectados una
                // sola vez en runSearch: así se recomponen solos si el fetch de packs (LaunchedEffect
                // aparte, hasta ~6s) llega DESPUÉS de que el sheet ya abrió (p.ej. deep-link), sin
                // perder la fila del pack ni necesitar cerrar/reabrir el sheet.
                val epPacks = ep?.let { e -> webPacks.filter { it.coversEpisode(e.season, e.episode, seasonStrict = true) } } ?: emptyList()
                val webs = sources.filterIsInstance<PlaySource.Web>() + epPacks.map { PlaySource.WebPack(it) }
                val archives = sources.filterIsInstance<PlaySource.Archive>()
                val anyLoading = loadingTorrent || loadingWeb || loadingArchive
                fun toggle(k: String) { expandedSections = if (k in expandedSections) expandedSections - k else expandedSections + k }

                if (!anyLoading && sources.isEmpty()) {
                    Text(
                        "No se encontraron fuentes para los idiomas elegidos. Probá activar más idiomas.",
                        color = ArkivTextSecondary, modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    // Tres secciones colapsables: cada resultado cae en la suya (no se mezclan).
                    // Orden: Web → Torrent → Archive.
                    // weight(fill=false) acota la altura del scroll interno al espacio disponible del
                    // sheet: así es un viewport REAL que scrollea, y el nested-scroll consume el gesto
                    // en vez de pasárselo al ModalBottomSheet (que se arrastraba/"intentaba cerrar").
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        SourceSection("TORRENT", ArkivRed, torrents, loadingTorrent,
                            "TORRENT" in expandedSections, { toggle("TORRENT") }, !preparing) { playSource(it) }
                        SourceSection("WEB", Color(0xFFB39DDB), webs, loadingWeb,
                            "WEB" in expandedSections, { toggle("WEB") }, !preparing,
                            onDownload = ep?.let { e -> { s: PlaySource -> downloadSource(s, e) } },
                        ) { playSource(it) }
                        SourceSection("ARCHIVE", Color(0xFF80CBC4), archives, loadingArchive,
                            "ARCHIVE" in expandedSections, { toggle("ARCHIVE") }, !preparing) { playSource(it) }
                    }
                }
            }
        }
    }

    packFor?.let { r ->
        val d = detail
        if (d != null) PackDialog(
            result = r,
            packResolver = graph.packResolver,
            defaultTitle = "${d.title} — Pack",
            posterUrl = d.posterUrl,
            onDismiss = { packFor = null },
            onSave = { title, contents, rows ->
                scope.launch {
                    val id = graph.repository.savePackAsSeries(title, d.posterUrl, d.overview, contents.infoHashHex, contents.infoBytes, rows)
                    packFor = null
                    onOpenItem(id)
                }
            },
            onPlayOne = { title, contents, row ->
                scope.launch {
                    val id = graph.repository.savePackAsSeries(title, d.posterUrl, d.overview, contents.infoHashHex, contents.infoBytes, contents.rows)
                    packFor = null
                    onPlay("$id::${row.index}")
                }
            },
        )
    }

    webPackFor?.let { p ->
        val d = detail
        if (d != null) WebPackDialog(
            pack = p,
            seriesId = d.imdbId.ifBlank { "tmdb${d.id}" },
            defaultTitle = d.title,
            posterUrl = d.posterUrl,
            onDismiss = { webPackFor = null },
            onSave = { title, episodes ->
                webPackFor = null
                addWebPack(p, title, episodes)
            },
            onPlayOne = { title, ep ->
                webPackFor = null
                addWebPack(p, title, p.episodes, ep)
            },
            onDownload = { title, episodes ->
                webPackFor = null
                downloadPack(p, episodes, title)
            },
        )
    }
}
