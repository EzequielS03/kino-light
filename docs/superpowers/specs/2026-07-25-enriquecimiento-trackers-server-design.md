# Enriquecimiento con otros trackers (server-side) — Diseño

**Fecha:** 2026-07-25
**Estado:** Diseño aprobado (pendiente revisión del spec)
**Contexto:** extiende el mirror de hacktorrent ([2026-07-24-hacktorrent-mirror-db-design.md](2026-07-24-hacktorrent-mirror-db-design.md)), ya desplegado y vivo en `torrents.comparadorinternet.co`.

## Objetivo

Cuando un título se **agrega o cambia** en un crawl del mirror, buscar ese mismo título en
los otros trackers que ya usa el app Arkiv (replicando su lógica), matchear los resultados y
guardarlos como torrents adicionales del título. Enriquece el catálogo con más/mejores torrents
(idiomas, calidades, seeds) sin depender solo de hacktorrent. **No se toca el app.**

## Alcance

- **Providers v1 (7):** `bitsearch`, `nyaa`, `eztv` (Cloudflare→FlareSolverr), `elitetorrent`,
  `dontorrent`, `divxtotal`, `subtorrents` (HTML), y `wolfmax4k` (renderJs → **probar vía
  FlareSolverr**, que devuelve HTML ya renderizado; si no alcanza, se difiere).
- **hacktorrent** sigue siendo la fuente base del catálogo (metadatos + su set de torrents).
- **Fuera de v1:** navegador headless propio (Playwright); se evalúa solo si FlareSolverr no
  cubre wolfmax4k.

## Referencias de implementación (portar, no inventar)

Regla del proyecto [[arkiv-portar-de-alfa]]: ante dudas de scraping/matching, portar del código real.

- **Alfa** (Python, Kodi): `alfa-addon/plugin.video.alfa/channels/{divxtotal,dontorrent,elitetorrent,eztv,subtorrents,wolfmax4k}.{py,json}`
  — muestran fetch, selectores, seguir a detalle, extracción de magnet, y filtrado por idioma/calidad.
- **Balandro** (Python, Kodi): `balandro-addon/plugin.video.balandro/` — implementaciones alternativas
  de los mismos trackers y de `bitsearch`/`nyaa`; útil para contrastar selectores y matching.
- **App Arkiv** (Kotlin): `app/src/main/java/com/arkiv/player/data/catalog/providers/`
  (`DeclarativeHtmlBackend.kt`, `HtmlParser.kt`, `ProviderDefinition.kt`, `SearchModels.kt`) +
  `app/src/main/assets/providers.json` — el motor declarativo y la config que replicamos 1:1.
- Matching por `tmdb_id` vs texto: [[arkiv-busqueda-torrents-matching]].

## Config de providers = el mismo `providers.json`

El port lee las **mismas definiciones declarativas** que el app (searchPath, `keywords` por tipo,
`parser` con selectores CSS, `detail` de 2 pasos, `needsCloudflare`, `renderJs`, `languageTokens`,
`hostAlt`). Fuente:

- **Primario:** descargar `providers.json` del repo hot-update `lordmacu/arkiv-providers` (raw),
  el mismo que consume el app → cuando actualizás selectores, el servidor se sincroniza solo.
  Cachea con TTL y valida JSON antes de reemplazar.
- **Fallback:** copia bundleada en el repo del mirror (`assets/providers.json`), por si el remoto
  falla. (La ruta/branch exacta del raw se confirma al implementar.)

## Arquitectura (paquete nuevo `mirror/providers/`)

```
crawler (incremental/full)
   └─ tras upsert_detail de un título nuevo/cambiado (o backfill) → enrich.enrich_title(title)
        ├─ provider_config: definiciones HTML de providers.json
        ├─ para cada provider habilitado (aislado, con timeout):
        │     html_backend.search(provider, ctx) → filas (name, magnet|torrentUrl, size, seeds, lang)
        │        · fetch: requests  |  FlareSolverr si needsCloudflare/renderJs
        │        · parse: BeautifulSoup + soupsieve (soporta `:has()`, `nth-of-type`, etc.)
        │        · detail (2 pasos): sigue torrentUrl → extrae magnet
        ├─ matcher: filtra resultados que realmente corresponden al título (ver abajo)
        └─ upsert de los torrents nuevos con source=<provider> (dedup por infohash)
```

- `mirror/providers/provider_config.py` — carga/parseo/caché de `providers.json` (solo backends HTML).
- `mirror/providers/html_backend.py` — scraper declarativo genérico (equivalente Python de `DeclarativeHtmlBackend.kt`).
- `mirror/providers/matcher.py` — matching fiel al app.
- `mirror/providers/fetch.py` — capa de fetch (requests + FlareSolverr) con timeouts/retries/rate-limit por host.
- `mirror/enrich.py` — orquesta el enriquecimiento de un título y su persistencia.

## Matching (el punto crítico de calidad — evitar falsos positivos)

Fiel al app; consultar Alfa/Balandro para reglas por tracker.

- **Query:** se arma desde `keywords` según el tipo: `{title} {year}` (peli),
  `{title} S{season:2}E{episode:2}` (serie), `{title} S{season:2}` (temporada),
  `{title} {episode_abs:2}` (anime). El mirror tiene `title`, `original_title`, `year`,
  y para anime season/episode por torrent.
- **Filtro de pertenencia:** normaliza (sin acentos, minúsculas, quita puntuación/`.`/`_`),
  exige que los tokens del título estén presentes, y:
  - **Película:** exige coincidencia de **año** (±1) para descartar remakes/otras entregas.
  - **Serie/anime:** exige season/episode del resultado (parseados del nombre) coincidan.
- **Score + umbral:** similitud título (token-set / trigram) sobre umbral; por debajo se descarta.
  Ante ambigüedad, **preferir precisión** (mejor no agregar que agregar el equivocado).
- Se registra por torrent enriquecido su `match_score` para auditar y ajustar el umbral.

## Cuándo corre (integrado al crawler)

- **Incremental (cada hora):** enriquece los ~6 títulos que trae (nuevos/cambiados). ~42 scrapes/h.
- **Full (2×/semana):** enriquece solo títulos **nuevos o con torrents de hacktorrent cambiados**
  (no re-scrapea los 14k intactos).
- **Backfill inicial:** el primer full marca "todo nuevo"; se cubre con un **presupuesto por corrida**
  `ENRICH_BUDGET=200` títulos-sin-enriquecer por corrida (config), cubriendo 14k en ~3 días de
  corridas horarias, sin ahogar la VM de 2 CPU.
- **Manual:** `crawler --mode enrich` para forzar backfill/pruebas.
- Elegibilidad: un título entra a la cola de enriquecimiento si `last_enriched IS NULL` o si sus
  torrents de hacktorrent cambiaron desde `last_enriched`.

## Storage (cambios aditivos, idempotentes)

- `torrents.name` text NULL — nombre del release scrapeado (visibilidad + respaldo de dedup sin infohash). El dedup real sigue por infohash.
- `torrents.source` text NOT NULL DEFAULT `'hacktorrent'` (`hacktorrent`|`bitsearch`|`nyaa`|…).
- `torrents.seeders` int NULL (los HTML lo dan; hacktorrent no → null).
- `torrents.match_score` numeric NULL (confianza del match, para auditar).
- `titles.last_enriched` timestamptz NULL; `titles.enrich_error` text NULL (última falla).
- **Dedup por infohash entre fuentes:** índice parcial `UNIQUE(title_id, infohash) WHERE infohash IS NOT NULL`.
  El mismo torrent en 2 trackers no se duplica (gana el primero; se puede registrar multi-source luego).
  Se mantiene `UNIQUE(title_id, magnet)` para los sin-infohash.
- Migración vía `migrate.py` con `ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` (seguro sobre la DB viva).

## Borrado / `active` con múltiples fuentes (resuelve la inquietud de borrado)

- El enriquecimiento **solo agrega** (append); **nunca desactiva** torrents de otras fuentes.
- `reconcile` (soft-delete) queda **acotado a la fuente hacktorrent**: solo desactiva torrents con
  `source='hacktorrent'` no vistos en el full. Los de otros trackers persisten.
- **Nueva regla de título activo:** un título está `active` si tiene **al menos un torrent activo de
  cualquier fuente**. Así, si hacktorrent saca una peli pero quedan torrents de otros trackers, el
  título sigue visible.
- Sigue sin existir `DELETE` real (todo es soft-delete reversible).

## Observabilidad (saber qué enriqueció y qué trackers funcionan)

Requisito explícito: poder ver que el enriquecimiento corrió, de dónde vino cada torrent, y
analizar con el tiempo qué trackers realmente entregan/matchean.

- **Provenance por torrent:** `torrents.source` (tracker) + `match_score` + `first_seen` (cuándo se
  agregó) + `seeders`. Query directa: `SELECT source, count(*), avg(match_score), avg(seeders) FROM
  torrents WHERE source<>'hacktorrent' GROUP BY source` → cuánto aportó cada tracker.
- **Stats por provider y corrida:** tabla `provider_stats(provider, run_at, titles, found, matched,
  added, errors)` — una fila por (provider, corrida de enrich). Distingue "tracker roto/caído"
  (found=0 sostenido) de "tracker que aporta". `enrich_title` devuelve contadores por provider
  (found/matched) para poder registrarlos.
- **Endpoint `/api/stats`:** resumen para revisar sin entrar a la DB:
  `{by_source: [{source, torrents, avg_score, avg_seeders}], providers: [{provider, last_run, found,
  matched, added, errors}], titles_enriched, titles_pending}`.
- **Por título:** `titles.last_enriched` (cuándo) y `titles.enrich_error` (última falla).

## Robustez (requisito explícito)

- **Aislamiento por provider:** un provider que falla (timeout, HTML cambiado, CF) no aborta el
  enriquecimiento del título ni de los otros providers; se registra el error y se sigue.
- **Fetch:** timeouts por request, reintentos con backoff, **rate-limit por host** (no martillar un
  tracker), User-Agent propio, y **FlareSolverr** para `needsCloudflare`/`renderJs` con fallback.
- **Idempotencia:** correr enrich dos veces sobre el mismo título no duplica (dedup por infohash/magnet).
- **Presupuesto y cortes:** `ENRICH_BUDGET` acota trabajo por corrida; timeout global por título.
- **Degradación:** si `providers.json` remoto falla, usa el bundleado; si un tracker está caído, se
  omite esa fuente sin romper el resto.
- **Observabilidad:** contadores por provider (encontrados/matcheados/errores) en `sync_runs` o log.

## Testing / validación

- **html_backend:** por cada tracker, fixtures HTML reales (guardadas del sitio) → verificar que el
  parser extrae filas/magnet/size/seeds correctamente (como se hizo con los JSON de hacktorrent).
- **matcher:** casos peli (año correcto/remake), serie (S/E correcto/incorrecto), anime; y **rechazos**
  de falsos positivos explícitos.
- **provider_config:** carga/validación de providers.json + fallback.
- **Validación en vivo:** enriquecer unos títulos reales y revisar a mano que los torrents matcheados
  sean correctos (precisión), antes de habilitarlo en las corridas automáticas.

## Deploy

- Mismo `docker-compose.prod.yml` en blog. Sumar deps a `requirements.txt`: `beautifulsoup4`,
  `soupsieve`, `lxml`. `FLARESOLVERR_URL` ya está seteado (`host.docker.internal:8191`).
- Rebuild + `up -d`; `migrate.py` aplica las columnas/índices nuevos.
- El enriquecimiento arranca **desactivado por flag** (`ENRICH_ENABLED=false`) hasta validar
  precisión en vivo; luego se habilita y el backfill corre solo en las corridas horarias.

## Decisiones resueltas (2026-07-25)

1. Disparador: solo títulos nuevos/cambiados + backfill gradual.
2. Matching: fiel al app (año para pelis, S/E para series/anime), preferir precisión.
3. wolfmax4k: intentar vía FlareSolverr en v1.
4. Fuente de providers.json: repo hot-update (raw) + fallback bundleado.
5. `ENRICH_BUDGET=200` títulos/corrida para el backfill (configurable).
6. Referencias: Alfa + Balandro (clonados en el repo) + motor Kotlin del app.
