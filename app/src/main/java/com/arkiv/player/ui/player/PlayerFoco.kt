package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * Los puntos de aterrizaje del foco D-pad (TV) en el overlay de pausa: navegación real entre los
 * botones y la barra, en vez de acciones fijas por tecla.
 *
 * Ver el `LaunchedEffect(controlsVisible)` de la pantalla: al mostrarse el overlay el foco de
 * Android pasa del video —que atajaba TODAS las teclas— a estos requesters; al ocultarse vuelve al
 * video, para el "cualquier tecla = mostrar los controles".
 *
 * Están juntos en un solo objeto porque son once cosas del mismo mecanismo y siempre viajan al
 * mismo sitio. Sueltos, cualquier composable que dibuje un pedazo del overlay tiene que recibirlos
 * de a uno, y esa firma crece con cada botón nuevo.
 */
@Immutable
internal class FocosDelOverlay {
    /** Fila de íconos: subtítulos/audio. */
    val subtitulos = FocusRequester()

    /** Los dos pasos del modo noche. */
    val bajarBrillo = FocusRequester()
    val subirBrillo = FocusRequester()

    val trivia = FocusRequester()

    /** Fila de transporte. */
    val retroceder = FocusRequester()
    val playPausa = FocusRequester()
    val adelantar = FocusRequester()

    /** Solo en series, y cada uno solo si existe ese vecino. */
    val episodioAnterior = FocusRequester()
    val episodioSiguiente = FocusRequester()

    /** La barra de progreso: es el punto al que entra el foco al abrirse el overlay. */
    val barra = FocusRequester()

    /** Botón de override "Reproducir en vivo" (Task 11, solo reproduciendo desde la NUC). */
    val verEnVivo = FocusRequester()
}

@Composable
internal fun rememberFocosDelOverlay(): FocosDelOverlay = remember { FocosDelOverlay() }
