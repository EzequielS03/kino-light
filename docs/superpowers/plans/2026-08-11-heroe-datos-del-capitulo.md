# Datos del capítulo en el héroe del home — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el héroe del home muestre los datos del capítulo —número, nombre y cuánto falta— en el TV al pararse encima de una tarjeta de "Continuar viendo", y en el celu en el héroe de lo último visto.

**Architecture:** La regla de numeración que ya vive en `EtiquetaDeCapitulo` se extrae a una versión que recibe los valores sueltos, y encima se arma una función pura que compone la línea completa omitiendo cada tramo que no se sepa. La consulta de "Continuar viendo" suma las cuatro columnas que le faltan (`season`, `episode`, `orderIndex` y el conteo de episodios del ítem). Las dos pantallas consumen la misma función.

**Tech Stack:** Kotlin, Room (SQLite), Kotlin Flow, Jetpack Compose (Material3 en el celu, TV Material en el televisor), JUnit4 (tests JVM puros, sin Robolectric).

**Spec:** `docs/superpowers/specs/2026-08-11-heroe-datos-del-capitulo-design.md`

## Global Constraints

- **Sin migración de Room.** No se toca ningún `@Entity` ni la versión de la base. Solo se amplía la proyección de una `@Query` de lectura existente.
- **La regla de numeración no se duplica.** `EtiquetaDeCapitulo` es el único lugar donde se decide cómo se nombra un capítulo; el detalle del TV, el del celu y ahora el héroe tienen que decir exactamente lo mismo.
- **Cada tramo de la línea se omite si no se sabe**, nunca se inventa. Esto vale para el nombre del capítulo (depende de TMDB) y para el tiempo restante (depende de que se conozca la duración: en Magis la sonda tarda y `durationMs` puede llegar en 0).
- **Separador**: dos espacios alrededor del punto medio (`"  ·  "`), igual que `EtiquetaDeCapitulo.conNombre` y `avance`.
- **Tests JVM puros.** Este proyecto no tiene Robolectric: nada que necesite Room, Android o Compose en un test unitario. Por eso la lógica va en un objeto puro.
- **Idioma del código:** nombres y comentarios en español.
- **Commits sin coautoría** y con la identidad `lordmacu`. **Nunca `git add -A`**: este working tree lo comparten varias sesiones. Agregar solo los archivos que nombra cada tarea.
- **Comando de tests:** `./gradlew testDebugUnitTest`

## Estructura de archivos

| Archivo | Responsabilidad | Tarea |
|---|---|---|
| `app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloHeroeTest.kt` (crear) | Tests de la línea del héroe | 1 |
| `app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt` (modificar) | `numero(...)` con valores sueltos + `lineaDeHeroe(...)` | 1 |
| `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (modificar) | 4 columnas nuevas en `ContinueRow` y en su consulta | 2 |
| `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` (modificar) | `Featured.meta` + pintarlo + armar la línea | 3 |
| `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt` (modificar) | La línea como subtítulo del héroe | 4 |

---

### Task 1: La línea del héroe (lógica pura + tests)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloHeroeTest.kt` (crear)

**Interfaces:**
- Consumes: nada nuevo. `EtiquetaDeCapitulo` ya existe, con `numero(ep: Episode)`, `conNombre`, `avance` y `botonReproducir`.
- Produces:
  - `EtiquetaDeCapitulo.numero(season: Int?, episode: Int?, orderIndex: Int): String` — el núcleo de la regla, con valores sueltos.
  - `EtiquetaDeCapitulo.lineaDeHeroe(esPelicula: Boolean, season: Int?, episode: Int?, orderIndex: Int, nombre: String?, positionMs: Long, durationMs: Long): String` — la línea completa; `""` cuando no queda nada que decir.

- [ ] **Step 1: Escribir los tests que fallan**

Crear `app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloHeroeTest.kt`:

```kotlin
package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La línea de datos del capítulo que el héroe del home muestra debajo del título.
 *
 * La regla que gobierna todo esto: cada tramo se OMITE si no se sabe, nunca se inventa. El héroe es
 * lo primero que se lee en la pantalla, así que un dato inventado ahí es peor que un dato ausente.
 */
class EtiquetaDeCapituloHeroeTest {

    private fun linea(
        esPelicula: Boolean = false,
        season: Int? = 1,
        episode: Int? = 5,
        orderIndex: Int = 4,
        nombre: String? = "La conspiración",
        positionMs: Long = 3 * 60_000L,
        durationMs: Long = 15 * 60_000L,
    ) = EtiquetaDeCapitulo.lineaDeHeroe(
        esPelicula = esPelicula,
        season = season,
        episode = episode,
        orderIndex = orderIndex,
        nombre = nombre,
        positionMs = positionMs,
        durationMs = durationMs,
    )

    @Test
    fun `con todo resuelto trae numero, nombre y lo que falta`() {
        assertEquals("T1 · E5  ·  La conspiración  ·  te faltan 12 min", linea())
    }

    @Test
    fun `sin nombre de TMDB quedan el numero y el tiempo`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", linea(nombre = null))
    }

    /** Un nombre en blanco es lo mismo que no tenerlo: no deja un separador colgando. */
    @Test
    fun `un nombre en blanco se trata como ausente`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", linea(nombre = "   "))
    }

    /** Magis con la sonda de duración pendiente: `durationMs` llega en 0. */
    @Test
    fun `sin duracion conocida no se inventa el tiempo`() {
        assertEquals("T1 · E5  ·  La conspiración", linea(durationMs = 0L))
    }

    @Test
    fun `sin temporada pero con capitulo no se inventa la temporada`() {
        assertEquals("E5  ·  La conspiración  ·  te faltan 12 min", linea(season = null))
    }

    /** En packs de torrent el `orderIndex` codifica temporada*1000 + capítulo. */
    @Test
    fun `sin numeracion cae al orderIndex de pack de torrent`() {
        assertEquals(
            "T2 · E3  ·  La conspiración  ·  te faltan 12 min",
            linea(season = null, episode = null, orderIndex = 2003),
        )
    }

    /** En archive.org el `orderIndex` es un correlativo que arranca en 0. */
    @Test
    fun `sin numeracion cae al orderIndex correlativo de archive`() {
        assertEquals(
            "E1  ·  La conspiración  ·  te faltan 12 min",
            linea(season = null, episode = null, orderIndex = 0),
        )
    }

    /** Una película no tiene temporada ni capítulo: un "E1" ahí sería ruido. */
    @Test
    fun `una pelicula solo dice cuanto falta`() {
        assertEquals("te faltan 12 min", linea(esPelicula = true))
    }

    @Test
    fun `una pelicula sin duracion conocida no dice nada`() {
        assertEquals("", linea(esPelicula = true, durationMs = 0L))
    }

    /** Lo que se muestra es lo que QUEDA, no lo transcurrido. */
    @Test
    fun `recien empezado casi todo el capitulo esta por delante`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 24 min",
            linea(positionMs = 60_000L, durationMs = 25 * 60_000L),
        )
    }

    /** A menos de un minuto del final no queda nada útil que decir del tiempo. */
    @Test
    fun `casi terminado ya no muestra el tiempo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            linea(positionMs = 25 * 60_000L - 30_000L, durationMs = 25 * 60_000L),
        )
    }

    // --- El núcleo compartido con el detalle -------------------------------------------------

    @Test
    fun `el numero con valores sueltos sigue la misma regla que el del modelo`() {
        assertEquals("T1 · E5", EtiquetaDeCapitulo.numero(season = 1, episode = 5, orderIndex = 4))
        assertEquals("E5", EtiquetaDeCapitulo.numero(season = null, episode = 5, orderIndex = 4))
        assertEquals("T2 · E3", EtiquetaDeCapitulo.numero(season = null, episode = null, orderIndex = 2003))
        assertEquals("E1", EtiquetaDeCapitulo.numero(season = null, episode = null, orderIndex = 0))
    }
}
```

- [ ] **Step 2: Correr los tests para verificar que fallan**

```bash
./gradlew testDebugUnitTest --tests "com.arkiv.player.ui.EtiquetaDeCapituloHeroeTest"
```

Esperado: falla a nivel de **compilación**, con `Unresolved reference: lineaDeHeroe` (y `None of the following functions can be called with the arguments supplied` para `numero`).

- [ ] **Step 3: Extraer el núcleo de la numeración**

En `app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt`, reemplazar la función `numero(ep: Episode)` (líneas 24-33) por estas dos, conservando el KDoc que ya tiene y moviéndolo a la versión de valores sueltos:

```kotlin
    /**
     * "T1 · E5" / "E5", a partir de los valores sueltos.
     *
     * Se omite el tramo que no se sepa en vez de inventarlo: es preferible "E5" solo antes que un
     * "T1 · E5" que apunte al capítulo equivocado. El orden de preferencia importa: `episode` manda
     * aunque no haya `season` —un capítulo de Magis guardado sin el contexto de la temporada
     * (`MagisEntities.build`, capítulo suelto) queda con `season = null`, aunque los que sí lo tienen
     * (`buildSeason`) ya numeran "T1 · E5"— y recién después se cae al `orderIndex`, que en packs de
     * torrent codifica temporada*1000 + episodio y en archive.org es un correlativo 0..N-1.
     *
     * Recibe los valores sueltos y no un [Episode] porque el héroe del home los tiene así, de una
     * fila de "Continuar viendo" (`ContinueRow`), no como modelo. La regla vive UNA sola vez y las
     * tres superficies —los dos detalles y el héroe— la comparten.
     */
    fun numero(season: Int?, episode: Int?, orderIndex: Int): String = when {
        season != null && episode != null -> "T$season · E$episode"
        episode != null -> "E$episode"
        orderIndex >= 1000 -> "T${orderIndex / 1000} · E${orderIndex % 1000}"
        else -> "E${orderIndex + 1}"
    }

    /** "T1 · E5" / "E5" para un episodio ya cargado como modelo. Ver la versión de valores sueltos. */
    fun numero(ep: Episode): String = numero(ep.season, ep.episode, ep.orderIndex)
```

- [ ] **Step 4: Escribir la línea del héroe**

En el mismo archivo, agregar al final del objeto (después de `botonReproducir`):

```kotlin
    /** Por debajo de esto no queda nada útil que decir del tiempo restante. */
    private const val RESTANTE_MINIMO_MS = 60_000L

    /**
     * La línea de datos del capítulo para el héroe del home: "T1 · E5  ·  La conspiración  ·  te
     * faltan 12 min".
     *
     * **Cada tramo se omite si no se sabe, nunca se inventa.** El héroe es lo primero que se lee en
     * la pantalla, así que un dato inventado ahí es peor que un dato ausente:
     *  - el **nombre** depende de que TMDB haya cruzado ese capítulo (`episode_still`);
     *  - el **tiempo** depende de conocer la duración, y en Magis la sonda tarda: `durationMs` llega
     *    en 0 hasta que resuelve. También se omite a menos de un minuto del final, donde "te falta
     *    1 min" no ayuda a decidir nada.
     *
     * En una **película** no hay número: "Continuar viendo" también trae películas a medias, y
     * numerarlas dejaría un "E1" absurdo debajo del título. Ahí queda solo el tiempo, que es
     * justamente lo que se quiere saber de una película empezada.
     *
     * Devuelve "" cuando no queda ningún tramo (una película sin duración conocida); quien la use
     * decide qué hacer con eso — las dos pantallas simplemente no dibujan la línea.
     */
    fun lineaDeHeroe(
        esPelicula: Boolean,
        season: Int?,
        episode: Int?,
        orderIndex: Int,
        nombre: String?,
        positionMs: Long,
        durationMs: Long,
    ): String {
        val tramos = mutableListOf<String>()
        if (!esPelicula) tramos += numero(season, episode, orderIndex)
        nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { tramos += it }
        val restante = durationMs - positionMs
        if (durationMs > 0 && restante >= RESTANTE_MINIMO_MS) {
            tramos += "te faltan ${restante / 60_000} min"
        }
        return tramos.joinToString("  ·  ")
    }
```

- [ ] **Step 5: Correr los tests para verificar que pasan**

```bash
./gradlew testDebugUnitTest --tests "com.arkiv.player.ui.EtiquetaDeCapituloHeroeTest"
```

Esperado: PASS, 12 tests.

- [ ] **Step 6: Correr la suite completa**

Los detalles del TV y del celu llaman a `numero(ep)`, que cambió de implementación (ahora delega).

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS, sin regresiones.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloHeroeTest.kt
git commit -m "feat(heroe): linea de datos del capitulo, compartida con el detalle"
```

---

### Task 2: Las columnas que le faltan a "Continuar viendo"

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (la `data class ContinueRow`, líneas 11-40, y la `@Query` de `observeContinueWatching`, líneas 214-231)

**Interfaces:**
- Consumes: nada de la Tarea 1.
- Produces: `ContinueRow` gana cuatro campos — `season: Int?`, `episode: Int?`, `orderIndex: Int`, `episodeCount: Int`.

Sin test: es una `@Query` de Room, y este proyecto no testea Room (no hay Robolectric). Room valida el SQL y el mapeo de columnas **en tiempo de compilación** vía KSP, que es lo que verifica el Step 3.

- [ ] **Step 1: Agregar los campos a `ContinueRow`**

En `app/src/main/java/com/arkiv/player/data/db/Daos.kt`, dentro de `data class ContinueRow`, agregar **antes** del campo `framePath` (que va último porque no sale de la query):

```kotlin
    /**
     * Numeración del capítulo, para la línea de datos del héroe del home (ver
     * [com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe]). `season`/`episode` son null cuando el
     * nombre del archivo no declaraba numeración; ahí manda `orderIndex`, que en packs de torrent
     * codifica temporada*1000 + episodio y en archive.org es un correlativo 0..N-1.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val orderIndex: Int = 0,
    /**
     * Cuántos episodios vivos tiene el ítem. Sirve para UNA cosa: distinguir la película (1) de la
     * serie, porque "Continuar viendo" trae las dos y numerar una película dejaría un "E1" absurdo
     * debajo del título del héroe.
     */
    val episodeCount: Int = 0,
```

- [ ] **Step 2: Ampliar la proyección de la consulta**

En el mismo archivo, en la `@Query` de `observeContinueWatching`, reemplazar la línea:

```
               s.stillUrl AS stillUrl, s.title AS episodeTitle
```

por:

```
               s.stillUrl AS stillUrl, s.title AS episodeTitle,
               e.season AS season, e.episode AS episode, e.orderIndex AS orderIndex,
               (SELECT COUNT(*) FROM episodes e2 WHERE e2.itemId = e.itemId AND e2.deleted = 0) AS episodeCount
```

El `FROM`, los `JOIN`, el `WHERE`, el `ORDER BY` y el `LIMIT` no se tocan.

- [ ] **Step 3: Compilar para verificar que Room acepta la consulta**

```bash
./gradlew compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Si Room se queja (`The columns returned by the query does not have the fields...`), revisar que los alias coincidan exactamente con los nombres de los campos de `ContinueRow`.

- [ ] **Step 4: Correr la suite completa**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt
git commit -m "feat(heroe): numeracion y conteo de episodios en continuar viendo"
```

---

### Task 3: La línea en el héroe del TV

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` (la `data class Featured` en la línea 81; `continueFeatured` en las líneas 123-135; `libraryFeatured` en 137-145; el bloque que pinta el héroe en 282-301; y el `featured = Featured(...)` de las filas de descubrimiento, alrededor de la línea 386)

**Interfaces:**
- Consumes: `EtiquetaDeCapitulo.lineaDeHeroe(...)` (Tarea 1); los campos `season`, `episode`, `orderIndex`, `episodeCount` de `ContinueRow` (Tarea 2).
- Produces: nada que consuman tareas posteriores.

Sin test: es cableado de Compose para TV, y este proyecto no tiene tests de instrumentación. La lógica de la línea ya quedó cubierta en la Tarea 1. Se verifica en el aparato (Tarea 5).

- [ ] **Step 1: Agregar el campo a `Featured`**

En `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`, reemplazar la línea 81:

```kotlin
private data class Featured(val title: String, val subtitle: String, val imageUrl: String?)
```

por:

```kotlin
/**
 * Lo que muestra el héroe del fondo. [meta] es la línea de datos del capítulo ("T1 · E5  ·  La
 * conspiración  ·  te faltan 12 min") y solo la llenan las tarjetas de "Continuar viendo": las
 * filas de descubrimiento muestran títulos de TMDB, que no son capítulos.
 */
private data class Featured(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val meta: String = "",
)
```

Los tres call sites que no pasan `meta` (`libraryFeatured` y los dos de descubrimiento) siguen compilando gracias al valor por defecto, y su héroe se ve igual que hoy.

- [ ] **Step 2: Armar la línea en `continueFeatured`**

En la misma pantalla, en `continueFeatured` (líneas 123-135), agregar el cuarto argumento al `Featured` que devuelve. El `return` queda así:

```kotlin
        return Featured(
            row.itemTitle,
            heroSubtitle(row.itemTitle, row.itemDescription, heroFallback(row.itemTitle, row.episodeTitle ?: row.displayName)),
            EleccionDeMiniatura.elegir(row.framePath, heroArt(row.itemId, thumb)),
            // Los datos del capítulo enfocado, que es lo que cambia al moverse entre tarjetas (la
            // sinopsis de arriba es de la SERIE y no cambia). La regla de qué se muestra y qué se
            // omite vive en EtiquetaDeCapitulo, compartida con los dos detalles.
            meta = com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe(
                esPelicula = row.episodeCount <= 1,
                season = row.season,
                episode = row.episode,
                orderIndex = row.orderIndex,
                nombre = row.episodeTitle,
                positionMs = row.positionMs,
                durationMs = row.durationMs,
            ),
        )
```

- [ ] **Step 3: Pintar la línea entre el título y la sinopsis**

En el bloque `featured?.let { f -> ... }` (líneas 282-301), insertar el `Text` de la línea **entre** el `Text` del título (que termina en la línea 291 con su `modifier`) y el `if (f.subtitle.isNotBlank())`:

```kotlin
                    if (f.meta.isNotBlank()) {
                        Text(
                            f.meta,
                            style = MaterialTheme.typography.titleSmall,
                            color = ArkivRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
```

Va antes de la sinopsis a propósito: es el dato que cambia al moverte entre tarjetas. En rojo (`ArkivRed`, el acento de la app, ya importado en el archivo) y en `titleSmall` para que se distinga de la sinopsis sin competir con el título.

- [ ] **Step 4: Compilar y correr la suite**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt
git commit -m "feat(heroe): datos del capitulo en el heroe del home del TV"
```

---

### Task 4: La línea en el héroe del celu

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt` (el bloque del héroe de "continuar viendo", alrededor de las líneas 113-131 — en concreto el argumento `subtitle` del `Hero(...)`, línea 127)

**Interfaces:**
- Consumes: `EtiquetaDeCapitulo.lineaDeHeroe(...)` (Tarea 1) y los campos nuevos de `ContinueRow` (Tarea 2).
- Produces: nada.

Sin test: cableado de Compose. Se verifica en el aparato (Tarea 5).

- [ ] **Step 1: Reemplazar el subtítulo del héroe**

En `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`, dentro del bloque `if (heroContinue != null) { ... Hero(...) }`, reemplazar el argumento:

```kotlin
                    subtitle = heroContinue.episodeTitle ?: heroContinue.displayName,
```

por:

```kotlin
                    // Los datos del capítulo, la MISMA línea que arma el héroe del TV: número,
                    // nombre y cuánto falta, omitiendo lo que no se sepa. Antes acá solo estaba el
                    // nombre del capítulo, sin número ni tiempo. Si no queda ningún tramo (una
                    // película sin duración conocida) se cae al nombre de siempre, para no dejar el
                    // héroe con una línea vacía.
                    subtitle = com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe(
                        esPelicula = heroContinue.episodeCount <= 1,
                        season = heroContinue.season,
                        episode = heroContinue.episode,
                        orderIndex = heroContinue.orderIndex,
                        nombre = heroContinue.episodeTitle,
                        positionMs = heroContinue.positionMs,
                        durationMs = heroContinue.durationMs,
                    ).ifBlank { heroContinue.episodeTitle ?: heroContinue.displayName },
```

El resto del `Hero(...)` no se toca: el héroe del celu ya usaba ese subtítulo para la etiqueta del capítulo, así que la línea nueva lo **reemplaza** y no hace falta ni campo nuevo ni un segundo `Text`.

- [ ] **Step 2: Compilar y correr la suite**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt
git commit -m "feat(heroe): datos del capitulo en el heroe del home del celu"
```

---

### Task 5: Verificación en el aparato

**Files:** ninguno (verificación manual).

Lo que los tests JVM no cubren: la consulta de Room y el dibujado en las dos pantallas.

**Antes de mandar taps por ADB al celular, confirmar con Cristian que no lo esté usando.**

- [ ] **Step 1: Compilar e instalar**

```bash
./gradlew assembleDebug
```

Instalar en el Fire TV Stick por ADB de red (puerto 5555) y en el Samsung S24+ por ADB WiFi. Ver los runbooks del proyecto para cada uno.

- [ ] **Step 2: Probar en el TV**

Abrir el home y moverse entre las tarjetas de "Continuar viendo".

Esperado:
- Debajo del título del héroe aparece la línea del capítulo, y **cambia con cada tarjeta**.
- La sinopsis de la serie sigue ahí, debajo de la línea.
- Al pasar a las filas de descubrimiento (En cartelera, etc.), el héroe se ve como siempre, sin línea.
- Si hay alguna película a medias en "Continuar viendo", su línea dice solo cuánto falta, sin "E1".

- [ ] **Step 3: Probar en el celu**

Abrir el home.

Esperado: el héroe de lo último visto muestra la línea completa donde antes decía solo el nombre del capítulo.

- [ ] **Step 4: Buscar el caso sin datos**

Si hay algún ítem de Magis recién empezado (con la sonda de duración pendiente) o un capítulo que TMDB no cruzó, confirmar que la línea aparece **recortada** —sin el tiempo, o sin el nombre— y nunca con un dato inventado ni con un separador colgando.

- [ ] **Step 5: Reportar el resultado**

Contarle a Cristian qué se probó y qué se vio en cada aparato. Si algo no dio lo esperado, **no** declarar la funcionalidad terminada.
