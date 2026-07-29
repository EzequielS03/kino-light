# Búsqueda de torrents vía backend Mirror API

**Fecha:** 2026-07-25
**Branch:** `feat/torrents-via-backend-api`
**Estado:** diseño aprobado (enfoque A)

## 1. Objetivo y alcance

Reemplazar la búsqueda de torrents **on-device** por consultas HTTP al backend
**Arkiv Mirror API** (`https://torrents.comparadorinternet.co`) para **películas,
series y anime**.

- **Backend único, sin fallback:** se retira el scraping on-device (providers.json,
  apibay, knaben, WebView/Cloudflare). Si el backend no tiene el título, no hay torrents.
- **Streaming web se mantiene:** la capa `WebSourceEngine` (pelisplus, gnula, cine24h…
  → enlaces directos resueltos por `jackett.comparadorinternet.co/resolve`) queda
  **intacta**; es una feature separada, no son torrents.
- **La reproducción no cambia:** `SearchPlayback`, `ArkivRepository`, `TorrentEngine`
  (libtorrent4j), `VlcPlayer` siguen igual mientras el endpoint devuelva magnet/infohash.

## 2. Contrato del backend (verificado en vivo)

### `GET /api/search`
Devuelve **títulos** (no torrents). Parámetros relevantes: `q`, `kind`
(`movie|serie|anime`), `tmdb_id`, `season`, `episode`, `page`, `page_size`.

```jsonc
{ "results": [ { "id": 917, "slug": "breaking-bad", "tmdb_id": "1396",
                 "title": "Breaking Bad", "type": "pelicula", "year": "2008", ... } ] }
```

- `tmdb_id` funciona como filtro y devuelve el `slug` (verificado con 1396 → `breaking-bad`,
  y anime Naruto tmdb 202). El campo `type` en `/api/search` no es confiable para
  distinguir tipo (Breaking Bad sale como `"pelicula"`); usar `kind` de `/api/title`.

### `GET /api/title/{slug}`
Devuelve metadata + `torrents[]`. Estructura de cada torrent:

```jsonc
{ "active": true, "download_type": "link",
  "magnet": "magnet:?xt=urn:btih:...", "infohash": "eaa8f4e0...",
  "season": 1, "episode": 1, "episode_end": null, "is_pack": false,
  "lang_norm": "LATINO", "lang_raw": "Latino/Inglés",
  "quality": "WEB-DL 1080p", "seeders": null,
  "size_bytes": 2877628088, "size_label": "2.68 GB",
  "source": "pelispanda", "subs": true, "title_id": 917 }
```

- Para película: los torrents no traen `season`/`episode`.
- `seeders` puede venir `null`.
- `lang_norm` ∈ {LATINO, CASTELLANO, …} — ya normalizado.

### Drop-in (compat hacktorrent)
`GET /wp-json/wpreact/v1/search?tmdb_id=…` y `/wp-json/wpreact/v1/movie|anime/{slug}`
existen y devuelven equivalentes. Usaremos los endpoints `/api/*` como primarios.

## 3. Enfoque elegido — A: fachada intacta, motor nuevo

`TorrentSearchApi` (`data/catalog/TorrentSearchApi.kt`) sigue siendo la **fachada pública**
que consumen los ViewModels y el player. Se reemplaza solo su **motor interno**: en vez
de fan-out a providers on-device + agregadores, hace 2 llamadas HTTP al backend.
Blast radius mínimo; se reusa el pipeline de reproducción completo.

Descartados: (B) mirror como un `ProviderBackend` más dentro del tier → mantiene la
maquinaria de fan-out para una sola fuente; (C) repositorio nuevo + borrar
`TorrentSearchApi` → toca todos los call sites, más riesgo.

## 4. Componente nuevo: `MirrorApiClient`

Ubicación: `app/src/main/java/com/arkiv/player/data/catalog/mirror/`.

- `MirrorApiClient(client: OkHttpClient, baseUrl: () -> String)`
  - `suspend fun resolveSlug(tmdbId: Int?, kind: ContentType, titleFallback: String?): String?`
    → `GET /api/search?tmdb_id=…&kind=…`; si no hay match y hay `titleFallback`,
      reintenta `GET /api/search?q=<titulo>&kind=…`; devuelve `results[0].slug` o `null`.
  - `suspend fun titleTorrents(slug: String): List<MirrorTorrent>`
    → `GET /api/title/{slug}`, parsea `torrents[]`.
- `MirrorTorrent` (data class): magnet, infohash, season?, episode?, episodeEnd?, isPack,
  langNorm, quality?, seeders?, sizeBytes, sizeLabel?, source?.
- Red: OkHttp (ya en el proyecto) + `org.json`. Sin Retrofit/Ktor (coherente con el resto).
- Cache en memoria TTL ~30 min para `resolveSlug` y `titleTorrents` (igual que el
  `rawCache` actual de `TorrentSearchApi`).
- Base URL nueva en `SettingsStore`: `torrentApiUrl`, default
  `https://torrents.comparadorinternet.co`, editable en runtime (como `providersUrl` etc.).

## 5. Mapeo `MirrorTorrent` → `TorrentResult`

- `magnet`/`infohash` → `RawTorrent.magnetUri`/`infoHash`; `downloadUrl` = null.
- `lang_norm` → `TorrentLang` **directo** (LATINO→LATINO, CASTELLANO→CASTELLANO,
  DUAL→DUAL, resto→mapa; default OTHER). Mejor que inferir del nombre como hoy.
- `quality` y `size_label`/`size_bytes` → se usan tal cual (no re-inferir del nombre).
- `seeders` null → 0 / desconocido; el ranking cae a idioma → calidad → tamaño.
- `name` para display = `lang_raw`/`quality`/`size_label` si `name` es null.

## 6. Filtrado por tipo

- **Película** (`searchMovie[Flow]`): todos los torrents del slug.
- **Serie/capítulo** (`searchEpisode[Flow]`): torrents con `season == S` y
  (`episode == E` **o** pack que cubra E: `is_pack` con `episode_end` que incluya E, o
  pack de temporada). Se reusa/adapta la lógica de packs existente
  (`PackFileParser`/`EpisodeFilePicker` siguen resolviendo el archivo dentro del pack).
- **Anime** (`searchAnime`): se usa el número que ya resuelve `AnimeEpisodeResolver`
  (absoluto/relativo) y se filtran los torrents del slug por ese `season`/`episode`.
  `AnimeSourceProvider` ya obtiene el `tmdbId` (de Fribb/Simkl) para resolver el slug.

## 7. Threading de `tmdb_id`

Los tres call sites ya tienen el id disponible pero hoy solo pasan títulos de texto:

- `CineDetailScreen.kt:143-144` → `d.id`.
- `SearchViewModel.kt` → `card.tmdbId`.
- `AnimeSourceProvider.kt:102` → `tmdbId` (Fribb/Simkl).

Se agregan parámetros `tmdbId: Int?` a las firmas públicas de
`searchMovie[Flow]/searchEpisode[Flow]/searchAnime` (los títulos quedan como
**fallback de texto**). Estrategia de resolución:
**tmdb_id primero → `q`=título en el mismo backend → vacío limpio ("no hay torrents")**.
Nunca cae a on-device.

## 8. Qué se retira

Se **desconecta del `AppGraph`** (`AppGraph.kt:96-137`) el tier on-device:
`RegistryProviderBackend`, agregadores apibay/knaben, y el hot-update de `providers.json`.
Queda muerto: `providers/` (RegistryProviderBackend, JsonProviderBackend,
DeclarativeHtmlBackend, HttpFetcher, HtmlParser, JsRenderer, CloudflareSolver,
CfClearanceStore, ProviderRegistry) y el asset `providers.json`.

**Decisión (sección 7 del brainstorming):** desconectar del grafo en el commit del feature;
**borrar los archivos muertos en un commit aparte** una vez validado en device (menos riesgo
de romper compilación por referencias colgantes). No se tocan `alfa-api/`, `balandro-addon/`
ni `build/` (siguen fuera del repo).

## 9. Testing (TDD)

`MockWebServer` (OkHttp) con fixtures de los JSON reales capturados (Matrix, Breaking Bad):

1. `MirrorApiClient.resolveSlug` por `tmdb_id` (match, no-match→fallback q, ambos vacío→null).
2. `MirrorApiClient.titleTorrents` parseo (incluye `seeders:null`, película sin season/episode).
3. Mapeo `MirrorTorrent → TorrentResult` (lang, quality, size).
4. Filtrado serie: `season/episode` exacto + pack que cubre el episodio.
5. Filtrado anime: número resuelto por `AnimeEpisodeResolver`.
6. Título inexistente → lista vacía (sin excepción, sin caer a on-device).

## 10. No cambia

`SearchPlayback`, `ArkivRepository`, `TorrentEngine`, `VlcPlayer`, y toda la capa de
**streaming web** (`WebSourceEngine`, `WebResolverApi`, `web_sources.json`).
