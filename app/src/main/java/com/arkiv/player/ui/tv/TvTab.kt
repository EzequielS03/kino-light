package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface

private val ALTO_TAB = 52.dp

/**
 * Tab de una fila horizontal de TV: el gesto de "cambiar de sección grande" con el control.
 *
 * Nació como las raíces del catálogo ([TvSeccionesDeCatalogo]) y lo comparte Ajustes
 * ([TvSettingsScreen]), para que las dos filas se vean y se enfoquen igual. Si divergieran, el
 * mismo movimiento del control tendría dos aspectos distintos según la pantalla.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvTab(
    etiqueta: String,
    seleccionada: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(ALTO_TAB),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            // Seleccionada y enfocada NO pueden ser el mismo rojo: con los dos iguales, mirando la
            // pantalla no se distingue en qué tab estás parado de cuál está abierto.
            containerColor = if (seleccionada) ArkivRed else ArkivSurface,
            focusedContainerColor = if (seleccionada) ArkivRed else ArkivSurface,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, Color.White),
                shape = RoundedCornerShape(24.dp),
            ),
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(
                etiqueta,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
