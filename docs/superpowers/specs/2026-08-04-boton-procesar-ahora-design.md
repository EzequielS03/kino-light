# Botón "Procesar ahora" (forzar crawl/enrich de un título puntual)

Fecha: 2026-08-04 · Estado: diseño

## Objetivo

El crawler de fuentes web del mirror (`~/hacktorrent-mirror` en blog) procesa títulos en lotes
batcheados (`WEB_CRAWL_BUDGET=200` por corrida, priorizados por `last_web_crawled NULLS FIRST`).
Un título recién agregado o poco popular puede tardar días en tener su turno — ya nos pasó dos
veces en esta sesión (Ranma ½, Dr. House: ambos con torrents pero sin fuentes web porque el
crawler todavía no los había tocado). Hoy la única forma de forzar el procesamiento de un título
puntual es que Claude entre por SSH a blog y corra un script ad-hoc. Este feature le da al usuario
un botón en la app para disparar esa misma señal él mismo, sin intermediarios.

## Alcance de esta iteración

- Un botón en la pantalla de resultados de búsqueda (`SearchScreen`, fase RESULTS) que dispara el
  procesamiento **del título actualmente seleccionado** — no de un catálogo ni de un lote.
- Cubre **tanto fuentes web (los 3 sitios) como torrents (enrich)**, en paralelo.
- Cubre también el caso "el título ni siquiera existe en el mirror todavía" (créalo desde TMDB).
- Fuera de alcance: reprocesar múltiples títulos a la vez, un panel de administración, o cambiar
  el comportamiento del crawler batcheado existente (`htm-web-crawl.timer` sigue igual).

## Arquitectura

**Sincrónico, con cooldown server-side.** La app dispara la request y espera (~60-90s) a que el
servidor termine web+torrents en paralelo; al recibir la respuesta, vuelve a correr la búsqueda de
fuentes para refrescar la pantalla sola. Se prefirió sobre polling porque el volumen de uso es
bajo (un botón que el usuario toca ocasionalmente, no un flujo continuo) y no vale la pena el
costo de construir job-tracking en el servidor para este caso.

El endpoint requiere una API key simple porque el mirror es público
(`torrents.comparadorinternet.co`, hoy sin auth en ningún endpoint) y esta acción dispara trabajo
real: scraping a sitios externos + carga en el NUC. Sin la key, cualquiera que descubra la URL
podría spamear el endpoint. El cooldown de 10 minutos por título es una segunda capa: protege
contra doble-tap/reintentos y contra pegarle repetido a los sitios scrapeados.

## Backend (`hacktorrent-mirror`)

**`POST /api/refresh`**, header `X-Api-Key` (nueva env var `REFRESH_API_KEY`; sin ella o con una
key incorrecta → `401`).

Body:
```json
{"tmdb_id": 1408, "kind": "serie", "title": "Dr. House", "year": 2004}
```
`kind` es `"movie" | "serie" | "anime"` (mismo vocabulario que usa `resolveSlug` hoy). `title` y
`year` son solo un fallback para mensajes de error legibles — la fuente de verdad para crear el
título, si hace falta, es TMDB.

**Resolución del título:**
1. Busca en `titles` por `tmdb_id` (con fallback sin `kind`, mismo criterio que ya usa
   `resolveSlug` — ver [[arkiv-busqueda-torrents-matching]]).
2. Si no existe: llama a TMDB (`TmdbClient`, ya en el proyecto) con `tmdb_id`+`kind` para traer
   título/año/original_title canónicos, arma el dict de 20 columnas con `build_new_title()`
   (mismo código que ya usa `htm-web-catalog`, en `mirror/web/catalog.py`) y lo inserta. El `id`
   sintético se deriva determinísticamente de `tmdb_id`+`kind` (variante de
   `synthetic_title_id`, hoy keyed por `page_url`, aquí keyed por `f"tmdb:{kind}:{tmdb_id}"`) —
   así dos llamadas con el mismo `tmdb_id` nunca crean dos filas.
3. Si TMDB no devuelve nada para ese `tmdb_id`+`kind` → `404` con mensaje claro.

**Cooldown:** antes de procesar, si `last_web_crawled` es de hace menos de 10 minutos → `429`
(`{"error": "ya se proceso hace poco, esperá unos minutos"}`). No hay una tabla separada de
"último enrich de torrents por título" hoy — se reusa `last_web_crawled` como proxy único de
cooldown para ambos (torrents+web se disparan siempre juntos en este endpoint, así que un solo
timestamp alcanza).

**Procesamiento (en paralelo, no en serie):**
- `run_web_crawl(conn, client, sites, [title], cfg=cfg, tmdb=tmdb, progress=...)` — ya existe,
  mismo código que usa `--mode full`, acá invocado para una lista de un solo título.
- `enrich_title(providers, fetcher, title_row)` (`mirror/enrich.py`) — mismo código que usa el
  enrich batcheado, para un solo título.

Ambos se lanzan con `ThreadPoolExecutor` (2 workers) y se espera a los dos. Igual que
`run_web_crawl`, actualiza `last_web_crawled` (para el cooldown) y hace el reconcile normal al
terminar.

**Respuesta:**
```json
{"ok": true, "created": false, "web_sources_added": 185, "torrents_added": 12}
```
En error (título no encontrado en TMDB, timeout de un sitio, etc.) → `ok: false` + `error` con
mensaje corto; no aborta si SOLO una de las dos partes (web o torrents) falla — reporta lo que sí
funcionó (mismo criterio de "una web caída no rompe a las demás" que ya usa `run_web_crawl`).

## Android (Arkiv)

- **Botón "Procesar ahora"** arriba de la fila de tabs (Todo/Torrent/Web/Archive) en
  `SearchScreen`, visible siempre en fase RESULTS (para película, serie o anime — la card ya tiene
  `tmdbId`+`kind`+`title`+`year`, todo lo que pide el endpoint).
- **`MirrorApiClient`:** nuevo método `suspend fun refresh(tmdbId, kind, title, year): RefreshResult`
  — POST con `X-Api-Key`, mismo `OkHttpClient` que ya usa la clase, timeout largo (readTimeout
  ~100s, cubre el peor caso de ~60-90s del servidor + margen).
- **`SearchViewModel`:** nuevo estado `_processingNow: StateFlow<Boolean>` + función
  `processNow()` — llama a `refresh()`, y al volver (éxito o error) llama de nuevo a
  `runSourceSearch(refineSeason.value, refineEpisode.value)` para refrescar los resultados con lo
  nuevo (si hubo error, igual refresca — puede que el cooldown haya rechazado pero el título ya
  tuviera datos previos que vale la pena re-mostrar).
- **UI del botón:** deshabilitado + spinner mientras `processingNow=true`. Si la respuesta es un
  error (401/404/429/500), un snackbar corto con el mensaje; no hay pantalla de error dedicada.
- **`SettingsStore`:** nuevo campo `refreshApiKey` (mismo patrón que `nucApiKey`:
  `DEFAULT_REFRESH_API_KEY` hardcodeado como default, ya que es la key de un servidor propio del
  usuario, editable en Ajustes por si rota la key).

## Testing

**Backend (TDD, igual que los fixes de esta sesión):**
- Título existente, sin cooldown → procesa, devuelve `sites_ok` y conteos.
- Título existente, en cooldown (`last_web_crawled` < 10 min) → `429`, no dispara ningún crawl.
- Título NO existente, `tmdb_id` válido → lo crea (verificar fila en `titles`) y procesa.
- Título NO existente, `tmdb_id` inválido/sin match en TMDB → `404`.
- Sin `X-Api-Key` o key incorrecta → `401`, no toca la DB.
- Dos llamadas seguidas con el mismo `tmdb_id` para un título que no existía → la segunda encuentra
  el título ya creado por la primera (no duplica fila).

**Android:** sin tests automatizados de UI en este proyecto (verificación manual en el device,
mismo criterio que el resto de las pantallas — ver [[arkiv-android-project]]).

## Relacionado

[[arkiv-busqueda-torrents-matching]], [[arkiv-fuente-web-estado]], [[arkiv-hacktorrent-mirror]]
