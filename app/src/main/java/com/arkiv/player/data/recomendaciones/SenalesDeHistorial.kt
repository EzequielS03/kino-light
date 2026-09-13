package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.FilaDeHistorial
import com.arkiv.player.data.model.TipoDeObra

/** Lo que el modelo necesita saber de algo que viste. [tipo] es `"tv"` o `"movie"`. */
internal data class Vista(val titulo: String, val tipo: String, val estado: String)

/**
 * El historial local → las señales que le importan al modelo. Port de
 * `arkiv-api/src/arkiv_api/recomendaciones/historial.py`, sobre la base de la app.
 *
 * - **terminado**: marcado como visto.
 * - **abandonado**: menos de [UMBRAL_ABANDONO] visto.
 * - A mitad de camino no dice nada (lo estás viendo ahora): se salta y decide una fila más vieja
 *   del mismo ítem, si la hay.
 *
 * **Se pierde *repetido***: la app guarda solo la última reproducción de cada capítulo, no un
 * historial de reproducciones. El contenido de adultos no aparece por construcción: `saveProgress`
 * en `PlayerViewModel` nunca escribe su progreso en `playback` (ver `hayQueAnotarHistorial` y
 * `AdultContent.shouldLog`), así que no hay fila que esta consulta pueda leer.
 */
internal object SenalesDeHistorial {
    const val UMBRAL_ABANDONO = 0.10
    const val TOPE = 30

    fun de(filas: List<FilaDeHistorial>): List<Vista> {
        val decididos = mutableSetOf<String>()
        val salida = mutableListOf<Vista>()
        for (f in filas.sortedByDescending { it.lastPlayedAt }) {
            if (f.itemId in decididos) continue
            val estado = when {
                f.watched -> "terminado"
                f.durationMs > 0 && f.positionMs.toDouble() / f.durationMs < UMBRAL_ABANDONO -> "abandonado"
                else -> continue
            }
            decididos += f.itemId
            val titulo = f.tituloCanonico?.takeIf { it.isNotBlank() } ?: f.titulo
            salida += Vista(titulo, TipoDeObra.de(f.tipo, f.categoryOverride, f.episodio), estado)
            if (salida.size >= TOPE) break
        }
        return salida
    }

    /** El renglón que el gateway le mandaba al modelo (`recomendaciones/modelo.py`). */
    fun renglones(vistas: List<Vista>): String =
        vistas.joinToString("\n") { "- ${it.titulo} (${it.tipo}): ${it.estado}" }
}
