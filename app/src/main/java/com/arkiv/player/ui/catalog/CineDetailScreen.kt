package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.AccionDeDescarga
import com.arkiv.player.ui.components.DescargaDeFila
import com.arkiv.player.ui.components.DialogoDeDescarga
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import kotlinx.coroutines.Job
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide al disparar una descarga (a la NUC o al propio
    // dispositivo, el worker de descargas locales también notifica). Ver rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    // Avisa "eso ya lo tenés bajado" cuando la cola saltea una descarga duplicada (ver
    // DuplicateDownloadPolicy): si no, el botón parecería no hacer nada.
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()
    // El control de descarga por fuente era de archive.org ([DescargasPorFuente], borrado en la
    // poda de esta rama); `porConfirmar` queda cableado al diálogo de abajo pero ya nadie lo llena.
    var porConfirmar by remember { mutableStateOf<Pair<DownloadRow, AccionDeDescarga>?>(null) }

    var detail by remember { mutableStateOf<TmdbDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var episodes by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var loadingEps by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var sheetEpisode by remember { mutableStateOf<TmdbEpisode?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<PlaySource>>(emptyList()) }
    // Estado de carga (para el spinner de la sección colapsable).
    var loadingArchive by remember { mutableStateOf(false) }
    // Secciones expandidas (por defecto abierta para ver caer los resultados).
    var expandedSections by remember { mutableStateOf(setOf("ARCHIVE")) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // confirmValueChange bloquea el swipe-to-close (que se disparaba al scrollear); se cierra con la X.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
    )

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
        val seriesId = com.arkiv.player.data.SeriesItemIds.canonicalSeriesId(d.imdbId, d.id)
        scope.launch {
            com.arkiv.player.data.offline.NucDownloads.refreshLibraryCache(
                graph.arkivOfflineApi, graph.database.nucLibraryItemDao(),
                seriesId = seriesId, replace = true,
            )
        }
    }

    // Cargar los capítulos de la temporada elegida (bajo demanda).
    LaunchedEffect(selectedSeason, detail) {
        val s = selectedSeason
        val d = detail
        if (s == null || d == null || !d.isSeries) { episodes = emptyList(); return@LaunchedEffect }
        loadingEps = true
        // `.orEmpty()`: acá solo se pinta una lista, así que "no se pudo consultar" (null) y "TMDB
        // no tenía capítulos" se ven igual. La distinción solo le importa a quien cachea en base.
        episodes = runCatching { graph.tmdbApi.seasonEpisodes(d.id, s) }.getOrNull().orEmpty()
        loadingEps = false
    }

    // La búsqueda de fuentes era archive.org ([graph.api], borrado en la poda de esta rama: ver
    // CLAUDE.md "Cero servidor propio"); magis no tiene wiring acá todavía (ver Task 6 del plan de
    // poda, "Simplificar búsqueda a solo-Magis"), así que el panel de fuentes queda siempre vacío.
    fun runSearch(ep: TmdbEpisode?) {
        searchJob?.cancel()
        loadingArchive = false
        sources = emptyList()
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

    fun playDitu(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val epId = graph.repository.addDituSource(
                ref = r.ref,
                contentId = r.extra["content_id"].orEmpty(),
                title = r.title,
                posterUrl = r.extra["poster"].orEmpty(),
            )
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo preparar Caracol."
        }
    }

    // Reproduce una fuente de Magis: guarda el ítem (id estable por contentId, ref al lado) y usa
    // el player unificado, que resuelve el ref → stream al cargar (loadMagis). Molde: playArchive.
    fun playMagis(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val epId = graph.repository.addMagisSource(
                ref = r.ref,
                contentId = r.extra["content_id"].orEmpty(),
                title = r.title,
                episode = r.episode,
                posterUrl = r.extra["poster"].orEmpty(),
                backdropUrl = r.extra["backdrop"].orEmpty(),
                // La temporada la trae el propio resultado del portal y NO se deja en null: un
                // episodio sin ella, mezclado con otros que sí la tienen, hace que
                // `ensureEpisodeStills` cruce aplanando desde la T1 y pise los stills buenos de toda
                // la serie (ver el KDoc de `MagisEntities.build`). `0` es "no la dijo", no la T0.
                season = r.season.takeIf { it > 0 },
            )
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo preparar Magis."
        }
    }

    // Encola una descarga al dispositivo.
    fun saveLocally(episodeId: String, source: String) {
        scope.launch { notifyDuplicates(listOf(graph.localDownloads.enqueue(episodeId, source))) }
    }

    // Despacha el botón de "Guardar en el dispositivo" de una fila según el tipo de fuente.
    // Archive.org (la única que de verdad se guardaba, vía saveArchiveLocally/addItem) se borró en
    // la poda de esta rama.
    fun downloadSource(s: PlaySource, ep: TmdbEpisode?) = when (s) {
        // Magis no se descarga: el CDN sirve con un token que vence a las ~48 h, así que el
        // archivo bajado dejaría de reproducirse. Es fuente de streaming, no de biblioteca.
        is PlaySource.Magis -> Unit
        // Ditu tampoco: el stream MPEG-DASH se sirve con tokens CDN de vida corta.
        is PlaySource.Ditu -> Unit
    }

    /**
     * El control de descarga de una fuente. SIEMPRE null: la única que lo llevaba era archive.org
     * (`DescargasPorFuente.deArchive`), borrada en la poda de esta rama. Magis tampoco: no se
     * descarga (su CDN vence).
     */
    fun descargaDe(s: PlaySource, ep: TmdbEpisode?): DescargaDeFila? = null

    fun playSource(s: PlaySource) = when (s) {
        is PlaySource.Magis -> playMagis(s.result)
        is PlaySource.Ditu -> playDitu(s.result)
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
                    Text("Preparando…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    if (sheetOpen) {
        val ep = sheetEpisode
        // archive.org se borró en la poda de esta rama: no hay de dónde sacar `sources`, así que
        // esta lista queda siempre vacía (ver `runSearch`).
        val archives: List<PlaySource> = sources
        fun toggle(k: String) { expandedSections = if (k in expandedSections) expandedSections - k else expandedSections + k }

        // El MISMO contenido (PanelDeFuentes) según la forma de la pantalla: hoja modal en
        // vertical/celular (como siempre), panel a la derecha en tablet horizontal.
        if (esTabletHorizontal()) {
            // Panel lateral: no tapa la ficha, deja el botón de Volver y el resto a la vista.
            // Ojo: acá NO se toca `sheetState` (es del ModalBottomSheet, que ni se compone en este
            // camino). Si el usuario gira a vertical con el panel abierto, ModalBottomSheet entra
            // de cero a la composición y se anima solo (su propio efecto interno hace el show());
            // si cierra el panel con sheetOpen = false, sheetState queda tal como estaba (sin tocar),
            // así que no hay estado "a medio animar" esperando a la próxima vez que se muestre.
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Surface(Modifier.width(420.dp).fillMaxHeight(), color = ArkivSurfaceHigh) {
                    PanelDeFuentes(
                        detail = detail, sheetEpisode = ep, archives = archives,
                        loadingArchive = loadingArchive,
                        expandedSections = expandedSections, toggle = { k -> toggle(k) }, preparing = preparing,
                        descargaDe = { s, e -> descargaDe(s, e) }, playSource = { s -> playSource(s) },
                        onCerrar = { sheetOpen = false },
                    )
                }
            }
        } else {
            ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState, containerColor = ArkivSurfaceHigh) {
                PanelDeFuentes(
                    detail = detail, sheetEpisode = ep, archives = archives,
                    loadingArchive = loadingArchive,
                    expandedSections = expandedSections, toggle = { k -> toggle(k) }, preparing = preparing,
                    descargaDe = { s, e -> descargaDe(s, e) }, playSource = { s -> playSource(s) },
                    onCerrar = { sheetOpen = false },
                )
            }
        }
    }

    // Misma pregunta y mismas palabras que en la biblioteca: es la misma acción sobre la misma cola.
    DialogoDeDescarga(
        accion = porConfirmar?.second,
        nombreDelCapitulo = porConfirmar?.first?.displayName,
        onConfirmar = {
            porConfirmar?.let { (fila, accion) ->
                scope.launch {
                    when (accion) {
                        AccionDeDescarga.CANCELAR -> graph.localDownloads.cancel(fila.episodeId)
                        AccionDeDescarga.SACAR_DE_LA_COLA, AccionDeDescarga.BORRAR ->
                            graph.localDownloads.remove(fila.episodeId)
                    }
                }
            }
            porConfirmar = null
        },
        onCerrar = { porConfirmar = null },
    )
}

/**
 * El contenido del buscador de fuentes: cabecera con el título/episodio + la sección colapsable de
 * Archive, con su [SourceRow] y su [ControlDeDescarga] (cola, progreso, cancelar, borrar). Es el
 * MISMO contenido para los dos contenedores posibles — `ModalBottomSheet` en vertical/celular,
 * panel lateral en tablet horizontal—: quien llama decide el contenedor, acá no se sabe cuál es.
 */
@Composable
private fun PanelDeFuentes(
    detail: TmdbDetail?,
    sheetEpisode: TmdbEpisode?,
    archives: List<PlaySource>,
    loadingArchive: Boolean,
    expandedSections: Set<String>,
    toggle: (String) -> Unit,
    preparing: Boolean,
    descargaDe: (PlaySource, TmdbEpisode?) -> DescargaDeFila?,
    playSource: (PlaySource) -> Unit,
    onCerrar: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        val ep = sheetEpisode
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                if (ep != null) "T${ep.season} · E${ep.episode} — ${ep.name}" else (detail?.title ?: "Fuentes"),
                color = Color.White, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCerrar) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }

        if (!loadingArchive && archives.isEmpty()) {
            Text(
                "No se encontraron fuentes.",
                color = ArkivTextSecondary, modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // weight(fill=false) acota la altura del scroll interno al espacio disponible del
            // contenedor (hoja o panel): así es un viewport REAL que scrollea, y el nested-scroll
            // consume el gesto en vez de pasárselo al ModalBottomSheet (que se arrastraba/"intentaba cerrar").
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SourceSection("ARCHIVE", Color(0xFF80CBC4), archives, loadingArchive,
                    "ARCHIVE" in expandedSections, { toggle("ARCHIVE") }, !preparing,
                    descargaDe = { s -> descargaDe(s, ep) },
                ) { playSource(it) }
            }
        }
    }
}
