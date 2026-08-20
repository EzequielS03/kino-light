package com.arkiv.player.data.marcadores

import android.util.Log
import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.ArkivApiClient
import kotlinx.coroutines.CancellationException

/**
 * Trae del gateway los tiempos de intro/outro de un capítulo y los guarda.
 *
 * Perezoso: se pide al reproducir, no en un barrido de la serie entera — Dragon Ball son 153
 * capítulos y se ven en orden. Y como `skip_markers` se sincroniza, lo resuelto en el celu ya está
 * en el TV sin volver a preguntar, y sin red la segunda vez.
 *
 * Lo que se guarda va con `origen = MarcadorDeCapitulo.ORIGEN_AUTO`: nunca MANUAL. La regla de
 * precedencia en [MarcadorDeCapitulo.elegir] hace que lo puesto a mano le gane siempre a lo
 * automático -- si esto se guardara como manual, un tiempo malo que trajera AniSkip ya no se
 * podría corregir a mano nunca.
 */
class BuscadorDeMarcadores(
    private val dao: SkipMarkerDao,
    private val gateway: ArkivApiClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** No devuelve nada: quien dibuja el botón lee la base, que es la fuente única. */
    suspend fun asegurar(itemId: String, episodeId: String, tmdbId: Int, temporada: Int, episodio: Int) {
        if (tmdbId <= 0 || episodio <= 0 || itemId.isBlank() || episodeId.isBlank()) return
        val id = MarcadorDeCapitulo.idDe(itemId, episodeId)
        // Ya preguntado: la fila existe aunque esté vacía. No se vuelve a preguntar por algo que
        // el gateway ya dijo que no sabe -- eso ya lo cachea él, pero la ida de red igual cuesta.
        if (dao.getById(id) != null) return
        val m = try {
            gateway.marcadores(tmdbId, temporada, episodio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("ArkivMarcadores", "sin marcadores para $episodeId: ${e.message}")
            return
        } ?: return
        dao.upsert(
            SkipMarkerEntity(
                id = id, itemId = itemId, episodeId = episodeId,
                openingStartMs = m.openingStartMs, openingEndMs = m.openingEndMs,
                endingStartMs = m.endingStartMs, updatedAt = clock(),
                origen = MarcadorDeCapitulo.ORIGEN_AUTO,
            ),
        )
    }
}
