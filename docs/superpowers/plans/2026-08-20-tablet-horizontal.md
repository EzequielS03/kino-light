# Tablet en horizontal — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** que en una tablet en horizontal la app se sienta como la de TV (hero grande, filas de
pósters, navegación al costado) sin perder ninguna función de la app de Android.

**Architecture:** el layout ancho es un **envoltorio**: cambia el contenedor que ordena las cosas,
nunca el contenido. Cada pantalla sigue llamando a los mismos composables de fila, diálogo y
control que usa el celular. Una función pura decide si estamos en tablet horizontal; todo lo demás
la consulta.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.12.01), Material 3, Navigation Compose, JUnit 4.

**Spec:** [../specs/2026-08-20-tablet-horizontal-design.md](../specs/2026-08-20-tablet-horizontal-design.md)

## Global Constraints

- **Envoltorio, no reescritura.** Prohibido duplicar una pantalla en una versión "ancha". Si una
  pantalla necesita otra distribución, se le da otro contenedor al MISMO contenido.
- **No se toca `ui/tv/` ni `ArkivTvRoot`.** El TV queda exactamente igual.
- **En vertical no cambia nada**, ni en tablet ni en celular. El celular no cambia en ninguna
  orientación.
- Los umbrales y los cálculos (columnas, tamaños) viven en **funciones puras con tests**, no en
  números sueltos dentro de un composable.
- Texto visible en **español de Bogotá (tuteo)**: `tú`, `tienes`, `confírmala`. Nunca voseo.
- Tests: `./gradlew :app:testDebugUnitTest`. Compilación: `./gradlew :app:compileDebugKotlin`.
- Commits sin línea de coautoría. Identidad `lordmacu`.
- Cada tarea termina compilando y con la suite en verde.

---

### Task 1: La regla de "esto es una tablet horizontal"

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/FormatoDePantalla.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/FormatoDePantallaTest.kt`

**Interfaces:**
- Produces:
  - `fun esTabletHorizontal(smallestWidthDp: Int, orientation: Int): Boolean`
  - `@Composable fun esTabletHorizontal(): Boolean` (lee `LocalConfiguration`)
  - `fun columnasDeGrilla(base: Int, esAncho: Boolean): Int`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatoDePantallaTest {

    @Test
    fun `una tablet acostada es tablet horizontal`() {
        assertTrue(esTabletHorizontal(800, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `una tablet parada no lo es`() {
        assertFalse(esTabletHorizontal(800, Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `un celular grande acostado NO es tablet`() {
        // Un Galaxy S24+ acostado mide 1040dp de ANCHO, pero su lado más chico son 480dp.
        // Por eso la regla mira el lado más chico: si mirara el ancho actual, el celular
        // se llevaría el layout de tablet cada vez que el usuario lo gira.
        assertFalse(esTabletHorizontal(480, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `el umbral es 600dp`() {
        assertFalse(esTabletHorizontal(599, Configuration.ORIENTATION_LANDSCAPE))
        assertTrue(esTabletHorizontal(600, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `la grilla duplica columnas en ancho`() {
        assertEquals(6, columnasDeGrilla(base = 3, esAncho = true))
        assertEquals(4, columnasDeGrilla(base = 2, esAncho = true))
    }

    @Test
    fun `la grilla del celular no cambia`() {
        assertEquals(3, columnasDeGrilla(base = 3, esAncho = false))
        assertEquals(2, columnasDeGrilla(base = 2, esAncho = false))
    }
}
```

- [ ] **Step 2: Correr el test y verlo fallar**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatoDePantallaTest*'`
Expected: FAIL — `Unresolved reference 'esTabletHorizontal'`.

- [ ] **Step 3: Escribir la implementación mínima**

```kotlin
package com.arkiv.player.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * ¿Estamos en una tablet en horizontal? Es lo único que decide si se usa el layout ancho.
 *
 * Mira el lado MÁS CHICO del aparato ([smallestWidthDp]) y no el ancho actual, porque el ancho
 * actual mentiría: un Galaxy S24+ acostado mide 1040dp de ancho y se llevaría el layout de tablet,
 * que es justo lo que no se quiere. El lado más chico es invariante a la rotación: 480dp en ese
 * celular (nunca califica) y ~800dp en una tablet de 10" (siempre califica).
 *
 * 600dp es el umbral estándar de Android para "tablet" (`sw600dp`).
 */
fun esTabletHorizontal(smallestWidthDp: Int, orientation: Int): Boolean =
    smallestWidthDp >= UMBRAL_TABLET_DP && orientation == Configuration.ORIENTATION_LANDSCAPE

const val UMBRAL_TABLET_DP = 600

/** La misma pregunta, leyendo la configuración actual. Recompone solo al rotar. */
@Composable
fun esTabletHorizontal(): Boolean {
    val config = LocalConfiguration.current
    return esTabletHorizontal(config.smallestScreenWidthDp, config.orientation)
}

/**
 * Columnas de una grilla. En ancho caben el doble: con 3 columnas en 1280dp cada tarjeta quedaría
 * de 400dp, más grande que la pantalla de un celular.
 */
fun columnasDeGrilla(base: Int, esAncho: Boolean): Int = if (esAncho) base * 2 else base
```

- [ ] **Step 4: Correr el test y verlo pasar**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatoDePantallaTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/FormatoDePantalla.kt app/src/test/java/com/arkiv/player/ui/FormatoDePantallaTest.kt
git commit -m "feat(tablet): la regla que decide el layout ancho"
```

---

### Task 2: Rail lateral en vez de barra de abajo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (`TABS` en :101, `Scaffold` en :189, `NavigationBar` en :278)

**Interfaces:**
- Consumes: `esTabletHorizontal()` (Task 1)

El `Scaffold` de hoy pone la `NavigationBar` en `bottomBar` cuando la ruta actual es una pestaña
(`isTab`). En tablet horizontal hay que envolver el `Scaffold` en una `Row` con una
`NavigationRail` a la izquierda, y NO pasar `bottomBar` en ese caso.

**Lo que no puede cambiar:** los seis destinos, el cálculo de `selected`
(`backStackEntry?.destination?.hierarchy?.any { it.route == tab.route }`), el `catalogResetSignal`
al entrar a `catalog`, y las opciones de `navigate` (`popUpTo` + `launchSingleTop` + `restoreState`).
Para no duplicarlo, extraer el `onClick` a una sola función local `irA(tab: Tab)` y usarla desde los
dos contenedores.

La barra del mini-player remoto (`MiniPlayerBar`, :262) **se queda abajo**: es estado de
reproducción, no navegación.

- [ ] **Step 1: Extraer la acción de navegación**

En `ArkivRoot`, antes del `Scaffold`:

```kotlin
    // Una sola definición de "ir a una pestaña", para que el rail y la barra no puedan
    // divergir en el comportamiento (reset del catálogo, popUpTo, restoreState).
    fun irA(tab: Tab) {
        if (tab.route == "catalog") graph.catalogResetSignal.tryEmit(Unit)
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
```

y reemplazar el cuerpo del `onClick` de `NavigationBarItem` por `{ irA(tab) }`.

- [ ] **Step 2: Compilar y comprobar que el celular sigue igual**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compila. El comportamiento en celular no cambió (mismo código, extraído).

- [ ] **Step 3: Añadir el rail**

```kotlin
    val ancho = esTabletHorizontal()

    Row(Modifier.fillMaxSize()) {
        if (ancho && isTab) {
            NavigationRail(containerColor = ArkivBlack) {
                TABS.forEach { tab ->
                    val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = { irA(tab) },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        }
        Scaffold(
            // …el Scaffold de hoy, tal cual, con una sola diferencia:
            // la NavigationBar de bottomBar va envuelta en `if (isTab && !ancho)`.
        )
    }
```

- [ ] **Step 4: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y la suite pasa.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(tablet): rail lateral en vez de barra de abajo"
```

---

### Task 3: El home, con el aire de la TV

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt` (`Hero` en :359, `PosterCard` en :539, `LazyRow`s en :260, :296, :324, :522)

**Interfaces:**
- Consumes: `esTabletHorizontal()` (Task 1)

El home ya tiene la estructura de la TV (hero + filas de pósters). Lo único que cambia son los
números, y van juntos en un solo lugar para que no queden dp sueltos:

```kotlin
/** Medidas del home según la forma de la pantalla. Ver [esTabletHorizontal]. */
private data class MedidasDelHome(val altoDelHero: Dp, val anchoDePoster: Dp)

@Composable
private fun medidasDelHome(): MedidasDelHome =
    if (esTabletHorizontal()) MedidasDelHome(altoDelHero = 420.dp, anchoDePoster = 180.dp)
    else MedidasDelHome(altoDelHero = 220.dp, anchoDePoster = 120.dp)
```

- [ ] **Step 1: Aplicar las medidas al hero**

En `Hero` (:359), reemplazar `.height(220.dp)` por `.height(medidas.altoDelHero)`, pasando
`medidas` como parámetro desde el llamador.

- [ ] **Step 2: Aplicar las medidas a las tarjetas**

En `PosterCard` (:539) reemplazar los dos `Modifier.width(120.dp)` por `Modifier.width(ancho)`,
con `ancho: Dp` como parámetro nuevo (default `120.dp` para no tocar otros llamadores).

- [ ] **Step 3: Usar el backdrop apaisado en el hero cuando es ancho**

El póster 2:3 estirado a 1280dp se ve mal. Si el `TitleCard` del hero trae backdrop, usarlo cuando
`esTabletHorizontal()`; si no trae, seguir con el póster (nunca dejar el hero vacío).

- [ ] **Step 4: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y pasa.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt
git commit -m "feat(tablet): el home respira en horizontal"
```

---

### Task 4: El detalle, en dos paneles

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt` (`DetailContent`)

**Interfaces:**
- Consumes: `esTabletHorizontal()` (Task 1)

Hoy `DetailContent` es UN `LazyColumn` donde los primeros items son la imagen y el bloque de
título, y después vienen los capítulos. En ancho: `Row { ficha (peso 1) | lista (peso 1.4) }`.

⚠️ **La trampa que hay que respetar:** `resumeIndex` (:402) calcula el índice del capítulo en el que
vas contando "2 items de cabecera, +1 si hay chips de filtro, +1 por cada sección con nombre". Si la
ficha se va al panel izquierdo, esos 2 items **ya no están en la lista** y el auto-scroll saltaría al
lugar equivocado. El offset tiene que salir de la misma condición que decide el layout — no de un
número copiado.

**Lo que no puede cambiar:** la fila de capítulo sigue siendo la misma `EpisodeRow`, con su
`ControlDeDescarga`, su `BarraDeDescarga` y su `LineaDeEstadoDeDescarga`. El `DialogoDeDescarga`
sigue colgando de `DetailContent`.

- [ ] **Step 1: Extraer los items de cabecera**

Sacar la imagen y el bloque de título a un composable `FichaDelItem(data)` que hoy se llama desde
los primeros `item {}` del `LazyColumn` y mañana también desde el panel izquierdo. Mismo contenido.

- [ ] **Step 2: Hacer que el offset dependa de la condición, no de un número**

```kotlin
    // Cuántos items van ANTES de los capítulos en el LazyColumn. En dos paneles la ficha no está
    // en la lista, así que no cuenta: sin esto el auto-scroll al capítulo en curso cae 2 items
    // más abajo de donde debe.
    val itemsDeCabecera = if (dosPaneles) 0 else 2
    val base = itemsDeCabecera + if (siteLabels.size >= 2) 1 else 0
```

y usar `base` donde hoy dice `if (siteLabels.size >= 2) 3 else 2`.

- [ ] **Step 3: Armar los dos paneles**

```kotlin
    if (dosPaneles) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { FichaDelItem(data) }
            Box(Modifier.weight(1.4f)) { listaDeCapitulos() }
        }
    } else {
        listaDeCapitulos()   // con la ficha adentro, como hoy
    }
```

- [ ] **Step 4: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y pasa.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt
git commit -m "feat(tablet): el detalle muestra ficha y capítulos a la vez"
```

---

### Task 5: Las grillas, con el doble de columnas

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt:129`
- Modify: `app/src/main/java/com/arkiv/player/ui/home/CategoriasScreen.kt:91`
- Modify: `app/src/main/java/com/arkiv/player/ui/home/RowBrowseScreen.kt:71`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt:354`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CatalogScreen.kt:155`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeSection.kt:239`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt:724`
- Modify: `app/src/main/java/com/arkiv/player/ui/live/LiveScreen.kt:408` y `:430`

**Interfaces:**
- Consumes: `columnasDeGrilla(base, esAncho)` y `esTabletHorizontal()` (Task 1)

Es la misma edición mecánica en nueve lugares: `GridCells.Fixed(3)` pasa a
`GridCells.Fixed(columnasDeGrilla(3, esTabletHorizontal()))`, y lo mismo con los `Fixed(2)`.

- [ ] **Step 1: Aplicar el cambio en los nueve sitios**
- [ ] **Step 2: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y pasa.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(tablet): las grillas usan el ancho que hay"
```

---

### Task 6: El buscador de fuentes, en columna a la derecha

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt:614` (el `ModalBottomSheet`)

**Interfaces:**
- Consumes: `esTabletHorizontal()` (Task 1)

En tablet horizontal la hoja que sube desde abajo tapa media pantalla en un aparato donde sobra
ancho. El MISMO contenido (`Column` de :616 con su cabecera y sus `SourceSection`) va en un panel a
la derecha.

**Lo que no puede cambiar:** las filas siguen siendo `SourceRow` con su `ControlDeDescarga`, así que
la cola, el progreso, cancelar y borrar siguen ahí. El `DialogoDeDescarga` sigue donde está.

- [ ] **Step 1: Extraer el contenido de la hoja**

Sacar el `Column` de :616 a un composable `PanelDeFuentes(...)` sin cambiar nada de adentro. Sus
parámetros son exactamente lo que ese `Column` usa hoy del scope de la pantalla: `detail`,
`sheetEpisode`, las listas por sección (`torrents`, `webs`, `archives`), sus banderas de carga,
`expandedSections`/`toggle`, `preparing`, `descargaDe` y `playSource`, más `onCerrar`. No se agrega
ni se quita ninguno.

- [ ] **Step 2: Elegir contenedor según la forma**

```kotlin
    if (sheetOpen) {
        if (esTabletHorizontal()) {
            // Panel lateral: mismo contenido, sin tapar la ficha.
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Surface(Modifier.width(420.dp).fillMaxHeight(), color = ArkivSurfaceHigh) {
                    PanelDeFuentes(...)
                }
            }
        } else {
            ModalBottomSheet(...) { PanelDeFuentes(...) }
        }
    }
```

- [ ] **Step 3: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y pasa.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat(tablet): las fuentes van al costado, no tapando la ficha"
```

---

### Task 7: Ancho máximo donde hay texto

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt` (la raíz con las
  pestañas; sus hijas —`AppTab`, `ReproduccionTab`, `SubtitulosTab`, `AccountSection`,
  `MisAparatos`— heredan el tope y no se tocan)
- Modify: `app/src/main/java/com/arkiv/player/ui/downloads/DownloadsScreen.kt`

Un renglón de 1280dp es ilegible. En tablet horizontal, el contenido de estas pantallas va con
`Modifier.widthIn(max = 720.dp)` centrado. No cambia nada más.

- [ ] **Step 1: Aplicar el tope de ancho en las dos pantallas**

En cada raíz, envolver el contenido:

```kotlin
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = if (esTabletHorizontal()) 720.dp else Dp.Unspecified)) {
            // …el contenido de hoy, sin tocar
        }
    }
```
- [ ] **Step 2: Verificar**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: compila y pasa.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(tablet): los textos largos no cruzan la pantalla entera"
```

---

### Task 8: Verificar en una tablet de verdad (emulada)

**Files:** ninguno (verificación).

Esta tarea es la que decide si el plan cumplió. **No vale leer el código:** hay que ejecutar.

- [ ] **Step 1: Crear el AVD de tablet**

```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd -n arkiv_tablet -k "system-images;android-34;google_apis;arm64-v8a" -d "pixel_tablet"
```

- [ ] **Step 2: Arrancarlo e instalar**

```bash
~/Library/Android/sdk/emulator/emulator -avd arkiv_tablet -no-snapshot -netdelay none -netspeed full &
./gradlew :app:assembleDebug
adb -s <emulador> install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Recorrer los criterios de aceptación del spec, con capturas**

Uno por uno, contra el spec:
1. Horizontal: rail lateral + hero grande + filas grandes.
2. Vertical: idéntico a hoy.
3. Detalle: ficha y capítulos a la vez.
4. **Descargar un capítulo desde la biblioteca**: encolar, ver el progreso en la fila, cancelarlo,
   reintentarlo, borrarlo.
5. **Lo mismo desde el buscador de fuentes.**
6. Grillas con más columnas.

- [ ] **Step 4: Verificar que el celular no cambió**

Instalar el mismo APK en el celular de Cristian y comprobar que se ve como antes en las dos
orientaciones.

- [ ] **Step 5: Escribir el informe**

Qué se probó, qué se vio, qué quedó sin probar. Si algo no calza con el spec, se dice; no se
maquilla.
