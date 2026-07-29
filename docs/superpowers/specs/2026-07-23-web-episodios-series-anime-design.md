# Diseño — Resolución de episodios (series y anime) en el layer web

**Fecha:** 2026-07-23
**Rama:** `feat/torrent-buffering-tier1` (in-place)
**Estado:** Diseño aprobado, pendiente plan
**Relacionado:** `2026-07-23-web-playback-resolucion-design.md` (resolver embed→stream + plumbing del player) · `2026-07-22-fuente-web-scraping-design.md` (motor de scraping)

## Problema

La hoja de fuentes web hoy solo funciona para **películas**. Para **series/anime** está rota de raíz, verificado en device (Dragon Ball Z → T1·E1, logs `ArkivWeb`):

1. **Sin navegación serie→episodio.** La búsqueda web es por título y devuelve la **página de la serie** (`/serie/dragon-ball-z`). Al elegirla, `playWeb` le pasa esa landing al resolver, que espera la página de UN episodio con player. Las películas andan porque su `pageUrl` = el video.
2. **Sin filtro de relevancia.** El drawer vuelca todo lo que el sitio devolvió para la query: las ~20 **películas** de DBZ y hasta shows ajenos (Kung Fu Panda, Blue Dragon vía "dragon" suelto). La entrada real "Dragon Ball Z" queda enterrada.
3. **Anime ≠ numeración de TMDB.** TMDB parte el anime por **sagas** (S1…S9); los sitios de anime dedicados usan **lista plana** (Episodio 1..N). Sin reconciliación, "S1·E1" de TMDB no mapea al episodio del sitio.

## Objetivo

Que la fuente web resuelva **episodios de series y anime** end-to-end (search → URL del episodio → resolver de blog → stream), robusto, reusando el patrón **canal↔server de Alfa** (`arkiv-portar-de-alfa`): lo específico del sitio on-device, lo headless genérico en blog.

## Principio rector / límite arquitectónico

- **On-device (por sitio):** navegación serie→temporada→episodio con selectores declarativos en `web_sources.json`. Es el equivalente a `seasons()`/`episodesxseasons()` de los canales `.py` de Alfa. Se beneficia del **fix-loop contra HTML vivo** ya probado (`webDiagnosticsDump`).
- **Blog (genérico):** el resolver Playwright (`/resolve?url=`) recibe la **URL del episodio ya resuelta** y hace pageUrl→stream. **Sin lógica por-sitio** (equivalente a los `server.py` de Alfa). Cambios en el resolver solo si un **host de embed** de anime no sniffea (se porta su patrón, no navegación).

## Flujo nuevo

1. **Search por título** (como hoy) → parse → **filtro de relevancia** → lista limpia de matches (series/pelis).
2. Drawer muestra las fuentes **a nivel serie** (rápido, sin fetch por resultado): "Dragon Ball Z — sololatino", etc.
3. **Al tap** de una fuente web de un episodio: `WebEpisodeLocator` toma `pageUrl de serie + (S,E) + absoluto` → **`pageUrl del episodio`** (lazy, un solo fetch del sitio elegido).
4. Esa URL de episodio entra al camino ya diseñado (`addWebSeriesEpisode` → resolver de blog → player unificado).

Locate **lazy en el tap** (no eager por cada resultado) para no disparar N fetches de páginas de serie. Si falla en un sitio, el usuario prueba otro (igual que las fuentes torrent).

## Componentes

### 1. Schema: bloque `episodes` en `WebSourceDefinition` (+ `web_sources.json`)

Bloque **opcional** por sitio. Sin él, el sitio se comporta como hoy (solo browse/search de series/pelis; no aporta episodios). Forma:

```jsonc
"episodes": {
  "mode": "native",                 // "native" | "flat"
  // --- native: el sitio tiene T/E reales ---
  "seasonSelector": "select#season-select option",   // valor de temporada
  "seasonValueAttr": "value",
  "episodeContainer": "div[data-season-panel='{season}']", // {season} sustituido
  "episodeRow": "a",                // cada <a> = un episodio
  "episodeNumSelector": "p.ep-num", // texto "E1" -> 1
  "episodeUrl": { "attr": "href" },
  // --- flat: lista plana indexada por nº absoluto (anime dedicado) ---
  "flatRow": "ul.episodes li a",
  "flatNumRegex": "Episodio\\s*(\\d+)"
}
```

Reglas via los `FieldRule` existentes (CSS/regex/attr). Validación tolerante: `WebSourceDefinition.fromJson` parsea `episodes` a `WebEpisodeRules?`; inválido → null (el sitio queda como "sin episodios", no rompe la definición).

### 2. `WebEpisodeLocator` (nuevo, puro y testeable)

Entrada: `WebEpisodeRules`, HTML de la página de serie (obtenido con `fetchWithFallback`/`PageFetcher`, reusa hostAlt), `season`, `episode`, `absoluteEpisode`. Salida: `episodePageUrl: String?`.

Cascada de resolución (robustez):
1. **native S/E:** ubica la temporada `season`, luego el episodio `episode` dentro. Si existe → su URL.
2. **flat/absoluto:** aplana todos los episodios (todas las temporadas o la lista plana) ordenados y toma el índice `absoluteEpisode`. Cubre anime dedicado y el caso "sitio con sagas distintas a TMDB".
3. **fallback cruzado:** si el sitio es `native` pero (S,E) no aparece, intenta flat/absoluto sobre sus episodios; y viceversa.
4. **sin match:** devuelve null (log `ArkivWeb`), la fuente se descarta. **Nunca** un episodio equivocado.

Es una función pura sobre HTML → testeable con fixtures de HTML real (patrón `WebFixtureTest`). El fetch se inyecta desde el backend.

### 3. Numeración absoluta (on-device, desde TMDB)

`absoluteEpisode` = Σ(episode_count de temporadas TMDB con número < S, excluyendo Especiales/S0) + `episode`. TMDB ya está cargado en `CineDetailScreen` (`detail.seasons` con conteos). Función pura `TmdbEpisode`→absoluto.

Para casos no triviales (precuelas que el sitio numera corrido, offsets de saga) se **reutiliza** la infra de anime ya presente en la rama (`AnimeQueryInput.absoluteEpisode`, cascada AniList/Simkl de `AnimeEpisodeResolver`) **cuando haya AniList id disponible**; si no, el absoluto de TMDB es el default. No se reordena ni se toca el enum `TorrentLang` (lo usa Cine).

### 4. Filtro de relevancia (resuelve el ruido del drawer)

En el camino de **search** (no en browse/grid), tras `filterByKind`, quedarse solo con resultados cuyo `WebTmdbMatcher.normalize(title)` sea igual a `normalize` de algún `ctx.titles` (match exacto normalizado; tolera "(2024)", tildes, puntuación). Efecto DBZ: quedan las 4 entradas "Dragon Ball Z"; se caen todas las pelis `Dragon Ball Z: <subtítulo>`, Kung Fu Panda, GT, Kai. Aplica también a películas (descarta títulos ajenos). Se implementa en `WebSourceBackend`/engine (tienen `ctx.titles`).

### 5. Canales de anime dedicados

Nuevas definiciones en `web_sources.json`: **animeflv, jkanime, tioanime** (search + `episodes` mode `flat`, tokens LAT/CAST/VOSE). Selectores **portados de Alfa como punto de partida** pero **validados contra HTML vivo** (fix-loop: `webDiagnosticsDump` → workflow de subagentes → fixtures reales → revalidar en el S24+). Los que no respondan desde el device se marcan `enabled:false` con `_nota` (como el resto de dead-ends).

### 6. Wiring en la UI (`CineDetailScreen`)

- `playWeb(r)`: para episodio, antes de `addWebSeriesEpisode`, llamar `webSourceEngine.locateEpisode(siteId, r.pageUrl, ep.season, ep.episode, absolute)`; si devuelve URL de episodio, seguir con esa URL; si null → `error = "El sitio no tiene ese episodio"` (y el usuario prueba otra fuente).
- El drawer sigue mostrando la fuente a nivel serie (rápido). Sin cambios de UX mayores.

## Manejo de errores

- Locate/fetch fallan → null → fuente descartada, log; el player nunca recibe una landing de serie.
- Numeración: si no hay conteos TMDB → absoluto = `episode` (degradación, mejor que crash).
- Resolver de blog: como hoy (timeout 30s, serializado). Si un host de anime no sniffea, se porta su patrón desde `servers/*.py` de Alfa.

## Testing

- **Unit (JVM, `org.json` real ya configurado):** `WebEpisodeLocator` native/flat/fallback contra fixtures de HTML real de cada sitio; numeración absoluta pura (bordes: S0/Especiales, temporada faltante); filtro de relevancia (DBZ deja 4, descarta pelis/ajenos).
- **Device (S24+ USB, serial R5CX7251VRM):** DBZ S1·E1 end-to-end: search → URL de episodio → `/resolve` → play. Un anime dedicado (animeflv) y una serie live-action native (para no regresionar). Logs `ArkivWeb`/`ArkivWebResolve`.

## Fuera de alcance

- Reproducción/subs/multi-audio del resolver (cubierto por `2026-07-23-web-playback-resolucion-design.md`).
- Capa de **torrents** de anime (`arkiv-anime-source-layer`, otra rama).
- Renumeración manual estilo `renumbertools` de Alfa (dialog por-serie): se descarta por no-robusta; se usa numeración automática.

## Orden de implementación (una sola entrega)

1. Filtro de relevancia (quick win, limpia el drawer ya).
2. Schema `episodes` + `WebEpisodeLocator` + numeración absoluta + wiring `playWeb` → DBZ vía sololatino (native) anda.
3. Canales anime dedicados (flat) + validación fix-loop.
4. Ajustes de resolver si algún host no sniffea.
5. Verificación end-to-end en device.
