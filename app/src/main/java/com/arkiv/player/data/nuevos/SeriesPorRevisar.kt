package com.arkiv.player.data.nuevos

import com.arkiv.player.data.ditu.DituFuente

/**
 * Una serie de la biblioteca con lo mínimo para decidir si vale revisarla.
 *
 * Deliberadamente plano y sin tipos de Room: así la decisión de a quién preguntarle se puede
 * probar sin base de datos, que es donde de verdad se puede equivocar uno.
 */
data class SerieCandidata(
    val itemId: String,
    /** "magis" | "ditu" today; legacy rows can still carry "archive" | "web" | "torrent". */
    val fuente: String,
    /** Cuándo se reprodujo por última vez algo de esta serie. 0 = nunca. */
    val ultimoVistoMs: Long,
    /** Episodios que hay hoy en la biblioteca para esta serie. */
    val episodios: Int,
)

/**
 * A qué series preguntarles si salió un capítulo nuevo, cuando se abre la app.
 *
 * El chequeo cuesta red —y para web, una búsqueda por capítulo candidato— así que preguntarle a
 * las 50 series de la biblioteca en cada arranque sería gastar batería y datos en 45 que nadie
 * está viendo. Los filtros de acá son lo que hace que esto sea barato de correr siempre:
 *
 * - **Con progreso reciente.** "La serie que estoy viendo" es literalmente eso: una en la que
 *   dejaste una marca hace poco. Si la retomás dentro de un año, el propio progreso la vuelve a
 *   traer sola.
 * - **Series, no películas.** Un ítem de un solo episodio no tiene capítulo siguiente.
 * - **Las más frescas primero, y cortadas.** Si seguís 40 series, las 10 que tocaste último son
 *   las que te importan hoy; el resto entra en el próximo arranque.
 */
object SeriesPorRevisar {

    /** Cuántas series se revisan por arranque. */
    const val MAX_SERIES = 10

    /** Qué tan atrás cuenta como "la estoy viendo". */
    const val VENTANA_DIAS = 30L

    private const val DIA_MS = 24 * 60 * 60 * 1000L

    /**
     * Sources this check lets through. "magis" and "ditu" (Caracol) both lead somewhere today
     * (`BuscadorDeCapitulos.revisarMagis`/`revisarDitu`). "archive" and "web" were removed in this
     * branch's pruning and are left out on purpose: keeping them here only cost a real series a
     * slot, since `BuscadorDeCapitulos` no-ops on both. Torrent stays out too (see the spec).
     */
    private val FUENTES = setOf("magis", DituFuente.SOURCE)

    fun elegir(candidatas: List<SerieCandidata>, ahoraMs: Long): List<SerieCandidata> {
        val piso = ahoraMs - VENTANA_DIAS * DIA_MS
        return candidatas.asSequence()
            .filter { it.fuente in FUENTES }
            .filter { it.episodios > 1 }
            .filter { it.ultimoVistoMs > 0L && it.ultimoVistoMs >= piso }
            .sortedByDescending { it.ultimoVistoMs }
            .take(MAX_SERIES)
            .toList()
    }
}
