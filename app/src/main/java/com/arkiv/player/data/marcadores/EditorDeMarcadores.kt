package com.arkiv.player.data.marcadores

import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity

/**
 * La corrección a mano de los tiempos, del capítulo que se está viendo o de la serie entera.
 *
 * Existe porque la fuente automática **se equivoca de verdad**: para un capítulo de Dragon Ball
 * devolvió los créditos etiquetados como opening, no hay forma fiable de detectarlo desde acá, y
 * por eso [MarcadorDeCapitulo.ORIGEN_MANUAL] gana siempre sobre [MarcadorDeCapitulo.ORIGEN_AUTO].
 *
 * Y existe **por capítulo** porque hasta ahora no lo hacía nadie: los dos caminos manuales que
 * había (`DetailViewModel.saveSkipMarker` y `PlayerViewModel.updateMarker`) escribían siempre con
 * `episodeId = ""`, o sea un manual de la SERIE, que por la precedencia de
 * [MarcadorDeCapitulo.elegir] le pisa el automático correcto a todos los demás capítulos. Corregir
 * uno rompía los otros veinte, y la rama `manualCapitulo` de `elegir` era inalcanzable.
 *
 * Cada método **fusiona con lo que ya hubiera en esa misma fila** (la del capítulo o la de la
 * serie, según el `episodeId`): la fuente se equivoca de a un campo, así que corregir el opening
 * no puede borrar el ending que ya estaba bien.
 */
class EditorDeMarcadores(
    private val dao: SkipMarkerDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** "El opening termina acá". `openingStartMs` queda en 0 si no había nada: el opening empieza al principio. */
    suspend fun finDelOpening(itemId: String, episodeId: String, posicionMs: Long) {
        val previo = dao.getById(MarcadorDeCapitulo.idDe(itemId, episodeId))
        guardar(itemId, episodeId, previo?.openingStartMs ?: 0L, posicionMs, previo?.endingStartMs)
    }

    /** "El ending empieza acá". */
    suspend fun inicioDelEnding(itemId: String, episodeId: String, posicionMs: Long) {
        val previo = dao.getById(MarcadorDeCapitulo.idDe(itemId, episodeId))
        guardar(itemId, episodeId, previo?.openingStartMs, previo?.openingEndMs, posicionMs)
    }

    /**
     * "Este capítulo no tiene intro ni outro".
     *
     * En un capítulo NO borra la fila: la deja manual y sin tiempos. Sin tiempos,
     * [MarcadorDeCapitulo.elegir] la ignora y no dibuja botones.
     *
     * En la serie sí borra, que es lo que hacía siempre el diálogo de la ficha: ahí no hay nada
     * automático que pueda volver.
     */
    suspend fun quitar(itemId: String, episodeId: String) {
        if (episodeId.isEmpty()) dao.delete(itemId) else guardar(itemId, episodeId, null, null, null)
    }

    private suspend fun guardar(
        itemId: String,
        episodeId: String,
        openingStartMs: Long?,
        openingEndMs: Long?,
        endingStartMs: Long?,
    ) {
        dao.upsert(
            SkipMarkerEntity(
                id = MarcadorDeCapitulo.idDe(itemId, episodeId),
                itemId = itemId,
                episodeId = episodeId,
                openingStartMs = openingStartMs,
                openingEndMs = openingEndMs,
                endingStartMs = endingStartMs,
                updatedAt = clock(),
                origen = MarcadorDeCapitulo.ORIGEN_MANUAL,
            ),
        )
    }
}
