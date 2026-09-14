# English translation sweep — status

Standing directive (from the user, 2026-09-13/14): sweep the whole `app/` codebase module by
module, lowest risk to highest risk, translating every developer-facing Spanish identifier,
comment, and KDoc to English. User-facing strings (Bogotá Spanish, tuteo, never voseo) are **out
of scope** and must stay exactly as they are. This supersedes the older "only new code must be
English" line in `.claude/reglas.md`.

Order chosen by the user: **módulo por módulo, de menor a mayor riesgo** (module by module,
lowest to highest risk).

## Overall completion: roughly **70-75%** of the whole sweep

- `playback/`, `security/`, `dlna/`, `cast/`, `thumbnails/`: **100% done.**
- `data/`: **~98% done** — two files left, `MagisEntities.kt` and `DituEntities.kt` (see below).
- `ui/` (159 main files across 14 subpackages, plus 6 top-level files, plus tests): roughly
  **55-60% done**. Fully finished packages: `detail/`, `downloads/`, `components/`, `offline/`,
  `library/`, `theme/`, `update/`, `settings/`, `home/`, `search/`, `catalog/`, plus all 6
  top-level `ui/*.kt` files. Partially touched (ripple-only, NOT fully translated) via
  shared-symbol renames: `live/`, `tv/`. Not touched at all: `player/` (23 files, the single
  largest untouched package).

`ui/` is far bigger than `data/` was (159 main files vs. roughly 90 in `data/`), so raw file count
means the overall codebase is still under most-of-the-way-done even though `data/` is essentially
finished and over half of `ui/`'s packages are now clean.

## Hard-won lesson this session: bare-name imports break silently after a rename

When a top-level (non-member) `fun`/`val` gets renamed, `sed` patterns anchored on the call-site
syntax (e.g. `\.etiqueta()` to catch `lang.etiqueta()`) do **not** match a bare import line like
`import com.arkiv.player.ui.settings.etiqueta` (no trailing parens, no receiver). This produced a
real compile break that briefly went undetected because a background gradle run's task-notification
reported "completed" even though the actual build had failed (piping gradle's output through `tail`
masks gradle's own exit code with `tail`'s). **After any bare top-level rename, grep specifically
for `^import .*\.<oldName>$` in addition to the usual call-site grep, and always read the tail of a
background build's output file directly rather than trusting the notification's summary alone when
the change was large.**

## What's fully done and committed (verified: compiles, full suite green at 1704/0/0/0)

### `data/` — 98% done

Every package is translated and verified EXCEPT:

- **`data/MagisEntities.kt`** (330 lines) — one Spanish data class (`CapituloDeTemporada`) plus
  pervasive Spanish locals/params/KDoc throughout `itemIdDe`, `idLegacyDeCapitulo`, `episodioIdDe`,
  `episodioIdDePelicula`, `capituloDe`, `refParaReparar`, `buildSeason`, `stillsDeTemporada`,
  `build`, `nombreCanonico`. Business-critical (chapter/item id derivation for every Magis save
  path) — needs the same careful read-grep-rename-ripple-verify treatment as the original
  `data/magis/` sweep. External ripple confirmed needed into: `SearchPlayback.kt`,
  `RecommendationSaving.kt`, `ReparacionDeMagis.kt` (gateway package), `MagisEntitiesTest.kt`.
  **`episodiosVistosEnLista`-style Room bare columns still apply** if this file constructs
  `ItemEntity`/`EpisodeEntity` — check which locals feed literal column-bound properties before
  renaming.
- **`data/DituEntities.kt`** (~450 lines, similar density expected) — at least two Spanish data
  classes (`CapituloDeCaracol`, `SerieDeCaracol`). Ripple confirmed into `DituEntitiesTest.kt` and
  `ArkivRepository.kt`. Not yet read in full — do that first.

Both were **missed by the earlier "data/ complete" declaration** (caught this session while
rippling an unrelated rename into `ArkivRepository.kt`, which called `PorDondeVas`/`NumeracionCodificada`,
themselves also missed top-level `data/*.kt` files that got fixed this session — see commits
`6eb17c8f` and the `ChapterLabel`/`ScreenFormat`/`EncodedNumbering` batch). **Lesson: a package
being "done" needs a final broad grep across every top-level file in it, not just the subpackages
that were the original focus** — these two were sitting in plain sight the whole time.

Everything else in `data/` (all subpackages, `data/local/`, `data/magis/`, `data/ditu/`,
`data/db/`, `ArkivRepository.kt`, `ContinueWatchingRule.kt` (was `PorDondeVas.kt`),
`StillMerge.kt` (was `MezclaDeStills.kt`), `EncodedNumbering.kt` (was `NumeracionCodificada.kt`),
`EpisodeNavigation.kt`, `WatchedThreshold.kt`) is translated and verified. See git log for the
full commit trail (`data/db/` through `ArkivRepository.kt` finishing at `c805220c`, then the gap
closures at `cad8b4af` and `6eb17c8f`).

### `ui/` — fully done packages

- **`ui/detail/`** (3/3 files): `DetailScreen.kt` (`FichaDelItem`→`ItemHero`,
  `dosPaneles`→`twoPanels`, `estadosDeDescarga`→`downloadStates`, `sePuedeBajar`→`canDownload`,
  `porConfirmar`→`pendingConfirmation`), `DetailViewModel.kt`, `MarkersDialog.kt`.
- **`ui/downloads/`** (2/2): `DownloadsScreen.kt`, `DownloadsViewModel.kt`.
- **`ui/components/`** (2/2): `Components.kt`, `DownloadControl.kt` (was `ControlDeDescarga.kt` —
  `DescargaDeFila`→`RowDownload`, `ControlDeDescarga`→`DownloadControl`,
  `BarraDeDescarga`→`DownloadBar`, `LineaDeEstadoDeDescarga`→`DownloadStatusLine`,
  `DialogoDeDescarga`→`DownloadConfirmDialog`, `AnilloConEquis`→`RingWithX` — this is a **shared
  component used by `catalog/`**, so its ripple already touched `AnimeShowDetailScreen.kt`,
  `CineDetailScreen.kt`, `PlaySources.kt` with minimal named-arg fixes only, not full translation).
- **`ui/offline/`** (2/2): `DuplicateDownloadNotice.kt`, `NotificationPermission.kt`.
- **`ui/library/`** (1/1): `LibraryScreen.kt`.
- **`ui/theme/`** (1/1): `Theme.kt`.
- **`ui/update/`** (1/1): `UpdateDialog.kt`.
- **All 6 top-level `ui/*.kt` files**: `Graph.kt`, `ScreenFormat.kt` (was `FormatoDePantalla.kt` —
  `esTabletHorizontal`→`isLandscapeTablet`, `columnasDeGrilla`→`gridColumns`,
  `anchoDeLectura`→`readingWidth`; this is used EVERYWHERE so its rename rippled into
  `home/CategoriasScreen.kt`/`HomeScreen.kt`/`RowBrowseScreen.kt`, `catalog/CineCatalogScreen.kt`/
  `AnimeSection.kt`/`CaracolScreen.kt`, `library/LibraryScreen.kt`, `search/SearchScreen.kt`,
  `detail/DetailScreen.kt`, `live/LiveScreen.kt`, `ArkivRoot.kt`, `settings/SettingsScreen.kt`,
  `downloads/DownloadsScreen.kt` — import/call-site fixes only in those not-yet-processed files),
  `Format.kt`, `ChapterLabel.kt` (was `EtiquetaDeCapitulo.kt` — `numero`→`number`,
  `conNombre`→`withName`, `avance`→`progressSummary`, `botonReproducir`→`playButtonLabel`,
  `lineaDeHeroe`→`heroLine`; also shared everywhere, same minimal-ripple treatment applied to
  `home/HomeScreen.kt`, `tv/TvEpisodeChip.kt`/`TvDetailScreen.kt`/`TvHomeScreen.kt`,
  `detail/DetailScreen.kt`, `data/db/Daos.kt`), `ArkivRoot.kt` (`irA`→`goToTab`,
  `ancho`→`isWide`), `ArkivSplash.kt` (full translation of the boot-intro geometry/animation code;
  `DURACION_DE_LA_INTRO_MS`→`INTRO_DURATION_MS`, rippled into `MainActivity.kt`).

### `ui/` — more fully done packages (added after the above was first written)

- **`ui/settings/`** (8/8 + 1/1 test): `CandadoDeAdultos.kt`→`AdultsLock.kt`,
  `SeccionDeAdultos.kt`→`AdultsSection.kt`, `SubtitulosTab.kt`→`SubtitlesTab.kt`,
  `TabDeAjustes`→`SettingsTab`, `IDIOMAS_AUDIO`/`IDIOMAS_SUBTITULO`→
  `AUDIO_LANGUAGES`/`SUBTITLE_LANGUAGES`, `TrackLang.etiqueta()`→`.label()`. Ripples into
  `tv/TvSettingsApp.kt`, `tv/TvSettingsSubtitulos.kt`, `tv/TvLanguageOrderEditor.kt`,
  `ui/player/PlayerPistas.kt`, `data/SettingsStore.kt`, `data/gateway/LiveModels.kt`.
  **Caught and fixed a real compile break from this batch**: the `.etiqueta()`→`.label()` sed only
  matched call sites (`lang.etiqueta()`), not the bare `import ...settings.etiqueta` lines in
  `PlayerPistas.kt`/`TvLanguageOrderEditor.kt` — see the lesson noted above.
- **`ui/home/`** (8/8 + 4/4 test): `CategoriasScreen.kt`→`CategoriesScreen.kt`,
  `CategoriasViewModel.kt`→`CategoriesViewModel.kt`, `ANIME_GENRE_ES`→`ANIME_GENRE_LABELS`,
  `MedidasDelHome`→`HomeSizes`, `TopSectionsSignature`'s Spanish fields→English,
  `HomeViewModel.bibliotecaOrdenada`→`orderedLibrary`. Ripples into `ArkivRoot.kt`,
  `tv/TvCategoriasScreen.kt`, `ui/library/LibraryScreen.kt`.
- **`ui/search/`** (8/8 + 8/8 test) — the last package finished before this doc's refresh:
  `FuentesBuscando.kt`→`SearchingSources.kt`, `FuentesCaidas.kt`→`DownSources.kt`
  (`EstadoDeLasFuentes`→`SourcesState`), `tabDeFuente`→`tabForSource`,
  `filasVisibles`→`visibleRows` (in `SourceTab.kt`), `cardDeTextoLibre`→`freeTextCard`,
  `sinRepetidos`→`withoutDuplicates` (in `CardContext.kt`), and `SearchViewModel.kt`/
  `SearchScreen.kt`/`SearchPlayback.kt` fully translated (their whole Magis/Caracol
  save-and-play API: `magisEpisodeIdDe`→`magisEpisodeIdFor`,
  `encolarDescargaDeCaracol`→`enqueueCaracolDownload`, `dituEpisodeIdDe`→`dituEpisodeIdFor`,
  etc). Ripples into `tv/TvSearchScreen.kt`, `ui/catalog/CaracolScreen.kt`,
  `ui/catalog/MagisSeasonDialog.kt`, `data/recomendaciones/RecommendationSaving.kt`,
  `data/MagisEntities.kt`, and `app/src/debug/.../PruebaDeDescargaDeCaracol.kt`.

### `ui/` — NOT fully done (ripple-only touches so far)

These packages have received only the minimal fixes needed to keep them compiling after a shared
symbol was renamed elsewhere (import statements, call-site named args, occasionally a directly
adjacent local variable). **Their own Spanish identifiers/comments are still untranslated** and
each needs its own full pass:

- **`catalog/`** (10 main + 4 test files) — `CineCatalogScreen.kt`, `AnimeSection.kt`,
  `CaracolScreen.kt`, `AnimeShowDetailScreen.kt`, `CineDetailScreen.kt`, `PlaySources.kt`,
  `MagisSeasonDialog.kt` touched by ripple only; the rest untouched. Note `PlaySources.kt`/
  `AnimeShowDetailScreen.kt`/`CineDetailScreen.kt` still have a local
  `porConfirmar`/`accion`/`fila` naming pattern from the `DownloadConfirmDialog` ripple that should
  get properly renamed during this package's own pass.
- **`live/`** (9 main + 8 test files) — `LiveScreen.kt` touched by ripple only (one identifier);
  the rest untouched.
- **`tv/`** (26 main + 4 test files) — `TvEpisodeChip.kt`, `TvDetailScreen.kt` (got a bit more:
  its `episodeMeta` function was fully translated since it was directly touched by the
  `ChapterLabel` ripple), `TvHomeScreen.kt`, `TvSettingsApp.kt`, `TvSettingsSubtitulos.kt`,
  `TvLanguageOrderEditor.kt`, `TvCategoriasScreen.kt`, `TvSearchScreen.kt` touched by ripple only;
  the other ~19 files untouched. This is the **second-largest** `ui/` package and likely
  high-risk (TV-specific focus/navigation logic).

### `ui/` — NOT touched at all

- **`player/`** (23 main + 13 test files) — the **single largest `ui/` package**, zero files
  opened yet except `PlayerPistas.kt` (one ripple fix: a broken import from the settings/ batch,
  its own Spanish content is otherwise untouched). Likely the highest-risk piece of `ui/` given
  it's the actual playback UI (ExoPlayer integration, skip markers, trivia overlay, etc.) —
  probably belongs last per the lowest-to-highest-risk ordering.

## Workflow to follow (established over 45+ commits this session)

1. Read the file(s) fully before touching anything.
2. For every rename: grep ALL real usages first — **including `app/src/debug/`**, which has
   caused real compile breaks from being forgotten — verify receiver types before assuming a match
   (two unrelated types can coincidentally share a method/property name).
3. Rename + ripple with Edit/targeted `sed`. **BSD/macOS `sed` does not support `\b` word-boundary
   syntax reliably** (confirmed to fail silently, not error) — use literal substrings instead.
4. Kotlin named-argument risk: renaming a function/constructor parameter breaks every call site
   using it as a named argument. Grep for `paramName =` specifically, not just the bare identifier.
   This bit twice this session (`StillMerge.merge(previa = ...)` compile failure,
   `DownloadConfirmDialog(accion = ...)` call sites in two not-yet-processed `catalog/` files).
5. When a shared/low-level symbol used by not-yet-processed files gets renamed, ripple with
   **minimal, targeted fixes only** into those files (import statements, the renamed call/named
   args) — do NOT fully translate an out-of-scope file just because one line in it had to change.
   Leave a note (like this doc does above) about what's left untouched there.
6. Compile: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, fix everything.
7. Full test: `./gradlew :app:assembleDebug :app:testDebugUnitTest`, then verify the **exact**
   count via:
   ```
   command grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml | \
     command grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | \
     awk -F'"' '{tests+=$2; skipped+=$4; failures+=$6; errors+=$8} END {print "tests="tests, "skipped="skipped, "failures="failures, "errors="errors}'
   ```
   Current baseline: **1704 tests, 0 skipped, 0 failures, 0 errors.** Must stay exactly there (or
   higher, never lower) after every commit.
8. Stage only the specific files touched — **never `git add -A`**, other sessions may share this
   working tree. When a file was renamed with `git mv` and then edited, `git add` the *new* path to
   pick up both the rename and the content diff.
9. Commit as `lordmacu <10134930+lordmacu@users.noreply.github.com>` (already the repo's git
   identity), message style `refactor(data): ...` / `refactor(ui): ...`, imperative, short. **Never
   add a `Co-Authored-By: Claude` line or any attribution footer** — the repo's root `CLAUDE.md`
   forbids it, which overrides the harness's own default attribution reminder.

## Established exception categories — what stays in Spanish, always

a. **UI-facing strings/labels/dialog text** shown directly to the end user (Bogotá Spanish, tuteo,
   never voseo) — e.g. "Bajando 42%", "¿Cancelar la descarga?", library section labels,
   `contentDescription`, snackbar text, dialog titles/bodies.
b. **Verbatim historical log-line quotes** documenting a measured bug (quoted exactly as observed).
c. **On-disk/serialized identifiers**: Room entity bare properties (every entity in this schema has
   zero `@ColumnInfo` annotations, so the bare Kotlin property name IS the SQL column name —
   `.tipo`, `.titulo`, `.origen`, `.tituloCanonico`, `.episodiosVistosEnLista`, `.porque`, `.orden`,
   `.generadoAt`, `.nombre`, `.numero`, `.categoria`, `.origenRemoto`, etc. — never rename these),
   DAO Row/DTO fields bound to `SELECT ... AS alias` (Room fails **silently** on a mismatch — e.g.
   `ProgresoConSiguienteRow.siguienteEpisodeId`, `FilaDeHistorial`'s fields and class name),
   SharedPreferences key/value literals, JSON field-name literals persisted across app versions —
   only the Kotlin *constant names* holding these literals may translate, never the literal values.
   Regex literals matching stored text (e.g. `EncodedNumbering`'s `"^Temporada (\d+)$"`) are also
   frozen for the same reason.
d. **Symbols in files not yet processed** are referenced by their old Spanish name until that
   file's own turn; ripple into consumer files uses **minimal, targeted fixes** only.
e. **Natural-language content sent to/from an LLM as prompt or data** (Kilo AI prompts, status
   strings fed into a prompt, filename-noise-pattern regexes like the one in
   `ArkivRepository.cleanTitleForSearch`) — this is data, not code.
f. Two `ContentSource` implementations — **`MagisFuente`** and **`DituFuente`** — deliberately kept
   their Spanish CLASS NAMES (internals/params/comments were translated) because renaming them
   ripples into 10+ files. Revisit once `ui/` is further along.

## Next steps

1. Continue `ui/` lowest-to-highest risk. `detail/`, `downloads/`, `components/`, `offline/`,
   `library/`, `theme/`, `update/`, `settings/`, `home/`, `search/` are done. Remaining, roughly in
   order: `catalog/` (10 files, already has ripple touches in 7 of them), `live/` (9 files, 1
   ripple touch), `tv/` (26 files, second-largest, 7 ripple touches so far), then `player/` last
   (23 files, likely highest-risk — actual playback logic, completely untouched).
2. After `ui/` is fully done, circle back to close the two remaining `data/` gaps
   (`MagisEntities.kt`, `DituEntities.kt`) with the same careful treatment as the original
   `data/magis/`/`data/ditu/` sweeps — these are self-contained enough that they don't block `ui/`
   progress, but the sweep isn't truly 100% without them.
3. Once both `data/` and `ui/` are 100%, revisit whether `MagisFuente`/`DituFuente` class names are
   now cheap enough to rename too (exception f above).
4. **Whenever a bare top-level `fun`/`val` gets renamed** (not a class/object member), grep
   separately for `^import .*\.<oldName>$` — a call-site-anchored sed pattern will not catch a bare
   import line, and that's a real, previously-hit compile break (see the lesson noted near the top
   of this doc).
4. Update this document and the auto-memory file `code-must-be-english.md` again at the next
   natural pause point — it drifts fast given how many files this sweep touches per session.
