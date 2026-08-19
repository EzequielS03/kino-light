package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Qué extremo del capítulo se está marcando. */
internal enum class ModoDeMarcado { INTRO, OUTRO }

/**
 * Marcar el fin de la intro y el arranque de los créditos (solo archive), para que los botones de
 * "Saltar intro/outro" sepan dónde están.
 *
 * Mientras se marca, el reproductor entra en un modo aparte: los controles normales se esconden
 * —cualquier guarda que diga `marcando == null` es de acá— y en su lugar sale el panel-editor con
 * su propio slider.
 */
@Stable
internal class EstadoDeMarcadores {
    /** El extremo que se está marcando ahora, o null si no estamos marcando. */
    var modo by mutableStateOf<ModoDeMarcado?>(null)
        private set

    /** El menú desplegable para elegir qué marcar está abierto. */
    var menuAbierto by mutableStateOf(false)
        private set

    /**
     * Ya estuvimos marcando en esta pantalla. Existe solo para que el `play()` de salida NO corra
     * en la composición inicial: sin esta marca, al abrir una fuente web nueva ese play reviviría
     * el video anterior —que sigue cargado en el service— por detrás del overlay "Resolviendo…"
     * mientras se resuelve la nueva.
     */
    private var estuvoMarcando = false

    /** Se está marcando: los controles normales ceden el lugar al panel-editor. */
    val marcando: Boolean get() = modo != null

    fun abrirMenu() {
        menuAbierto = true
    }

    fun cerrarMenu() {
        menuAbierto = false
    }

    /** Elegir qué marcar cierra el menú y entra al modo. */
    fun marcar(que: ModoDeMarcado) {
        menuAbierto = false
        modo = que
    }

    fun terminar() {
        modo = null
    }

    /**
     * Qué hacer al entrar o salir del modo, para el efecto que lo acompaña: `true` = acabamos de
     * entrar (hay que pausar y ubicar el slider), `false` = acabamos de salir (hay que reanudar),
     * `null` = no hubo transición y no se toca el player.
     */
    fun transicion(): Boolean? = when {
        modo != null -> {
            estuvoMarcando = true
            true
        }

        estuvoMarcando -> {
            estuvoMarcando = false
            false
        }

        else -> null
    }
}

@Composable
internal fun rememberEstadoDeMarcadores(): EstadoDeMarcadores = remember { EstadoDeMarcadores() }
