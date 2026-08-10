package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Tarjeta de póster (2:3, ancho = alto × 2/3) con título debajo, hasta 2 líneas.
 * Mismo patrón de foco/color que [TvLandscapeCard].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPosterCard(
    title: String,
    posterUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    /** Segunda línea bajo el título ("24 ep.", "12 capítulos vistos"). Null = no se dibuja. */
    subtitle: String? = null,
    onFocus: () -> Unit = {},
    /** Mantener pulsado. Null = la tarjeta no ofrece menú contextual. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(cardHeight * 2f / 3f),
    ) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocus() },
            scale = CardDefaults.scale(focusedScale = 1.08f),
            colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
            border = CardDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
            ),
        ) {
            if (posterUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(ArkivSurfaceHigh),
                )
            } else {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
        if (showTitle && subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
