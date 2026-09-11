package com.arkiv.player.data.nuevos

/**
 * Qué capítulos pedirle a la fuente cuando se revisa una serie que estás viendo.
 *
 * Lo que hace caro este chequeo es web: el gateway no expone lista de episodios para esa fuente
 * (`MagisCatalog.detail` es solo de magis, el resto responde 422), así que hay que hacer **una búsqueda
 * por capítulo candidato**. Sin cotas, seguir 15 series serían cientos de búsquedas en cada
 * arranque de la app. Este objeto es el que las pone.
 *
 * Son dos cotas, y cada una tapa un agujero distinto:
 *
 * 1. **Solo lo posterior al máximo que ya tenés.** Un hueco en el medio (tengo 1,3,4,5) casi
 *    siempre es un capítulo que la fuente nunca tuvo — un doblaje que falta, un archivo que el
 *    uploader nunca subió. Volver a buscarlo en cada arranque es pagar esa búsqueda eternamente
 *    para recibir el mismo "no hay". Lo nuevo, en cambio, siempre llega por arriba.
 *
 * 2. **Tope por serie.** Una serie que dejaste hace un año puede tener 20 capítulos nuevos de
 *    golpe; traerlos todos de una la dejaría comerse el presupuesto entero del arranque. Se traen
 *    los primeros y el resto en la próxima, que además es el orden en que los vas a ver.
 */
object CapitulosFaltantes {

    /** Cuántos capítulos nuevos se traen por serie y por chequeo. */
    const val MAX_POR_SERIE = 5

    /**
     * Los números que hay que pedirle a la fuente: los de [enLaFuente] que faltan en [tengo],
     * posteriores al máximo ya guardado, sin repetir, en orden y acotados a [MAX_POR_SERIE].
     *
     * Nunca propone borrar: si la fuente reporta MENOS de lo que hay guardado (pasa cuando un
     * ítem se re-deriva o el portal esconde temporadas viejas) el resultado es vacío, no una
     * lista de bajas.
     */
    fun aPedir(tengo: Collection<Int>, enLaFuente: Collection<Int>): List<Int> {
        val piso = tengo.maxOrNull() ?: 0
        return enLaFuente.asSequence()
            .filter { it > piso }
            .distinct()
            .sorted()
            .take(MAX_POR_SERIE)
            .toList()
    }

    /**
     * Season-aware variant of [aPedir], for a source that numbers chapters PER SEASON (Caracol):
     * plain [aPedir] compares against the highest NUMBER stored, so season 2's chapter 1 would
     * look like it's already covered by a season 1 that has ten chapters.
     *
     * The key is `(season ?: 0, number)`, compared season-first. Same two bounds as [aPedir] --
     * only keys past the highest stored key, capped at [MAX_POR_SERIE] -- just applied to the pair
     * instead of a single number.
     */
    fun toFetchBySeason(
        have: Collection<Pair<Int?, Int>>,
        inSource: Collection<Pair<Int?, Int>>,
    ): List<Pair<Int, Int>> {
        fun key(pair: Pair<Int?, Int>): Pair<Int, Int> = (pair.first ?: 0) to pair.second
        val floor = have.map(::key).maxWithOrNull(KEY_ORDER)
        return inSource.asSequence()
            .map(::key)
            .filter { floor == null || KEY_ORDER.compare(it, floor) > 0 }
            .distinct()
            .sortedWith(KEY_ORDER)
            .take(MAX_POR_SERIE)
            .toList()
    }

    /** Season first, then number -- the same order [toFetchBySeason]'s keys compare by. */
    private val KEY_ORDER = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
}
