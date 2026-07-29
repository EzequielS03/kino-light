# Web Series Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the app's search screen, when the user searches a series/anime **without** picking a season/episode, show the whole series from OUR backend mirror as a **pack** ("PACK · N episodios") that can be added to the library in one tap — mirroring how torrent packs already behave. When the user **does** pick a season/episode, show that specific episode from the mirror, ready to play.

**Architecture:** The backend already stores every episode of a crawled series in `web_sources` and `/api/title/<slug>` already returns all of them, so **no backend work is needed**. On the app side, `MirrorApiClient.titleWebSources(slug)` already fetches the full list; what's missing is (a) a pack model that groups those episodes by site, (b) a `PlaySource.WebPack` variant rendered with the existing PACK badge pattern, (c) wiring the search screen's WEB branch to the mirror (pack when no episode, single episode when there is one — mirroring the TORRENT branch's existing shape in `SearchViewModel.runSourceSearch`), and (d) a click handler that adds all episodes via the already-existing `ArkivRepository.addWebSeriesEpisode`.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines. Repo `/Users/cristian/archive`, branch `feat/torrents-via-backend-api`.

## Global Constraints

- **No backend changes.** `/api/title/<slug>` already returns the full `web_sources` array; `MirrorApiClient.titleWebSources(slug)` already parses it.
- **Reuse, don't reinvent:** `ArkivRepository.addWebSeriesEpisode(seriesId, showTitle, posterUrl, season, episode, episodeName, pageUrl)` (`ArkivRepository.kt:522`) already creates/updates a `web:series:<id>` library item and upserts one episode — adding a whole series is calling it once per episode. The PACK badge styling already exists in `PlaySources.kt` (Torrent branch, ~line 111).
- **Anime vs series id convention (must match `SearchPlayback.playWeb` exactly, `SearchPlayback.kt:169-175`):** for anime the series id is `"anilist" + anilistId` and **season is forced to 1** (anime uses absolute numbering); for TMDB series the id is `seriesIdFor(card, detail)` = `detail.imdbId` or `"tmdb"+id`, using the real season. Deviating would create duplicate library items for the same show.
- Test command: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`, compile check `./gradlew :app:compileDebugKotlin`. No Claude co-author line; identity already `lordmacu`.
- **Coverage caveat (not a blocker, but shapes UX):** only ~46 of 1,943 titles are crawled so far, so most searches will find no mirror pack and must fall back to the existing live scrape. The fallback must stay intact.

---

## File Structure

- Modify `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt` — add `MirrorWebPack`.
- Modify `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` — add `seriesWebPacks(...)`.
- Modify `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt` — add `PlaySource.WebPack` + its `SourceRow` branch.
- Modify `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt` — wire the WEB branch to the mirror.
- Modify `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt` — add `addWholeWebSeries(...)`.
- Modify `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt` — handle a `WebPack` click.
- Tests under `app/src/test/java/com/arkiv/player/data/catalog/mirror/`.

---

## Task 1: `MirrorWebPack` model + `TorrentSearchApi.seriesWebPacks`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt`

**Interfaces:**
- Consumes: `MirrorWebSource` (existing), `MirrorApiClient.resolveSlug(tmdbId, kind, titleFallback)` and `.titleWebSources(slug)` (existing), `ContentType` (existing).
- Produces:
  - `data class MirrorWebPack(val siteId: String, val showTitle: String, val episodes: List<MirrorWebSource>)` with `val episodeCount: Int get() = episodes.size` and `val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()`.
  - `MirrorWebPack.groupBySite(showTitle: String, sources: List<MirrorWebSource>): List<MirrorWebPack>` — companion function; groups by `siteId`, sorts each pack's episodes by `(season, episode)`, and orders packs by `episodeCount` descending (most complete site first).
  - `TorrentSearchApi.seriesWebPacks(titles: List<String>, kind: ContentType, tmdbId: Int? = null, showTitle: String): List<MirrorWebPack>` — resolves the slug, fetches all web sources, returns the grouped packs; empty list when there's no slug or no sources.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorWebPackTest {
    private fun w(site: String, season: Int, episode: Int) =
        MirrorWebSource(siteId = site, pageUrl = "https://$site/s$season/e$episode", season = season,
            episode = episode, name = "T${season}E$episode", quality = "", langNorm = "latino")

    @Test fun `agrupa por sitio y cuenta episodios`() {
        val sources = listOf(
            w("serieskao", 1, 2), w("serieskao", 1, 1), w("serieskao", 2, 1),
            w("sololatino", 1, 1),
        )
        val packs = MirrorWebPack.groupBySite("Naruto", sources)
        assertEquals(2, packs.size)
        // el sitio con MAS episodios va primero
        assertEquals("serieskao", packs[0].siteId)
        assertEquals(3, packs[0].episodeCount)
        assertEquals("Naruto", packs[0].showTitle)
        assertEquals(listOf(1, 2), packs[0].seasons)
        assertEquals(1, packs[1].episodeCount)
    }

    @Test fun `ordena los episodios de cada pack por temporada y numero`() {
        val sources = listOf(w("serieskao", 2, 1), w("serieskao", 1, 10), w("serieskao", 1, 2))
        val packs = MirrorWebPack.groupBySite("X", sources)
        assertEquals(
            listOf(1 to 2, 1 to 10, 2 to 1),
            packs[0].episodes.map { it.season to it.episode },
        )
    }

    @Test fun `sin fuentes devuelve lista vacia`() {
        assertEquals(emptyList<MirrorWebPack>(), MirrorWebPack.groupBySite("X", emptyList()))
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorWebPackTest"`

- [ ] **Step 3: Implement**

Append to `WebMirrorModels.kt`:

```kotlin
/**
 * La serie COMPLETA de un sitio, tal como la tiene nuestro backend: sirve para ofrecerla como
 * "paquete" (agregar todos los capitulos de una) igual que se hace con los packs de torrent.
 */
data class MirrorWebPack(
    val siteId: String,
    val showTitle: String,
    val episodes: List<MirrorWebSource>,
) {
    val episodeCount: Int get() = episodes.size
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    companion object {
        /** Un pack por sitio, con sus episodios ordenados y el sitio mas completo primero. */
        fun groupBySite(showTitle: String, sources: List<MirrorWebSource>): List<MirrorWebPack> =
            sources.groupBy { it.siteId }
                .map { (site, eps) ->
                    MirrorWebPack(site, showTitle, eps.sortedWith(compareBy({ it.season }, { it.episode })))
                }
                .sortedByDescending { it.episodeCount }
    }
}
```

Append to `TorrentSearchApi.kt` (near `searchEpisodeWeb`/`searchAnimeWeb`; match the file's existing fully-qualified-name style):

```kotlin
    /**
     * La serie COMPLETA desde el mirror, agrupada por sitio, para ofrecerla como paquete cuando el
     * usuario busca la serie sin elegir capitulo. Vacio si el titulo aun no esta crawleado.
     */
    suspend fun seriesWebPacks(
        titles: List<String>,
        kind: ContentType,
        tmdbId: Int? = null,
        showTitle: String,
    ): List<com.arkiv.player.data.catalog.mirror.MirrorWebPack> = withTimeoutOrNull(MIRROR_WEB_TIMEOUT_MS) {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, kind, ts.firstOrNull()) ?: return@withTimeoutOrNull emptyList()
        com.arkiv.player.data.catalog.mirror.MirrorWebPack.groupBySite(showTitle, mirror.titleWebSources(slug))
    } ?: emptyList()
```

(Verified: the constant is `private const val MIRROR_WEB_TIMEOUT_MS = 6_000L` at `TorrentSearchApi.kt:31` — reuse it, don't add a new literal. The snippet above deliberately mirrors the shape of the existing `searchEpisodeWeb`, which is: `= withTimeoutOrNull(MIRROR_WEB_TIMEOUT_MS) { ...; ?: return@withTimeoutOrNull emptyList(); ... } ?: emptyList()`.)

- [ ] **Step 4: Run to verify PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt
git commit -m "feat(web): MirrorWebPack (serie completa agrupada por sitio) + TorrentSearchApi.seriesWebPacks"
```

---

## Task 2: `PlaySource.WebPack` variant + PACK badge in `SourceRow`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`

**Interfaces:**
- Consumes: `MirrorWebPack` (Task 1).
- Produces: `PlaySource.WebPack(val pack: MirrorWebPack) : PlaySource` and its rendering branch in `SourceRow`.

- [ ] **Step 1: Read the current file first**

Read `PlaySources.kt` fully. It has a `sealed interface PlaySource` with `Torrent`/`Archive`/`Web`, and `SourceRow` has TWO `when(source)` blocks: one picking `(tag, tagColor)` (~line 85) and one rendering the detail text (~line 100). Both must gain a `WebPack` branch or the code won't compile (exhaustive `when` on a sealed type).

**No unit test for this task** — it's Compose UI rendering, and this codebase has no Compose UI test infrastructure (consistent with how `Archive`/`Web` branches were added). Verification is a clean compile plus the reviewer reading the branch.

- [ ] **Step 2: Add the variant and both branches**

In the sealed interface:
```kotlin
    data class WebPack(val pack: com.arkiv.player.data.catalog.mirror.MirrorWebPack) : PlaySource
```

In the tag `when` (same style as the others):
```kotlin
        is PlaySource.WebPack -> "WEB" to Color(0xFFB39DDB)
```

In the detail `when` (mirror the existing Torrent PACK badge styling — the orange `Color(0xFFFFB74D)` line):
```kotlin
                is PlaySource.WebPack -> {
                    val p = source.pack
                    Text(p.showTitle, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "PACK · ${p.episodeCount} episodios" +
                            if (p.seasons.size > 1) "  ·  ${p.seasons.size} temporadas" else "",
                        color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall,
                    )
                    Text(p.siteId, color = Color(0xFFB39DDB), style = MaterialTheme.typography.labelSmall)
                }
```

- [ ] **Step 3: Verify clean compile**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

**Verified: adding the variant WILL break 4 other files** that have an exhaustive `when` over `PlaySource` (a sealed interface). You must add a `WebPack` branch to each:
- `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`
- `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt`
- `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt`
- `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`

For THIS task, give each the **minimal safe branch** so it compiles without changing behavior — these screens don't produce `WebPack` results (only the search screen will, in Task 3), so the branch is unreachable there. Read each site and pick the shape that fits its `when` (e.g. a no-op, or the same handling as `Web` if the surrounding code returns a value). `SearchScreen.kt`'s `playResult` gets its REAL implementation in Task 4 — a minimal placeholder here is fine and expected. Report exactly which files/branches you touched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt
git commit -m "feat(web): PlaySource.WebPack + badge PACK · N episodios en SourceRow"
```

---

## Task 3: Wire the search screen's WEB branch to the mirror

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`

**Interfaces:**
- Consumes: `TorrentSearchApi.seriesWebPacks` (Task 1), `TorrentSearchApi.searchEpisodeWeb`/`searchAnimeWeb` (already exist), `PlaySource.WebPack` (Task 2).
- Produces: search results that include mirror-backed web packs (no episode chosen) or mirror-backed web episodes (episode chosen), falling back to the existing live scrape when the mirror has nothing.

- [ ] **Step 1: Read the current `runSourceSearch` fully**

Read `SearchViewModel.kt`'s `runSourceSearch(season: Int?, episode: Int?)`. Note its existing structure: it resolves `titles`, `d: TmdbDetail?`, `show: AnimeShow?`, defines `fun append(new: List<PlaySource>)`, then `launch`es one coroutine per source type. The TORRENT branch already switches on `episode != null` — you are giving the WEB branch the same shape. Find the WEB `launch` block (it builds a `SearchContext` and calls `webSourceEngine.searchFlow(ctx)`).

**No unit test** — `SearchViewModel` has no existing unit tests (it needs the full `AppGraph`); consistent with how the other branches landed. Verification is a clean compile + the reviewer reading the logic.

- [ ] **Step 2: Replace ONLY the WEB `launch` block's body** with mirror-first + live fallback:

```kotlin
            launch {
                // MIRROR primero (nuestro backend, ya crawleado): sin capitulo elegido ofrecemos la
                // serie completa como PACK; con capitulo, ese capitulo puntual. Si el titulo aun no
                // esta crawleado, caemos al scraping en vivo de siempre.
                val mirror: List<PlaySource> = when {
                    card.kind == "movie" -> emptyList()
                    episode != null -> {
                        val eps = if (card.kind == "anime") {
                            val absolute = show?.let { s -> runCatching { animeSourceProvider.episodeSourcesWeb(s, episode) }.getOrDefault(emptyList()) } ?: emptyList()
                            absolute
                        } else if (season != null) {
                            runCatching { torrentSearchApi.searchEpisodeWeb(titles, season, episode, tmdbId = d?.id) }.getOrDefault(emptyList())
                        } else emptyList()
                        eps.map { PlaySource.Web(it) }
                    }
                    else -> {
                        val kindForMirror = if (card.kind == "anime") ContentType.ANIME else ContentType.TV
                        runCatching {
                            torrentSearchApi.seriesWebPacks(titles, kindForMirror, tmdbId = d?.id, showTitle = card.title)
                        }.getOrDefault(emptyList()).map { PlaySource.WebPack(it) }
                    }
                }
                if (mirror.isNotEmpty()) {
                    append(mirror)
                    _loadingWeb.value = false
                } else {
                    val ctx = SearchContext(
                        titles = titles,
                        type = when (card.kind) {
                            "movie" -> ContentType.MOVIE
                            "anime" -> ContentType.ANIME
                            else -> ContentType.TV
                        },
                        season = season ?: 0,
                        episode = episode ?: 0,
                        year = d?.year ?: "",
                    )
                    runCatching { webSourceEngine.searchFlow(ctx).collect { chunk -> append(chunk.map { PlaySource.Web(it) }) } }
                    _loadingWeb.value = false
                }
            }
```

**Adapt to the REAL current code:** the snippet above assumes the block's existing `SearchContext` construction and `_loadingWeb.value = false` placement — read what's actually there and preserve its exact shape (field names, `year` source, etc.), changing only the mirror-first/fallback structure. Add any missing imports (`ContentType`, `SearchContext` are likely already imported; `PlaySource.WebPack` is same-package-ish — check).

- [ ] **Step 3: Verify clean compile + full test suite**

Run `./gradlew :app:compileDebugKotlin` then `./gradlew :app:testDebugUnitTest` → both BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt
git commit -m "feat(web): el buscador consulta el mirror (pack de serie sin capitulo, capitulo puntual con capitulo)"
```

---

## Task 4: Add the whole series when the user taps a pack

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`

**Interfaces:**
- Consumes: `MirrorWebPack` (Task 1), `ArkivRepository.addWebSeriesEpisode(...)` (existing, `ArkivRepository.kt:522`), `graph.repository.firstEpisodeId(itemId)` (existing — used by the torrent pack save flow; confirm its exact name by reading `SearchPlayback.kt`'s pack-saving function).
- Produces: `SearchPlayback.addWholeWebSeries(pack, card, detail, animeShow, resultPoster): PlaybackResult` and a `PlaySource.WebPack` branch in `SearchScreen.playResult`.

- [ ] **Step 1: Read both files first**

In `SearchPlayback.kt` read `playWeb(...)` (~line 159) for the exact id conventions, and the torrent-pack save function right below it (its KDoc mentions returning an itemId and `firstEpisodeId`) — reuse that same "return the first episode so playback can start" convention.

**No unit test** — same rationale as Tasks 2-3 (these are graph/repository-bound UI paths with no existing test harness). Verification: clean compile + reviewer reading the id conventions against `playWeb`.

- [ ] **Step 2: Add `addWholeWebSeries` to `SearchPlayback.kt`**

```kotlin
    /**
     * Agrega la serie COMPLETA de un pack web: un episodio de biblioteca por cada capitulo del
     * mirror. Reusa las MISMAS convenciones de id que [playWeb] (anime -> "anilist<id>" con season
     * fijo en 1 por la numeracion absoluta; series TMDB -> imdb/tmdb con la season real) para no
     * crear un item duplicado del mismo show. Devuelve el primer episodio para poder reproducir ya.
     */
    suspend fun addWholeWebSeries(
        pack: com.arkiv.player.data.catalog.mirror.MirrorWebPack,
        card: TitleCard,
        detail: TmdbDetail?,
        animeShow: AnimeShow?,
        resultPoster: String,
    ): PlaybackResult {
        val isAnime = card.kind == "anime"
        val seriesId = if (isAnime) "anilist${card.anilistId ?: animeShow?.id}" else seriesIdFor(card, detail)
        var first: String? = null
        for (ep in pack.episodes) {
            val season = if (isAnime) 1 else ep.season
            val name = ep.name.ifBlank { "Ep ${ep.episode}" }
            val id = graph.repository.addWebSeriesEpisode(
                seriesId, pack.showTitle, resultPoster, season, ep.episode, name, ep.pageUrl,
            )
            if (first == null) first = id
        }
        return if (first != null) PlaybackResult.Ready(first)
               else PlaybackResult.Failed("No se pudo agregar la serie")
    }
```

- [ ] **Step 3: Handle the click in `SearchScreen.kt`**

Find `fun playResult(source: PlaySource)` (~line 183) — currently a `when` over the 3 variants (it will already be failing to compile after Task 2 unless a branch was added there; if a placeholder branch was added, replace it now with the real one):

```kotlin
        is PlaySource.WebPack -> addWholeSeries(source.pack)
```

and add the `addWholeSeries` helper next to the existing `playWebResult`, following that function's exact shape (it launches a coroutine, sets `preparing`, calls into `SearchPlayback`, and navigates on `PlaybackResult.Ready`). Read `playWebResult` and copy its structure — do not invent a different navigation/error pattern.

- [ ] **Step 4: Verify clean compile + full test suite**

Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt
git commit -m "feat(web): tocar un pack web agrega la serie completa a la biblioteca"
```

---

## Self-Review

- **Spec coverage:** searches our backend ✅ (`titleWebSources` via `seriesWebPacks`); series listed as a pack when no episode chosen ✅ (Task 1+2+3); specific episode playable when season/episode given ✅ (Task 3 wires the already-built `searchEpisodeWeb`/`episodeSourcesWeb`); add whole series ✅ (Task 4 reusing `addWebSeriesEpisode`).
- **Placeholders:** Tasks 2-4 instruct "read the real file and adapt" for the UI wiring rather than pasting exact final code, because those files were read only in the regions relevant to this feature; every task states exactly what to look for and what convention to preserve. All model/API code (Task 1) is complete and exact.
- **Type consistency:** `MirrorWebPack(siteId, showTitle, episodes)` with `episodeCount`/`seasons` used identically in Tasks 1→2→4; `seriesWebPacks(...) -> List<MirrorWebPack>` consumed once (Task 3) and mapped to `PlaySource.WebPack`, which Task 2 defines and Task 4 destructures via `source.pack`.

## Open follow-ups (out of scope v1)
- Coverage: only crawled titles produce a pack; the rest fall back to live scrape (by design).
- No dedup across sites — each site is its own pack, deliberately (they're alternative hosts and the user may prefer one).
- Adding a pack adds ALL its episodes; no per-season selection UI (torrent packs have a picker dialog — a per-season picker for web could come later).
