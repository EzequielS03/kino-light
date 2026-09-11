package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.ditu.DituCanal
import com.arkiv.player.data.ditu.DituFuente
import com.arkiv.player.data.ditu.DituItem
import com.arkiv.player.data.ditu.FalloDeCaracol
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.DituVivo
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Qué sección del catálogo de Caracol se ve. Arranca en "Series" -- ver el brief. */
private enum class SeccionDeCaracol(val etiqueta: String) {
    SERIES("Series"),
    PELICULAS("Películas"),
    EN_VIVO("En vivo"),
}

/**
 * La sección de Caracol en el celular: su catálogo y sus canales en vivo.
 *
 * Misma data que el televisor ([com.arkiv.player.ui.tv.TvCaracolScreen]): el catálogo lo guarda
 * [DituFuente.catalogoCompleto] 6 h ("Recargar" lo pide igual), los canales se piden cada vez que se
 * entra, y el split en series/películas es el mismo [CaracolCatalogo] que usa el televisor.
 *
 * Abrir un título va por el MISMO camino que la búsqueda ([SearchPlayback], ver
 * `SearchScreen.playDituResult`): una película se guarda y reproduce con
 * [SearchPlayback.playDitu], y una serie abre [MagisSeasonDialog] -- que al elegir un capítulo
 * guarda la serie entera con [SearchPlayback.playDituSeason]. Caracol es Widevine (no se puede
 * bajar), así que la ventana se abre sin casillas de guardar (`onSave = null`), igual que en la
 * búsqueda.
 *
 * Un canal en vivo no pasa por la biblioteca: viaja por [DituVivo.dejar], igual que en el
 * televisor.
 */
@Composable
fun CaracolScreen(onPlay: (episodeId: String) -> Unit, contentPadding: PaddingValues) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }

    var titulos by remember { mutableStateOf<List<DituItem>>(emptyList()) }
    var canales by remember { mutableStateOf<EstadoDeCanales>(EstadoDeCanales.Cargando) }
    var cargando by remember { mutableStateOf(true) }
    var errorCatalogo by remember { mutableStateOf<String?>(null) }
    var recargas by remember { mutableStateOf(0) }
    var seccion by remember { mutableStateOf(SeccionDeCaracol.SERIES) }

    // Serie de Caracol abierta: se eligen los capítulos antes de reproducir, igual que en la
    // búsqueda -- ver el KDoc de arriba.
    var dituSeason by remember { mutableStateOf<GatewayResult?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recargas) {
        cargando = true
        // Cada cosa falla sola: que no haya canales no puede dejar la pantalla sin catálogo.
        runCatching { graph.dituFuente.catalogoCompleto(forzar = recargas > 0) }
            .onSuccess { titulos = it; errorCatalogo = null }
            .onFailure {
                // El detalle va al log; en pantalla, en palabras de persona.
                android.util.Log.w("CaracolScreen", "no cargó el catálogo", it)
                errorCatalogo = FalloDeCaracol.alCargarElCatalogo(it)
            }
        val resultadoDeCanales = runCatching { graph.dituFuente.canales() }
        resultadoDeCanales.exceptionOrNull()
            ?.let { android.util.Log.w("CaracolScreen", "no cargaron los canales", it) }
        canales = EstadoDeCanales.de(resultadoDeCanales)
        cargando = false
    }

    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    // Lo mismo que hace la búsqueda con un resultado de Caracol (SearchScreen.playDituResult).
    fun abrirTitulo(item: DituItem) {
        if (preparing) return
        val fuente = PlaySource.Ditu(DituFuente.resultadoDe(item))
        if (fuente.esSerie()) {
            dituSeason = fuente.result
            return
        }
        preparing = true
        playError = null
        scope.launch { applyResult(playback.playDitu(fuente.result)) }
    }

    val catalogo = remember(titulos) { CaracolCatalogo.de(titulos) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SeccionDeCaracol.values().forEach { s ->
                        FilterChip(
                            selected = seccion == s,
                            onClick = { seccion = s },
                            label = { Text(s.etiqueta) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArkivCaracolVerde,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
                IconButton(onClick = { recargas++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = ArkivTextSecondary)
                }
            }

            if (playError != null) {
                Text(
                    playError!!,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val gridPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            )

            when {
                cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ArkivRed)
                }
                seccion == SeccionDeCaracol.EN_VIVO -> CaracolCanales(
                    canales = canales,
                    contentPadding = gridPadding,
                    onAbrir = { canal -> onPlay(DituVivo.dejar(canal)) },
                )
                else -> CaracolGrilla(
                    titulos = if (seccion == SeccionDeCaracol.SERIES) catalogo.series else catalogo.peliculas,
                    vacio = if (seccion == SeccionDeCaracol.SERIES) "series" else "películas",
                    error = errorCatalogo,
                    contentPadding = gridPadding,
                    onAbrir = ::abrirTitulo,
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

    dituSeason?.let { serie ->
        MagisSeasonDialog(
            season = serie,
            client = graph.fuenteDeContenido,
            onDismiss = { dituSeason = null },
            onPlay = { capitulos, capitulo, s ->
                dituSeason = null
                preparing = true
                playError = null
                scope.launch { applyResult(playback.playDituSeason(serie, capitulos, capitulo, s)) }
            },
            // Sin casillas de "Guardar": Caracol es Widevine y no se baja (ver `FuenteDeDescarga`).
            // A la biblioteca entra al reproducir, igual que en la búsqueda.
            onSave = null,
            etiqueta = "Caracol",
            acento = ArkivCaracolVerde,
        )
    }
}

/** La grilla de 3 columnas de series/películas, o el estado vacío/error de esa sección. */
@Composable
private fun CaracolGrilla(
    titulos: List<DituItem>,
    vacio: String,
    error: String?,
    contentPadding: PaddingValues,
    onAbrir: (DituItem) -> Unit,
) {
    if (titulos.isEmpty()) {
        EmptyState(
            title = error ?: "Caracol no tiene $vacio para mostrar.",
            subtitle = if (error != null) "Probá otra vez con «Recargar»." else null,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnasDeGrilla(3, esTabletHorizontal())),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(titulos, key = { it.ref() }) { item ->
            PosterCard(title = item.titulo, imageUrl = item.posterUrl, onClick = { onAbrir(item) })
        }
    }
}

/** La pestaña "En vivo": la lista de canales, o el estado vacío/error de [EstadoDeCanales]. */
@Composable
private fun CaracolCanales(
    canales: EstadoDeCanales,
    contentPadding: PaddingValues,
    onAbrir: (DituCanal) -> Unit,
) {
    when (canales) {
        EstadoDeCanales.Cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ArkivRed)
        }
        EstadoDeCanales.Vacio -> EmptyState(title = "Caracol no tiene canales en vivo para mostrar.")
        is EstadoDeCanales.Fallo -> EmptyState(
            title = canales.mensaje,
            subtitle = "Probá otra vez con «Recargar».",
        )
        is EstadoDeCanales.Listos -> LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            items(canales.canales, key = { it.channelId }) { canal ->
                CaracolCanalRow(canal = canal, onClick = { onAbrir(canal) })
            }
        }
    }
}

/** Una fila de canal: logo + nombre, mismo tratamiento visual que `GuiaCanalRow` (ui/live). */
@Composable
private fun CaracolCanalRow(canal: DituCanal, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (canal.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = canal.logoUrl,
                    contentDescription = canal.nombre,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            } else {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
            }
        }
        Text(
            text = canal.nombre,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivCaracolVerde)
    }
}
