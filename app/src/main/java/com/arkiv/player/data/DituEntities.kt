package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.ditu.DituFuente
import com.arkiv.player.data.ditu.DituRef

/**
 * Construye (ítem + episodio) de lo que llega de Caracol. Puro/JVM (sin `android.*`) para poder
 * testearse sin Room; el repositorio solo lo guarda ([ArkivRepository.addDituSource]).
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
     * Existe por los `GROUP_OF_BUNDLES`: `DituEpisodios` aplana todas las temporadas de la serie en
     * una sola lista y toma el número de cada capítulo del `episodeNumber` de su bundle, así que dos
     * temporadas pueden traer cada una su capítulo 1 (así lo arma `DituEpisodiosTest`). Con
     * [episodioIdDe] solo, el 1 de la T2 caería en la fila del 1 de la T1 —`upsertEpisodes` es un
     * REPLACE— y ese capítulo quedaría reproduciendo el otro.
     */
    fun episodioIdDeCapitulo(itemId: String, temporada: Int, number: Int): String =
        if (temporada <= 1) episodioIdDe(itemId, number) else "$itemId::t${temporada}e$number"

    /**
     * El `contentId` del ítem al que va esto, o null si no se puede guardar.
     *
     * - Película ([episode] en 0): el ítem es ella misma, y su ref tiene que ser un `VOD`. Una serie
     *   no se guarda así: `DituResolve.vod` sabe arrancar el primer capítulo de una serie, pero la
     *   tarjeta quedaría como película y abriría siempre ese capítulo. Primero se eligen capítulos.
     * - Capítulo ([episode] > 0): el ítem es la serie, así que el contentId sale de [seriesRef], que
     *   tiene que ser una serie; el ref del capítulo, un `VOD`.
     *
     * Los dos refs tienen que ser de Caracol ([DituRef.decodificar]). Es la guarda de que un ref de
     * otra fuente nunca termine guardado con un id `ditu:`, que el reproductor mandaría a Caracol.
     */
    fun contentIdDelItem(ref: String, seriesRef: String, episode: Int): String? {
        val propio = DituRef.decodificar(ref) ?: return null
        if (propio.esSerie) return null
        if (episode <= 0) return propio.contentId
        val serie = DituRef.decodificar(seriesRef) ?: return null
        return serie.contentId.takeIf { serie.esSerie }
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
     * la que `DituEpisodios` lee un capítulo que no la trae. Un capítulo en null entre otros con
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
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Caracol" },
            description = null,
            // Una carátula vacía no borra la que ya estaba.
            thumbnailUrl = posterUrl.ifBlank { existente?.thumbnailUrl.orEmpty() },
            addedAt = existente?.addedAt ?: ahora,
            categoryOverride = if (esCapitulo) "series" else existente?.categoryOverride,
            source = DituFuente.FUENTE,
            torrentData = if (esCapitulo) seriesRef else ref,
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            // Un tmdbId ausente no borra el que ya estaba guardado, igual que en Magis.
            tmdbId = tmdbId ?: existente?.tmdbId,
            tipo = if (esCapitulo) "tv" else "movie",
            tituloCanonico = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() } ?: existente?.tituloCanonico,
        )
        val ep = if (esCapitulo) {
            val temporada = season?.takeIf { it > 0 } ?: 1
            EpisodeEntity(
                id = episodioIdDeCapitulo(itemId, temporada, episode),
                itemId = itemId,
                section = "",
                displayName = "E$episode" + episodeTitle.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
                orderIndex = (temporada - 1) * ORDEN_POR_TEMPORADA + episode,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = 0,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                season = temporada,
                episode = episode,
                torrentFileIndex = null,
                torrentData = ref,
            )
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

    private const val ORDEN_POR_TEMPORADA = 10_000
}
