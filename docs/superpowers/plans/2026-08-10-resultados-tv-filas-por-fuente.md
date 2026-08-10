# Resultados del TV como filas por fuente — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la pantalla de resultados del TV muestre todas las fuentes a la vez, con una fila horizontal por fuente en vez de una única lista vertical de 500+ ítems.

**Architecture:** Una función pura decide qué filas se ven y en qué orden. Las tarjetas y la fila viven en un archivo nuevo (`TvSourceRows.kt`) porque `TvSearchScreen.kt` ya tiene 1611 líneas. Magis usa el `TvPosterCard` que ya existe; las fuentes sin imagen usan un mosaico de texto nuevo.

**Tech Stack:** Kotlin, Compose para TV (`androidx.tv.material3`), JUnit 4.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-10-resultados-tv-filas-por-fuente-design.md`.
- Solo el TV: `ui/tv/`. La pantalla del celular (`ui/search/`, `ui/catalog/PlaySources.kt`) NO se toca.
- **Nunca `git add -A`.** Commitear con rutas explícitas.
- Identidad de git: `lordmacu` / `10134930+lordmacu@users.noreply.github.com`. Sin coautoría.
- Comentarios y nombres de test en español.
- `./gradlew :app:testDebugUnitTest` para tests, `:app:compileDebugKotlin` para compilar.
- Fire Stick: `192.168.1.22:5555` (AFTKM). Instalar con `~/Library/Android/sdk/platform-tools/adb -s 192.168.1.22:5555 install -r ...`.

---

### Task T1: `filasVisibles`, la función que decide qué filas hay

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SourceTab.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt` (crear si no existe)

**Interfaces:**
- Consumes: `SourceTab`, `tabOf`, `PlaySource` (ya existen).
- Produces: `fun filasVisibles(sources: List<PlaySource>, tab: SourceTab): List<Pair<SourceTab, List<PlaySource>>>`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt`:

```kotlin
package com.arkiv.player.ui.search

import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué filas se dibujan en los resultados del TV, en qué orden y cuáles se saltean. */
class SourceTabTest {

    private fun torrent(nombre: String) = PlaySource.Torrent(
        TorrentResult(name = nombre, seeders = 1, sizeBytes = 0, lang = TorrentLang.LATINO),
    )

    private fun magis(titulo: String) = PlaySource.Magis(
        GatewayResult(source = "magis", title = titulo, ref = "r-$titulo"),
    )

    @Test fun las_filas_van_en_el_orden_del_enum() {
        val r = filasVisibles(listOf(torrent("t"), magis("m")), SourceTab.TODO)
        assertEquals(listOf(SourceTab.MAGIS, SourceTab.TORRENT), r.map { it.first })
    }

    @Test fun una_fuente_sin_resultados_no_deja_fila() {
        val r = filasVisibles(listOf(magis("m")), SourceTab.TODO)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
    }

    @Test fun sin_resultados_no_hay_ninguna_fila() {
        assertTrue(filasVisibles(emptyList(), SourceTab.TODO).isEmpty())
    }

    @Test fun con_un_filtro_puesto_queda_una_sola_fila() {
        val r = filasVisibles(listOf(torrent("t"), magis("m")), SourceTab.TORRENT)
        assertEquals(listOf(SourceTab.TORRENT), r.map { it.first })
        assertEquals(1, r.first().second.size)
    }

    @Test fun un_filtro_sobre_una_fuente_vacia_no_deja_filas() {
        assertTrue(filasVisibles(listOf(magis("m")), SourceTab.TORRENT).isEmpty())
    }

    @Test fun cada_fila_conserva_el_orden_de_llegada_de_su_fuente() {
        val fuentes = listOf(torrent("a"), magis("m"), torrent("b"))
        val fila = filasVisibles(fuentes, SourceTab.TODO).first { it.first == SourceTab.TORRENT }
        assertEquals(listOf("a", "b"), fila.second.map { (it as PlaySource.Torrent).result.name })
    }
}
```

Si alguna firma de `TorrentResult`, `GatewayResult` o `ArchiveSearchResult` no coincide, ajustar los
helpers `torrent()`/`magis()` a los parámetros reales — el resto del test no cambia.

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.SourceTabTest"
```

Esperado: FALLA al compilar, `Unresolved reference 'filasVisibles'`.

- [ ] **Step 3: Implementar**

Al final de `SourceTab.kt`:

```kotlin
/**
 * Las fuentes a dibujar como filas en los resultados del TV: en el orden del enum, sin las vacías
 * y respetando el filtro elegido.
 *
 * Vive acá y no en la pantalla porque es la única parte de "cómo se ve" que se puede probar sin
 * Compose, y es justo la que decide si una fuente se pierde de vista.
 */
fun filasVisibles(sources: List<PlaySource>, tab: SourceTab): List<Pair<SourceTab, List<PlaySource>>> {
    val porFuente = sources.groupBy { tabOf(it) }
    return SourceTab.entries
        .filter { it != SourceTab.TODO && (tab == SourceTab.TODO || it == tab) }
        .mapNotNull { fuente -> porFuente[fuente]?.takeIf { it.isNotEmpty() }?.let { fuente to it } }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.SourceTabTest"
```

Esperado: PASA, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SourceTab.kt app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt
git commit -m "feat(tv): funcion que decide que filas de fuente se ven y en que orden"
```

---

### Task T2: Las tarjetas y la fila, en su propio archivo

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvSourceRows.kt`

**Interfaces:**
- Consumes: `PlaySource`, `SourceTab`, `TvPosterCard`, `MetaChip`, `PackDetector`, `QualityLabel`, `langColor`.
- Produces:
  - `TvSourceCard(source, enabled, modifier, onFocus, onClick)` — mosaico 320×180 para fuentes sin imagen
  - `LazyListScope.tvFilaDeFuente(fuente, items, enabled, loading, primeraTarjeta, onFocusPrimera, onPlay)` — etiqueta + `LazyRow`

- [ ] **Step 1: Crear el archivo**

`TvSourceCard` porta el CONTENIDO del `TvSourceRow` actual (`TvSearchScreen.kt:1085`) — tag,
título de 2-3 líneas, la línea de metadatos por tipo de fuente y el badge PACK — pero en un
mosaico de ancho fijo en vez de una fila a todo el ancho. Copiar el `when (source)` tal cual está
hoy: ya cubre las cinco variantes de `PlaySource` y sus colores.

Estructura:

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSourceCard(source: PlaySource, enabled: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    // Surface clickable de 320x180, mismo foco/borde que TvSourceRow (containerColor
    // ArkivSurfaceHigh, focusedContainerColor ArkivRed, borde blanco de 2 dp).
    // Dentro, Column: badge de fuente arriba, título (maxLines = 3), metadatos abajo.
}
```

Y la fila:

```kotlin
/**
 * Una fila etiquetada de una fuente. Magis va con carátula porque es la única que trae imagen;
 * el resto va con mosaico de texto, que es donde está su información (seeds, tamaño, calidad).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun LazyListScope.tvFilaDeFuente(
    fuente: SourceTab,
    items: List<PlaySource>,
    enabled: Boolean,
    loading: Boolean,
    primeraTarjeta: FocusRequester?,
    onPlay: (PlaySource) -> Unit,
) {
    item(key = "fila-${fuente.name}") { /* etiqueta: "${fuente.label}  ${items.size}" + spinner si loading */ }
    item(key = "row-${fuente.name}") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { sourceKey(it) }) { s ->
                val esPrimera = s === items.first()
                val mod = if (esPrimera && primeraTarjeta != null) Modifier.focusRequester(primeraTarjeta) else Modifier
                if (fuente == SourceTab.MAGIS) {
                    TvPosterCard(
                        title = (s as PlaySource.Magis).result.title,
                        posterUrl = s.result.extra["poster"],
                        cardHeight = 200.dp,
                        modifier = mod,
                    ) { onPlay(s) }
                } else {
                    TvSourceCard(s, enabled = enabled, modifier = mod) { onPlay(s) }
                }
            }
        }
    }
}
```

`contentPadding = 48.dp` es el mismo margen lateral que usan las filas del Home
(`TvHomeScreen.kt:331`), para que las dos pantallas queden alineadas.

- [ ] **Step 2: Verificar que compila**

```bash
./gradlew :app:compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Todavía nadie lo usa; esto solo confirma que las APIs de TV Material 3
y las firmas de `TvPosterCard` están bien.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvSourceRows.kt
git commit -m "feat(tv): tarjeta y fila horizontal por fuente para los resultados"
```

---

### Task T3: Cablearlo en la pantalla

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt` — `loadingOf` (~919), el bloque de RESULTS (~975-1060), `TvSourceTabRow` (~763), el header (~960), y borrar `TvSourceRow` (~1084-1183)

**Interfaces:**
- Consumes: `filasVisibles` (T1), `tvFilaDeFuente` (T2).
- Produces: nada.

- [ ] **Step 1: El spinner de Magis que falta**

En `loadingOf`, agregar la línea que no está:

```kotlin
        SourceTab.MAGIS to loadingMagis,
```

Si `loadingMagis` no está recolectado en esta pantalla, agregarlo al lado de los otros
`collectAsStateWithLifecycle()`.

- [ ] **Step 2: Los chips scrollean**

En `TvSourceTabRow`, la fila pasa a:

```kotlin
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
```

Con el import `androidx.compose.foundation.horizontalScroll` y `androidx.compose.foundation.rememberScrollState`.

- [ ] **Step 3: RESULTS pasa a filas**

Reemplazar el bloque que hoy hace `items(shown, ...) { TvSourceRow(...) }` por:

```kotlin
            val filas = filasVisibles(ordered, tab)
            if (filas.isEmpty() && !anyLoading) {
                item(key = "vacio") { /* el mensaje de "no se encontraron fuentes" de hoy, sin cambios */ }
            }
            filas.forEach { (fuente, items) ->
                tvFilaDeFuente(
                    fuente = fuente,
                    items = items,
                    enabled = !preparing,
                    loading = loadingOf[fuente] == true,
                    // El foco inicial va a la primera tarjeta de la PRIMERA fila, no de cada una.
                    primeraTarjeta = if (fuente == filas.first().first) firstFocus else null,
                    onPlay = { onSelect(it) },
                )
            }
```

Usar el mismo callback de selección que hoy recibe `TvSourceRow` en su `onClick`.

`shown` y `filterByTab` dejan de usarse en esta pantalla: borrar la línea `val shown = ...` si
queda huérfana (el compilador avisa con un warning de variable sin usar).

- [ ] **Step 4: El header se achica**

El bloque del encabezado de RESULTS pasa de póster grande + varias líneas a una fila:
póster de 90 dp de alto, y al lado título (`titleLarge`, 1 línea con elipsis) y debajo una línea
de metadatos (año · temporada · cantidad de fuentes). Objetivo concreto: que el encabezado no pase
de **140 dp** de alto, para que entren dos filas y media en pantalla.

- [ ] **Step 5: Borrar `TvSourceRow`**

Ya no lo usa nadie. Borrar el composable entero (`private fun TvSourceRow`, ~100 líneas) y los
imports que queden huérfanos (el compilador los marca como warning, no como error: revisarlos a
mano).

- [ ] **Step 6: Compilar y correr los tests**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Esperado: BUILD SUCCESSFUL.

- [ ] **Step 7: Probar en el Fire Stick**

```bash
~/Library/Android/sdk/platform-tools/adb connect 192.168.1.22:5555
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.22:5555 install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.22:5555 shell monkey -p com.arkiv.player -c android.intent.category.LAUNCHER 1
```

Recorrido, buscando algo con muchos torrents (p. ej. "batman"):

1. En RESULTS se ven **al menos dos filas** en pantalla (Magis y Torrent), sin scrollear.
2. La fila de Magis muestra carátulas; la de Torrent, mosaicos de texto con seeds y tamaño.
3. Derecha/izquierda recorre una fuente; abajo/arriba salta entre fuentes.
4. El foco arranca en la primera tarjeta de la primera fila.
5. El chip de Magis muestra su spinner mientras busca.
6. Elegir un chip deja una sola fila; "Todo" las trae de vuelta.
7. Reproducir desde una tarjeta sigue funcionando igual que antes.

Capturar con `adb -s 192.168.1.22:5555 exec-out screencap -p > tv.png` y mirarla.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt
git commit -m "feat(tv): los resultados se ven como una fila por fuente"
```

---

## Fuera de alcance

- Con una sola fuente elegida sigue siendo una fila horizontal larga, no una grilla.
- La fase QUERY del buscador del TV y su historial propio.
- El choque de `kind = "tv"` entre `TvSearchScreen` y `CineCatalogScreen`.
