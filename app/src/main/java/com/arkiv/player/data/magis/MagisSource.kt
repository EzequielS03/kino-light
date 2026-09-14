package com.arkiv.player.data.magis

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.ContentSource
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
 * The app talking directly to the Magis portal, with the same contract it used to ask the gateway
 * for. It's the port of `MagisAdapter` (`arkiv-api/src/arkiv_api/adapters/magis/adapter.py`): what
 * the server used to do between the portal and the app lives here — ranking search, building
 * chapters and crossing them with TMDB.
 *
 * What the gateway kept in Redis is kept in memory: it's lost when the process dies, which is fine
 * for a catalog and saves the portal's rate-limited calls while the app is alive.
 */
internal class MagisSource(
    private val catalog: MagisCatalog,
    private val vodResolver: MagisResolve,
    private val tmdb: TmdbApi,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ContentSource {

    override fun recognizes(ref: String): Boolean = MagisRef.decode(ref) != null

    private val lock = Mutex()
    private val searches = ExpiringCache<String, List<JSONObject>>(TTL_MS, cap = 32)
    private val chapters = ExpiringCache<String, PortalChapters>(TTL_MS, cap = 16)

    // --- search -------------------------------------------------------------

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = nowMs()
        emit(SearchEvent.SourceStart(FUENTE))
        val items = runCatching { sortedItems(ctx) }.getOrElse { e ->
            emit(SearchEvent.SourceError(FUENTE, e.message ?: "error de magis", nowMs() - t0, 0))
            emit(SearchEvent.Done(nowMs() - t0))
            return@flow
        }
        var count = 0
        for (item in items) {
            val result = resultFrom(item, ctx) ?: continue
            emit(SearchEvent.ResultEvent(FUENTE, result))
            count++
        }
        emit(SearchEvent.SourceDone(FUENTE, count, nowMs() - t0))
        emit(SearchEvent.Done(nowMs() - t0))
    }.flowOn(Dispatchers.IO)

    private suspend fun sortedItems(ctx: GatewaySearchQuery): List<JSONObject> {
        val query = portalQuery(ctx.q)
        // The key is what's asked OF THE PORTAL, not the whole `q`: this way two titles from the
        // same family share a pool instead of spending one call each.
        val pool = lock.withLock { searches[query.lowercase()] }
            ?: catalog.search(query).let { r ->
                val data = r.getOrNull() ?: throw GatewayException(explain("búsqueda", r))
                searchItems(data).also {
                    lock.withLock { searches[query.lowercase()] = it }
                }
            }

        // Sorted AFTER the cache and against the whole `q`: the pool is the family's, but each
        // request wants its own title on top.
        var items = sortBySimilarity(pool, titleForms(ctx))

        val isSeries = ctx.type == "tv" || ctx.type == "anime"
        if (isSeries && ctx.season > 0) {
            // With a requested season it's filtered down to that one; if NONE matches, all are
            // shown, which is better than leaving the tab empty over an unexpected name format.
            val matching = items.filter {
                it.optString("programType") !in MagisRef.SERIES ||
                    seasonFromName(itemTitle(it)) == ctx.season
            }
            if (matching.isNotEmpty()) items = matching
        }
        return sortSeasons(items)
    }

    /**
     * The known forms of what was asked: the title as-is and, if TMDB knows it, the ORIGINAL. The
     * portal keeps a lot of international content only under its English title, so without the
     * original there's no way to recognize it from a Spanish search. It's a HELP, not a
     * requirement: if TMDB isn't available or fails, it sorts with what there is.
     */
    private suspend fun titleForms(ctx: GatewaySearchQuery): List<String> {
        if (ctx.tmdbId <= 0) return listOf(ctx.q)
        val type = if (ctx.type == "movie") "movie" else "tv"
        val original = runCatching { tmdb.detail(type, ctx.tmdbId)?.originalTitle }.getOrNull()
        return if (original.isNullOrBlank() || original == ctx.q) listOf(ctx.q)
        else listOf(ctx.q, original)
    }

    private fun resultFrom(item: JSONObject, ctx: GatewaySearchQuery): GatewayResult? {
        val contentId = item.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val programType = item.optString("programType").ifBlank { "movie" }
        val title = itemTitle(item)
        val season =
            if (programType in MagisRef.SERIES) seasonFromName(title) else ctx.season
        val episodeCount = item.opt("volumnCount")?.toString()?.toIntOrNull()
            ?: item.opt("updateCount")?.toString()?.toIntOrNull()
            ?: 0
        return GatewayResult(
            source = FUENTE,
            title = title.ifBlank { contentId },
            ref = MagisRef(contentId, programType, ctx.episode).encode(),
            kind = ctx.type,
            year = itemYear(item),
            season = season,
            episode = ctx.episode,
            extra = mapOf(
                "content_id" to contentId,
                "program_type" to programType,
                "episode_count" to episodeCount.toString(),
            ) + itemImages(item),
        )
    }

    // --- playback ---------------------------------------------------------

    override suspend fun resolve(ref: String): GatewayPlayable {
        val magis = MagisRef.decode(ref)
            ?: throw GatewayException("ese ref no es de magis: no se puede reproducir")

        // A series' contentId is NOT playable (`startPlayVOD` on it returns `节目不存在`): its
        // chapters have to be listed and one played.
        val chapter = if (magis.isSeries) chapterFrom(magis) else null
        val r = vodResolver.resolveVod(
            contentId = chapter?.optString("contentId")?.takeIf { it.isNotBlank() } ?: magis.contentId,
            seriesContentId = if (chapter != null) magis.contentId else null,
        )
        val p = r.getOrNull() ?: throw GatewayException(explain("reproducción", r))
        return GatewayPlayable(
            kind = FUENTE,
            url = p.url,
            headers = p.headers,
            mime = p.mime,
            // For a chapter, the duration the list declares wins (already in memory): the portal
            // sends it empty for almost every series, and there it falls to 0 and the player fills
            // it in by demuxing.
            durationMs = if (chapter != null) {
                portalDurationMs(chapter.opt("duration"))
            } else {
                p.durationMs
            },
            videoCodec = p.videoCodec,
            container = p.container,
            subtitles = p.subtitles.map { GatewaySubtitle(it.lang, it.url, it.format) },
        )
    }

    /** The requested chapter, raw as the portal gives it (its `contentId` and duration come from there). */
    private suspend fun chapterFrom(magis: MagisRef): JSONObject {
        val items = portalChapters(magis.contentId).items
        if (items.isEmpty()) throw GatewayException("la serie ${magis.contentId} vino sin capítulos")
        if (magis.episode <= 0) return items.first()
        return items.firstOrNull {
            it.opt("seriesNumber")?.toString()?.trim() == magis.episode.toString()
        } ?: throw GatewayException("la serie no tiene el capítulo ${magis.episode}")
    }

    // --- chapters ------------------------------------------------------------

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
        val magis = MagisRef.decode(ref)
            ?: throw GatewayException("ese ref no es de magis: no se pueden listar capítulos")
        val raw = portalChapters(magis.contentId)
        val (extra, seriesFromTmdb) = enrich(raw)

        val episodes = raw.items.mapNotNull { ep ->
            val number = ep.opt("seriesNumber")?.toString()?.toIntOrNull() ?: 0
            val fromTmdb = extra[number]
            GatewayEpisode(
                number = number,
                title = ep.optString("name").ifBlank { "Capítulo $number" },
                // The ref points at the SERIES plus the number: whoever plays it looks the chapter
                // back up in the list, which is already in memory by then.
                ref = MagisRef(magis.contentId, "teleplay", number).encode(),
                still = fromTmdb?.still,
                tmdbTitle = fromTmdb?.title,
                overview = fromTmdb?.overview,
            )
        }

        // The `series` block travels WHENEVER the portal gave an imdb, even if enrichment didn't
        // come out: with the imdb the app can resolve the series on its own.
        val series = if (IMDB.matches(raw.imdb)) {
            GatewaySerie(
                imdbId = raw.imdb,
                tmdbId = seriesFromTmdb?.tmdbId ?: 0,
                seasonNumber = raw.season ?: 0,
                // The canonical name only goes if TMDB gave it: blank, the library would adopt an
                // empty name and the card would be left with no text.
                title = seriesFromTmdb?.title.orEmpty(),
                posterUrl = seriesFromTmdb?.posterUrl.orEmpty(),
                backdropUrl = seriesFromTmdb?.backdropUrl.orEmpty(),
            )
        } else {
            null
        }
        return episodes to series
    }

    /** What a series' detail gives about its season. */
    private data class PortalChapters(
        val items: List<JSONObject>,
        /** The detail's `keyWords` IS the series' IMDb id. */
        val imdb: String,
        /** `null` = it's not known which season this is (different from "it's the 1st"). */
        val season: Int?,
        /** How many chapters the portal SAYS the season has (can be more than the published ones). */
        val declared: Int?,
    )

    private suspend fun portalChapters(seriesId: String): PortalChapters {
        lock.withLock { chapters[seriesId] }?.let { return it }
        val r = catalog.detail(seriesId, type = "0")
        val data = r.getOrNull()?.optJSONObject("assetData")
            ?: throw GatewayException(explain("capítulos", r))

        val items = mutableListOf<JSONObject>()
        data.optJSONArray("simpleProgramList")?.forEachObject { items.add(it) }

        val seasons = data.optJSONArray("sameSeasonSeriesList")
        var number: Int? = null
        seasons?.forEachObject { t ->
            if (t.optString("contentId") == seriesId) {
                number = t.opt("seasonNumber")?.toString()?.toIntOrNull()
            }
        }
        // A SINGLE-season series doesn't show up in its own list: the portal sends an empty
        // `sameSeasonSeriesList`. That's not "which season is unknown", it's "it's the 1st", and
        // reading it as unknown turned off the ENTIRE enrichment with the imdb sitting right there
        // (measured on Dragon Ball: keyWords=tt0088509, 153 chapters, sameSeasonSeriesList=[]).
        //
        // Only when the list comes EMPTY: if it carries seasons and ours isn't in it, that IS
        // unknown, and guessing 1 would enrich with another season's chapters.
        if (number == null && (seasons == null || seasons.length() == 0)) number = 1

        val output = PortalChapters(
            items = items,
            imdb = data.optString("keyWords"),
            season = number,
            declared = data.opt("volumnCount")?.toString()?.toIntOrNull(),
        )
        // Only saved if there are chapters: caching an empty list over a transient failure would
        // leave the series with no chapters for hours.
        if (items.isNotEmpty()) lock.withLock { chapters[seriesId] = output }
        return output
    }

    private data class FromTmdb(val still: String?, val title: String?, val overview: String?)

    /**
     * Each chapter's image, name and synopsis according to TMDB. The portal doesn't have them (its
     * per-chapter `posterList` always arrives empty) but it publishes the series' IMDb id, and with
     * that the match is exact.
     *
     * Best-effort end to end: any failure returns empty instead of sinking the listing, which is
     * the call that gets things playing.
     */
    private suspend fun enrich(
        raw: PortalChapters,
    ): Pair<Map<Int, FromTmdb>, TmdbSeriesForMagis?> {
        val season = raw.season
        if (!IMDB.matches(raw.imdb) || season == null) return emptyMap<Int, FromTmdb>() to null
        return runCatching {
            val series = tmdb.seriesByImdb(raw.imdb)
                ?: return@runCatching emptyMap<Int, FromTmdb>() to null
            val fromTmdb = tmdb.seasonEpisodes(series.tmdbId, season)
                ?: return@runCatching emptyMap<Int, FromTmdb>() to series.forMagis()

            // Numbering guard: the total the portal DECLARES for the season gets compared against
            // what that season has in TMDB, and it only enriches if they match.
            //
            //  - One Piece "Temp.1" declares 8 chapters and TMDB's real season 1 has 61: they don't
            //    match, no enrichment happens. The portal split the series differently, and
            //    crossing by number would put stills that don't correspond — worse than none,
            //    because it goes unnoticed.
            //  - A season that's airing declares 20 and the portal published 8: 20 DOES match
            //    TMDB, so those 8 get enriched. Comparing published counts (8 against 20) the
            //    guard would block exactly what just premiered, which is what gets watched most.
            //
            // This calculation is the kind someone will want to "simplify" to comparing real
            // counts; it isn't noticed as wrong until a still shows up that doesn't belong.
            val expected = raw.declared?.takeIf { it > 0 } ?: raw.items.size
            if (expected != fromTmdb.size) return@runCatching emptyMap<Int, FromTmdb>() to series.forMagis()

            val rows = fromTmdb.associate { c ->
                c.episode to FromTmdb(
                    still = c.stillUrl.takeIf { it.isNotBlank() },
                    title = c.name.takeIf { it.isNotBlank() },
                    overview = c.overview.takeIf { it.isNotBlank() },
                )
            }.filterValues { it.still != null || it.title != null || it.overview != null }

            // English fallback ONLY for empty synopses: TMDB returns an empty `overview` in es-MX
            // very often (the name usually comes in fine). It has its own catch: it's an extra on
            // top of ALREADY resolved data, so if it fails, what got built in Spanish is kept.
            val missing = rows.filterValues { it.overview == null }.keys
            if (missing.isEmpty()) return@runCatching rows to series.forMagis()
            val inEnglish = runCatching {
                tmdb.seasonEpisodes(series.tmdbId, season, languageOverride = "en-US").orEmpty()
            }.getOrDefault(emptyList())
            val completed = rows.toMutableMap()
            inEnglish.forEach { c ->
                if (c.episode in missing && c.overview.isNotBlank()) {
                    completed[c.episode] = completed.getValue(c.episode).copy(overview = c.overview)
                }
            }
            completed.toMap() to series.forMagis()
        }.getOrDefault(emptyMap<Int, FromTmdb>() to null)
    }

    /** What of TMDB gets used here, without dragging along the whole model. */
    internal data class TmdbSeriesForMagis(
        val tmdbId: Int,
        val title: String,
        val posterUrl: String,
        val backdropUrl: String,
    )

    private fun com.arkiv.player.data.catalog.TmdbSeriesByImdb.forMagis() =
        TmdbSeriesForMagis(tmdbId, title, posterUrl, backdropUrl)

    private fun explain(what: String, r: MagisResult<*>): String = when (r) {
        is MagisResult.PortalError -> "magis rechazó la $what (${r.code}${r.msg?.let { ": $it" }.orEmpty()})"
        is MagisResult.RedError -> "no se pudo hablar con magis (${r.cause.message})"
        is MagisResult.Ok -> "magis devolvió una $what sin datos"
    }

    private companion object {
        const val FUENTE = "magis"
        const val TTL_MS = 6 * 60 * 60 * 1000L
        val IMDB = Regex("""tt\d{7,}""")
    }
}

/** In-memory cache with expiry and an entry cap (the oldest goes first). */
internal class ExpiringCache<K, V>(private val ttlMs: Long, private val cap: Int) {
    private val entries = LinkedHashMap<K, Pair<Long, V>>()

    operator fun get(key: K): V? {
        val (expiresAt, value) = entries[key] ?: return null
        if (System.currentTimeMillis() >= expiresAt) {
            entries.remove(key)
            return null
        }
        return value
    }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = (System.currentTimeMillis() + ttlMs) to value
        while (entries.size > cap) entries.remove(entries.keys.first())
    }
}
