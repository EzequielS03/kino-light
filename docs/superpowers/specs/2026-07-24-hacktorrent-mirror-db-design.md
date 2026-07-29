# Mirror de HackTorrent en `blog` (Postgres + Flask)

**Fecha:** 2026-07-24
**Estado:** Diseño aprobado (pendiente revisión final del spec)

## Objetivo

Construir en el host SSH `blog` una base de datos PostgreSQL bien estructurada que
espeja el catálogo de **hacktorrent.to** (películas + anime, con sus torrents/magnets),
alimentada por un crawler idempotente, y exponerla vía una API Flask en
`torrents.comparadorinternet.co` para que la app Arkiv la consuma.

La DB debe permitir **búsqueda correcta y amplia**: por título (español + original,
insensible a acentos), por `tmdb_id` exacto, por actor/director/género, y con filtros
por año, idioma y calidad.

## Alcance

- **Incluye:** películas y anime (los únicos con torrents en hacktorrent).
- **Contenido episódico:** el anime es episódico (trae `season`/`episode` por torrent, y
  un título puede abarcar varias temporadas). Se mapea temporada+capítulo de forma robusta
  al ingerir (ver `map_episode`). La lógica queda lista para series si algún día tienen torrents.
- **Excluye:** series. Verificado empíricamente que la API de hacktorrent **no** expone
  `downloads` para series (probadas 40 series al azar + Breaking Bad, The Last of Us,
  Stranger Things → 0 descargas; sin `?season`, sin ruta `/episodes`, sin contenedor
  alternativo). El sitio es una SPA React ("wpreact") alimentada 100% por el JSON API,
  así que el crawler **no necesita scrapear HTML**.

## Hallazgos sobre la fuente (hacktorrent `wpreact/v1`)

Base: `https://hacktorrent.to/wp-json/wpreact/v1`

### Endpoints de listado (enumeración completa)
- `GET /movies?posts_per_page=N&page=P` → `{"movies":[...], "total":N, "pages":P}`
- `GET /animes?posts_per_page=N&page=P` → `{"animes":[...], "total":N, "pages":P}`
- `GET /released?posts_per_page=N&page=P` → `{"movies":[...]}` (lo más reciente; base del incremental)
- (`/series` existe pero se ignora.)

### Endpoints de detalle (traen `downloads[]`)
- `GET /movie/{slug}` → título + `downloads[]`
- `GET /anime/{slug}` → título + `downloads[]` (cada uno con `season` y `episode`)

### Tamaño del catálogo (2026-07-24)
- movies: **13.697** · animes: **131** · (series: 910, ignoradas)

### Campos de un título
`id, slug, title, overview, original_title, duracion, background_image, trailer,
featured, years (fecha de estreno), actors (CSV), country, director, genres (CSV),
year, tmdb_id, imdb (rating), importer_version, type (pelicula|anime), language, downloads[]`

### Campos de un `download` (torrent)
`quality (ej "WEB-DL 1080p"), date (ej "20240105"), size (ej "9.06 GB"), subs (0|1),
total_download, download_type (ej "link"), download_link (magnet con btih),
language (ej "Latino/Inglés")`. En anime además: `season` (int), `episode` (int).

Muestras reales guardadas durante el diseño: `movie.json`, `anime.json`, `serie.json`
(en el scratchpad de la sesión) — se copiarán al repo como fixtures de test.

## Arquitectura

Tres piezas desacopladas en `blog`, todas Python (no se compila Rust en blog):

```
hacktorrent.to (wpreact JSON API)
      │  crawler (Python, systemd --user timer)
      ▼
  PostgreSQL (mirror, fuente de verdad)
      │
      ▼
  Flask API (gunicorn) ── cloudflared ──► torrents.comparadorinternet.co ──► app Arkiv
```

- **Crawler:** lee la API y hace UPSERT idempotente en Postgres.
- **Postgres:** DB estructurada y normalizada.
- **Flask API:** dos caras (drop-in + rica). Se despliega en **Coolify** (ver sección Despliegue).

## Esquema de la DB (PostgreSQL)

Extensiones: `unaccent`, `pg_trgm`.

**`f_unaccent` (IMMUTABLE):** `unaccent()` es `STABLE`, no `IMMUTABLE`, así que no puede usarse
directo en columnas `GENERATED` ni en índices funcionales. Se define un wrapper inmutable:
```sql
CREATE OR REPLACE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS
$$ SELECT public.unaccent('public.unaccent', $1) $$;
```
Todo lo que necesite inmutabilidad (search_doc generado, índices de expresión) usa `f_unaccent`.

### `titles`
Un registro por película/anime.

| columna | tipo | notas |
|---|---|---|
| `id` | bigint PK | id de hacktorrent |
| `slug` | text UNIQUE NOT NULL | clave de UPSERT |
| `kind` | text | `movie` \| `anime` |
| `title` | text | español |
| `original_title` | text | |
| `overview` | text | |
| `year` | int | |
| `release_date` | date | de `years` |
| `duration_min` | int | parseado de `duracion` ("130m") |
| `tmdb_id` | bigint | indexado (matching exacto) |
| `imdb_rating` | numeric(3,1) | de `imdb` |
| `country` | text | |
| `director` | text | crudo (también normalizado en `people`) |
| `language` | text | idioma del título |
| `img_background` | text | |
| `img_featured` | text | |
| `trailer` | text | |
| `importer_version` | text | |
| `active` | boolean | soft-delete (false si desaparece del full crawl) |
| `first_seen` | timestamptz | |
| `last_seen` | timestamptz | actualizado en cada crawl que lo ve |
| `updated_at` | timestamptz | |
| `actors_text` | text | CSV crudo de actores (alimenta search_doc) |
| `genres_text` | text | CSV crudo de géneros (alimenta search_doc) |
| `search_doc` | tsvector | GENERATED **con pesos**: `setweight(title,'A') ‖ setweight(original_title,'B') ‖ setweight(actors+director,'C') ‖ setweight(genres,'D')`, todo vía `to_tsvector('spanish', f_unaccent(...))` |

Índices: `UNIQUE(slug)`, `btree(tmdb_id)`, `btree(kind)`, `btree(year)`,
`GIN(search_doc)`, `GIN(f_unaccent(title) gin_trgm_ops)`, `GIN(f_unaccent(original_title) gin_trgm_ops)`.

### `torrents`
Un registro por download.

| columna | tipo | notas |
|---|---|---|
| `id` | bigserial PK | |
| `title_id` | bigint FK→titles | |
| `infohash` | text | btih normalizado (lower, hex-40; base32→hex si aplica) |
| `magnet` | text | magnet completo |
| `quality` | text | |
| `size_bytes` | bigint | parseado de `size` |
| `size_label` | text | crudo ("9.06 GB") |
| `lang_raw` | text | "Latino/Inglés" |
| `lang_norm` | text | enum tipo `TorrentLang` del app (LATINO/DUAL/CASTELLANO/JAP_SUB/ENGLISH/OTHER) |
| `subs` | boolean | |
| `season` | int NULL | episódico (anime) |
| `episode` | int NULL | episódico; primer episodio si es pack |
| `episode_end` | int NULL | último episodio si el torrent es un pack/rango |
| `is_pack` | boolean | true si cubre varios episodios o temporada completa |
| `source_date` | date | de `date` ("20240105") |
| `download_type` | text | |
| `active` | boolean | soft-delete |
| `first_seen` / `last_seen` | timestamptz | |

Índices: `btree(title_id)`, `btree(infohash)`, `btree(lang_norm)`, `btree(quality)`,
`UNIQUE(title_id, infohash)` (dedupe por título; si `infohash` es null, `UNIQUE(title_id, magnet)`).

### `people` + `title_people`
- `people(id serial PK, name text UNIQUE, name_unaccent text)`
- `title_people(title_id FK, person_id FK, role text /* actor|director */, PRIMARY KEY(title_id, person_id, role))`
- Índice trigram sobre `people.name_unaccent`.

### `genres` + `title_genres`
- `genres(id serial PK, name text UNIQUE)`
- `title_genres(title_id FK, genre_id FK, PRIMARY KEY(title_id, genre_id))`

### `sync_runs` (observabilidad)
- `sync_runs(id serial PK, kind text /* incremental|full */, started_at, finished_at,
  titles_seen int, torrents_upserted int, errors int, ok boolean)`
- La API `/api/health` lee la última corrida.

## Crawler (ingesta)

Un solo módulo con dos modos, invocado por systemd.

### Normalizaciones (unidad testeable pura)
- `parse_size("9.06 GB") -> 9728000000` (soporta GB/MB/KB, coma o punto).
- `parse_duration("130m") -> 130`.
- `parse_date("20240105") -> date(2024,1,5)`.
- `extract_infohash(magnet) -> hex40 | None` (btih hex-40 directo; base32-32 → decodifica a hex-40).
- `norm_language("Latino/Inglés") -> "LATINO"` (misma prioridad/tokens que `TorrentSearchApi.TorrentLang` del app; reusar la lógica de tokens de idioma existente).
- `split_csv("a, b ,c") -> ["a","b","c"]` (trim, drop vacíos).
- `map_episode(download, dn_name) -> (season, episode, episode_end, is_pack)` — mapeo de
  temporada/episodio para contenido episódico (anime):
  - **Fuente primaria:** campos `season`/`episode` del API (siempre presentes en anime).
  - **Validación/fallback:** parsear del `dn` del magnet (y del `title`) con regex, en este
    orden: `SxxEyy` (con rango opcional `S01E01-E12` / `S01E01E12`), `NxNN` ("1x05"),
    y castellano "Temporada X Capítulo Y". Si el API no trae season/episode, se usa esto.
  - **Packs:** si el nombre indica un rango (`E01-E12`, "Cap 1 al 12") o temporada completa
    (sin `Eyy`, o "Temporada completa"), setear `episode`=primero, `episode_end`=último
    (o null si desconocido) e `is_pack=true`.
  - **Mismatch:** si API y `dn` discrepan en season/episode, se confía en el API pero se
    registra el caso en `sync_runs.errors` (y log) para auditar la fuente.

### Modo incremental (timer cada ~3h)
1. Registrar `sync_runs(kind=incremental)`.
2. Leer `/released` (feed FIJO de los títulos más recientes: ignora `page`/`posts_per_page`
   y no trae `pages`, así que es 1 request que devuelve los ~N más nuevos — no es paginable).
3. Para cada slug: bajar `/movie/{slug}` (son películas) y UPSERT (título + torrents + people +
   genres), marcando `last_seen = now()`. El anime nuevo y el resto del catálogo los cubre el
   full semanal (por eso el incremental no reconcilia: solo agrega/actualiza lo reciente).

### Modo full (semanal)
1. Registrar `sync_runs(kind=full)`.
2. Paginar `/movies` y `/animes` completos (usar `pages`), bajar detalle de cada uno,
   UPSERT como arriba.
3. **Reconciliación:** los `titles`/`torrents` cuyo `last_seen` < inicio de esta corrida
   se marcan `active=false` (no se borran).

### Robustez
- UA propio, rate-limit suave (sleep configurable), reintentos con backoff.
- Si Cloudflare bloquea (challenge), caer a **FlareSolverr**.
- Idempotente por `slug` (+ `tmdb_id` de refuerzo). Correr dos veces seguidas no cambia nada.
- Transacción por título (un detalle que falla no aborta el resto).

## Búsqueda (Postgres FTS avanzado)

Objetivo: búsqueda **rica** (por título es/original, tmdb_id, actor/director/género) con
tolerancia a acentos y typos, y buen ranking. Todo dentro de Postgres (una sola fuente de verdad).

- **Acentos:** `f_unaccent` (IMMUTABLE) en el `search_doc` generado y en los índices trigram.
  Así "accion" ≡ "acción", "pinata" ≡ "piñata".
- **Full-text con pesos:** `search_doc` usa `setweight`: título=A, título original=B,
  actores+director=C, géneros=D. La query usa `plainto_tsquery('spanish', f_unaccent(q))`.
- **Ranking por relevancia:** `ts_rank_cd(search_doc, query)` como score primario; para queries
  que no matchean FTS (typos), score secundario por `word_similarity()` de pg_trgm sobre título.
  Orden final: `GREATEST(ts_rank_cd, word_similarity) DESC`, desempate por `imdb_rating`/`year`.
- **Fuzzy/typos:** `pg_trgm` con operador `<%` (**word_similarity**, matchea la query contra la
  palabra más parecida del título — funciona en títulos multi-palabra, ej. "matriix" →
  "Matrix: Revoluciones", cosa que `%`/similarity de string completo no logra). Usa el índice
  GIN trigram sobre `f_unaccent(title)`/`f_unaccent(original_title)`. Umbral configurable.
- **tmdb_id exacto:** atajo directo (índice btree), gana a cualquier match textual.
- **Facetas/filtros combinables:** `kind`, `year` (o rango), `genre`, `actor`, `director`,
  `lang`, `quality`, `season`, `episode` — vía joins a las tablas normalizadas + columnas de
  `torrents`. Se pueden combinar con la búsqueda textual.
- **Autocompletar:** `GET /api/suggest?q=` devuelve títulos por prefijo/typo (trigram +
  `to_tsquery(... :*)` para prefijos), rápido, para as-you-type.

Nota: si en el futuro se quiere UX instant-search estilo Algolia, se puede agregar Meilisearch
como índice secundario alimentado desde Postgres (fuera de alcance en esta versión).

## Flask API

Servida con gunicorn; sólo lectura sobre Postgres (pool de conexiones).

### Cara A — drop-in hacktorrent (cero cambios de código en la app)
Replica el shape exacto de hacktorrent para que la app sólo cambie `baseUrl` en
`providers.json` (hot-update vía repo `lordmacu/arkiv-providers`):

- `GET /wp-json/wpreact/v1/search?query=&tmdb_id=` → `{"results":[...]}` con el mismo
  formato de item que hacktorrent. Búsqueda mejorada: insensible a acentos (`unaccent`
  + FTS español, fallback trigram) y opción de `tmdb_id` exacto.
- `GET /wp-json/wpreact/v1/movie/{slug}` → título + `downloads[]` (mismo shape).
- `GET /wp-json/wpreact/v1/anime/{slug}` → título + `downloads[]` con `season`/`episode`.

Se valida contra las fixtures reales de hacktorrent (los campos que la app consume:
`slug, title, type, tmdb_id, downloads[].download_link/quality/size/language/season/episode`).

### Cara B — API rica (para el futuro, sin tocar la app hasta que se quiera)
- `GET /api/search?q=&kind=&year=&genre=&actor=&director=&lang=&quality=&tmdb_id=&season=&episode=&page=&page_size=`
  → títulos paginados con torrents normalizados. `q` usa FTS con pesos + ranking `ts_rank_cd`
  y fallback trigram (typos); el resto son filtros/facetas combinables (incluye `season`/`episode`);
  ordena por relevancia, desempate `imdb_rating`/`year`.
- `GET /api/suggest?q=` → autocompletar (prefijo + typo), lista corta de títulos para as-you-type.
- `GET /api/title/{slug}` → detalle completo (metadatos + people + genres). Para anime, los
  torrents vienen **agrupados por temporada → episodio** (`seasons: [{season, episodes:[...]}]`,
  con los packs listados aparte). Para pelis, lista plana de torrents.
- `GET /api/health` → `{ok, last_sync: {kind, finished_at, titles_seen}, counts:{titles,torrents}}`.

Nota: hacktorrent no da conteo de seeds; el shape drop-in no incluye `seeders` (la app
ya asume "vivo, desconocido" para este provider). La API rica tampoco inventa seeds.

## Despliegue (Coolify)

Todo el stack se despliega en **Coolify** (PaaS self-hosted sobre Docker), no en systemd/cloudflared.

- **Repo del código:** `hacktorrent-mirror` (Dockerfile + `mirror/` + `sql/` + fixtures),
  conectado a Coolify vía Git (auto-deploy on push).
- **Postgres:** recurso de base de datos gestionado por Coolify (PostgreSQL 16). Las
  extensiones `unaccent`/`pg_trgm` y `f_unaccent` se aplican con el `sql/schema.sql`
  (idempotente) en el primer deploy / migración.
- **API Flask:** aplicación Docker en Coolify (gunicorn escuchando en el puerto expuesto),
  con **dominio y SSL automáticos** (Traefik). Dominio: `torrents.comparadorinternet.co`
  (el DNS debe apuntar al host de Coolify).
- **Crawler:** **Scheduled Task** de Coolify (cron) que corre `python -m mirror.crawler`:
  - incremental: `--mode incremental` cada ~3h.
  - full: `--mode full` semanal.
  (Corre en el mismo proyecto/red que Postgres; toma `DATABASE_URL` de las env vars del proyecto.)
- **Config:** variables de entorno del proyecto en Coolify (`DATABASE_URL`, `HACKTORRENT_BASE_URL`,
  `RATE_LIMIT_S`, `FLARESOLVERR_URL` opcional).
- **Dev local:** Postgres de prueba con Docker (`docker run ... postgres:16`) para correr los tests.
- **Prerrequisito a confirmar:** en qué host corre Coolify y que el DNS de
  `torrents.comparadorinternet.co` apunte ahí (o vaya por Cloudflare hacia ese host).

## Testing

- **Normalizaciones del crawler:** unit tests puros de `parse_size`, `parse_duration`,
  `parse_date`, `extract_infohash` (hex y base32), `norm_language`, `split_csv`.
- **Mapeo de episodios (`map_episode`):** casos con season/episode del API; fallback por
  `dn` (`SxxEyy`, `1x05`, "Temporada X Capítulo Y"); packs/rangos (`E01-E12`, temporada
  completa) → `is_pack`/`episode_end`; y detección de mismatch API↔`dn`. Fixtures desde
  `anime.json` real (incl. show multi-temporada como *Classroom of the Elite*).
- **Mapeo detalle→filas:** dado `movie.json`/`anime.json` reales, verificar el conjunto
  de filas `titles`/`torrents`/`people`/`genres` resultante.
- **API drop-in:** comparar el JSON de `/movie/{slug}` y `/anime/{slug}` contra las
  fixtures de hacktorrent (campos que la app consume).
- **API rica:** tests de filtros (tmdb_id exacto, actor, género, año/idioma/calidad) y de
  búsqueda sin acentos ("accion" ≡ "acción").
- **Idempotencia:** correr el UPSERT dos veces sobre la misma fixture no cambia conteos.

## Decisiones resueltas (aprobadas 2026-07-24)

1. Nombre de dominio: **`torrents.comparadorinternet.co`**.
2. Ubicación del código: repo nuevo **`hacktorrent-mirror`**.
3. Motor de búsqueda: **Postgres FTS avanzado** (pesos + `ts_rank_cd` + `pg_trgm` fuzzy +
   `f_unaccent` + `/api/suggest`). Meilisearch queda como opción futura, fuera de alcance.
4. Despliegue: **todo por Coolify** (Postgres gestionado + app Flask Docker + crawler como
   Scheduled Task + dominio/SSL por Traefik). Reemplaza systemd/cloudflared.
   - Pendiente: confirmar host de Coolify + apuntar DNS del dominio a ese host.
