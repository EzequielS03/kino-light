package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/**
 * Deja que el foco SALGA de un campo de texto con el D-pad.
 *
 * Los `TextField` de Compose (foundation, no tv-material3) consumen arriba/abajo porque los usan
 * para mover el cursor entre líneas. En el celular no molesta —se toca el siguiente campo— pero en
 * el TV el único modo de moverse es el D-pad, así que una vez que el foco entra a un campo ya no
 * sale: en Ajustes no se podía pasar del email a la contraseña ni bajar a "Iniciar sesión".
 *
 * `onPreviewKeyEvent` ve la tecla ANTES que el campo, así que movemos el foco a mano. Si no hay a
 * dónde moverse devolvemos `false` y el evento sigue su curso normal hacia el campo.
 */
@Composable
fun Modifier.dpadFocusEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/** Fallback for cards with no poster (any source can lack one): gradient + video icon. */
@Composable
private fun CardPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Movie,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.size(52.dp),
        )
    }
}

/** Tarjeta apaisada (16:9) con arte a pantalla completa, badge y título superpuesto. Altura fija. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLandscapeCard(
    title: String,
    imageUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = ArkivRed,
    episodeCountLabel: String? = null,
    /**
     * Capítulos nuevos desde la última vez que se abrió el detalle. 0 = no se pinta nada.
     * Ver [com.arkiv.player.data.nuevos.NewEpisodeCounter].
     */
    nuevos: Int = 0,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(ArkivSurfaceHigh),
        ) {
            if (imageUrl.isNullOrBlank()) {
                CardPlaceholder()
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Sin título sobrepuesto: el nombre del contenido se ve arriba en el hero al enfocar.
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (episodeCountLabel != null) {
                Text(
                    text = episodeCountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Novedades. Va abajo a la derecha —y no arriba— porque arriba ya conviven el badge de
            // fuente y el conteo de episodios: una tercera etiqueta ahí tapaba el arte justo donde
            // suele estar la cara del póster. En rojo para que se distinga de los otros dos, que
            // son informativos y grises.
            if (nuevos > 0) {
                Text(
                    text = "+$nuevos",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ArkivRed)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Miniatura apaisada (16:9) con el nombre superpuesto y barra de progreso. Estilo Netflix. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWideCard(
    title: String,
    imageUrl: String?,
    progress: Float,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(ArkivSurfaceHigh),
        ) {
            if (imageUrl.isNullOrBlank()) {
                CardPlaceholder()
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Sin texto sobrepuesto: el nombre se ve arriba en el hero al enfocar.
            // Barra de progreso en el borde inferior.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x66000000)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .background(ArkivRed),
                )
            }
        }
    }
}
