# English translation sweep — status

Standing directive (from the user, 2026-09-13/14): sweep the whole `app/` codebase module by
module, lowest risk to highest risk, translating every developer-facing Spanish identifier,
comment, and KDoc to English. User-facing strings (Bogotá Spanish, tuteo, never voseo) are **out
of scope** and must stay exactly as they are. This supersedes the older "only new code must be
English" line in `.claude/reglas.md`.

Order chosen by the user: **módulo por módulo, de menor a mayor riesgo** (module by module,
lowest to highest risk).

## Overall completion: **~92%** of the whole sweep

- `playback/`, `security/`, `dlna/`, `cast/`, `thumbnails/`: **100% done.**
- `data/` (~all packages): **~99% done** — only `ArkivRepository.kt`'s tail is left (see below).
- `ui/` (~110-113 files): **0% done — not started.**

Rough file-count math: `playback/` + the small early modules + all of `data/` is roughly 160-170
files out of the whole sweep; `ui/` (~110-113 files, not started) is the big remaining chunk. That
puts the true overall completion closer to **~55-60% of the whole codebase**, even though `data/`
itself is essentially finished. The 92% figure above is "how much of the *currently active* effort
(data/) is done," not "how much of the whole app is done" — see the honest breakdown below.

## What's fully done and committed

Every package under `data/` is translated and verified (compiles, full test suite green at
**1704 tests, 0 skipped, 0 failures, 0 errors** after every commit):

- `data/update/`, `data/ia/`, `data/biblioteca/`, `data/gateway/ContentSource.kt`, `data/model/`,
  `data/marcadores/`, `data/nuevos/`, `data/subtitles/`, `data/caracol/`, `data/catalog/`,
  `data/recomendaciones/`, `data/trivia/`
- `data/local/` (all download strategies, `LocalDownloadManager`/`LocalDownloadWorker`,
  `LocalLibrary`, `DownloadSource`, `DownloadNotificationText`, `DownloadActionsReceiver`,
  `DownloadGroupPolicy`, `DuplicateDownloadPolicy`, `DownloadRetryPolicy`, `HttpRangeDownloader`,
  `LocalFilePaths`, plus every test — including sweeping Spanish backtick test names, which don't
  show up in an accented-character grep)
- `data/magis/` (16 main + 14 test files — `MagisAccount`, `MagisCredentialStore`, `EncryptedPrefs`,
  `MagisCatalog`, `MagisPortalClient`, `MagisSearch`, `MagisSession`, `MagisFuente` (class name
  kept, internals translated), `MagisResolve`, `MagisLive`, `MagisLiveCatalog`, etc.)
- `data/ditu/` (8 main + 9 test files — `DituRef`, `DituEntitlement`, `DituClient`, `DituCatalog`,
  `DituItem`, `DituChannel`, `DituEpisodes`, `DituResolve`, `CaracolFailure`, `DituFuente` (class
  name kept, internals translated))
- `data/db/` — **the module the user called "muy delicado"** — `Entities.kt`, `Daos.kt`,
  `ArkivDatabase.kt` (all 29 migrations' comments), `SyncTriggers.kt`, and their tests. See the
  hard rule below; this was the highest-precision work in the whole sweep and is fully verified.

**Last commit of the fully-clean run:** `ceda1ae8` (`translate SyncTriggers.kt and its tests to
English, completing data/db/`). Everything after that is `ArkivRepository.kt` work — see below.

## In progress right now: `ArkivRepository.kt` (1391 lines)

This is the **last file in `data/`**. A forked subagent did most of the work; it was stopped
mid-task (not an error — the user asked to pause the session) after leaving a few stale references
that I (the parent session) fixed by hand to get back to a **clean, compiling, fully-tested
checkpoint**, committed as `fbd642d7` (`translate most of ArkivRepository.kt to English
(partial)`). **The working tree is clean and green right now** — a new agent can start from a known
good state.

### Roughly 80-85% of this one file is done

Translated and verified: everything from the top of the file through line ~1153, including
`observeLibrary`/`observeLibraryOrdered`/`observeLibraryGroups`/`observeContinueWatching`/
`observeWatchedItems`, the TMDB-artwork section, the episode-stills/frames section, `setCategory`,
`renameItem`, `markChaptersSeen`, all the `addMagisSource`/`addDituSource`/`addDituSeason`/
`addMagisSeason`/`applyMagisIdentity`/`magisRefToRepair` family, `removeItem`,
`observeItemDetail`, episode navigation helpers, and the skip-marker section. Full ripple already
landed in `AppGraph.kt`, `DetailViewModel.kt`, `HomeViewModel.kt`, `TvLibraryViewModel.kt`,
`ReparacionDeMagis.kt`, `RecommendationAggregator.kt`, `SearchPlayback.kt`.

### What's left in `ArkivRepository.kt` (precise, line numbers as of commit `fbd642d7`)

Roughly lines **1128-1391** (the tail of the file) still has Spanish identifiers/comments:

1. **`marcarEnCurso(episodeId)`** (line 1153) — rename to something like `markInProgress`.
   Translate its KDoc (starts around line 1128) and the local `existente`.
   **External ripple needed** (grep confirmed these exact hits):
   - `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` — one real call site
     (`repo.marcarEnCurso(episodeId)`, line ~327) plus 3 comment mentions.
   - `app/src/main/java/com/arkiv/player/data/PorDondeVas.kt` — 1 comment mention.
   - `app/src/test/java/com/arkiv/player/data/PorDondeVasTest.kt` — 2 comment mentions.
   - `app/src/test/java/com/arkiv/player/data/ItemDetailResumeTest.kt` — 1 comment mention.

2. **`savePlayback`** (line 1168, name already English) — translate its one-line KDoc and two
   inline comments (`yaEstabaVisto`, "camino MÁS COMÚN..."). Local `yaEstabaVisto` → e.g.
   `wasAlreadyWatched`.

3. **`setWatched`** (line 1191, name already English) — translate its two inline comments
   ("Marcar como visto SÍ es..." / "Al desmarcar..."). No renames needed here.

4. **`borrarFrameDe(episodeId)`** (private, line 1231) — rename to e.g. `deleteFrameFor`.
   **Zero external usages** (grep-confirmed) — it's `private`, so this is a same-file-only rename,
   just update the two call sites inside `savePlayback`/`setWatched` in the same file. Translate
   its KDoc (mixed English/Spanish already, finish the Spanish half).

5. **`obraParaDatos(episodeId)`** (`internal`, line 1243) — rename to e.g. `triviaSubjectFor`.
   Translate its KDoc and locals `ep`, `item`, `episodio`, `temporada`.
   **External ripple needed** (comments only, no real external call sites found — it's called only
   from `PlayerViewModel.kt` which IS a real call site):
   - `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` line ~381 — **real call
     site** (`repo.obraParaDatos(episodeId)`).
   - `app/src/main/java/com/arkiv/player/data/trivia/TriviaFacts.kt` — 1 comment.
   - `app/src/main/java/com/arkiv/player/data/model/Models.kt` — 1 comment.
   - `app/src/test/java/com/arkiv/player/data/trivia/TriviaFactsTest.kt` — 1 comment.

6. **`fichaDeObra(obra)`** (`internal`, line 1282) — rename to e.g. `workSheetFor`. Translate its
   (long) KDoc and locals `tmdb`, `id`, `obra`, `serie`, `capitulo`.
   **External ripple needed:**
   - `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` line ~394 — **real call
     site** (`repo.fichaDeObra(obra)`).
   - `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt` — 1 comment.
   - `app/src/main/java/com/arkiv/player/data/trivia/WorkSheet.kt` — 2 comments.

7. **`TmdbMatch` data class** (line 1347) and its field **`exacto: Boolean`** (line 1359) — this
   is a plain data class, NOT a Room entity/DTO, so the field is safe to rename (e.g. `exact`).
   **External ripple needed** (grep-confirmed, small and precise):
   - `app/src/main/java/com/arkiv/player/data/ditu/DituFuente.kt` line ~130 —
     `?.takeIf { it.exacto }` → `?.takeIf { it.exact }`.
   - `app/src/test/java/com/arkiv/player/data/PickTmdbMatchTest.kt` — a **dedicated test file**
     for `pickTmdbMatch`/`normalizeTitle`/`cleanTitleForSearch` (lines ~104, 117, 126 use
     `.exacto`) — this whole test file should get a full pass (translate its Spanish test names
     too, e.g. `pickTmdbMatch("el senor de los cielos!", ...)` is just test data, leave it, but
     check for Spanish `fun \`...\`` test names in this file).

8. **`normalizeTitle`**, **`pickTmdbMatch`** (lines 1365, 1372 — names already English) —
   translate the KDoc blocks above them (lines ~1319-1346). **Also fix a pre-existing doc-drift
   bug while you're there**: there are two adjacent KDoc blocks before `TmdbMatch` — the FIRST one
   (starting "Título 'desnudo' para buscar en TMDB...", mentions the `" — Pack"` suffix) is
   actually documentation for `cleanTitleForSearch` (the regex at the bottom of the file), not for
   `TmdbMatch`. It got separated from its function at some point. Move it down to sit directly
   above `cleanTitleForSearch` (line 1381) instead of floating above `TmdbMatch`.

9. **`cleanTitleForSearch`** (line 1381, name already English) — translate its one inline comment
   ("Solo el SUFIJO..."). **Leave the giant `noise` regex string completely untouched** — words
   like `latino`, `castellano`, `espanol`, `español` in there are literal release-tag patterns
   being matched against real file names, not prose; this is data, not code (same category as the
   AI-prompt content rule below).

### Hard rules that applied throughout `data/db/` and still apply to whatever's left

- **Every Room entity property with no `@ColumnInfo` annotation IS the literal SQL column name.**
  This schema has zero `@ColumnInfo` uses anywhere. Properties like `.tipo`, `.titulo`, `.origen`,
  `.tituloCanonico`, `.episodiosVistosEnLista`, `.porque`, `.orden`, `.generadoAt`, `.nombre`,
  `.numero`, `.categoria`, `.origenRemoto` on an **entity instance** (`ItemEntity`, `EpisodeEntity`,
  `SkipMarkerEntity`, `LiveFavoriteEntity`, `LiveRecentEntity`, `LiveChannelCacheEntity`,
  `RecomendacionEntity`) must NEVER be renamed. `ArkivRepository.kt` reads/writes these directly in
  many places (e.g. `item.tipo`, `item.tituloCanonico` inside `obraParaDatos` above) — leave those
  exact accesses alone even while translating everything around them.
- **DAO Row/DTO field names are also frozen**, for a related but distinct reason: they're bound to
  `SELECT ... AS alias` in `@Query` strings by exact name match, and Room fails **silently** (leaves
  the Kotlin field null instead of erroring) on a mismatch. Frozen fields:
  `ProgresoConSiguienteRow.siguienteEpisodeId`, `SerieConProgresoRow.episodios`/`.ultimoVistoMs`,
  `VistoRow.episodios`/`.ultimoVistoMs`, `UltimaReproduccionRow.ultimaMs`,
  `FilaDeHistorial.episodio`/`.titulo`/`.tituloCanonico`/`.tipo` (this whole class, including its
  name, was left 100% untouched per earlier precedent), `ContinueRow.framePath` and friends. `TmdbMatch`
  above is a **plain, non-Room data class** — it's fine to rename its field, that rule doesn't apply to it.
- **User-facing strings never translate.** Several `DownloadOutcome.Failed`/error-message strings
  elsewhere in `data/` turned out to surface directly in UI (accessibility text, notification
  bodies) even though they're built in a `data/` file — when in doubt, trace where a string
  actually gets displayed before deciding.
- **Verification pattern that worked well for `data/db/`:** `SyncTriggersTest.kt` and
  `RecomendacionQueryTest.kt` run the actual generated SQL against real SQLite over JDBC — their
  passing is strong evidence that renamed Kotlin identifiers (which get string-interpolated into
  SQL, e.g. `$table`/`$pk`/`$NOW`) didn't change the generated SQL at all. There's no such test for
  `ArkivRepository.kt` itself (no `ArkivRepositoryTest.kt` exists) — the **full test suite passing
  is the only safety net** for this file specifically, so don't skip the full
  `:app:assembleDebug :app:testDebugUnitTest` run after finishing it.

## Workflow to follow (established over ~35 commits this session)

1. Read the file(s) fully before touching anything.
2. For every rename: grep ALL real usages first — **including `app/src/debug/`**, which has
   caused real compile breaks twice this session from being forgotten — verify receiver types
   before assuming a match (two unrelated types can coincidentally share a method/property name).
3. Rename + ripple with Edit/targeted `sed`. **BSD/macOS `sed` does not support `\b` word-boundary
   syntax reliably** (confirmed to fail silently, not error) — use literal substrings instead, and
   watch for a broad pattern accidentally matching inside a longer, unrelated identifier (happened
   once with a catch-all `EstadoDeDescarga` → `DownloadDisplayState` sed that also mangled
   `LineaDeEstadoDeDescarga`).
4. Kotlin named-argument risk: renaming a function/constructor parameter breaks every call site
   using it as a named argument. Grep for `paramName =` specifically, not just the bare identifier.
5. Compile: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, fix everything.
6. Full test: `./gradlew :app:assembleDebug :app:testDebugUnitTest`, then verify the **exact**
   count via:
   ```
   command grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml | \
     command grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | \
     awk -F'"' '{tests+=$2; skipped+=$4; failures+=$6; errors+=$8} END {print "tests="tests, "skipped="skipped, "failures="failures, "errors="errors}'
   ```
   Current baseline: **1704 tests, 0 skipped, 0 failures, 0 errors.** Must stay exactly there (or
   higher, never lower) after every commit.
7. Stage only the specific files touched — **never `git add -A`**, other sessions may share this
   working tree.
8. Commit as `lordmacu <10134930+lordmacu@users.noreply.github.com>` (already the repo's git
   identity), message style `refactor(data): ...` / `refactor(ui): ...`, imperative, short. **Never
   add a `Co-Authored-By: Claude` line or any attribution footer** — the repo's root `CLAUDE.md`
   forbids it, which overrides the harness's own default attribution reminder.

## Established exception categories — what stays in Spanish, always

a. **UI-facing strings/labels/dialog text** shown directly to the end user (Bogotá Spanish, tuteo,
   never voseo) — e.g. "Bajando 42%", "¿Cancelar la descarga?", library section labels.
b. **Verbatim historical log-line quotes** documenting a measured bug (quoted exactly as observed).
c. **On-disk/serialized identifiers**: Room entity bare properties (see the hard rule above),
   SharedPreferences key/value literals, JSON field-name literals in files persisted across app
   versions — only the Kotlin *constant names* holding these literals may be translated, never the
   literal values.
d. **Symbols in files not yet processed** are referenced by their old Spanish name until that
   file's own turn; ripple into consumer files uses **minimal, targeted fixes** only (the specific
   renamed symbol), never a full translation of an out-of-scope file.
e. **Natural-language content sent to/from an LLM as prompt or data** (Kilo AI prompts, status
   strings fed into a prompt, filename-noise-pattern regexes like the one in
   `cleanTitleForSearch`) — this is data, not code.
f. Two `ContentSource` implementations — **`MagisFuente`** and **`DituFuente`** — deliberately kept
   their Spanish CLASS NAMES (internals/params/comments were translated) because renaming them
   ripples into 10+ files across `data/` and `ui/`, including the not-yet-translated
   `ArkivRepository.kt`/`ui/`. Revisit this decision once `ArkivRepository.kt` and `ui/` are done —
   it may become cheap to finish at that point.

## Next steps after `ArkivRepository.kt` is finished

1. Update the auto-memory file `code-must-be-english.md` (in the user's memory system, not this
   repo) to mark `data/` as **100% done**.
2. Move on to `ui/` (~110-113 files) — **not started at all yet**. This is the single largest
   remaining chunk of the whole sweep. No sub-scoping has been done yet; a fresh agent should
   survey `ui/`'s subpackages (probably `ui/home/`, `ui/detail/`, `ui/search/`, `ui/library/`,
   `ui/player/`, `ui/tv/`, `ui/catalog/`, `ui/live/`, `ui/settings/`, `ui/components/`, `ui/offline/`,
   etc.) and propose a lowest-to-highest-risk order the same way `data/`'s subpackages were ordered,
   rather than assuming a single pass will work — `ui/` files are much more heavily
   cross-referenced with Compose recomposition concerns and are where nearly all the genuinely
   user-facing Spanish strings live, so the developer-facing/user-facing split needs extra care
   per file (a Compose `@Composable` function often mixes both in the same few lines).
