# Catálogo con categorías — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Añadir categorías estilo Elementum al catálogo vivo de Arkiv (`CineCatalogScreen`/TMDB): listas curadas, géneros, calendarios (Próximamente) e historial de búsqueda; y **revivir** el browse de anime (AniList) con géneros.

**Architecture:** Se extiende `TmdbApi` (curated/genres/discover) y `AniListApi` (genres/genre filter), se enriquece `CineCatalogViewModel`+`CineCatalogScreen` con categoría+género, se revive `AnimeSection` cableándola a `catalog_anime/{anilistId}`, y se añade historial en Room (v10). Todo aguas abajo (detalle, reproducción) sin cambios.

**Tech Stack:** Kotlin, Coroutines, OkHttp+org.json (a mano), Room 2.6.1, Jetpack Compose, JUnit4. Tests suspend con `runBlocking`.

## Global Constraints
- Paquete `com.arkiv.player`. Identidad git `lordmacu`, commits SIN `Co-Authored-By`. `git add` solo archivos de cada task.
- Red: OkHttp+org.json a mano (NO Retrofit/Moshi). TMDB `auth="api_key=$apiKey&language=$language"`, `base="https://api.themoviedb.org/3"`, language `es-MX`.
- Reutilizar sin romper: `TmdbItem`, `TmdbApi.list()/get()/parseItem()`, `AnimeShow`, `AnimeSection`, `AnimeShowDetailScreen`, `CineDetailScreen`, rutas `cine/{type}/{tmdbId}` y `catalog_anime/{anilistId}`.
- Rama `feat/catalogo-categorias`. Correr SOLO los tests de cada task; `TransportRouterTest` (si fallara) es ajeno.
- Cada consulta de red en `runCatching` → lista vacía ante fallo (ningún fallo tumba la UI).
- Spec: `docs/superpowers/specs/2026-07-22-catalogo-categorias-design.md`.

## File Structure
- `data/catalog/TmdbApi.kt` — **Modify**: `TmdbCategory`, `TmdbGenre`, `curated()`, `genres()`, `discover()`.
- `data/catalog/AniListApi.kt` — **Modify**: `genres()`, param `genre` en `browse()`.
- `data/db/Entities.kt` — **Modify**: `SearchHistoryEntity`.
- `data/db/Daos.kt` — **Modify**: `SearchHistoryDao`.
- `data/db/ArkivDatabase.kt` — **Modify**: entity + version 10 + `MIGRATION_9_10` + `abstract fun searchHistoryDao()`.
- `ui/catalog/CineCatalogScreen.kt` — **Modify**: VM (category/genre) + UI (chips categoría, selector género, tipo "anime", historial).
- `ui/ArkivRoot.kt` — **Modify**: cablear `onOpenAnime` a `catalog_anime/{anilistId}` desde el catálogo.
- `ui/catalog/AnimeSection.kt` — **Modify**: selector de género AniList.
- Tests: `TmdbApiUrlTest.kt`, `AniListGenreTest.kt`, `SearchHistoryDaoTest.kt`.

---

### Task 1: `TmdbApi` — categorías curadas, géneros y discover

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/TmdbApiUrlTest.kt`

**Interfaces produced:**
- `enum class TmdbCategory { POPULAR, TRENDING, TOP_RATED, NOW_PLAYING, UPCOMING }`
- `data class TmdbGenre(val id: Int, val name: String)`
- `fun tmdbCategoryPath(type: String, category: TmdbCategory): String` (top-level, pura, testeable — devuelve el path relativo sin auth)
- `suspend fun TmdbApi.curated(type: String, category: TmdbCategory, page: Int): List<TmdbItem>`
- `suspend fun TmdbApi.genres(type: String): List<TmdbGenre>`
- `suspend fun TmdbApi.discover(type: String, genreId: Int, page: Int): List<TmdbItem>`

- [ ] **Step 1: Test que falla (lógica pura de path)**

```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbApiUrlTest {
    @Test fun `paths de categorias por tipo`() {
        assertEquals("/movie/popular", tmdbCategoryPath("movie", TmdbCategory.POPULAR))
        assertEquals("/movie/top_rated", tmdbCategoryPath("movie", TmdbCategory.TOP_RATED))
        assertEquals("/movie/now_playing", tmdbCategoryPath("movie", TmdbCategory.NOW_PLAYING))
        assertEquals("/movie/upcoming", tmdbCategoryPath("movie", TmdbCategory.UPCOMING))
        assertEquals("/tv/on_the_air", tmdbCategoryPath("tv", TmdbCategory.NOW_PLAYING))
        assertEquals("/tv/airing_today", tmdbCategoryPath("tv", TmdbCategory.UPCOMING))
        assertEquals("/trending/movie/week", tmdbCategoryPath("movie", TmdbCategory.TRENDING))
        assertEquals("/trending/tv/week", tmdbCategoryPath("tv", TmdbCategory.TRENDING))
    }
}
```

- [ ] **Step 2: Ejecutar (falla)** — Run: `./gradlew :app:testDebugUnitTest --tests '*TmdbApiUrlTest*'` — Expected: FAIL.

- [ ] **Step 3: Implementar** (añadir a `TmdbApi.kt`; los enums/data class a nivel de archivo, los métodos dentro de la clase reusando `list()`/`get()`/`auth`)

```kotlin
enum class TmdbCategory { POPULAR, TRENDING, TOP_RATED, NOW_PLAYING, UPCOMING }
data class TmdbGenre(val id: Int, val name: String)

/** Path relativo (sin auth) del endpoint de una categoría. Puro/testeable. */
fun tmdbCategoryPath(type: String, category: TmdbCategory): String = when (category) {
    TmdbCategory.TRENDING -> "/trending/$type/week"
    TmdbCategory.POPULAR -> "/$type/popular"
    TmdbCategory.TOP_RATED -> "/$type/top_rated"
    TmdbCategory.NOW_PLAYING -> if (type == "tv") "/tv/on_the_air" else "/movie/now_playing"
    TmdbCategory.UPCOMING -> if (type == "tv") "/tv/airing_today" else "/movie/upcoming"
}
```

Dentro de la clase `TmdbApi` (junto a `browse`):

```kotlin
    /** Lista curada por categoría (populares/tendencias/top rated/en cartelera/próximamente). */
    suspend fun curated(type: String, category: TmdbCategory, page: Int): List<TmdbItem> =
        list("$base${tmdbCategoryPath(type, category)}?$auth&page=${page.coerceAtLeast(1)}", type)

    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<TmdbGenre>>()

    /** Géneros disponibles para el tipo (cacheado en memoria). */
    suspend fun genres(type: String): List<TmdbGenre> {
        genreCache[type]?.let { return it }
        return withContext(Dispatchers.IO) {
            val json = get("$base/genre/$type/list?$auth") ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(json).optJSONArray("genres") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optInt("id", 0); val name = o.optString("name")
                    if (id == 0 || name.isBlank()) null else TmdbGenre(id, name)
                }
            }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) genreCache[type] = it }
        }
    }

    /** Descubrir por género. */
    suspend fun discover(type: String, genreId: Int, page: Int): List<TmdbItem> =
        list("$base/discover/$type?$auth&with_genres=$genreId&sort_by=popularity.desc&page=${page.coerceAtLeast(1)}", type)
```

- [ ] **Step 4: Ejecutar (pasa)** — Run: `./gradlew :app:testDebugUnitTest --tests '*TmdbApiUrlTest*'` — Expected: PASS.
- [ ] **Step 5: Commit** — `git add TmdbApi.kt TmdbApiUrlTest.kt` — msg: `feat(catalogo): TmdbApi curated/genres/discover para categorías`.

---

### Task 2: `AniListApi` — géneros + filtro por género

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AniListApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/AniListGenreTest.kt`

**Interfaces produced:**
- `fun buildAnimeBrowseQuery(sort: String, hasSearch: Boolean, hasGenre: Boolean): String` (top-level pura, testeable — arma el GraphQL; incluye `genre_in:[$genre]` solo si `hasGenre`)
- `suspend fun AniListApi.genres(): List<String>`
- `browse(page, sort, search, genre: String? = null)` (param nuevo con default)

- [ ] **Step 1: Test que falla**

```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListGenreTest {
    @Test fun `query incluye genre_in solo cuando hay genero`() {
        val withGenre = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = true)
        assertTrue(withGenre.contains("genre_in"))
        assertTrue(withGenre.contains("\$genre"))
        val without = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = false)
        assertFalse(without.contains("genre_in"))
    }
}
```

- [ ] **Step 2: Ejecutar (falla)** — Run: `./gradlew :app:testDebugUnitTest --tests '*AniListGenreTest*'` — Expected: FAIL.

- [ ] **Step 3: Implementar** — Extraer la construcción del query a `buildAnimeBrowseQuery(...)` (top-level), añadir `genre_in:[$genre]` al bloque `media(...)` cuando `hasGenre`, declarar `$genre:String` en la firma del query. Modificar `browse` para aceptar `genre: String? = null`, incluir `$genre:String` en las vars y pasar `hasGenre = genre != null` al builder; `if (genre != null) vars.put("genre", genre)`. Añadir:

```kotlin
    /** Lista de géneros de AniList (GenreCollection). */
    suspend fun genres(): List<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", "query{GenreCollection}").toString()
        val json = runCatching {
            client.newCall(Request.Builder().url(ANILIST_URL).post(body.toRequestBody(jsonMedia)).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching {
            val arr = JSONObject(json).optJSONObject("data")?.optJSONArray("GenreCollection") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
```
(Verifica los nombres reales de `ANILIST_URL`/cliente/`jsonMedia`/`toRequestBody` en el archivo y ajústalos; el patrón HTTP debe copiar el de `browse`.)

- [ ] **Step 4: Ejecutar (pasa)** — Run: `./gradlew :app:testDebugUnitTest --tests '*AniListGenreTest*'` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(catalogo): AniListApi genres() + filtro por género en browse`.

---

### Task 3: Historial de búsqueda en Room (v10)

**Files:**
- Modify: `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/ArkivDatabase.kt`
- Test: `app/src/test/java/com/arkiv/player/data/db/SearchHistoryDaoTest.kt`

**Interfaces produced:**
- `@Entity(tableName="search_history") data class SearchHistoryEntity(@PrimaryKey val query: String, val kind: String, val atMs: Long)`
- `SearchHistoryDao { suspend fun upsert(e); suspend fun recent(limit: Int): List<SearchHistoryEntity>; suspend fun clear() }`
- `ArkivDatabase.searchHistoryDao()`, version 10, `MIGRATION_9_10`.

- [ ] **Step 1: Test que falla (DAO con Room in-memory)**

```kotlin
package com.arkiv.player.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryDaoTest {
    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), ArkivDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test fun `upsert deduplica por query y recent ordena desc`() = runBlocking {
        val db = db(); val dao = db.searchHistoryDao()
        dao.upsert(SearchHistoryEntity("naruto", "anime", 100))
        dao.upsert(SearchHistoryEntity("coco", "movies", 200))
        dao.upsert(SearchHistoryEntity("naruto", "anime", 300)) // actualiza atMs
        val recent = dao.recent(10)
        assertEquals(2, recent.size)
        assertEquals("naruto", recent[0].query) // 300 > 200
        dao.clear(); assertEquals(0, dao.recent(10).size)
        db.close()
    }
}
```
(Nota: requiere Robolectric + `androidx.room:room-testing`/`androidx.test:core`. Si el proyecto NO tiene esas deps de test, el implementador las añade a `app/build.gradle.kts` en `testImplementation`, o convierte el test a instrumented; preferir Robolectric si ya se usa en el repo — verificar. Si añadir Robolectric es demasiado, dejar el DAO sin test unitario y validar por compilación, reportándolo.)

- [ ] **Step 2: Ejecutar (falla)** — Run: `./gradlew :app:testDebugUnitTest --tests '*SearchHistoryDaoTest*'` — Expected: FAIL (entidad/DAO no existen).

- [ ] **Step 3: Implementar**
- En `Entities.kt`:
```kotlin
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val kind: String,
    val atMs: Long,
)
```
- En `Daos.kt`:
```kotlin
@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY atMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
```
- En `ArkivDatabase.kt`: añadir `SearchHistoryEntity::class` a `entities`, subir `version = 10`, `abstract fun searchHistoryDao(): SearchHistoryDao`, definir y registrar:
```kotlin
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` " +
                    "(`query` TEXT NOT NULL, `kind` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`query`))",
                )
            }
        }
```
Añadirla a `.addMigrations(...)` en el `databaseBuilder` (junto a las demás MIGRATION_x_y).

- [ ] **Step 4: Ejecutar (pasa)** — Run: `./gradlew :app:testDebugUnitTest --tests '*SearchHistoryDaoTest*'`; y `./gradlew :app:compileDebugKotlin` (KSP genera el DAO). Expected: PASS/BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `feat(catalogo): historial de búsqueda en Room (v10 + MIGRATION_9_10)`.

---

### Task 4: `CineCatalogViewModel` — estado categoría/género

**Files:** Modify `ui/catalog/CineCatalogScreen.kt` (clase `CineCatalogViewModel`).

**Interfaces produced:** `category: StateFlow<TmdbCategory>`, `genreId: StateFlow<Int?>`, `genres: StateFlow<List<TmdbGenre>>`, `setCategory(c)`, `setGenre(id?)`; `load()` con prioridad query > genre > category.

- [ ] **Step 1: Añadir estado y setters** (LEE la clase en fresco). Añadir flows `_category` (default `TmdbCategory.POPULAR`), `_genreId` (null), `_genres`. En `init`/`setType` cargar `api.genres(type)` en `_genres` y resetear `_genreId=null`. Setters `setCategory`/`setGenre` → `reload()`.

- [ ] **Step 2: Reescribir `load()` con prioridad**. Reemplazar la línea del `runCatching { if (q != null) api.search(...) else api.browse(...) }` por:
```kotlin
            val result = runCatching {
                when {
                    q != null -> api.search(_type.value, q, page)
                    _genreId.value != null -> api.discover(_type.value, _genreId.value!!, page)
                    else -> api.curated(_type.value, _category.value, page)
                }
            }.getOrDefault(emptyList())
```
- [ ] **Step 3: Compilar** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(catalogo): categoría y género en CineCatalogViewModel`.

---

### Task 5: `CineCatalogScreen` — UI de categorías + géneros

**Files:** Modify `ui/catalog/CineCatalogScreen.kt` (composable).

- [ ] **Step 1: Añadir fila de chips de categoría** (Tendencias/Populares/Top rated/En cartelera/Próximamente) que llaman `vm.setCategory(...)`, siguiendo el patrón de `FilterChip` ya usado en la pantalla (mira los chips de tipo). Mapea labels→`TmdbCategory`.
- [ ] **Step 2: Añadir selector de género** (fila scrollable de `FilterChip` poblada con `vm.genres`; "Todos" = `setGenre(null)`, cada género = `setGenre(id)`), visible cuando `type != "anime"`.
- [ ] **Step 3: Compilar** — BUILD SUCCESSFUL. (UI sin unit test.)
- [ ] **Step 4: Commit** — `feat(catalogo): chips de categoría y selector de género en CineCatalogScreen`.

---

### Task 6: Revivir Anime — tipo "anime" + navegación

**Files:** Modify `ui/catalog/CineCatalogScreen.kt`, `ui/ArkivRoot.kt`.

- [ ] **Step 1: Callback de anime**. En `CineCatalogScreen`, añadir parámetro `onOpenAnime: (Long) -> Unit`. En `ArkivRoot.kt:236` (donde se instancia `CineCatalogScreen`), pasar `onOpenAnime = { navController.navigate("catalog_anime/$it") }`.
- [ ] **Step 2: Tercer tipo "anime"**. Añadir un chip "Anime" al toggle de tipo. Cuando `type == "anime"`, en vez del grid TMDB renderizar `AnimeSection(onOpenAnime = onOpenAnime, contentPadding = contentPadding)` (import de `AnimeSection`) y `return@Column`/rama equivalente (como hace hoy `CatalogScreen.kt:117-119`). El tipo lo puede manejar un `remember { mutableStateOf }` local en el composable (no necesita ir al VM TMDB, ya que anime usa AniList).
- [ ] **Step 3: Compilar** — BUILD SUCCESSFUL. Verifica que abrir un anime navegue a `catalog_anime/{id}` → `AnimeShowDetailScreen` (ruta ya registrada).
- [ ] **Step 4: Commit** — `feat(catalogo): revivir pestaña Anime (AnimeSection cableada a catalog_anime)`.

---

### Task 7: Géneros de anime en `AnimeSection`

**Files:** Modify `ui/catalog/AnimeSection.kt` (y su ViewModel si tiene uno interno).

- [ ] **Step 1: Cargar géneros** con `graph.aniListApi.genres()` y estado local; selector de género (chips) con "Todos".
- [ ] **Step 2: Pasar género al browse** — al elegir género, pasar `genre` a `AniListApi.browse(page, sort, search, genre)`. Resetear/paginar como con los sorts existentes.
- [ ] **Step 3: Compilar** — BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(catalogo): selector de género de anime (AniList) en AnimeSection`.

---

### Task 8: Historial de búsqueda en la UI

**Files:** Modify `ui/catalog/CineCatalogScreen.kt` (y `AnimeSection.kt` para anime). Usa `graph.database.searchHistoryDao()` (o expón un helper en el grafo/repo).

- [ ] **Step 1: Guardar al buscar** — cuando el usuario ejecuta una búsqueda (TMDB o AniList), `upsert(SearchHistoryEntity(query, kind, System.currentTimeMillis()))` en un `scope.launch`.
- [ ] **Step 2: Mostrar recientes** — cuando el campo de búsqueda está vacío, mostrar `recent(8)` como chips/lista; tocar una = rellenar y ejecutar la búsqueda. Botón "Limpiar" → `clear()`.
- [ ] **Step 3: Compilar** — BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `feat(catalogo): historial de búsquedas recientes en el catálogo`.

---

## Self-Review (autor)
- **Cobertura spec:** §2.1 TmdbApi → T1; §2.4 AniList genres → T2; §2.5 Room historial → T3+T8; §2.2 VM+UI categorías/géneros → T4+T5; §2.3 revivir anime → T6; géneros anime → T7. Calendarios = categoría UPCOMING/now_playing en T1+T5 (sin infra extra). ✓
- **Tipos consistentes:** `TmdbCategory`/`TmdbGenre` (T1) usados en T4/T5; `genres()`/`genre` param (T2) en T7; `SearchHistoryDao` (T3) en T8. ✓
- **Sin placeholders de código en el data-layer** (T1-T3 con código completo). Las tasks de UI (T4-T8) dan objetivo + anclas concretas y patrón de referencia (los `FilterChip` existentes), apropiado para Compose donde el código exacto depende del layout vivo — el implementador lee en fresco.
- **Riesgo señalado:** T3 puede requerir Robolectric/room-testing en `testImplementation`; si es muy invasivo, degradar a compile-only y reportar.
