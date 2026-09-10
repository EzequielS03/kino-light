package com.arkiv.player.data.model

/**
 * Si una obra es serie (`"tv"`) o película (`"movie"`) para TMDB.
 *
 * **Equivocarse acá no da "sin datos", da datos de OTRA OBRA**: un id de TMDB solo significa algo
 * dentro de su catálogo. Medido en producción: se pidió `movie:82452` para Avatar, y en TMDB
 * `tv:82452` es "Avatar: La leyenda de Aang" mientras que `movie:82452` es "Savage Water", una
 * película de rafting de 1979.
 *
 * Por eso se miran todas las señales, de la más confiable a la más débil:
 *  1. `tipoDelItem`: el tipo que trajo la fuente (`ItemEntity.tipo`).
 *  2. `categoryOverride`, que es lo que la app ya usa para decidir si algo es serie y puede venir
 *     corregido a mano por la persona.
 *  3. Que ESTE capítulo traiga número.
 *
 * Vivía en `TriviaDelPlayer.tipoDe` hasta `e161b231`; volvió a la capa de datos porque ahora la
 * usan el dato curioso y "Para ti".
 */
object TipoDeObra {
    fun de(tipoDelItem: String?, categoryOverride: String?, episodio: Int?): String = when {
        tipoDelItem == "tv" || tipoDelItem == "movie" -> tipoDelItem
        categoryOverride == "series" -> "tv"
        categoryOverride == "movie" -> "movie"
        episodio != null -> "tv"
        else -> "movie"
    }
}
