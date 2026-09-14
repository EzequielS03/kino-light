package com.arkiv.player.ui.tv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.biblioteca.WatchedGroup
import com.arkiv.player.data.biblioteca.LibraryWatched
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla "Mi biblioteca" del TV.
 *
 * NO reusa `HomeViewModel` a propósito: ese arrastra todo el motor de descubrimiento (pide géneros
 * a TMDB y AniList en su `init` y cachea ~40 filas de títulos). Abrir la biblioteca crearía una
 * segunda instancia completa de eso para una pantalla que no muestra descubrimiento.
 *
 * Tampoco expone el `artwork`: la grilla usa carátulas (`LibraryRow.thumbnailUrl`), no los backdrops
 * apaisados que el home necesita para el hero. El artwork igual se resuelve — `observeLibraryGroups`
 * lo lee de la base para agrupar, y `ensureArtwork` ya corre desde el home, que es el destino de
 * arranque del TV.
 */
class TvLibraryViewModel(private val repo: ArkivRepository) : ViewModel() {

    val grupos: StateFlow<List<LibraryGroup>> = repo.observeLibraryGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vistos: StateFlow<List<WatchedGroup>> =
        combine(grupos, repo.observeWatchedItems()) { grupos, vistos ->
            LibraryWatched.cross(grupos, vistos)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saca TODOS los miembros del grupo de la biblioteca (soft-delete, ver `ArkivRepository.removeItem`),
     * no solo `primary`.
     *
     * La grilla dibuja grupos (`LibraryGroup`), pero `removeItem` opera fila por fila. Si una serie
     * está guardada desde dos fuentes (p. ej. los 4 Naruto agrupados en `tv:46260`), borrar solo
     * `primary` deja viva la peor copia y la tarjeta sigue en pantalla: el texto de confirmación
     * promete "se quita en todos tus aparatos" y con un solo miembro no lo cumple.
     *
     * Sin test: solo itera `repo.removeItem`, y este repo corre sobre Room, que en este proyecto no
     * se testea sin Robolectric (no lo hay -- ver restricciones del proyecto). Un test de este método
     * sería o bien contra Room de verdad (fuera de alcance acá) o un wrapper artificial que solo
     * probaría el wrapper, no este código.
     */
    fun quitarGrupo(grupo: LibraryGroup) {
        viewModelScope.launch {
            grupo.members.forEach { repo.removeItem(it.identifier) }
        }
    }

    /** Película <-> serie a mano, cuando la detección automática se equivoca. Null = automática. */
    fun setCategory(itemId: String, isMovie: Boolean?) {
        viewModelScope.launch { repo.setCategory(itemId, isMovie) }
    }
}
