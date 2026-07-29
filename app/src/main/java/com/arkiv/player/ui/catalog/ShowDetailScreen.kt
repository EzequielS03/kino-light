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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.catalog.CatalogEpisode
import com.arkiv.player.data.catalog.CatalogShow
import com.arkiv.player.data.catalog.CatalogTorrent
import com.arkiv.player.data.catalog.PackDetector
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

@Composable
fun ShowDetailScreen(
    imdbId: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf<CatalogShow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableIntStateOf(1) }
    var pickEpisode by remember { mutableStateOf<CatalogEpisode?>(null) }
    var packFor by remember { mutableStateOf<TorrentResult?>(null) }

    // Calienta la sesión + DHT del torrent mientras el usuario ve los episodios (arranque más rápido).
    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

    LaunchedEffect(imdbId) {
        loading = true
        show = runCatching { graph.catalogApi.show(imdbId) }.getOrNull()
        season = show?.episodes?.firstOrNull()?.season ?: 1
        loading = false
    }

    fun play(episode: CatalogEpisode, torrent: CatalogTorrent) {
        val s = show ?: return
        preparing = true
        error = null
        pickEpisode = null
        scope.launch {
            val meta = graph.torrentEngine.resolveMagnet(torrent.magnet)
            if (meta == null) {
                preparing = false
                error = "No se pudo abrir el torrent (puede no tener seeds ahora)"
                return@launch
            }
            val videos = graph.torrentEngine.videoFiles(meta)
                .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
            if (videos.isEmpty()) {
                preparing = false
                error = "El torrent no tiene video reproducible"
                return@launch
            }
            val title = "${s.title} · S${episode.season}E${episode.episode} · ${torrent.quality}"
            val itemId = graph.repository.addTorrent(title, meta.infoHashHex, meta.infoBytes, videos, s.posterUrl)
            val epId = graph.repository.firstEpisodeId(itemId)
            preparing = false
            if (epId != null) onPlay(epId)
        }
    }

    // Intercepta el tap sobre una calidad del episodio: si el nombre de la calidad marca un PACK
    // (varios episodios), abre el diálogo de pack en vez de reproducir directo. En la práctica este
    // catálogo (estilo Popcorn Time) ya viene pre-partido por episodio, así que rara vez dispara,
    // pero se cubre igual por consistencia con Cine/Anime.
    fun playOrPack(episode: CatalogEpisode, torrent: CatalogTorrent) {
        if (PackDetector.isPack(torrent.quality)) {
            packFor = TorrentResult(
                name = torrent.quality,
                seeders = torrent.seeds,
                sizeBytes = 0L,
                lang = TorrentLang.OTHER,
                magnetUri = torrent.magnet,
            )
            pickEpisode = null
        } else {
            play(episode, torrent)
        }
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val s = show
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            s == null -> Text(
                "No se pudo cargar la serie.",
                color = ArkivTextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> {
                val seasons = s.episodes.map { it.season }.distinct().sorted()
                val episodes = s.episodes.filter { it.season == season }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                        AsyncImage(
                            model = s.backdropUrl.ifBlank { s.posterUrl },
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
                                if (s.year.isNotBlank()) append(s.year)
                                if (s.ratingPct > 0) append("  ·  ★ ${s.ratingPct / 10.0}")
                                if (s.numSeasons > 0) append("  ·  ${s.numSeasons} temporadas")
                            },
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (!s.synopsis.isNullOrBlank()) {
                            Text(
                                s.synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArkivTextSecondary,
                                maxLines = 4,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        // Selector de temporada.
                        if (seasons.size > 1) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                seasons.forEach { sn ->
                                    FilterChip(
                                        selected = sn == season,
                                        onClick = { season = sn },
                                        label = { Text("T$sn") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ArkivRed, selectedLabelColor = Color.White,
                                        ),
                                    )
                                }
                            }
                        }

                        episodes.forEach { ep ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !preparing) { pickEpisode = ep }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivRed)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(
                                        "${ep.episode}. ${ep.title.ifBlank { "Episodio ${ep.episode}" }}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        ep.torrents.joinToString("  ·  ") { it.quality },
                                        color = ArkivTextSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }

                        if (error != null) {
                            Text(error!!, color = ArkivRed, modifier = Modifier.padding(top = 12.dp))
                        }
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

    // Diálogo de calidad al tocar un episodio.
    val ep = pickEpisode
    if (ep != null) {
        AlertDialog(
            onDismissRequest = { pickEpisode = null },
            title = { Text("S${ep.season}E${ep.episode} · Calidad") },
            text = {
                Column {
                    ep.torrents.forEach { t ->
                        TextButton(onClick = { playOrPack(ep, t) }) {
                            Text("${t.quality}  ·  ${t.seeds} seeds")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickEpisode = null }) { Text("Cerrar") } },
        )
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
                    val id = graph.repository.savePackAsSeries(title, s.posterUrl, s.synopsis, contents.infoHashHex, contents.infoBytes, rows)
                    packFor = null
                    onOpenItem(id)
                }
            },
            onPlayOne = { title, contents, row ->
                scope.launch {
                    val id = graph.repository.savePackAsSeries(title, s.posterUrl, s.synopsis, contents.infoHashHex, contents.infoBytes, contents.rows)
                    packFor = null
                    onPlay("$id::${row.index}")
                }
            },
        )
    }
}
