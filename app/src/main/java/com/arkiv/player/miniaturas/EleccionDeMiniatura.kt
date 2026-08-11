package com.arkiv.player.miniaturas

/**
 * Qué imagen se muestra en una tarjeta: el frame capturado si lo hay, y si no el primer respaldo
 * con contenido.
 *
 * Vive acá y no en cada pantalla porque son CUATRO superficies con la misma regla y respaldos
 * distintos (hero, "Continuar viendo", lista de capítulos y tarjeta de serie). Con la regla
 * repetida en cada una, alcanza con que una quede desalineada para que la misma serie se vea
 * distinta en dos lugares de la misma pantalla.
 *
 * No recibe el progreso a propósito: el frame SOLO existe si el capítulo se empezó, así que
 * "gana solo en lo empezado" ya está implícito en que [frame] sea null o no.
 */
object EleccionDeMiniatura {

    fun elegir(frame: String?, vararg respaldos: String?): String? =
        frame?.takeIf { it.isNotBlank() }
            ?: respaldos.firstOrNull { !it.isNullOrBlank() }
}
