# Capa de proveedores de torrents on-device (estilo Burst) — Diseño

**Fecha:** 2026-07-21
**Estado:** Aprobado para plan
**Origen:** Traer el conocimiento de Elementum/Burst (Kodi) a Arkiv — un scraper declarativo de torrents que corre en el dispositivo como **fallback** cuando Jackett (blog) no está disponible o devuelve pocos resultados.

## 1. Contexto y motivación

Arkiv ya reproduce torrents con un motor nativo (libtorrent4j) superior al `lt2http` de Elementum, ya tiene catálogo TMDB (es-MX) y ya busca torrents vía Jackett self-hosted en `blog` (jackett.comparadorinternet.co). Lo único que Elementum tiene y Arkiv no es **Burst**: un scraper con proveedores definidos de forma **declarativa** (JSON) que corre on-device sin servidor.

Objetivo: replicar ese modelo declarativo en Arkiv para que, si `blog`/Jackett se cae o rinde mal, la app siga encontrando torrents por su cuenta. **Jackett en blog sigue siendo el principal (mejorado); la capa on-device es el fallback/complemento.**

### Descubrimientos del código actual (baseline)

- Existe la abstracción `TorrentBackend { val id; suspend fun search(query): List<RawTorrent> }` en `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt:78`. Ya hay 3 impls: `ApibayBackend`, `KnabenBackend`, `JackettBackend`.
- El orquestador `TorrentSearchApi.runSearch()` (`:360`) ya hace fan-out multi-backend con `async`/`awaitAll`, dedupe por infohash, clasificación de idioma (`classify()`, `:541`), ranking, corte por tamaño y caché en memoria (30 min).
- Modelos ya existentes y reutilizables: `RawTorrent(name, seeders, sizeBytes, infoHash?, magnetUri?, downloadUrl?)` (`:58`), `TorrentResult` (`:38`), `enum TorrentLang { LATINO, DUAL, CASTELLANO, JAP_SUB, OTHER, ENGLISH }` (`:28`), `TorrentSource` (`:72`), `resolveSource()`/`resolveDownloadUrl()` (`:432`).
- Tokens de idioma ya modelados: `LANG_HINT_TOKENS = ["latino","castellano","spanish"]` (`:236`).
- TMDB alimenta la búsqueda con `TmdbDetail.searchTitles` (latino + inglés + original, máx 5) — `data/catalog/TmdbApi.kt:134`.
- Convenciones: Kotlin + Compose, **DI manual** (`AppGraph.kt`, singletons perezosos), **OkHttp + org.json a mano** (no Retrofit/Moshi), Coroutines/Flow, Room 2.6.1. **No hay Jsoup hoy** (hay que añadirlo). Motor torrent: libtorrent4j 2.1.0-31. Config de Jackett **hardcodeada** en `AppGraph.kt:59-61`.

Enfoque elegido: **C — declarativo con selectores CSS (Jsoup)**, no el DSL propio de Burst. Robustez = definiciones hot-updatables desde blog + módulo Cloudflare aislado + fallos por-proveedor contenidos.

Alcance del primer entregable: **todo el diseño** (app + Cloudflare + hot-update + los 3 cambios de blog).

## 2. Arquitectura y punto de conexión

La capa nueva se enchufa **detrás de `TorrentBackend`**. El orquestador, el pipeline (dedupe/clasificación/ranking/cache) y el motor de streaming quedan intactos.

```
CineDetailScreen.runSearch()                    (sin cambios)
        │
        ▼
TorrentSearchApi.runSearch()  ── fan-out ──┬─ JackettBackend            (principal, blog)
   (dedupe, ranking, cache)                │
                                           └─ ProviderRegistryBackend   ← NUEVO (fallback, con gating)
                                                     │
                                                     ▼
                                           ProviderRegistry (defs JSON: asset bundled + remota de blog)
                                                     │ N definiciones
                                                     ▼
                                           DeclarativeHtmlBackend (una por def)
                                             ├─ QueryBuilder  (plantillas → URLs)
                                             ├─ HttpFetcher   (OkHttp, UA navegador)
                                             │     └─ CloudflareSolver (WebView, AISLADO)
                                             └─ HtmlParser    (Jsoup + selectores CSS)
                                                     ↓
                                             List<RawTorrent>   (modelo que YA existe)
```

### Piezas nuevas (paquete `data/catalog/providers/`)
- `ProviderDefinition` — data class del JSON (schema en §3).
- `ProviderRegistry` — carga y mergea definiciones (asset bundled + remota de blog); valida y expone la lista.
- `ProviderRegistryBackend` — implementa `TorrentBackend`; aplica el gating de fallback (§6) y corre las defs habilitadas.
- `DeclarativeHtmlBackend` — ejecuta una `ProviderDefinition`: QueryBuilder + HttpFetcher + HtmlParser.
- `QueryBuilder` — plantillas con placeholders → queries → URLs.
- `HtmlParser` — Jsoup + selectores CSS → `RawTorrent`.
- `HttpFetcher` — OkHttp con UA de navegador, charset, detección de challenge CF.
- `CloudflareSolver` — módulo aislado con WebView headless; entrega cookie `cf_clearance`.
- `JackettHealth` — cachea el estado de blog (§6).

### Decisiones de robustez (transversales)
1. **Aislamiento de fallos:** cada proveedor corre en su corrutina con `try/catch` + timeout; excepción → lista vacía, nunca tumba el fan-out.
2. **Fallback gobernado:** `ProviderRegistryBackend` solo dispara sus proveedores según salud de blog + suficiencia de resultados (§6), para no castigar latencia/batería/datos cuando blog está sano.
3. **Hot-update desde blog:** app trae `providers.json` bundled (día-1/offline) y opcionalmente baja una versión más nueva de blog; la remota gana, con fallback a la bundled.

### Reutiliza tal cual
`RawTorrent`, `TorrentLang`, `classify()`, `LANG_HINT_TOKENS`, `resolveSource()`/`resolveDownloadUrl()`, el fan-out, el dedupe, el ranking y la caché de `TorrentSearchApi`.

## 3. Schema de la definición de proveedor (JSON)

```jsonc
{
  "id": "bt4g",                      // único; también es el TorrentBackend.id del wrapper
  "name": "BT4G",
  "enabled": true,
  "priority": 50,                    // orden/tie-break dentro del fallback
  "baseUrl": "https://bt4g.org",
  "searchPath": "/search/{query}/1", // {query} se sustituye url-encoded
  "charset": "utf-8",                // decodificación (algunos sitios usan windows-1251)
  "needsCloudflare": false,          // pista; el solver igual se activa si detecta challenge

  "keywords": {
    "movie":  "{title} {year}",
    "tv":     "{title} S{season:2}E{episode:2}",
    "season": "{title} S{season:2}",
    "anime":  "{title} {episode_abs:2}"
  },
  "languageTokens": ["latino", "castellano", "spanish"],

  "parser": {
    "rowSelector":   "table.torrents tr.result",
    "name":      { "selector": "a.title", "attr": "text" },
    "magnet":    { "selector": "a[href^=magnet]", "attr": "href" },
    "torrentUrl":{ "selector": "a.dl", "attr": "href", "resolve": "absolute" },
    "infohash":  { "selector": "", "attr": "", "regex": "" },
    "seeds":     { "selector": "td.seeds", "attr": "text", "regex": "\\d+" },
    "size":      { "selector": "td.size",  "attr": "text" }
  },

  "detail": {                         // opcional: 2º fetch si el magnet no está en el listado
    "followFrom": "torrentUrl",
    "magnet":   { "selector": "a[href^=magnet]", "attr": "href" }
  }
}
```

### `FieldRule` (mecanismo único para todos los campos)
`selector` (CSS) + `attr` (`"text"` o nombre de atributo) + `regex` (extrae grupo 1, opcional) + `resolve` (`"absolute"` completa URLs relativas con `baseUrl`, opcional). Cubre casi cualquier sitio sin código nuevo.

### Placeholders soportados (plantillas y `searchPath`)
`{title}`, `{title_original}`, `{year}`, `{season}`, `{season:2}`, `{episode}`, `{episode:2}`, `{episode_abs}`, `{episode_abs:2}`, `{query}`. El sufijo `:2` = zero-pad a 2 dígitos.

### Notas de diseño
- `keywords` por tipo = aporte directo de Burst; encaja con `searchEpisode/searchMovie`. `{episode_abs}` da numeración absoluta para la capa de anime.
- `languageTokens` alimenta variantes de query; la **clasificación** del resultado sigue en `classify()` (no se duplica en el JSON).
- `detail` opcional: sitios que no ponen el magnet en el listado → `followFrom` hace un 2º fetch (concurrencia limitada, solo top-N filas por seeds).
- Un proveedor mínimo son ~8 líneas (baseUrl, searchPath, un keyword, rowSelector, name, magnet, seeds, size). Todo lo demás es opcional.

### Validación
Al cargar, cada definición se valida (campos obligatorios: `id`, `baseUrl`, `searchPath`, `parser.rowSelector`, `parser.name`, al menos una fuente entre `magnet`/`infohash`/`torrentUrl`; URL válida). Una definición inválida se descarta y se loguea; el resto del registro carga.

## 4. Motor de query y parsing

### QueryBuilder
Entrada: `ProviderDefinition` + contexto (`searchTitles` de TMDB, season, episode, year, tipo, episode_abs).
1. Elige plantilla según tipo (`movie`/`tv`/`season`/`anime`).
2. Sustituye placeholders (con zero-pad `:2`).
3. Genera variantes: query base + una por `languageToken`, iterando sobre `searchTitles` (como hoy hace `searchEpisode`).
4. Inyecta cada query url-encoded en `searchPath` → lista de URLs.
5. **Tope de queries por proveedor** (default 4–6, configurable) para acotar el fan-out `titles × tokens`.

### HttpFetcher
- OkHttp (el existente), UA de navegador (como `JackettBackend`), timeouts cortos (~8s connect/read), `charset` de la definición.
- Detección de challenge CF: response 403/503 **o** body con marcadores (`cf-mitigated`, `Just a moment`, `__cf_chl`) → delega en `CloudflareSolver` (§5). Si el solver está off o falla → vacío para ese proveedor.

### HtmlParser (Jsoup + CSS)
1. Parsea HTML con `baseUrl` (resuelve relativas).
2. `rows = doc.select(parser.rowSelector)`.
3. Por fila aplica cada `FieldRule`: `select(selector).first()` → `.text()` o `.attr(attr)`; aplica `regex` (grupo 1); aplica `resolve: "absolute"`.
4. Construye `RawTorrent`:
   - `seeders`: parse robusto (`\d+`, default 0).
   - `sizeBytes`: reutiliza el parser de "1.4 GB"/"720 MB" ya presente en `TorrentSearchApi`.
   - Prioridad de fuente: `magnet` → `infohash` → `torrentUrl`. Si falta magnet y hay `detail`, encola 2º fetch (semáforo, p. ej. máx 3 fichas en paralelo, solo top-N filas por seeds).
5. Filas sin nombre o sin fuente reproducible → descartadas.

**Salida:** `List<RawTorrent>` → aguas abajo (dedupe, `classify()`, ranking, `resolveSource()`, cache) funciona sin cambios.

### Robustez del parser
- Cada `FieldRule` en `try/catch`: selector que no matchea → campo null/0, no explota la fila.
- `rowSelector` con 0 matches → lista vacía + log "posible cambio de sitio" con el `id`.
- Límite duro de filas por página (p. ej. 50).

## 5. CloudflareSolver (WebView, aislado)

### Flujo
1. `HttpFetcher` detecta challenge → `CloudflareSolver.solve(url)`.
2. Levanta un **WebView headless** (fuera de la jerarquía visible; hilo principal, requisito Android), JS habilitado, UA de navegador coherente con OkHttp.
3. Carga la URL; el WebView resuelve el JS challenge de Cloudflare (es un navegador real).
4. Espera `cf_clearance` en `CookieManager` o timeout (~20s).
5. Devuelve `{ cookies, userAgent }`; `HttpFetcher` **reintenta por OkHttp** inyectando `cf_clearance` + mismo UA.
6. Cookies cacheadas por dominio (TTL ~30 min) → un challenge cubre muchas búsquedas.

**Clave:** el WebView solo abre la puerta (cookie); el scraping real lo hace OkHttp+Jsoup. No se parsea dentro del WebView.

### Aislamiento y robustez
- **Un único WebView reutilizable** (singleton en `AppGraph`), serializado con `Mutex` (un challenge a la vez) → evita fugas y carreras.
- **Timeout duro**: sin `cf_clearance` a tiempo → falla limpio, proveedor vacío.
- **Interruptor global**: `SettingsStore.cloudflareSolverEnabled` (default on) para apagarlo en devices problemáticos (p. ej. Fire Stick con WebView viejo).
- **Cache de cookies** en memoria (dominio + TTL). Persistir en Room = *opcional fase 2* (YAGNI).
- **No bloquea el fan-out**: corre en su corrutina con timeout; los proveedores públicos sin CF responden en paralelo.

### Límites honestos
- Cloudflare **Turnstile/CAPTCHA interactivo** no se resuelve headless — y por regla no completamos CAPTCHAs. Esos sitios se loguean como "requiere interacción" y no son viables on-device.
- Fire TV: WebView más lento; cubierto por el interruptor global.
- Ataca el challenge **JS automático** ("Just a moment…"), que es lo que hoy rompe a OkHttp.

## 6. Orquestación del fallback + health-check

### Gating (cuándo dispara on-device)
**Health-check de blog** — `JackettHealth` cachea estado con ping ligero a `GET /health` (timeout ~3s, cache ~60s): `HEALTHY` / `DEGRADED` / `DOWN`.

**Decisión por búsqueda** (en `ProviderRegistryBackend`, dentro de `runSearch`):
```
if (jackettHealth == DOWN)
      → salta Jackett, dispara on-device directo (fallback puro)
else
      → corre Jackett (principal)
        └─ si resultados útiles < umbral (default 3) o Jackett tardó/erró
              → dispara on-device y fusiona
        └─ si Jackett trajo suficiente
              → NO dispara on-device (ahorra batería/latencia/datos)
```

### Fusión de resultados
Cuando corren ambos, sus `RawTorrent` caen al **mismo pipeline existente**: dedupe por infohash, `classify()`, ranking, corte por tamaño, cache. Sin cambios.

### Registro y carga de definiciones
- **Bundled** (`app/src/main/assets/providers.json`): día-1 y offline.
- **Remoto** (`GET /providers.json?version=` en blog): al abrir la app / cada X horas, si hay versión más nueva la baja y cachea (Room/disco). La remota gana; si no carga o es inválida, usa la bundled.
- **Refactor necesario:** mover host/apikey de Jackett y URL del endpoint de definiciones de `AppGraph.kt:59-61` a `SettingsStore`, para que blog sea configurable.

### Observabilidad
Cada proveedor loguea: nº de filas parseadas, `rowSelector` con 0 matches, si hubo challenge CF, latencia. Pantalla de "estado de proveedores" en ajustes = *opcional fase 2* (YAGNI).

## 7. Lado blog (mejoras)

**A. Más indexers latino/anime en Jackett** — añadir vía UI de Jackett los trackers es/lat/anime de Burst que hoy faltan (algunos usan el FlareSolverr ya presente en blog). Entregable: lista curada derivada del `providers.json` de Burst. No requiere tocar la app (ya consulta `indexers/all/results`).

**B. Tokens de query por idioma** — vive en la app (QueryBuilder, §4) y aplica a **ambas** rutas (Jackett y on-device): un solo `languageTokens` + `searchTitles`. Punto de unificación de la lógica de queries. En blog no hay cambio; mejora las queries que se le mandan a Jackett.

**C. Endpoints nuevos en blog** (servicio liviano junto a Jackett):
- `GET /health` → `{status, jackettUp, flaresolverrUp}`, timeout rápido. Consumido por `JackettHealth`.
- `GET /providers.json?version=` → sirve la versión curada de definiciones (hot-update).
- **Regla de blog (estricta):** nunca compilar pesado en blog (VM 2 CPU). **Default recomendado:** servir `providers.json` como archivo estático (Caddy/nginx ya presente para los subdominios) + un `/health` trivial (endpoint estático o script CGI mínimo que verifique que el puerto de Jackett responde). Alternativa (si se quiere lógica real en `/health`): binario ya compilado en el Mac + `systemd --user`. El plan confirma cuál, pero se parte del archivo estático por simplicidad.

## 8. Manejo de errores (transversal)

| Fallo | Comportamiento |
|---|---|
| Proveedor tira excepción / timeout | Lista vacía, log con `id`. Fan-out sigue. |
| `rowSelector` matchea 0 filas | Vacío + log "posible cambio de sitio". |
| CloudflareSolver falla/timeout | Proveedor vacío; interruptor global puede apagarlo. |
| `providers.json` remoto inválido | Usa bundled; log de versión rechazada. |
| Definición individual inválida | Se descarta esa def; el resto carga. |
| blog `/health` no responde | Se asume `DEGRADED` (corre Jackett; on-device listo si falla). |

**Principio:** ningún fallo de un proveedor o del solver puede degradar la búsqueda por Jackett ni tumbar el fan-out. Aislamiento por corrutina + `try/catch` + timeout en cada borde.

## 9. Testing

- **Unit — QueryBuilder:** placeholders, zero-pad `:2`, variantes por token/título, tope de queries. Puro, sin red.
- **Unit — HtmlParser:** con **fixtures HTML** (snapshots reales por sitio en `test/resources`), verificar extracción de name/magnet/seeds/size. Atrapa regresiones cuando un sitio cambia.
- **Unit — ProviderDefinition:** válidas / inválidas.
- **Unit — orquestación fallback:** con `JackettHealth` y backends fake, verificar gating (DOWN→salta Jackett; pocos resultados→dispara on-device; suficientes→no dispara).
- **Instrumented (opcional):** `CloudflareSolver` contra URL real detrás de CF — manual/humo, no en CI.
- **Sin red en CI:** backends declarativos se testean contra fixtures, no sitios vivos.

## 10. Dependencias nuevas

- **Jsoup** (parsing HTML) — no está hoy en el proyecto; añadir a `app/build.gradle.kts`.
- WebView: parte del SDK Android, no requiere dependencia externa.

## 11. Fuera de alcance (YAGNI / fases futuras)

- Persistir cookies de Cloudflare en Room (fase 2).
- Pantalla de "estado de proveedores" en ajustes (fase 2).
- Resolver CAPTCHAs interactivos (nunca — regla de seguridad).
- Sitios privados con login complejo on-device (no viable de forma fiable; se quedan en Jackett/blog).
