package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.ui.home.CategoriasViewModel
import com.arkiv.player.ui.home.HomeRowSpec
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTextSecondary

private const val HERO_ESCALA = 1.12f
private const val HERO_DERIVA_MS = 14_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCategoriasScreen(
    onBrowseRow: (rowId: String, title: String) -> Unit,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val graph = rememberGraph()
    val vm: CategoriasViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriasViewModel(graph.tmdbApi, graph.aniListApi) } },
    )
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()

    var featured by remember { mutableStateOf<Featured?>(null) }
    val navSound = rememberNavSound()

    // Restaurar scroll guardado en el VM (sobrevive navigate → back).
    val rowsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.tvScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.tvScrollOffset,
    )
    LaunchedEffect(rowsListState) {
        snapshotFlow { rowsListState.firstVisibleItemIndex to rowsListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                vm.tvScrollIndex = index
                vm.tvScrollOffset = offset
            }
    }

    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    // Un grupo = label + fila + spacer. Alto fijo para que encajen exactamente 2 grupos visibles.
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad

    val heroDeriva by rememberInfiniteTransition(label = "heroDeriva").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = HERO_DERIVA_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDerivaX",
    )

    if (loading && rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Agrupar secciones de una vez para pasarlas como items atómicos al LazyColumn.
    data class Section(val key: String, val label: String, val suffix: String, val specs: List<HomeRowSpec>)
    val sections = buildList {
        val fijas = rows.filter { it.id in setOf("cartelera", "peliculas_populares", "tendencias", "series_populares", "series_top", "anime", "anime_populares", "anime_top") }
        if (fijas.isNotEmpty()) add(Section("destacadas", "Destacadas", "", fijas))
        val pelis = rows.filter { it.id.startsWith("g_movie_") }
        if (pelis.isNotEmpty()) add(Section("pelis", "Géneros · Películas", " · Películas", pelis))
        val series = rows.filter { it.id.startsWith("g_tv_") }
        if (series.isNotEmpty()) add(Section("series", "Géneros · Series", " · Series", series))
        val anime = rows.filter { it.id.startsWith("g_anime_") }
        if (anime.isNotEmpty()) add(Section("anime", "Géneros · Anime", " · Anime", anime))
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {

        // Fondo inmersivo: preview de la categoría enfocada + degradados.
        Crossfade(targetState = featured?.imageUrl, animationSpec = tween(450), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd)
                        .graphicsLayer {
                            val margen = size.width * (HERO_ESCALA - 1f) / 2f
                            scaleX = HERO_ESCALA
                            scaleY = HERO_ESCALA
                            translationX = (heroDeriva * 2f - 1f) * margen
                        },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {

            // ── Hero fijo ──────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Text(
                    "Categorías",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ArkivTextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                featured?.let { f ->
                    Text(
                        f.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                }
            }

            // ── Filas de categorías (exactamente 2 secciones visibles) ─────────────────────────
            // Cada sección es UN item del LazyColumn (label + fila horizontal + spacer en Column)
            // para que la altura sea atómica y encaje sin cortar la segunda fila.
            CompositionLocalProvider(LocalBringIntoViewSpec provides TraerConScrollMinimo) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowsRegionHeight)
                        .padding(top = rowsTopPad),
                ) {
                    items(sections, key = { it.key }) { section ->
                        Column {
                            TvRowLabel(section.label, labelHeight)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides PivotoDeTv) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(section.specs, key = { it.id }) { spec ->
                                        LaunchedEffect(spec.id) { vm.fetchPreview(spec.id) }
                                        val imageUrl = previews[spec.id]
                                        val label = spec.title.removeSuffix(section.suffix)
                                        TvLandscapeCard(
                                            title = label,
                                            imageUrl = imageUrl,
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = Featured(title = label, subtitle = "", imageUrl = imageUrl)
                                            },
                                            onClick = { onBrowseRow(spec.id, spec.title) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    item(key = "bottom_pad") { Spacer(Modifier.height(rowGap)) }
                }
            }
        }
    }
}
