# Datos del capítulo en el héroe del home

Fecha: 2026-08-11

## El problema

En el home del TV, pararse encima de una tarjeta de "Continuar viendo" cambia el héroe, pero lo que
se lee ahí abajo es la **sinopsis de la serie** — el mismo texto para todos los capítulos de la
misma serie. No dice en qué capítulo estás parado, ni cuál es, ni cuánto te falta. Para saberlo hay
que entrar al detalle.

En el home del celu el héroe es fijo en lo último visto (en un teléfono no hay "pararse encima") y
su subtítulo es el nombre del capítulo a secas: sin número de temporada ni de capítulo, y sin
cuánto queda.

## Qué se muestra

Una línea de datos del capítulo:

```
T1 · E5  ·  La conspiración  ·  te faltan 12 min
```

**Cada tramo se omite si no se sabe, en vez de inventarlo.** Es la misma regla que ya gobierna la
numeración en `EtiquetaDeCapitulo`, y acá importa más todavía porque el héroe es lo primero que se
lee:

| Tramo | Cuándo aparece |
|---|---|
| Número | Siempre en una serie. Si faltan `season`/`episode`, se deduce del `orderIndex` (en packs de torrent codifica temporada×1000+capítulo; en archive.org es un correlativo). |
| Nombre | Solo si TMDB lo cruzó (`episode_still`). Sin nombre, la línea queda en número y tiempo. |
| Tiempo restante | Solo si se conoce la duración. En Magis la sonda de duración tarda y `durationMs` puede llegar en 0: ahí se omite, en vez de mostrar un "te faltan 0 min" mentiroso. |

### Las películas

"Continuar viendo" también trae películas a medias, y una película no tiene temporada ni capítulo.
Con la regla cruda quedaría un "E1" absurdo debajo del título. En una película la línea se queda
**solo con el tiempo restante**, que ahí sigue siendo justo el dato que se quiere.

Para distinguirla hace falta saber cuántos episodios tiene el ítem, dato que la consulta de
"Continuar viendo" hoy no trae. Se resuelve con el mismo criterio que ya usa la biblioteca
(`ItemDao.observeLibrary`): contar los episodios vivos del ítem en la propia consulta.

## Dónde se pinta

**TV** (`ui/tv/TvHomeScreen.kt`). El `Featured` del héroe (título, subtítulo, imagen) gana un campo
para esta línea, y el héroe la dibuja **entre el título y la sinopsis**. Ese orden es a propósito:
la línea del capítulo es el dato que cambia al moverte entre tarjetas, y la sinopsis es de la serie
y no cambia. La sinopsis se conserva tal cual.

Las filas de descubrimiento del home muestran títulos de TMDB, no capítulos: pasan el campo vacío y
se ven igual que hoy.

**Celu** (`ui/home/HomeScreen.kt`). El héroe de lo último visto ya usa como subtítulo la etiqueta
del capítulo (`episodeTitle ?: displayName`), así que la línea nueva **reemplaza** esa expresión —
no hace falta campo nuevo ni una segunda línea. Cuando no hay nada empezado, el héroe cae a la
tendencia #1 de TMDB, que no es un capítulo y no lleva línea.

## Arquitectura

### El dato que falta

`ContinueRow` no trae numeración. Se suman a la proyección de `PlaybackDao.observeContinueWatching`
cuatro columnas, todas sobre tablas que ya existen:

- `e.season`, `e.episode`, `e.orderIndex` — la numeración y su respaldo.
- El conteo de episodios vivos del ítem, para distinguir la película (mismo patrón que
  `ItemDao.observeLibrary`).

Sin migración de Room: es una proyección, no un `@Entity`. El sync no se entera.

### La regla, compartida

`EtiquetaDeCapitulo.numero(ep: Episode)` recibe el modelo `Episode`, y el héroe tiene una
`ContinueRow`. Para no reescribir la numeración se extrae el núcleo a una versión que recibe los
tres valores sueltos (`season`, `episode`, `orderIndex`), y `numero(ep)` pasa a delegar en ella.

Esto es lo que ese objeto existe para garantizar — su propio KDoc dice que las dos pantallas de
detalle *tienen* que decir lo mismo. Acá se suma una tercera superficie sin duplicar la regla: si
mañana cambia cómo se numera un capítulo, cambia en un solo lugar y las tres se enteran.

Encima va una función nueva que arma la línea completa a partir de los datos de una fila de
"Continuar viendo", aplicando las omisiones de la tabla de arriba. Vive en `EtiquetaDeCapitulo`
junto al resto, y es pura: se testea sin Room ni Compose.

## Verificación

Tests JVM puros de la función que arma la línea (este proyecto no tiene Robolectric):

- serie con todo resuelto → `T1 · E5  ·  La conspiración  ·  te faltan 12 min`
- sin nombre de TMDB → `T1 · E5  ·  te faltan 12 min`
- sin duración conocida (Magis con la sonda pendiente) → `T1 · E5  ·  La conspiración`
- sin `season` pero con `episode` → arranca en `E5`, sin temporada inventada
- sin `season` ni `episode`, con `orderIndex` de pack de torrent → `T2 · E3`
- sin `season` ni `episode`, con `orderIndex` correlativo de archive.org → `E1` para el índice 0
- película (un solo episodio) → solo `te faltan 12 min`, sin número
- película sin duración conocida → línea vacía (el héroe no dibuja nada)
- capítulo recién empezado y capítulo casi terminado → el tiempo restante es el que queda, no el transcurrido

Fuera del alcance de los tests quedan la consulta de Room y el dibujado; eso se verifica en el
aparato: pararse encima de varias tarjetas de "Continuar viendo" en el TV y ver que la línea cambia
con cada una, y abrir el home del celu y ver la línea en el héroe.
