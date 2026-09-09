package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// Los renglones de Ajustes de TV. Los comparten los cuatro tabs (TvSettings*.kt), así que viven
// acá y no dentro de ninguno: si cada tab tuviera el suyo, el foco se vería distinto según dónde
// estés parado.

// Estilo único de los botones de Ajustes (TvActionOption): inactivo = negro + borde blanco 1dp;
// enfocado/presionado = fondo rojo Arkiv, sin borde blanco. Delega al estilo compartido de
// botones-acción de TV (TvButtonStyle.kt) para que Ajustes no se desincronice del resto de la app.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun tvBotonColors() = arkivTvSurfaceColors()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun tvBotonBorder() = arkivTvSurfaceBorder()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvActionOption(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Superficie negra + borde blanco inactivo, rojo Arkiv al enfocar/presionar (estándar
        // compartido en TvButtonStyle.kt). Sin colores explícitos el Surface de tv.material3 cae
        // en el esquema claro por defecto de la librería y el botón se veía BLANCO.
        colors = tvBotonColors(),
        border = tvBotonBorder(),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(16.dp))
    }
}
