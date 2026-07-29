package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/**
 * Tarjeta de episodio para un carrusel horizontal (usada en el overlay de pausa del player y en
 * el detalle de una serie): miniatura + número + progreso ("10 de 25 min" / "Visto") + barra fina.
 * Reutilizable con D-pad: [modifier] es donde el llamador cuelga focusRequester/focusProperties.
 */
@Composable
fun TvEpisodeChip(
    episode: Episode,
    isCurrent: Boolean,
    progress: PlaybackEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Still del capítulo (TMDB). Si es null se cae al thumb de archive.org. */
    stillUrl: String? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val totalMin = (episode.durationSeconds / 60).toInt().coerceAtLeast(0)
    val watchedFrac = if (progress != null && progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // Solo mostrar el progreso en minutos si hay duración real: en packs de torrent sin
    // metadata de duración, "0 de 0 min" no dice nada útil.
    val progressLabel = when {
        progress == null || progress.positionMs <= 0 || totalMin <= 0 -> null
        progress.watched -> "Visto"
        else -> "${(progress.positionMs / 60000).toInt().coerceAtLeast(0)} de $totalMin min"
    }
    // orderIndex es secuencial (0, 1, 2...) para archive.org, pero en packs de torrent
    // codifica temporada*1000 + episodio (p. ej. 1002 = T1E2) para poder ordenar por
    // temporada/episodio en una sola columna — hay que decodificarlo para mostrarlo.
    val episodeLabel = if (episode.orderIndex >= 1000) {
        "T${episode.orderIndex / 1000} · E${episode.orderIndex % 1000}"
    } else {
        "${episode.orderIndex + 1}"
    }

    Column(
        modifier = modifier
            .width(120.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) ArkivRed.copy(alpha = 0.25f) else ArkivSurfaceHigh)
            .border(
                width = if (isFocused) 3.dp else if (isCurrent) 2.dp else 0.dp,
                color = if (isFocused) Color.White else if (isCurrent) ArkivRed else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            // Preferencia: still real del capítulo (TMDB) -> miniatura de archive.org. Los packs
            // de torrent sin match en TMDB no tienen ninguna de las dos y quedan con el fondo negro.
            val thumb = stillUrl
                ?: episode.thumbPath?.let { ArchiveUrls.download(episode.itemId, it) }
            AsyncImage(
                model = thumb,
                contentDescription = episode.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (watchedFrac > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(watchedFrac)
                            .fillMaxSize()
                            .background(ArkivRed),
                    )
                }
            }
        }
        Text(
            episodeLabel,
            color = if (isCurrent) ArkivRed else Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        progressLabel?.let {
            Text(
                it,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
