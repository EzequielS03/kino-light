# Historial único e imágenes de Magis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dejar un solo historial de búsqueda (sobre Room, no SharedPreferences) y hacer que los resultados de Magis traigan sus propias imágenes, hasta el Home.

**Architecture:** Fase A mueve el historial del buscador unificado a la tabla `search_history` que ya existía y agrega una tabla `recent_titles` para los pósters; borra el `SearchHistoryStore` de prefs. Fase B suma dos claves de imagen al `extra` de Magis en el gateway y las aterriza en `items.thumbnailUrl` y `artwork.backdropsJson`, que son de donde el Home ya saca sus imágenes.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.6.1 (migraciones a mano), Coil; del lado del gateway Python 3 + pytest (repo `arkiv-api`, deploy Docker en `blog`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-10-historial-unico-e-imagenes-magis-design.md`.
- Dos repos: `/Users/cristian/archive` (app) y `/Users/cristian/arkiv-api` (gateway). La Fase B toca los dos y la app no ve nada hasta que el gateway esté desplegado.
- **Nunca `git add -A` ni `git commit -am`.** El working tree de `archive` lo comparten varias sesiones: commitear siempre con rutas explícitas, solo los archivos de la tarea.
- Identidad de git obligatoria: `lordmacu` / `10134930+lordmacu@users.noreply.github.com`. Sin pie de coautoría.
- Comentarios y nombres de test en español, como el resto de los dos repos.
- App: `./gradlew :app:testDebugUnitTest` para tests, `:app:compileDebugKotlin` para compilar.
- Gateway: `cd /Users/cristian/arkiv-api && .venv/bin/python -m pytest tests/test_adapter_magis.py`.
- `kind` del buscador unificado: la constante `"buscar"`. NO reusar `"tv"`, que ya está pisado entre `TvSearchScreen` y `CineCatalogScreen`.
- Topes: 10 textos, 12 títulos.

---

# FASE A — un solo historial, sobre Room

### Task A1: Reducir `SearchHistoryPolicy` a lo que SQL no hace

El orden, el tope y el dedupe pasan a ser SQL. Sobrevive la normalización del texto y la regla de identidad de un título, que SQLite no hace sola.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt` (reemplazo casi total)
- Modify: `app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt` (reemplazo casi total)

**Interfaces:**
- Consumes: nada.
- Produces:
  - `data class RecentTitle(kind: String, tmdbId: Int?, anilistId: Long?, title: String, posterUrl: String, year: String)` — se conserva tal cual está hoy, no se toca.
  - `object SearchHistoryPolicy` con `const val MAX_QUERIES = 10`, `const val MAX_TITLES = 12`,
    `fun normalizeQuery(texto: String): String?`,
    `fun titleId(kind: String, tmdbId: Int?, anilistId: Long?, title: String): String`,
    `fun titleId(t: RecentTitle): String`

- [ ] **Step 1: Reescribir el test**

Reemplazar el contenido completo de `app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt` por:

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo único del historial que SQLite no resuelve solo: normalizar el texto buscado y decidir
 * cuándo dos títulos son la misma obra. El orden, el tope y el dedupe los hace la consulta
 * (`ORDER BY atMs DESC LIMIT`) y la PK con REPLACE — ver SearchHistoryRepo.
 */
class SearchHistoryPolicyTest {

    @Test fun el_texto_se_guarda_recortado() {
        assertEquals("dune", SearchHistoryPolicy.normalizeQuery("  dune  "))
    }

    @Test fun un_texto_vacio_o_de_solo_espacios_no_se_guarda() {
        assertNull(SearchHistoryPolicy.normalizeQuery(""))
        assertNull(SearchHistoryPolicy.normalizeQuery("   "))
    }

    @Test fun el_texto_conserva_sus_mayusculas() {
        // El dedupe sin mirar mayúsculas lo hace la consulta con lower(); lo que se GUARDA es
        // lo que el usuario escribió, que es lo que va a ver en el chip.
        assertEquals("One Piece", SearchHistoryPolicy.normalizeQuery("One Piece"))
    }

    @Test fun la_identidad_de_un_titulo_sale_del_id_de_su_fuente() {
        assertEquals("series:tmdb-1399", SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"))
        assertEquals("anime:anilist-21", SearchHistoryPolicy.titleId("anime", null, 21L, "One Piece"))
    }

    @Test fun dos_series_con_el_mismo_nombre_no_son_la_misma_obra() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("series", 1399, null, "The Office"),
            SearchHistoryPolicy.titleId("series", 2316, null, "The Office"),
        )
    }

    @Test fun una_peli_y_un_anime_con_el_mismo_numero_no_se_pisan() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("movie", 21, null, "Peli"),
            SearchHistoryPolicy.titleId("anime", null, 21L, "Anime"),
        )
    }

    @Test fun sin_ningun_id_la_identidad_cae_al_nombre_en_minusculas() {
        assertEquals("movie:n-dune", SearchHistoryPolicy.titleId("movie", null, null, "Dune"))
        assertEquals(
            SearchHistoryPolicy.titleId("movie", null, null, "Dune"),
            SearchHistoryPolicy.titleId("movie", null, null, "  DUNE  "),
        )
    }

    @Test fun la_sobrecarga_de_RecentTitle_da_el_mismo_id() {
        val t = RecentTitle("series", 1399, null, "Game of Thrones", "", "2011")
        assertEquals(SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"), SearchHistoryPolicy.titleId(t))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SearchHistoryPolicyTest"
```

Esperado: FALLA al compilar — `Unresolved reference 'normalizeQuery'` / `'titleId'`.

- [ ] **Step 3: Reescribir la policy**

Reemplazar en `app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt` el `object SearchHistoryPolicy` entero (el `data class RecentTitle` de arriba queda igual) por:

```kotlin
/**
 * Lo único del historial que SQLite no resuelve solo.
 *
 * Antes acá vivían el orden, el tope y el dedupe; ahora los hace la consulta
 * (`ORDER BY atMs DESC LIMIT`) y la PK con REPLACE. Ver [SearchHistoryRepo].
 */
object SearchHistoryPolicy {

    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12

    /** Recorta el texto buscado. Devuelve null si no queda nada que valga la pena guardar. */
    fun normalizeQuery(texto: String): String? = texto.trim().ifEmpty { null }

    /**
     * Id de identidad de un título, que es la PK de `recent_titles`.
     *
     * Por id de la fuente, no por nombre: hay series distintas que se llaman igual, y el mismo
     * número puede ser una peli en TMDB y otra cosa en AniList — por eso el `kind` va adelante.
     * Sin ningún id (no debería pasar, pero una fila vieja puede traerlo) cae al nombre en
     * minúsculas, que es mejor que dar todo por distinto y llenar la lista de repetidos.
     */
    fun titleId(kind: String, tmdbId: Int?, anilistId: Long?, title: String): String = when {
        tmdbId != null -> "$kind:tmdb-$tmdbId"
        anilistId != null -> "$kind:anilist-$anilistId"
        else -> "$kind:n-${title.trim().lowercase()}"
    }

    fun titleId(t: RecentTitle): String = titleId(t.kind, t.tmdbId, t.anilistId, t.title)
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SearchHistoryPolicyTest"
```

Esperado: PASA, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt
git commit -m "refactor(buscar): la policy del historial se queda con lo que SQL no hace"
```

---

### Task A2: Room — tabla `recent_titles` y métodos que le faltan al DAO

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt` (agregar entidad después de `SearchHistoryEntity`, ~línea 120)
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (ampliar `SearchHistoryDao` ~línea 384, agregar `RecentTitleDao` al final)
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt` (entidad en la lista, `version = 18`, `MIGRATION_17_18`, accessor)

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces:
  - `RecentTitleEntity(id, kind, tmdbId, anilistId, title, posterUrl, year, atMs)`
  - `RecentTitleDao` con `upsert`, `observeRecent(limit): Flow<List<RecentTitleEntity>>`, `deleteOne(id)`, `clear()`, `trim(keep)`
  - `SearchHistoryDao` con `observeRecent(kind, limit): Flow<List<SearchHistoryEntity>>`, `deleteOne(kind, query)`, `clearKind(kind)`
  - `ArkivDatabase.recentTitleDao()`

- [ ] **Step 1: La entidad**

En `Entities.kt`, justo después de `SearchHistoryEntity`:

```kotlin
/**
 * Un título abierto desde el buscador, para poder volver a él sin buscarlo de nuevo.
 *
 * La PK es el id derivado que arma [com.arkiv.player.data.SearchHistoryPolicy.titleId]: encierra
 * la regla de identidad en un solo lugar y deja que REPLACE haga el dedupe.
 */
@Entity(tableName = "recent_titles")
data class RecentTitleEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val atMs: Long,
)
```

- [ ] **Step 2: Los DAOs**

En `Daos.kt`, dentro de `SearchHistoryDao` (que ya tiene `upsert`, `recent` y `clear`), agregar:

```kotlin
    @Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(kind: String, limit: Int = 10): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE kind = :kind AND lower(query) = lower(:query)")
    suspend fun deleteOne(kind: String, query: String)

    @Query("DELETE FROM search_history WHERE kind = :kind")
    suspend fun clearKind(kind: String)
```

Y al final del archivo:

```kotlin
@Dao
interface RecentTitleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentTitleEntity)

    @Query("SELECT * FROM recent_titles ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<RecentTitleEntity>>

    @Query("DELETE FROM recent_titles WHERE id = :id")
    suspend fun deleteOne(id: String)

    @Query("DELETE FROM recent_titles")
    suspend fun clear()

    /** Borra lo que pase del tope. Cada fila arrastra una URL de póster: conviene podar. */
    @Query("DELETE FROM recent_titles WHERE id NOT IN (SELECT id FROM recent_titles ORDER BY atMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
```

`Flow`, `Dao`, `Insert`, `OnConflictStrategy` y `Query` ya están importados en el archivo.

- [ ] **Step 3: La base**

En `ArkivDatabase.kt`:

1. Agregar `RecentTitleEntity::class,` a la lista de entidades (al lado de `SearchHistoryEntity::class,`).
2. Cambiar `version = 17` por `version = 18`.
3. Agregar el accessor junto a los otros: `abstract fun recentTitleDao(): RecentTitleDao`
4. Después de `MIGRATION_16_17`, agregar:

```kotlin
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_titles (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "kind TEXT NOT NULL, " +
                        "tmdbId INTEGER, " +
                        "anilistId INTEGER, " +
                        "title TEXT NOT NULL, " +
                        "posterUrl TEXT NOT NULL, " +
                        "year TEXT NOT NULL, " +
                        "atMs INTEGER NOT NULL)",
                )
            }
        }
```

5. Sumarla al `.addMigrations(...)`, al final de la lista: `..., MIGRATION_16_17, MIGRATION_17_18)`

- [ ] **Step 4: Verificar que compila y que Room valida el esquema**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Esperado: BUILD SUCCESSFUL. Si Room se queja de que el esquema de la migración no coincide con la entidad, el mensaje dice exactamente qué columna difiere — corregir el `CREATE TABLE`, no la entidad.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Entities.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt
git commit -m "feat(db): tabla recent_titles y consultas que le faltaban al historial"
```

---

### Task A3: Cambiar el store de prefs por Room

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/SearchHistoryRepo.kt`
- Delete: `app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (la línea de `searchHistory`)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt` (tipo del parámetro, bloque de historial, `search`, `pickTitle`)

**Interfaces:**
- Consumes: `SearchHistoryPolicy.normalizeQuery` / `.titleId` (A1); `SearchHistoryDao`, `RecentTitleDao`, `RecentTitleEntity` (A2); `RecentTitle` (ya existe).
- Produces:
  - `class SearchHistoryRepo(searchHistoryDao, recentTitleDao)` con
    `val queries: Flow<List<String>>`, `val titles: Flow<List<RecentTitle>>`,
    `suspend fun addQuery(q: String)`, `suspend fun addTitle(t: RecentTitle)`,
    `suspend fun removeQuery(q: String)`, `suspend fun removeTitle(t: RecentTitle)`, `suspend fun clear()`
  - `AppGraph.searchHistory: SearchHistoryRepo` (mismo nombre de propiedad, otro tipo)

- [ ] **Step 1: El repo**

Crear `app/src/main/java/com/arkiv/player/data/SearchHistoryRepo.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.RecentTitleDao
import com.arkiv.player.data.db.RecentTitleEntity
import com.arkiv.player.data.db.SearchHistoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Historial del buscador unificado, sobre Room.
 *
 * Reemplaza a un store en SharedPreferences que duplicaba la tabla `search_history`, que ya
 * existía y ya usaban el catálogo, el anime y el buscador del TV.
 *
 * El orden, el tope y el dedupe los hace la consulta; acá solo se normaliza lo que entra
 * ([SearchHistoryPolicy]) y se traduce la entidad al modelo que ve la UI.
 */
class SearchHistoryRepo(
    private val searchHistoryDao: SearchHistoryDao,
    private val recentTitleDao: RecentTitleDao,
) {

    val queries: Flow<List<String>> =
        searchHistoryDao.observeRecent(KIND, SearchHistoryPolicy.MAX_QUERIES)
            .map { filas -> filas.map { it.query } }

    val titles: Flow<List<RecentTitle>> =
        recentTitleDao.observeRecent(SearchHistoryPolicy.MAX_TITLES)
            .map { filas -> filas.map { it.toRecentTitle() } }

    suspend fun addQuery(q: String) {
        val limpio = SearchHistoryPolicy.normalizeQuery(q) ?: return
        // Borrar primero las variantes de mayúsculas: la PK de search_history distingue "Dune"
        // de "dune", así que sin esto quedarían dos chips que son la misma búsqueda.
        searchHistoryDao.deleteOne(KIND, limpio)
        searchHistoryDao.upsert(com.arkiv.player.data.db.SearchHistoryEntity(limpio, KIND, System.currentTimeMillis()))
    }

    suspend fun addTitle(t: RecentTitle) {
        recentTitleDao.upsert(
            RecentTitleEntity(
                id = SearchHistoryPolicy.titleId(t),
                kind = t.kind,
                tmdbId = t.tmdbId,
                anilistId = t.anilistId,
                title = t.title,
                posterUrl = t.posterUrl,
                year = t.year,
                atMs = System.currentTimeMillis(),
            ),
        )
        recentTitleDao.trim(SearchHistoryPolicy.MAX_TITLES)
    }

    suspend fun removeQuery(q: String) = searchHistoryDao.deleteOne(KIND, q)

    suspend fun removeTitle(t: RecentTitle) = recentTitleDao.deleteOne(SearchHistoryPolicy.titleId(t))

    /** Borra el historial entero (las dos listas): es lo que espera un botón que dice "borrar". */
    suspend fun clear() {
        searchHistoryDao.clearKind(KIND)
        recentTitleDao.clear()
    }

    private fun RecentTitleEntity.toRecentTitle() =
        RecentTitle(kind, tmdbId, anilistId, title, posterUrl, year)

    companion object {
        /**
         * Cajón propio del buscador unificado. NO se reusa "tv": TvSearchScreen guarda con ese
         * kind y CineCatalogScreen guarda ahí las búsquedas de series, así que esas dos listas ya
         * se mezclan entre sí. No le sumamos un tercero.
         */
        const val KIND = "buscar"
    }
}
```

- [ ] **Step 2: Borrar el store viejo y cambiar el grafo**

```bash
git rm app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt
```

En `AppGraph.kt`, reemplazar la línea de `searchHistory` por:

```kotlin
    val searchHistory: SearchHistoryRepo by lazy { SearchHistoryRepo(database.searchHistoryDao(), database.recentTitleDao()) }
```

Y cambiar el import `com.arkiv.player.data.SearchHistoryStore` por `com.arkiv.player.data.SearchHistoryRepo`.

- [ ] **Step 3: El ViewModel usa el repo**

En `SearchViewModel.kt`:

1. El parámetro del constructor pasa de `SearchHistoryStore` a `SearchHistoryRepo`:

```kotlin
    private val searchHistory: com.arkiv.player.data.SearchHistoryRepo,
```

2. Reemplazar el bloque `// --- historial del buscador ---` entero por:

```kotlin
    // --- historial del buscador -------------------------------------------
    // Graba el ViewModel, no la pantalla: así da igual quién dispare la búsqueda y hay un solo
    // lugar donde mirar. El TV usa el mismo ViewModel y por eso también graba acá; su pantalla
    // muestra su propio historial (kind "tv"), que es otra lista.
    val recentQueries: StateFlow<List<String>> = searchHistory.queries
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    val recentTitles: StateFlow<List<com.arkiv.player.data.RecentTitle>> = searchHistory.titles
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // Best-effort: si Room falla, la búsqueda sigue. El historial nunca rompe el buscar.
    private fun enHistorial(bloque: suspend () -> Unit) {
        viewModelScope.launch { runCatching { bloque() } }
    }

    fun forgetQuery(q: String) = enHistorial { searchHistory.removeQuery(q) }
    fun forgetTitle(t: com.arkiv.player.data.RecentTitle) = enHistorial { searchHistory.removeTitle(t) }
    fun clearHistory() = enHistorial { searchHistory.clear() }
```

3. Agregar el import `kotlinx.coroutines.flow.stateIn` al bloque de imports.

4. En `search(q)`, la línea `searchHistory.addQuery(q)` pasa a ser:

```kotlin
        enHistorial { searchHistory.addQuery(q) }
```

5. En `pickTitle(card)`, la línea `searchHistory.addTitle(card.toRecent())` pasa a ser:

```kotlin
        enHistorial { searchHistory.addTitle(card.toRecent()) }
```

- [ ] **Step 4: Verificar que compila y que los tests pasan**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Esperado: BUILD SUCCESSFUL. `SearchScreen.kt`, `TvSearchScreen.kt` y `QueryContent` NO cambian: el tipo de `graph.searchHistory` cambió pero el nombre de la propiedad y las firmas del ViewModel son las mismas.

- [ ] **Step 5: Probar en el celular**

Solo después de confirmar con Cristian que no está usando el teléfono (los taps caen en la app que esté al frente). Verificar la app en primer plano dentro del mismo comando que toca:

```bash
~/Library/Android/sdk/platform-tools/adb -s <serial> shell dumpsys window | grep mCurrentFocus
```

Instalar y recorrer:

```bash
./gradlew :app:assembleDebug && ~/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Abrir Buscar → el historial de ayer NO está (era el de prefs, se perdió a propósito).
2. Buscar algo → el chip aparece al volver con la ×.
3. Buscar lo mismo con otras mayúsculas → sigue habiendo un solo chip.
4. Abrir un título → sale en "Seguí buscando" con su póster.
5. Matar la app y abrirla → el historial sigue.
6. "Borrar historial" → las dos listas quedan vacías.
7. Ir al buscador del TV o al catálogo → su historial propio sigue intacto (no se mezcló).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SearchHistoryRepo.kt app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt
git commit -m "refactor(buscar): el historial pasa de prefs a Room, sin duplicar la tabla que ya existia"
```

---

# FASE B — imágenes de Magis

### Task B1: El gateway emite las imágenes

**Files:**
- Modify: `/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/adapter.py` (helper nuevo cerca de `_anio`, ~línea 134; `extra=` del `Result`, ~línea 229)
- Test: `/Users/cristian/arkiv-api/tests/test_adapter_magis.py`

**Interfaces:**
- Consumes: nada de la app.
- Produces: dos claves nuevas en el `extra` de cada `Result` de magis — `poster` (URL del `fileType == "icon"`) y `backdrop` (URL del `fileType == "poster"`). Ausentes si el portal no las trae; nunca cadena vacía.

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_adapter_magis.py`, agregar arriba (junto a `SEARCH_OK`) el fixture y, después de `test_mapea_la_busqueda_desde_searchitemlist`, los tests:

```python
# posterList real: el `size` viene con dos formatos distintos en la MISMA respuesta
# ("100*100" y "262x370"), por eso el criterio es el fileType y no el tamaño.
SEARCH_CON_IMAGENES = {
    "searchItemList": [
        {
            "itemList": [
                {"contentId": "c1", "name": "Duna", "programType": "movie",
                 "posterList": [
                     {"fileUrl": "https://cdn/chico.jpg", "size": "100*100", "fileType": "stage"},
                     {"fileUrl": "https://cdn/vertical.jpg", "size": "262x370", "fileType": "icon"},
                     {"fileUrl": "https://cdn/apaisada.jpg", "size": "1920x1080", "fileType": "poster"},
                 ]},
            ]
        }
    ]
}


async def test_la_busqueda_trae_el_poster_vertical_y_la_apaisada():
    _, _, adapter = _armar(ClienteFalso(search=SEARCH_CON_IMAGENES))
    out = [r async for r in adapter.search(SearchContext(q="duna"))]
    assert out[0].extra["poster"] == "https://cdn/vertical.jpg"
    assert out[0].extra["backdrop"] == "https://cdn/apaisada.jpg"


async def test_un_item_sin_posterlist_no_emite_claves_de_imagen():
    _, _, adapter = _armar()
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert "poster" not in out[0].extra
    assert "backdrop" not in out[0].extra


async def test_las_miniaturas_de_100x100_se_descartan():
    solo_stage = {"searchItemList": [{"itemList": [
        {"contentId": "c9", "name": "Sola", "programType": "movie",
         "posterList": [{"fileUrl": "https://cdn/chico.jpg", "size": "100*100", "fileType": "stage"}]},
    ]}]}
    _, _, adapter = _armar(ClienteFalso(search=solo_stage))
    out = [r async for r in adapter.search(SearchContext(q="sola"))]
    assert "poster" not in out[0].extra
    assert "backdrop" not in out[0].extra
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

```bash
cd /Users/cristian/arkiv-api && .venv/bin/python -m pytest tests/test_adapter_magis.py -k imagen -q
```

Esperado: FALLAN con `KeyError: 'poster'`.

- [ ] **Step 3: Implementar**

En `adapter.py`, después de `_anio` (~línea 134):

```python
# fileType del portal -> clave que ve la app. `stage` (100x100) se descarta: no sirve para
# nada de lo que la app muestra hoy.
_TIPOS_IMAGEN = {"icon": "poster", "poster": "backdrop"}


def _imagenes(item: dict) -> dict[str, str]:
    """URLs de las imagenes del item, por tipo.

    El `fileType` es el unico criterio confiable: los `size` vienen con dos formatos distintos
    en la misma respuesta ("100*100" y "262x370"). Si un tipo falta, su clave NO se emite —
    una cadena vacia obligaria a la app a distinguir "no hay" de "hay y esta vacia".
    """
    out: dict[str, str] = {}
    for p in item.get("posterList") or []:
        clave = _TIPOS_IMAGEN.get(str(p.get("fileType") or ""))
        url = str(p.get("fileUrl") or "")
        if clave and url and clave not in out:
            out[clave] = url
    return out
```

Y en el `Result` de `search` (~línea 229), el `extra`:

```python
                extra={
                    "content_id": str(cid),
                    "program_type": tipo_prog,
                    "episode_count": str(item.get("volumnCount") or item.get("updateCount") or 0),
                    **_imagenes(item),
                },
```

No hace falta subir la versión de caché: `_items` guarda los items CRUDOS, así que el
`posterList` ya está en las entradas cacheadas.

- [ ] **Step 4: Correr toda la suite del adaptador**

```bash
cd /Users/cristian/arkiv-api && .venv/bin/python -m pytest tests/test_adapter_magis.py -q
```

Esperado: todos verdes, incluidos los tres nuevos.

- [ ] **Step 5: Commit y desplegar en `blog`**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/adapters/magis/adapter.py tests/test_adapter_magis.py
git commit -m "feat(magis): la busqueda emite el poster vertical y la imagen apaisada"
git push
```

Desplegar y verificar contra el gateway en vivo:

```bash
ssh blog 'cd ~/arkiv-api && git pull && sudo docker compose up -d --build'
```

```bash
cd /Users/cristian/archive && K=$(grep '^ARKIV_API_KEY=' .env | cut -d= -f2- | tr -d '"'"'"' \r') && curl -s -m 120 -H "X-Arkiv-Key: $K" "https://api.comparadorinternet.co/v1/search?q=avatar&type=movie&sources=magis&budget_ms=60000" | head -3
```

Esperado: los `extra` traen `poster` y `backdrop`. **Si el título ya estaba cacheado la búsqueda
puede volver sin las claves durante hasta 6 h** (`_TTL_BUSQUEDA_S`): probar con un título que no
se haya buscado antes.

---

### Task B2: Las imágenes aterrizan en la base de la app

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`addMagisSource`, ~línea 625)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt` (`magisEpisodeId` ~línea 79, `magisEpisodeIdDe` ~línea 93)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` (`playMagis`, ~línea 307)

**Interfaces:**
- Consumes: `extra["poster"]` y `extra["backdrop"]` del gateway (B1).
- Produces: `addMagisSource(ref, contentId, title, episode, posterUrl, backdropUrl)` — un parámetro más, con default `""`.

- [ ] **Step 1: `addMagisSource` escribe también el artwork**

En `ArkivRepository.kt`, la firma pasa a:

```kotlin
    suspend fun addMagisSource(
        ref: String,
        contentId: String,
        title: String,
        episode: Int = 0,
        posterUrl: String = "",
        backdropUrl: String = "",
    ): String? {
```

Y justo antes del `return ep.id`, después de `itemDao.replaceItem(item, listOf(ep))`:

```kotlin
        // La imagen apaisada del portal va al mismo lugar donde el hero del Home busca la de TMDB.
        // Se escribe SOLO si Magis la trajo: una fila vacía dejaría al ítem sin arte para siempre,
        // porque ensureArtwork saltea todo ítem que ya tenga fila. Sin fila, TMDB la completa.
        if (backdropUrl.isNotBlank()) {
            artworkDao.upsert(
                com.arkiv.player.data.db.ArtworkEntity(
                    itemId = id,
                    tmdbId = null,
                    tmdbType = null,
                    backdropsJson = JSONArray(listOf(backdropUrl)).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
```

`JSONArray` y `artworkDao` ya se usan en este archivo (`ensureArtwork`, ~línea 107).

- [ ] **Step 2: Los tres llamadores pasan las imágenes**

En `SearchPlayback.kt`, `magisEpisodeId`:

```kotlin
    suspend fun magisEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? {
        val contentId = r.extra["content_id"].orEmpty()
        return graph.repository.addMagisSource(
            ref = r.ref, contentId = contentId, title = r.title, episode = r.episode,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
        )
    }
```

En `SearchPlayback.kt`, `magisEpisodeIdDe` — el capítulo hereda las imágenes de SU temporada,
que es de donde vienen (un `GatewayEpisode` no trae imagen propia):

```kotlin
    ): String? = graph.repository.addMagisSource(
        ref = capitulo.ref,
        contentId = temporada.extra["content_id"].orEmpty(),
        title = "${temporada.title} · ${capitulo.title}",
        episode = capitulo.number,
        posterUrl = temporada.extra["poster"].orEmpty(),
        backdropUrl = temporada.extra["backdrop"].orEmpty(),
    )
```

En `CineDetailScreen.kt`, `playMagis`:

```kotlin
            val epId = graph.repository.addMagisSource(
                ref = r.ref,
                contentId = r.extra["content_id"].orEmpty(),
                title = r.title,
                episode = r.episode,
                posterUrl = r.extra["poster"].orEmpty(),
                backdropUrl = r.extra["backdrop"].orEmpty(),
            )
```

- [ ] **Step 3: Verificar que compila y que los tests pasan**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Esperado: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat(magis): guardar el poster y la imagen apaisada del portal al reproducir"
```

---

### Task B3: Miniatura en la fila de Magis

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt` (`SourceRow`, ~línea 153)

**Interfaces:**
- Consumes: `extra["poster"]` (B1), `PlaySource.Magis` (ya existe).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: La miniatura**

En `PlaySources.kt`, dentro de `SourceRow`, entre la barrita de color y el `Icon` de play:

```kotlin
        Box(Modifier.width(3.dp).height(56.dp).background(accent))
        // Solo Magis trae imagen por resultado. Sin póster no se dibuja nada: un hueco gris en
        // cada fila sería peor que la fila de hoy.
        val miniatura = (source as? PlaySource.Magis)?.result?.extra?.get("poster").orEmpty()
        if (miniatura.isNotBlank()) {
            AsyncImage(
                model = miniatura,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 8.dp).size(width = 38.dp, height = 56.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Icon(
```

Los 38×56 dp son el 2:3 que entra en el alto que la fila ya tiene, así que la lista no cambia de
ritmo entre una fuente y otra.

- [ ] **Step 2: Los dos imports que faltan**

`size`, `width`, `height` y `clip` ya están en el archivo. Agregar solo estos dos, en orden
alfabético dentro de su grupo:

```kotlin
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
```

- [ ] **Step 3: Compilar y armar el APK**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Esperado: BUILD SUCCESSFUL.

- [ ] **Step 4: Probar en el celular**

Confirmar primero con Cristian que el teléfono está libre. El `<serial>` sale de
`~/Library/Android/sdk/platform-tools/adb devices` (algo como `192.168.1.21:44785`; el puerto
cambia cada vez que se apaga la depuración inalámbrica). Instalar:

```bash
~/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Recorrido, con un título que NO se haya buscado en las últimas 6 h (por la caché del gateway):

1. Buscar una peli → pestaña Magis → las filas salen con carátula.
2. Las filas de torrent, web y archive quedan igual que antes, sin hueco.
3. Reproducir un resultado de Magis y volver al Home.
4. La tarjeta de "Continuar viendo" muestra el póster de Magis.
5. La imagen grande de arriba es la apaisada de Magis, no una de TMDB.
6. Un título de archive o torrent sigue mostrando arte de TMDB como siempre.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt
git commit -m "feat(magis): miniatura en las filas de resultados de magis"
```

---

## Fuera de alcance

- La imagen `stage` (100×100) del portal.
- Imágenes por capítulo: el capítulo hereda las de su temporada.
- Miniaturas en torrent, web y archive.
- El choque de `kind = "tv"` entre `TvSearchScreen` y `CineCatalogScreen`.
- Unificar el historial del TV y el del catálogo con el del buscador: siguen siendo tres listas.
