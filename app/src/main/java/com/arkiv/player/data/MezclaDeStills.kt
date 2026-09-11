package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeStillEntity

/**
 * Cómo se combina la fila de `episode_still` que YA estaba guardada con la que se acaba de resolver.
 *
 * It exists because two different sources write to that table with different data: Magis fills it
 * when saving the season (`MagisEntities.stillsDeTemporada`, with what the gateway matched
 * against TMDB) and `ArkivRepository.ensureEpisodeStills` fills it for everything else (Ditu
 * today, and legacy torrent/web/archive rows) by asking TMDB.
 * `EpisodeStillDao.upsertAll` es un REPLACE, así que la segunda escritura pisa la primera **fila
 * entera**: sin esta mezcla, un timeout de TMDB al abrir el detalle dejaba `stillUrl`, `title` y
 * `overview` en null encima de lo que Magis ya había guardado bien, y como la fila igual quedaba
 * escrita (marca "ya preguntado"), esa serie se quedaba sin imágenes para siempre.
 *
 * La regla es campo por campo y no "fila nueva o fila vieja": las dos fuentes se complementan. El
 * gateway solo le pide a TMDB es-MX, así que puede traer still y nombre pero no sinopsis; TMDB
 * consultado desde acá puede traer la sinopsis del respaldo en inglés. Con la regla por fila, la
 * segunda escritura borraría la mitad buena de la primera.
 *
 * Puro/JVM (sin Room ni red) para poder testearse, igual que [MagisEntities].
 */
object MezclaDeStills {

    /**
     * Lo nuevo manda **si trae algo**; si viene vacío o null, gana lo que ya estaba guardado.
     *
     * [fetchedAt] siempre se queda con el de [nueva]: la fecha dice "cuándo se preguntó por última
     * vez", no "de cuándo es el dato", y se acaba de preguntar.
     */
    fun mezclar(previa: EpisodeStillEntity?, nueva: EpisodeStillEntity): EpisodeStillEntity {
        if (previa == null) return nueva
        return nueva.copy(
            stillUrl = nueva.stillUrl?.takeIf { it.isNotBlank() } ?: previa.stillUrl,
            title = nueva.title?.takeIf { it.isNotBlank() } ?: previa.title,
            overview = nueva.overview?.takeIf { it.isNotBlank() } ?: previa.overview,
        )
    }

    /**
     * [mezclar] para un lote entero: cada fila nueva contra la que hubiera con su mismo `episodeId`.
     *
     * Lo usan los dos caminos de escritura de la tabla, que es justo el punto: el de Magis
     * (`ArkivRepository.addMagisSource`/`addMagisSeason`, vía `MagisEntities.stillsDeTemporada`)
     * también pasa por acá. Sin eso, guardar una temporada le devolvía el REPLACE crudo a la tabla:
     * `stillsDeTemporada` deja en null todo campo que el gateway no resolvió, así que si el gateway
     * traía solo el still, el nombre y la sinopsis que `ensureEpisodeStills` había completado antes
     * se perdían en silencio — y como la fila seguía existiendo, su corte temprano impedía volver a
     * llenarlos.
     *
     * [previas] viene como mapa (no lista) porque el llamador ya la tiene indexada por `episodeId`,
     * que es la PK de la tabla.
     */
    fun mezclarTodas(
        previas: Map<String, EpisodeStillEntity>,
        nuevas: List<EpisodeStillEntity>,
    ): List<EpisodeStillEntity> = nuevas.map { mezclar(previas[it.episodeId], it) }
}
