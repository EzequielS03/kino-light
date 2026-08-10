# Historial único e imágenes de Magis

Fecha: 2026-08-10
Estado: aprobado, sin implementar

Dos partes independientes, en este orden. La A es limpieza (deja un solo
historial donde hoy hay dos); la B es la que agrega valor. Cada una se puede
soltar por separado.

---

# Parte A — un solo historial de búsqueda, sobre Room

## Problema

El 2026-08-09 se implementó el historial del buscador unificado
(`ui/search/SearchScreen.kt`) sobre un `SearchHistoryStore` en
SharedPreferences. **Ya existía una tabla Room `search_history` con su DAO**
(`data/db/Entities.kt`, `data/db/Daos.kt`), usada por `CineCatalogScreen`,
`AnimeSection` y `TvSearchScreen`. No se vio al explorar. Hoy hay dos
mecanismos que guardan lo mismo y no se hablan.

Se consolida sobre Room, que es donde vive el resto y lo que ya sincroniza.

## Qué se construye

### `search_history` para el buscador unificado

El buscador unificado guarda con `kind = "buscar"`.

**No** se reusa `"tv"`. Hoy `TvSearchScreen` guarda con `kind = "tv"` y
`CineCatalogScreen` guarda las búsquedas de series con `kind = "tv"` también:
esas dos listas ya se están mezclando. Es un bug previo, queda fuera de
alcance, pero no se le suma un tercer inquilino.

### `SearchHistoryDao` — tres métodos nuevos

```kotlin
@Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
fun observeRecent(kind: String, limit: Int = 10): Flow<List<SearchHistoryEntity>>

@Query("DELETE FROM search_history WHERE kind = :kind AND lower(query) = lower(:query)")
suspend fun deleteOne(kind: String, query: String)

@Query("DELETE FROM search_history WHERE kind = :kind")
suspend fun clearKind(kind: String)
```

`observeRecent` es Flow (los existentes son `suspend`): los chips tienen que
actualizarse solos al buscar, sin que la pantalla los vuelva a pedir.

### Tabla nueva `recent_titles` — MIGRATION_17_18

Los pósters de títulos abiertos no tenían dónde vivir. Room va en la v17 con 17
migraciones a mano: una más es rutina acá.

```kotlin
@Entity(tableName = "recent_titles")
data class RecentTitleEntity(
    @PrimaryKey val id: String,   // "<kind>:<tmdbId|anilistId|lower(title)>"
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val atMs: Long,
)
```

La PK es un id derivado, no compuesta: encierra la regla de identidad
(kind + id de la fuente, y el nombre en minúsculas solo cuando no hay ningún
id) en un solo lugar, y deja que `OnConflictStrategy.REPLACE` haga el dedupe.

`RecentTitleDao`: `upsert`, `observeRecent(limit)`, `deleteOne(id)`, `clear()`,
más un `trim` que borra lo que pase del tope:

```kotlin
@Query("DELETE FROM recent_titles WHERE id NOT IN (SELECT id FROM recent_titles ORDER BY atMs DESC LIMIT :keep)")
suspend fun trim(keep: Int)
```

### Qué se borra

- `data/SearchHistoryStore.kt` — entero.
- `AppGraph.searchHistory` — se reemplaza por los dos DAOs.
- Casi todo `SearchHistoryPolicy`: el orden, el tope y el dedupe los hace SQL
  (`ORDER BY atMs DESC LIMIT`, PK + `REPLACE`, `trim`).

**Sobrevive** un objeto puro chico, porque SQLite no lo hace solo:

```kotlin
object SearchHistoryPolicy {
    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12
    /** Recorta; devuelve null si no queda nada que guardar. */
    fun normalizeQuery(texto: String): String?
    /** Id de identidad de un título: kind + id de la fuente, o el nombre en minúsculas si no hay id. */
    fun titleId(kind: String, tmdbId: Int?, anilistId: Long?, title: String): String
}
```

`SearchHistoryPolicyTest` se reduce a los casos de esas dos funciones. Los
tests de orden/tope/dedupe se van con la lógica que probaban: pasan a ser
comportamiento de SQLite, y probar SQLite no es nuestro trabajo.

`RecentTitle` sigue siendo el tipo que ve la UI: el DAO devuelve
`RecentTitleEntity` y un mapper lo pasa a `RecentTitle`. Así `QueryContent` no
se entera de que ahora hay Room detrás, y `SearchScreen.kt` no cambia.

El tope de textos se aplica al LEER (`LIMIT 10`), no al escribir: la tabla
crece sin límite, igual que ya crece con las otras tres pantallas que la usan.
Son filas de tres campos; no vale una tarea de poda. Los títulos sí se podan
(`trim`), porque cada fila arrastra una URL de póster.

### Lo que NO cambia

`SearchViewModel` sigue siendo el único que graba, en `search()` y
`pickTitle()`. La UI de `QueryContent` no cambia: mismos chips, mismos
pósters, mismos callbacks. Solo cambia de dónde salen los datos.

Como los DAOs son `suspend`, el ViewModel graba dentro de `viewModelScope`.

## Errores

| Caso | Qué pasa |
|---|---|
| Falla la escritura en Room | `runCatching`; la búsqueda sigue. El historial nunca rompe el buscar. |
| El historial viejo en prefs | Se pierde. Son diez textos de un día de uso; migrarlos no vale el código. El archivo `arkiv_search_history.xml` queda huérfano en el device (~1 KB) y no se borra: limpiarlo cuesta más líneas de las que ahorra. |

---

# Parte B — imágenes de Magis

## Problema

Los resultados de Magis salen sin imagen: en la lista de fuentes son una fila
de texto, y al reproducirlos el Home los muestra sin carátula. El portal SÍ
tiene las imágenes; el gateway las descarta.

Verificado el 2026-08-10 contra el portal, desde el contenedor `arkiv-api` en
`blog`: cada item de `v3/searchByName` trae un `posterList` con entradas
tipadas por `fileType`.

| `fileType` | tamaño | qué es |
|---|---|---|
| `icon` | 262×370 | póster vertical 2:3 |
| `poster` | 1920×1080 | apaisada, para el hero |
| `stage` | 100×100 | recortes chicos |

Una URL de muestra responde `200 image/jpeg`, ~20 KB, sin auth ni token: es un
CDN público, Coil la baja como cualquier otra.

## Qué se construye

### Gateway (`arkiv-api`, deploy en `blog`)

`adapters/magis/adapter.py` suma dos claves al `extra` de cada `Result`:

- `poster` ← el `fileUrl` del `fileType == "icon"`
- `backdrop` ← el `fileUrl` del `fileType == "poster"`

Selección tolerante: si falta el tipo buscado, la clave no se emite (cadena
vacía nunca; ausente). Nada de adivinar por tamaño — los `size` vienen con dos
formatos distintos en la misma respuesta (`"100*100"` y `"262x370"`), así que
`fileType` es el único criterio confiable.

`stage` se descarta: 100×100 no sirve para nada de lo que hay hoy.

### Transporte — cero cambios

`GatewayResult.extra` es un `Map<String, String>` libre y se parsea genérico
(`GatewayModels.kt`). Sumar claves es retrocompatible: los APK viejos las
ignoran solos. `PlaySource.Magis` envuelve el `GatewayResult` entero, así que
el dato llega hasta la UI sin tocar un mapper.

### App — dónde aterrizan

Los dos huecos del Home ya existen:

| Slot del Home | Campo | Qué le ponemos |
|---|---|---|
| Tarjeta de "Continuar viendo" | `items.thumbnailUrl` | el `poster` vertical |
| Imagen grande de arriba (hero) | `artwork.backdropsJson` | el `backdrop` 16:9 |

`ArkivRepository.addMagisSource` **ya recibe un `posterUrl`** y lo escribe en
`items.thumbnailUrl`; hoy le llega `""`. Cambios:

1. Los tres llamadores le pasan el póster: `SearchPlayback.magisEpisodeId`,
   `SearchPlayback.magisEpisodeIdDe` (usa el de la temporada) y
   `CineDetailScreen.playMagis`.
2. `addMagisSource` gana un parámetro `backdropUrl: String = ""` y, **solo si
   no está vacío**, escribe la fila de `artwork` con `backdropsJson` =
   `[backdropUrl]`.

### "Si es Magis no traemos TMDB" — sale gratis

`ensureArtwork` ya arranca con `if (artworkDao.get(row.identifier) != null) continue`.
Con la fila escrita por Magis, a TMDB no se le pregunta nunca por ese ítem.
**No hay que tocar esa función.**

Por eso la fila de artwork se escribe **solo cuando hay backdrop**: si Magis no
trajo imagen apaisada, escribir una fila vacía dejaría el ítem sin arte para
siempre. Sin fila, TMDB la completa — que es mejor que nada.

### `SourceRow` — miniatura solo en Magis

`ui/catalog/PlaySources.kt:153`. Hoy: barrita de color de 3 dp, ícono de play,
y una columna de texto, todo a 56 dp de alto.

Para `PlaySource.Magis` con `extra["poster"]` no vacío, entre la barrita y el
ícono va un `AsyncImage` de **38×56 dp** (2:3 dentro del alto que ya tiene la
fila), esquinas de 4 dp, `ContentScale.Crop`. Sin póster, la fila queda igual
que hoy — nada de huecos grises.

Las otras tres fuentes no cambian: no tienen imagen por resultado.

## Errores

| Caso | Qué pasa |
|---|---|
| El item del portal no trae `posterList` | No se emiten las claves; la app se comporta como hoy |
| La URL da 404 o el CDN se cae | Coil no pinta nada; la fila y la tarjeta quedan sin imagen, sin romper |
| APK viejo contra gateway nuevo | Ignora las claves nuevas |
| App nueva contra gateway viejo | `extra["poster"]` viene null; fila sin miniatura, `posterUrl = ""` como hoy |

## Tests

**Gateway:** en `tests/test_adapter_magis.py`, con un item de fixture que tenga
`posterList` con los tres `fileType`:
- el `extra` sale con `poster` = la URL del `icon` y `backdrop` = la del 1920×1080
- un item sin `posterList` no emite ninguna de las dos claves
- un item con solo `stage` tampoco

**App:** `addMagisSource` no tiene test unitario hoy (toca Room). La
verificación es en device: buscar algo en Magis, ver la miniatura en la fila,
reproducir, y confirmar que en el Home la tarjeta y el hero salen con arte de
Magis y no de TMDB.

## Fuera de alcance

- La `stage` de 100×100.
- Imágenes por capítulo (`GatewayEpisode`): la temporada alcanza.
- Miniaturas en torrent / web / archive.
- El choque de `kind = "tv"` entre `TvSearchScreen` y `CineCatalogScreen`.
