package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.AnimeSourceResult
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.TorrentSource
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.torrent.EpisodeFilePicker
import kotlinx.coroutines.async
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ANIME_LANGS = listOf(
    TorrentLang.LATINO, TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.ENGLISH, TorrentLang.JAP_SUB,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeShowDetailScreen(
    anilistId: Long,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    deepLinkEpisode: Int? = null,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide recién al disparar una descarga a la NUC, que es
    // lo único que notifica desde esta pantalla. Ver rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    var show by remember { mutableStateOf<AnimeShow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }
    // Idiomas a priorizar en la búsqueda (no excluye: TorrentSearchApi solo reordena/prioriza).
    var langs by remember { mutableStateOf<Set<TorrentLang>>(emptySet()) }
    // Episodios expandidos por modo (clave = nº de episodio; -1 = packs/otros). Mapas separados
    // para que la expansión de un modo no "sangre" al otro (los nº de episodio pueden coincidir).
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val expandedAll = remember { mutableStateMapOf<Int, Boolean>() }
    // Ruta B: fuentes por episodio (clave = nº de episodio), en 3 secciones (torrent/web/archive).
    val sourcesByEp = remember { mutableStateMapOf<Int, List<AnimeSourceResult>>() }
    val webByEp = remember { mutableStateMapOf<Int, List<WebResult>>() }
    val archiveByEp = remember { mutableStateMapOf<Int, List<ArchiveSearchResult>>() }
    val loadingEp = remember { mutableStateMapOf<Int, Boolean>() }
    val loadingWebEp = remember { mutableStateMapOf<Int, Boolean>() }
    val loadingArchiveEp = remember { mutableStateMapOf<Int, Boolean>() }
    // Expandido de cada sub-sección por episodio (clave "ep:TIPO"). Torrent abierta por defecto.
    val expandedSub = remember { mutableStateMapOf<String, Boolean>() }
    // Job en vuelo por episodio (padre de las 3 búsquedas), para cancelarlo si cambian los idiomas.
    val episodeJobs = remember { mutableStateMapOf<Int, Job>() }
    // Ruta A: browse "todos los releases".
    var mode by remember { mutableStateOf("episodes") } // "episodes" (B) | "all" (A)
    var browse by remember { mutableStateOf<List<AnimeSourceResult>?>(null) }
    var loadingBrowse by remember { mutableStateOf(false) }
    var browseJob by remember { mutableStateOf<Job?>(null) }
    // Episodios pedidos a mano (fuera del rango 1..total), p.ej. numeración absoluta de long-runners.
    val manualEpisodes = remember { mutableStateListOf<Int>() }
    var manualEpText by remember { mutableStateOf("") }
    var webPacks by remember { mutableStateOf<List<MirrorWebPack>>(emptyList()) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }

    // Calienta la sesión + DHT del torrent mientras el usuario ve los capítulos (arranque más rápido).
    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

    LaunchedEffect(anilistId) {
        loading = true
        show = runCatching { graph.aniListApi.details(anilistId) }.getOrNull()
        loading = false
    }

    // Refresca la caché local de "qué episodios ya están en la NUC" al abrir el detalle: así
    // PlaybackPreferenceStore (Task 10) tiene datos frescos aunque la descarga se haya disparado
    // desde otro dispositivo o el usuario nunca haya visitado la pantalla de Descargas.
    // `replace = true`: la respuesta es la verdad completa de la serie (refleja también borrados).
    // Si la consulta falla, NucDownloads.refreshLibraryCache no toca nada (ver ahí el porqué).
    LaunchedEffect(anilistId) {
        scope.launch {
            com.arkiv.player.data.offline.NucDownloads.refreshLibraryCache(
                graph.arkivOfflineApi, graph.database.nucLibraryItemDao(),
                seriesId = "anilist$anilistId", replace = true,
            )
        }
    }

    // Packs web (serie completa por sitio): un solo fetch por show, cacheado y reusado por los 2
    // modos — "Por episodio" inyecta los que cubren el episodio abierto, "Todos" los lista enteros.
    LaunchedEffect(show) {
        val s = show ?: return@LaunchedEffect
        webPacks = runCatching { graph.animeSourceProvider.seriesWebPacks(s) }.getOrDefault(emptyList())
    }

    fun loadEpisode(ep: Int) {
        if (loadingEp[ep] == true || archiveByEp.containsKey(ep)) return
        val s = show ?: return
        episodeJobs[ep]?.cancel()
        loadingEp[ep] = true; loadingWebEp[ep] = true; loadingArchiveEp[ep] = true
        val reqLangs = langs
        val maxBytes = graph.settings.maxTorrentSizeGb.value.toLong().let { if (it <= 0) 0L else it shl 30 }
        episodeJobs[ep] = scope.launch {
            // 3 búsquedas independientes y PROGRESIVAS (molde de películas): cada sección se llena
            // apenas su fuente responde; ninguna espera a las otras. Si los idiomas cambiaron mientras
            // una consulta estaba en vuelo (langs != reqLangs), se descarta para no pisar la vigente.
            val titlesDeferred = async { runCatching { graph.animeSourceProvider.browseTitles(s) }.getOrDefault(emptyList()) }
            launch { // TORRENT
                runCatching {
                    graph.animeSourceProvider.episodeSourcesFlow(s, ep, langs = reqLangs, maxSizeBytes = maxBytes).collect { chunk ->
                        if (langs == reqLangs) sourcesByEp[ep] = (sourcesByEp[ep] ?: emptyList()) + chunk
                    }
                }
                if (langs == reqLangs) loadingEp[ep] = false
            }
            launch { // WEB
                // Mirror primero y, si responde, NO se scrapea en vivo (mismo patron que
                // CineDetailScreen). El mirror devuelve fuentes A NIVEL DE EPISODIO (url del
                // capitulo + su titulo), mientras que el scraping en vivo es a nivel de SHOW
                // (WebResult no tiene season/episode y la query solo usa {title}/{year}), asi que
                // no aporta precision de episodio: si el mirror ya tiene el capitulo, correr ambos
                // solo suma latencia y ruido. El backend normaliza la numeracion al insertar y
                // guarda el titulo del episodio, asi que cuando quedan varios candidatos el
                // usuario los distingue por titulo, no por un numero ambiguo.
                val mirrorWeb = runCatching { graph.animeSourceProvider.episodeSourcesWeb(s, ep) }.getOrDefault(emptyList())
                if (mirrorWeb.isNotEmpty()) {
                    if (langs == reqLangs) webByEp[ep] = (webByEp[ep] ?: emptyList()) + mirrorWeb
                } else {
                    val titles = titlesDeferred.await()
                    if (titles.isNotEmpty()) {
                        val ctx = SearchContext(titles = titles, type = ContentType.ANIME, episode = ep)
                        runCatching {
                            graph.webSourceEngine.searchFlow(ctx).collect { chunk ->
                                if (langs == reqLangs) webByEp[ep] = (webByEp[ep] ?: emptyList()) + chunk
                            }
                        }
                    }
                }
                if (langs == reqLangs) loadingWebEp[ep] = false
            }
            launch { // ARCHIVE (una sola consulta)
                val titles = titlesDeferred.await()
                val a = runCatching { graph.api.search("${titles.firstOrNull() ?: s.title} $ep") }.getOrDefault(emptyList())
                if (langs == reqLangs) { archiveByEp[ep] = a; loadingArchiveEp[ep] = false }
            }
        }
    }

    // Deep-link opcional (handoff desde la búsqueda por fases): apenas cargue el show, expandir y
    // cargar fuentes del episodio pedido una sola vez. Se agrega a manualEpisodes (como el botón "Ir
    // al episodio") para que se renderice también si cae fuera del rango 1..total (numeración absoluta).
    var animeDeepLinkHandled by remember { mutableStateOf(false) }
    LaunchedEffect(show, deepLinkEpisode) {
        if (animeDeepLinkHandled) return@LaunchedEffect
        show ?: return@LaunchedEffect
        val ep = deepLinkEpisode ?: return@LaunchedEffect
        animeDeepLinkHandled = true
        if (ep !in manualEpisodes) manualEpisodes.add(ep)
        expanded[ep] = true
        loadEpisode(ep)
    }

    fun loadBrowse() {
        if (browse != null || loadingBrowse) return
        val s = show ?: return
        browseJob?.cancel()
        loadingBrowse = true
        val reqLangs = langs
        val maxBytes = graph.settings.maxTorrentSizeGb.value.toLong().let { if (it <= 0) 0L else it shl 30 }
        browseJob = scope.launch {
            runCatching {
                graph.animeSourceProvider.browseSourcesFlow(s, langs = reqLangs, maxSizeBytes = maxBytes).collect { chunk ->
                    if (langs == reqLangs) browse = (browse ?: emptyList()) + chunk
                }
            }
            if (langs == reqLangs) loadingBrowse = false
        }
    }

    fun play(r: TorrentResult, epNumber: Int? = null) {
        val s = show ?: return
        preparing = true
        error = null
        scope.launch {
            val source = graph.torrentSearchApi.resolveSource(r)
            val meta = when (source) {
                is TorrentSource.Magnet -> graph.torrentEngine.resolveMagnet(source.uri)
                is TorrentSource.TorrentFile -> graph.torrentEngine.resolveTorrent(source.bytes)
                null -> null
            }
            if (meta == null) {
                preparing = false
                error = "No se pudo abrir el torrent (puede no tener seeds ahora)"
                return@launch
            }
            val videos = graph.torrentEngine.videoFiles(meta)
                .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
            val video = epNumber?.let { epNo ->
                EpisodeFilePicker.pick(videos.map { it.name }, season = 1, episode = epNo, absoluteEpisode = epNo)
                    ?.let { videos[it] }
            } ?: videos.maxByOrNull { it.sizeBytes } ?: videos.firstOrNull()
            if (video == null) {
                preparing = false
                error = "El torrent no tiene video reproducible"
                return@launch
            }
            val epId = graph.repository.addAnimeEpisode(
                anilistId = anilistId,
                showTitle = s.title,
                posterUrl = s.posterUrl,
                episodeName = r.name,
                infoHashHex = meta.infoHashHex,
                infoBytes = meta.infoBytes,
                fileIndex = video.index,
                fileSizeBytes = video.sizeBytes,
            )
            preparing = false
            onPlay(epId)
        }
    }

    // Intercepta el tap sobre una fuente torrent: si es un PACK (varios episodios), abre el
    // diálogo de pack en vez de reproducir directo. Cubre las 3 listas de ReleaseRow (single-play,
    // por episodio y "Todos"), ya que las 3 pueden mostrar packs (p.ej. la sección "Packs / otros").
    fun playOrPack(r: TorrentResult, epNumber: Int?) {
        if (com.arkiv.player.data.catalog.PackDetector.isPack(r.name)) packFor = r else play(r, epNumber)
    }

    // Reproduce un ítem de archive.org: lo agrega a la biblioteca y usa el player normal. Molde: CineDetailScreen.
    fun playArchive(item: ArchiveSearchResult) {
        preparing = true; error = null
        scope.launch {
            val added = graph.repository.addItem(item.identifier).getOrNull()
            if (added == null) { preparing = false; error = "No se pudo abrir el ítem de archive.org"; return@launch }
            val epId = graph.repository.firstEpisodeId(added.identifier)
            preparing = false
            if (epId != null) onPlay(epId)
        }
    }

    // Reproduce una fuente web de un episodio de anime: crea el episodio web (guarda la pageUrl) y
    // usa el player unificado, que resuelve pageUrl → stream al cargar. Molde: CineDetailScreen.playWeb.
    //
    // Season: la fila local se guarda por hash de pageUrl -- la misma que escriben
    // addWebPack/downloadPack con la temporada REAL del mirror. Inventar 1 acá le revertía la
    // temporada a esa fila y rompía la búsqueda en nuc_library_items (ver WebSourceSeason). El
    // WebResult del mirror ya la trae; `webPacks` queda como respaldo por si la fuente es en vivo.
    fun playWebEp(r: WebResult, ep: Int) {
        val s = show ?: return
        preparing = true; error = null
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forResult(r, webPacks)
        scope.launch {
            val epId = graph.repository.addWebSeriesEpisode(
                "anilist$anilistId", s.title, s.posterUrl, season, ep, "${s.title} - Ep $ep", r.pageUrl,
            )
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo abrir la fuente web"
        }
    }

    // Agrega los capítulos elegidos de un pack web (serie completa de un sitio) a la biblioteca, uno
    // por episodio — mismo molde que playWebEp pero en loop. Devuelve al reproducir el episodio
    // pedido si se tocó uno puntual (onPlayOne del diálogo), si no el primero agregado (onSave).
    //
    // Task 11: antes guardaba season=1 fijo. Acá SÍ hay temporada real -- MirrorWebSource.season es
    // la del episodio en el mirror, la misma que downloadPack manda en el job de la NUC -- así que
    // guardar 1 fijo desalineaba el season local del guardado en nuc_library_items y
    // PlaybackPreferenceStore.decide() nunca encontraba el capítulo bajado en series con más de una
    // temporada. Se usa ep.season, igual que ya hace CineDetailScreen.addWebPack.
    //
    // Los caminos de "un episodio suelto" (playWebEp/downloadEpisode) escriben ESTA MISMA fila
    // (clave = hash de pageUrl) y ya no inventan 1: resuelven la temporada por pageUrl contra
    // `webPacks` (WebSourceSeason), así que las dos rutas coinciden escriba la que escriba último.
    fun addWebPack(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>, playEpisode: MirrorWebSource? = null) {
        val s = show ?: return
        preparing = true; error = null
        scope.launch {
            var first: String? = null
            var wanted: String? = null
            for (ep in episodes) {
                val id = graph.repository.addWebSeriesEpisode(
                    "anilist$anilistId", title, s.posterUrl, ep.season, ep.episode,
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
    // ya trae la temporada real por episodio (ver WebMirrorModels.kt), así que se usa tal cual en vez
    // de asumir season=1 (esa normalización es solo para la reproducción/guardado local del anime).
    // `episodes` default = pack.episodes completo y `title` default = el título del show: mantiene el
    // llamado directo desde WebPackRow (Task 8, botón de descarga rápida sin abrir el diálogo) igual
    // que antes; WebPackDialog pasa la selección real del usuario y el título editado en el diálogo
    // (mismo que ya usa onSave/addWebPack -- si no, "Guardar" y "Descargar offline" quedan mostrando
    // nombres distintos para el mismo pack).
    fun downloadPack(pack: MirrorWebPack, episodes: List<MirrorWebSource> = pack.episodes, title: String = show?.title.orEmpty()) {
        val s = show ?: return
        askNotifications()
        scope.launch {
            val items = episodes.map {
                com.arkiv.player.data.offline.NucDownloadItem(it.season, it.episode, it.pageUrl)
            }
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = "anilist$anilistId", showTitle = title.ifBlank { s.title },
                posterUrl = s.posterUrl, items = items,
            )
        }
    }

    // Descarga un único episodio web suelto (fuera de un pack). El episodio es el de la fila que se
    // está viendo y la temporada sale del propio WebResult del mirror (o de los packs, si la fuente
    // fuera en vivo), igual que playWebEp -- así el season que se guarda en la NUC calza con el de
    // la fila local, que es con el que después PlaybackPreferenceStore.decide() busca el capítulo.
    fun downloadEpisode(r: WebResult, ep: Int) {
        val s = show ?: return
        askNotifications()
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forResult(r, webPacks)
        scope.launch {
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = "anilist$anilistId", showTitle = s.title, posterUrl = s.posterUrl,
                items = listOf(com.arkiv.player.data.offline.NucDownloadItem(season, ep, r.pageUrl)),
            )
        }
    }

    // Al cambiar los idiomas priorizados, invalida lo ya cargado y re-consulta (episodios abiertos +
    // browse si estaba cargado). En la primera composición no hay nada cacheado, así que es no-op.
    LaunchedEffect(langs) {
        // Cancela cualquier consulta por episodio en vuelo (aunque su episodio ya no esté
        // expandido) para que una respuesta con el idioma anterior no llegue a pisar nada.
        episodeJobs.values.forEach { it.cancel() }
        episodeJobs.clear()
        val openEpisodes = expanded.filterValues { it }.keys.toList()
        sourcesByEp.clear(); webByEp.clear(); archiveByEp.clear()
        loadingEp.clear(); loadingWebEp.clear(); loadingArchiveEp.clear()
        openEpisodes.forEach { loadEpisode(it) }
        // Re-consulta browse si ya había datos O si la primera carga seguía en vuelo (browse ==
        // null pero loadingBrowse == true): en ese caso también hay que cancelar y relanzar, si
        // no la re-consulta nunca se dispara y el resultado queda con el idioma viejo.
        if (browse != null || loadingBrowse) {
            browseJob?.cancel()
            browse = null
            loadingBrowse = false
            loadBrowse()
        }
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val s = show
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            s == null -> Text(
                "No se pudo cargar el anime.",
                color = ArkivTextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                    AsyncImage(
                        model = s.bannerUrl.ifBlank { s.posterUrl },
                        contentDescription = s.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack))))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        buildString {
                            if (s.year > 0) append(s.year)
                            if (s.scorePct > 0) append("  ·  ★ ${s.scorePct / 10.0}")
                            if (s.episodes > 0) append("  ·  ${s.episodes} eps")
                        },
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (s.genres.isNotEmpty()) {
                        Text(
                            s.genres.joinToString(" · "),
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (s.description.isNotBlank()) {
                        Text(
                            s.description,
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    // MOVIE/MUSIC (o cualquier formato de 1 solo episodio, p.ej. OVA/SPECIAL) no
                    // tienen lista "1..N" real: es reproducción única por título.
                    val singlePlay = s.format == "MOVIE" || s.format == "MUSIC" || s.episodes == 1

                    Text(
                        "Idiomas a buscar",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ANIME_LANGS.forEach { l ->
                            FilterChip(
                                selected = l in langs,
                                onClick = { langs = if (l in langs) langs - l else langs + l },
                                label = { Text(l.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                            )
                        }
                    }

                    if (singlePlay) {
                        LaunchedEffect(s.id) { loadBrowse() }
                        Text(
                            "Reproducir",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                        )
                        when {
                            loadingBrowse -> Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed)
                                Text("Buscando fuentes…", color = ArkivTextSecondary)
                            }
                            browse.isNullOrEmpty() -> Text(
                                "No se encontraron torrents para este título.",
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            else -> browse!!.forEach { src ->
                                ReleaseRow(result = src.result, enabled = !preparing) { playOrPack(src.result, null) }
                            }
                        }
                    } else {
                    // Selector de modo: Por episodio (B) | Todos (A).
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("episodes" to "Por episodio", "all" to "Todos").forEach { (m, label) ->
                            Text(
                                label,
                                color = if (mode == m) Color.White else ArkivTextSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (mode == m) ArkivRed else ArkivSurfaceHigh)
                                    .clickable { mode = m; if (m == "all") loadBrowse() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }

                    if (mode == "episodes") {
                        val total = if (s.episodes > 0) s.episodes else 0
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = manualEpText,
                                onValueChange = { manualEpText = it.filter(Char::isDigit) },
                                label = { Text(if (total == 0) "Ir al episodio (en emisión)" else "Ir al episodio") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val n = manualEpText.toIntOrNull()
                                    if (n != null && n > 0) {
                                        if (n !in manualEpisodes) manualEpisodes.add(n)
                                        expanded[n] = true
                                        loadEpisode(n)
                                        manualEpText = ""
                                    }
                                },
                                enabled = manualEpText.toIntOrNull()?.let { it > 0 } == true,
                                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                            ) { Text("Ir") }
                        }
                        val displayEpisodes = (if (total > 0) (1..total).toList() else emptyList()) +
                            manualEpisodes.filter { it > total }.sorted()
                        displayEpisodes.forEach { ep ->
                            val open = expanded[ep] ?: false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val now = !open
                                        expanded[ep] = now
                                        if (now) loadEpisode(ep)
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null, tint = Color.White,
                                )
                                Text(
                                    "Episodio $ep",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                // Contador total = las 3 fuentes (torrent + web + archive) del episodio, más
                                // los packs web que lo cubren (mismo criterio que la sub-sección WEB de abajo,
                                // así el número de la fila colapsada coincide con el de adentro).
                                val count = (sourcesByEp[ep]?.size ?: 0) + (webByEp[ep]?.size ?: 0) + (archiveByEp[ep]?.size ?: 0) +
                                    webPacks.count { it.coversEpisode(season = 0, episode = ep, seasonStrict = false) }
                                if (count > 0) Text(
                                    "$count",
                                    color = ArkivTextSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            if (open) {
                                // 3 sub-secciones colapsables por episodio (igual que películas): cada una
                                // progresiva e independiente. TORRENT abierta por defecto; WEB/ARCHIVE colapsadas.
                                val torrents = sourcesByEp[ep] ?: emptyList()
                                val webs = webByEp[ep] ?: emptyList()
                                val archives = archiveByEp[ep] ?: emptyList()
                                AnimeSourceSection(
                                    "TORRENT", ArkivRed, torrents.size, loadingEp[ep] == true,
                                    expandedSub["$ep-t"] ?: true, { expandedSub["$ep-t"] = !(expandedSub["$ep-t"] ?: true) },
                                ) {
                                    torrents.forEach { src ->
                                        ReleaseRow(result = src.result, enabled = !preparing) { playOrPack(src.result, src.episode ?: ep) }
                                    }
                                }
                                val epPacks = webPacks.filter { it.coversEpisode(season = 0, episode = ep, seasonStrict = false) }
                                AnimeSourceSection(
                                    "WEB", Color(0xFFB39DDB), webs.size + epPacks.size, loadingWebEp[ep] == true,
                                    expandedSub["$ep-w"] ?: false, { expandedSub["$ep-w"] = !(expandedSub["$ep-w"] ?: false) },
                                ) {
                                    webs.forEach { r ->
                                        WebEpRow(r, enabled = !preparing, onClick = { playWebEp(r, ep) }, onDownload = { downloadEpisode(r, ep) })
                                    }
                                    epPacks.forEach { p ->
                                        WebPackRow(p, enabled = !preparing, onClick = { webPackFor = p }, onDownload = { downloadPack(p) })
                                    }
                                }
                                AnimeSourceSection(
                                    "ARCHIVE", Color(0xFF80CBC4), archives.size, loadingArchiveEp[ep] == true,
                                    expandedSub["$ep-a"] ?: false, { expandedSub["$ep-a"] = !(expandedSub["$ep-a"] ?: false) },
                                ) {
                                    archives.forEach { item -> ArchiveEpRow(item, enabled = !preparing) { playArchive(item) } }
                                }
                            }
                        }
                    }

                    if (mode == "all") {
                        if (webPacks.isNotEmpty()) {
                            AnimeSourceSection(
                                "WEB", Color(0xFFB39DDB), webPacks.size, false,
                                expandedSub["all-w"] ?: true, { expandedSub["all-w"] = !(expandedSub["all-w"] ?: true) },
                            ) {
                                webPacks.forEach { p ->
                                    WebPackRow(p, enabled = !preparing, onClick = { webPackFor = p }, onDownload = { downloadPack(p) })
                                }
                            }
                        }
                        when {
                            // PROGRESIVO: spinner grande solo mientras no haya NADA aún.
                            loadingBrowse && browse.isNullOrEmpty() -> Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed)
                                Text("Buscando releases…", color = ArkivTextSecondary)
                            }
                            browse.isNullOrEmpty() -> Text(
                                "No se encontraron torrents para este anime.",
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            else -> {
                                val groups = remember(browse) {
                                    browse!!.groupBy { it.episode }
                                        .toSortedMap(compareBy { it ?: Int.MAX_VALUE })
                                }
                                groups.forEach { (ep, items) ->
                                    val key = ep ?: -1
                                    val open = expandedAll[key] ?: (ep == groups.keys.firstOrNull())
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { expandedAll[key] = !open }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null, tint = Color.White,
                                        )
                                        Text(
                                            if (ep != null) "Episodio $ep" else "Packs / otros",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${items.size}",
                                            color = ArkivTextSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    if (open) items.forEach { src ->
                                        ReleaseRow(result = src.result, enabled = !preparing) { playOrPack(src.result, src.episode ?: ep) }
                                    }
                                }
                                if (loadingBrowse) Row(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed)
                                    Text("Buscando más…", color = ArkivTextSecondary)
                                }
                            }
                        }
                    }
                    }

                    if (error != null) {
                        Text(error!!, color = ArkivRed, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x88000000)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ArkivRed)
                    Text("Abriendo el torrent…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    packFor?.let { r ->
        val s = show
        if (s != null) PackDialog(
            result = r,
            packResolver = graph.packResolver,
            defaultTitle = "${s.title} — Pack",
            posterUrl = s.posterUrl,
            onDismiss = { packFor = null },
            onSave = { title, contents, rows ->
                scope.launch {
                    val id = graph.repository.savePackAsSeries(title, s.posterUrl, s.description, contents.infoHashHex, contents.infoBytes, rows)
                    packFor = null
                    onOpenItem(id)
                }
            },
            onPlayOne = { title, contents, row ->
                scope.launch {
                    val id = graph.repository.savePackAsSeries(title, s.posterUrl, s.description, contents.infoHashHex, contents.infoBytes, contents.rows)
                    packFor = null
                    onPlay("$id::${row.index}")
                }
            },
        )
    }

    webPackFor?.let { p ->
        val s = show
        if (s != null) WebPackDialog(
            pack = p,
            defaultTitle = s.title,
            posterUrl = s.posterUrl,
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

@Composable
private fun ReleaseRow(result: TorrentResult, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 32.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivRed)
        Column(Modifier.weight(1f)) {
            Text(
                result.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val q = com.arkiv.player.data.catalog.QualityLabel.extract(result.name)
            Text(
                "${result.lang.label}${if (q.isNotBlank()) "  ·  $q" else ""}  ·  ${result.seeders} seeds" +
                    if (result.sizeLabel.isNotBlank()) "  ·  ${result.sizeLabel}" else "",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            // Aviso de PACK (batch de varios episodios): avisa antes de bajar decenas de GB.
            if (com.arkiv.player.data.catalog.PackDetector.isPack(result.name)) {
                Text(
                    "PACK · varios episodios",
                    color = androidx.compose.ui.graphics.Color(0xFFFFB74D),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Sub-sección colapsable por tipo (TORRENT/WEB/ARCHIVE) dentro de un episodio. Molde: CineDetailScreen.SourceSection. */
@Composable
private fun AnimeSourceSection(
    tag: String,
    tagColor: Color,
    count: Int,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(start = 32.dp, top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(tagColor.copy(alpha = 0.20f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Text("$tag  $count", color = tagColor, style = MaterialTheme.typography.labelMedium) }
            if (loading) CircularProgressIndicator(color = tagColor, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Colapsar" else "Expandir", tint = ArkivTextSecondary,
            )
        }
        if (expanded) {
            content()
            if (count == 0 && !loading) Text(
                "Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun WebEpRow(r: WebResult, enabled: Boolean, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFB39DDB))
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { r.siteName }, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                r.siteName + listOfNotNull(r.language.ifBlank { null }, r.quality.ifBlank { null }).joinToString("") { "  ·  $it" },
                color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDownload, enabled = enabled) {
            Icon(Icons.Default.Download, contentDescription = "Descargar offline", tint = Color(0xFFB39DDB))
        }
    }
}

@Composable
private fun WebPackRow(pack: MirrorWebPack, enabled: Boolean, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFFFB74D))
        Column(Modifier.weight(1f)) {
            Text(pack.showTitle, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "PACK · ${pack.episodeCount} capítulos" +
                    (if (pack.seasons.size > 1) "  ·  ${pack.seasons.size} temporadas" else "") +
                    "  ·  ${pack.siteId}",
                color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDownload, enabled = enabled) {
            Icon(Icons.Default.Download, contentDescription = "Descargar offline", tint = Color(0xFFFFB74D))
        }
    }
}

@Composable
private fun ArchiveEpRow(item: ArchiveSearchResult, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF80CBC4))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.year.isNotBlank()) Text(item.year, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
