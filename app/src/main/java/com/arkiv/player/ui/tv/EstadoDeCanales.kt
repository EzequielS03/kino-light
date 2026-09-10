package com.arkiv.player.ui.tv

import com.arkiv.player.data.ditu.DituCanal

/**
 * Lo que muestra la pestaña "En vivo" de [TvCaracolScreen], armado a partir de la llamada a
 * `DituFuente.canales`.
 *
 * Existe para que un fallo NO se vea igual que "no hay canales": si Caracol o la red fallan, la
 * pestaña lo dice con el mensaje del error y la persona puede reintentar con "Recargar".
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
            onFailure = {
                Fallo(it.message?.takeIf { m -> m.isNotBlank() } ?: "No se pudieron cargar los canales de Caracol.")
            },
        )
    }
}
