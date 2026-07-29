# Catálogo con categorías (estilo Elementum) — Diseño (REVISADO a la arquitectura real)

**Fecha:** 2026-07-22
**Rama:** `feat/catalogo-categorias`
**Estado:** Alcance aprobado ("lo más completo y robusto"); pendiente revisión del spec.

## 0. Corrección de arquitectura (vs. primera versión del spec)
La exploración reveló la arquitectura VIVA real (distinta de lo que asumí al inicio):
- La pestaña **"Catálogo"** (móvil, `ArkivRoot.kt:235`) usa **`CineCatalogScreen`** → `TmdbApi.browse/search` → abre `cine/{type}/{tmdbId}` → **`CineDetailScreen`** (que dispara la búsqueda de torrents por Jackett/proveedores). Es TMDB, no Butter.
- `CatalogScreen` (Butter/Popcorn), `CatalogDetailScreen`, `ShowDetailScreen`, `AnimeSection` y `AnimeShowDetailScreen` están **construidas pero MUERTAS**: sus rutas (`catalog/`, `catalog_show/`, `catalog_anime/`) están registradas en el NavHost pero **ningún `navigate()` las alcanza**. El anime (AniList) del otro agente, y sus fixes F1/F5, **no son alcanzables** en la app viva; hoy el anime queda absorbido dentro del tipo `tv` ("Series y anime") de `CineCatalogScreen`.
- Android TV (`ArkivTvRoot`/`TvHomeScreen`) **no tiene catálogo de browse** (solo biblioteca). Fuera de alcance aquí.

Ventaja: como el catálogo vivo YA es TMDB, **todas** las categorías de Elementum son nativas de TMDB (curadas, géneros vía `/discover`, calendarios vía `upcoming`/`on_the_air`), y los calendarios **no necesitan mapeo de ids** (un item de calendario es un `TmdbItem` que abre `cine/` igual que el resto).

## 1. Objetivo y alcance (completo)
Enriquecer el catálogo vivo (`CineCatalogScreen`/TMDB) con categorías estilo Elementum, y **revivir el browse de anime** (AniList) para que sea alcanzable:
1. **Listas curadas** (chips): Populares, Tendencias, Top rated, En cartelera (now_playing / on_the_air), Próximamente (upcoming / airing) — vía TMDB.
2. **Géneros** (Acción, Comedia…): TMDB `/genre/list` + `/discover?with_genres=`.
3. **Calendarios**: la categoría "Próximamente" cubre esto (TMDB upcoming/on_the_air) sin infra extra.
4. **Historial de búsqueda** (Room): últimas búsquedas, tocar = re-buscar, limpiar.
5. **Revivir Anime + géneros de anime**: añadir un tipo "Anime" a `CineCatalogScreen` que renderice la `AnimeSection` existente (AniList) y navegue a `catalog_anime/{anilistId}` → `AnimeShowDetailScreen` (revive las rutas). Añadir selector de género AniList.

**Fuera de alcance (fase 2):** Trakt (OAuth/listas/scrobble social); catálogo de browse para Android TV; discover avanzado (año/idioma/red).

### Baseline vivo
- `CineCatalogScreen`/`CineCatalogViewModel` (`CineCatalogScreen.kt:62,113`): estado `type` (movie|tv), `page`, `query`; `TmdbApi.browse(type,page)`→`/{type}/popular`, `search(type,q,page)`; chips solo "Películas"/"Series y anime" (`:150-151`).
- `TmdbApi` (OkHttp+org.json, es-MX): `browse`/`search`/`detail`/`list(url,type)`/`get(url)`/`parseItem`/`auth="api_key=…&language=es-MX"`/`base="https://api.themoviedb.org/3"`. Solo `popular` para browse.
- `AnimeSection`/`AniListApi` (construidas, F1-F5 aplicados): `browse(page,sort,search)` GraphQL; `AnimeShow.genres` ya existe; navega vía `onOpenAnime(anilistId)`.
- `AnimeShowDetailScreen` registrada en `catalog_anime/{anilistId}` (`ArkivRoot.kt:267`).
- Room `ArkivDatabase` **version 9**.

## 2. Componentes y cambios

### 2.1 `TmdbApi` — nuevas consultas (reusan `list()`/`get()`/`auth`)
- `enum class TmdbCategory { POPULAR, TRENDING, TOP_RATED, NOW_PLAYING, UPCOMING }`.
- `suspend fun curated(type: String, category: TmdbCategory, page: Int): List<TmdbItem>`:
  - movie: popular→`/movie/popular`, top_rated→`/movie/top_rated`, now_playing→`/movie/now_playing`, upcoming→`/movie/upcoming`.
  - tv: popular→`/tv/popular`, top_rated→`/tv/top_rated`, now_playing→`/tv/on_the_air`, upcoming→`/tv/airing_today`.
  - trending (ambos): `/trending/{type}/week`.
  - Todas con `?$auth&page=`.
- `data class TmdbGenre(val id: Int, val name: String)`; `suspend fun genres(type: String): List<TmdbGenre>` → `/genre/{type}/list?$auth` (cache en memoria por type).
- `suspend fun discover(type: String, genreId: Int, page: Int): List<TmdbItem>` → `/discover/{type}?$auth&with_genres={id}&sort_by=popularity.desc&page=`.

### 2.2 `CineCatalogViewModel` + `CineCatalogScreen`
- VM: añadir estado `category: TmdbCategory` (default POPULAR) y `genreId: Int?` (null = sin filtro). En `load()`:
  - si hay `query` → `search` (como hoy);
  - else si `genreId != null` → `discover(type, genreId, page)`;
  - else → `curated(type, category, page)`.
  - setters `setCategory(c)` y `setGenre(id?)` que hacen `reload()`. Al cambiar `type`, resetear genre a null (los géneros difieren por tipo) y recargar géneros del nuevo tipo.
- UI: fila de chips de **categoría** (Tendencias/Populares/Top rated/En cartelera/Próximamente) + un **selector de Género** (chips scrollables poblados con `genres(type)`; "Todos" = sin filtro). Mantener el toggle de tipo y la búsqueda. Paginación existente intacta.

### 2.3 Revivir Anime en `CineCatalogScreen`
- Añadir un tercer valor de tipo **"anime"** al toggle (Películas / Series / **Anime**).
- Cuando `type == "anime"`, en vez del grid TMDB, renderizar la **`AnimeSection`** existente, pasándole `onOpenAnime = { navController.navigate("catalog_anime/$it") }` (cablear desde `ArkivRoot` el `onOpen` de anime, o pasar un callback nuevo a `CineCatalogScreen`).
- Esto revive `AnimeSection` → `catalog_anime/{anilistId}` → `AnimeShowDetailScreen` (con los fixes F1/F5 ya aplicados).

### 2.4 Géneros de anime (AniList)
- `AniListApi.genres(): List<String>` → GraphQL `GenreCollection`.
- `AniListApi.browse(page, sort, search, genre: String? = null)`: si `genre != null`, añadir `genre_in: [$genre]` a `media(...)`. Param con default → no rompe llamadas.
- `AnimeSection`: selector de género (chips con `genres()`), pasa `genre` a `browse`.

### 2.5 Historial de búsqueda (Room v10)
- Entidad `SearchHistoryEntity(query: String @PrimaryKey, kind: String, atMs: Long)` + `SearchHistoryDao` (`upsert`, `recent(limit): List<SearchHistoryEntity>` o Flow, `clear()`).
- `ArkivDatabase` version 9 → 10, con **Migration real** (no destructiva, para no perder biblioteca/progreso): `CREATE TABLE search_history (...)`.
- Al buscar (TMDB o AniList) → `upsert(query, kind, now)`. Cuando el campo de búsqueda está vacío, mostrar las últimas N (tocar = re-buscar). Botón "limpiar".

## 3. Flujo de datos
`CineCatalogScreen`/`AnimeSection` (categoría/género/tipo/búsqueda) → `TmdbApi`/`AniListApi` → `List<TmdbItem>`/`List<AnimeShow>` → grid existente → `cine/{type}/{tmdbId}` o `catalog_anime/{anilistId}` → detalle/reproducción **sin cambios**. Historial vía `SearchHistoryDao`.

## 4. Manejo de errores
- Toda consulta en `runCatching` → lista vacía (patrón de `list()`); UI muestra "sin resultados". Ningún fallo tumba la pantalla.
- `genres()` cacheado; si falla, selector vacío (degrada a solo categorías).
- Migración Room real; si el `CREATE TABLE` falla se maneja con `runCatching` en el DAO y la feature de historial degrada (no rompe el resto de la DB).

## 5. Testing
- **Unit — TmdbApi:** construcción correcta de URLs por `curated`/`discover` (verificar el string por categoría/tipo/género) y parseo de `genres()` (fixture JSON `/genre/list`). Sin red.
- **Unit — AniListApi:** `browse(..., genre=X)` incluye `genre_in` en el query; parseo de `genres()`.
- **Unit — SearchHistory DAO:** `upsert` deduplica por query y actualiza `atMs`; `recent` ordena desc; `clear` vacía (Room in-memory).
- **Compila + suite verde**; UI sin unit test obligatorio (se valida por cableado/compilación).

## 6. Riesgos / notas
- Revivir anime implica tocar `CineCatalogScreen`/`ArkivRoot` (cableado de navegación) — verificar que el toggle de 3 tipos no rompa el reset/paginación existente.
- El VM `CineCatalogViewModel` crece; mantener el `load()` con la prioridad clara (query > genre > category) para no enredar estados.

## 7. Dependencias
Ninguna nueva. Reusa OkHttp/org.json, Room, Compose.
