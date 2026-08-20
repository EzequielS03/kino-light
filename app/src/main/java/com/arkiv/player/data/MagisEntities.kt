package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.EpisodeStillEntity
import com.arkiv.player.data.db.ItemEntity

/**
 * Un capítulo de una temporada, tal como lo necesita [MagisEntities].
 *
 * Modelo propio y no `GatewayEpisode` a propósito: `MagisEntities` es puro/JVM (se testea sin Room
 * ni red) y no debe depender del paquete `gateway`. El llamador mapea uno al otro.
 *
 * [still], [tmdbTitle] y [overview] son lo que el gateway agrega cruzando el capítulo contra TMDB
 * (ver `GatewayEpisode`); opcionales porque TMDB no siempre resuelve. [MagisEntities.stillsDeTemporada]
 * los usa para armar la fila de `episode_still` de cada capítulo.
 */
data class CapituloDeTemporada(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
)

/**
 * Construye (ítem + episodio) de lo que llega de Magis. Puro/JVM (sin `android.*`) para poder
 * testearse sin Room; el repositorio solo lo guarda. Mismo molde que [PackEntities].
 *
 * **Una temporada es UN ítem, y sus capítulos son sus episodios.** Antes cada capítulo era su
 * propio ítem (`magis:<contentId>:e1`, `…:e2`, …), y como la categoría se detecta sola por
 * cantidad de episodios (`LibraryRow.isMovie`: `episodeCount <= 1`), un capítulo suelto se leía
 * como **película** y la serie aparecía en la fila equivocada del home. Acá se hace lo mismo que
 * ya hacían el resto de las fuentes con capítulos (`addWebSeriesEpisode`, `addSeriesEpisode`,
 * `addAnimeEpisode`): id estable por serie + `categoryOverride = "series"` desde el primer
 * capítulo, sin esperar a que haya dos.
 *
 * El id sale del `contentId` del portal —que en Magis ya es por temporada— y no del `ref`: el ref
 * se re-emite en cada búsqueda y un id derivado de él perdería la marca de "voy por aquí".
 */
object MagisEntities {

    const val PREFIX = "magis:"

    /** El ítem de una película o de una temporada entera. */
    fun itemIdDe(contentId: String): String = PREFIX + contentId

    /**
     * El id que tenía un capítulo cuando cada uno era su propio ítem.
     *
     * Sirve para barrer esas filas: quedaron en la biblioteca como tarjetas-película sueltas y no
     * hay migración de Room que las toque (ver los planes en `docs/superpowers/plans/`), así que se
     * limpian al volver a guardar ese mismo capítulo.
     */
    fun idLegacyDeCapitulo(contentId: String, episode: Int): String = "${itemIdDe(contentId)}:e$episode"

    /**
     * El id de un capítulo DENTRO del ítem de su temporada. Lo usa [capituloDe] para armar el
     * `EpisodeEntity`, y también el repositorio: para saber cuántos episodios va a tener la
     * temporada TRAS guardar (la unión de lo que ya había con lo que llega del portal, ver
     * `ArkivRepository.addMagisSeason`) necesita comparar contra los mismos ids sin duplicar acá y
     * allá el formato `$itemId::e$number`.
     */
    fun episodioIdDe(itemId: String, number: Int): String = "$itemId::e$number"

    /**
     * El id del episodio de una PELÍCULA -- o de una serie que entró como ref suelto, que es como
     * guardaba "Para ti" antes de saber pedirle los capítulos al gateway.
     *
     * Tiene nombre propio porque no alcanza con escribirlo donde se guarda:
     * `ArkivRepository.addMagisSeason` lo BARRE al guardar la temporada de una serie que ya había
     * entrado así. Su id no es el de ningún capítulo ([episodioIdDe] siempre lleva `:e`), así que el
     * upsert de la temporada no lo pisa y quedaría de capítulo fantasma —con el título de la serie y
     * el ref de la temporada entera— para siempre. Si el barrido y el guardado no calcularan
     * exactamente el mismo id, uno borraría algo que no es y el otro dejaría el fantasma intacto.
     */
    fun episodioIdDePelicula(itemId: String): String = "$itemId::0"

    /**
     * El episodio de UN capítulo. Lo comparten [build] y [buildSeason] a propósito: el `id` es la
     * clave primaria, así que si los dos caminos no lo armaran idéntico, guardar la temporada
     * duplicaría los capítulos que ya estaban guardados sueltos.
     *
     * [season] es el número de temporada real cuando se conoce ([buildSeason], que lo saca de
     * `GatewaySerie.seasonNumber`) o `null` cuando no ([build], que guarda un capítulo suelto sin
     * ese contexto). Importa más de lo que parece: `ArkivRepository.ensureEpisodeStills` cruza por
     * (temporada, capítulo) SOLO si TODOS los episodios del ítem tienen `season` puesto: uno solo en
     * `null` lo hace caer a su rama de repartir capítulos 1..N por temporada desde la 1, que para
     * una serie que no arranca en la T1 (Breaking Bad T5, por ejemplo) pone el still de otro
     * capítulo. De ahí que esto NO sea cosmético.
     */
    private fun capituloDe(itemId: String, number: Int, title: String, ref: String, season: Int?) = EpisodeEntity(
        id = episodioIdDe(itemId, number),
        itemId = itemId,
        section = "",
        displayName = "E$number" + title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
        orderIndex = number,
        durationSeconds = 0.0,
        thumbPath = null,
        originalPath = null,
        originalFormat = null,
        originalSize = 0,
        derivativePath = null,
        derivativeFormat = null,
        derivativeSize = 0,
        season = season,
        // Numerado a propósito: es con esto que `CapitulosFaltantes` sabe cuál falta.
        episode = number,
        torrentFileIndex = null,
        torrentData = ref,
    )

    /**
     * El ref de temporada con el que volver a pedirle al gateway la identidad de un ítem de Magis
     * que se guardó sin ella, o null si no hay nada que reparar.
     *
     * El `tmdbId` se escribe al GUARDAR la temporada, no al abrirla: los ítems que entraron cuando
     * el gateway todavía no sabía identificar la serie se quedaron sin él, y con él sin nombre real
     * de capítulo, sin miniatura y sin sinopsis — para siempre, porque abrir la pantalla no vuelve
     * a preguntar. El `seriesRef` sí quedó guardado (mismo campo donde web guarda su `pageUrl`), y
     * con eso alcanza para preguntar una sola vez.
     *
     * Un `tmdbId` en 0 cuenta como ausente: `GatewaySerie.tmdbId` sale de un `optInt` y un campo
     * que no vino da 0, no null.
     */
    fun refParaReparar(identifier: String, tmdbId: Int?, torrentData: String?): String? {
        if (!identifier.startsWith("magis:")) return null
        if (tmdbId != null && tmdbId > 0) return null
        return torrentData?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * La temporada COMPLETA: un ítem y un episodio por capítulo.
     *
     * Es lo que se guarda al tocar un capítulo para verlo — la lista ya la tiene la pantalla, así
     * que no cuesta ni una llamada de red. Corre en cada reproducción, así que **tiene que ser
     * idempotente**: ids derivados del contenido y todo lo que `upsertItem` (REPLACE) borraría
     * copiado de [existente], igual que en [build].
     *
     * [seriesRef] es el ref de la temporada (el que responde `/v1/episodes`). En blanco no pisa el
     * guardado: los refs caducan y uno vencido es mejor que ninguno. A diferencia de [build], acá
     * NO se cae al ref de un capítulo como último recurso — un ref de capítulo en el ítem haría que
     * `BuscadorDeCapitulos` le pidiera la lista de capítulos a un capítulo.
     *
     * [episodiosVistosEnLista] va COMPLETO, ya calculado por quien llama, y no `existente
     * ?.episodiosVistosEnLista` leído acá adentro. Antes se guardaba sin tocar y el repositorio lo
     * corregía después con un segundo UPDATE puntual (`marcarEpisodiosVistos`) — dos escrituras a la
     * misma fila en la misma llamada, que es justo lo que hacía recursar el trigger de sync cuando
     * caían en el mismo segundo (ver `SyncTriggers`). Acá adentro no se puede calcular solo: el
     * total post-guardado es la UNIÓN de los capítulos que ya estaban en la base con los que traen
     * [capitulos] (`addMagisSeason` hace upsert, no replace, para no perder capítulos viejos que el
     * portal ya no liste), y esta función es pura/JVM — no tiene con qué consultar la base. Por eso
     * el repositorio lo resuelve (con `ItemDao.getEpisodesOf`) y lo pasa ya resuelto.
     *
     * [tmdbId] y [seasonNumber] son últimos y con default porque todos los llamadores usan
     * argumentos nombrados; el orden no importa, solo que sean opcionales para no romper a quien ya
     * llamaba a esta función antes de que existieran.
     */
    fun buildSeason(
        contentId: String,
        title: String,
        capitulos: List<CapituloDeTemporada>,
        posterUrl: String,
        ahora: Long,
        seriesRef: String,
        existente: ItemEntity?,
        episodiosVistosEnLista: Int?,
        // Puede llegar null cuando TMDB no resolvió esta vez (o el gateway es viejo), y no por eso
        // hay que descartar lo que ya estaba guardado — de ahí el `?:` de abajo.
        tmdbId: Int? = null,
        // El `season_number` de `GatewaySerie`: se lo pasa a [capituloDe] para que
        // `ArkivRepository.ensureEpisodeStills` pueda cruzar por (temporada, capítulo) exacto en vez
        // de aplanar desde la temporada 1 (ver el KDoc de [capituloDe]). Null cuando el gateway no
        // resolvió la serie: el episodio queda con `season = null`, igual que hoy.
        seasonNumber: Int? = null,
        // El nombre con el que TMDB conoce la serie (`GatewaySerie.titulo`). Mismo `?:` que
        // [tmdbId]: uno ausente no borra el que ya estaba guardado. Ver [nombreCanonico].
        tituloCanonico: String? = null,
    ): Pair<ItemEntity, List<EpisodeEntity>> {
        val itemId = itemIdDe(contentId)
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Magis" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existente?.addedAt ?: ahora,
            categoryOverride = "series",
            source = "magis",
            torrentData = seriesRef.ifBlank { existente?.torrentData.orEmpty() }.takeIf { it.isNotBlank() },
            episodiosVistosEnLista = episodiosVistosEnLista,
            // Un tmdbId ausente (TMDB no resolvió esta vez, o el gateway es viejo) no puede borrar
            // el que ya estaba guardado: los refs de imdb/tmdb no cambian, así que uno viejo sigue
            // siendo válido.
            tmdbId = tmdbId ?: existente?.tmdbId,
            // Es SIEMPRE una temporada -no hace falta el `?:` de tmdbId: acá no hay ambigüedad que
            // preservar, cada llamada a buildSeason es de una serie.
            tipo = "tv",
            tituloCanonico = nombreCanonico(tituloCanonico, existente),
        )
        return item to capitulos.map { capituloDe(itemId, it.number, it.title, it.ref, seasonNumber) }
    }

    /**
     * Una fila de `episode_still` por cada capítulo que TMDB pudo enriquecer (still, nombre real o
     * sinopsis) — los que no traen nada quedan afuera, para que la UI caiga al `displayName` del
     * portal en vez de mostrar una fila vacía.
     *
     * `episodeId` sale de [episodioIdDe], el MISMO cálculo que usa [capituloDe] para el id del
     * `EpisodeEntity`: es la clave por la que esta fila se cruza con su episodio, y si no calzaran
     * la imagen no aparecería nunca.
     */
    fun stillsDeTemporada(
        itemId: String,
        capitulos: List<CapituloDeTemporada>,
        ahora: Long,
    ): List<EpisodeStillEntity> = capitulos
        .filter { it.still != null || it.tmdbTitle != null || it.overview != null }
        .map { cap ->
            EpisodeStillEntity(
                episodeId = episodioIdDe(itemId, cap.number),
                stillUrl = cap.still,
                fetchedAt = ahora,
                title = cap.tmdbTitle,
                overview = cap.overview,
            )
        }

    /**
     * [episode] > 0 = capítulo de una serie; 0 = película (el camino de siempre, intacto).
     *
     * [ref] es el del capítulo (o el de la película) y viaja en el episodio, que es lo que el
     * player resuelve al reproducir. [seriesRef] es el de la TEMPORADA y viaja en el ítem, que es
     * lo que sirve para preguntarle al portal si salió un capítulo nuevo; en blanco NO pisa el que
     * ya estaba guardado, porque los refs caducan y uno vencido es mejor que ninguno.
     *
     * [existente] es la fila que ya está en la base, si la hay: `upsertItem` es un REPLACE (borra e
     * inserta), así que lo que no se copie de ahí se pierde —la fecha de alta reordenaría el home y
     * `episodiosVistosEnLista` volvería a prender el badge sobre capítulos ya mirados.
     *
     * [season] y [tmdbId] son opcionales (default `null`) por compatibilidad, **no porque haya un
     * camino al que no le importen**: TODO llamador que pueda saber la temporada tiene que pasarla.
     * `ItemDao.upsertEpisodes` es un `@Insert(onConflict = REPLACE)`, así que cada una de estas
     * llamadas reescribe la fila entera del episodio; una sin [season] le BORRA la temporada a un
     * capítulo que otro camino ya había guardado bien, el ítem queda con episodios mezclados (unos
     * con temporada, otros sin) y `ArkivRepository.ensureEpisodeStills` cae a su rama de aplanar
     * desde la T1 (ver el KDoc de [capituloDe]), pisando en silencio los stills de toda la serie. Y
     * `episodes` es tabla sincronizada: ese null viaja al otro dispositivo.
     *
     * Quiénes pasan hoy la temporada, y de dónde la sacan:
     *  - `SearchPlayback.magisEpisodeIdDe` (botón "Guardar" del diálogo de temporada, celu y TV) y
     *    `BuscadorDeCapitulos.revisarMagis` (capítulos nuevos en background): del `season_number`
     *    del bloque `series` (`GatewaySerie`) que devuelve `/v1/episodes`.
     *  - `SearchPlayback.magisEpisodeId` y `CineDetailScreen.playMagis` (resultado suelto de
     *    búsqueda, sin lista de capítulos): del `season` del propio `GatewayResult`.
     *
     * Queda en `null` solo cuando de verdad no se sabe: el gateway no pudo cruzar la serie contra
     * TMDB, o el portal no mandó temporada en el resultado. Inventarla sería peor.
     *
     * Mismo `?:` que en [buildSeason] para [tmdbId]: uno nuevo ausente no borra el que ya estaba.
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
            title = title.ifBlank { "Magis" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existente?.addedAt ?: ahora,
            categoryOverride = if (esCapitulo) "series" else existente?.categoryOverride,
            source = "magis",
            torrentData = if (esCapitulo) {
                seriesRef.ifBlank { existente?.torrentData?.ifBlank { null } ?: ref }
            } else {
                ref
            },
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = tmdbId ?: existente?.tmdbId,
            // `episode` dice con certeza si esto es un capítulo de serie o una película: no hace
            // falta el `?:` de tmdbId, cada llamada sabe cuál de las dos es.
            tipo = if (esCapitulo) "tv" else "movie",
            tituloCanonico = nombreCanonico(tituloCanonico, existente),
        )
        val ep = if (esCapitulo) {
            capituloDe(itemId, episode, episodeTitle, ref, season = season)
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
     * El nombre canónico que queda tras guardar: el que llega, o el que ya estaba.
     *
     * En blanco cuenta como ausente, no como nombre: `GatewaySerie.titulo` viene vacío cuando el
     * gateway es viejo o TMDB no resolvió, y adoptar esa cadena dejaría la tarjeta SIN TEXTO. Y
     * ausente no borra: [build] y [buildSeason] corren en cada guardado, así que sin este `?:` una
     * sola pasada con el gateway caído devolvería la tarjeta al nombre del portal.
     */
    private fun nombreCanonico(nuevo: String?, existente: ItemEntity?): String? =
        nuevo?.trim()?.takeIf { it.isNotEmpty() } ?: existente?.tituloCanonico
}
