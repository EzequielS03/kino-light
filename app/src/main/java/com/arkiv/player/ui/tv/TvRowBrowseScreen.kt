package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val hasError by vm.hasError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val state = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 10 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    // Foco inicial: cuando llegan los primeros ítems, solicita el foco al primer elemento del grid.
    // Se usa runCatching para tolerar el caso en que el Composable todavía no está completamente
    // inicializado ("FocusRequester is not initialized").
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(items) {
        if (items.isNotEmpty()) {
            runCatching { firstItemFocus.requestFocus() }
        }
    }

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

            Box(modifier = Modifier.fillMaxSize()) {
                // Grid de contenido envuelto en CompositionLocalProvider para el scroll mínimo en TV
                CompositionLocalProvider(LocalBringIntoViewSpec provides TraerConScrollMinimo) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val cardHeight = 120.dp
                        itemsIndexed(
                            items,
                            key = { _, card -> "${card.kind}-${card.tmdbId}-${card.anilistId}" },
                        ) { index, card ->
                            val art = card.backdropUrl.ifBlank { card.posterUrl }
                            TvLandscapeCard(
                                title = card.title,
                                imageUrl = art,
                                cardHeight = cardHeight,
                                modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
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

                // Estado vacío: sin ítems, sin carga en curso y sin más páginas disponibles
                if (items.isEmpty() && !isLoading && !canLoadMore) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No se pudo cargar el contenido",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Reintentar",
                                color = ArkivRed,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clickable { vm.resetAndLoad() }
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
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
