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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.DituCatalogResponse
import com.arkiv.player.data.gateway.DituSerieItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

private data class Categoria(val label: String, val tags: Set<String>?, val soloMovies: Boolean = false)

private val CATEGORIAS = listOf(
    Categoria("Todos", tags = null),
    Categoria("Novelas", tags = setOf("Telenovela")),
    Categoria("Series", tags = setOf("Serie", "Ficción", "Drama", "Acción", "Misterio")),
    Categoria("Películas", tags = null, soloMovies = true),
    Categoria("Reality", tags = setOf("Reality", "Talent show", "Talent Show")),
    Categoria("Noticias", tags = setOf("Periodístico", "Noticiero Colombiano", "Noticias", "Periodismo", "Informativo")),
    Categoria("Deportes", tags = setOf("Deportes", "Leyendas deportivas", "Mundial 2026", "Mundial", "Sueños deportivos", "Recopilación de partidos")),
    Categoria("Entretenimiento", tags = setOf("Entretenimiento", "Talk show", "Talk Show", "Video Podcast", "Entrevista")),
)

@Composable
fun CaracolScreen(
    contentPadding: PaddingValues,
    onPlay: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    val columnas = columnasDeGrilla(3, esTabletHorizontal())
    val focusManager = LocalFocusManager.current

    var catalogo by remember { mutableStateOf<DituCatalogResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var serieAbierta by remember { mutableStateOf<DituSerieItem?>(null) }
    var query by remember { mutableStateOf("") }
    var categoria by remember { mutableStateOf(CATEGORIAS[0]) }

    LaunchedEffect(Unit) {
        try {
            val resp = graph.arkivApiClient.dituCatalog()
            catalogo = resp.copy(series = resp.series.sortedBy { it.title.lowercase() })
        } catch (e: Throwable) {
            error = "No se pudo cargar el catálogo: ${e.message}"
        }
    }

    val seriesFiltradas = remember(catalogo, query, categoria) {
        var lista = catalogo?.series ?: return@remember null
        val tags = categoria.tags
        lista = when {
            categoria.soloMovies -> lista.filter { it.isMovie }
            tags != null -> lista.filter { !it.isMovie && it.tag in tags }
            else -> lista
        }
        val q = query.trim()
        if (q.isNotEmpty()) lista = lista.filter { it.title.contains(q, ignoreCase = true) }
        lista
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
            catalogo == null -> {
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Buscar…") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                items(CATEGORIAS) { cat ->
                                    FilterChip(
                                        selected = categoria == cat,
                                        onClick = { categoria = cat },
                                        label = { Text(cat.label) },
                                    )
                                }
                            }
                        }
                    }

                    if (seriesFiltradas.isNullOrEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (query.isBlank()) "Sin contenido en esta categoría"
                                else "Sin resultados para \"$query\"",
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        items(seriesFiltradas, key = { it.contentId }) { serie ->
                            CaracolSerieCard(
                                serie = serie,
                                onClick = {
                                    if (serie.isMovie) {
                                        scope.launch {
                                            val ep = GatewayEpisode(number = 1, title = serie.title, ref = serie.ref)
                                            val result = playback.playDituEpisode(
                                                serie = serie.toGatewayResult(),
                                                ep = ep,
                                                epIndex = 0,
                                                serieInfo = null,
                                                posterOverride = serie.posterUrl,
                                                backdropOverride = "",
                                            )
                                            if (result is PlaybackResult.Ready) onPlay(result.episodeId)
                                        }
                                    } else {
                                        serieAbierta = serie
                                    }
                                },
                            )
                        }
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
