# Enrichment de series + anime + películas — Diseño

**Fecha:** 2026-07-25
**Estado:** Diseño aprobado (pendiente revisión del spec)
**Extiende:** [enriquecimiento-trackers-server](2026-07-25-enriquecimiento-trackers-server-design.md) (ya implementado, movies-only) y el [mirror](2026-07-24-hacktorrent-mirror-db-design.md).

## Objetivo

Que el enrichment cubra **películas, series y anime** (hoy solo películas). Para las series —que el
mirror no tiene— traer el catálogo de hacktorrent (metadata) y llenarlas de torrents desde los otros
trackers. Para el anime —que ya está con torrents por episodio— sumar más torrents. Descubrir los
episodios sin conocerlos de antemano.

## Decisiones (aprobadas 2026-07-25)

1. **Series al mirror**: traer las 910 series de hacktorrent (metadata-only: título, original_title,
   overview, año, tmdb_id, imágenes, `kind='serie'`). Sin torrents de hacktorrent → los pone el enrichment.
2. **Matching episódico**: búsqueda **solo por título** (no per-episodio), parsear `SxxEyy` de cada
   resultado, guardar el torrent con su `season`/`episode`. Una búsqueda por serie descubre todos los
   episodios que el tracker tiene.
3. **Cadencia**: pelis one-shot; series/anime **re-enriquecidos periódicamente** (nuevos capítulos).
4. Sumar trackers de series/anime (del survey de Alfa/Balandro) — sección aparte.

## Cambios

### Catálogo (crawler)
- El **full crawl** agrega `/series` (paginado como `/movies`/`/animes`), UPSERT como `kind='serie'`,
  metadata-only (0 torrents de hacktorrent). Reusa `build_title` (ya mapea todos los campos).
- `reconcile` de series por `kind='serie'` igual que los otros (soft-delete si desaparece del catálogo).
- Las series arrancan `active` según la regla ya existente: **activo si tiene ≥1 torrent activo de
  cualquier fuente**. Una serie recién traída (0 torrents) queda `active=false` hasta que el enrichment
  le encuentre torrents. (Así el catálogo servido no muestra series vacías.)

### Query episódico (matcher/enrich)
- Para `kind in (serie, anime)`, la query de búsqueda es **solo el título** (`{title}`), NO los templates
  `tv`/`anime` per-episodio de `providers.json` (esos los usa el app para buscar un capítulo puntual;
  nosotros queremos todos). Buscar "Breaking Bad" en eztv/nyaa/etc. devuelve todos sus episodios.
- De cada resultado se parsea `SxxEyy` (reusar `map_episode`/regex existente) → `season`/`episode`.
  Resultados sin S/E parseable (packs de temporada, "Temporada completa") → `is_pack=true`, `episode=null`.

### Matching (por tipo)
- **Película**: como ahora (gate por año, sin S/E, matcher consciente de tags de release).
- **Serie/anime**: matchea por **cobertura de título** (mismo matcher), SIN exigir un S/E puntual
  (queremos todos los episodios). La cobertura + el guard de 1-token evitan matchear la serie equivocada.
  El S/E parseado se guarda pero no se usa como gate a nivel título.

### enrich_title / run_enrich
- `enrich_title(providers, fetcher, title_row)`: si `kind in (serie,anime)`, arma query solo-título,
  parsea S/E por resultado y lo incluye en la fila (`season`/`episode`/`is_pack`). Para pelis, igual que hoy.
- `run_enrich`: **quitar el filtro `kind='movie'`** → cubre los 3 tipos.
  - Elegibilidad: pelis `last_enriched IS NULL OR (torrents hacktorrent cambiaron)`.
  - series/anime: `last_enriched IS NULL OR last_enriched < now() - INTERVAL 'N days'` (re-enriquecer
    periódico para capítulos nuevos; N configurable, ej. 7).
  - Presupuesto `ENRICH_BUDGET` por corrida (ya existe). El re-enrich episódico corre en el full 2×/semana.

### Storage
- Sin cambios de schema: `torrents.season/episode/episode_end/is_pack` ya existen; los enriquecidos los llenan.
- Dedup por infohash (ya). Un episodio de 2 trackers con el mismo infohash → una fila.

### Trackers por tipo
- Guiado por `providers.json`: un provider se usa para episódico si tiene template `tv`/`anime`
  (o si es de todo). **eztv** (TV) y **nyaa** (anime/TV) pasan a aportar de verdad (antes 0 en pelis).
- Sumar trackers nuevos del survey (Alfa/Balandro), priorizando series/anime en español. **[del survey]**

## Testing / validación
- Crawler: test de que `/series` se ingiere como `kind='serie'` metadata-only.
- enrich episódico: fixtures con nombres tipo "Show S01E05 1080p" → parsea S/E correcto, matchea por título.
- Matcher: serie correcta vs otra serie (cobertura), pack de temporada.
- Validación en vivo: enriquecer una serie conocida (ej. "Breaking Bad") y un anime, revisar que trae
  episodios con S/E correcto de eztv/nyaa/españoles (como se hizo con Avatar).

## Fuera de alcance (v2)
- Paginación profunda de resultados episódicos (v2 usa la primera página de cada tracker).
- Catálogo de series desde TMDB (se usa el de hacktorrent).

## Follow-ups relacionados (en curso, aparte)
- Resolver `.torrent` de subtorrents/wolfmax4k (subagente).
- Sumar trackers faltantes de Alfa/Balandro (subagente survey).
