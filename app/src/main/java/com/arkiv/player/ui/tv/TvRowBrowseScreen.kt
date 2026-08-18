package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.*
import com.arkiv.player.AppGraph
import com.arkiv.player.ui.home.RowBrowseViewModel
import com.arkiv.player.ui.home.searchShortcutRoute
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRowBrowseScreen(
    rowId: String,
    title: String,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
    graph: AppGraph,
) {
    BackHandler { onBack() }

    val vm: RowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { RowBrowseViewModel(rowId, graph.tmdbApi, graph.aniListApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val state = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 10 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArkivBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Encabezado fijo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 32.dp, bottom = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }

            // Grid de contenido
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val cardHeight = 120.dp
                items(items, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                    val art = card.backdropUrl.ifBlank { card.posterUrl }
                    TvLandscapeCard(
                        title = card.title,
                        imageUrl = art,
                        cardHeight = cardHeight,
                        onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = ArkivRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        // Pista de navegación: "atrás para volver"
        Text(
            text = "← Atrás para volver",
            style = MaterialTheme.typography.labelSmall,
            color = ArkivTextSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = 24.dp),
        )
    }
}
