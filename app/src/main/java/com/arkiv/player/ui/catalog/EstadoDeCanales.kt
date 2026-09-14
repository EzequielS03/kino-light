package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.ditu.CaracolFailure

/**
 * What the Caracol "En vivo" tab shows, built from the `DituFuente.canales` call. Shared by the TV
 * screen (`com.arkiv.player.ui.tv.TvCaracolScreen`) and the phone one ([CaracolScreen]).
 *
 * Exists so a failure does NOT look like "no channels": if Caracol or the network fails, the tab
 * says so in plain words ([CaracolFailure.onLoadChannels]; the detail goes to whichever
 * screen's log) and the person can retry with "Recargar".
 */
internal sealed interface EstadoDeCanales {

    /** Todavía no volvió la primera llamada. */
    object Cargando : EstadoDeCanales

    data class Listos(val canales: List<DituChannel>) : EstadoDeCanales

    /** Caracol respondió, y sin canales. */
    object Vacio : EstadoDeCanales

    data class Fallo(val mensaje: String) : EstadoDeCanales

    companion object {
        fun de(resultado: Result<List<DituChannel>>): EstadoDeCanales = resultado.fold(
            onSuccess = { if (it.isEmpty()) Vacio else Listos(it) },
            onFailure = { Fallo(CaracolFailure.onLoadChannels(it)) },
        )
    }
}
