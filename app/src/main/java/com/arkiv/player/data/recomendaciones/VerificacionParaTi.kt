package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ia.JsonDelModelo
import com.arkiv.player.data.ia.JsonIlegible
import com.arkiv.player.data.ia.RespuestaDeIa
import java.text.Normalizer

internal data class Candidato(val titulo: String, val anio: String, val tipo: String, val porque: String)

internal data class Verificada(
    val candidato: Candidato,
    val tmdbId: Int,
    val tipo: String,
    val titulo: String,
    val posterUrl: String,
    val ref: String,
)

/** El PRIMER resultado de TMDB para ese tipo, o null. */
internal fun interface BuscadorEnTmdb { suspend fun buscar(tipo: String, titulo: String): TmdbItem? }

internal fun interface BuscadorEnFuentes {
    suspend fun buscar(titulo: String, tipo: String, anio: String, tmdbId: Int): List<GatewayResult>
}

/** Qué resultados son ESA obra. Null = no contestó (que es distinto de "ninguno"). */
internal fun interface Arbitro {
    suspend fun cuales(titulo: String, anio: String, tipo: String, resultados: List<GatewayResult>): List<Int>?
}

/**
 * Para comparar títulos sin importar tildes, mayúsculas ni signos. Port de `normalizar_titulo`
 * (`recomendaciones/verificacion.py`): así "El Señor de los Anillos!" y "el senor  de los anillos"
 * quedan iguales. Letras de cualquier alfabeto cuentan.
 */
internal object NormalizarTitulo {
    private val MARCAS = Regex("\\p{Mn}+")
    fun de(texto: String): String {
        val sinTildes = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFKD).replace(MARCAS, "")
        val limpio = sinTildes.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")
        return limpio.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * El árbitro de matching: decide si un resultado de fuente ES la obra buscada. Port de
 * `arkiv-api/src/arkiv_api/arbitro.py`, que existe por un bug medido: "The Mandalorian" terminó
 * apuntando a un podcast. Comparar el título exacto tampoco sirve (los releases se llaman
 * `Titulo.2022.1080p-dual-lat`); un modelo comparando es lo único que cubre los dos casos.
 */
internal class ArbitroDeIa(private val ia: suspend (String) -> RespuestaDeIa) : Arbitro {

    override suspend fun cuales(titulo: String, anio: String, tipo: String, resultados: List<GatewayResult>): List<Int>? {
        val lista = resultados.take(TOPE_RESULTADOS)
        val filas = lista.mapIndexed { i, r -> fila(i, r) }.joinToString("\n")
        val r = ia("${instruccion(titulo, anio, tipo)}\n\n$filas")
        if (r !is RespuestaDeIa.Texto) return null
        return try {
            val arr = JsonDelModelo.arreglo(r.texto)
            // Un modelo que contesta índices inventados no puede sacar a nadie de la lista: solo
            // sobreviven enteros de verdad (un `true` no es un índice) dentro del rango.
            (0 until arr.length()).mapNotNull { arr.opt(it) as? Int }.filter { it in lista.indices }
        } catch (e: JsonIlegible) {
            null
        }
    }

    /** `_fila` del gateway: `"<i>. [<source>] <title>"` y, si los hay, `" (<año>, <tipo>, <calidad>)"`. */
    private fun fila(i: Int, r: GatewayResult): String {
        val detalles = listOf(r.year, r.kind, r.quality).filter { it.isNotBlank() }
        val base = "$i. [${r.source}] ${r.title}"
        return if (detalles.isEmpty()) base else "$base (${detalles.joinToString(", ")})"
    }

    private fun instruccion(titulo: String, anio: String, tipo: String): String {
        val cual = if (tipo == "tv" || tipo == "anime") "serie" else "película"
        val conAnio = if (anio.isNotBlank()) " ($anio)" else ""
        return "Busco: $titulo$conAnio ($cual). Abajo hay una lista numerada de resultados de " +
            "varias fuentes: nombres de release de torrents, ítems de archivos y entradas de " +
            "catálogo. Dime cuáles corresponden a ESA obra exacta. Una temporada o un capítulo " +
            "de la serie buscada sí corresponde; un release con el título dentro del nombre " +
            "(p. ej. 'Titulo.2022.1080p-dual-lat') sí corresponde. Un podcast, reseña, " +
            "documental sobre la obra, otra obra del mismo universo o una de nombre parecido " +
            "NO corresponde. Si busco una película, una serie del mismo nombre NO corresponde, " +
            "y al revés tampoco. Responde SOLO un arreglo JSON con los números que sí, " +
            "p. ej. [0,2]. Si ninguno corresponde, responde []."
    }

    private companion object { const val TOPE_RESULTADOS = 25 }
}

/**
 * La cascada que decide qué llega a la fila. Port de
 * `arkiv-api/src/arkiv_api/recomendaciones/verificacion.py`.
 *
 * **El ORDEN es la optimización**: cada paso es más caro que el anterior, así que el que descarta
 * más barato va primero. Consultar las fuentes de un título que TMDB ni conoce sería pagar el paso
 * caro para nada.
 */
internal class VerificacionParaTi(
    private val tmdb: BuscadorEnTmdb,
    private val fuentes: BuscadorEnFuentes,
    private val arbitro: Arbitro,
) {
    suspend fun verificar(candidatos: List<Candidato>, yaVistos: Set<String>, tope: Int = 10): List<Verificada> {
        val salida = mutableListOf<Verificada>()
        for (c in candidatos) {
            if (salida.size >= tope) break

            // 1. ¿Existe? Un título que TMDB no conoce era una alucinación. La búsqueda ya va con
            //    include_adult=false (`TmdbApi.search`), así que lo adulto no pasa.
            val (enTmdb, tipo) = tipoReal(c) ?: continue

            // 2. ¿Ya lo tienes? Por id y por los DOS títulos. Un título que normaliza a vacío no
            //    dice nada y no puede descartar.
            val porTitulo = listOf(NormalizarTitulo.de(enTmdb.title), NormalizarTitulo.de(c.titulo))
                .filter { it.isNotEmpty() }
            if ("tmdb:${enTmdb.id}" in yaVistos || porTitulo.any { it in yaVistos }) continue

            // 3. ¿Se puede reproducir? Recién acá se paga el paso caro. El año y el id de TMDB
            //    viajan con la búsqueda.
            val anio = enTmdb.year
            val resultados = fuentes.buscar(c.titulo, tipo, anio, enTmdb.id)
            if (resultados.isEmpty()) continue

            // 4. ¿Es esa obra? Rechazo total = candidato descartado; árbitro caído = el primero.
            val indices = arbitro.cuales(c.titulo, anio, tipo, resultados)
            val elegido = when {
                indices == null -> resultados.first()
                indices.isEmpty() -> continue
                else -> resultados[indices.first()]
            }
            val titulo = enTmdb.title.ifBlank { c.titulo }
            salida += Verificada(c, enTmdb.id, tipo, titulo, enTmdb.posterUrl, elegido.ref)
        }
        return salida
    }

    /**
     * `_buscar_tipo_real` del gateway: primero el tipo que propuso el modelo (el caso común, más
     * barato), el otro solo si el primero no calza EXACTO. Un calce exacto en cualquiera de los dos
     * corta; si ninguno calza, gana el primero que apareció.
     */
    private suspend fun tipoReal(c: Candidato): Pair<TmdbItem, String>? {
        val objetivo = NormalizarTitulo.de(c.titulo)
        val otro = if (c.tipo == "tv") "movie" else "tv"
        var mejor: Pair<TmdbItem, String>? = null
        for (tipo in listOf(c.tipo, otro)) {
            val primero = tmdb.buscar(tipo, c.titulo) ?: continue
            if (mejor == null) mejor = primero to tipo
            if (objetivo.isNotEmpty() && NormalizarTitulo.de(primero.title) == objetivo) return primero to tipo
        }
        return mejor
    }
}
