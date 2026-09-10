package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Varias fuentes detrás de una sola. Existe para que agregar Caracol —y después RCN— no obligue a
 * tocar ninguna pantalla: `AppGraph.fuenteDeContenido` sigue siendo UN objeto.
 *
 * Dos reglas gobiernan la búsqueda:
 *
 * - **Una fuente caída no vacía la búsqueda de las otras.** Cada fuente ya emite su propio
 *   `SourceError` y termina; acá simplemente no se deja que eso corte el flujo común.
 * - **Hay un solo `Done`, al final.** Los `Done` de cada fuente se descartan y se emite uno propio
 *   cuando todas terminaron: si pasaran los de adentro, la pantalla creería que la búsqueda
 *   terminó cuando apenas terminó la primera fuente.
 *
 * Para resolver y para listar capítulos no hay mezcla: el `ref` decide. Cada fuente sabe leer los
 * suyos (`reconoce`), incluidos los viejos del gateway, que no llevan un prefijo visible.
 */
internal class FuenteCompuesta(private val fuentes: List<FuenteDeContenido>) : FuenteDeContenido {

    override fun reconoce(ref: String): Boolean = fuentes.any { it.reconoce(ref) }

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = System.currentTimeMillis()
        // `merge` corre las fuentes en paralelo y emite lo de cada una a medida que llega, que es
        // lo que la pantalla espera: pinta resultados mientras la otra fuente sigue buscando.
        // Cada fuente se protege por separado: si lanza, se emite un SourceError en lugar de
        // propagar la excepción que mataría toda la búsqueda.
        val mezclado = fuentes
            .map { fuente ->
                flow {
                    var nombreFuente = "desconocida"
                    var countResultados = 0
                    try {
                        fuente.search(ctx)
                            .filterNot { e -> e is SearchEvent.Done }
                            .collect { evento ->
                                if (evento is SearchEvent.SourceStart) {
                                    nombreFuente = evento.source
                                }
                                if (evento is SearchEvent.ResultEvent) {
                                    countResultados++
                                }
                                emit(evento)
                            }
                    } catch (e: Exception) {
                        // El catch de Flow respeta CancellationException: si es eso, se propaga.
                        emit(SearchEvent.SourceError(nombreFuente, e.message ?: "Error desconocido", 1, countResultados))
                    }
                }
            }
            .merge()
        emitAll(mezclado)
        emit(SearchEvent.Done(System.currentTimeMillis() - t0))
    }

    override suspend fun resolve(ref: String): GatewayPlayable = para(ref).resolve(ref)

    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> =
        para(ref).episodesConSerie(ref)

    private fun para(ref: String): FuenteDeContenido =
        fuentes.firstOrNull { it.reconoce(ref) }
            ?: throw GatewayException("No hay ninguna fuente que sepa abrir esto")
}
