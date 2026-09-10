package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow

/**
 * De dónde salen los títulos que la app busca y reproduce.
 *
 * Existe para que el cableado del sub-proyecto 2A sea un cambio de constructor: las pantallas
 * dependen de esta interfaz y no de un cliente concreto del gateway, así que pasar del gateway al
 * cliente directo del portal no las toca. Hoy hay dos fuentes que la implementan, `MagisFuente` y
 * `DituFuente`, y una tercera implementación, `FuenteCompuesta`, que las junta detrás del único
 * objeto que ven las pantallas (`AppGraph.fuenteDeContenido`). Los modelos siguen llamándose
 * `Gateway*` porque renombrarlos sería churn sin
 * ninguna ganancia (son el contrato, no el transporte).
 *
 * Los errores viajan como [GatewayException]: quien llama ya los atrapa así.
 */
interface FuenteDeContenido {
    /**
     * Si este `ref` es de esta fuente. Existe desde que hay más de una implementación (`MagisFuente`,
     * `DituFuente`): cada una sabe leer los suyos —incluidos los viejos del gateway, que no llevan
     * prefijo visible— sin que quien reparte tenga que adivinar por fuera.
     */
    fun reconoce(ref: String): Boolean

    fun search(ctx: GatewaySearchQuery): Flow<SearchEvent>

    suspend fun resolve(ref: String): GatewayPlayable

    /**
     * Capítulos de una temporada y, si se pudo identificar la serie contra TMDB, su bloque
     * [GatewaySerie] (null si no).
     */
    suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?>

    suspend fun episodes(ref: String): List<GatewayEpisode> = episodesConSerie(ref).first
}
