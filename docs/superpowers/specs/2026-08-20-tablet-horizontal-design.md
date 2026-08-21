# Tablet en horizontal — diseño

**Fecha:** 2026-08-20
**Estado:** aprobado en conversación, sin implementar

## El problema

La app tiene hoy dos formas: la del celular (`ui/` — home, detalle, buscador, biblioteca…) y la
del TV (`ui/tv/`, ~520 KB de código propio con su propio root de navegación). En una tablet se
muestra la del celular, que está dimensionada para 400 dp de ancho: en una tablet horizontal
(~1280 dp) el hero mide 220 dp de alto, los pósters 120 dp de ancho y la barra de navegación vive
abajo, cruzando media pantalla. Se ve desértico y desaprovechado.

Lo que se quiere es que en tablet horizontal la app **se sienta como la de TV** —hero grande,
filas de pósters, navegación al costado— **sin perder una sola función de la app de Android**.

## La decisión de fondo (y por qué)

Se evaluaron tres caminos:

1. **Reusar las pantallas de TV en la tablet.** Se vería idéntico a la TV, pero las pantallas de
   TV **no tienen descargas** (ni cola, ni progreso, ni cancelar, ni borrar) ni los diálogos que
   sí tiene el celular. Habría que portarlas, y quedarían dos implementaciones que mantener
   sincronizadas para siempre. Es exactamente el mecanismo que produjo el bug del 2026-08-20: la
   descarga existía en la biblioteca y no en el buscador de fuentes, porque eran dos caminos.
2. **Adaptar el código del celular al ancho.** Una sola implementación; las funciones existen por
   construcción.
3. **Mixto** (elegido): el **home** toma el aire de la TV —hero grande y filas de pósters— y el
   **resto** son las pantallas del celular en layout ancho.

Se eligió el 3 porque el home es la pantalla que define la sensación del producto, y resulta que
el home del celular **ya tiene la misma estructura que el de TV** (`HomeScreen.kt` ya trae `Hero`,
`PosterCard` y `LazyRow`): no hay que reescribirlo, hay que agrandarlo.

## Regla de oro: envoltorio, no reescritura

El layout ancho **cambia el contenedor, nunca el contenido**. Cada pantalla sigue llamando a los
mismos composables de fila, diálogo y control que usa el celular; lo único distinto es cómo se
ordenan en pantalla.

Consecuencia directa —y el motivo de que el spec exista—: cuando mañana se agregue algo a la fila
de un capítulo, aparece en las dos formas sin que nadie tenga que acordarse. Es lo contrario de lo
que pasa hoy con la TV.

**Prohibido en esta implementación:** duplicar una pantalla en una versión "ancha". Si una pantalla
necesita otra distribución, se le da otro contenedor al mismo contenido.

## Cómo se decide que es una tablet horizontal

```kotlin
fun esTabletHorizontal(config: Configuration): Boolean =
    config.smallestScreenWidthDp >= 600 &&
        config.orientation == Configuration.ORIENTATION_LANDSCAPE
```

- **`smallestScreenWidthDp`, no `screenWidthDp`.** El ancho actual mentiría: un Galaxy S24+
  acostado mide 1040 dp de ancho y se llevaría el layout de tablet, que es justo lo que no se
  quiere. El ancho más chico del aparato es invariante a la rotación: 480 dp en ese celular
  (nunca califica) y ~800 dp en una tablet de 10" (siempre califica).
- **600 dp** es el umbral estándar de Android para "tablet" (`sw600dp`).
- En **vertical** no cambia nada: se ve la app de celular tal cual, como hoy.
- El TV **no pasa por acá**: sigue eligiendo su propia raíz en `MainActivity` con
  `DeviceType.isTelevision`. Este spec no toca `ui/tv/`.

Es una función pura sobre `Configuration`, así que se prueba con tests de JVM y no hace falta un
aparato para saber si la regla es correcta.

`MainActivity` ya declara `configChanges="orientation|screenSize|…"`, así que rotar no recrea la
Activity; Compose lee la `Configuration` nueva y recompone. No hay que tocar el manifest ni fijar
orientación.

## Qué cambia, pantalla por pantalla

### 1. Shell de navegación

`NavigationBar` (abajo) → `NavigationRail` (izquierda) cuando es tablet horizontal. Mismos seis
destinos (`Inicio`, `Categorías`, `Biblioteca`, `En vivo`, `Magis`, `Ajustes`), misma lógica de
navegación —incluido el `catalogResetSignal` al entrar a Catálogo—, mismo estado seleccionado.

La barra del mini-player remoto se mantiene donde está (abajo, a lo ancho del contenido): es una
barra de estado de reproducción, no de navegación.

### 2. Home — el que se parece a la TV

- **Hero**: de 220 dp de alto a ocupar el alto disponible del viewport (con un tope), usando el
  **backdrop apaisado** en vez del póster vertical. El póster 2:3 estirado a 1280 dp de ancho se ve
  mal; el backdrop es la imagen que TMDB ya trae para esto.
- **Filas**: pósters de ~180 dp (hoy 120 dp), con el mismo `LazyRow` y las mismas tarjetas.
- **Mismos datos y mismos clics**: el `HomeViewModel`, las specs de filas y los destinos no cambian.

### 3. Detalle de un ítem — dos paneles

Izquierda: portada, título, sinopsis y acciones. Derecha: la lista de capítulos, con su propio
scroll.

Es la pantalla donde más importa la regla de oro: cada fila sigue siendo la misma `EpisodeRow`, con
su barra de descarga, su porcentaje, su anillo con X para cancelar y su papelera verde. El diálogo
de confirmación es el mismo `DialogoDeDescarga`.

### 4. Biblioteca y catálogo

Más columnas en la grilla, en función del ancho disponible. Sin cambios de contenido.

### 5. Buscador de fuentes

Hoy las fuentes aparecen en una hoja que sube desde abajo (`ModalBottomSheet`). En tablet
horizontal pasan a una **columna a la derecha**, con los resultados a la izquierda: la hoja tapa
media pantalla en un aparato donde sobra ancho.

Las filas de fuente son las mismas (`SourceRow` con su `ControlDeDescarga`), así que la cola, el
progreso, cancelar y borrar siguen ahí.

### 6. Ajustes, descargas y todo lo que sea texto

Ancho máximo centrado (~720 dp). Un renglón de 1280 dp es ilegible.

### 7. Player

Sin cambios. Ya es pantalla completa y no depende del shell.

## Qué NO cambia

- `ui/tv/` y `ArkivTvRoot`: intactos. El TV sigue igual.
- El comportamiento en celular (vertical u horizontal) y en tablet vertical.
- Los ViewModels, el repositorio, la base y el gateway: nada de esto sabe de layouts.
- El player.
- La cola de descargas: sigue siendo de una a la vez.

## Criterios de aceptación

1. En una tablet horizontal (AVD, `sw >= 600 dp`) el shell muestra rail lateral y el home muestra
   hero grande con filas de pósters grandes.
2. En esa misma tablet, en vertical, la app se ve **exactamente como hoy**.
3. En el celular (vertical y horizontal) no cambia nada.
4. En la tablet horizontal se puede: encolar una descarga, ver su progreso en la fila, cancelarla,
   sacarla de la cola, borrarla y reintentar una fallida — desde la biblioteca **y** desde el
   buscador de fuentes.
5. El detalle en tablet horizontal muestra ficha y capítulos a la vez, sin perder ninguna acción de
   la fila de capítulo.
6. El TV sigue funcionando igual (no se tocó su raíz).
7. `esTabletHorizontal` tiene tests que cubren: celular acostado (no), tablet acostada (sí), tablet
   parada (no).

## Riesgos

- **Regresión en el celular.** Es el riesgo real: se tocan pantallas compartidas. Se mitiga con la
  regla de oro (contenedor nuevo, contenido intacto) y verificando las dos formas antes de dar algo
  por hecho.
- **Pantallas que hoy asumen ancho de celular** con números fijos en dp. Aparecerán a medida que se
  implemente; se resuelven con el mismo criterio (contenedor), no con una copia ancha.
- **El emulador no mide rendimiento real.** El AVD de tablet sirve para layout y para verificar que
  las funciones están; no dice nada de fluidez en un aparato real.

## Verificación

- AVD de tablet (creado para esto) + capturas de pantalla en horizontal y vertical.
- El celular real de Cristian, para confirmar que no cambió nada ahí.
- Tests de JVM para la regla y para cualquier cálculo de columnas/tamaños que se extraiga.

Ver [[arkiv-android-project]].
