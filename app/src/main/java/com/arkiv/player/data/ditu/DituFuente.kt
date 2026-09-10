package com.arkiv.player.data.ditu

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.FuenteDeContenido
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySerie
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Caracol Streaming hablando el mismo contrato que ya habla Magis.
 *
 * Es el puerto de `DituAdapter` (`arkiv-api/src/arkiv_api/adapters/ditu/adapter.py`): lo que el
 * gateway hacía entre la API de Caracol y la app —armar los resultados, aplanar las temporadas,
 * cruzar con TMDB— vive acá.
 *
 * TMDB se usa SOLO para el `tmdbId` y el título canónico. Las imágenes las pone Caracol, que las
 * tiene siempre; las de TMDB entran únicamente si Caracol no trajo ninguna.
 */
internal class DituFuente(
    private val catalogo: DituCatalogo,
    private val episodios: DituEpisodios,
    private val resolucion: DituResolve,
    private val tmdb: TmdbApi? = null,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) : FuenteDeContenido {

    override fun reconoce(ref: String): Boolean = DituRef.decodificar(ref) != null

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = ahoraMs()
        emit(SearchEvent.SourceStart(FUENTE))
        val items = runCatching { catalogo.buscar(ctx.q) }.getOrElse { e ->
            emit(SearchEvent.SourceError(FUENTE, e.message ?: "error de Caracol", ahoraMs() - t0, 0))
            emit(SearchEvent.Done(ahoraMs() - t0))
            return@flow
        }
        for (item in items) {
            emit(SearchEvent.ResultEvent(FUENTE, resultadoDe(item)))
        }
        emit(SearchEvent.SourceDone(FUENTE, items.size, ahoraMs() - t0))
        emit(SearchEvent.Done(ahoraMs() - t0))
    }.flowOn(Dispatchers.IO)

    override suspend fun resolve(ref: String): GatewayPlayable {
        val propio = DituRef.decodificar(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        return runCatching { resolucion.vod(propio) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo reproducir en Caracol", it) }
    }

    /** Resolver un canal en vivo. No pasa por [resolve] porque un canal no tiene `ref`: lo que lo
     *  identifica es el par (channelId, assetId) que vino con la lista. */
    suspend fun resolverCanal(canal: DituCanal): GatewayPlayable =
        runCatching { resolucion.vivo(canal) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo abrir el canal", it) }

    suspend fun canales(): List<DituCanal> =
        runCatching { catalogo.canales() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron listar los canales", it) }

    /**
     * El catálogo entero, guardado [VIGENCIA_DEL_CATALOGO_MS]: son unos 330 títulos en una sola
     * llamada (ver [DituCatalogo]), y pedirlos cada vez que se entra a la sección es tiempo regalado.
     *
     * [forzar] se salta lo guardado: es el botón de recargar, para cuando Caracol agrega algo y no se
     * quiere esperar a que venza. Si la llamada falla, lo guardado se queda como estaba.
     */
    suspend fun catalogoCompleto(forzar: Boolean = false): List<DituItem> {
        val guardado = catalogoGuardado
        if (!forzar && guardado != null && ahoraMs() - guardado.traidoEnMs < VIGENCIA_DEL_CATALOGO_MS) {
            return guardado.titulos
        }
        val titulos = runCatching { catalogo.catalogo() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo cargar el catálogo", it) }
        catalogoGuardado = CatalogoGuardado(titulos, ahoraMs())
        return titulos
    }

    /** Un solo valor, no un caché por clave: el catálogo de Caracol es uno. */
    private class CatalogoGuardado(val titulos: List<DituItem>, val traidoEnMs: Long)

    @Volatile
    private var catalogoGuardado: CatalogoGuardado? = null

    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
        val propio = DituRef.decodificar(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        val temporada = runCatching { episodios.de(propio) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron leer los capítulos", it) }

        val eps = temporada.episodios.map {
            // La temporada va por capítulo: en un grupo, la de `serie` es una sola para todas.
            GatewayEpisode(number = it.numero, title = it.titulo, ref = it.ref(), season = it.temporada)
        }
        if (temporada.tituloSerie.isBlank() && temporada.posterUrl.isBlank()) return eps to null

        var serie = GatewaySerie(
            imdbId = "",
            tmdbId = 0,
            seasonNumber = temporada.temporada,
            titulo = temporada.tituloSerie,
            posterUrl = temporada.posterUrl,
            backdropUrl = temporada.fondoUrl,
        )
        // TMDB solo aporta identidad, no imágenes: las de Caracol son las correctas para su propio
        // catálogo. Que falle no puede costar los capítulos, que ya están listos.
        //
        // `val tmdb = tmdb` (sombrear la propiedad en una local): el brief original llamaba
        // `tmdb.search(...)` dentro del lambda de `runCatching` confiando en el smart-cast del
        // `tmdb != null` de más arriba, y no compila — Kotlin no aplica smart-cast a una propiedad
        // de clase capturada dentro de un lambda, solo a variables locales.
        val tmdb = tmdb
        if (tmdb != null && temporada.tituloSerie.isNotBlank()) {
            val hit = runCatching { tmdb.search("tv", temporada.tituloSerie).firstOrNull() }.getOrNull()
            if (hit != null) {
                serie = serie.copy(
                    tmdbId = hit.id,
                    titulo = hit.title.ifBlank { serie.titulo },
                    posterUrl = serie.posterUrl.ifBlank { hit.posterUrl },
                    backdropUrl = serie.backdropUrl.ifBlank { hit.backdropUrl },
                )
            }
        }
        return eps to serie
    }

    internal companion object {
        const val FUENTE = "ditu"

        /**
         * Un título de Caracol como lo ve el resto de la app. Lo usan la búsqueda ([search]) y la
         * sección de Caracol del televisor (`TvCaracolScreen`): así un título abierto desde
         * cualquiera de las dos llega igual a `SearchPlayback` y a la lista de capítulos.
         */
        fun resultadoDe(item: DituItem) = GatewayResult(
            source = FUENTE,
            title = item.titulo,
            ref = item.ref(),
            kind = if (item.esPelicula) "movie" else "series",
            year = item.anio,
            extra = mapOf("poster" to item.posterUrl, "content_type" to item.contentType),
        )

        /** Cuánto vale el catálogo guardado por [catalogoCompleto]: 6 h. */
        private const val VIGENCIA_DEL_CATALOGO_MS = 6 * 60 * 60 * 1000L
    }
}
