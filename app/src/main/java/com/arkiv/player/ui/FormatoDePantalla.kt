package com.arkiv.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/**
 * Tope de ancho para el contenido que se LEE (ajustes, descargas, la pantalla de entrada): en una
 * tablet horizontal un renglón de 1280dp es ilegible, así que el contenido se acota y se centra.
 *
 * Vive acá y no repetido en cada pantalla para que el número sea uno solo: tres copias del mismo
 * `720.dp` se desincronizan a la primera que alguien ajuste.
 *
 * Ojo con el orden: este modificador tiene que ir ANTES de un `fillMaxSize()`/`fillMaxWidth()`, o el
 * segundo pisa la restricción y el tope no hace nada.
 */
@Composable
fun Modifier.anchoDeLectura(): Modifier =
    widthIn(max = if (esTabletHorizontal()) 720.dp else Dp.Unspecified)
