# Anime Source Layer — Diseño

**Fecha:** 2026-07-21
**Estado:** Aprobado tras spikes de validación
**Ámbito:** Arkiv Player (`com.arkiv.player`)

## 1. Problema

Arkiv ya tiene catálogo de anime (AniList) y reproducción de torrents, pero la
**ruta de anime es la pobre** comparada con la de cine/TMDB:

| | Ruta Cine/TMDB (rica) | Ruta Anime (actual) |
|---|---|---|
| Backends | Apibay + Knaben + Jackett (fan-out) | **Solo AnimeTosho** |
| Idioma | clasifica y rankea | **no aplica prioridad de audio** |
| Numeración | regex + relevancia | regex ad-hoc, sin offset absoluto |

Consecuencia: al buscar anime hoy **no aparece el latino/castellano** (que vive en
Jackett) y el matching de episodios es frágil (absoluto vs estacional, cours, batches).

## 2. Objetivo

Unificar la búsqueda de fuentes de anime dentro del pipeline multi-backend
(`TorrentSearchApi`), heredando fan-out + dedup + ranking, y añadir un **resolver de
numeración robusto** que produzca los queries y el filtro correctos.

**Prioridad de audio (ranking):** Latino → Castellano → Dual → Japonés → Inglés.
El idioma manda sobre seeders; los seeders desempatan.

## 3. Arquitectura

```
AnimeShowDetailScreen (AniList show + episodio elegido)
   └─ AnimeEpisodeResolver.spec(show, ep)
        ├─ AnimeMappingRepository.mappingFor(anilistId)  → cross-IDs + tvdbSeason   [Fribb, Room cache]
        ├─ SimklApi (si falta dato)                      → alt_titles + verificación
        ├─ AniListApi.absoluteOffset(anilistId)          → offset absoluto
        └─ TmdbApi.detail(tmdbId, es-MX)                 → título en español
      = SourceQuerySpec { titles[], absEp, seasonEp, queries[], matches() }
   └─ TorrentSearchApi.searchAnime(spec, prefer=[LAT,CAST,DUAL,JP,EN])
        └─ fan-out Apibay + Knaben + Jackett  → clasifica + dedup + rank
   └─ usuario elige → resolveSource → TorrentEngine → ArkivRepository.addAnimeEpisode → player
```

### Componentes

| Componente | Rol | Estado |
|---|---|---|
| `AnimeMappingRepository` | Baja y cachea (Room) el dataset Fribb; `mappingFor(anilistId)` → cross-IDs + `tvdbSeason` | **Nuevo** |
| `SimklApi` | `search/id?anilist=` → cross-IDs; `anime/{id}?extended=full` → `alt_titles`, `total_episodes`, `mapped_tvdb_seasons`. Header `simkl-api-key = SIMKL_CLIENT_ID` | **Nuevo** |
| `AnimeEpisodeResolver` | Lógica pura: show + episodio → `SourceQuerySpec` (queries + predicado `matches`) | **Nuevo** |
| `AniListApi.absoluteOffset()` | Traversal de precuelas TV sumando episodios | **Extiende** existente |
| `TorrentSearchApi.searchAnime()` | Entry point anime-aware: fan-out multi-backend con queries del spec + ranking de idioma | **Extiende** existente |
| `AnimeShowDetailScreen` | Cambia su fuente de `animeApi.search()` → `searchAnime(spec)` | **Reuso, 1 cambio** |

Convenciones respetadas: OkHttp + `org.json` + coroutines + DI manual en `AppGraph`
(`by lazy`), sin Retrofit/Hilt. Claves desde `BuildConfig`/`.env`
(`SIMKL_CLIENT_ID`), Jackett ya cableado en `AppGraph`.

## 4. Resolver de numeración — cascada de 3 capas

`AnimeEpisodeResolver` resuelve IDs y numeración en orden de confianza; cada capa
rellena lo que falte de la anterior:

1. **Fribb dataset (`Fribb/anime-lists`, `anime-list-full.json`)** — offline, cacheado.
   Da cross-IDs (`tvdb_id`, `imdb_id`, `themoviedb_id`, `mal_id`, `simkl_id`) +
   `season.tvdb`. **No trae `episode_offset` para TV multi-temporada** (solo ~626
   OVAs/especiales), así que el offset absoluto lo ponen las capas 2/3.
2. **Simkl** — `alt_titles`, `total_episodes` autoritativo (clave para series en
   emisión donde AniList da `episodes=null`, p. ej. One Piece), `mapped_tvdb_seasons`.
3. **Traversal AniList** — camina `relations` PREQUEL de formato TV sumando
   `episodes` → offset absoluto. Fallback cuando no hay dato externo.

**Red de seguridad (heurístico):** siempre se generan variantes de query:
`romaji / inglés / nativo / español (TMDB) / sinónimos (Simkl)` ×
`{title} {absEp:02}`, `{title} - {absEp}`, `{title} S{season:02}E{ep:02}`,
`{title} {ep:02}`, rangos `01-12` para batches. Títulos dedup + acentos normalizados
(NFKD) + descarte de títulos CJK puros.

`SourceQuerySpec.matches(releaseName): Boolean` acepta cualquiera de las formas de
numeración → el filtro de relevancia no descarta el release correcto ni deja pasar
otros episodios. Si con predicado estricto salen 0 candidatos, relaja a match por
título (comportamiento ya existente en `TorrentSearchApi`).

## 5. Ranking de idioma

Lista de prioridad **explícita** (no el ordinal del enum `TorrentLang`, que hoy es
LATINO/DUAL/CASTELLANO/JAP_SUB/OTHER/ENGLISH):

```
[LATINO, CASTELLANO, DUAL, JAP_SUB, ENGLISH, OTHER]
```

Comparador: ordena por `(índiceDePrioridad, streamingFriendly, -seeders)`.

**Nota de los spikes:** el grueso del audio español de anime shippea como **"Dual"**
(japonés + español), no como "Latino" puro. El clasificador debe puntuar `Dual` alto
(marcadores `dual`, `dual audio`, `dual-audio`).

## 6. Manejo de errores (degradación; nunca bloquea el play)

- Fribb no baja / entrada ausente → sigue con Simkl + traversal.
- Simkl 429/timeout → sigue sin sus sinónimos.
- Traversal falla → `absEp = null`, usa solo `SxxEyy` + heurístico.
- 0 candidatos estrictos → relaja a relevancia por título.
- Dataset Fribb en Room con TTL semanal; siempre sirve lo cacheado aunque el refresh
  falle.

## 7. Testing

- **Unit puro (sin red):** `AnimeEpisodeResolver` con fixtures — AoT (offset 59 →
  abs 64), One Piece (absoluto, `episodes=null`), Frieren (una temporada), batch/pack.
  Assert de queries generadas y de que `matches()` acepta `05` y `64` y rechaza otros
  episodios.
- **Parser Fribb** con JSON fixture recortado.
- **Ranking**: releases mock → orden respeta LAT>CAST>DUAL>JP>EN, desempate por seeders.
- Validación con red real: cubierta por los spikes (sección 9), fuera de la suite.

## 8. Alcance (YAGNI)

- **No** UI nueva: reuso `AnimeShowDetailScreen`, solo cambio la fuente de datos.
- **No** OAuth de Simkl (el `CLIENT_SECRET` queda para futuro sync de watchlist).
- **No** dataset de mapeo embebido: se baja y cachea en Room.
- Indexers latino = config de Jackett, no código.
- **No** se toca la ruta de cine/TMDB.

## 9. Validación por spikes (2026-07-21)

Todos contra APIs/datos reales antes de comprometer el diseño:

| Spike | Resultado | Evidencia |
|---|---|---|
| A — Simkl | ✅ | `search/id?anilist=21` → simkl id + `total_episodes:1176` + `alt_titles` + `mapped_tvdb_seasons`, sin OAuth |
| B — Fribb | ✅ cross-IDs / ⚠️ sin offset TV | tvdb/imdb/tmdb/mal + `season.tvdb`; `episode_offset=None` en TV → lo pone el traversal |
| C — Traversal AniList | ✅ | AoT Final Season: offset 59 (25+12+22), ep1 = abs 60 |
| D — Payoff latino | ✅ (vía Jackett) | Demon Slayer 36 latino/dual, Dragon Ball Super 152, Frieren 51. Público (apibay/Knaben) ≈ 0 |
| E — Puente TMDB | ✅ (por `tmdb_id` directo) | Frieren → "Frieren: Más allá del final del viaje". Por `imdb` fallaba |
| Capstone E2E | ✅ | AoT ep 5: matchea `05` y `64`; DUAL(26 seed) rankea sobre JP-SUB(27 seed) |

**Decisión clave derivada:** el valor real es meter el anime al pipeline con Jackett
+ priorizar "Dual"; los backends públicos aportan poco latino.

## 10. Orden de implementación sugerido

1. `AnimeMappingRepository` + entidad/DAO Room + parser Fribb (con tests de parser).
2. `SimklApi` (lookup + detalle).
3. `AniListApi.absoluteOffset()` (traversal).
4. `AnimeEpisodeResolver` + `SourceQuerySpec` (tests unit puros — el núcleo de valor).
5. `TorrentSearchApi.searchAnime()` + lista de prioridad de idioma explícita.
6. Cablear en `AppGraph` y cambiar `AnimeShowDetailScreen` a la nueva fuente.
