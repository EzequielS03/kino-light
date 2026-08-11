package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel

/**
 * Recorre **la lista con la que se entró** al canal (la categoría, los favoritos o el
 * resultado de búsqueda), que es la que el usuario tiene en la cabeza. Da la vuelta
 * en los extremos, como un decodificador.
 */
class LiveZapping(private val lista: List<LiveChannel>, indiceInicial: Int) {
    private var indice = indiceInicial.coerceIn(0, (lista.size - 1).coerceAtLeast(0))

    val actual: LiveChannel get() = lista[indice]

    fun siguiente(): LiveChannel {
        indice = (indice + 1) % lista.size
        return actual
    }

    fun anterior(): LiveChannel {
        indice = (indice - 1 + lista.size) % lista.size
        return actual
    }

    /** Vacío si hay un solo canal: no hay a dónde zapear ni qué precalentar. */
    fun vecinos(): List<LiveChannel> {
        if (lista.size < 2) return emptyList()
        return listOf(lista[(indice + 1) % lista.size], lista[(indice - 1 + lista.size) % lista.size])
    }
}

/**
 * Puente efímero entre la pantalla "En vivo" (grilla/guía) y el reproductor: la lista con la que
 * el usuario entró (categoría, favoritos, recientes o resultado de búsqueda) -- lo que necesita
 * [LiveZapping] para recorrer la MISMA lista que el usuario tiene en la cabeza, no el catálogo
 * entero.
 *
 * Una lista de [LiveChannel] no cruza bien un NavHost basado en argumentos String: la ruta
 * "player/{episodeId}" (compartida con VOD) solo lleva el código del canal. Mismo problema que ya
 * resuelve [com.arkiv.player.playback.NowPlaying] para otro estado efímero entre pantallas, y la
 * misma solución: un objeto mutable de corta vida, fijado ANTES de navegar (por `LiveScreen` /
 * `TvLiveGuideScreen`, vía `ArkivRoot`/`ArkivTvRoot`) y consumido una única vez por
 * `PlayerViewModel.loadLive` al abrir un episodeId con el prefijo
 * [com.arkiv.player.playback.PlayerSource.LIVE_PREFIX].
 *
 * Si no hay nada fijado acá (el proceso se recreó a mitad del reproductor en vivo, o quien navega
 * no pasó por la grilla) `PlayerViewModel` cae a una lista de un solo canal: se pierde el zapping
 * hasta volver a la grilla, pero el canal elegido reproduce igual.
 */
object LiveZappingSource {
    var lista: List<LiveChannel> = emptyList()
}
