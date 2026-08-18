package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import com.arkiv.player.ui.home.CategoriasViewModel
import com.arkiv.player.ui.home.HomeRowSpec
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

private val TV_CARD_HEIGHT = 120.dp
private val TV_CARD_RADIUS = RoundedCornerShape(8.dp)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvCategoriasScreen(
    onBrowseRow: (rowId: String, title: String) -> Unit,
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
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(rows) {
        if (rows.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    if (loading && rows.size <= 8) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val fijas = rows.filter { it.id in setOf("cartelera", "peliculas_populares", "tendencias", "series_populares", "series_top", "anime", "anime_populares", "anime_top") }
    val generosPelis = rows.filter { it.id.startsWith("g_movie_") }
    val generosSeries = rows.filter { it.id.startsWith("g_tv_") }
    val generosAnime = rows.filter { it.id.startsWith("g_anime_") }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TraerConScrollMinimo) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Categorías",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (fijas.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { TvSectionHeader("Destacadas") }
                items(fijas, key = { it.id }) { spec ->
                    LaunchedEffect(spec.id) { vm.fetchPreview(spec.id) }
                    TvCategoryCard(
                        spec = spec,
                        imageUrl = previews[spec.id],
                        modifier = if (spec == fijas.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { onBrowseRow(spec.id, spec.title) },
                    )
                }
            }

            if (generosPelis.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { TvSectionHeader("Géneros · Películas") }
                items(generosPelis, key = { it.id }) { spec ->
                    LaunchedEffect(spec.id) { vm.fetchPreview(spec.id) }
                    TvCategoryCard(
                        spec = spec,
                        imageUrl = previews[spec.id],
                        onClick = { onBrowseRow(spec.id, spec.title) },
                    )
                }
            }

            if (generosSeries.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { TvSectionHeader("Géneros · Series") }
                items(generosSeries, key = { it.id }) { spec ->
                    LaunchedEffect(spec.id) { vm.fetchPreview(spec.id) }
                    TvCategoryCard(
                        spec = spec,
                        imageUrl = previews[spec.id],
                        onClick = { onBrowseRow(spec.id, spec.title) },
                    )
                }
            }

            if (generosAnime.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { TvSectionHeader("Géneros · Anime") }
                items(generosAnime, key = { it.id }) { spec ->
                    LaunchedEffect(spec.id) { vm.fetchPreview(spec.id) }
                    TvCategoryCard(
                        spec = spec,
                        imageUrl = previews[spec.id],
                        onClick = { onBrowseRow(spec.id, spec.title) },
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "← Atrás para volver",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCategoryCard(
    spec: HomeRowSpec,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = spec.title
        .removeSuffix(" · Películas")
        .removeSuffix(" · Series")
        .removeSuffix(" · Anime")

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(TV_CARD_HEIGHT)
            .clip(TV_CARD_RADIUS),
        colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = CardDefaults.shape(TV_CARD_RADIUS),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.88f)),
                        ),
                    ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
