package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeStillEntity

/**
 * Cómo se combina la fila de `episode_still` que YA estaba guardada con la que se acaba de resolver.
 *
 * Existe porque a esa tabla escriben dos fuentes distintas y con datos distintos: Magis la llena al
 * guardar la temporada (`MagisEntities.stillsDeTemporada`, con lo que el gateway cruzó contra TMDB)
 * y `ArkivRepository.ensureEpisodeStills` la llena para torrent/web/archive preguntándole a TMDB.
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
}
