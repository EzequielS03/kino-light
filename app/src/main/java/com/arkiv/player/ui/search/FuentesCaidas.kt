package com.arkiv.player.ui.search

import com.arkiv.player.data.ditu.FalloDeCaracol

/**
 * Qué pasó con cada fuente en la última búsqueda de fuentes: cuáles respondieron y cuáles se
 * cayeron, con su error.
 *
 * Existe porque el spec pide que el error de una fuente se vea sin tapar lo que las otras sí
 * trajeron. Antes el `SourceError` solo iba al log: con Caracol caído, su pestaña decía "Sin
 * resultados en Caracol." como si no hubiera nada, y con todas caídas la pantalla aconsejaba probar
 * con otra temporada.
 *
 * Las fuentes van con el nombre con que viajan en los eventos de búsqueda (`"magis"`, `"ditu"`), el
 * mismo con que `GatewayResult.toPlaySource` las reparte.
 */
data class EstadoDeLasFuentes(
    val respondieron: Set<String> = emptySet(),
    /** Fuente → mensaje de su error, en el orden en que fallaron. */
    val caidas: Map<String, String> = emptyMap(),
    /** Fuente → la excepción de su error, para las que la mandaron (ver `SearchEvent.SourceError.causa`). */
    val causas: Map<String, Throwable> = emptyMap(),
) {
    fun conRespuesta(fuente: String) = copy(respondieron = respondieron + fuente)
    fun conCaida(fuente: String, error: String, causa: Throwable? = null) = copy(
        caidas = caidas + (fuente to error),
        causas = if (causa == null) causas else causas + (fuente to causa),
    )
}

/** La pestaña de una fuente por su nombre en los eventos, o null si no se sabe cuál es. */
private fun tabDeFuente(fuente: String): SourceTab? = when (fuente) {
    "magis" -> SourceTab.MAGIS
    "ditu" -> SourceTab.CARACOL
    else -> null
}

/**
 * Cómo se nombra una fuente en los avisos. "Una fuente" cubre el nombre que pone `FuenteCompuesta`
 * cuando una fuente se cae antes de anunciarse.
 */
private fun nombreDeFuente(fuente: String): String = tabDeFuente(fuente)?.label ?: "Una fuente"

private fun seCayo(tab: SourceTab, estado: EstadoDeLasFuentes): Boolean =
    estado.caidas.keys.any { tabDeFuente(it) == tab }

/**
 * Una línea por cada fuente caída que corresponde a [tab] ("Todo" las muestra todas). Van arriba de
 * la lista, haya o no resultados: si Caracol se cae y Magis responde, se ven los resultados de Magis
 * y la línea de Caracol. Sin errores la lista es vacía y la pantalla queda igual que antes.
 *
 * La línea de Caracol la escribe [FalloDeCaracol], en palabras de persona. La de Magis y la de una
 * fuente sin nombre siguen como antes: el nombre y el texto del error.
 */
fun avisosDeFuentesCaidas(estado: EstadoDeLasFuentes, tab: SourceTab): List<String> =
    estado.caidas
        .filter { (fuente, _) -> tab == SourceTab.TODO || tabDeFuente(fuente) == tab }
        .map { (fuente, error) ->
            if (tabDeFuente(fuente) == SourceTab.CARACOL) {
                FalloDeCaracol.enLaBusqueda(estado.causas[fuente], error)
            } else {
                "${nombreDeFuente(fuente)} no respondió: $error"
            }
        }

/** Lo que dice la pantalla cuando la búsqueda terminó sin ningún resultado. */
fun textoSinFuentes(estado: EstadoDeLasFuentes): String =
    if (estado.caidas.isNotEmpty() && estado.respondieron.isEmpty()) SIN_RESPUESTA else SIN_FUENTES

/**
 * Lo que dice una pestaña de origen sin filas, o null si su fuente se cayó: eso ya lo explica la
 * línea de [avisosDeFuentesCaidas], y "Buscando…" o "Sin resultados" la contradirían.
 */
fun textoPestanaVacia(tab: SourceTab, buscando: Boolean, estado: EstadoDeLasFuentes): String? = when {
    seCayo(tab, estado) -> null
    buscando -> "Buscando en ${tab.label}…"
    else -> "Sin resultados en ${tab.label}."
}

/** Lo que dice, en "Todo" del celular, la sección de una fuente que no trajo nada. */
fun textoSeccionVacia(fuente: SourceTab, estado: EstadoDeLasFuentes): String =
    if (seCayo(fuente, estado)) "No respondió" else "Sin resultados"

/** El texto de siempre: la búsqueda llegó a las fuentes y ninguna tenía nada. */
internal const val SIN_FUENTES =
    "No se encontraron fuentes. Vuelve atrás y prueba con otra temporada/capítulo, o sin especificar ninguno."

/** Ninguna fuente contestó: el consejo de otra temporada no sirve cuando el problema es la conexión. */
internal const val SIN_RESPUESTA =
    "Ninguna fuente respondió. Revisa tu conexión a internet y vuelve a intentar."
