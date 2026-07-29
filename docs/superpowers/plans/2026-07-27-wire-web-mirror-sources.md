# Wire Web Mirror Sources Into Episode Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the already-built but unused `MirrorApiClient.titleWebSources(slug)` / `MirrorWebMapper` into the TV-series and anime episode-playback flows, so the app actually uses our backend's crawled `web_sources` (fast, no on-device Cloudflare-solving) instead of always live-scraping. Matching strategy per content type follows the EXISTING, already-proven precedent from torrent matching (`MirrorFilter`): strict `season==requested && episode==requested` for TV series, tolerant `episode in {relative, absolute}` (no season check) for anime — this sidesteps the site-vs-TMDB season-numbering drift found during research (e.g. sololatino's own season boundaries for Naruto: 54/51/54/60 vs TMDB's canonical 52/52/54/62) without needing a separate TMDB-remapping layer, because it's the SAME strategy already battle-tested for anime torrents.

**Architecture:** A new `MirrorWebFilter` object (twin of `MirrorFilter`, no pack concept since `MirrorWebSource` has no `isPack`/`episodeEnd`) selects matching sources. Two new thin `TorrentSearchApi` methods (`searchEpisodeWeb`, `searchAnimeWeb`) mirror the existing `searchEpisode`/`searchAnime` orchestration (`resolveSlug` → fetch → filter → map), reusing the SAME resolved slug pattern. A new `AnimeSourceProvider.episodeSourcesWeb` mirrors `episodeSources`'s exact `showMeta`/`spec`-building so the anime screen doesn't duplicate that logic. Both screens (`CineDetailScreen.kt` for TV series, `AnimeShowDetailScreen.kt` for anime) get their existing "WEB" coroutine branch changed to try the mirror FIRST (fast) and only fall back to the existing live `webSourceEngine.searchFlow` scrape if the mirror has nothing for that episode — both paths funnel into the SAME `PlaySource.Web`/`WebResult` type, so **zero UI changes** are needed (`PlaySources.kt`'s `SourceRow` already renders `PlaySource.Web` generically regardless of where it came from).

**Tech Stack:** Kotlin, Jetpack Compose, coroutines/Flow. Repo `/Users/cristian/archive`, branch `feat/torrents-via-backend-api` (current branch — this work continues on it, no new branch needed unless the implementer finds one already in progress from a concurrent session).

## Global Constraints

- **Scope: anime + series only, not movies** — the backend `web_sources` pipeline only covers `kind IN ('serie','anime')`; the `ep == null` (movie) case in `CineDetailScreen.kt` must keep going straight to the existing live web-scrape path, unchanged.
- **⚠️ SHARED WORKING TREE.** This repo is edited concurrently by other Claude Code sessions. Every task's implementer MUST: `git add` ONLY the exact files listed in that task (never `git add -A`/`git add .`/`commit -a`), never run `git checkout`/`git stash`/`git reset`/branch switches, and MUST re-read the CURRENT content of every file it touches before editing (a concurrent session may have changed lines around the target — do not blindly apply a diff against a stale mental snapshot).
- No new `PlaySource` variant — reuse `PlaySource.Web` (`PlaySources.kt:36-40`), which already wraps `com.arkiv.player.data.catalog.web.WebResult`. `MirrorWebMapper.toWebResult()` (already built, `MirrorWebMapper.kt`) already produces that exact type.
- Test command: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"` for a single class, or `./gradlew :app:testDebugUnitTest` for the whole suite. No Claude co-author line in commits; git identity already configured (`lordmacu`).
- `MirrorWebSource` fields (already built, `WebMirrorModels.kt:4-12`): `siteId: String, pageUrl: String, season: Int, episode: Int, name: String, quality: String, langNorm: String` — non-nullable `Int` for `season`/`episode` (unlike `MirrorTorrent.season: Int?`).

---

## File Structure

- Create `app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilter.kt` — pure selection logic (twin of `MirrorFilter.kt`).
- Create `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilterTest.kt` — twin of `MirrorFilterTest.kt`.
- Modify `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` — add `searchEpisodeWeb`, `searchAnimeWeb`.
- Modify `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt` — add `episodeSourcesWeb`.
- Modify `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` — wire mirror-first into the WEB launch branch.
- Modify `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt` — wire mirror-first into the WEB launch branch.

---

## Task 1: `MirrorWebFilter` — pure selection logic + tests

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilter.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilterTest.kt`

**Interfaces:**
- Consumes: `MirrorWebSource` (`WebMirrorModels.kt`, already exists), `ContentType` (`com.arkiv.player.data.catalog.providers.ContentType`, already exists — `MOVIE, TV, ANIME`).
- Produces: `MirrorWebFilter.select(sources: List<MirrorWebSource>, type: ContentType, season: Int, episodeNumbers: Set<Int>): List<MirrorWebSource>` — used by Task 2.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorWebFilterTest {
    private fun w(season: Int, episode: Int, tag: String = "") =
        MirrorWebSource(siteId = "serieskao", pageUrl = "https://x/$tag", season = season,
            episode = episode, name = tag, quality = "", langNorm = "latino")

    @Test fun `movie devuelve todas sin filtrar`() {
        val list = listOf(w(1, 1, "a"), w(2, 3, "b"))
        val r = MirrorWebFilter.select(list, ContentType.MOVIE, 0, emptySet())
        assertEquals(setOf("a", "b"), r.map { it.name }.toSet())
    }

    @Test fun `serie filtra por temporada y episodio exactos`() {
        val list = listOf(
            w(1, 1, "ok"),
            w(2, 1, "otraTemp"),
            w(1, 2, "otroEp"),
        )
        val r = MirrorWebFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(1))
        assertEquals(listOf("ok"), r.map { it.name })
    }

    @Test fun `anime matchea nº absoluto o relativo sin exigir temporada`() {
        val list = listOf(
            w(1, 1085, "abs"),
            w(21, 5, "rel"),
            w(1, 3, "no"),
        )
        val r = MirrorWebFilter.select(list, ContentType.ANIME, season = 0, episodeNumbers = setOf(5, 1085))
        assertEquals(setOf("abs", "rel"), r.map { it.name }.toSet())
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorWebFilterTest"`
Expected: FAIL (`MirrorWebFilter` doesn't exist).

- [ ] **Step 3: Implement**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType

/** Selección de fuentes web del mirror por tipo/temporada/episodio (gemela de MirrorFilter, sin
 *  packs: MirrorWebSource no tiene isPack/episodeEnd). Misma estrategia que ya usan los torrents:
 *  TV exige temporada exacta; anime solo exige que el episodio esté en el set (relativo o
 *  absoluto), sin exigir temporada — esto evita depender de que la numeración de temporadas del
 *  sitio scrapeado coincida con la canónica de TMDB (a veces no coincide, ver AnimeShowDetailScreen). */
object MirrorWebFilter {
    fun select(
        sources: List<MirrorWebSource>,
        type: ContentType,
        season: Int,
        episodeNumbers: Set<Int>,
    ): List<MirrorWebSource> = when (type) {
        ContentType.MOVIE -> sources
        ContentType.TV -> sources.filter { matches(it, season, episodeNumbers, seasonStrict = true) }
        ContentType.ANIME -> sources.filter { matches(it, season, episodeNumbers, seasonStrict = false) }
    }

    private fun matches(w: MirrorWebSource, season: Int, epNums: Set<Int>, seasonStrict: Boolean): Boolean {
        if (seasonStrict && season > 0 && w.season != season) return false
        return w.episode in epNums
    }
}
```

- [ ] **Step 4: Run to verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorWebFilterTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilter.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebFilterTest.kt
git commit -m "feat(web): MirrorWebFilter (selección de web_sources por tipo/temporada/episodio, misma estrategia que MirrorFilter de torrents)"
```

---

## Task 2: `TorrentSearchApi.searchEpisodeWeb` + `searchAnimeWeb`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt`

**Interfaces:**
- Consumes: `MirrorWebFilter.select(...)` (Task 1), `mirror.titleWebSources(slug)` (already exists, `MirrorApiClient.kt:172-183`), `mirror.resolveSlug(tmdbId, kind, titleFallback)` (already exists, `MirrorApiClient.kt:71-102`), `MirrorWebMapper.toWebResult(w)` (already exists, `MirrorWebMapper.kt`), `SourceQuerySpec` (already exists — has `primaryTitle: String`, `seasonForMirror: Int`, `episodeNumbers: Set<Int>`, used identically by the existing `searchAnime`).
- Produces: `TorrentSearchApi.searchEpisodeWeb(titles: List<String>, season: Int, episode: Int, tmdbId: Int? = null): List<com.arkiv.player.data.catalog.web.WebResult>` and `TorrentSearchApi.searchAnimeWeb(spec: SourceQuerySpec, tmdbId: Int? = null): List<com.arkiv.player.data.catalog.web.WebResult>` — both consumed by Task 3 (anime) and Task 4 (TV series).

- [ ] **Step 1: Read the current file first**

Read `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` in full before editing — a concurrent session may have changed it since this plan was written. Locate the existing `searchEpisode` and `searchAnime` methods (their current shape, verified when this plan was authored, is shown below as the pattern to mirror — but confirm against the file you actually read).

**No test for this task** (matches existing convention: `searchEpisode`/`searchAnime`/`searchMovie` have no dedicated unit tests in this codebase — `TorrentSearchApi` is thin orchestration over already-tested `MirrorFilter`/`MirrorWebFilter`; correctness of the NEW pure filtering logic is what Task 1 tests). Verification for this task is a clean compile.

- [ ] **Step 2: Add the two methods** (place them near `searchEpisode`/`searchAnime` for readability; exact insertion point is your judgment based on the file you read — keep the existing methods completely unchanged):

```kotlin
    /**
     * Igual que [searchEpisode] pero contra web_sources del mirror (backend ya crawleo el episodio
     * server-side — sin Cloudflare on-device). Vacio si el mirror no tiene nada para este episodio.
     */
    suspend fun searchEpisodeWeb(
        titles: List<String>,
        season: Int,
        episode: Int,
        tmdbId: Int? = null,
    ): List<com.arkiv.player.data.catalog.web.WebResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.TV, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorWebFilter.select(mirror.titleWebSources(slug), ContentType.TV, season, setOf(episode))
        return sel.map { com.arkiv.player.data.catalog.mirror.MirrorWebMapper.toWebResult(it) }
    }

    /**
     * Igual que [searchAnime] pero contra web_sources del mirror, con la misma spec (numeración
     * absoluta/relativa) que ya arma AnimeSourceProvider para torrents.
     */
    suspend fun searchAnimeWeb(
        spec: SourceQuerySpec,
        tmdbId: Int? = null,
    ): List<com.arkiv.player.data.catalog.web.WebResult> {
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, spec.primaryTitle) ?: return emptyList()
        val sel = MirrorWebFilter.select(
            mirror.titleWebSources(slug), ContentType.ANIME, spec.seasonForMirror, spec.episodeNumbers,
        )
        return sel.map { com.arkiv.player.data.catalog.mirror.MirrorWebMapper.toWebResult(it) }
    }
```

- [ ] **Step 3: Verify clean compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, no errors referencing the new methods or missing imports (`MirrorWebFilter` is same-package as `TorrentSearchApi`'s `mirror.*` references — check whether `TorrentSearchApi.kt` already has a `import com.arkiv.player.data.catalog.mirror.MirrorFilter`-style import block or uses fully-qualified names inline like the existing `searchMovie`/`searchEpisode` methods do for `ContentType`/`MirrorFilter` — match whatever style the file already uses, don't introduce a new import style).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt
git commit -m "feat(web): TorrentSearchApi.searchEpisodeWeb + searchAnimeWeb (web_sources del mirror, mismo patron que los metodos de torrents)"
```

---

## Task 3: `AnimeSourceProvider.episodeSourcesWeb`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`

**Interfaces:**
- Consumes: `TorrentSearchApi.searchAnimeWeb(spec, tmdbId)` (Task 2), the EXISTING private `showMeta(show)` and `AnimeEpisodeResolver.spec(AnimeQueryInput(...))` (already used identically by `episodeSources`, `AnimeSourceProvider.kt:44-59`).
- Produces: `AnimeSourceProvider.episodeSourcesWeb(show: AnimeShow, episode: Int): List<com.arkiv.player.data.catalog.web.WebResult>` — consumed by Task 5.

- [ ] **Step 1: Read the current file first**

Read `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt` in full — confirm `showMeta`, `episodeSources`, and the `ShowMeta`/`AnimeQueryInput` shapes match what's shown below (verified when this plan was authored); adapt if a concurrent session changed them.

**No test for this task** (matches existing convention — `episodeSources`/`episodeSourcesFlow` have no dedicated unit tests; the class is orchestration over already-tested pieces). Verification is a clean compile.

- [ ] **Step 2: Add the method** (place it right after `episodeSources`, before `episodeSourcesFlow`, for locality with its non-flow sibling):

```kotlin
    /** Fuentes WEB del mirror para UN episodio (server-side, sin scraping on-device). Reusa la
     *  misma spec (numeración absoluta/relativa) que [episodeSources] usa para torrents. */
    suspend fun episodeSourcesWeb(show: AnimeShow, episode: Int): List<com.arkiv.player.data.catalog.web.WebResult> {
        val meta = showMeta(show)
        val absolute = if (meta.offset > 0) meta.offset + episode else null
        val spec = AnimeEpisodeResolver.spec(
            AnimeQueryInput(
                titles = meta.titles,
                episode = episode,
                absoluteEpisode = absolute,
                tvdbSeason = meta.tvdbSeason,
            ),
        )
        return torrentSearchApi.searchAnimeWeb(spec, tmdbId = meta.tmdbId)
    }
```

- [ ] **Step 3: Verify clean compile**

Run: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt
git commit -m "feat(web): AnimeSourceProvider.episodeSourcesWeb (reusa showMeta/spec de episodeSources)"
```

---

## Task 4: Wire mirror-first into `CineDetailScreen.kt` (TV series)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`

**Interfaces:**
- Consumes: `graph.torrentSearchApi.searchEpisodeWeb(titles, season, episode, tmdbId)` (Task 2).

- [ ] **Step 1: Read the current file first — CRITICAL, this is a shared-tree UI file**

Read `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` in full, focusing on the `runSearch(ep: TmdbEpisode?)` function and its third `launch { ... }` block (the "WEB" branch — currently builds a `SearchContext` and calls `graph.webSourceEngine.searchFlow(ctx)`, appending `PlaySource.Web(...)`). Confirm the CURRENT exact code matches (or note how it differs from) what's shown below before editing — a concurrent session may have touched this file.

- [ ] **Step 2: Replace ONLY the WEB launch block's body** (the one currently building `SearchContext`/calling `graph.webSourceEngine.searchFlow` — do NOT touch the TORRENT or ARCHIVE launch blocks above it, do NOT touch anything outside this one `launch { ... }`) with:

```kotlin
            launch {
                // Mirror primero (rapido, sin Cloudflare on-device): si el backend ya tiene fuentes
                // web para este episodio, las usamos. Si no (o es pelicula, fuera de alcance del
                // mirror web), caemos al scraping en vivo de siempre.
                val mirrorWeb = if (ep != null) {
                    runCatching {
                        graph.torrentSearchApi.searchEpisodeWeb(d.searchTitles, ep.season, ep.episode, tmdbId = d.id)
                    }.getOrDefault(emptyList())
                } else emptyList()
                if (mirrorWeb.isNotEmpty()) {
                    append(mirrorWeb.map { PlaySource.Web(it) })
                    if (sheetEpisode == ep) loadingWeb = false
                } else {
                    val ctx = com.arkiv.player.data.catalog.providers.SearchContext(
                        titles = d.searchTitles,
                        type = if (ep != null) com.arkiv.player.data.catalog.providers.ContentType.TV
                               else com.arkiv.player.data.catalog.providers.ContentType.MOVIE,
                        season = ep?.season ?: 0,
                        episode = ep?.episode ?: 0,
                        year = d.year,
                    )
                    // Streaming por-canal: cada web agrega sus resultados apenas termina (no espera a todas).
                    runCatching { graph.webSourceEngine.searchFlow(ctx).collect { chunk -> append(chunk.map { PlaySource.Web(it) }) } }
                    if (sheetEpisode == ep) loadingWeb = false
                }
            }
```

- [ ] **Step 3: Verify clean compile**

Run: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat(web): CineDetailScreen prueba el mirror de web_sources antes de scrapear en vivo (series)"
```

---

## Task 5: Wire mirror-first into `AnimeShowDetailScreen.kt` (anime)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`

**Interfaces:**
- Consumes: `graph.animeSourceProvider.episodeSourcesWeb(s, ep)` (Task 3).

- [ ] **Step 1: Read the current file first — CRITICAL, this is a shared-tree UI file**

Read `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt` in full, focusing on `loadEpisode(ep: Int)` and its `launch { // WEB ... }` block (currently awaits `titlesDeferred`, builds a `SearchContext`, calls `graph.webSourceEngine.searchFlow(ctx)`, appends into `webByEp[ep]`). Confirm the CURRENT exact code before editing.

- [ ] **Step 2: Replace ONLY the `launch { // WEB ... }` block's body** (do NOT touch the TORRENT or ARCHIVE launch blocks) with:

```kotlin
            launch { // WEB
                // Mirror primero (rapido, sin Cloudflare on-device); si no hay nada ahi, scraping en vivo.
                val mirrorWeb = runCatching { graph.animeSourceProvider.episodeSourcesWeb(s, ep) }.getOrDefault(emptyList())
                if (mirrorWeb.isNotEmpty()) {
                    if (langs == reqLangs) webByEp[ep] = (webByEp[ep] ?: emptyList()) + mirrorWeb
                } else {
                    val titles = titlesDeferred.await()
                    if (titles.isNotEmpty()) {
                        val ctx = SearchContext(titles = titles, type = ContentType.ANIME, episode = ep)
                        runCatching {
                            graph.webSourceEngine.searchFlow(ctx).collect { chunk ->
                                if (langs == reqLangs) webByEp[ep] = (webByEp[ep] ?: emptyList()) + chunk
                            }
                        }
                    }
                }
                if (langs == reqLangs) loadingWebEp[ep] = false
            }
```

- [ ] **Step 3: Verify clean compile**

Run: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt
git commit -m "feat(web): AnimeShowDetailScreen prueba el mirror de web_sources antes de scrapear en vivo (anime)"
```

---

## Self-Review

- **Spec coverage:** anime+series only (movies keep live-scrape only, `ep == null` branch in Task 4) ✅; tolerant matching for anime / strict for TV matching the EXISTING torrent precedent (sidesteps #4's numbering-drift risk without a new remapping layer) ✅; zero new UI/`PlaySource` variant needed (reuses `PlaySource.Web`) ✅; mirror-first with live-scrape fallback (matches the original web-sources-mirror plan's stated design intent) ✅.
- **Placeholders:** none — every task has complete, verified-against-the-actual-current-file code (Task 2/3's methods were checked against the real `TorrentSearchApi.kt`/`AnimeSourceProvider.kt` content when this plan was written; Tasks 4/5 explicitly instruct re-reading the file first since it's shared-tree).
- **Type consistency:** `MirrorWebFilter.select(sources, type, season, episodeNumbers) -> List<MirrorWebSource>` used identically in both new `TorrentSearchApi` methods; `searchEpisodeWeb`/`searchAnimeWeb` both return `List<WebResult>`, consumed identically by `.map { PlaySource.Web(it) }` in both screens, matching the type the existing live `webSourceEngine.searchFlow` already produces.

## Open follow-ups (out of scope v1)
- No language-priority filtering/sorting applied to mirror-web results (matches existing live-web-scrape behavior, which also doesn't filter by language at this call site — not a regression).
- If mirror-web returns a NON-empty but incomplete result (e.g. only 1 of 3 expected releases) for an episode, the live-scrape fallback is skipped entirely (mirror "wins" outright on any non-empty result) — acceptable v1 tradeoff (avoids double-querying/slow live-scrape when the mirror likely already found the right thing), revisit if incomplete-mirror-results turn out to be common in practice.
- TV series matching stays STRICT (no tolerance for site-vs-TMDB season drift) since the investigation only found evidence of drift for long-running ANIME (sololatino/Naruto); regular Western TV series season boundaries are stable/network-defined and not expected to drift the same way — revisit if evidence of TV-series drift turns up.
