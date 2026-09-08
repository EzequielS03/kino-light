package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.EtiquetaDeCapitulo
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
    /**
     * Nombre real del capítulo (TMDB o el que trajo el gateway de Magis). Va DEBAJO del número, no
     * en su lugar: el número identifica el capítulo que se va a reproducir y sigue siendo el dato
     * cierto aunque el cruce con TMDB quede corrido. Null (o el capítulo sin nombre resuelto) deja
     * el chip exactamente como estaba.
     */
    episodeTitle: String? = null,
    /** Se llama cuando este chip TOMA el foco, para que la pantalla de arriba siga al capítulo
     *  enfocado (fondo + textos), igual que el hero del Home sigue a la card enfocada. */
    onFocus: (() -> Unit)? = null,
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
    val episodeLabel = EtiquetaDeCapitulo.numero(episode)

    Column(
        modifier = modifier
            .width(168.dp)
            .onFocusChanged {
                // Solo al GANAR el foco: si se avisara también al perderlo, al pasar de un chip al
                // siguiente llegaría el "perdí" del viejo después del "gané" del nuevo y el hero
                // quedaría mostrando el capítulo equivocado.
                if (it.isFocused && !isFocused) onFocus?.invoke()
                isFocused = it.isFocused
            }
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
                .height(94.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            // Preferencia: still real del capítulo (TMDB). La miniatura de archive.org que iba
            // después se borró en la poda de esta rama; sin ninguna de las dos queda fondo negro.
            val thumb = stillUrl
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
        episodeTitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                // Una línea: el chip mide 168 dp y abajo todavía va el progreso. Un nombre largo
                // ("La conspiración de los Saiyajin") se corta, no empuja el resto del carrusel.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/**
 * Chip de una fuente de la serie ("web · 300 ep."), para elegir de cuál ver los capítulos cuando
 * la misma serie entró a la biblioteca desde varias. Mismo tratamiento de foco que
 * [TvEpisodeChip]: solo avisa al GANAR el foco, porque el "perdí" del chip viejo llega después
 * del "gané" del nuevo y dejaría la pantalla mostrando la fuente equivocada.
 */
@Composable
fun TvSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ArkivRed.copy(alpha = 0.25f) else ArkivSurfaceHigh)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}
