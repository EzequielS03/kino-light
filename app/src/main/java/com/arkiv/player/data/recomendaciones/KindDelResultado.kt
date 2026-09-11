package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.ditu.DituRef
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef

/**
 * El `kind` real de un resultado ("movie" o "tv"), leído de su propio `ref` -no del tipo que se
 * BUSCÓ-. Existe por un bug medido: `MagisFuente` arma cada resultado con `kind = ctx.type`, así
 * que al buscar una serie todas las películas del pool de Magis le llegan al árbitro etiquetadas
 * "tv", y la regla del prompt "si busco una serie, una película NO corresponde" nunca se puede
 * aplicar. Acá se corrige antes de que el árbitro vea la lista.
 *
 * `null` cuando el ref no se entiende (no es de Magis ni de Caracol): en ese caso quien llama deja
 * el `kind` como venía.
 */
internal fun kindRealDelRef(ref: String): String? {
    MagisRef.decodificar(ref)?.let { return if (it.esSerie) "tv" else "movie" }
    DituRef.decodificar(ref)?.let { return if (it.esSerie) "tv" else "movie" }
    return null
}

/** [resultado] con su `kind` corregido según [kindRealDelRef], o tal cual si el ref no se entiende. */
internal fun conKindReal(resultado: GatewayResult): GatewayResult =
    kindRealDelRef(resultado.ref)?.let { resultado.copy(kind = it) } ?: resultado
