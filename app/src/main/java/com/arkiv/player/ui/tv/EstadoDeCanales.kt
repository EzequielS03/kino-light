package com.arkiv.player.ui.tv

import com.arkiv.player.data.ditu.DituCanal
import com.arkiv.player.data.ditu.FalloDeCaracol

/**
 * Lo que muestra la pestaña "En vivo" de [TvCaracolScreen], armado a partir de la llamada a
 * `DituFuente.canales`.
 *
 * Existe para que un fallo NO se vea igual que "no hay canales": si Caracol o la red fallan, la
 * pestaña lo dice en palabras de persona ([FalloDeCaracol.alCargarLosCanales]; el detalle va al log
 * de `TvCaracolScreen`) y la persona puede reintentar con "Recargar".
 */
internal sealed interface EstadoDeCanales {

    /** Todavía no volvió la primera llamada. */
    object Cargando : EstadoDeCanales

    data class Listos(val canales: List<DituCanal>) : EstadoDeCanales

    /** Caracol respondió, y sin canales. */
    object Vacio : EstadoDeCanales

    data class Fallo(val mensaje: String) : EstadoDeCanales

    companion object {
        fun de(resultado: Result<List<DituCanal>>): EstadoDeCanales = resultado.fold(
            onSuccess = { if (it.isEmpty()) Vacio else Listos(it) },
            onFailure = { Fallo(FalloDeCaracol.alCargarLosCanales(it)) },
        )
    }
}
