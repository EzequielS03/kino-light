# API Unificada (`arkiv-api`) — Design Spec

Fecha: 2026-08-09

## Objetivo

Reemplazar la constelación de APIs que la app consume hoy por **un solo gateway** con **un solo
contrato**, capaz de agregar fuentes de búsqueda nuevas **sin publicar un APK**.

`magis` (el portal IPTV de `lordmacu/magia`) entra como **fuente nueva** junto a las tres que ya
existen. Las cuatro fuentes de búsqueda quedan:

| Fuente | Backend |
|--------|---------|
| `torrent` | hacktorrent-mirror `:8097` + Jackett `:9117` |
| `web` | resolver Node `:8123` + `web_sources.json` |
| `archive` | archive.org |
| `magis` | `iptv_client.py` de `lordmacu/magia` (**nuevo**) |

## Estado actual (lo que se unifica)

Inventario verificado en `blog` el 2026-08-09:

| Host | Puerto | Servicio | Rutas |
|------|--------|----------|-------|
| `db.comparadorinternet.co` | 8095 | PocketBase (control remoto celu↔TV, SSE) | colecciones + auth |
| `alfa.comparadorinternet.co` | 8096 | Flask — Alfa headless | `/find` `/resolve` `/nav` `/health` |
| `torrents.comparadorinternet.co` | 8097 | Flask — hacktorrent-mirror + Postgres | `/api/search` `/api/suggest` `/api/title/<slug>` `/api/stats` `/library/*` `/wp-json/wpreact/*` |
| `arkiv-offline.comparadorinternet.co` | 8099 | Flask — descargas al NUC | `/jobs` `/jobs/<id>/events` `/library` `/stream/<id>` |
| `crunch.comparadorinternet.co` | 8100 | FastAPI — Crunchyroll → Archive/VOE | `/download` `/health` |
| `jackett.comparadorinternet.co` | 9117 / 8123 | Jackett + resolver Node | `/api/v2.0/*`, `/resolve` `/proxy`, `web_sources.json` |
| `apk.comparadorinternet.co` | 8098 | nginx — distribución APK | `latest.json` |

Además la app llama directo a TMDB, AniList, Simkl, Cinemeta, AnimeTosho, OpenSubtitles,
archive.org, GitHub raw (`providers.json`, anime-lists, trackers) y los mirrors `fusme.link`
y compañía.

## Dolores que resuelve

1. Agregar una fuente hoy obliga a tocar Kotlin y publicar APK.
2. La app conoce 7 hostnames propios + 10 APIs externas, cada una con su forma y sus fallos.
3. La búsqueda es un fan-out desde el dispositivo, sin caché compartido.
4. Siete servicios que mantener en un NUC saturado.
5. Las llaves (TMDB, OpenSubtitles, Simkl, `REFRESH_API_KEY`) viven en `BuildConfig`; rotarlas
   obliga a sacar APK.
6. No hay caché de servidor: cada dispositivo re-consulta lo mismo.

## Restricción de hardware que condiciona el diseño

`blog` es un **NUC5CPYB físico** (Celeron N3050, tope 8 GB, ya en swap). Por eso el gateway
separa dos planos:

- **Plano de control** (búsqueda, resolución, catálogo, jobs, metadata) → **sí** pasa por el gateway.
- **Plano de datos** (bytes de video, SSE de realtime) → el gateway responde **`302`** al backend
  real. Los bytes nunca cruzan el gateway.

Esto da "un solo hostname, un solo contrato" sin pagarlo en CPU ni en ancho de banda.

## Arquitectura

Servicio nuevo **`arkiv-api`** (FastAPI + uvicorn) desplegado en Coolify, puerto `8101`,
expuesto como **`api.comparadorinternet.co`**.

```
                    api.comparadorinternet.co
                              │
                    ┌─────────▼─────────┐
                    │   Router  /v1/*   │  auth X-Arkiv-Key
                    └─────────┬─────────┘
                              │
              ┌───────────────▼───────────────┐
              │  Orquestador de fan-out       │  presupuesto · dedup · ranking
              │  aislamiento por fuente       │  circuit breaker
              └──┬────────┬────────┬───────┬──┘
                 │        │        │       │
            ┌────▼──┐ ┌───▼──┐ ┌───▼───┐ ┌─▼──────┐
            │torrent│ │ web  │ │archive│ │ magis  │  ← SourceAdapter
            └────┬──┘ └───┬──┘ └───┬───┘ └─┬──────┘
                 │        │        │       │
            htm:8097   node:8123  archive  iptv_client
            jackett:9117          .org     (vendorizado)
                              │
                    ┌─────────▼─────────┐
                    │ Redis  ·  Postgres│  caché · sesión · rate-limit · métricas
                    └───────────────────┘
```

### Unidades y responsabilidades

| Unidad | Qué hace | De qué depende |
|--------|----------|----------------|
| `router/` | Traduce HTTP ↔ dominio. Valida params, aplica auth, serializa NDJSON. | orquestador |
| `orchestrator/` | Corre los adaptadores en paralelo con presupuesto de tiempo, deduplica, rankea, emite eventos. | interfaz `SourceAdapter` (no las implementaciones) |
| `adapters/<fuente>.py` | Una fuente. Traduce `SearchContext` → llamadas del backend → `Result`. | su backend + el store |
| `catalog/` | TMDB · AniList · Simkl · Cinemeta · OpenSubtitles, cacheados. | Redis |
| `store/` | Redis (caché, sesiones, rate-limit) y Postgres (registro de fuentes, métricas). | — |

El orquestador **no** conoce ninguna fuente concreta. Agregar una fuente es un archivo nuevo más
una fila en el registro: nada más se toca.

### Interfaz `SourceAdapter`

```python
class SourceAdapter(Protocol):
    name: str
    capabilities: set[str]          # {"movie", "tv", "anime", "pack"}

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]: ...
    async def resolve(self, ref: ResultRef) -> Playable: ...
    async def health(self) -> HealthStatus: ...
```

## Contrato HTTP

### `GET /v1/search` — NDJSON

`Content-Type: application/x-ndjson`. Una línea por evento:

```
{"type":"source_start","source":"torrent"}
{"type":"result","source":"torrent","item":{…}}
{"type":"source_done","source":"torrent","count":42,"ms":830}
{"type":"source_error","source":"magis","error":"timeout","ms":4000}
{"type":"done","ms":4100}
```

Params: `q`, `type` (`movie|tv|anime`), `season`, `episode`, `year`, `tmdb_id`, `anilist_id`,
`sources` (csv, default todas), `lang`, `budget_ms` (default 4000).

`?format=json` devuelve un JSON agregado único — para la TV, scripts y debug.

**Por qué NDJSON:** la app ya pinta resultados a medida que llegan y re-ordena en vivo
(`SearchViewModel.refreshSeeders`, `countsByTab`). Un JSON único bloqueante sería una regresión
de UX.

### `POST /v1/resolve`

Cada `item` de `/v1/search` incluye un campo **`ref`**: una cadena opaca, firmada y con TTL, que
codifica la fuente y lo que esa fuente necesita para resolver (`content_id`, magnet, URL de la
página web, identificador de archive…). La app la reenvía tal cual y **nunca la interpreta**; así
una fuente puede cambiar su forma interna sin romper la app ni obligar a un APK nuevo.

Petición: `{"ref": "<opaco>"}`. Respuesta:

```json
{ "kind": "magis",
  "url": "https://cdn…/x_media.mp4",
  "headers": { "Content-Auth": "…", "Content-License": "…" },
  "mime": "video/mp4",
  "expires_at": "2026-08-11T…",
  "fallback": { "url": "…" } }
```

`headers` es genérico: cubre el `Referer`/`User-Agent` que ya usan las fuentes web y el
`Content-Auth`/`Content-License` de magis sin casos especiales.

### Resto

| Ruta | Qué hace |
|------|----------|
| `GET /v1/sources` | Registro de fuentes activas + capacidades + estado del breaker. **Acá vive el hot-update**: la app pinta las pestañas con lo que diga el servidor. |
| `GET /v1/catalog/*` | TMDB · AniList · Simkl · Cinemeta · OpenSubtitles cacheados, en las formas que la app ya parsea. Las llaves salen del APK. |
| `GET /v1/health` | Estado por adaptador. |
| `GET /v1/stats` | Latencia, hits y errores por fuente. |
| `GET /v1/stream/*`, `/v1/jobs/<id>/events` | **`302`** al backend real (plano de datos). |

## El adaptador `magis`

- Vendoriza `iptv_client.py` de `lordmacu/magia`, **pineado por commit**.
- **Sesión compartida en Redis** (`userId`/`userToken`), TTL 48h, refresh en 401. El README de
  magia confirma que los tokens son portables entre sesiones y viven ~48h, así que cualquier
  worker los reusa sin re-activar.
- **Token bucket en Redis: 1 llamada / 1.5s global.** El rate-limit del portal deja de ser por
  proceso y pasa a ser del sistema entero.
- Caché: búsqueda 6h · `detail`/`episodes` 24h · `play_vod` 40h (por debajo de la vida del token).
- **Solo `movie` y `series`.** La TV en vivo queda **fuera de alcance** — evita `LiveProxy`,
  `sign_o3` y la resolución SLB binaria.
- Secretos (`IPTV_3DES_KEY`, `IPTV_HOSTS`, `IPTV_APP_ID`, `IPTV_DEVICE_SN`, …) como **secretos de
  Coolify**, copiados desde el `.env` local. Nunca al repo.

## Robustez

- **Aislamiento**: una fuente que falla o expira emite `source_error`; las demás siguen. Nunca
  tumba la búsqueda.
- **Circuit breaker** por fuente: **5 fallos seguidos** → abierto 5 min, visible en `/v1/sources`.
  Al cerrarse vuelve en modo half-open (una sola petición de prueba).
- **stale-while-revalidate**: si una fuente está caída, sirve lo cacheado con `"stale": true`.
- **Presupuesto** de 4s por defecto, configurable por request.
- **Auth**: header `X-Arkiv-Key` desde `BuildConfig`. Sin ella, `401`.
- **Métricas por fuente** en Postgres → alimentan `/v1/stats`.

## Cambios en la app

- `ArkivApiClient` nuevo: consume NDJSON con OkHttp línea a línea y alimenta `_sources` **igual
  que hoy**. La UI incremental no cambia.
- `SourceKind` += `MAGIS` · `SourceTab` += `Magis` · `PlaySource` += `Magis`.
- `PlayerSourceTag`: generalizar `referer`/`userAgent` a `headers: Map<String, String>`,
  manteniendo los campos actuales como derivados para no romper el player ni el cast.
- **Feature flag** `useGateway` en `SettingsStore` (default ON), con caída al camino actual si el
  gateway no responde.
- **Se queda en el dispositivo**: el scrape UDP de seeders y el conteo DHT
  (`SearchViewModel.refreshSeeders`) — eso necesita la red del celular, no la del NUC.

## Testing

| Nivel | Qué se prueba |
|-------|---------------|
| Adaptadores | Fixtures HTTP grabadas por fuente (respx/vcr). |
| Orquestador | Adaptadores fake — lento, que falla, vacío — para verificar presupuesto, aislamiento y dedup. |
| Contrato | Golden files del NDJSON. |
| `magis` en vivo | Test de integración **opt-in**, fuera de CI (consume rate-limit real). |
| App | Parser NDJSON y fallback por feature flag. |

## Despliegue

- Contenedor `arkiv-api` en Coolify, puerto `8101`.
- Ingress nuevo en `~/.cloudflared/comparador.yml`: `api.comparadorinternet.co` → `127.0.0.1:8101`.
- ⚠️ Reiniciar el túnel **tumba db/alfa/torrents ~40 s**. Se agrega el ingress y se reinicia
  **una sola vez**, en ventana.

### Fases

| Fase | Contenido |
|------|-----------|
| F1 | Gateway + orquestador + adaptadores `torrent` y `archive` |
| F2 | Adaptador `web` |
| F3 | Adaptador `magis` |
| F4 | Catálogo (TMDB, AniList, Simkl, Cinemeta, OpenSubtitles) |
| F5 | Integración en la app (cliente NDJSON, pestaña Magis, feature flag) |
| F6 | Jobs (`arkiv-offline`, `crunch`) bajo el gateway, bytes por `302` |

## Fuera de alcance

- **TV en vivo de magis.** Requiere `LiveProxy`, `sign_o3` (emulación Unicorn) y resolución SLB
  binaria. Sub-proyecto aparte si alguna vez se quiere.
- **PocketBase / realtime detrás del gateway.** El control remoto celu↔TV sigue pegándole
  directo a `db.comparadorinternet.co`. Ya costó estabilizar el SSE contra Cloudflare
  (HTTP/1.1 + poll de respaldo); meter un hop más reintroduce ese problema sin ganancia.
- **Apagar los servicios viejos.** Siguen vivos durante toda la migración; se retiran recién
  cuando el gateway esté probado en dispositivo.
- **Proxy de bytes de video por el gateway.** Decisión explícita por el hardware de `blog`.

## Repositorio

Repo nuevo **`~/arkiv-api`**, independiente — igual que `arkiv-offline` y `hacktorrent-mirror`.
No se mezcla Python con el código Android de `~/archive`.
