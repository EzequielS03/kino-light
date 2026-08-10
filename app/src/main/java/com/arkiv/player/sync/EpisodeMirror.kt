package com.arkiv.player.sync

/**
 * Decide si hay que volver a copiar la lista de episodios de un ítem que YA existe en el destino.
 *
 * El espejo de biblioteca solo copiaba episodios al crear el ítem; si ya existía, se limitaba a
 * adoptar la categoría. Resultado: una serie que gana capítulos en el origen quedaba congelada para
 * siempre en el destino (Dragon Ball GT: 29 en la TV mientras el origen ya iba en 58).
 */
object EpisodeMirror {

    /**
     * Compara por id, sin importar el orden. Un id nuevo o uno que desapareció obliga a recopiar.
     *
     * Excepción deliberada: si el origen no trae NINGÚN episodio de ese ítem no se toca nada. Un
     * snapshot sin episodios casi siempre significa que todavía no llegaron, y recopiar dejaría la
     * serie vacía y sin forma de recuperarla; el precio es no propagar el caso real (y rarísimo) de
     * un ítem al que le borraron todos los capítulos.
     */
    fun differs(local: List<String>, remote: List<String>): Boolean {
        if (remote.isEmpty()) return false
        return local.toSet() != remote.toSet()
    }
}
