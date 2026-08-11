package com.arkiv.player.ui.tv

// Estilo único de los botones de acción en TV: inactivo = negro + borde blanco 1dp;
// enfocado/presionado = fondo rojo Arkiv sin borde blanco; deshabilitado = superficie oscura
// atenuada. Fuente única de verdad para que ningún botón vuelva a caer en los colores default de
// tv.material3 (blanco inactivo / negro enfocado, exactamente al revés de lo que queremos).
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/** Colores para `androidx.tv.material3.Button(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvButtonColors() = ButtonDefaults.colors(
    containerColor = ArkivSurfaceHigh,
    contentColor = Color.White,
    focusedContainerColor = ArkivRed,
    focusedContentColor = Color.White,
    pressedContainerColor = ArkivRed,
    pressedContentColor = Color.White,
    disabledContainerColor = ArkivSurfaceHigh,
    disabledContentColor = Color.White.copy(alpha = 0.4f),
)

/** Borde para `androidx.tv.material3.Button(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvButtonBorder() = ButtonDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)

/** Colores para botones de acción hechos con `androidx.tv.material3.Surface(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvSurfaceColors() = ClickableSurfaceDefaults.colors(
    containerColor = ArkivSurfaceHigh,
    focusedContainerColor = ArkivRed,
    pressedContainerColor = ArkivRed,
    contentColor = Color.White,
    focusedContentColor = Color.White,
    pressedContentColor = Color.White,
)

/** Borde para botones de acción hechos con `androidx.tv.material3.Surface(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvSurfaceBorder() = ClickableSurfaceDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)
