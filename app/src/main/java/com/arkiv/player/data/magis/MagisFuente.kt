package com.arkiv.player.data.magis

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.FuenteDeContenido
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySerie
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * La app hablándole al portal de Magis directo, con el mismo contrato que antes le pedía al
 * gateway. Es el puerto de `MagisAdapter` (`arkiv-api/src/arkiv_api/adapters/magis/adapter.py`):
 * acá vive lo que el servidor hacía entre el portal y la app — rankear la búsqueda, armar los
 * capítulos y cruzarlos con TMDB.
 *
 * Lo que el gateway guardaba en Redis se guarda en memoria: se pierde al morir el proceso, que para
 * un catálogo está bien y ahorra las llamadas con ritmo mínimo del portal mientras la app vive.
 */
internal class MagisFuente(
    private val catalogo: MagisCatalog,
    private val resolucion: MagisResolve,
    private val tmdb: TmdbApi,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) : FuenteDeContenido {

    override fun reconoce(ref: String): Boolean = MagisRef.decodificar(ref) != null

    private val candado = Mutex()
    private val busquedas = CacheConVencimiento<String, List<JSONObject>>(TTL_MS, tope = 32)
    private val capitulos = CacheConVencimiento<String, CapitulosDelPortal>(TTL_MS, tope = 16)

    // --- búsqueda -------------------------------------------------------------

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = ahoraMs()
        emit(SearchEvent.SourceStart(FUENTE))
        val items = runCatching { itemsOrdenados(ctx) }.getOrElse { e ->
            emit(SearchEvent.SourceError(FUENTE, e.message ?: "error de magis", ahoraMs() - t0, 0))
            emit(SearchEvent.Done(ahoraMs() - t0))
            return@flow
        }
        var cuantos = 0
        for (item in items) {
            val resultado = resultadoDe(item, ctx) ?: continue
            emit(SearchEvent.ResultEvent(FUENTE, resultado))
            cuantos++
        }
        emit(SearchEvent.SourceDone(FUENTE, cuantos, ahoraMs() - t0))
        emit(SearchEvent.Done(ahoraMs() - t0))
    }.flowOn(Dispatchers.IO)

    private suspend fun itemsOrdenados(ctx: GatewaySearchQuery): List<JSONObject> {
        val consulta = consultaDePortal(ctx.q)
        // La clave es lo que se le pide AL PORTAL, no el `q` completo: así dos títulos de la misma
        // familia comparten pool en vez de gastar una llamada cada uno.
        val pool = candado.withLock { busquedas[consulta.lowercase()] }
            ?: catalogo.search(consulta).let { r ->
                val datos = r.dato() ?: throw GatewayException(explicar("búsqueda", r))
                itemsDeBusqueda(datos).also {
                    candado.withLock { busquedas[consulta.lowercase()] = it }
                }
            }

        // Se ordena DESPUÉS del caché y contra el `q` completo: el pool es de la familia, pero cada
        // pedido quiere su título arriba.
        var items = ordenarPorParecido(pool, formasDelTitulo(ctx))

        val esSerie = ctx.type == "tv" || ctx.type == "anime"
        if (esSerie && ctx.season > 0) {
            // Con una temporada pedida se filtra a esa; si NINGUNA coincide se muestran todas, que
            // es mejor que dejar la pestaña vacía por un nombre con un formato inesperado.
            val coinciden = items.filter {
                it.optString("programType") !in MagisRef.SERIES ||
                    temporadaDeNombre(tituloDeItem(it)) == ctx.season
            }
            if (coinciden.isNotEmpty()) items = coinciden
        }
        return ordenarTemporadas(items)
    }

    /**
     * Las formas conocidas de lo que se pidió: el título tal cual y, si TMDB lo sabe, el ORIGINAL.
     * El portal guarda mucho contenido internacional solo con su título en inglés, así que sin el
     * original no hay forma de reconocerlo desde una búsqueda en español. Es una AYUDA, no un
     * requisito: si TMDB no está o se cae, se ordena con lo que hay.
     */
    private suspend fun formasDelTitulo(ctx: GatewaySearchQuery): List<String> {
        if (ctx.tmdbId <= 0) return listOf(ctx.q)
        val tipo = if (ctx.type == "movie") "movie" else "tv"
        val original = runCatching { tmdb.detail(tipo, ctx.tmdbId)?.originalTitle }.getOrNull()
        return if (original.isNullOrBlank() || original == ctx.q) listOf(ctx.q)
        else listOf(ctx.q, original)
    }

    private fun resultadoDe(item: JSONObject, ctx: GatewaySearchQuery): GatewayResult? {
        val contentId = item.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val tipoPrograma = item.optString("programType").ifBlank { "movie" }
        val titulo = tituloDeItem(item)
        val temporada =
            if (tipoPrograma in MagisRef.SERIES) temporadaDeNombre(titulo) else ctx.season
        val cuantosCapitulos = item.opt("volumnCount")?.toString()?.toIntOrNull()
            ?: item.opt("updateCount")?.toString()?.toIntOrNull()
            ?: 0
        return GatewayResult(
            source = FUENTE,
            title = titulo.ifBlank { contentId },
            ref = MagisRef(contentId, tipoPrograma, ctx.episode).codificar(),
            kind = ctx.type,
            year = anioDeItem(item),
            season = temporada,
            episode = ctx.episode,
            extra = mapOf(
                "content_id" to contentId,
                "program_type" to tipoPrograma,
                "episode_count" to cuantosCapitulos.toString(),
            ) + imagenesDeItem(item),
        )
    }

    // --- reproducción ---------------------------------------------------------

    override suspend fun resolve(ref: String): GatewayPlayable {
        val magis = MagisRef.decodificar(ref)
            ?: throw GatewayException("ese ref no es de magis: no se puede reproducir")

        // El contentId de una serie NO es reproducible (`startPlayVOD` sobre él devuelve
        // `节目不存在`): hay que listar los capítulos y reproducir uno.
        val capitulo = if (magis.esSerie) capituloDe(magis) else null
        val r = resolucion.resolveVod(
            contentId = capitulo?.optString("contentId")?.takeIf { it.isNotBlank() } ?: magis.contentId,
            seriesContentId = if (capitulo != null) magis.contentId else null,
        )
        val p = r.dato() ?: throw GatewayException(explicar("reproducción", r))
        return GatewayPlayable(
            kind = FUENTE,
            url = p.url,
            headers = p.headers,
            mime = p.mime,
            // De un capítulo manda la duración que declara la lista (ya está en memoria): el portal
            // la manda vacía para casi todas las series, y ahí se va en 0 y la completa el
            // reproductor demuxeando.
            durationMs = if (capitulo != null) {
                duracionMsDelPortal(capitulo.opt("duration"))
            } else {
                p.durationMs
            },
            videoCodec = p.videoCodec,
            container = p.container,
            subtitles = p.subtitulos.map { GatewaySubtitle(it.lang, it.url, it.formato) },
        )
    }

    /** El capítulo pedido, crudo como lo da el portal (de ahí sale su `contentId` y su duración). */
    private suspend fun capituloDe(magis: MagisRef): JSONObject {
        val items = capitulosDelPortal(magis.contentId).items
        if (items.isEmpty()) throw GatewayException("la serie ${magis.contentId} vino sin capítulos")
        if (magis.episodio <= 0) return items.first()
        return items.firstOrNull {
            it.opt("seriesNumber")?.toString()?.trim() == magis.episodio.toString()
        } ?: throw GatewayException("la serie no tiene el capítulo ${magis.episodio}")
    }

    // --- capítulos ------------------------------------------------------------

    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
        val magis = MagisRef.decodificar(ref)
            ?: throw GatewayException("ese ref no es de magis: no se pueden listar capítulos")
        val crudos = capitulosDelPortal(magis.contentId)
        val (extra, serieTmdb) = enriquecer(crudos)

        val episodios = crudos.items.mapNotNull { ep ->
            val numero = ep.opt("seriesNumber")?.toString()?.toIntOrNull() ?: 0
            val deTmdb = extra[numero]
            GatewayEpisode(
                number = numero,
                title = ep.optString("name").ifBlank { "Capítulo $numero" },
                // El ref apunta a la SERIE más el número: quien reproduzca vuelve a buscar el
                // capítulo en la lista, que a esa altura ya está en memoria.
                ref = MagisRef(magis.contentId, "teleplay", numero).codificar(),
                still = deTmdb?.still,
                tmdbTitle = deTmdb?.titulo,
                overview = deTmdb?.sinopsis,
            )
        }

        // El bloque `series` viaja SIEMPRE que el portal haya dado un imdb, aunque el
        // enriquecimiento no haya salido: con el imdb la app puede resolver la serie por su cuenta.
        val serie = if (IMDB.matches(crudos.imdb)) {
            GatewaySerie(
                imdbId = crudos.imdb,
                tmdbId = serieTmdb?.tmdbId ?: 0,
                seasonNumber = crudos.temporada ?: 0,
                // El nombre canónico va solo si TMDB lo dio: en blanco la biblioteca adoptaría un
                // nombre vacío y la tarjeta quedaría sin texto.
                titulo = serieTmdb?.titulo.orEmpty(),
                posterUrl = serieTmdb?.posterUrl.orEmpty(),
                backdropUrl = serieTmdb?.backdropUrl.orEmpty(),
            )
        } else {
            null
        }
        return episodios to serie
    }

    /** Lo que el detalle de una serie da sobre su temporada. */
    private data class CapitulosDelPortal(
        val items: List<JSONObject>,
        /** `keyWords` del detalle ES el id de IMDb de la serie. */
        val imdb: String,
        /** `null` = no se sabe qué temporada es (distinto de "es la 1"). */
        val temporada: Int?,
        /** Los capítulos que el portal DICE que tiene la temporada (puede ser más que los publicados). */
        val declarados: Int?,
    )

    private suspend fun capitulosDelPortal(serieId: String): CapitulosDelPortal {
        candado.withLock { capitulos[serieId] }?.let { return it }
        val r = catalogo.detail(serieId, tipo = "0")
        val datos = r.dato()?.optJSONObject("assetData")
            ?: throw GatewayException(explicar("capítulos", r))

        val items = mutableListOf<JSONObject>()
        datos.optJSONArray("simpleProgramList")?.forEachObjeto { items.add(it) }

        val temporadas = datos.optJSONArray("sameSeasonSeriesList")
        var numero: Int? = null
        temporadas?.forEachObjeto { t ->
            if (t.optString("contentId") == serieId) {
                numero = t.opt("seasonNumber")?.toString()?.toIntOrNull()
            }
        }
        // Una serie de temporada ÚNICA no aparece en su propia lista: el portal manda
        // `sameSeasonSeriesList` vacía. Eso no es "no se sabe qué temporada es", es "es la 1", y
        // leerlo como desconocido apagaba el enriquecimiento ENTERO con el imdb ahí al lado (medido
        // en Dragon Ball: keyWords=tt0088509, 153 capítulos, sameSeasonSeriesList=[]).
        //
        // Solo cuando la lista viene VACÍA: si trae temporadas y la nuestra no está, eso sí es no
        // saber, y adivinar 1 enriquecería con los capítulos de otra temporada.
        if (numero == null && (temporadas == null || temporadas.length() == 0)) numero = 1

        val salida = CapitulosDelPortal(
            items = items,
            imdb = datos.optString("keyWords"),
            temporada = numero,
            declarados = datos.opt("volumnCount")?.toString()?.toIntOrNull(),
        )
        // Solo se guarda si hay capítulos: cachear una lista vacía por un fallo transitorio dejaría
        // la serie sin capítulos durante horas.
        if (items.isNotEmpty()) candado.withLock { capitulos[serieId] = salida }
        return salida
    }

    private data class DeTmdb(val still: String?, val titulo: String?, val sinopsis: String?)

    /**
     * Imagen, nombre y sinopsis de cada capítulo según TMDB. El portal no los tiene (su
     * `posterList` por capítulo llega siempre vacío) pero publica el id de IMDb de la serie, y con
     * eso el match es exacto.
     *
     * Best-effort de punta a punta: cualquier fallo devuelve vacío en vez de tumbar el listado, que
     * es la llamada por la que se reproduce.
     */
    private suspend fun enriquecer(
        crudos: CapitulosDelPortal,
    ): Pair<Map<Int, DeTmdb>, TmdbSerieParaMagis?> {
        val temporada = crudos.temporada
        if (!IMDB.matches(crudos.imdb) || temporada == null) return emptyMap<Int, DeTmdb>() to null
        return runCatching {
            val serie = tmdb.seriePorImdb(crudos.imdb)
                ?: return@runCatching emptyMap<Int, DeTmdb>() to null
            val deTmdb = tmdb.seasonEpisodes(serie.tmdbId, temporada)
                ?: return@runCatching emptyMap<Int, DeTmdb>() to serie.paraMagis()

            // Guard de numeración: se compara el total que el portal DECLARA para la temporada
            // contra el que tiene esa temporada en TMDB, y solo se enriquece si coinciden.
            //
            //  - One Piece "Temp.1" declara 8 capítulos y la temporada 1 real de TMDB tiene 61: no
            //    coinciden, no se enriquece. El portal partió la serie distinto, y cruzar por número
            //    pondría stills que no corresponden — peor que ninguno, porque no se nota.
            //  - Una temporada en emisión declara 20 y el portal publicó 8: 20 SÍ coincide con TMDB,
            //    así que esos 8 se enriquecen. Comparando publicados (8 contra 20) el guard
            //    bloquearía justo lo que se acaba de estrenar, que es lo que más se mira.
            //
            // Esta cuenta es la que alguien va a querer "simplificar" a comparar cantidades reales;
            // no se nota que está mal hasta que aparece un still que no es.
            val esperados = crudos.declarados?.takeIf { it > 0 } ?: crudos.items.size
            if (esperados != deTmdb.size) return@runCatching emptyMap<Int, DeTmdb>() to serie.paraMagis()

            val filas = deTmdb.associate { c ->
                c.episode to DeTmdb(
                    still = c.stillUrl.takeIf { it.isNotBlank() },
                    titulo = c.name.takeIf { it.isNotBlank() },
                    sinopsis = c.overview.takeIf { it.isNotBlank() },
                )
            }.filterValues { it.still != null || it.titulo != null || it.sinopsis != null }

            // Respaldo en inglés SOLO para las sinopsis vacías: TMDB devuelve el `overview` vacío en
            // es-MX muy seguido (el nombre suele venir igual). Tiene su propio catch: es un extra
            // sobre datos YA resueltos, así que si falla se conserva lo que se armó en español.
            val faltan = filas.filterValues { it.sinopsis == null }.keys
            if (faltan.isEmpty()) return@runCatching filas to serie.paraMagis()
            val enIngles = runCatching {
                tmdb.seasonEpisodes(serie.tmdbId, temporada, idioma = "en-US").orEmpty()
            }.getOrDefault(emptyList())
            val completadas = filas.toMutableMap()
            enIngles.forEach { c ->
                if (c.episode in faltan && c.overview.isNotBlank()) {
                    completadas[c.episode] = completadas.getValue(c.episode).copy(sinopsis = c.overview)
                }
            }
            completadas.toMap() to serie.paraMagis()
        }.getOrDefault(emptyMap<Int, DeTmdb>() to null)
    }

    /** Lo que de TMDB se usa acá, sin arrastrar el modelo entero. */
    internal data class TmdbSerieParaMagis(
        val tmdbId: Int,
        val titulo: String,
        val posterUrl: String,
        val backdropUrl: String,
    )

    private fun com.arkiv.player.data.catalog.TmdbSerieDeImdb.paraMagis() =
        TmdbSerieParaMagis(tmdbId, titulo, posterUrl, backdropUrl)

    private fun explicar(que: String, r: MagisResult<*>): String = when (r) {
        is MagisResult.PortalError -> "magis rechazó la $que (${r.codigo}${r.msg?.let { ": $it" }.orEmpty()})"
        is MagisResult.RedError -> "no se pudo hablar con magis (${r.causa.message})"
        is MagisResult.Ok -> "magis devolvió una $que sin datos"
    }

    private companion object {
        const val FUENTE = "magis"
        const val TTL_MS = 6 * 60 * 60 * 1000L
        val IMDB = Regex("""tt\d{7,}""")
    }
}

/** Caché en memoria con vencimiento y tope de entradas (lo más viejo sale primero). */
internal class CacheConVencimiento<K, V>(private val ttlMs: Long, private val tope: Int) {
    private val entradas = LinkedHashMap<K, Pair<Long, V>>()

    operator fun get(clave: K): V? {
        val (vence, valor) = entradas[clave] ?: return null
        if (System.currentTimeMillis() >= vence) {
            entradas.remove(clave)
            return null
        }
        return valor
    }

    operator fun set(clave: K, valor: V) {
        entradas.remove(clave)
        entradas[clave] = (System.currentTimeMillis() + ttlMs) to valor
        while (entradas.size > tope) entradas.remove(entradas.keys.first())
    }
}
