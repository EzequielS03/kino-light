package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup

/**
 * Lo visto de UN ítem de la biblioteca. Modelo puro: la fila de Room (`VistoRow`) se mapea a esto
 * en el repositorio, para que este objeto se pueda testear sin traer Room al test.
 */
data class VistoDeItem(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** Un grupo de la biblioteca con lo que ya se vio de él. */
data class GrupoVisto(
    val grupo: LibraryGroup,
    val capitulosVistos: Int,
    val ultimoVistoMs: Long,
)

/**
 * Arma la sección "Ya visto" cruzando el progreso por ítem con los grupos de la biblioteca.
 *
 * Se cruza contra los GRUPOS y no contra los ítems sueltos por el mismo motivo que la fila de
 * Series del home: una serie guardada desde varias fuentes tiene que ser una sola tarjeta.
 */
object VistosDeLaBiblioteca {

    /**
     * [vistos] llega por itemId. Un grupo cuenta como visto si CUALQUIERA de sus miembros tiene
     * capítulos vistos.
     *
     * El conteo es el **máximo** entre miembros y no la suma: las adquisiciones son copias
     * alternativas de la misma serie, no contenido disjunto (mismo criterio que
     * `LibraryGroup.episodeCount`, donde sumar 6 adquisiciones daba 794 capítulos para una serie
     * de ~220).
     *
     * Las filas de [vistos] cuyo ítem ya no pertenece a ningún grupo se ignoran: quitar algo de la
     * biblioteca no borra su progreso de `playback`, y sin este filtro reaparecería acá para
     * siempre.
     */
    fun cruzar(grupos: List<LibraryGroup>, vistos: List<VistoDeItem>): List<GrupoVisto> {
        val porItem = vistos.associateBy { it.itemId }
        return grupos.mapNotNull { grupo ->
            val filas = grupo.members.mapNotNull { porItem[it.identifier] }
            if (filas.isEmpty()) return@mapNotNull null
            GrupoVisto(
                grupo = grupo,
                capitulosVistos = filas.maxOf { it.episodios },
                ultimoVistoMs = filas.maxOf { it.ultimoVistoMs },
            )
        }.sortedByDescending { it.ultimoVistoMs }
    }

    /** "1 capítulo visto" / "3 capítulos vistos". Singular a mano: sin esto, uno solo decía "1 capítulos vistos". */
    fun etiquetaDeVistos(capitulos: Int): String =
        if (capitulos == 1) "1 capítulo visto" else "$capitulos capítulos vistos"
}
