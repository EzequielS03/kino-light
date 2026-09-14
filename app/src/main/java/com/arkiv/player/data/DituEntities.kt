package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.ditu.DituFuente
import com.arkiv.player.data.ditu.DituRef
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySerie

/**
 * Un capítulo de una serie de Caracol, tal como lo necesita [DituEntities].
 *
 * Modelo propio y no `GatewayEpisode`, por lo mismo que [CapituloDeTemporada] en Magis: esto es
 * puro/JVM y no depende del paquete `gateway`. El llamador mapea uno al otro.
 *
 * [season] es la del propio capítulo (en un `GROUP_OF_BUNDLES`, la de su bundle); null = no se
 * sabe, y se guarda en la 1 ([DituEntities.temporadaGuardada]).
 */
data class CapituloDeCaracol(
    val number: Int,
    val title: String,
    val ref: String,
    val season: Int? = null,
)

/**
 * Lo que arma [DituEntities.buildSerie]: el ítem, sus episodios, y el episodeId del capítulo que se
 * tocó ([idDelElegido]), o null si ese capítulo no quedó guardado con su propio ref.
 */
data class SerieDeCaracol(
    val item: ItemEntity,
    val episodios: List<EpisodeEntity>,
    val idDelElegido: String?,
)

/**
 * Construye (ítem + episodios) de lo que llega de Caracol. Puro/JVM (sin `android.*`) para poder
 * testearse sin Room; el repositorio solo lo guarda ([ArkivRepository.addDituSource] para una
 * película o un capítulo suelto, [ArkivRepository.addDituSeason] para una serie entera).
 *
 * Calcado de [MagisEntities], y más chico: Caracol nunca tuvo filas viejas en esta rama, así que no
 * hay ids legacy que barrer ni identidad que reparar.
 *
 * A diferencia de Magis, lo guardado acá sigue sirviendo después: el `ref` codifica ids de Caracol,
 * que son estables (ver [DituRef]). Ese ref va en el `torrentData` del EPISODIO, que es de donde lo
 * lee `PlayerViewModel.loadDitu` (vía [ArkivRepository.magisRefForEpisode]) para resolver.
 */
object DituEntities {

    /**
     * Tiene que empezar con `"ditu:"`: es lo que hace que `PlayerSource.kindFor` devuelva
     * `SourceKind.DITU` y el reproductor entre por `loadDitu`. Con cualquier otro prefijo, lo
     * guardado se abriría con el reproductor de otra fuente.
     */
    const val PREFIX = "ditu:"

    /** El ítem de una película o de una serie entera. */
    fun itemIdDe(contentId: String): String = PREFIX + contentId

    /** El id de un capítulo dentro del ítem de su serie. El mismo formato que Magis. */
    fun episodioIdDe(itemId: String, number: Int): String = "$itemId::e$number"

    /** El id del único episodio de una película. */
    fun episodioIdDePelicula(itemId: String): String = "$itemId::0"

    /**
     * El id de un capítulo sabiendo su temporada: [episodioIdDe] tal cual en la T1, y con la
     * temporada adentro de la T2 en adelante.
     *
     * Existe por los `GROUP_OF_BUNDLES`: `DituEpisodes` aplana todas las temporadas de la serie en
     * una sola lista y toma el número de cada capítulo del `episodeNumber` de su bundle, así que dos
     * temporadas pueden traer cada una su capítulo 1 (así lo arma `DituEpisodesTest`). Con
     * [episodioIdDe] solo, el 1 de la T2 caería en la fila del 1 de la T1 —`upsertEpisodes` es un
     * REPLACE— y ese capítulo quedaría reproduciendo el otro.
     */
    fun episodioIdDeCapitulo(itemId: String, temporada: Int, number: Int): String =
        if (temporada <= 1) episodioIdDe(itemId, number) else "$itemId::t${temporada}e$number"

    /**
     * La temporada con la que queda guardado un capítulo: la que llega, o la 1 si no llega ninguna
     * (o llega en 0). Es la misma regla con la que `DituEpisodes` lee un capítulo que no la trae.
     */
    fun temporadaGuardada(season: Int?): Int = season?.takeIf { it > 0 } ?: 1

    /**
     * The season a Caracol chapter is saved with: the chapter's own ([GatewayEpisode.season],
     * which `DituFuente` fills in per chapter) and [serie]'s only when that's missing.
     *
     * Order matters: in a `GROUP_OF_BUNDLES`, [serie]'s season is a single value (season 1)
     * flattened across every season in the group, so if it won, season 2's chapter 1 would save
     * as season 1's chapter 1 and overwrite its row (see [episodioIdDeCapitulo]).
     *
     * Moved here from `ui/search/SearchPlayback.kt` once a second data-layer caller
     * (`BuscadorDeCapitulos.revisarDitu`) needed it too, alongside `RecommendationAggregator`.
     */
    fun temporadaDelCapitulo(
        capitulo: GatewayEpisode,
        serie: GatewaySerie?,
    ): Int? = capitulo.season ?: serie?.seasonNumber

    /**
     * A Caracol chapter shaped the way [DituEntities] saves it, with the season from
     * [temporadaDelCapitulo].
     *
     * Callers that map a whole list AND a chosen chapter through this (like
     * `SearchPlayback.playDituSeason`) must map both with it: if they didn't pull the season from
     * the same place, the chosen chapter would be looked up in a season that isn't its own.
     */
    fun capituloDeCaracol(
        capitulo: GatewayEpisode,
        serie: GatewaySerie?,
    ): CapituloDeCaracol = CapituloDeCaracol(
        number = capitulo.number,
        title = capitulo.title,
        ref = capitulo.ref,
        season = temporadaDelCapitulo(capitulo, serie),
    )

    /**
     * El id con el que queda guardado un capítulo, sea suelto ([build]) o con su serie
     * ([buildSerie]): los dos lo arman por [capituloDe], que lo saca de acá. Y [elegidoEntre] busca
     * con este mismo cálculo, así que el capítulo que se busca es el que se guardó.
     */
    fun idDelCapitulo(itemId: String, season: Int?, number: Int): String =
        episodioIdDeCapitulo(itemId, temporadaGuardada(season), number)

    /**
     * El `contentId` del ítem al que va esto, o null si no se puede guardar.
     *
     * - Película ([episode] en 0): el ítem es ella misma, y su ref tiene que ser un `VOD`. Una serie
     *   no se guarda así: `DituResolve.vod` sabe arrancar el primer capítulo de una serie, pero la
     *   tarjeta quedaría como película y abriría siempre ese capítulo. Primero se eligen capítulos.
     * - Capítulo ([episode] > 0): el ítem es la serie, así que el contentId sale de [seriesRef], que
     *   tiene que ser una serie; el ref del capítulo, un `VOD`.
     *
     * Los dos refs tienen que ser de Caracol ([DituRef.decode]). Es la guarda de que un ref de
     * otra fuente nunca termine guardado con un id `ditu:`, que el reproductor mandaría a Caracol.
     */
    fun contentIdDelItem(ref: String, seriesRef: String, episode: Int): String? {
        val propio = DituRef.decode(ref) ?: return null
        if (propio.isSeries) return null
        if (episode <= 0) return propio.contentId
        val serie = DituRef.decode(seriesRef) ?: return null
        return serie.contentId.takeIf { serie.isSeries }
    }

    /**
     * [episode] > 0 = capítulo de una serie; 0 = película.
     *
     * [ref] es el del capítulo (o el de la película) y va en el episodio: es lo que el reproductor
     * resuelve. [seriesRef] es el de la serie y va en el ítem.
     *
     * [existente] es la fila que ya está en la base, si la hay: `upsertItem` es un REPLACE, así que
     * lo que no se copie de ahí se pierde (la fecha de alta reordenaría el home). Mismo cuidado que
     * [MagisEntities.build].
     *
     * [season] nunca queda en null en un capítulo: sin temporada va a la 1, que es la misma regla con
     * la que `DituEpisodes` lee un capítulo que no la trae. Un capítulo en null entre otros con
     * temporada haría que `ArkivRepository.ensureEpisodeStills` aplanara desde la T1 (ver el KDoc de
     * `MagisEntities.capituloDe`).
     *
     * El `orderIndex` de la T2 en adelante va corrido [ORDEN_POR_TEMPORADA] por temporada: la
     * biblioteca ordena por `orderIndex` (`ItemDao.getEpisodesOf`), y con el número pelado los
     * capítulos de una serie de varias temporadas saldrían intercalados. En la T1 es el número,
     * igual que en Magis.
     */
    fun build(
        contentId: String,
        ref: String,
        title: String,
        episode: Int,
        episodeTitle: String,
        posterUrl: String,
        ahora: Long,
        seriesRef: String,
        existente: ItemEntity?,
        season: Int? = null,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): Pair<ItemEntity, EpisodeEntity> {
        val itemId = itemIdDe(contentId)
        val esCapitulo = episode > 0
        val item = itemDe(
            itemId = itemId, ref = ref, title = title, esCapitulo = esCapitulo, posterUrl = posterUrl,
            ahora = ahora, seriesRef = seriesRef, existente = existente,
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = tmdbId, tituloCanonico = tituloCanonico,
        )
        val ep = if (esCapitulo) {
            capituloDe(itemId, CapituloDeCaracol(episode, episodeTitle, ref, season))
        } else {
            EpisodeEntity(
                id = episodioIdDePelicula(itemId),
                itemId = itemId,
                section = "",
                displayName = MetadataParser.cleanName(title),
                orderIndex = 0,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = 0,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                torrentFileIndex = null,
                torrentData = ref,
            )
        }
        return item to ep
    }

    /**
     * La serie ENTERA: el ítem una vez, un episodio por cada uno de [capitulos], y cuál de esos
     * episodios es el [elegido] (el capítulo que se tocó, para reproducirlo).
     *
     * Es lo que se guarda al tocar un capítulo para verlo ([ArkivRepository.addDituSeason]), con la
     * lista que la ventana de capítulos ya cargó: sin ninguna llamada de red. Corre en cada
     * reproducción, así que tiene que ser idempotente: ids derivados del contenido, y lo que
     * `upsertItem` (REPLACE) borraría, copiado de [existente], igual que en [build].
     *
     * El ítem y cada episodio salen de las MISMAS piezas que usa [build] ([itemDe] y [capituloDe]):
     * un capítulo que ya tenías guardado suelto cae en su misma fila, no en una repetida.
     *
     * [episodiosVistosEnLista] llega ya re-sellado por quien llama, como en
     * `MagisEntities.buildSeason`: el total tras guardar es la unión de lo que ya había en la base
     * con lo que trae [capitulos], y esta función no tiene con qué leer la base.
     */
    fun buildSerie(
        contentId: String,
        seriesRef: String,
        title: String,
        capitulos: List<CapituloDeCaracol>,
        elegido: CapituloDeCaracol,
        posterUrl: String,
        ahora: Long,
        existente: ItemEntity?,
        episodiosVistosEnLista: Int?,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): SerieDeCaracol {
        val itemId = itemIdDe(contentId)
        val item = itemDe(
            itemId = itemId, ref = "", title = title, esCapitulo = true, posterUrl = posterUrl,
            ahora = ahora, seriesRef = seriesRef, existente = existente,
            episodiosVistosEnLista = episodiosVistosEnLista,
            tmdbId = tmdbId, tituloCanonico = tituloCanonico,
        )
        // Un episodio por id: si Caracol repitiera temporada y número en dos capítulos, los dos
        // caerían en la misma fila, así que queda el último y a la base llega exactamente esto.
        val episodios = capitulos.map { capituloDe(itemId, it) }.associateBy { it.id }.values.toList()
        return SerieDeCaracol(item, episodios, elegidoEntre(episodios, elegido))
    }

    /**
     * Los de [capitulos] que se pueden guardar en la serie [seriesRef]: los que pasan la guarda de
     * [contentIdDelItem], para que un ref que no es de Caracol nunca termine con id `ditu:`. Un
     * capítulo en 0 tampoco entra: con `episode = 0` esa guarda lo toma como película, y su
     * contentId sería el del capítulo, no el de la serie.
     */
    fun capitulosGuardables(seriesRef: String, capitulos: List<CapituloDeCaracol>): List<CapituloDeCaracol> =
        capitulos.filter { it.number > 0 && contentIdDelItem(it.ref, seriesRef, it.number) != null }

    /**
     * El episodeId del capítulo [elegido] entre los [guardados] (lo que devolvió [buildSerie]), o
     * null si no quedó guardado con su propio ref.
     *
     * Se busca por temporada Y número, con el mismo cálculo del guardado ([idDelCapitulo]), nunca
     * por número solo: en un `GROUP_OF_BUNDLES` la lista trae el capítulo 1 de la T1 y el capítulo 1
     * de la T2 (ver [episodioIdDeCapitulo]), y por número el de la T2 reproduciría el de la T1.
     *
     * Y la fila tiene que tener el ref del [elegido]: si Caracol repitiera temporada y número en
     * dos capítulos, los dos irían al mismo id y [buildSerie] guarda uno solo, así que reproducir
     * esa fila sería reproducir otro. Con null, quien llama cae a guardar el elegido solo.
     */
    fun elegidoEntre(guardados: List<EpisodeEntity>, elegido: CapituloDeCaracol): String? =
        guardados.firstOrNull { ep ->
            ep.id == idDelCapitulo(ep.itemId, elegido.season, elegido.number) && ep.torrentData == elegido.ref
        }?.id

    /**
     * El ítem, para [build] y [buildSerie]. Un capítulo ([esCapitulo]) va en el ítem de su serie, con
     * el ref de la serie en el `torrentData`; una película lleva el suyo ([ref]).
     */
    private fun itemDe(
        itemId: String,
        ref: String,
        title: String,
        esCapitulo: Boolean,
        posterUrl: String,
        ahora: Long,
        seriesRef: String,
        existente: ItemEntity?,
        episodiosVistosEnLista: Int?,
        tmdbId: Int?,
        tituloCanonico: String?,
    ) = ItemEntity(
        identifier = itemId,
        title = title.ifBlank { "Caracol" },
        description = null,
        // Una carátula vacía no borra la que ya estaba.
        thumbnailUrl = posterUrl.ifBlank { existente?.thumbnailUrl.orEmpty() },
        addedAt = existente?.addedAt ?: ahora,
        categoryOverride = if (esCapitulo) "series" else existente?.categoryOverride,
        source = DituFuente.SOURCE,
        torrentData = if (esCapitulo) seriesRef else ref,
        episodiosVistosEnLista = episodiosVistosEnLista,
        // Un tmdbId ausente no borra el que ya estaba guardado, igual que en Magis.
        tmdbId = tmdbId ?: existente?.tmdbId,
        tipo = if (esCapitulo) "tv" else "movie",
        tituloCanonico = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() } ?: existente?.tituloCanonico,
    )

    /**
     * El episodio de UN capítulo. Lo comparten [build] y [buildSerie] a propósito: el `id` es la
     * clave primaria, así que si los dos caminos no lo armaran idéntico, guardar la serie duplicaría
     * el capítulo que ya estaba guardado suelto. Mismo cuidado que `MagisEntities.capituloDe`.
     */
    private fun capituloDe(itemId: String, capitulo: CapituloDeCaracol): EpisodeEntity {
        val temporada = temporadaGuardada(capitulo.season)
        val number = capitulo.number
        return EpisodeEntity(
            id = idDelCapitulo(itemId, capitulo.season, number),
            itemId = itemId,
            section = "",
            displayName = "E$number" + capitulo.title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
            orderIndex = (temporada - 1) * ORDEN_POR_TEMPORADA + number,
            durationSeconds = 0.0,
            thumbPath = null,
            originalPath = null,
            originalFormat = null,
            originalSize = 0,
            derivativePath = null,
            derivativeFormat = null,
            derivativeSize = 0,
            season = temporada,
            episode = number,
            torrentFileIndex = null,
            torrentData = capitulo.ref,
        )
    }

    private const val ORDEN_POR_TEMPORADA = 10_000
}
