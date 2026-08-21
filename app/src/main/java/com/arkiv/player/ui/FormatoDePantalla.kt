package com.arkiv.player.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * ¿Estamos en una tablet en horizontal? Es lo único que decide si se usa el layout ancho.
 *
 * Mira el lado MÁS CHICO del aparato ([smallestWidthDp]) y no el ancho actual, porque el ancho
 * actual mentiría: un Galaxy S24+ acostado mide 1040dp de ancho y se llevaría el layout de tablet,
 * que es justo lo que no se quiere. El lado más chico es invariante a la rotación: 480dp en ese
 * celular (nunca califica) y ~800dp en una tablet de 10" (siempre califica).
 *
 * 600dp es el umbral estándar de Android para "tablet" (`sw600dp`).
 */
fun esTabletHorizontal(smallestWidthDp: Int, orientation: Int): Boolean =
    smallestWidthDp >= UMBRAL_TABLET_DP && orientation == Configuration.ORIENTATION_LANDSCAPE

const val UMBRAL_TABLET_DP = 600

/** La misma pregunta, leyendo la configuración actual. Recompone solo al rotar. */
@Composable
fun esTabletHorizontal(): Boolean {
    val config = LocalConfiguration.current
    return esTabletHorizontal(config.smallestScreenWidthDp, config.orientation)
}

/**
 * Columnas de una grilla. En ancho caben el doble: con 3 columnas en 1280dp cada tarjeta quedaría
 * de 400dp, más grande que la pantalla de un celular.
 */
fun columnasDeGrilla(base: Int, esAncho: Boolean): Int = if (esAncho) base * 2 else base
