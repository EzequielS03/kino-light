package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkiv.player.data.ArkivRepository

/**
 * Quién es el capítulo que suena y cuáles son sus vecinos: el encabezado del overlay de pausa y los
 * botones de "Capítulo anterior" / "Siguiente episodio".
 *
 * Los tres salen de la misma consulta y cambian juntos —al saltar de capítulo, los tres— así que
 * viajan juntos. Y los tres se leen solo desde el overlay.
 */
@Stable
internal class EstadoDeCabecera(private val repository: ArkivRepository) {
    /** Título del ítem + nombre del episodio (solo series), para el encabezado. */
    var info by mutableStateOf<ArkivRepository.PlayerHeaderInfo?>(null)
        private set

    /**
     * Episodios vecinos, si los hay. Ambos son null en películas (una sola sección, ver
     * EpisodeNavigation) y cada uno lo es en su extremo: el primero de la temporada no tiene
     * anterior, el último no tiene siguiente. Esa nulidad es la ÚNICA condición para mostrar los
     * botones.
     */
    var anterior by mutableStateOf<String?>(null)
        private set

    var siguiente by mutableStateOf<String?>(null)
        private set

    /** Título a mostrar, con [respaldo] para cuando la consulta todavía no volvió. */
    fun titulo(respaldo: String): String = info?.itemTitle ?: respaldo

    /** Temporada/capítulo, o null en películas y mientras no haya respuesta. */
    val etiquetaDeEpisodio: String? get() = info?.episodeLabel

    internal suspend fun cargar(episodioEnCurso: String) {
        anterior = repository.previousEpisode(episodioEnCurso)?.id
        siguiente = repository.nextEpisode(episodioEnCurso)?.id
        info = repository.headerInfo(episodioEnCurso)
    }
}

@Composable
internal fun rememberEstadoDeCabecera(repository: ArkivRepository): EstadoDeCabecera =
    remember(repository) { EstadoDeCabecera(repository) }

/** Recarga la cabecera y los vecinos cada vez que cambia el capítulo que suena. */
@Composable
internal fun EfectoDeCabecera(estado: EstadoDeCabecera, episodioEnCurso: String) {
    LaunchedEffect(episodioEnCurso) { estado.cargar(episodioEnCurso) }
}
