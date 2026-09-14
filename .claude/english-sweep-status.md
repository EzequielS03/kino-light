# English translation sweep — status

Standing directive (from the user, 2026-09-13/14): sweep the whole `app/` codebase module by
module, lowest risk to highest risk, translating every developer-facing Spanish identifier,
comment, and KDoc to English. User-facing strings (Bogotá Spanish, tuteo, never voseo) are **out
of scope** and must stay exactly as they are. This supersedes the older "only new code must be
English" line in `.claude/reglas.md`.

Order chosen by the user: **módulo por módulo, de menor a mayor riesgo** (module by module,
lowest to highest risk).

## Overall completion: roughly **82-85%** of the whole sweep

- `playback/`, `security/`, `dlna/`, `cast/`, `thumbnails/`: **100% done.**
- `data/`: **~98% done** — three files left now: `MagisEntities.kt`, `DituEntities.kt`, and
  `LibraryGrouping.kt` (found this session — `LibraryGroup.nuevos` and other content still
  Spanish; see below).
- `ui/` (159 main files across 14 subpackages, plus 6 top-level files, plus tests): roughly
  **77-80% done**. Fully finished packages: `detail/`, `downloads/`, `components/`, `offline/`,
  `library/`, `theme/`, `update/`, `settings/`, `home/`, `search/`, `catalog/`, `live/`, plus all 6
  top-level `ui/*.kt` files. `tv/` is IN PROGRESS this session — 21 of 26 main files done, only
  `TvHomeScreen.kt` (58K) and `TvSearchScreen.kt` (72K) remain, the two largest files in all of
  `ui/`. Not touched at all: `player/` (23 files, the single largest untouched package).

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

### `ui/` — more fully done packages (catalog/ and live/)

- **`catalog/`** (10/10 + 4/4 test, commit `1a12c7f1`): `EstadoDeCanales.kt`→`ChannelsState.kt`,
  `CapitulosPorTemporada.kt`→`ChaptersBySeason.kt`, `PlaySources.kt`, `MagisSeasonDialog.kt`,
  `CineDetailScreen.kt`, `AnimeShowDetailScreen.kt`, `CaracolScreen.kt`, `CineCatalogScreen.kt` all
  translated. `AnimeSection.kt` was already fully English.
- **`live/`** (9/9 + 9/9 test) — fully translated across several commits:
  `IndiceDelCajon.kt`→`DrawerIndex.kt`, `LiveZapping.kt`/`LiveZappingSource`,
  `RecentLiveChannels.kt`, `CanalesDelPais.kt`→`CountryChannels.kt`,
  `DpadDelDrawer.kt`→`DrawerDpad.kt` (with `DrawerFocus`/`DrawerAction`), `LiveController.kt`
  (`open`/`preheat`/`invalidate`/`close`, was `abrir`/`precalentar`/`invalidar`/`cerrar`),
  `LiveGuideList.kt` (`currentProgram`/`progressOf`, was `enCurso`/`avance`), `LiveViewModel.kt`
  (`LiveUiState`/`LiveViewModel` fully translated: `channels`/`categories`/`search`/`loading`/
  `chooseCategory`/`requestEpg`/`toggleFavorite`, was `canales`/`categorias`/`busqueda`/
  `cargando`/`elegirCategoria`/`pedirEpgDe`/`alternarFavorito`), and finally `LiveScreen.kt`
  itself (`LocalView`/`CategoryChip`/`ErrorWithRetry`/`ChannelGrid`/`ChannelCard`, `onOpenChannel`
  param, local `open()`/`favorite()`). Rippled into `TvCajonDeCanales.kt`, `PlayerScreen.kt`,
  `PlayerVivo.kt`, `TriviaDelPlayer.kt`, `SpinnerDelPlayer.kt`, `AppGraph.kt`,
  `PlaybackService.kt`, `LiveHlsProxy.kt`, `PlayerSource.kt`, `TvLiveGuideScreen.kt`,
  `CaracolScreen.kt`, `HomeScreen.kt`, `ArkivRoot.kt`, `data/db/Daos.kt`, `data/gateway/LiveModels.kt`
  (comment-only fixes in the last two — those files are not yet processed themselves).
  `LiveChannel`/`LiveRecentEntity`/`LiveChannelCacheEntity`/`LiveProgram` field names
  (`nombre`/`numero`/`logo`/`titulo`/`inicio`/`fin`/etc.) deliberately kept as-is: gateway-bound.
  `LiveCatalogGateway`'s own interface method names (`categorias`/`canales`/`epg`) also kept as-is:
  that interface lives in `data/gateway/LiveApi.kt`, not yet processed.

### `ui/tv/` — IN PROGRESS (started this session, not yet complete)

Fully translated so far (main + matching tests where they exist):

- `NavSound.kt`, `TvButtonStyle.kt` (comments only).
- `TvSettingsWidgets.kt` (`tvButtonColors`/`tvButtonBorder`, was `tvBotonColors`/`tvBotonBorder`).
- `TvTab.kt` (`label`/`selected`, was `etiqueta`/`seleccionada`; `TAB_HEIGHT`, was `ALTO_TAB`) —
  rippled into `TvSeccionesDeCatalogo.kt`, `TvSettingsScreen.kt`, `TvCaracolScreen.kt` (named-arg
  call sites).
- `TvSourceRows.kt` (`tvSourceRow`, was `tvFilaDeFuente`; `CARD_HEIGHT`/`MARGIN`, was
  `ALTO_TARJETA`/`MARGEN`) — rippled into `TvSearchScreen.kt`'s named-arg call site.
- `TvSettingsCuenta.kt` (`account`/`onLinkMagis`/`TvLinkedSection`, was
  `cuenta`/`onVincularMagis`/`TvVinculadaSection`) — rippled into `TvSettingsScreen.kt` and
  `AccountSection.kt`'s comment.
- `TvPosterCard.kt` (comments only).
- `TvSettingsSubtitulos.kt`→`TvSettingsSubtitles.kt` (`TvSettingsSubtitles` fun).
- `TvLanguageOrderEditor.kt` (`TvLangButton`, was `TvLangBoton`).
- `TvSettingsScreen.kt` (`TvSettingsTab` enum with `SUBTITLES`/`ACCOUNT`/`APP` and `label`, was
  `TabDeAjustesTv` with `etiqueta`; `magisAccount`/`linkingMagis`/`magisState`/`firstTabFocus`
  locals).
- `library/TvLibraryViewModel.kt` (`groups`/`watched`/`removeGroup`, was
  `grupos`/`vistos`/`quitarGrupo`) — rippled into `library/TvLibraryScreen.kt`'s call sites (that
  screen itself is NOT yet translated).
- `TvSettingsApp.kt` (`TvAdultsSection`/`TvChangeAdultsCode`, was
  `TvSeccionAdultos`/`TvCambiarCodigoDeAdultos`).
- `TvComponents.kt` (`TvLandscapeCard`'s `newEpisodes` param, was `nuevos`).
- `TvEpisodeChip.kt` (comments only; identifiers were already English).
- `TvFormularioConTeclado.kt`→`TvKeyboardAndFields.kt` and `TvOfertaVincularMagis.kt`→
  `TvMagisLinkOffer.kt`, translated together since the second is the sole caller of the first:
  `TvFocusedFields`/`rememberTvFocusedFields`/`TvKeyboardAndFields`/`TvFieldChip`/
  `TvPasswordVisibilityButton`/`KEYBOARD_WEIGHT`/`FIELDS_WEIGHT` (was `TvCamposConFoco`/
  `rememberTvCamposConFoco`/`TvTecladoYCampos`/`CampoTvChip`/`TvBotonMostrarPassword`/
  `PESO_TECLADO`/`PESO_CAMPOS`), and `shouldOfferMagisLink`/`TvMagisLinkOffer(account, onNotNow)`
  (was `debeOfrecerVincularMagis`/`TvOfertaVincularMagis(cuenta, onAhoraNo)`) — rippled into
  `ArkivTvRoot.kt`, `TvSettingsScreen.kt`, `SettingsStore.kt`, `MagisAccount.kt`,
  `TvHomeScreenParaTiTest.kt`'s comment references (those files are not yet fully processed).

Also fully translated (added later in the session):

- `library/TvLibraryScreen.kt` (`TvMenuItem`/`seriesSubtitle`/`TvPosterGrid`/`TvLibraryItemDialog`
  with English params; `LibraryGroup.nuevos` left as-is — belongs to `data/LibraryGrouping.kt`,
  not yet processed, a third `data/` gap alongside `MagisEntities.kt`/`DituEntities.kt`).
- `library/TvDownloadsSection.kt` (`DownloadConfirmation` enum with `STOP`/`REMOVE`,
  `TvDownloadActionsDialog` with English params) — **this completes `ui/tv/library/` fully.**
- `TvKeyboard.kt` + test (`TvKeyboardMode.UPPER`/`LOWER`/`SYMBOLS`, was `MAYUS`/`MINUS`/`SIMBOLOS`;
  `TvKey.Mode`, was `TvKey.Modo`; `onMode` param, was `onModo`) — rippled into
  `TvKeyboardAndFields.kt`/`TvMagisLinkOffer.kt`.
- `TvRowBrowseScreen.kt` (`BROWSE_HERO_SCALE`/`BROWSE_HERO_DRIFT_MS`, comments) —
  `TraerConScrollMinimo`/`Featured` (from `TvHomeScreen.kt`) left as-is.
- `TvCategoriasScreen.kt` (`HERO_SCALE`/`HERO_DRIFT_MS`, locals, comments) — same not-yet-processed
  externals left as-is.
- `TvCaracolScreen.kt` (`TvCaracolContent`/`TitleRow`/`HeroText`/`Message`/`Focused`/`CaracolRow`,
  was `TvCaracolContenido`/`FilaDeTitulos`/`TextoDelHero`/`Mensaje`/`Enfocado`/`FilaDeCaracol`, plus
  all locals) — `TvCapitulosDeCaracol`/`PivotoDeTv`/`TraerConScrollMinimo` left as-is (belong to
  `TvSearchScreen.kt`/`TvHomeScreen.kt`).
- `ArkivTvRoot.kt` (`magisConfirmed`/`showOffer`/`magisState`/`unlocked`/`scope` locals, full
  comment translation) — named args for not-yet-processed screens kept matching their real
  Spanish param names.
- `TvCajonDeCanales.kt`→`TvChannelDrawer.kt` (`TvChannelDrawer` with `focus`/`onFocus`/
  `onChooseChannel`/`currentChannel` params, `DrawerItem`/`DrawerChannelRow`/`DrawerMessage`) —
  rippled into `PlayerVivo.kt`'s import and call site.
- `TvLiveGuideScreen.kt` (`onWatchChannel`/`onBack` params, `TvLocalView`/`TvCategoryChip`/
  `TvGuideMessage`/`TvChannelRow`) — rippled into `ArkivTvRoot.kt`'s call site.
- `TvDetailScreen.kt`'s remaining dense comment blocks (identifiers were already English from the
  earlier `ChapterLabel` ripple).
- `TvSeccionesDeCatalogo.kt`→`TvCatalogSections.kt` (`TvCatalogSections` with `includeAdults`/
  `onPlay`/`onBack` params, `HeroBackground`/`HeroText`/`rowsOf`/`metadataFor`/`ItemsRow`/
  `Message`/`CatalogRow`) — `ItemDeCatalogo`/`SeccionDeCatalogo` (from `data/gateway/`, not yet
  processed) and their fields left as-is. Rippled into `ArkivTvRoot.kt`'s call site and stale
  comment references in `TvTab.kt`/`TvHomeScreen.kt`/`TvSettingsScreen.kt`/`TvCaracolScreen.kt`.

**NOT yet touched**: only `TvHomeScreen.kt` (58K, the largest file in the package) and
`TvSearchScreen.kt` (72K, the single largest file in all of `ui/`) remain. Both still fully
Spanish; both are heavily cross-referenced by already-translated files (`PivotoDeTv`,
`TraerConScrollMinimo`, `Featured`, `TvRowLabel` all live in `TvHomeScreen.kt`; `TvCapitulosDeCaracol`,
`TvSeasonChip`, `TvRefineRow` in `TvSearchScreen.kt`) — expect heavy ripple both ways once these
two are tackled. This is the **second-largest** `ui/` package and likely high-risk (TV-specific
focus/navigation logic) — these two files alone are roughly as much code as everything already
done in this package combined.

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
   `library/`, `theme/`, `update/`, `settings/`, `home/`, `search/`, `catalog/`, `live/` are done.
   `tv/` is 21/26 files done — only `TvHomeScreen.kt` (58K) and `TvSearchScreen.kt` (72K) remain,
   then `player/` last (23 files, likely highest-risk — actual playback logic, completely untouched).
2. After `ui/` is fully done, circle back to close the three remaining `data/` gaps
   (`MagisEntities.kt`, `DituEntities.kt`, `LibraryGrouping.kt`) with the same careful treatment as
   the original `data/magis/`/`data/ditu/` sweeps — these are self-contained enough that they
   don't block `ui/` progress, but the sweep isn't truly 100% without them.
3. Once both `data/` and `ui/` are 100%, revisit whether `MagisFuente`/`DituFuente` class names are
   now cheap enough to rename too (exception f above).
4. **Whenever a bare top-level `fun`/`val` gets renamed** (not a class/object member), grep
   separately for `^import .*\.<oldName>$` — a call-site-anchored sed pattern will not catch a bare
   import line, and that's a real, previously-hit compile break (see the lesson noted near the top
   of this doc).
4. Update this document and the auto-memory file `code-must-be-english.md` again at the next
   natural pause point — it drifts fast given how many files this sweep touches per session.
