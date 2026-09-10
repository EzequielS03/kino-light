package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Varias fuentes detrás de una sola. Existe para que agregar Caracol —y después RCN— no obligue a
 * tocar ninguna pantalla: `AppGraph.fuenteDeContenido` sigue siendo UN objeto.
 *
 * Dos reglas gobiernan la búsqueda:
 *
 * - **Una fuente caída no vacía la búsqueda de las otras.** Cada fuente se protege por separado
 *   con `.catch` de Flow: si lanza una excepción, se emite un `SourceError` en lugar de propagar.
 *   El `.catch` no atrapa `CancellationException`: si la corrutina es cancelada, la excepción se
 *   propaga. Eso lo distingue de `try/catch` común, que sí la atraparía.
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
        // Cada fuente se protege con `.catch` de Flow: si lanza una excepción, se emite un
        // SourceError. El `.catch` no atrapa CancellationException (si la corrutina es cancelada,
        // la excepción se propaga). El `try/catch` común sí la atraparía, solo que el emit posterior
        // fallaría silenciosamente.
        val mezclado = fuentes
            .map { fuente ->
                flow {
                    var nombreFuente = "desconocida"
                    var countResultados = 0
                    val t0Fuente = System.currentTimeMillis()
                    emitAll(
                        fuente.search(ctx)
                            .filterNot { it is SearchEvent.Done }
                            .onEach { evento ->
                                if (evento is SearchEvent.SourceStart) {
                                    nombreFuente = evento.source
                                }
                                if (evento is SearchEvent.ResultEvent) {
                                    countResultados++
                                }
                            }
                            .catch { e ->
                                emit(SearchEvent.SourceError(nombreFuente, e.message ?: "Error desconocido",
                                    System.currentTimeMillis() - t0Fuente, countResultados))
                            }
                    )
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
