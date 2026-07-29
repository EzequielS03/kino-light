# Búsqueda por Fases — Diseño

**Fecha:** 2026-07-24
**Estado:** Aprobado (pendiente revisión final del usuario)

## Problema

El catálogo actual funciona bien para películas (un torrent con el nombre de la peli es fácil
de casar). Falla para **series y anime**: casi nadie publica un capítulo suelto como torrent;
lo que existe son **packs** (temporadas/series completas). La experiencia de catálogo, que obliga
a navegar por una grilla de tendencias y entrar a una card fija, no acompaña bien la intención
de "quiero *esto* específico, y si es serie a lo mejor quiero la serie completa".

Queremos una **pantalla nueva de búsqueda guiada por fases** que reutilice al máximo lo ya
construido, sin tocar ni reemplazar el catálogo.

## Alcance

**Incluye:**
- Wizard de búsqueda: texto → cards → (si serie) temporada/capítulo opcional → resultados multi-fuente → reproducir.
- Fase 1 unificada: TMDB (multi) + capa de anime + resultados directos torrent/archive.
- Metadata de la card que **viaja** con el resultado y **enriquece** lo que se guarda en la biblioteca.
- Reproducir no-pack reutilizando "ver en dispositivo" y "En la TV".
- **Acción de pack**: si el resultado es un pack, abrir el flujo de guardar-pack-como-serie **ya
  desarrollado** (reutiliza `PackDialog` + `PackResolver` + `ArkivRepository.savePackAsSeries`).

**No incluye (fuera de alcance por ahora):**
- Nada nuevo de packs: la lógica de guardado ya existe y solo se **reutiliza** desde esta pantalla.

## Decisiones tomadas

1. **Flujo nuevo dedicado** — pantalla propia, no depende de `CineDetailScreen`. Comparte APIs
   (TmdbApi, TorrentSearchApi, TorrentEngine, ArkivRepository, WebSourceEngine, SyncManager).
2. **Entrada:** icono de lupa en la barra superior del Catálogo → `navigate("search")`.
3. **Fase 1 unificada:** TMDB multi + anime + resultados directos.
4. **Fase 3 (serie):** un solo paso con temporada/capítulo **opcionales**.
5. **Packs:** en alcance vía **reutilización** — si el resultado es pack, abrir `PackDialog`
   (guardar-pack-como-serie ya desarrollado). No se implementa lógica nueva de packs.
6. **Enriquecimiento:** la card lleva título, overview, póster, tipo, tmdbId/tmdbType, arte, y
   —si eligió S/E— el título real del episodio. El capítulo se guarda como
   **`Nombre de la serie · SxxExx · Título del episodio`**.

## Recorrido del usuario

- **Caso A — capítulo puntual de una serie:** lupa → escribe "Superman" → toca la card de la serie
  → pone Temporada/Capítulo → Buscar → ve resultados por fuente → elige → dispositivo o TV.
- **Caso B — serie completa (packs):** igual, pero deja S/E vacíos → busca la serie a secas →
  aparecen packs con badge "Pack / serie completa" (solo visibles por ahora).
- **Caso C — película:** toca la card de la peli → se salta S/E → resultados → elige → dispositivo/TV.
- **Caso D — resultado directo:** toca una fila cruda de torrent/archive → va directo a reproducir.

## Arquitectura

### Máquina de estados (en el ViewModel)

```
QUERY ──pick título TMDB/anime──► [movie]  ───────────────► RESULTS
   │                              [series] ──► REFINE ─────► RESULTS
   └──pick resultado directo──────────────────────────────► (reproducir directo)
```

- **QUERY:** barra de texto + dos secciones de resultados (títulos / directos).
- **REFINE:** solo si el título es serie. Campos S/E opcionales + botón Buscar. Para **anime**
  (sin `tmdbId`), el paso ofrece solo "Buscar serie/pack" (la numeración por temporada/capítulo no
  aplica igual); la búsqueda por S/E queda para series TMDB.
- **RESULTS:** búsqueda progresiva y en paralelo por fuente; lista agrupada TORRENT/WEB/ARCHIVE.
  Si un torrent es pack (`PackDetector.isPack`), al tocarlo se abre `PackDialog` (guardar serie /
  reproducir uno); si no, se reproduce directo (dispositivo/TV).

### Componentes nuevos

**1. `TmdbApi.searchMulti(query): List<TmdbItem>`** (nuevo)
Usa `/search/multi`, devuelve pelis + series con su `type` y póster; descarta `person`.
Reutiliza `parseItem`/`imgUrl`/`get` existentes.

**2. `SearchWizardViewModel`** (nuevo) — mantiene:
- `query: String`, `phase: QUERY | REFINE | RESULTS`
- `titleResults: List<TitleCard>` (TMDB + anime), `directResults: List<PlaySource>` (torrent/archive crudos)
- `selected: CardContext?`, `season: Int?`, `episode: Int?`
- `sources: List<PlaySource>` + flags de carga por fuente (torrent/web/archive)
- Acciones: `search(q)`, `pickTitle(card)`, `pickDirect(source)`, `runSourceSearch()`,
  `play(source)`, `playOnTv(source)`, `clear()`

**3. `SearchScreen`** (nuevo) — UI por fase (Compose). Reutiliza los componentes de fila/sección
de resultados (ver "Reutilización").

**4. `CardContext`** (nuevo, modelo) — lo que viaja desde `pickTitle` hasta `play`:
```
data class CardContext(
    val title: String,
    val overview: String?,      // descripción de la card
    val posterUrl: String,
    val type: String,           // "movie" | "series"
    val tmdbId: Int?, val tmdbType: String?,
    val searchTitles: List<String>,   // títulos para buscar torrents (vía TmdbApi.detail)
    val year: String,
    // solo si eligió S/E:
    val season: Int?, val episode: Int?, val episodeTitle: String?,
)
```

### Reutilización (máxima, es requisito)

Se **promueven** (mueven a un archivo compartido, ej. `ui/catalog/PlaySources.kt`) las piezas que
hoy viven dentro de `CineDetailScreen`, para que las use tanto CineDetail como el wizard **sin que
el wizard dependa de la pantalla**:

- `sealed interface PlaySource { Torrent | Archive | Web }`
- Composables `SourceSection` y `SourceRow` (render de resultados agrupados por fuente).

Se reutilizan **tal cual** (sin cambios) las APIs:
- Búsqueda torrent: `TorrentSearchApi.searchMovieFlow`, `searchEpisodeFlow`, `searchAnimeBrowseFlow`.
- Búsqueda web: `WebSourceEngine.searchFlow(SearchContext)`.
- Búsqueda archive: `graph.api.search(q)`.
- Búsqueda anime (Fase 1): `AnimeApi.search(query)`.
- Motor: `TorrentEngine.warmUp/resolveMagnet/resolveTorrent/videoFiles/pickVideo`.
- Reproducción/guardado: `ArkivRepository.addSeriesEpisode`, `addSeriesEpisodeMagnet`,
  `addTorrent`, `addTorrentMagnet`, `addItem`, `firstEpisodeId`; `EpisodeFilePicker.pick`.
- TV: `SyncManager.playOnTv(episodeId)` (mismo patrón que ArkivRoot).
- **Packs (flujo completo, ya desarrollado):** `PackDetector.isPack(name)` para detectar,
  `PackDialog` (composable) para elegir/renombrar, `graph.packResolver` (`PackResolver.resolve`),
  y `ArkivRepository.savePackAsSeries(title, posterUrl, description, infoHashHex, infoBytes, rows)`.
  El wizard le pasa `title`/`posterUrl`/`overview` desde el `CardContext`.

La orquestación de `play/playArchive/playWeb` del wizard replica la lógica ya probada de
`CineDetailScreen.play()` (resuelve magnet/.torrent → add* → `firstEpisodeId` → `onPlay`/`playOnTv`).
Si al implementar resulta natural, se extrae a un helper compartido; si no, se copia el patrón.

### Fase 1: unificación de fuentes

Al escribir texto, el ViewModel dispara **en paralelo**:
- `TmdbApi.searchMulti(q)` → `TitleCard`s (pelis + series).
- `AnimeApi.search(q)` → `TitleCard`s de anime (marcadas como serie).
- Torrent/archive por texto (`searchMovie`/`graph.api.search`) → `directResults` (`PlaySource` crudos).

Presentación: dos secciones — **"Películas y series"** (grilla de cards con póster) y
**"Resultados directos"** (filas `SourceRow`). Cada fuente puebla su sección apenas responde.

**Anime en Fase 1:** las cards de anime (de `AnimeApi.search`) se marcan como serie pero **no tienen
`tmdbId`**. En RESULTS usan `searchAnimeBrowseFlow` (búsqueda por títulos, orientada a packs), no
`searchEpisodeFlow`. El enriquecimiento de arte TMDB no aplica; se guarda con póster/título del anime.

### Enriquecimiento de metadata (la card "viaja")

Al elegir una card, `CardContext` se completa con `TmdbApi.detail(type, tmdbId)` (para `searchTitles`,
`year`, `overview`) y —si eligió S/E— con `TmdbApi.seasonEpisodes(...)` (para `episodeTitle`).

Al reproducir/guardar:

| Dato de la card | Hoy | Con enriquecimiento |
|---|---|---|
| Título ítem | del torrent | **de la card** |
| Descripción ítem | `null` | **overview de la card** (nuevo param `description` en add*) |
| Tipo movie/serie | autodetección | **`categoryOverride`** (ya en `addSeriesEpisode`; agregar en el path de película) |
| Póster | ya llega | se mantiene |
| Label del capítulo | `T{s} · E{e}  {nombre}` | **`{serie} · S{s}E{e} · {título ep}`** |
| Arte (backdrops/hero) | no se guarda | poblar **`ArtworkEntity`** (tmdbId, tmdbType) cuando haya card |

Cambios mínimos en `ArkivRepository`:
- Agregar parámetro `description: String? = null` a `addSeriesEpisode`, `addSeriesEpisodeMagnet`,
  `addTorrent`, `addTorrentMagnet` y persistirlo en `ItemEntity.description`.
- Ajustar el `label` del episodio en `addSeriesEpisode`/`addSeriesEpisodeMagnet` a
  `"{showTitle} · S{season}E{episode} · {episodeName}"` (formateo `S%02dE%02d`).
- Cuando venga `tmdbId`, escribir/actualizar `ArtworkEntity` (reutilizar el flujo de artwork ya existente).

El **camino directo (Caso D)** no tiene card → usa las firmas por defecto (sin `description`,
sin arte), igual que hoy. Sin regresión.

## Navegación

- `ArkivRoot`: nueva ruta `composable("search") { SearchScreen(onPlay = navigateToPlayer, onBack = popBackStack) }`.
- En la barra superior del Catálogo (`CineCatalogScreen`), agregar `IconButton` con `Icons.Default.Search`
  que llame `onOpenSearch` → `navController.navigate("search")`.
- Reproducir usa el mismo `navigate("player/{id}")` que el resto de la app.

## Manejo de errores

- Sin resultados por fuente → mensaje por sección; las otras fuentes siguen mostrando.
- Torrent sin seeds / sin video → mismo mensaje que CineDetail ("No se pudo abrir el torrent…").
- `.torrent` ilegible → "No se pudo leer el .torrent".
- Fase 1 totalmente vacía → estado vacío con sugerencia de reformular.
- Cancelación: al reescribir el query o volver atrás, se cancela el `searchJob` en curso (patrón de CineDetail).

## Testing

- `TmdbApi.searchMulti`: parseo movie/tv, descarta `person`, arma póster.
- Formateo del label de episodio: `"{serie} · S02E05 · {título}"` con y sin título de episodio.
- `ArkivRepository`: `description` se persiste; label enriquecido; `ArtworkEntity` poblado cuando hay tmdbId.
- Máquina de estados del ViewModel: pick movie → RESULTS; pick series → REFINE; pick directo → reproducir;
  S/E vacíos → búsqueda "serie completa"; S/E llenos → `searchEpisodeFlow`.
- Los flujos de torrent/web/archive y el motor ya tienen su cobertura existente (no se re-testean).

## Revisión de arquitectura (2026-07-24, tras mapear el código) — HÍBRIDO

Al implementar se descubrió que **las fases 3-5 ya existen completas y por duplicado**, y que la app
**ya tiene capa de anime con cards**. Decisiones actualizadas del usuario:

- **Anime = cards vía AniList** (`AniListApi.browse(page, sort, search, genre)` → `AnimeShow` con
  `posterUrl`, `id: Long`, `description`). La resolución de episodios/packs de anime ya existe en
  `AnimeSourceProvider` y `AnimeShowDetailScreen`.
- **Arquitectura HÍBRIDA:** el wizard hace **solo la Fase 1** (búsqueda unificada → cards TMDB +
  cards anime + resultados directos) **+ un paso REFINE opcional** (temporada/capítulo para series,
  episodio para anime), y luego hace **handoff (deep-link)** a la pantalla de detalle existente:
  - Card película TMDB → `cine/movie/{tmdbId}` (sin REFINE).
  - Card serie TMDB → REFINE opcional → `cine/tv/{tmdbId}?season=&episode=` (con S/E) o sin args.
  - Card anime → REFINE opcional → `catalog_anime/{anilistId}?episode=` o sin args.
  - Resultado directo (torrent/archive crudo) → reproducir directo en el wizard (path mínimo).
- **Reutilización:** REFINE real, búsqueda multi-fuente, reproducir, "En la TV" y **PackDialog** los
  siguen haciendo `CineDetailScreen`/`AnimeShowDetailScreen` — el wizard NO los reimplementa.
- **Deep-link:** se añade a `CineDetailScreen`/`AnimeShowDetailScreen` un parámetro S/E **opcional**
  que, si viene, auto-selecciona la temporada y **auto-abre los resultados** de ese episodio. Sin
  args, las pantallas se comportan igual que hoy (sin regresión).
- **Enriquecimiento:** al tocar las pantallas de detalle para el deep-link, se aprovecha para
  **pasar `description`** (overview/description) a sus llamadas `add*` (el label `Serie·SxxExx·Título`
  de Task 2 ya aplica a todos los callers automáticamente).
- Esta revisión **deja obsoletas** las secciones previas que describían un `SearchWizardViewModel`
  que reimplementaba fases 3-5 y un `CardContext` con `searchTitles`/`episodeTitle`. El plan de
  implementación (tareas 5-7) refleja el híbrido.

## Riesgos / notas

- `searchMulti` mezcla tipos; hay que mapear bien `media_type` → `type` interno ("movie"/"series").
- Promover `PlaySource`/`SourceRow`/`SourceSection` fuera de `CineDetailScreen` toca esa pantalla
  (solo imports); es refactor de bajo riesgo y mantiene una sola fuente de verdad.
- El enriquecimiento de `ArtworkEntity` debe respetar el flujo actual (no re-buscar en cada carga).
