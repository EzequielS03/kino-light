package com.arkiv.player.ui.home

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.rememberGraph

@Composable
fun CategoriasScreen(
    contentPadding: PaddingValues,
    onBrowseRow: (rowId: String, title: String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: CategoriasViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriasViewModel(graph.tmdbApi, graph.aniListApi) } },
    )
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    if (loading && rows.size <= 8) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Agrupamos las filas por tipo para mostrar cabeceras de sección.
    val fijas = rows.filter { it.id.none { c -> c == '_' } || it.id in listOf("cartelera", "peliculas_populares", "tendencias", "series_populares", "series_top", "anime", "anime_populares", "anime_top") }
    val generosPelis = rows.filter { it.id.startsWith("g_movie_") }
    val generosSeries = rows.filter { it.id.startsWith("g_tv_") }
    val generosAnime = rows.filter { it.id.startsWith("g_anime_") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item {
            Text(
                "Categorías",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }

        if (fijas.isNotEmpty()) {
            item { SectionHeader("Destacadas") }
            items(fijas, key = { it.id }) { spec ->
                CategoryRow(spec.title) { onBrowseRow(spec.id, spec.title) }
            }
        }

        if (generosPelis.isNotEmpty()) {
            item { SectionHeader("Géneros · Películas") }
            items(generosPelis, key = { it.id }) { spec ->
                CategoryRow(spec.title.removeSuffix(" · Películas")) { onBrowseRow(spec.id, spec.title) }
            }
        }

        if (generosSeries.isNotEmpty()) {
            item { SectionHeader("Géneros · Series") }
            items(generosSeries, key = { it.id }) { spec ->
                CategoryRow(spec.title.removeSuffix(" · Series")) { onBrowseRow(spec.id, spec.title) }
            }
        }

        if (generosAnime.isNotEmpty()) {
            item { SectionHeader("Géneros · Anime") }
            items(generosAnime, key = { it.id }) { spec ->
                CategoryRow(spec.title.removeSuffix(" · Anime")) { onBrowseRow(spec.id, spec.title) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun CategoryRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
}
