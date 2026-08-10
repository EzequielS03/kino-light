package com.arkiv.player.ui.tv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.biblioteca.GrupoVisto
import com.arkiv.player.data.biblioteca.VistosDeLaBiblioteca
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

    val vistos: StateFlow<List<GrupoVisto>> =
        combine(grupos, repo.observeVistos()) { grupos, vistos ->
            VistosDeLaBiblioteca.cruzar(grupos, vistos)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saca el ítem de la biblioteca. Es soft-delete (ver `ArkivRepository.removeItem`), así que el
     * borrado viaja por el sync y no reaparece desde el otro dispositivo.
     *
     * NO borra los archivos ya descargados al dispositivo: eso se hace desde la sección Descargas.
     * El diálogo que llama a esto lo dice explícitamente.
     */
    fun quitar(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }

    /** Película <-> serie a mano, cuando la detección automática se equivoca. Null = automática. */
    fun setCategory(itemId: String, isMovie: Boolean?) {
        viewModelScope.launch { repo.setCategory(itemId, isMovie) }
    }
}
