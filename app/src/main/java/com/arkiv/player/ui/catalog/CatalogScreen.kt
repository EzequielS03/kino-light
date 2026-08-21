package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.catalog.CatalogItem
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

private data class SortOption(val label: String, val value: String)

private val SORTS = listOf(
    SortOption("Tendencias", "trending"),
    SortOption("Populares", "popularity"),
    SortOption("Recientes", "last added"),
    SortOption("Mejor rating", "rating"),
    SortOption("Año", "year"),
)

@Composable
fun CatalogScreen(
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenAnime: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val vm: CatalogViewModel = viewModel(
        factory = viewModelFactory { initializer { CatalogViewModel(graph.catalogApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf("movies") } // movies | shows | anime
    var queryText by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, items.size) {
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (items.isNotEmpty() && last >= items.size - 4) vm.loadMore()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
    ) {
        // Toggle Películas / Series / Anime.
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chip: @Composable (String, String) -> Unit = { k, label ->
                FilterChip(
                    selected = kind == k,
                    onClick = {
                        kind = k
                        if (k != "anime") vm.setKind(k)
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
            chip("movies", "Películas")
            chip("shows", "Series")
            chip("anime", "Anime")
        }

        if (kind == "anime") {
            AnimeSection(onOpenAnime = onOpenAnime, contentPadding = contentPadding)
            return@Column
        }

        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            placeholder = { Text(if (kind == "shows") "Buscar series…" else "Buscar películas…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(queryText) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SORTS.forEach { s ->
                FilterChip(
                    selected = sort == s.value,
                    onClick = { vm.setSort(s.value) },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
        }

        if (error != null && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = ArkivTextSecondary, modifier = Modifier.padding(32.dp))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnasDeGrilla(3, esTabletHorizontal())),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.imdbId + it.title }) { item ->
                CatalogPoster(item, onClick = {
                    if (item.isShow) onOpenShow(item.imdbId) else onOpenMovie(item.imdbId)
                })
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ArkivRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPoster(item: CatalogItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
        ) {
            AsyncImage(model = item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize())
            if (item.ratingPct > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.padding(end = 2.dp).size(12.dp))
                    Text("${item.ratingPct / 10.0}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (item.year.isNotBlank()) {
            Text(item.year, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
        }
    }
}
