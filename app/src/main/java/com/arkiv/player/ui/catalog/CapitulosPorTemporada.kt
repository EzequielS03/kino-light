package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayEpisode

/**
 * Cómo se listan los capítulos en la ventana de una temporada (celular: `MagisSeasonDialog`; TV:
 * `TvMagisSeasonContent`) cuando la lista trae varias temporadas.
 *
 * Existe por Caracol: un `GROUP_OF_BUNDLES` llega como UNA lista con todas sus temporadas aplanadas
 * (`DituEpisodes`), y cada temporada puede traer su propio capítulo 1. Si además Caracol no manda
 * `episodeTitle`, el título cae a "Episodio N" (`DituEpisodes`) y el 1 de la T1 queda idéntico al 1
 * de la T2. Con varias temporadas, cada fila dice la suya y la lista va por temporada y después por
 * número.
 *
 * Con `season` en null —Magis nunca la manda: `MagisFuente` no la pasa— o con una sola temporada,
 * todo queda como estaba: el orden en que llegó y el número pelado.
 */
object CapitulosPorTemporada {

    /** Si la lista trae más de una temporada distinta. */
    fun variasTemporadas(capitulos: List<GatewayEpisode>): Boolean =
        capitulos.mapNotNull { it.season }.distinct().size > 1

    /** En el orden en que se muestran: el mismo en que llegaron, salvo con varias temporadas. */
    fun ordenar(capitulos: List<GatewayEpisode>): List<GatewayEpisode> =
        if (!variasTemporadas(capitulos)) capitulos
        else capitulos.sortedWith(compareBy({ it.season ?: 0 }, { it.number }))

    /**
     * "T2 · E1" cuando hay [variasTemporadas] y el capítulo trae la suya; si no, [sinTemporada]
     * seguido del número: el celular pinta el número pelado (`""`) y el TV "E" más el número.
     */
    fun etiqueta(capitulo: GatewayEpisode, variasTemporadas: Boolean, sinTemporada: String = ""): String {
        val temporada = capitulo.season
        return if (variasTemporadas && temporada != null) "T$temporada · E${capitulo.number}"
        else "$sinTemporada${capitulo.number}"
    }
}
