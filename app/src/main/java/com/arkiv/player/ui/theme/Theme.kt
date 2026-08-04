package com.arkiv.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ArkivRed = Color(0xFFE50914)
val ArkivBlack = Color(0xFF0E0E0E)
val ArkivSurface = Color(0xFF181818)
val ArkivSurfaceHigh = Color(0xFF242424)
val ArkivTextPrimary = Color(0xFFF5F5F5)
val ArkivTextSecondary = Color(0xFFB3B3B3)

/**
 * Verde ya usado en el resto de la app para "estado bueno" (idioma LATINO en [com.arkiv.player.ui.catalog.PlaySources],
 * categoría "series" en [com.arkiv.player.ui.search.SearchScreen]) -- se reusa para marcar
 * "ya descargado en la NUC" y así no inventar un color nuevo ni pisar el rojo de marca
 * (reservado a CTAs). Vive acá, y no en una pantalla, porque lo comparten el diálogo de packs
 * ([com.arkiv.player.ui.catalog.WebPackDialog]) y el detalle de "Mi biblioteca"
 * ([com.arkiv.player.ui.detail.DetailScreen]): son el MISMO indicador y tienen que verse igual.
 */
val NucDownloadedGreen = Color(0xFF4CAF50)

private val ArkivColorScheme = darkColorScheme(
    primary = ArkivRed,
    onPrimary = Color.White,
    secondary = ArkivRed,
    background = ArkivBlack,
    onBackground = ArkivTextPrimary,
    surface = ArkivSurface,
    onSurface = ArkivTextPrimary,
    surfaceVariant = ArkivSurfaceHigh,
    onSurfaceVariant = ArkivTextSecondary,
    outline = Color(0xFF3A3A3A),
)

private val ArkivTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun ArkivTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // Arkiv siempre es oscuro
    MaterialTheme(
        colorScheme = ArkivColorScheme,
        typography = ArkivTypography,
        content = content,
    )
}
