# Resultados del TV: una fila horizontal por fuente

Fecha: 2026-08-10
Estado: aprobado, sin implementar

## Problema

La fase RESULTS del buscador del TV es **una sola lista vertical plana**
(`TvSearchScreen.kt`, `LazyColumn` + `shown = filterByTab(ordered, tab)`).
Con "Todo" caen 536 torrents, 20 de Magis, los web y los de archive en una
única columna ordenada nada más por "packs primero". Eso significa:

- Los 20 de Magis quedan sepultados entre cientos de torrents y con el control
  remoto no hay forma razonable de llegar.
- Para mirar otra fuente hay que subir hasta los chips y cambiar de pestaña, o
  sea que nunca ves dos fuentes a la vez.
- Es una columna vertical en una pantalla apaisada: se desperdicia el ancho.

Además, **el Home del TV ya usa filas horizontales** ("Continuar viendo",
"Series", con `TvWideCard` y `TvPosterCard`). La pantalla de resultados es la
única que se sale de ese lenguaje.

Bug de arrastre: el `loadingOf` del TV no incluye `SourceTab.MAGIS`, así que ese
chip nunca muestra que está buscando.

## Qué se construye

RESULTS pasa a ser **una fila horizontal por fuente**, en el orden del enum
`SourceTab`: Magis · Torrent · Web · Archive. Derecha/izquierda recorre una
fuente; abajo/arriba salta entre fuentes. Las filas vacías no se dibujan.

Cada fila es un `LazyRow`: 536 torrents no cuestan nada porque solo se componen
las tarjetas visibles.

### Dos formas de tarjeta

Las fuentes no traen lo mismo, así que no pueden verse igual:

- **Magis** → `TvPosterCard`, que ya existe. Carátula 2:3, alto 200 dp. Magis es
  la única fuente con imagen propia (ver el spec de las imágenes de Magis).
- **Torrent / Web / Archive** → `TvSourceCard`, nueva. Mosaico de 320×180 dp con
  el nombre en tres líneas y debajo las pastillas que hoy muestra `TvSourceRow`
  (PACK, idioma, calidad, seeds, tamaño). Sin imagen que mostrar, el dato ES el
  texto; achicarlo a una carátula vacía sería perder información.

Que dos filas tengan tarjetas distintas es lo normal en una tele, y el propio
Home ya mezcla `TvWideCard` con `TvPosterCard`.

### Los chips pasan a filtrar filas

`TvSourceTabRow` deja de elegir "qué lista se ve" y pasa a elegir "qué filas se
ven": `Todo` muestra las cuatro, `Torrent` deja solo esa. Y la fila de chips
**scrollea en horizontal**, porque con cinco chips ya no entran a lo ancho sin
aplastar el último (el mismo bug que tenía el celular, donde "Archive" salía
partido letra por letra).

### El encabezado se achica

Hoy el header de RESULTS ocupa mucho alto para lo que aporta. Pasa a una línea:
póster chico + título + año/temporada al lado. El objetivo es que entren dos
filas y media en pantalla, que es lo que hace que "ver todas las fuentes"
funcione de verdad.

### Foco

El foco inicial va a la primera tarjeta de la primera fila con resultados. Como
hoy, no se lo roba al usuario cuando llegan resultados de otra fuente después
(`focusedOnce`), y se re-enfoca cuando aparece un error de reproducción.

## Arquitectura

`TvSearchScreen.kt` tiene **1611 líneas**. Lo nuevo NO va ahí:

- **Nuevo** `ui/tv/TvSourceRows.kt`: `TvSourceCard` (el mosaico de texto) y
  `TvSourceFila` (etiqueta + `LazyRow`, que elige la forma de tarjeta según la
  fuente). Un archivo con un solo propósito, testeable de a poco.
- `TvSearchScreen.kt` solo cambia el bloque de RESULTS: en vez de `items(shown)`
  emite una `TvSourceFila` por fuente visible.
- `TvSourceRow` (la fila vertical actual) **se borra**: nada más la usa.

Se reusan `TvPosterCard`, `MetaChip` y los colores de acento que ya existen.

## Errores y bordes

| Caso | Qué pasa |
|---|---|
| Una fuente sin resultados | Su fila no se dibuja (ni etiqueta) |
| Todas vacías y ya no carga | El mensaje de "no se encontraron fuentes" de hoy, sin cambios |
| Una fuente todavía cargando | Su etiqueta muestra el spinner que ya existe |
| Un resultado de Magis sin póster | `TvPosterCard` ya cae a un fondo liso con el título |
| Nombre de torrent larguísimo | Tres líneas y elipsis, como hoy |

## Tests

La lógica pura ya está cubierta (`countsByTab`, `filterByTab`, `tabOf` en
`SourceTab.kt`). Lo que cambia es composición de UI, que este proyecto no testea
con Compose UI tests.

Se agrega una función pura testeable en `SourceTab.kt`:

```kotlin
/** Las fuentes a mostrar como filas, en orden y sin las vacías, según el filtro elegido. */
fun filasVisibles(sources: List<PlaySource>, tab: SourceTab): List<Pair<SourceTab, List<PlaySource>>>
```

`SourceTabTest`: el orden es el del enum; las vacías no aparecen; con un filtro
puesto queda una sola fila; con TODO quedan todas las no vacías; una fuente con
resultados nunca se pierde.

Verificación en el Fire Stick (`192.168.1.22:5555`, ver la memoria de ADB):
buscar algo con muchos torrents y confirmar que se ven Magis y Torrent a la vez,
que el D-pad salta entre filas, y que el chip de Magis muestra su spinner.

## Fuera de alcance

- **Con una sola fuente elegida sigue siendo una fila horizontal larga**, no una
  grilla que aproveche la pantalla. Es mejor que hoy, pero no lo ideal; queda
  como paso siguiente para no meter dos rediseños en la misma pasada.
- El buscador del TV (fase QUERY) y su historial propio.
- Las miniaturas y la grilla de dos columnas del celular: ya están hechas y son
  composables distintos.
