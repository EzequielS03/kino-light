package com.arkiv.player.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.DituSerieItem
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

@Composable
fun CaracolScreen(
    contentPadding: PaddingValues,
    onPlay: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    val columnas = columnasDeGrilla(3, esTabletHorizontal())

    var series by remember { mutableStateOf<List<DituSerieItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var serieAbierta by remember { mutableStateOf<DituSerieItem?>(null) }

    LaunchedEffect(Unit) {
        try {
            series = graph.arkivApiClient.dituCatalog()
        } catch (e: Throwable) {
            error = "No se pudo cargar el catálogo: ${e.message}"
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> {
                Text(
                    error!!,
                    color = ArkivTextSecondary,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            series == null -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnas),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    items(series!!, key = { it.contentId }) { serie ->
                        CaracolSerieCard(serie = serie, onClick = { serieAbierta = serie })
                    }
                }
            }
        }
    }

    serieAbierta?.let { serie ->
        MagisSeasonDialog(
            season = serie.toGatewayResult(),
            client = graph.arkivApiClient,
            onDismiss = { serieAbierta = null },
            onPlay = { capitulos, capitulo, serieInfo ->
                serieAbierta = null
                scope.launch {
                    val result = playback.playDituEpisode(
                        serie = serie.toGatewayResult(),
                        ep = capitulo,
                        epIndex = capitulos.indexOf(capitulo),
                        serieInfo = serieInfo,
                        posterOverride = serie.posterUrl,
                        backdropOverride = "",
                    )
                    if (result is PlaybackResult.Ready) onPlay(result.episodeId)
                }
            },
            onSave = { elegidos, serieInfo ->
                serieAbierta = null
                scope.launch {
                    playback.saveDituSeason(
                        serieResult = serie.toGatewayResult(),
                        elegidos = elegidos,
                        serieInfo = serieInfo,
                        posterOverride = serie.posterUrl,
                        backdropOverride = "",
                    )
                }
            },
        )
    }
}

@Composable
private fun CaracolSerieCard(serie: DituSerieItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = serie.posterUrl,
            contentDescription = serie.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = serie.title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
