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
/**
 * Distingue un lote que rebotó ENTERO de unas pocas filas malas entre muchas.
 *
 * La cuarentena ([SyncQuarantine]) existe para que UNA fila inválida no atasque la colección para
 * siempre: tras N intentos se la da por perdida y el cursor la pasa de largo. Pero cuando el
 * rechazo viene del servidor y afecta a TODAS las filas por igual, ese mecanismo se vuelve en
 * contra — cada fila quema sus intentos, todas caen en cuarentena y el cursor salta por encima de
 * la biblioteca completa. Pasó de verdad: 1.095 `episodes` + 307 `progress` rechazados en 25 h
 * porque el `accountId` del aparato quedó desfasado del de su record y la regla
 * `accountId = @request.auth.accountId` los rechazaba en bloque.
 *
 * Si NADA del lote pasó, no son N filas malas: es el servidor. No se cuentan los intentos, y como
 * las filas quedan sin resolver [PushFrontier] deja el cursor quieto hasta que se arregle.
 *
 * Con una sola fila el caso es indistinguible de una fila envenenada, así que ahí se conserva la
 * cuarentena: es la garantía de que la colección no se atasca para siempre.
 */
object PushLote {
    fun esRechazoSistemico(intentadas: Int, fallidas: Int): Boolean =
        intentadas > 1 && fallidas == intentadas
}

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
