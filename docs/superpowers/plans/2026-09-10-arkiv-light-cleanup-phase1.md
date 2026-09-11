# Arkiv Light Cleanup — Phase 1 (Dead Code) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete everything that only existed for features removed from the `light-magis` branch (torrent, archive.org, NUC/web, gateway/sync leftovers), and correct the comments that became false, with no change in what the app does.

**Architecture:** One task per removed-feature cluster. Each task deletes the dead cluster (found through the call graph, not name matching), updates or deletes the tests that covered it, runs the full suite plus `assembleDebug`, and commits. Live code that still carries an old name is **not** touched here: that is Phase 2. Database columns are **not** touched here: that is Phase 3.

**Tech Stack:** Kotlin, Jetpack Compose (phone + TV), Room 2.6.1, media3/ExoPlayer, libVLC, JUnit 4, Gradle (`./gradlew`).

**Spec:** `docs/superpowers/specs/2026-09-10-arkiv-light-cleanup-design.md` (read the "Inventory", "Phase 1" and "Verification" sections).

## Global Constraints

- **When:** start only after sub-project 4 (AI with Kilo) is closed on the branch. The base is the branch HEAD at that moment. Line numbers below are approximate (measured 2026-09-10): locate code by content, not by line.
- **No behavior change.**
  - No text the user sees changes. Spanish UI strings stay byte-identical, including `"Esta fuente ya no está disponible en esta versión"`.
  - No persisted string changes: preference keys, JSON keys, stored refs, and the values written to `downloads.source` (`"magis"`, `"ditu"`, `"archive"`).
- **Do not touch** anything alive:
  - VLC (`VlcPlayer`, `PlaybackService`, `loadLocal`), local downloads (`data/local`), DLNA, Chromecast, the OTA check, TMDB/AniList, the AI module (`data/ia`, `data/trivia`, `data/recomendaciones`);
  - `WebExtras` / `webExtras` / `resolving`: alive for Magis (subtitle languages, the "Resolviendo…" banner);
  - `RemoteRow`: a home row loaded from the network;
  - `repararIdentidadDeMagis`, `GatewayResult.toPlaySource()`, `SourceKind.LOCAL` (used by `loadLocal`), `CastOptionsProvider` (referenced from `AndroidManifest.xml`), `KindDelResultado.conKindReal` (added by sub-project 4's final fixes);
  - every Room column. `torrentData`, `torrentFileIndex`, `thumbPath`, `original*`, `derivative*` and `remoteUrl` stay until Phase 3, so the code that reads or writes them only because the column exists stays too.
- **Language:** identifiers, comments, KDoc, log messages and commit messages you write or rewrite are in **English**. User-facing Spanish text is never changed.
- **Verify every task** with `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`, **in the foreground**, never in the background. `--rerun` is mandatory: without it Gradle returns cached results.
- **Search tools:** in this environment `grep` and `cat` are intercepted by a hook that can truncate output silently. Use `command grep`, `command cat` or the Read tool. `graft callers <symbol>` shows real callers.
- **Commits:**
  - identity `lordmacu <10134930+lordmacu@users.noreply.github.com>`;
  - **no co-author footer** of any kind (`Co-Authored-By`, `Claude-Session`);
  - `git add` with explicit paths only, never `git add -A` / `git add .`;
  - one commit per task (or per sub-step where stated);
  - `git status --short` empty at the end of each task.
- **Before deleting a screen, service, receiver or route,** check `app/src/main/AndroidManifest.xml` and every `navController.navigate(` string. A symbol with no Kotlin caller can still be reachable from there.

## File map

| Area | Files |
|---|---|
| Torrent leftovers | `data/ArkivRepository.kt`, `data/db/Daos.kt` (`LibraryRow`), `ui/library/LibraryScreen.kt`, `ui/Format.kt`, `ui/tv/TvHomeScreen.kt`, `cast/CastRequest.kt`, `ui/player/PlayerScreen.kt` (`castRequestFor`), `data/local/LocalFilePaths.kt`, `data/local/LocalDownloadManager.kt`, `data/local/DescargasPorFuente.kt` |
| Source kinds | `playback/PlayerSource.kt`, `ui/player/PlayerViewModel.kt`, `data/local/FuenteDeDescarga.kt`, `playback/PlaybackService.kt`, `ui/player/TriviaDelPlayer.kt` |
| archive.org data and UI | `ui/add/AddScreen.kt`, `ui/add/AddViewModel.kt`, `ui/tv/TvAddScreen.kt`, `ui/ArkivRoot.kt`, `data/ArkivRepository.kt`, `data/model/Models.kt`, `data/Mappers.kt`, `data/MetadataParser.kt`, `data/db/Entities.kt` (KDoc only), `data/local/DownloadGroupPolicy.kt`, `ui/detail/DetailViewModel.kt`, `data/nuevos/BuscadorDeCapitulos.kt`, `ui/catalog/PlaySources.kt` |
| Gateway/sync leftovers and unreferenced declarations | `data/gateway/MotivoDelGateway.kt`, `data/subtitles/SubtitlePrefs.kt`, `data/db/Daos.kt` (`EpisodeFrameDao`), `data/SeriesEpisodeLabel.kt`, `data/catalog/QualityLabel.kt`, `ui/Graph.kt`, `ui/search/SearchPlayback.kt`, `ui/search/SearchViewModel.kt`, `ui/tv/TvFormularioConTeclado.kt` |
| Dependencies and resources | `app/build.gradle.kts`, `app/src/main/res/drawable/tv_focus_highlight.*`, `app/src/main/res/layout/arkiv_player_view.xml` |
| Comments | files under `app/src/main/java` and `app/src/test/java` with stale mentions |

All code paths are under `app/src/main/java/com/arkiv/player/`; test paths are under `app/src/test/java/com/arkiv/player/`.

---

### Task 1: Remove the torrent leftovers

**Files:**
- Modify: `data/ArkivRepository.kt`, `data/db/Daos.kt`, `ui/library/LibraryScreen.kt`, `ui/Format.kt`, `ui/tv/TvHomeScreen.kt`, `cast/CastRequest.kt`, `ui/player/PlayerScreen.kt`, `data/local/LocalFilePaths.kt`, `data/local/LocalDownloadManager.kt`, `data/local/DescargasPorFuente.kt`
- Test: `cast/CastRequestBuilderTest.kt`, `data/local/LocalFilePathsTest.kt`, `data/local/DescargasPorFuenteTest.kt`

**Interfaces:**
- Produces:
  - `CastRequest.build(...)` without the `isTorrent` parameter;
  - `libraryMeta(isMovie: Boolean, durationSeconds: Double, episodeCount: Int)` without `isTorrent`;
  - `LibraryItem` and `LibraryRow` without `isTorrent`.

Why this is dead: nothing writes `source = "torrent"` any more, so `LibraryRow.isTorrent` (`source == "torrent"`) and `LibraryItem.isTorrent` are always `false`. `torrentDataOf` and `torrentSourceForEpisode` have 0 callers. `deTorrent` has 0 callers. The only caller of `CastRequest.build` passes `isTorrent = false`.

- [ ] **Step 1: Confirm the cluster is dead.**

  Run each check and expect no caller outside the listed files:

  ```
  graft callers torrentDataOf
  graft callers torrentSourceForEpisode
  graft callers deTorrent
  command grep -rn "isTorrent" app/src/main/java
  ```

  Expected: `isTorrent` appears only in
  - `ArkivRepository.kt` (declaration ~l.42 and assignment ~l.1111),
  - `Daos.kt` (~l.146),
  - `LibraryScreen.kt` (~l.185-187),
  - `Format.kt` (~l.16-18),
  - `TvHomeScreen.kt` (~l.381),
  - `CastRequest.kt` (~l.48-62),
  - `PlayerScreen.kt` (~l.742, `isTorrent = false`).

  If any other caller appears, stop and report it.

- [ ] **Step 2: Delete the torrent functions from the repository.**

  In `data/ArkivRepository.kt`, delete:
  - the `EpisodeTorrent` sealed type (~l.23-26);
  - `suspend fun torrentDataOf(itemId: String): ByteArray?`, with its KDoc (~l.662-667);
  - `suspend fun torrentSourceForEpisode(episodeId: String): EpisodeTorrent?`, with its KDoc (~l.669-683).

- [ ] **Step 3: Remove `isTorrent` and the TORRENT badge.**

  In `data/ArkivRepository.kt`:
  - delete `val isTorrent: Boolean = false` from `LibraryItem` (~l.42);
  - delete the argument `isTorrent = item.source == "torrent",` (~l.1111).

  In `data/db/Daos.kt`, delete `val isTorrent: Boolean get() = source == "torrent"` from `LibraryRow` (~l.146).

  In `ui/Format.kt`, change `libraryMeta` to drop the parameter and its branch:

  ```kotlin
  fun libraryMeta(isMovie: Boolean, durationSeconds: Double, episodeCount: Int): String = when {
  ```

  Keep the remaining branches exactly as they are; only the `isTorrent -> "Película"` line and the parameter go.

  In `ui/library/LibraryScreen.kt`, delete `private val TorrentBadgeColor` (~l.59) and change the three lines (~l.185-187) to:

  ```kotlin
                  badge = if (row.isMovie) "PELÍCULA" else "SERIE",
                  badgeColor = if (row.isMovie) ArkivRed else SeriesBadgeColor,
                  meta = libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount),
  ```

  In `ui/tv/TvHomeScreen.kt` (~l.381): `libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount)`.

  The comment near `LibraryScreen.kt` ~l.229 that says "partir de `isTorrent`" must be rewritten in English, or deleted if it no longer explains anything.

- [ ] **Step 4: Drop the torrent path from `CastRequest`.**

  In `cast/CastRequest.kt`, remove the `isTorrent: Boolean` parameter of `build(...)` and simplify the two `when`s:
  - `isTorrent || isLive -> lanUrl` becomes `isLive -> lanUrl`;
  - delete the `isTorrent -> lanMime ?: MIME_MP4` line.

  Keep every other branch and default as is.

  In `ui/player/PlayerScreen.kt`, `castRequestFor` (~l.742): delete the `isTorrent = false,` argument.

  In `CastRequestBuilderTest.kt`, delete the tests whose subject is the torrent path, and remove the `isTorrent = …` argument from the remaining calls.

- [ ] **Step 5: Drop the torrent download folder.**

  In `data/local/LocalDownloadManager.kt` (~l.200), delete this line:

  ```kotlin
  runCatching { File(targetDir(), "torrents/${LocalFilePaths.torrentDirName(episodeId)}").deleteRecursively() }
  ```

  In `data/local/LocalFilePaths.kt`, delete `fun torrentDirName(...)` (~l.54) and its KDoc. In `LocalFilePathsTest.kt`, delete its tests.

- [ ] **Step 6: Delete `DescargasPorFuente.deTorrent`.**

  Delete it (~l.29) and its KDoc. In `DescargasPorFuenteTest.kt`, delete its tests.

- [ ] **Step 7: Fix the comments in the touched files.**

  In every file this task modified, rewrite in English, or delete, each comment that now describes torrent code or flows that no longer exist. Keep comments that explain a still-valid reason.

- [ ] **Step 8: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL, 0 test failures. Then check that no torrent code is left outside the Phase 3 columns:

  ```
  command grep -rnE "isTorrent|EpisodeTorrent|torrentDataOf|torrentSourceForEpisode|torrentDirName|deTorrent|TorrentBadge" app/src
  ```

  Expected: no output.

- [ ] **Step 9: Commit.**

  ```bash
  git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt app/src/main/java/com/arkiv/player/ui/Format.kt app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt app/src/main/java/com/arkiv/player/cast/CastRequest.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt app/src/main/java/com/arkiv/player/data/local/LocalFilePaths.kt app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt app/src/main/java/com/arkiv/player/data/local/DescargasPorFuente.kt app/src/test/java/com/arkiv/player/cast/CastRequestBuilderTest.kt app/src/test/java/com/arkiv/player/data/local/LocalFilePathsTest.kt app/src/test/java/com/arkiv/player/data/local/DescargasPorFuenteTest.kt
  git commit -m "refactor(cleanup): remove torrent leftovers"
  ```

---

### Task 2: Source kinds — `ARCHIVE` becomes `UNKNOWN`; drop `NUC`, `loadWeb` and the archive prefetch

**Files:**
- Modify: `playback/PlayerSource.kt`, `ui/player/PlayerViewModel.kt`, `data/local/FuenteDeDescarga.kt`, `playback/PlaybackService.kt`, `ui/player/TriviaDelPlayer.kt` (only if it names the removed values)
- Test: `playback/PlayerSourceTest.kt`, `playback/PlayerSourceTagTest.kt`, `playback/CachingDeRedTest.kt`, `playback/DeteccionDeEstancamientoTest.kt`, `ui/player/TriviaDelPlayerTest.kt`, `data/local/FuenteDeDescargaTest.kt`

**Interfaces:**
- Produces:
  - `enum class SourceKind { UNKNOWN, MAGIS, LOCAL, LIVE, DITU }`;
  - `PlayerSource.kindFor(id)` returns `SourceKind.UNKNOWN` for ids with no known prefix;
  - `PlayerViewModel.loadUnknownSource(episodeId)` (private) replaces `loadArchive`.

Why:
- `NUC` is never returned by `kindFor`; its only uses route to the `loadWeb` stub.
- `ARCHIVE` is the catch-all for ids with no known prefix. Its load path is a stub that shows `"Esta fuente ya no está disponible en esta versión"`. That visible behavior stays; only the name changes, to say what it is.
- The `ARCHIVE` branch of `prefetchNext` warms the head of an archive.org URL through `buildData` / `localArchiveUri` / `warmHead`, which nothing else uses.

- [ ] **Step 1: Write the failing tests.**

  In `playback/PlayerSourceTest.kt`, add:

  ```kotlin
  @Test fun `an id with no known prefix is an unknown source`() {
      assertEquals(SourceKind.UNKNOWN, PlayerSource.kindFor("some-old-archive-identifier"))
  }
  ```

  In `data/local/FuenteDeDescargaTest.kt`, add:

  ```kotlin
  @Test fun `an unknown source keeps the persisted download source value`() {
      assertEquals("archive", FuenteDeDescarga.para("some-old-archive-identifier"))
  }
  ```

  Replace every `SourceKind.ARCHIVE` in the listed tests with `SourceKind.UNKNOWN`. Delete the test cases that only exercise `SourceKind.NUC`.

- [ ] **Step 2: Run them and expect a failure.**

  Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*PlayerSourceTest*' --tests '*FuenteDeDescargaTest*'`

  Expected: the build fails with `Unresolved reference: UNKNOWN`.

- [ ] **Step 3: Change the enum and the fallback.**

  In `playback/PlayerSource.kt`:

  ```kotlin
  enum class SourceKind { UNKNOWN, MAGIS, LOCAL, LIVE, DITU }
  ```

  In `kindFor`, the `else -> SourceKind.ARCHIVE` becomes `else -> SourceKind.UNKNOWN`. Its KDoc explains, in English, that `UNKNOWN` covers ids from sources removed from this branch, which the player answers with a "no longer available" error.

- [ ] **Step 4: Update the player.**

  In `ui/player/PlayerViewModel.kt`:

  1. **In `load()`** (~l.322-346), delete the `if (kind != SourceKind.ARCHIVE) { … }` guard and keep its body. Every kind now checks for a downloaded file first. This changes nothing observable: only Magis content can be downloaded (`AppGraph.downloadStrategies` has only `"magis"`). The dispatch becomes:

     ```kotlin
                 val local = localLibrary.fileFor(episodeId)
                 if (local != null) { loadLocal(episodeId, local); return@launch }
                 if (TriviaDelPlayer.pideDatos(episodeId, kind)) cargarTrivia(episodeId)
                 Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
                 when (kind) {
                     SourceKind.UNKNOWN -> loadUnknownSource(episodeId)
                     SourceKind.MAGIS -> loadMagis(episodeId)
                     SourceKind.DITU -> loadDitu(episodeId)
                     // kindFor() never returns LOCAL: a downloaded file is detected above by
                     // localLibrary.fileFor(). The branch exists because the `when` is exhaustive.
                     SourceKind.LOCAL -> loadUnknownSource(episodeId)
                     // Unreachable: live channels return before this launch (see the guard above).
                     SourceKind.LIVE -> Unit
                 }
     ```

     Keep the existing comments above `fileFor`, rewritten in English, and drop the sentence about `ARCHIVE` being left out.
  2. **Rename** `private fun loadArchive(episodeId: String)` to `loadUnknownSource`. Keep its body byte-identical, including the Spanish error text and the `_webExtras` / `_resolving` resets. Replace its KDoc and log line with English that describes what it does today:

     ```kotlin
         /**
          * Plays nothing and reports that the source is gone. Reached for ids whose source was
          * removed from this branch (old library rows with no known prefix).
          */
         private fun loadUnknownSource(episodeId: String) {
             Log.w(PLAY, "loadUnknownSource() episodeId=$episodeId → source not available in this branch")
             _playlist.value = null
             _webExtras.value = null
             _resolving.value = false
             _error.value = "Esta fuente ya no está disponible en esta versión"
         }
     ```
  3. **Delete** `private fun loadWeb(episodeId: String)` and its KDoc (~l.1050).
  4. **In `prefetchNext`** (~l.1087-1115), replace the `SourceKind.ARCHIVE -> { … }` block with `SourceKind.UNKNOWN -> Unit`, and delete the `SourceKind.NUC -> Unit` branch. Then delete `buildData`, `localArchiveUri` and `warmHead`: their only callers were that block (~l.1098 and ~l.1103). Confirm with `graft callers` for each before deleting. Rewrite the branch comments in English.

- [ ] **Step 5: Update the download mapping and the service fallback.**

  In `data/local/FuenteDeDescarga.kt`, replace the last branch with the following, and turn the KDoc into English with the same meaning:

  ```kotlin
          // UNKNOWN (ids from removed sources), LOCAL and LIVE have no download strategy. "archive"
          // is the value this branch has always persisted for them in `downloads.source`; it stays
          // until the Phase 3 audit.
          SourceKind.UNKNOWN, SourceKind.LOCAL, SourceKind.LIVE -> "archive"
  ```

  In `playback/PlaybackService.kt` (~l.158):

  ```kotlin
  .getOrDefault(SourceKind.UNKNOWN)
  ```

  An old extras value `"ARCHIVE"` fails `valueOf` and falls to `UNKNOWN`, so nothing breaks.

  In `ui/player/TriviaDelPlayer.kt`, if `pideDatos` or its KDoc names `ARCHIVE` or `NUC`, update it. The `else -> false` branch stays.

- [ ] **Step 6: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL, 0 failures. Then check the old names are gone:

  ```
  command grep -rnE "SourceKind\.(ARCHIVE|NUC)|loadArchive|loadWeb|localArchiveUri|warmHead" app/src
  ```

  Expected: no output.

- [ ] **Step 7: Commit.**

  ```bash
  git add app/src/main/java/com/arkiv/player/playback/PlayerSource.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/data/local/FuenteDeDescarga.kt app/src/main/java/com/arkiv/player/playback/PlaybackService.kt app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt app/src/test/java/com/arkiv/player/playback/PlayerSourceTagTest.kt app/src/test/java/com/arkiv/player/playback/CachingDeRedTest.kt app/src/test/java/com/arkiv/player/playback/DeteccionDeEstancamientoTest.kt app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt app/src/test/java/com/arkiv/player/data/local/FuenteDeDescargaTest.kt
  git commit -m "refactor(cleanup): unknown source kind replaces archive; drop NUC and the web filler"
  ```

  If `TriviaDelPlayer.kt` did not change, leave it out of `git add`.

---

### Task 3: Remove the archive.org data layer and screens

**Files:**
- Delete: `ui/add/AddScreen.kt`, `ui/add/AddViewModel.kt`, `ui/tv/TvAddScreen.kt`
- Modify: `ui/ArkivRoot.kt`, `data/ArkivRepository.kt`, `data/model/Models.kt`, `data/Mappers.kt`, `data/MetadataParser.kt`, `data/db/Entities.kt` (KDoc only), `data/local/DownloadGroupPolicy.kt`, `ui/detail/DetailViewModel.kt`, `data/nuevos/BuscadorDeCapitulos.kt`, `data/local/DescargasPorFuente.kt`, `ui/catalog/PlaySources.kt`
- Modify (repo root): `CLAUDE.md` (one parenthetical in the host-sweep note)
- Test: `data/MetadataParserTest.kt`, `data/EpisodeNumberFromNameTest.kt`, `playback/ContenedorDeVideoTest.kt`, `data/local/DescargasPorFuenteTest.kt`, plus any test the compiler flags

**Interfaces:**
- Produces: `MetadataParser` keeps only `fun cleanName(path: String, identifier: String? = null): String` and the private helpers it needs. Magis and Caracol (`MagisEntities` ~l.304, `DituEntities` ~l.160) call it.

Why this is dead:
- The `"add"` route in `ArkivRoot` exists, but nothing navigates to it (no `navigate("add")` anywhere).
- `addItem` is a stub that always fails with "archive.org ya no está disponible".
- `refreshItem` only proceeds for `source == "archive"` items and then calls that stub.
- `ArchiveItem`, `RawFile` and `VideoVariant` only feed the archive.org metadata parser.
- The domain fields `original`, `derivative`, `playbackVariant` and `castVariant` have no reader.
- `toEntity` / `toItemEntity`, `deArchive` and `SourceSection` have 0 callers.
- `MetadataParser.episodeNumberOf` has 0 code callers: the two mentions in `Entities.kt` ~l.83 and `Models.kt` ~l.32 are KDoc.

- [ ] **Step 1: Confirm reachability.**

  ```
  command grep -rnE "navigate\(\"add" app/src/main/java
  graft callers addItem
  graft callers refreshItem
  graft callers toEntity
  graft callers toItemEntity
  graft callers deArchive
  graft callers SourceSection
  ```

  Expected: no `navigate("add"`. `addItem` is called only from `refreshItem` and `AddViewModel`. `refreshItem` is called only from `DetailViewModel` (~l.54, ~l.90) and `BuscadorDeCapitulos.revisarArchive`. The rest have no caller. Confirm `AddScreen` and `TvAddScreen` are not referenced from `AndroidManifest.xml`.

- [ ] **Step 2: Delete the add screens and their route.**

  Delete `ui/add/AddScreen.kt`, `ui/add/AddViewModel.kt` and `ui/tv/TvAddScreen.kt`. In `ui/ArkivRoot.kt`:
  - delete the `composable("add") { … }` block (~l.360-370);
  - delete the `AddScreen` import;
  - fix the comment near ~l.81 that cites the `"add"` route (English, or delete).

  The branch's `CLAUDE.md` names `AddScreen` as a source of noise in the host sweep ("…o en el texto de ayuda de un campo (`AddScreen`),"). With the screen gone that example is false. Delete only that clause, so the sentence reads "…URLs de ejemplo en comentarios/KDoc, namespaces XML del cliente DLNA, …". `CLAUDE.md` is Spanish and stays Spanish: it is the branch's rule document, which the user reads.

- [ ] **Step 3: Delete the archive.org repository functions.**

  In `data/ArkivRepository.kt`, delete `addItem` (~l.592) and `refreshItem` (~l.641), with their KDoc and any `ArchiveItem` import.

  In `ui/detail/DetailViewModel.kt`, delete the `repo.refreshItem(id)` calls (~l.54 and inside `refresh()` ~l.90). The "mark chapters as seen" behavior (`marcarCapitulosVistos`) must still run when the detail opens, in the same order relative to loading the episodes.

  If `refresh()` becomes empty, check its callers with `graft callers refresh` scoped to the detail screens. If a UI element only called it to refresh archive.org, delete that element and `refresh()`; if something still needs it, keep it with the remaining body. Report which case applied.

- [ ] **Step 4: Drop the archive.org and web branches of the new-chapters check.**

  In `data/nuevos/BuscadorDeCapitulos.kt`, the `when (serie.fuente)` keeps only `"magis" -> revisarMagis(serie)` and `else -> 0`. Delete `revisarArchive` and `revisarWeb`, with their KDoc.

- [ ] **Step 5: Delete the archive.org model and mappers.**

  In `data/model/Models.kt`:
  - delete `VideoVariant` (~l.4), `ArchiveItem` (~l.101) and `RawFile` (~l.110);
  - delete the `original` and `derivative` fields (~l.19-20) and the `playbackVariant` and `castVariant` properties (~l.40-44) from the episode model.

  Rewrite the KDoc that cites `MetadataParser.episodeNumberOf` (~l.30-33) in English. `season` and `episode` are now set by `MagisEntities` / `DituEntities` from the source's own data.

  In `data/Mappers.kt`, delete `toEntity` (~l.27) and `toItemEntity` (~l.49). Delete the file if nothing remains that the compiler needs.

  In `data/local/DownloadGroupPolicy.kt` (~l.169-170), delete the `original = null, derivative = null` arguments.

  In `data/db/Entities.kt` (~l.82-84), rewrite the KDoc that cites `MetadataParser.episodeNumberOf` the same way. **Do not change any column.**

- [ ] **Step 6: Shrink `MetadataParser` to what Magis and Caracol use.**

  In `data/MetadataParser.kt`, keep `cleanName` and the private helpers it calls (`stripExtension`). Delete `parse`, `isVideo`, `toVariant`, `groupKey`, `naturalCompare`, `extensionOf`, `directoryOf` and `episodeNumberOf`, unless the compiler shows a remaining caller (report it). Rewrite the object's KDoc in English: it cleans a display name for Magis and Caracol items.

  Tests:
  - in `MetadataParserTest.kt`, keep the `cleanName` tests and delete the rest;
  - delete `EpisodeNumberFromNameTest.kt` if it only tests `episodeNumberOf`;
  - update `ContenedorDeVideoTest.kt` if it referenced a deleted helper.

- [ ] **Step 7: Delete the last archive.org leftovers.**

  Delete `DescargasPorFuente.deArchive` (~l.34) and its tests, and `SourceSection` in `ui/catalog/PlaySources.kt` (~l.102) with any import that only it used.

- [ ] **Step 8: Fix the comments in the touched files.**

  In every file this task modified, rewrite in English, or delete, the comments that describe archive.org flows that no longer exist.

- [ ] **Step 9: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL, 0 failures. Then:

  ```
  command grep -rnE "AddScreen|AddViewModel|TvAddScreen|addItem\(|refreshItem|ArchiveItem|RawFile|VideoVariant|revisarArchive|revisarWeb|deArchive|SourceSection|episodeNumberOf" app/src
  ```

  Expected: no output. (`CLAUDE.md` is outside `app/src`, so the `AddScreen` check there is the diff of Step 2.)

- [ ] **Step 10: Commit.**

  Stage the deleted and modified files explicitly (`git add` on each path, including `CLAUDE.md`; `git rm` for the three deleted screens), then:

  ```bash
  git commit -m "refactor(cleanup): remove the archive.org data layer and add screens"
  ```

---

### Task 4: Remove gateway and sync leftovers and unreferenced declarations

**Files:**
- Delete: `data/gateway/MotivoDelGateway.kt`, `data/SeriesEpisodeLabel.kt`, `data/catalog/QualityLabel.kt`
- Modify: `data/subtitles/SubtitlePrefs.kt`, `data/db/Daos.kt` (`EpisodeFrameDao`), `ui/Graph.kt`, `ui/search/SearchPlayback.kt`, `ui/search/SearchViewModel.kt`, `ui/tv/TvFormularioConTeclado.kt`
- Test: `data/gateway/MotivoDelGatewayTest.kt`, `data/SeriesEpisodeLabelTest.kt`, `data/catalog/QualityLabelTest.kt`, `ui/search/HandoffRouteTest.kt`

**Interfaces:**
- Produces: nothing new. Deletions only.

- [ ] **Step 1: Re-run the zero-reference scan and exclude what is alive.**

  For each of these, run `graft callers <name>` and `command grep -rnw "<name>" app/src/main app/src/test`:
  - `motivoDelGateway`
  - `applyFromRemote`
  - `pendientesDeBajar`
  - `marcarBajado`
  - `SeriesEpisodeLabel`
  - `QualityLabel`
  - `formatBytes`
  - `seriesIdOf`
  - `handoffRouteFor`
  - `ANCHO_TECLADO_DP`
  - `ANCHO_CAMPOS_DP`

  Expected: no production caller. Tests may reference some of them; they go too.

  **Do not delete** `CastOptionsProvider` (manifest meta-data) or `conKindReal` (in use by sub-project 4's final fixes). Delete any other symbol only with the same evidence.

- [ ] **Step 2: Delete them.**

  - Delete `data/gateway/MotivoDelGateway.kt` and `MotivoDelGatewayTest.kt`.
  - Delete `SubtitlePrefs.applyFromRemote` (~l.130) and its KDoc; it applied settings pulled by cloud sync.
  - Delete `EpisodeFrameDao.pendientesDeBajar` and `marcarBajado` (`Daos.kt` ~l.803-821) with their `@Query` and KDoc. They served the frame upload to the cloud. **The `remoteUrl` column stays until Phase 3.**
  - Delete `data/SeriesEpisodeLabel.kt` + `SeriesEpisodeLabelTest.kt`, and `data/catalog/QualityLabel.kt` + `QualityLabelTest.kt`.
  - Delete `formatBytes` (`ui/Graph.kt` ~l.16), `seriesIdOf` (`SearchPlayback.kt` ~l.295), `handoffRouteFor` (`SearchViewModel.kt` ~l.24) + `HandoffRouteTest.kt`, and the constants `ANCHO_TECLADO_DP` / `ANCHO_CAMPOS_DP` (`TvFormularioConTeclado.kt` ~l.96-97).

- [ ] **Step 3: Fix the comments in the touched files.**

  English, or delete.

- [ ] **Step 4: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit.**

  Stage explicitly (`git rm` for deleted files), then:

  ```bash
  git commit -m "refactor(cleanup): remove gateway and sync leftovers and unreferenced declarations"
  ```

---

### Task 5: Remove dead dependencies and resources

**Files:**
- Modify: `app/build.gradle.kts`
- Delete: `app/src/main/res/drawable/tv_focus_highlight.*`, `app/src/main/res/layout/arkiv_player_view.xml`

- [ ] **Step 1: Confirm nothing uses them.**

  ```
  command grep -rnE "zxing|BarcodeFormat|QRCode|BitMatrix" app/src/main/java
  command grep -rnE "androidx\.media3\.database|DatabaseProvider" app/src/main/java
  command grep -rnE "tv_focus_highlight|arkiv_player_view" app/src/main
  ```

  Expected: no output from any of the three.

- [ ] **Step 2: Remove them.**

  In `app/build.gradle.kts`, delete the lines `implementation("com.google.zxing:core:3.5.3")` and `implementation("androidx.media3:media3-database:1.5.1")`, with any comment that only described them. Delete the two resource files.

- [ ] **Step 3: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL. If removing `media3-database` breaks the build (a media3 cache class needs it transitively), restore that single line, keep the rest, and report it.

- [ ] **Step 4: Commit.**

  ```bash
  git add app/build.gradle.kts
  git rm app/src/main/res/drawable/tv_focus_highlight.* app/src/main/res/layout/arkiv_player_view.xml
  git commit -m "build(cleanup): drop unused zxing and media3-database dependencies and two unused resources"
  ```

---

### Task 6: Sweep the stale comments

**Files:**
- Modify: any file under `app/src/main/java` and `app/src/test/java` with a comment that is now false. `docs/` is **out of scope**: specs and plans record history on purpose. `CLAUDE.md` is out of scope too, except the one clause Task 3 fixes.

**The rule for every comment that mentions a removed system:** torrent, archive.org, NUC/arkiv-offline, cloud sync/`CloudSyncManager`, PocketBase, the `arkiv-api` gateway (as a server), pairing/remote control, the web resolver, Simkl, OpenSubtitles.

- If it states something **false about the current code** (a class, flow or caller that no longer exists, or a behavior that no longer happens), rewrite it in English so it is true, or delete it if nothing true remains.
- If it records a **reason that still justifies the current code** (a measured lesson, why a guard exists), keep it. Translate it to English only if you rewrite it anyway.
- Never change code in this task, only comments.

- [ ] **Step 1: Measure before.**

  Save this scan's output in the task report. It is the before-count. For each keyword, it prints the number of comment lines that mention it:

  ```
  for k in torrent "archive\.org" "CloudSync|cloudsync" "PocketBase|pocketbase" "arkiv-api|ArkivApiClient" "[Pp]areo|pairing" "[Rr]emoto" "Simkl" "OpenSubtitles" "NUC|arkiv-offline"; do n=$(command grep -rnE "$k" app/src/main/java app/src/test/java | command grep -cE ':[0-9]+:\s*(//|\*|/\*)'); echo "$k $n"; done
  ```

- [ ] **Step 2: Go file by file.**

  Work through the files the scan hits, applying the rule. One commit per package directory is fine; each commit message says which package, for example `docs(cleanup): correct stale comments in data/local`.

- [ ] **Step 3: Measure after.**

  Re-run the scan and put both counts in the report. Mentions that remain must be ones that record a still-valid reason, and the report lists a few of them as examples.

- [ ] **Step 4: Verify.**

  Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`

  Expected: BUILD SUCCESSFUL. Comment-only changes must not change the test count.

---

### Task 7: Verify on the KALLEY R3 (controller task, not a subagent)

- [ ] **Step 1: Install.**

  ```bash
  ./gradlew :app:assembleDebug && adb -s <kalley> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

  Find the TV with `adb mdns services`. If it shows the OTA "Nueva versión" dialog, dismiss it with BACK, never "Actualizar ahora".

- [ ] **Step 2: Smoke test.**

  Check that audio is playing with `adb shell dumpsys audio` (`AudioPlaybackConfiguration` of the app's uid), and read the screen with `uiautomator dump`:

  1. A Magis movie or episode plays, and the trivia card appears.
  2. A Magis live channel plays, and CENTER pauses and resumes it.
  3. A Caracol episode and a Caracol live channel play.
  4. A downloaded file plays through VLC.
  5. The library and "Continuar viendo" show the same items as before the phase.
  6. The "Para ti" row is there, and opening a recommendation lands on its detail.
  7. Opening a detail screen works (it no longer calls `refreshItem`).

- [ ] **Step 3: Record the results** in the ledger.

---

## Self-Review

**1. Spec coverage (Phase 1 section and inventory):**
- Every "Confirmed dead" item has a task:
  - the torrent functions and `EpisodeTorrent` → Task 1;
  - `deTorrent` → Task 1, `deArchive` → Task 3, `motivoDelGateway` → Task 4;
  - `MetadataParser` / `VideoVariant` / the archive domain fields / the mappers → Task 3;
  - the add screens → Task 3, `SourceSection` → Task 3;
  - the source fillers and `SourceKind` → Task 2;
  - the dependencies and resources → Task 5;
  - the `episode_frame` upload queries → Task 4.
- Stale comments → a per-task step plus the Task 6 sweep.
- "Not in this phase" is enforced in Global Constraints: alive code, old names, columns, VLC, downloads.
- Verification at the end of the phase → Task 7.

**2. Placeholders:** none. Where exact code depends on surrounding lines the plan cannot pin, the step gives the before/after lines and the command that proves the result (`graft callers`, the `command grep` checks with "expected: no output").

**3. Consistency:**
- `SourceKind.UNKNOWN` is the same name in Task 2's enum, the `when`s, `FuenteDeDescarga`, `PlaybackService` and the tests.
- `loadUnknownSource` replaces `loadArchive` in the only file that had it.
- The `"archive"` persisted value is kept in Task 2 and left for the Phase 3 audit.
- `libraryMeta` and `CastRequest.build` lose `isTorrent` in their declarations and in every caller listed.
