# Backend de proveedores por API JSON — Diseño

**Fecha:** 2026-07-25
**Branch:** `feat/torrents-ondevice-sin-servidor` (continúa la capa de torrents on-device)
**Alcance:** Añadir un **backend declarativo para trackers cuya fuente es una API JSON** (no HTML).
Primer consumidor: **hacktorrent** (WordPress + wpreact REST API). Solo **películas** en v1.

---

## 1. Contexto y motivación

Los trackers actuales se scrapean por HTML (`DeclarativeHtmlBackend`) o, si cargan por JS, con
`renderJs` (WebView). **hacktorrent** es distinto: expone una **API REST JSON** limpia. Investigación
en vivo (jul 2026, host `https://hacktorrent.to/`):

- **Búsqueda:** `GET /wp-json/wpreact/v1/search?query={q}&posts_per_page=20&page=1` →
  `{"results": [{slug, title, original_title, type, year, language}, …], "total", "pages"}`.
  `type` ∈ {`pelicula`, `serie`, `anime`}. `language` p. ej. "Latino".
- **Detalle de película:** `GET /wp-json/wpreact/v1/movie/{slug}/` →
  `{…, "downloads": [{quality, size, download_link, language}, …], "related": […]}`.
  - `download_link` es un **magnet DIRECTO** (todos empiezan por `magnet:`; verificado).
  - Varias calidades por película (4K/1080p/…), cada una con su magnet, `size` ("9.94 GB"), y
    `language` ("Latino/Inglés").
  - Mapeo de tipo → ruta de detalle: `pelicula` → `movie`, `serie` → `serie`, `anime` → `anime`
    (tomado del código de Alfa, `channels/hacktorrent.py`).

Ventaja: es la fuente **más robusta** (JSON estructurado, no se rompe con rediseños de HTML), con
magnets directos, multi-calidad e idioma etiquetado. Por eso vale un backend propio.

## 2. Diseño

### 2.1 Esquema declarativo — bloque `jsonApi` en `ProviderDefinition`

`ProviderDefinition` gana un campo opcional `jsonApi: JsonApiConfig?` (default null). Si está
presente, el proveedor se ejecuta con `JsonProviderBackend` (no `DeclarativeHtmlBackend`). Todos los
demás campos comunes (`id`, `name`, `enabled`, `priority`, `baseUrl`, `hostAlt`, `languageTokens`)
siguen aplicando. `parser`/`detail`/`searchPath`/`renderJs` quedan **ignorados** cuando hay `jsonApi`.

`JsonApiConfig` (parseado en `ProviderDefinition.fromJson`):
```
searchPath: String              // "/wp-json/wpreact/v1/search?query={query}&posts_per_page=20&page=1"
resultsPath: String             // "results" (clave del array de resultados en el JSON de búsqueda)
itemSlug: String                // "slug"
itemTitle: String               // "title"
itemType: String                // "type"
itemYear: String? = null        // "year" (opcional)
itemLanguage: String? = null    // "language" (opcional)
movieType: String               // "pelicula" (valor de itemType que tratamos como película en v1)
detailPath: String              // "/wp-json/wpreact/v1/movie/{slug}/" (plantilla con {slug})
downloadsPath: String           // "downloads" (clave del array de descargas en el detalle)
dlLink: String                  // "download_link" (el magnet)
dlQuality: String? = null       // "quality"
dlSize: String? = null          // "size"
dlLanguage: String? = null      // "language"
```
Ejemplo (hacktorrent) — ver §2.4.

### 2.2 `JsonProviderBackend` (implementa `ProviderBackend`)

`search(ctx: SearchContext): List<RawTorrent>`:
1. Arma la URL de búsqueda: `baseUrl + searchPath.replace("{query}", urlEncode(ctx.titles.first()))`.
   (Igual que HTML: se puede iterar `ctx.titles` para multi-título, acotado — ver Concurrencia.)
2. `fetcher.fetch(url, "utf-8")` → String; `JSONObject(body)`; array en `resultsPath`.
3. Filtra items con `itemType == movieType` (v1: solo películas; series/anime fuera de alcance).
4. Para cada item película (con cap de concurrencia), arma
   `detailUrl = baseUrl + detailPath.replace("{slug}", item[itemSlug])`, hace `fetcher.fetch`,
   parsea `downloadsPath`.
5. Por cada download → `RawTorrent`:
   - `magnetUri = dl[dlLink]` (si empieza por `magnet:`, si no se descarta),
   - `infoHash` = extraído del magnet (`btih:` regex, como ya hace `TorrentSearchApi`),
   - `name = "{item.title} {dl.quality} {dl.language}"` (incluye título para la relevancia y
     calidad+idioma para la clasificación de idioma de `classify()`),
   - `sizeBytes = HtmlParser.parseSize(dl.size)` (reusa el parser de tamaños ya existente, con
     unidades binarias),
   - `seeders`: la API no da seeds → 0 en el `RawTorrent`; el pipeline lo trata como el resto.
     Nota: a diferencia del HTML (donde el default seeds=1 vive en `HtmlParser`), aquí el backend
     debe asignar **seeders = 1** ("vivo, desconocido") para que `finalize` (que filtra `>0`) no lo
     descarte. Documentar esa decisión en el código.
6. Devuelve la lista aplanada. `runCatching` por item/download: un fallo no rompe al resto.

Reusa el `PageFetcher` (HttpFetcher) para las peticiones (UA de navegador; JSON es texto). El
pipeline de `TorrentSearchApi` (relevancia `movieRelevance`, `classify` de idioma, ranking, dedupe,
caché) aplica igual — el `RawTorrent.name` con título+idioma es lo que necesita.

### 2.3 Seam / wiring

`RegistryProviderBackend` (que hoy hace `DeclarativeHtmlBackend(def, fetcher, jsRenderer)` por def)
elige el backend según la def:
```
if (def.jsonApi != null) JsonProviderBackend(def, fetcher).search(ctx)
else DeclarativeHtmlBackend(def, fetcher, jsRenderer = jsRenderer).search(ctx)
```
Sin cambios en `AppGraph` (el `RegistryProviderBackend` ya recibe el `fetcher`). El hot-update y el
merge por `id` aplican igual.

### 2.4 Def de hacktorrent (bundled)

```json
{
  "id": "hacktorrent",
  "name": "HackTorrent",
  "enabled": true,
  "priority": 67,
  "baseUrl": "https://hacktorrent.to",
  "hostAlt": [],
  "languageTokens": ["latino", "castellano"],
  "jsonApi": {
    "searchPath": "/wp-json/wpreact/v1/search?query={query}&posts_per_page=20&page=1",
    "resultsPath": "results",
    "itemSlug": "slug", "itemTitle": "title", "itemType": "type", "itemYear": "year", "itemLanguage": "language",
    "movieType": "pelicula",
    "detailPath": "/wp-json/wpreact/v1/movie/{slug}/",
    "downloadsPath": "downloads",
    "dlLink": "download_link", "dlQuality": "quality", "dlSize": "size", "dlLanguage": "language"
  }
}
```
Nota: NO lleva `keywords`/`parser` (los exige el path HTML). `ProviderDefinition.fromJson` debe
aceptar una def con `jsonApi` y SIN `keywords`/`parser` (hoy los exige y devuelve null sin ellos) —
ver §3.

## 3. Cambios a `ProviderDefinition.fromJson`

- Parsear el bloque `jsonApi` → `JsonApiConfig?`.
- **Relajar la validación**: si `jsonApi != null`, NO exigir `keywords` ni `parser` (que son del path
  HTML). Si `jsonApi == null`, se mantiene la validación actual (keywords+parser obligatorios).
- `RealProvidersJsonTest` debe seguir verde (la def de hacktorrent parsea sin ser descartada).

## 4. Testing

- **JsonApiConfig parse:** una def con `jsonApi` y sin `keywords`/`parser` parsea OK; los campos se leen.
- **JsonProviderBackend (fixtures del JSON REAL):** un `PageFetcher` fake que devuelve el JSON de
  búsqueda para la URL de search y el JSON de detalle para `movie/{slug}/`. Asserta:
  - se generan N `RawTorrent` (uno por download de la película que matchea),
  - `magnetUri` empieza por `magnet:`, `infoHash` extraído,
  - `name` contiene el título + calidad,
  - `sizeBytes` > 0 (parseado de "9.94 GB"),
  - `classify(name)` de un download "Latino/Inglés" → `LATINO`.
  - un item tipo `serie` en los resultados se **ignora** (v1 solo películas).
- **Aislamiento:** un detail que falla (JSON inválido) no rompe a los demás.
- **Seed default:** los `RawTorrent` tienen `seeders == 1` (no 0) para sobrevivir el filtro de
  `finalize`.
- Suite completa verde; `RealProvidersJsonTest` incluye hacktorrent.

## 5. Verificación en device

Instalar / hot-update; buscar una película popular. En logcat (`ArkivProv`) el backend loguea
`provider=hacktorrent … filas=N` con N>0, y las fuentes aparecen (latino arriba). Confirmar que una
reproduce (magnet directo → player de torrent).

## 6. Fuera de alcance (v1)

- **Series y anime** de hacktorrent (necesitan navegación de temporada/episodio: `serie/{slug}/related`
  + endpoints de episodios). Solo películas por ahora. `type != "pelicula"` se ignora.
- Paginación (solo `page=1`, `posts_per_page=20`).
- Los `related` del detalle (recomendados) se ignoran.
- La capa web (`/resolve`) sigue fuera (otro branch).

## 7. Criterios de éxito

- Un proveedor puede declararse con `jsonApi` (sin `keywords`/`parser`) y ser scrapeado por
  `JsonProviderBackend`, con el MISMO pipeline de relevancia/idioma/ranking/caché.
- hacktorrent devuelve fuentes de película (magnets directos, multi-calidad, latino etiquetado),
  verificado en device.
- Los proveedores existentes (HTML/renderJs) se comportan **idénticos** (jsonApi es aditivo/opcional).
- Un fallo de red/JSON degrada a vacío sin romper a los demás.
- Genérico: cualquier otro tracker con API JSON de este estilo se agrega con solo la def (sin código).
