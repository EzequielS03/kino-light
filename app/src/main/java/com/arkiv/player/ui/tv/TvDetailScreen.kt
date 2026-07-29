package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.detail.DetailViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Detalle de un ítem, estilo Prime Video: backdrop a pantalla completa con degradado, info
 * (título/cantidad de episodios/descripción/reproducir) sobre la izquierda, y abajo un carrusel
 * horizontal de episodios — el mismo [TvEpisodeChip] que usa el overlay de pausa del player.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDetailScreen(
    identifier: String,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: DetailViewModel = viewModel(
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, identifier) } },
    )
    val detail by vm.detail.collectAsStateWithLifecycle()
    val data = detail ?: return

    val playFR = remember { FocusRequester() }
    val resumeEpisodeFR = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()

    // El carrusel abre posicionado en el capítulo que se venía viendo (el mismo que reproduce el
    // botón Reproducir). En series largas quedaba fuera de pantalla y había que buscarlo a mano.
    // Stills de TMDB por capítulo: se resuelven una vez y quedan cacheados en la base; Coil
    // se encarga del caché de las imágenes en disco.
    val stills by graph.repository.observeEpisodeStills(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(identifier) {
        runCatching { graph.repository.ensureEpisodeStills(identifier) }
    }

    val resumeId = data.resumeEpisode?.id
    LaunchedEffect(resumeId) {
        val idx = data.episodes.indexOfFirst { it.id == resumeId }
        if (idx > 0) episodesListState.scrollToItem(idx)
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        AsyncImage(
            model = data.thumbnailUrl,
            contentDescription = data.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Degradado horizontal: negro a la izquierda para leer el texto (igual que el hero del Home).
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
            ),
        )
        // Degradado vertical: negro abajo para fundir con el carrusel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            // --- Info (título, cantidad, descripción, reproducir) ---
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    data.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (data.episodes.size > 1) "${data.episodes.size} episodios" else "Película",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                data.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
                    )
                }
                data.resumeEpisode?.let { resume ->
                    Button(
                        onClick = { onPlayEpisode(resume.id) },
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .focusRequester(playFR)
                            .focusProperties { down = resumeEpisodeFR },
                    ) {
                        Text("▶  Reproducir")
                    }
                }
            }

            // --- Carrusel de episodios (mismo componente que el overlay de pausa del player) ---
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    if (data.episodes.size > 1) "Episodios" else "Detalles",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    state = episodesListState,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(data.episodes, key = { it.id }) { ep ->
                        val isResume = ep.id == resumeId
                        TvEpisodeChip(
                            episode = ep,
                            isCurrent = data.inProgressEpisode?.id == ep.id,
                            progress = data.progress[ep.id],
                            stillUrl = stills[ep.id],
                            onClick = { onPlayEpisode(ep.id) },
                            modifier = Modifier.then(
                                if (isResume) {
                                    Modifier.focusRequester(resumeEpisodeFR).focusProperties { up = playFR }
                                } else {
                                    Modifier
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
