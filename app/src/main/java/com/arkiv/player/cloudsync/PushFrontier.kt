package com.arkiv.player.cloudsync

/** Resultado de intentar subir una fila. [settled] = subió bien, o está en cuarentena. */
data class RowOutcome(val updatedAt: Long, val settled: Boolean)

/**
 * Decide hasta dónde puede avanzar el cursor de push de una colección.
 *
 * El cursor es una MARCA DE AGUA: `getXSince(cursor)` devuelve las filas con `updatedAt > cursor`,
 * así que todo lo que queda por debajo del cursor no se vuelve a mirar nunca. Antes se avanzaba al
 * máximo `updatedAt` de TODAS las filas procesadas, subieran o no: cualquier fallo — validación
 * permanente o un simple bache de red — enterraba esas filas para siempre (pérdida silenciosa;
 * pasó de verdad, se perdieron 21 borrados).
 *
 * La regla correcta: avanzar solo hasta lo que quedó resuelto, deteniéndose ESTRICTAMENTE ANTES
 * de la fila sin resolver más vieja, para que el próximo ciclo la vuelva a tomar.
 */
object PushFrontier {

    fun advance(current: Long, outcomes: List<RowOutcome>): Long {
        // La fila sin resolver más vieja pone el techo: el cursor debe quedar por DEBAJO de ella
        // (el filtro es `> cursor`, así que dejarlo en su mismo valor ya la saltaría).
        val techo = outcomes.filter { !it.settled }.minOfOrNull { it.updatedAt }
        val avance = outcomes
            .filter { it.settled && (techo == null || it.updatedAt < techo) }
            .maxOfOrNull { it.updatedAt }
            ?: return current
        return maxOf(current, avance)
    }
}
