package com.arkiv.player.ui.search

import com.arkiv.player.AppGraph
import com.arkiv.player.data.DituEntities

/** Resultado de intentar preparar una reproducción: listo con episodeId, o falló con un mensaje
 *  para mostrar al usuario (mismos textos que mostraba SearchScreen antes de la extracción). */
sealed class PlaybackResult {
    data class Ready(val episodeId: String) : PlaybackResult()
    data class Failed(val message: String) : PlaybackResult()
}

/**
 * Resuelve una fuente elegida en el buscador (Magis o Caracol), la guarda en la biblioteca vía
 * [AppGraph.repository] y devuelve el episodeId listo para reproducir. Extraído VERBATIM de las
 * funciones locales que vivían en `SearchScreen` (playArchiveResult, entre otras) para
 * que TV pueda reusar exactamente la misma lógica sin duplicarla. No-Compose a propósito: solo
 * necesita el grafo de dependencias, no estado de UI.
 *
 * `preparing`/`playError`/`onPlay(epId)` siguen viviendo en el composable que llama a estos métodos:
 * este helper solo resuelve+guarda y devuelve el resultado.
 */
class SearchPlayback(private val graph: AppGraph) {

    /**
     * Guarda un resultado de Magis y devuelve su episodeId.
     *
     * El id sale del `contentId` del portal, no del ref: el ref se re-emite en cada búsqueda y un id
     * derivado de él perdería la posición de reproducción. El ref se guarda al lado y se refresca.
     *
     * La temporada sale del propio resultado (`GatewayResult.season`, que el portal manda en la
     * búsqueda) y NO se deja en null: un episodio sin `season` en un ítem donde los demás sí la
     * tienen hace que `ArkivRepository.ensureEpisodeStills` caiga a su rama de aplanar desde la
     * temporada 1 y pise los stills buenos de toda la serie (ver el KDoc de `MagisEntities.build`).
     * `0` es "el portal no la dijo", no la temporada cero, de ahí el `takeIf`.
     */
    suspend fun magisEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? {
        val contentId = r.extra["content_id"].orEmpty()
        return graph.repository.addMagisSource(
            ref = r.ref, contentId = contentId, title = r.title, episode = r.episode,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
            season = r.season.takeIf { it > 0 },
        )
    }

    /**
     * Reproduce un capítulo suelto de una temporada de Magis.
     *
     * El capítulo entra como episodio del ítem de la TEMPORADA (una tarjeta por serie, marcada como
     * serie desde el primer capítulo; ver `MagisEntities`), con su propia marca de "voy por aquí".
     */
    /**
     * Guarda el capítulo y devuelve su episodeId, sin navegar. Lo usa el guardado en lote (el botón
     * "Guardar" del diálogo de temporada, en el celu y en el TV) y el respaldo de [playMagisSeason].
     *
     * [serie] es el bloque `series` de la misma respuesta que trajo [capitulo] (null si el gateway
     * no lo pudo resolver contra TMDB), y **no tiene default a propósito**: guardar sin él era el
     * bug. `upsertEpisodes` es un `@Insert(onConflict = REPLACE)`, así que esta llamada reescribe la
     * fila ENTERA del episodio; sin `season`, marcar tres capítulos de una temporada ya guardada por
     * [playMagisSeason] les borraba el número de temporada, y desde ahí `ensureEpisodeStills` cruza
     * aplanando desde la T1 y pisa los stills de toda la serie (ver el KDoc de
     * `MagisEntities.build`). Hasta Task 5, además, `episodes` era tabla sincronizada: ese
     * `season = null` viajaba al otro dispositivo también. Sin cloud sync el daño queda contenido
     * a este aparato, pero sigue siendo el mismo bug local.
     *
     * Los tres campos enriquecidos del capítulo (still, nombre real y sinopsis) viajan por lo mismo
     * que la temporada: el diálogo YA los tiene en la mano, y sin pasarlos un capítulo guardado sin
     * haberlo reproducido nunca quedaba sin fila en `episode_still` — o sea, tarjeta negra en la
     * biblioteca hasta que alguien abriera la serie.
     */
    suspend fun magisEpisodeIdDe(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): String? = graph.repository.addMagisSource(
        ref = capitulo.ref,
        contentId = temporada.extra["content_id"].orEmpty(),
        // El título del ítem es el de la TEMPORADA, no el del capítulo: el ítem es la serie, y el
        // capítulo se nombra aparte adentro. Pegarlos dejaba tarjetas "Daima T1 · Daima T1_1".
        title = temporada.title,
        episode = capitulo.number,
        episodeTitle = capitulo.title,
        // El capítulo hereda las imágenes de SU temporada: un GatewayEpisode no trae propias.
        posterUrl = temporada.extra["poster"].orEmpty(),
        backdropUrl = temporada.extra["backdrop"].orEmpty(),
        // El ref de la temporada es el que resuelve MagisCatalog.detail; el del capítulo no.
        seriesRef = temporada.ref,
        season = serie?.seasonNumber,
        // Mismo blindaje que en [playMagisSeason]: `tmdbId` sale de un `optInt`, así que un campo
        // ausente daría 0 y ese 0 le ganaría al `?:` que preserva el tmdbId ya guardado.
        tmdbId = serie?.tmdbId?.takeIf { it > 0 },
        // El nombre de TMDB, para que la tarjeta no se quede con el del portal.
        tituloCanonico = serie?.titulo,
        still = capitulo.still,
        tmdbTitle = capitulo.tmdbTitle,
        overview = capitulo.overview,
    )

    suspend fun playMagisEpisode(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val epId = magisEpisodeIdDe(temporada, capitulo, serie)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo.")
    }

    /**
     * Guarda la temporada ENTERA y devuelve el capítulo que se tocó, para reproducirlo.
     *
     * Same idea that `playPackRow` (torrent) and `saveWebPack` (web) used to follow, before this
     * branch's pruning removed both: touching a chapter brings the whole season into the library,
     * not just that chapter. The list AND the series were already loaded by the screen
     * with `client.episodesConSerie` when it opened, so this costs no network call.
     * **No descarga nada**: eso lo sigue haciendo el botón "Guardar".
     *
     * [serie] es el bloque `series` de esa misma respuesta (null si el gateway no pudo resolver la
     * serie contra TMDB): de ahí sale el `tmdbId` que se guarda en el ítem. Viaja como parámetro y
     * no se vuelve a pedir acá adentro — este es el camino por el que se reproduce, así que un
     * round-trip redundante es justo lo que no puede haber.
     *
     * Si la temporada no se pudo guardar (el portal no mandó `content_id`), cae al camino de
     * siempre —guardar solo el capítulo— antes que dejar al usuario sin reproducir nada.
     */
    suspend fun playMagisSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        elegido: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val guardados = graph.repository.addMagisSeason(
            contentId = temporada.extra["content_id"].orEmpty(),
            title = temporada.title,
            capitulos = capitulos.map {
                com.arkiv.player.data.CapituloDeTemporada(
                    number = it.number, title = it.title, ref = it.ref,
                    still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
                )
            },
            seriesRef = temporada.ref,
            posterUrl = temporada.extra["poster"].orEmpty(),
            backdropUrl = temporada.extra["backdrop"].orEmpty(),
            // `GatewaySerie.tmdbId` sale de un `optInt` (GatewayModels.kt): si el campo faltara daría
            // 0, no null, y ese 0 le ganaría al `?:` de `buildSeason` y borraría un tmdbId válido que
            // ya estuviera guardado. Hoy el gateway solo manda `series` cuando SÍ resolvió, así que no
            // es alcanzable, pero blindarlo acá no cuesta nada.
            tmdbId = serie?.tmdbId?.takeIf { it > 0 },
        // El nombre de TMDB, para que la tarjeta no se quede con el del portal.
        tituloCanonico = serie?.titulo,
            seasonNumber = serie?.seasonNumber,
        )
        val epId = guardados[elegido.number] ?: return playMagisEpisode(temporada, elegido, serie)
        return PlaybackResult.Ready(epId)
    }

    /** Reproduce un resultado de Magis: lo guarda y devuelve a dónde navegar. */
    suspend fun playMagis(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = magisEpisodeId(r)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Magis.")
    }

    /**
     * Guarda un resultado de Caracol (una película) y devuelve su episodeId. Calcado de
     * [magisEpisodeId], con una diferencia que importa: lo guardado no vence (ver `DituRef`).
     *
     * El id sale del `contentId` que va dentro del ref (`ditu1:<contentType>:<contentId>`), y el ref
     * queda en el `torrentData` del episodio: de ahí lo lee `PlayerViewModel.loadDitu`. A una serie
     * le devuelve null: primero se eligen sus capítulos ([playDituSeason]). Ver
     * `DituEntities.contentIdDelItem`.
     */
    suspend fun dituEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? =
        graph.repository.addDituSource(ref = r.ref, title = r.title, posterUrl = r.extra["poster"].orEmpty())

    /** Reproduce una película de Caracol: la guarda y devuelve a dónde navegar. */
    suspend fun playDitu(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = dituEpisodeId(r)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Caracol.")
    }

    /**
     * Guarda la serie ENTERA de Caracol y devuelve el capítulo que se tocó, para reproducirlo.
     *
     * Es el camino de Caracol de la ventana de capítulos, calcado de [playMagisSeason]: tocar un
     * capítulo trae a la biblioteca todos los de la lista que la ventana ya cargó con
     * `episodesConSerie` al abrirse, así que no cuesta ninguna llamada de red. Solo escribe por
     * `ArkivRepository.addDituSeason` —id `ditu:`, nunca por [playMagisSeason] ni
     * [magisEpisodeIdDe], que arman ids `magis:`—.
     *
     * El elegido NO se busca por número, como en [playMagisSeason]: en un `GROUP_OF_BUNDLES` la lista
     * trae un capítulo 1 en cada temporada, y por número se reproduciría el de otra. Se busca por su
     * temporada y su número (`DituEntities.elegidoEntre`), y la lista y el elegido pasan por el mismo
     * [DituEntities.capituloDeCaracol], así que su temporada sale de la misma
     * [DituEntities.temporadaDelCapitulo].
     *
     * Si la serie no se pudo guardar, o el elegido no quedó en ella, cae a [playDituEpisode] —guardar
     * solo el capítulo— antes que dejar a la persona sin reproducir nada.
     */
    suspend fun playDituSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        elegido: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val epId = graph.repository.addDituSeason(
            seriesRef = temporada.ref,
            // El ítem es la serie; cada capítulo se nombra aparte, adentro.
            title = temporada.title,
            capitulos = capitulos.map { DituEntities.capituloDeCaracol(it, serie) },
            elegido = DituEntities.capituloDeCaracol(elegido, serie),
            posterUrl = temporada.extra["poster"].orEmpty().ifBlank { serie?.posterUrl.orEmpty() },
            backdropUrl = serie?.backdropUrl.orEmpty(),
            // Mismo blindaje que en [playDituEpisode]: un tmdbId en 0 no pisa uno ya guardado.
            tmdbId = serie?.tmdbId?.takeIf { it > 0 },
            // Sin cruce con TMDB, `GatewaySerie.titulo` es el nombre de Caracol, no el canónico.
            tituloCanonico = serie?.takeIf { it.tmdbId > 0 }?.titulo,
        ) ?: return playDituEpisode(temporada, elegido, serie)
        return PlaybackResult.Ready(epId)
    }

    /**
     * Guarda UN capítulo de una serie de Caracol y devuelve su episodeId, para reproducirlo.
     *
     * Ya no es el camino normal: al tocar un capítulo, la ventana de capítulos llama a
     * [playDituSeason], que guarda la serie entera. Esto es su respaldo, para cuando la serie no se
     * pudo guardar o el elegido no quedó en ella. Solo escribe por `ArkivRepository.addDituSource`
     * —nunca por [playMagisSeason] ni [magisEpisodeIdDe], que arman ids `magis:`—, y le da al
     * capítulo el mismo id que le da [playDituSeason] (los dos lo arman con `DituEntities`).
     *
     * La temporada la decide [DituEntities.temporadaDelCapitulo].
     */
    suspend fun playDituEpisode(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulo: com.arkiv.player.data.gateway.GatewayEpisode,
        serie: com.arkiv.player.data.gateway.GatewaySerie?,
    ): PlaybackResult {
        val epId = graph.repository.addDituSource(
            ref = capitulo.ref,
            seriesRef = temporada.ref,
            // El ítem es la serie; el capítulo se nombra aparte, adentro.
            title = temporada.title,
            episode = capitulo.number,
            episodeTitle = capitulo.title,
            posterUrl = temporada.extra["poster"].orEmpty().ifBlank { serie?.posterUrl.orEmpty() },
            backdropUrl = serie?.backdropUrl.orEmpty(),
            season = DituEntities.temporadaDelCapitulo(capitulo, serie),
            // `DituFuente` deja el tmdbId en 0 cuando TMDB no la encontró: ese 0 no puede pisar un
            // tmdbId ya guardado.
            tmdbId = serie?.tmdbId?.takeIf { it > 0 },
            // Sin cruce con TMDB, `GatewaySerie.titulo` es el nombre de Caracol, no el canónico.
            tituloCanonico = serie?.takeIf { it.tmdbId > 0 }?.titulo,
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo de Caracol.")
    }

}
