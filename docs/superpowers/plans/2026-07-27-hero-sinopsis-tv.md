# Sinopsis en el hero del home de TV — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el subtítulo del hero del home de TV muestre la sinopsis del título en 2 líneas en vez de texto que repite el título que ya está arriba.

**Architecture:** Dos funciones puras nuevas en `ui/Format.kt` (`plainSynopsis` para normalizar el texto, `heroFallback` para el respaldo sin duplicar) más el cableado de la sinopsis desde 4 fuentes de datos que ya la tienen o casi. Ninguna llamada de red nueva: TMDB ya manda `overview` en la respuesta de la lista y `items.description` ya existe en la DB. Los 5 sitios de `TvHomeScreen` que construyen `Featured` se consolidan en dos helpers locales.

**Tech Stack:** Kotlin, Jetpack Compose (androidx.tv.material3), Room 2.6.1 + KSP, JUnit 4.

## Global Constraints

- **Sin migración de Room.** `items.description` ya existe como columna (`Entities.kt:11`). Solo se agrega a dos SELECT y a sus data classes de proyección. Si algún paso parece pedir un cambio de esquema, está mal implementado.
- **Sin red nueva.** No buscar sinopsis en TMDB al vuelo para los ítems que no la tienen guardada: sería una petición por cada cambio de foco y un matching por título (frágil). Esos ítems caen al fallback.
- **Truncado por `maxLines`, nunca por caracteres.** El `Text` usa `maxLines = 2` + `TextOverflow.Ellipsis`. Nada de cortar strings a N caracteres.
- **Idioma del código:** comentarios y KDoc en español, como el resto del repo. Nombres de funciones en inglés.
- **Commits:** identidad `lordmacu` (ya configurada en el repo). **Nunca `git add -A`** — este working tree se comparte con otras sesiones; agregar solo los archivos que toca la tarea. Sin pie de coautoría.
- **Comando de test:** `./gradlew :app:testDebugUnitTest --tests "<FQN>"`

---

### Task 1: `plainSynopsis` — normalizar la sinopsis

Las sinopsis llegan sucias de dos fuentes: AniList devuelve HTML (`<br>`, `<i>`, `<b>`) y las descripciones de archive.org traen markup y saltos de línea crudos. Esta función las deja listas para pintar.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/Format.kt` (agregar al final)
- Create: `app/src/test/java/com/arkiv/player/ui/FormatTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `fun plainSynopsis(raw: String?): String` en el paquete `com.arkiv.player.ui`. Devuelve `""` cuando no queda texto, para que el call site encadene con `.ifBlank { ... }`.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/ui/FormatTest.kt`:

```kotlin
package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainSynopsisTest {
    @Test fun `quita tags html de anilist`() {
        assertEquals(
            "Un profesor de química. Nota: spoiler",
            plainSynopsis("Un profesor de química.<br><br><i>Nota:</i> spoiler"),
        )
    }

    @Test fun `decodifica entidades basicas`() {
        assertEquals(
            "Tom & Jerry \"el mejor\" y algo más",
            plainSynopsis("Tom &amp; Jerry &quot;el mejor&quot;&nbsp;y algo más"),
        )
    }

    @Test fun `no decodifica dos veces una entidad escapada`() {
        // "&amp;lt;" es un "&lt;" literal escrito a propósito: debe quedar "&lt;", no "<".
        assertEquals("&lt;b&gt;", plainSynopsis("&amp;lt;b&amp;gt;"))
    }

    @Test fun `colapsa saltos de linea y espacios multiples`() {
        assertEquals("Una línea y otra", plainSynopsis("Una línea\n\n   y    otra"))
    }

    @Test fun `solo markup queda vacio`() {
        assertEquals("", plainSynopsis("<p></p><br>"))
    }

    @Test fun `null y blanco quedan vacios`() {
        assertEquals("", plainSynopsis(null))
        assertEquals("", plainSynopsis("   "))
    }

    @Test fun `texto ya limpio es idempotente`() {
        val limpio = "Un profesor de química con cáncer terminal."
        assertEquals(limpio, plainSynopsis(limpio))
        assertEquals(limpio, plainSynopsis(plainSynopsis(limpio)))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.PlainSynopsisTest"
```

Esperado: FALLA a nivel de compilación con "Unresolved reference: plainSynopsis".

- [ ] **Step 3: Implementar**

Agregar al final de `app/src/main/java/com/arkiv/player/ui/Format.kt`:

```kotlin
private val HtmlTag = Regex("<[^>]*>")
private val Whitespace = Regex("\\s+")

/**
 * Sinopsis lista para pintar. AniList devuelve HTML (`<br>`, `<i>`) y las descripciones de
 * archive.org llegan con markup y saltos crudos. Devuelve "" si no queda texto, para que el
 * call site caiga a su respaldo con `ifBlank`.
 */
fun plainSynopsis(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // Los tags se quitan ANTES de decodificar entidades: al revés, un "&lt;b&gt;" literal se
    // volvería "<b>" y el strip se comería texto que el autor escribió a propósito.
    // Por espacio y no por "": un "<br>" entre palabras tiene que dejar separador.
    return raw.replace(HtmlTag, " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // "&amp;" va al final: si fuera primero, "&amp;lt;" se decodificaría dos veces y
        // terminaría en "<" en vez del "&lt;" literal que el autor escribió.
        .replace("&amp;", "&")
        .replace(Whitespace, " ")
        .trim()
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.PlainSynopsisTest"
```

Esperado: PASA, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/Format.kt app/src/test/java/com/arkiv/player/ui/FormatTest.kt
git commit -m "feat(tv): plainSynopsis — normaliza sinopsis con HTML y entidades"
```

---

### Task 2: `heroFallback` — respaldo sin duplicar el título

Cuando un ítem de "Continuar viendo" no tiene sinopsis, hoy el subtítulo muestra `displayName`, que es exactamente donde está el bug: para series es `"Show · S01E03 · Nombre"` (el título adentro) y para películas es el título limpio (duplicación exacta).

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/Format.kt` (agregar al final)
- Modify: `app/src/test/java/com/arkiv/player/ui/FormatTest.kt` (agregar una clase de test)

**Interfaces:**
- Consumes: nada.
- Produces: `fun heroFallback(itemTitle: String, displayName: String): String` en `com.arkiv.player.ui`. Devuelve `""` cuando lo que quedaría es el título repetido.

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de `app/src/test/java/com/arkiv/player/ui/FormatTest.kt` (nueva clase, mismo archivo):

```kotlin
class HeroFallbackTest {
    @Test fun `serie con nombre de capitulo pierde el titulo`() {
        assertEquals(
            "S01E03 · Glorious Purpose",
            heroFallback("Loki", "Loki · S01E03 · Glorious Purpose"),
        )
    }

    @Test fun `serie sin nombre de capitulo`() {
        assertEquals("S01E03", heroFallback("Loki", "Loki · S01E03"))
    }

    @Test fun `pelicula con el titulo repetido queda vacia`() {
        assertEquals("", heroFallback("Dune", "Dune"))
    }

    @Test fun `la comparacion ignora mayusculas`() {
        assertEquals("", heroFallback("Dune", "dune"))
        assertEquals("S01E03", heroFallback("Loki", "loki · S01E03"))
    }

    @Test fun `si el titulo cambio despues el displayName queda intacto`() {
        // La etiqueta se formateó con otro showTitle: no hay prefijo que quitar.
        assertEquals("Loki · S01E03", heroFallback("Loki 2021", "Loki · S01E03"))
    }

    @Test fun `tolera espacios de borde en el titulo`() {
        assertEquals("S01E03", heroFallback("  Loki  ", "Loki · S01E03"))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.HeroFallbackTest"
```

Esperado: FALLA a nivel de compilación con "Unresolved reference: heroFallback".

- [ ] **Step 3: Implementar**

Agregar al final de `app/src/main/java/com/arkiv/player/ui/Format.kt`:

```kotlin
/**
 * Respaldo del subtítulo del hero en "Continuar viendo" cuando el ítem no tiene sinopsis: la
 * etiqueta del episodio SIN el título de la serie, que ya está arriba en el hero.
 *
 * Devuelve "" cuando lo que quedaría es el título repetido — el caso película, donde
 * `displayName` es directamente el título limpio del archivo.
 */
fun heroFallback(itemTitle: String, displayName: String): String {
    val title = itemTitle.trim()
    val prefix = "$title · "
    // Si la etiqueta se formateó con otro showTitle (el título se editó después), no hay prefijo
    // que quitar y se deja tal cual: mejor de más que comerse texto por una coincidencia parcial.
    val rest = displayName.let {
        if (it.startsWith(prefix, ignoreCase = true)) it.substring(prefix.length) else it
    }.trim()
    // Insensible a mayúsculas: los títulos guardados y los cleanName() de archivos difieren en
    // capitalización seguido, y una duplicación que se escapa por una mayúscula es el bug de acá.
    return if (rest.equals(title, ignoreCase = true)) "" else rest
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.HeroFallbackTest"
```

Esperado: PASA, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/Format.kt app/src/test/java/com/arkiv/player/ui/FormatTest.kt
git commit -m "feat(tv): heroFallback — etiqueta de episodio sin repetir el titulo"
```

---

### Task 3: `overview` de TMDB hasta la `TitleCard`

TMDB ya manda `overview` en la respuesta de las listas (`/discover`, `/popular`, `/trending`…). `TmdbItem` simplemente no lo parsea, y `toTitleCard()` lo hardcodea en `null`. Esto es puro cableado — sin red nueva.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt:24-35` (data class) y `:266-274` (parser)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt:22-31`
- Create: `app/src/test/java/com/arkiv/player/ui/search/CardContextTest.kt`

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces: `TmdbItem.overview: String` (default `""`) y `TitleCard.overview` poblado desde TMDB. `TitleCard.overview` sigue siendo `String?` — no cambia su tipo.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/ui/search/CardContextTest.kt`:

```kotlin
package com.arkiv.player.ui.search

import com.arkiv.player.data.catalog.TmdbItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CardContextTest {
    @Test fun `la card de tmdb conserva el overview`() {
        val item = TmdbItem(
            id = 1396,
            type = "tv",
            title = "Breaking Bad",
            originalTitle = "Breaking Bad",
            posterUrl = "",
            year = "2008",
            backdropUrl = "",
            overview = "Un profesor de química con cáncer terminal.",
        )
        assertEquals("Un profesor de química con cáncer terminal.", item.toTitleCard().overview)
    }

    @Test fun `sin overview la card queda con string vacio`() {
        val item = TmdbItem(
            id = 1,
            type = "movie",
            title = "X",
            originalTitle = "X",
            posterUrl = "",
            year = "2020",
            backdropUrl = "",
        )
        assertEquals("", item.toTitleCard().overview)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.CardContextTest"
```

Esperado: FALLA a nivel de compilación con "No parameter with name 'overview' found" en `TmdbItem`.

- [ ] **Step 3: Agregar el campo a `TmdbItem`**

En `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt`, dentro de `data class TmdbItem`, agregar después de `backdropUrl`:

```kotlin
    /** Sinopsis en español; TMDB la manda en la misma respuesta de la lista. Vacía si falta. */
    val overview: String = "",
```

El default `""` importa: `CineCatalogScreen.kt:183` también construye `TmdbItem` y no debe romperse.

- [ ] **Step 4: Parsear el campo**

En el mismo archivo, en el `return TmdbItem(` de `~:266`, agregar como última propiedad (después de `backdropUrl = ...`):

```kotlin
            overview = o.optString("overview"),
```

- [ ] **Step 5: Pasarlo a la `TitleCard`**

En `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt`, en `TmdbItem.toTitleCard()`, cambiar la línea `overview = null,` por:

```kotlin
    overview = overview,
```

- [ ] **Step 6: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.CardContextTest"
```

Esperado: PASA, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt app/src/main/java/com/arkiv/player/ui/search/CardContext.kt app/src/test/java/com/arkiv/player/ui/search/CardContextTest.kt
git commit -m "feat(catalogo): TmdbItem trae el overview hasta la TitleCard"
```

---

### Task 4: `description` en las queries de biblioteca y continuar viendo

`items.description` ya existe en la DB pero ninguna de las dos queries que alimentan el home la selecciona.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt:11-21` (`ContinueRow`), `:24-42` (`LibraryRow`), `:75-85` (SELECT de `observeLibrary`), `:134-149` (SELECT de `observeContinueWatching`)

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces: `LibraryRow.description: String?` y `ContinueRow.itemDescription: String?`. Task 5 consume ambos.

**Nota sobre verificación:** estas son proyecciones de Room, construidas solo por el generador. No hay test unitario razonable — el chequeo real es KSP, que falla la compilación si una columna del SELECT no matchea un campo de la data class (y al revés). Por eso el paso de verificación es una compilación, y es un chequeo fuerte, no un "compila y ya".

- [ ] **Step 1: Agregar el campo a `ContinueRow`**

En `app/src/main/java/com/arkiv/player/data/db/Daos.kt`, dentro de `data class ContinueRow`, agregar después de `itemThumbnailUrl`:

```kotlin
    /** Sinopsis del ítem (no del episodio); null en los ítems que se agregaron sin metadata. */
    val itemDescription: String?,
```

- [ ] **Step 2: Seleccionarlo en `observeContinueWatching`**

En el `@Query` de `observeContinueWatching`, cambiar la línea:

```sql
               i.thumbnailUrl AS itemThumbnailUrl,
```

por:

```sql
               i.thumbnailUrl AS itemThumbnailUrl, i.description AS itemDescription,
```

- [ ] **Step 3: Agregar el campo a `LibraryRow`**

Dentro de `data class LibraryRow`, agregar después de `thumbnailUrl`:

```kotlin
    /** Sinopsis del ítem; null en los que se agregaron sin metadata (web, magnet suelto). */
    val description: String?,
```

- [ ] **Step 4: Seleccionarlo en `observeLibrary`**

En el `@Query` de `observeLibrary`, cambiar la primera línea del SELECT:

```sql
        SELECT i.identifier, i.title, i.thumbnailUrl,
```

por:

```sql
        SELECT i.identifier, i.title, i.thumbnailUrl, i.description,
```

- [ ] **Step 5: Compilar y verificar que KSP acepta las proyecciones**

```bash
./gradlew :app:compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Si aparece "The columns returned by the query does not have the fields [...]" es que un alias no coincide con el nombre del campo — revisar los pasos 2 y 4.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt
git commit -m "feat(db): biblioteca y continuar viendo traen la description del item"
```

---

### Task 5: Cablear la sinopsis en el hero

Último paso: los sitios que construyen `Featured` pasan la sinopsis con respaldo, y el `Text` del subtítulo pasa a 2 líneas.

Son **5** sitios, no 4: además de los tres `onFocus` y el de descubrimiento, el destacado inicial del `LaunchedEffect` (`:127-134`) también arma un `Featured` — y hoy es el primero que se ve al abrir el home, con la misma duplicación. Se consolidan en dos helpers locales para no repetir la lógica.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` — imports, helpers locales (~`:116`), `LaunchedEffect` inicial (`:127-134`), `Text` del subtítulo (`:276-285`), y los `onFocus`/`onFocusRow` de `:318`, `:343`, `:360`, `:400`

**Interfaces:**
- Consumes: `plainSynopsis(raw: String?): String` y `heroFallback(itemTitle: String, displayName: String): String` (Tasks 1 y 2); `TitleCard.overview` poblado (Task 3); `LibraryRow.description` y `ContinueRow.itemDescription` (Task 4).
- Produces: nada — es la hoja del árbol.

- [ ] **Step 1: Agregar los imports**

En `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`, junto al import existente `com.arkiv.player.ui.libraryMeta`:

```kotlin
import com.arkiv.player.ui.heroFallback
import com.arkiv.player.ui.plainSynopsis
```

Y junto a `com.arkiv.player.data.db.LibraryRow` (`:64`) — `ContinueRow` todavía no está importado en este archivo:

```kotlin
import com.arkiv.player.data.db.ContinueRow
```

- [ ] **Step 2: Agregar los dos helpers locales**

Dentro de `TvHomeScreen`, justo debajo de `fun heroArt(...)` (~`:116`):

```kotlin
    // El subtítulo del hero es la sinopsis del título; cuando el ítem no la tiene guardada
    // (los que se agregaron por web o magnet suelto) cae al dato de siempre. Nunca repite el
    // título, que ya está arriba en grande.
    fun continueFeatured(row: ContinueRow): Featured {
        val thumb = row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) } ?: row.itemThumbnailUrl
        return Featured(
            row.itemTitle,
            plainSynopsis(row.itemDescription).ifBlank { heroFallback(row.itemTitle, row.displayName) },
            heroArt(row.itemId, thumb),
        )
    }

    fun libraryFeatured(row: LibraryRow) = Featured(
        row.title,
        plainSynopsis(row.description)
            .ifBlank { libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount, row.isTorrent) },
        heroArt(row.identifier, row.thumbnailUrl),
    )
```

- [ ] **Step 3: Usar los helpers en el destacado inicial**

Reemplazar el cuerpo del `LaunchedEffect(library, continueWatching, artwork)` (`:127-134`) por:

```kotlin
    LaunchedEffect(library, continueWatching, artwork) {
        if (featured == null) {
            featured = continueWatching.firstOrNull()?.let { continueFeatured(it) }
                ?: library.firstOrNull()?.let { libraryFeatured(it) }
        }
    }
```

- [ ] **Step 4: Usar los helpers en los tres `onFocus` de biblioteca y continuar viendo**

En "Continuar viendo" (`:318`), cambiar:

```kotlin
                                    onFocus = { navSound(); featured = Featured(row.itemTitle, row.displayName, heroArt(row.itemId, thumb)) },
```

por:

```kotlin
                                    onFocus = { navSound(); featured = continueFeatured(row) },
```

En `TvLibrarySection` de Series (`:343`) y de Películas (`:360`), cambiar en ambas:

```kotlin
                            onFocusRow = { navSound(); featured = Featured(it.title, libraryMeta(it.isMovie, it.durationSeconds, it.episodeCount, it.isTorrent), heroArt(it.identifier, it.thumbnailUrl)) },
```

por:

```kotlin
                            onFocusRow = { navSound(); featured = libraryFeatured(it) },
```

- [ ] **Step 5: Cablear las filas de descubrimiento**

En el `onFocus` de `TvLandscapeCard` (`:398-401`), cambiar:

```kotlin
                                            featured = Featured(card.title, discoveryMeta(card), art)
```

por:

```kotlin
                                            featured = Featured(
                                                card.title,
                                                plainSynopsis(card.overview).ifBlank { discoveryMeta(card) },
                                                art,
                                            )
```

- [ ] **Step 6: Dar dos líneas al subtítulo**

En el `Text` del subtítulo (`:276-285`), cambiar `maxLines = 1,` por:

```kotlin
                            maxLines = 2,
```

El resto del `Text` no se toca: `fillMaxWidth(0.55f)` mantiene la columna alineada con el título y `TextOverflow.Ellipsis` corta donde corresponda según el ancho renderizado.

- [ ] **Step 7: Compilar y correr toda la suite**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Esperado: BUILD SUCCESSFUL, sin tests fallando.

- [ ] **Step 8: Verificar en el TV**

Instalar en el Fire TV Stick (ver la memoria `arkiv-adb-firestick` para la IP y el pareo) y comprobar en el home:

1. Al abrir, el destacado inicial muestra sinopsis (o el meta) — **no** el título repetido.
2. Moviéndose por "Continuar viendo" en una serie con sinopsis: título arriba, sinopsis abajo en 2 líneas.
3. Moviéndose por una serie/película de biblioteca agregada desde el catálogo TMDB: sinopsis.
4. Moviéndose por un ítem agregado por web o magnet suelto: cae al meta ("12 episodios"), sin duplicar.
5. Moviéndose por las filas de descubrimiento (TMDB y anime/AniList): sinopsis, sin tags HTML sueltos.
6. El bloque de texto no empuja ni desalinea las filas de abajo.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt
git commit -m "feat(tv): el hero del home muestra la sinopsis en vez de repetir el titulo"
```

---

## Notas de verificación

Los tests unitarios cubren las dos funciones puras (Tasks 1 y 2) y el mapeo de `overview` (Task 3). El resto —queries de Room y cableado de Compose— se verifica compilando (KSP valida las proyecciones) y ejecutando en el TV, que es donde el cambio se ve.

La cobertura de sinopsis es parcial a propósito: los ítems agregados desde el catálogo TMDB la tienen; los de web y magnet suelto guardan `description = null` (`ArkivRepository.kt:507`, `:530`) y caen al respaldo. Eso es esperado, no un bug.
