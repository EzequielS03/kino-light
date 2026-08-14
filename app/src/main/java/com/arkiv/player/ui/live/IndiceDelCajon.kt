package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel

/**
 * En qué fila se para el cajón de canales al abrirse: la del canal que se está viendo.
 *
 * Existe como función aparte por una razón concreta. Medido en el Fire TV el 2026-08-14: al abrir
 * el cajón, la lista se iba sola hacia arriba durante un rato largo hasta el primer canal. Eran
 * DOS efectos peleando — uno hacía `scrollToItem` al canal en vivo y otro pedía el foco, y el
 * `FocusRequester` estaba puesto en el ítem 0. Pedirle foco a la primera fila arrastra la lista
 * entera de vuelta al principio, y con 1040 canales ese arrastre se ve interminable.
 *
 * El arreglo no es elegir cuál efecto gana: es que haya UN solo índice. La misma fila que recibe
 * el foco es la que se pone a la vista, y por construcción no pueden discrepar.
 */
object IndiceDelCajon {

    /**
     * Nunca devuelve -1: el resultado va derecho a `scrollToItem` y a decidir qué fila lleva el
     * `FocusRequester`. Con el canal fuera de la lista —lo primero que pasa al escribir en el
     * buscador— se para en el primer resultado, que es lo que se quiere mirar en ese momento.
     */
    fun para(canales: List<LiveChannel>, canalActual: String?): Int {
        if (canales.isEmpty() || canalActual.isNullOrBlank()) return 0
        return canales.indexOfFirst { it.code == canalActual }.coerceAtLeast(0)
    }
}
