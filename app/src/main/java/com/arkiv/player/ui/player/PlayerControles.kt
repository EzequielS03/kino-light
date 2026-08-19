package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Inactividad tras la que el overlay se va solo, mientras el video esté andando. */
private const val INACTIVIDAD_MS = 4500L

/**
 * Si el overlay de controles de VOD está en pantalla, y su auto-ocultado.
 *
 * El modo vivo NO usa esto: tiene su propio par visible+tick en [EstadoDeVivo], porque la barra de
 * progreso y la fila de transporte que estas dos variables gobiernan no existen en un directo.
 *
 * Arranca oculto a propósito: al abrir se ve el spinner de carga y luego el video limpio, sin el
 * overlay de pausa encima. Hay que tocar la pantalla (o cualquier tecla en TV) para traerlo.
 */
@Stable
internal class EstadoDeControles {
    var visible by mutableStateOf(false)
        private set

    /**
     * Sube con cada señal de actividad y reinicia la cuenta del auto-ocultado. Es un tick y no un
     * timestamp porque lo único que se necesita es relanzar el efecto: dos interacciones en el
     * mismo milisegundo tienen que contar como dos.
     */
    var tickDeActividad by mutableIntStateOf(0)
        private set

    /** Hubo actividad: trae el overlay y reinicia la cuenta. Es el `bump()` de siempre. */
    fun huboActividad() {
        visible = true
        tickDeActividad++
    }

    /**
     * Solo reinicia la cuenta, sin traer el overlay. Lo usa la navegación con D-pad DENTRO del
     * overlay ya abierto: moverse entre botones no debe dejarlo desvanecerse en plena navegación.
     */
    fun sigueVivo() {
        tickDeActividad++
    }

    fun ocultar() {
        visible = false
    }

    /** Un toque sobre el video: si el overlay está puesto lo saca, y si no, lo trae. */
    fun alternar() {
        if (visible) ocultar() else huboActividad()
    }
}

@Composable
internal fun rememberEstadoDeControles(): EstadoDeControles = remember { EstadoDeControles() }

/**
 * Auto-ocultado del overlay tras [INACTIVIDAD_MS] de inactividad real.
 *
 * El tick se reinicia con cada tecla mientras el overlay está abierto (ver el onPreviewKeyEvent del
 * contenedor), así que no se desvanece en plena navegación de botones o miniaturas.
 *
 * NO se bloquea del todo a propósito: el auto-ocultado es la única salida cuando el video está en
 * pausa —BACK también cierra el overlay, pero solo mientras se esté viendo—. Y por eso pide
 * [reproduciendo]: en pausa el overlay se queda.
 *
 * [carruselRevelado] va como clave para que abrir o cerrar el carrusel de capítulos arranque un
 * temporizador fresco.
 */
@Composable
internal fun EfectoDeAutoOcultado(
    estado: EstadoDeControles,
    reproduciendo: Boolean,
    marcando: Boolean,
    carruselRevelado: Boolean,
) {
    LaunchedEffect(estado.tickDeActividad, reproduciendo, estado.visible, carruselRevelado) {
        if (estado.visible && reproduciendo && !marcando) {
            delay(INACTIVIDAD_MS)
            estado.ocultar()
        }
    }
}
