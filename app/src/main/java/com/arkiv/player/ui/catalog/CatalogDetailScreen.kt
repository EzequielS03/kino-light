package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.catalog.CatalogMovie
import com.arkiv.player.data.catalog.CatalogTorrent
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

@Composable
fun CatalogDetailScreen(imdbId: String, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var movie by remember { mutableStateOf<CatalogMovie?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Calienta la sesión + DHT del torrent mientras el usuario ve los episodios (arranque más rápido).
    LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }

    LaunchedEffect(imdbId) {
        loading = true
        movie = runCatching { graph.catalogApi.movie(imdbId) }.getOrNull()
        loading = false
    }

    fun play(torrent: CatalogTorrent) {
        val m = movie ?: return
        preparing = true
        error = null
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
            val title = "${m.title}${if (m.year.isNotBlank()) " (${m.year})" else ""} · ${torrent.quality}"
            val itemId = graph.repository.addTorrent(title, meta.infoHashHex, meta.infoBytes, videos, m.posterUrl)
            val epId = graph.repository.firstEpisodeId(itemId)
            preparing = false
            if (epId != null) onPlay(epId)
        }
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val m = movie
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            m == null -> Text(
                "No se pudo cargar la película.",
                color = ArkivTextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                    AsyncImage(
                        model = m.backdropUrl.ifBlank { m.posterUrl },
                        contentDescription = m.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack)),
                        ),
                    )
                }
                Column(Modifier.padding(16.dp)) {
                    Text(m.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        buildString {
                            if (m.year.isNotBlank()) append(m.year)
                            if (m.ratingPct > 0) append("  ·  ★ ${m.ratingPct / 10.0}")
                            if (m.runtime.isNotBlank()) append("  ·  ${m.runtime} min")
                        },
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (m.genres.isNotEmpty()) {
                        Text(
                            m.genres.joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } },
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    Text(
                        "Reproducir",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    m.torrents.forEach { t ->
                        Button(
                            onClick = { play(t) },
                            enabled = !preparing,
                            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(
                                "  ${t.quality}  ·  ${t.seeds} seeds  ${if (t.sizeLabel.isNotBlank()) "· ${t.sizeLabel}" else ""}",
                            )
                        }
                    }

                    if (error != null) {
                        Text(error!!, color = ArkivRed, modifier = Modifier.padding(top = 12.dp))
                    }

                    if (!m.synopsis.isNullOrBlank()) {
                        Text(
                            m.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
        }

        // Botón volver.
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x88000000)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }

        // Overlay al preparar el torrent.
        if (preparing) {
            Box(
                Modifier.fillMaxSize().background(Color(0xAA000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ArkivRed)
                    Text(
                        "Abriendo el torrent…",
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
