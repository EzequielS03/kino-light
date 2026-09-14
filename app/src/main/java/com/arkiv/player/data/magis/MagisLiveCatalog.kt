package com.arkiv.player.data.magis

import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Live TV categories and channels, straight from the portal. Implements the same
 * [LiveCatalogGateway] the gateway used to provide, so the live screens don't change.
 *
 * The sub-project's plan didn't include this (only channel resolution), but without the listing
 * the live section would keep asking the server for the catalog — exactly what this branch removes.
 *
 * There's an in-memory cache because the portal has a minimum pace between calls and the full list
 * is 3 pages: with no cache, opening the channel drawer would cost ~1.5s every time. It's lost when
 * the process dies, which is exactly what's wanted (resolution's `main_addr` isn't cached, but the
 * catalog can be).
 */
internal class MagisLiveCatalog(
    private val catalog: MagisCatalog,
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : LiveCatalogGateway {

    private val lock = Mutex()
    private var categoriesCache: List<PortalCategory> = emptyList()
    private var categoriesExpireAt = 0L
    private val channelsCache = mutableMapOf<Int, Pair<Long, List<LiveChannel>>>()
    private val trees = ExpiringCache<String, List<CatalogSection>>(TTL_MS, cap = 8)

    /** A category as the portal understands it, with the adult flag it doesn't carry. */
    private data class PortalCategory(val id: Int, val name: String, val isAdult: Boolean)

    override suspend fun categories(includeAdults: Boolean): List<LiveCategory> =
        allCategories()
            .filter { includeAdults || !it.isAdult }
            .map { LiveCategory(id = it.id, name = it.name) }

    /**
     * ALL of the category's channels, not the first page. Measured against the portal on
     * 2026-08-14: the "Todos" category (76182) returns 500 per page and has THREE pages with no
     * repeated code — 1040 real channels. Asking for a single one, the guide, the drawer and
     * zapping worked with 48% of the catalog and search couldn't find what never loaded.
     */
    override suspend fun channels(category: Int): List<LiveChannel> {
        lock.withLock {
            channelsCache[category]?.takeIf { nowMs() < it.first }?.let { return it.second }
        }
        val isAdult = allCategories().any { it.id == category && it.isAdult }
        val all = mutableListOf<LiveChannel>()
        val seen = mutableSetOf<String>()
        for (page in 1..MAX_PAGES) {
            val batch = onePage(category, page, isAdult) ?: break
            val new = batch.filter { seen.add(it.code) }
            all.addAll(new)
            // An incomplete page is the last one. Without this cutoff it would keep asking up to
            // the cap, and every extra request spends a turn of the portal's rate limit for nothing.
            if (batch.size < PAGE_SIZE || new.isEmpty()) break
        }
        if (all.isNotEmpty()) {
            lock.withLock { channelsCache[category] = (nowMs() + TTL_MS) to all }
        }
        return all
    }

    /**
     * The portal has NO programming: what it publishes as "EPG" are sports endpoints, not a guide
     * (checked; it's outside the sub-project's scope). It answers "I don't know of any", which is
     * what the UI already knows how to show, instead of making up schedules.
     */
    override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> =
        emptyMap<String, List<LiveProgram>>() to codes

    /**
     * A catalog root's sections (movies, series, kids, anime, 18+), each with its first items:
     * `getNextColumns` brings them in `assetList`, so a second call per section isn't needed.
     *
     * The roots' codes were found by trying them against the portal: the "obvious" ones
     * (`masnew_vod`, `masnew_movie`, `masnew_home`, `masnew`) get rejected.
     */
    suspend fun tree(root: String, includeAdults: Boolean = false): List<CatalogSection> {
        val code = ROOTS[root] ?: throw IllegalArgumentException("no such root: $root")
        val isAdultRoot = root in ADULT_ROOTS
        // Same as the channels' 18+ category: the default has to be the safe one, so no path that
        // forgets the parameter ends up serving it.
        require(!isAdultRoot || includeAdults) { "the 18+ section has to be requested explicitly" }

        lock.withLock { trees[root]?.let { return it } }
        val r = catalog.nextColumns(code, pageSize = 60)
        val columns = r.getOrNull()?.optJSONArray("recommendList") ?: return emptyList()

        val sections = mutableListOf<CatalogSection>()
        columns.forEachObject { c ->
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@forEachObject
            val items = mutableListOf<CatalogItem>()
            c.optJSONArray("assetList")?.forEachObject { a ->
                val id = a.optString("contentId").takeIf { it.isNotBlank() } ?: return@forEachObject
                val type = a.optString("programType").ifBlank { "movie" }
                items.add(
                    CatalogItem(
                        id = id,
                        title = a.optString("name"),
                        poster = logoFrom(a),
                        durationS = a.opt("duration")?.toString()?.toIntOrNull() ?: 0,
                        // Marked ITEM BY ITEM and not only on the section: the item travels alone
                        // up to the player, and there the "this doesn't get logged in history"
                        // rule has to be applicable without knowing which section it came from.
                        adult = isAdultRoot,
                        // This used to be signed by the gateway and expire at 24h; now it's a
                        // local descriptor, so the section is always usable for playback.
                        ref = MagisRef(id, type, 0).encode(),
                        type = type,
                    ),
                )
            }
            sections.add(
                CatalogSection(
                    id = c.opt("columnId")?.toString()?.toIntOrNull() ?: 0,
                    name = name,
                    adult = isAdultRoot,
                    items = items,
                ),
            )
        }
        if (sections.isNotEmpty()) lock.withLock { trees[root] = sections }
        return sections
    }

    private suspend fun allCategories(): List<PortalCategory> {
        lock.withLock {
            if (categoriesCache.isNotEmpty() && nowMs() < categoriesExpireAt) return categoriesCache
        }
        // `pageSize` 200 and not 30: with 30 the portal returned exactly 30 —the round number was
        // the cutoff, not the total— and EIGHT whole categories were lost (the real count is 38).
        val r = catalog.nextColumns(LIVE_ROOT, pageSize = 200)
        val list = r.getOrNull()?.optJSONArray("recommendList") ?: return emptyList()
        val output = mutableListOf<PortalCategory>()
        list.forEachObject { c ->
            val id = c.opt("columnId")?.toString()?.toIntOrNull() ?: return@forEachObject
            val name = categoryName(c.optString("name"))
            output.add(PortalCategory(id = id, name = name, isAdult = isAdultCategory(name)))
        }
        if (output.isNotEmpty()) {
            lock.withLock {
                categoriesCache = output
                categoriesExpireAt = nowMs() + TTL_MS
            }
        }
        return output
    }

    /** `null` = the portal didn't answer this page (different from "the category has no more"). */
    private suspend fun onePage(
        columnId: Int,
        page: Int,
        isAdult: Boolean,
    ): List<LiveChannel>? {
        val sessionResult = session.ensureSession()
        if (sessionResult !is MagisResult.Ok) return null
        val r = session.withValidSession {
            portal.call(
                path = "v6/getLiveData",
                bean = mapOf(
                    "columnId" to columnId,
                    "pageNum" to page,
                    "pageSize" to PAGE_SIZE,
                    "dataVersion" to "",
                    "expireTimeStr" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val list = r.getOrNull()?.optJSONArray("channelList") ?: return null
        val output = mutableListOf<LiveChannel>()
        list.forEachObject { c ->
            val code = c.optString("channelCode").takeIf { it.isNotBlank() } ?: return@forEachObject
            output.add(
                LiveChannel(
                    code = code,
                    name = c.optString("name"),
                    number = c.opt("channelNumber")?.toString()?.toIntOrNull() ?: 0,
                    logo = logoFrom(c),
                    // Marked on the CHANNEL and not only on the category: the channel travels
                    // alone up to the player (zapping, deep link, recents) and there's no category
                    // at hand there anymore.
                    adult = isAdult,
                ),
            )
        }
        return output
    }

    private companion object {
        const val LIVE_ROOT = "masnew_live"

        /** The VOD catalog's roots, with the codes the portal does accept. */
        val ROOTS = mapOf(
            "peliculas" to "masnew_movies",
            "series" to "masnew_series",
            "infantil" to "masnew_kids",
            "anime" to "masnew_anime",
            "adultos" to "masnew_adult",
        )
        val ADULT_ROOTS = setOf("adultos")
        const val PAGE_SIZE = 500
        const val MAX_PAGES = 6
        const val TTL_MS = 6 * 60 * 60 * 1000L

        /** The portal calls the all-channels category "ChannelList" — one of its own internal
         *  names, in English, that ended up straight on the screen. */
        val NAMES = mapOf("ChannelList" to "Todos")

        /** Recognized by NAME because it's the only thing the portal gives: there's no field that marks them. */
        val ADULT_NAMES = setOf("18+", "adultos", "adulto", "xxx", "+18")

        fun categoryName(name: String): String = NAMES[name] ?: name

        fun isAdultCategory(name: String): Boolean = name.trim().lowercase() in ADULT_NAMES

        /**
         * The channel's image in the REAL shape the portal sends (diagnosed in production): there's
         * NO `logo`/`icon`/`logoUrl` or `posterList[].url` — those fields were speculative and
         * never arrive. `posterList[]` DOES come with `fileType`/`fileUrl` (`fileType` is the only
         * reliable criterion, not order or size) and, as a fallback, a loose `posterUrl`.
         */
        fun logoFrom(channel: JSONObject): String? {
            channel.optJSONArray("posterList")?.forEachObject { p ->
                if (p.optString("fileType") != "icon") return@forEachObject
                p.optString("fileUrl").takeIf { it.isNotBlank() }?.let { return it }
            }
            return channel.optString("posterUrl").takeIf { it.isNotBlank() }
        }
    }
}
