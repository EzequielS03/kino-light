# Diseño — Fuente "web" (scraping estilo Alfa) en Arkiv

**Fecha:** 2026-07-22
**Rama:** `feat/torrent-buffering-tier1` (o rama nueva `feat/web-source-tier1`)
**Estado:** Diseño aprobado, pendiente plan de implementación

## Objetivo

Agregar un tercer tipo de fuente de contenido — **web** (scraping de sitios de streaming en español, estilo addon Alfa de Kodi) — que conviva con las fuentes **torrent** y **archive.org** que ya existen. El resultado se **pinta** en dos lugares y las webs se configuran por **JSON** para agregarlas fácil.

**Corte de esta fase:** llegar hasta que la fuente web **se pinte** (grid + hoja de fuentes). El click → resolver embed → reproducir queda **explícitamente fuera** (fase siguiente, cuando termine el reproductor). Cada fuente ya viaja como un tipo autodescriptivo con su `pageUrl`, de modo que el reproductor futuro sepa despachar por tipo.

## Referencia de implementación (regla)

Ante cualquier duda de diseño/implementación, **consultar el código real de Alfa y portar su solución** (aunque sea Python) — ya resolvieron estos casos borde. Repo: [alfa-addon/addon](https://github.com/alfa-addon/addon). Mapa de referencia:
- Enriquecimiento título→metadata: `core/tmdb.py` (`set_infoLabels_itemlist`) → guía para `WebTmdbMatcher`.
- Patrón browse/search + fallback de dominios: `channels/*.py` + `.json` (`host_alt`, `host_black_list`) → guía para `WebSourceDefinition`/`WebSourceBackend`.
- Cloudflare/proxy: `core/httptools.py`, `core/proxytools*.py`.
- Resolvers embed→stream (fase siguiente): `servers/*.py` (ej. `archiveorg.py`, `doodstream.py`).

## Contexto / cómo funciona hoy

- **Motor declarativo existente (torrent-only):** `data/catalog/providers/` con `providers.json` (asset), `ProviderDefinition`, `DeclarativeHtmlBackend`, `HtmlParser`, `HttpFetcher`/`PageFetcher`, `CloudflareSolver`, `QueryBuilder`, `ProviderRegistry` (merge asset + hot-update remoto desde `blog`). Su salida es `RawTorrent` (magnet/infohash/seeds). Fan-out por `RegistryProviderBackend` con `runCatching` por proveedor.
- **Hoja de fuentes (corazón de la UX):** `ui/catalog/CineDetailScreen.kt` (catálogo TMDB). Tiene `sealed interface PlaySource { Torrent(TorrentResult), Archive(ArchiveSearchResult) }`. Al abrir una película o un capítulo (`runSearch`), busca torrent + archive **en paralelo** y los muestra combinados en un `ModalBottomSheet` vía `SourceRow`, cada uno con su badge de color. `playSource()` despacha por tipo: torrent→player de torrent, archive→player normal.
- **Grid del catálogo:** `CineCatalogScreen.kt` con `CineCatalogViewModel` (TMDB, `TmdbApi.search/category` es-MX → `List<TmdbItem>` con `searchTitles`). Las tarjetas (`TmdbItem`) están keyed por `tmdbId`. (Nota: existe además otro grid Popcorn `CatalogScreen`/`CatalogViewModel` sobre `CatalogApi` — NO es el punto de integración; el que lleva a la hoja de fuentes es `CineCatalogScreen`.)

## Arquitectura elegida (Alfa híbrido: web = enlaces, TMDB = identidad)

La web es la fuente de **catálogo/enlaces**; **TMDB es la capa de identidad y metadata** que la enriquece y fusiona. Esto da:
- **Completo:** se ve todo el catálogo de la web, no solo lo que cruza con TMDB.
- **Robusto:** metadata limpia de TMDB, con fallback al póster/título de la web cuando no hay match; una web caída no rompe nada.

### Componentes (paquete nuevo `data/catalog/web/`)

1. **`WebSourceDefinition`** — definición declarativa por sitio, parseada y **validada** desde JSON (espejo de `ProviderDefinition`, devuelve `null` si es inválida sin romper el resto). Campos: `id`, `name`, `enabled`, `priority`, `baseUrl`, `hostAlt: List<String>` (fallback de dominios, estilo Alfa `host_alt`), `needsCloudflare`, `languageTokens`, `browse: Map<kind, pathTemplate>`, `search: pathTemplate`, `keywords: Map<kind, template>`, `parser: WebParserRules`.
   - `WebParserRules`: extracción de filas por `rowSelector` (CSS) **o** `rowRegex` (estilo Alfa, con grupos capturados — necesario para canales como `cuevana2espanol` que raspan con regex crudo), y `FieldRule` (reusa el `FieldRule` existente de providers: selector CSS + attr + regex + `resolve:"absolute"`) para `title`, `pageUrl`, `poster`, `year`, `kindHint`.

2. **`WebResult`** — un resultado crudo de una web: `data class WebResult(siteId, siteName, title, year, pageUrl, posterUrl, language, quality: String = "", kind, tmdbId: Int? = null, subtitleUrl: String? = null, audioLanguages: List<String> = emptyList())`. `pageUrl` es la carga útil para el reproductor futuro; `tmdbId` lo rellena el matcher; `quality` es la etiqueta cruda del sitio (HD/1080p/720p/CAM/…, estilo Alfa — texto libre, no buckets); `subtitleUrl`/`audioLanguages` quedan reservados y se pueblan en la fase de resolución (replicando los server-resolvers de Alfa).

3. **`WebSourceBackend`** — ejecuta UNA definición. **Reusa** `PageFetcher`/`HtmlParser`/`CloudflareSolver` existentes (no reinventa fetch ni Cloudflare). Dos funciones:
   - `browse(kind, page): List<WebResult>` — para el grid (con paginación por `page`).
   - `search(ctx: SearchContext): List<WebResult>` — para la hoja de fuentes (usa el mismo `SearchContext` que ya usan los torrents: titles/type/season/episode/year).
   - Aplica `hostAlt` como fallback cuando `baseUrl` falla.

4. **`WebSourceRegistry`** — espejo de `ProviderRegistry`: parsea `assets/web_sources.json`, mergea hot-update remoto desde `blog` por `id` (remoto gana), ordena por `priority`. Lectura fresca en cada búsqueda para reflejar el hot-update sin reconstruir.

5. **`WebSourceEngine`** (fan-out) — espejo de `RegistryProviderBackend`: corre TODAS las webs activas, cada una en su corrutina con `runCatching`; el fallo de una no rompe a las demás. Expone `browse(kind, page)` y `search(ctx)` agregando todas.

6. **`WebTmdbMatcher`** — capa de identidad (el `set_infoLabels_itemlist` de Alfa). Dado un `WebResult`:
   - Normaliza título (minúsculas, sin tildes/puntuación, quita `(2024)`, tokens de idioma/calidad).
   - Consulta `TmdbApi.search(type, query)` es-MX.
   - **Match confiable** (título normalizado igual + año ±1) → setea `tmdbId`, hereda póster/rating de TMDB.
   - **Sin match** → conserva póster/título de la web, `tmdbId = null`.
   - Cache en memoria por sesión para no repegarle a TMDB.

### `assets/web_sources.json` (esquema)

```jsonc
[{
  "id": "cuevana",
  "name": "Cuevana",
  "enabled": true,
  "priority": 60,
  "baseUrl": "https://ejemplo.tld",
  "hostAlt": ["https://espejo2.tld"],
  "needsCloudflare": true,
  "languageTokens": ["latino", "castellano"],
  "browse": { "movie": "/peliculas?page={page}", "tv": "/series?page={page}" },
  "search": "/?s={query}",
  "keywords": { "movie": "{title} {year}", "tv": "{title}" },
  "parser": {
    // Filas: rowSelector (CSS) O rowRegex (estilo Alfa, con grupos). Necesario porque
    // Alfa usa AMBOS (ej. cuevana2espanol extrae con regex crudo sobre HTML ofuscado).
    "rowSelector": "article.item",
    // "rowRegex": "<a href=\"([^\"]+)\".*?<h3>([^<]+)",   // alternativa a rowSelector
    "title":   { "selector": "h2", "attr": "text" },
    "pageUrl": { "selector": "a", "attr": "href", "resolve": "absolute" },
    "poster":  { "selector": "img", "attr": "src" },
    "year":    { "selector": ".year", "attr": "text", "regex": "\\d{4}" },
    "quality": { "selector": ".calidad", "attr": "text" },   // opcional: etiqueta cruda (HD/1080p/CAM…)
    "kindHint":{ "selector": "a", "attr": "href", "regex": "/(pelicula|serie)/" }
  }
}]
```

**Seed inicial — portar las mismas webs de Alfa (máxima compatibilidad).** Alfa NO usa JSON: cada canal es Python. "Sembrar con las de Alfa" = **portar** sus canales de películas/series en español (mismos sitios + misma lógica de extracción), conservando sus `id` de Alfa para trazabilidad. Los 54 canales español activos se clasifican por portabilidad al formato declarativo:

- **Tier 1 — 24 standalone** (más portable, CSS/regex directo): `allpeliculas`, `asialiveaction`, `cine24h`, `cinelibreonline`, `detodopeliculas`, `doramasyt`, `ecarteleratrailers`, `fullseriehd`, `genteclic`, `gnula`, `lacartoons`, `lamovie`, `legalmentegratis`, `mirapeliculas`, `pelicinehd`, `pelis182`, `pelisflix`, `pelisplus`, `repelishd`, `serieskao`, `seriesretro`, `sololatino`, `yandispoiler`, `zonaleros`.
- **Tier 2 — 21 con framework `AlfaChannel`** (portables mapeando sus reglas `finds`/`controls`): `cinecalidad`, `cinemundo`, `divxtotal`, `doramedplay`, `elitetorrent`, `entrepeliculasyseries`, `estrenoscinesaa`, `flizzmovies`, `hacktorrent`, `homecine`, `mitorrent`, `osjonosu`, `pelisforte`, `pelispanda`, `pelispedia`, `poseidonhd`, `retrotv`, `tubeonline`, `tubepelis`, `veronline`, `zoowomaniacos`.
- **Tier 3 — 9 con login/API** (diferidos o con código custom): `allcalidad`, `cuevana2espanol`, `dontorrent`, `doramasflix`, `grantorrent`, `hdfull`, `mejortorrent`, `peliculasflix`, `wolfmax4k`.

**Alcance de esta fase (decidido):** portar **Tier 1 + Tier 2 = 45 canales**; Tier 3 (login/API) diferido. Orden: motor + lote de validación T1 (~3–5, ej. `pelisplus`, `gnula`, `cine24h`) para probar el pipeline punta a punta, luego completar T1, luego T2. Cada canal portado se agrega/actualiza por JSON o hot-update desde `blog` (`ssh blog`) sin recompilar. Para cada uno se consulta su `channels/<id>.py` + `.json` en el repo de Alfa y se traducen sus selectores/regex.

**Nota Tier 2 (framework `AlfaChannel`, en `lib/AlfaChannelHelper.py`):** cada canal define un dict `finds` (`{'find_all': [{'tag': ['article'], 'class': [...]}]}` → se traduce a `rowSelector`) y un callback `list_all_matches` con la extracción por campo (`elem.find(...).get(attr)` → nuestros `FieldRule`). Confirmado mapeable al formato declarativo; solo requiere traducir esos dos por canal. (Portar 45 canales es volumen manual repetitivo — candidato natural a paralelizar con subagentes en la fase de implementación.)

### Idiomas, subtítulos y calidades (replicar Alfa)

Cómo lo resuelve Alfa (a replicar fielmente):
- **Múltiples audios = idiomas por enlace** (nivel canal `findvideos`): cada fuente reproducible se etiqueta con `language` (LAT/CAST/VOSE). No es multi-pista dentro de un archivo, sino **varias fuentes del mismo título, una por doblaje**. → En Arkiv: `WebResult.language` + posiblemente varias `WebResult` por título (una por idioma), igual que hoy cada torrent trae su badge de idioma. Ya visible en la hoja desde esta fase.
- **Calidades** (230 canales): cada canal declara un `list_quality` y etiqueta cada fila/enlace con `quality=` usando **la etiqueta cruda del sitio** (`HD`, `1080p`, `720p`, `CAM`, o `'default'`), con filtro (`filtertools.quality_allowed`) y uso en `autoplay`. **No son buckets fijos** alta/media/baja. → En Arkiv: `WebResult.quality` (texto libre) + un `qualityRank()` opcional para ordenar (reusar el de `CatalogApi`: 4k>1080>720). Algunos sitios exponen la calidad en el **listado** (ej. `dontorrent`, `gnula`) → se parsea con el `FieldRule` `quality`; en la mayoría está a nivel de **enlace** → se puebla en la fase de resolución.
- **Subtítulos = a nivel server-resolver** (fase de reproducción, diferida): 11 servers de Alfa devuelven una URL de subtítulo junto al stream (`[label, url, 0, subtitle]`). El subtítulo se obtiene **al resolver embed→stream**, no en el listado.

**Qué se hace en esta fase (listado):** `WebResult` carga `language` y `quality` (cuando el listado los expone); y se **reservan** campos para la fase de resolución: `subtitleUrl`, `audioLanguages` (y la `quality` fina por enlace). Así el reproductor futuro recibe idioma/calidad/subtítulo sin rediseñar el modelo. La extracción real de subtítulo, variantes de audio y calidad por enlace se implementa en la **fase de resolución** (server-resolvers de Alfa), replicando su lógica.

### Integración en la UI

**Hoja de fuentes — `CineDetailScreen` (primario):**
- `PlaySource` gana `data class Web(val result: WebResult) : PlaySource`.
- `runSearch` suma un tercer `async { webEngine.search(ctx) }` en paralelo con torrent + archive → `sources = torrents + archive + web`.
- `SourceRow` pinta el badge web (color propio, ej. violeta) mostrando `sitio · idioma · calidad` (calidad solo si el listado la trae).
- `playSource(PlaySource.Web)` = **stub de esta fase**: muestra aviso "Reproducción web: próximamente". El tipo + `pageUrl` quedan listos para que el reproductor futuro despache.

**Grid — `CineCatalogScreen.kt` / `CineCatalogViewModel` (aditivo):**
- Al cargar una página TMDB (`List<TmdbItem>`), se lanza `webEngine.browse(kind, page)` en paralelo; cada `WebResult` pasa por `WebTmdbMatcher`.
- Merge por `tmdbId`: web con `tmdbId` ya presente en el grid → se fusiona (badge "Web"); web con `tmdbId` nuevo o `null` → tarjeta propia con badge "Web".
- Para pintar el badge sin romper `TmdbItem`, la fila del grid se envuelve en un `CineGridCard(item: TmdbItem?, web: WebResult?, sources: Set<SourceKind>)` (o se agrega `sources` a `TmdbItem`); decisión menor a resolver en el plan.
- Degradación limpia: si el backend web falla o el JSON está vacío, el grid queda **idéntico a hoy**.

## Manejo de errores

- Definición JSON inválida → descartada, no rompe el resto (patrón `fromJson` → `null` ya existente).
- Web caída / timeout / Cloudflare no resuelto → esa web devuelve `emptyList()`, las demás siguen.
- TMDB sin match → fallback a metadata de la web.
- `web_sources.json` ausente o vacío → feature invisible; app igual que hoy.

## Testing

- **Unit (JVM puro, como los tests actuales de providers):**
  - Parseo/validación de `web_sources.json` (válidas + inválidas descartadas).
  - `HtmlParser` sobre HTML fixture de una web → `List<WebResult>` (browse y search).
  - `hostAlt`: fallback cuando el primer dominio falla.
- **`WebTmdbMatcher`:** normalización de título; match/no-match con `TmdbApi` fakeado; año ±1.
- **Merge del grid:** web con tmdbId existente fusiona; web sin match crea tarjeta; backend caído no altera el grid.

### Validación por canal portado (obligatorio)

Cada uno de los 45 canales se **valida individualmente** al portarlo — no se da por bueno hasta comprobar que la traducción de selectores/regex es correcta. Dos capas por canal:

1. **Fixture test (determinista, en CI):** se guarda un HTML real de la página de listado del sitio en `test/resources/web/<id>.html`; el test corre el parser del canal contra ese fixture y asserta que devuelve N filas con `title` + `pageUrl` no vacíos (y `poster`/`year` cuando aplique). Detecta traducciones mal hechas sin depender de la red.
2. **Smoke test en vivo (manual/opt-in, no en CI):** un runner (test con tag `@LiveWeb` o pequeño script) golpea el sitio real y reporta por canal: nº de resultados de browse + search de un título conocido. Sirve para ver **cuáles funcionan y cuáles no** hoy (dominio caído, Cloudflare, cambio de HTML).

**Estado por canal:** un canal que falla la validación se deja `enabled: false` en `web_sources.json` con un comentario del motivo (roto/Cloudflare/dominio muerto), en vez de romper el resto. La feature se enciende por canal a medida que se validan. Un reporte de portado (tabla id → ok/roto/motivo) acompaña la implementación.

Dado el volumen (45 canales × validación), el portado + validación es **candidato a paralelizar con subagentes** (uno por canal: lee `channels/<id>.py` de Alfa → emite entrada JSON + fixture → valida), con opt-in del usuario en la fase de implementación.

## Fuera de alcance (fase siguiente)

- Resolver embed → stream directo (los "servers" de Alfa: doodstream, streamtape, voe, archive.org, etc.).
- Reproducción real de la fuente web (el reproductor está en modificación).
- Browse tipo árbol (mainlist→seasons→episodes) de cada web: no hace falta porque la identidad la da TMDB; la web solo necesita browse-lista + search-por-título.

## Archivos afectados (estimado)

- **Nuevos:** `assets/web_sources.json`; `data/catalog/web/WebSourceDefinition.kt`, `WebResult.kt`, `WebSourceBackend.kt`, `WebSourceRegistry.kt`, `WebSourceEngine.kt`, `WebTmdbMatcher.kt`; tests en `test/.../web/`.
- **Modificados:** `CineDetailScreen.kt` (PlaySource.Web + runSearch + SourceRow + stub click); `CineCatalogScreen.kt`/`CineCatalogViewModel` (browse web + merge + badge); `AppGraph.kt` (wiring del engine, reusando el `PageFetcher` existente).
