# TV: pantalla "Mi biblioteca"

Fecha: 2026-08-10
Estado: aprobado, sin implementar

## Problema

En el TV se guarda contenido y no hay dónde verlo.

El disparador concreto: guardar una temporada de Magis desde el buscador
(`TvSearchScreen.saveMagisSeason`) hace **dos cosas** — mete cada capítulo en la
biblioteca y encola una descarga al almacenamiento del aparato. De esas dos,
la segunda no tiene ninguna pantalla en el TV, y la primera cae en un home
donde no se la encuentra.

Tres fallas distintas, encadenadas:

1. **El home no alcanza como biblioteca.** La zona que scrollea mide
   exactamente dos filas (`TvHomeScreen.kt`, `rowsRegionHeight = rowUnit * 2`) y
   el orden es *Continuar viendo → Series → Películas → ~40 filas de
   descubrimiento*. Con algo en "Continuar viendo", "Películas" ya nace fuera de
   pantalla y hay que bajar a ciegas para encontrarla.
2. **El TV no tiene pantalla de descargas.** El botón "Guardar toda la
   temporada" encola N descargas al disco del Fire Stick sin forma de verlas,
   cancelarlas ni borrarlas. En un aparato con almacenamiento chico eso es un
   camino directo a llenarlo sin darse cuenta.
3. **No existe "lo que ya vi".** `observeContinueWatching` filtra `watched = 0`,
   así que al terminar algo desaparece y no queda rastro visible en ningún lado.

Bug de arrastre (ya arreglado, ver "Fuera de alcance"): antes cada capítulo de
Magis era su propio ítem, y como la categoría se detecta por cantidad de
episodios (`LibraryRow.isMovie`: `episodeCount <= 1`), un capítulo suelto se leía
como **película**. Una temporada guardada entera aparecía como N tarjetas-película
en la tercera fila del home — invisible.

## Qué se construye

Una pantalla dedicada `TvLibraryScreen`, con entrada propia en la barra superior
del home, al lado de "Buscar".

### Entrada y ruta

Ruta nueva `library` en `ArkivTvRoot`, y un `TvNavButton` de "Mi biblioteca"
justo después de "Buscar" en la barra superior de `TvHomeScreen`:

```
ARKIV    [🔍 Buscar]  [▤ Mi biblioteca]  [⚙ Ajustes]  [⟳ Sincronizar]
```

Del home **se van** las filas `lib_series` y `lib_movies`. El home queda: hero +
Continuar viendo + descubrimiento. Toda la biblioteca vive en la pantalla nueva.

Cuidado al hacerlo: hoy el foco inicial del home cae en la primera tarjeta de
biblioteca cuando no hay "Continuar viendo" (`firstPosterId` en
`TvHomeScreen.kt`). Ese código lleva un comentario largo sobre un bug de
foco+scroll ya pagado — el `requestFocus` con reintento y el `scrollToItem(0)`
previo existen por una razón. Al quitar las filas, `firstPosterId` deja de tener
sentido y el foco inicial debe caer en "Continuar viendo" y, si está vacío, en la
primera fila de descubrimiento, **conservando** el patrón de reintento.

### Forma de la pantalla

Dos zonas de foco: menú lateral fijo (~220 dp) y contenido.

```
┌────────────┬────────────────────────────────────────┐
│  ARKIV     │  SERIES · 8 títulos                    │
│            │                                        │
│  Todo      │   ▢▢   ▢▢   ▢▢   ▢▢   ▢▢   ▢▢       │
│▸ Series    │   ▢▢   ▢▢   ▢▢   ▢▢   ▢▢   ▢▢       │
│  Películas │                                        │
│  Ya visto  │   ▢▢   ▢▢                             │
│  Descargas │                                        │
└────────────┴────────────────────────────────────────┘
```

El filtro *Todas / Series / Películas* del teléfono (`LibraryScreen`, chips
`LibFilter`) **es** el menú lateral acá. No se duplica como chips: con el control
remoto, subir hasta una fila de chips y volver a bajar cada vez que cambiás de
sección es un viaje largo, y dos controles para lo mismo compiten por el foco.

- **Todo / Series / Películas** → `LazyVerticalGrid` de 6 columnas con
  `TvPosterCard` (ya existe). Las series salen agrupadas, igual que hoy en el
  home: una serie guardada desde dos fuentes es una sola tarjeta.
- **Ya visto** → la misma grilla, con los títulos terminados, el último primero.
- **Descargas** → lista vertical, no grilla: hace falta ancho para el progreso y
  el estado. Arriba, el espacio libre del disco.

**D-pad.** Foco inicial en el menú, sección "Todo". `→` entra a la grilla, `←`
desde la primera columna vuelve al menú — para eso ya existe
`Modifier.dpadFocusEscape()` en `TvComponents.kt`. `Atrás` vuelve al home desde
cualquier sección.

Mantener pulsada una tarjeta abre el diálogo que ya existe (`TvCategoryDialog`:
ver detalle, cambiar categoría) más una entrada nueva **"Quitar de mi
biblioteca"**, que hoy en el TV no existe y es justo lo que falta para deshacer
un guardado por error.

## Datos

### Lo guardado

Reusa `HomeViewModel` tal cual (`library`, `libraryGroups`, `artwork`). Sin
ViewModel nuevo para esta parte.

Todo sale de `libraryGroups`: `LibraryGrouping.groupKeyOf` ya mete a cada
película en su propio grupo a propósito (el `tmdbId` de artwork se equivoca en
películas — juntaba "Lego Batman" con "Batman (1966)"), así que el menú es un
filtro puro sobre una sola lista:

| Sección   | Filtro                  |
| --------- | ----------------------- |
| Todo      | todos los grupos        |
| Series    | `!primary.isMovie`      |
| Películas | `primary.isMovie`       |

"Todo" es todo lo guardado, sin importar si ya se vio: "Ya visto" es una vista
distinta sobre el mismo conjunto, no una sección que le saque títulos a las
otras. Un título terminado aparece en las dos.

Elegir una tarjeta hace lo mismo que hoy en el home: si es película reproduce
directo, si es serie abre el detalle con los capítulos. Vale igual en "Ya visto".

### Ya visto

Consulta nueva en `PlaybackDao` —donde ya vive `observeContinueWatching`—, con el
molde del `MAX(lastPlayedAt)` agrupado de `seriesConProgreso` (`ItemDao`):

```sql
SELECT e.itemId AS itemId, COUNT(*) AS episodios, MAX(p.lastPlayedAt) AS ultimoVistoMs
FROM playback p JOIN episodes e ON e.id = p.episodeId
WHERE p.watched = 1 AND p.deleted = 0 AND e.deleted = 0
GROUP BY e.itemId
```

La pantalla cruza esas filas con `libraryGroups`: un grupo está visto si
**cualquiera** de sus miembros tiene capítulos vistos, se ordena por el
`ultimoVistoMs` más alto del grupo, y la tarjeta dice "12 capítulos vistos".
Reusando `LibraryGrouping` una serie guardada de dos fuentes no sale duplicada.

El conteo por grupo es el **máximo** entre miembros, no la suma — mismo criterio
que `LibraryGroup.episodeCount`, y por el mismo motivo: las adquisiciones son
copias alternativas de la misma serie, no contenido disjunto (sumar 6
adquisiciones de Naruto daba 794 capítulos para una serie de ~220).

`watched` se marca solo al 60% de reproducción (`ArkivRepository`), así que "Ya
visto" incluye lo que quedó cerca del final. Es deliberado: con el criterio
estricto la sección estaría casi siempre vacía.

### Descargas

Reusa `DownloadsViewModel` entero: ya expone `groups`, `cancel`, `retry`,
`remove` y `confirm`, y `DownloadGroupPolicy` ya agrupa por serie. Lo único que
falta es la UI de TV.

Para el espacio libre: `LocalDownloadManager` ya mide el disco con `StatFs`
dentro de `hasFreeSpaceFor`, pero no publica el número. Se agrega
`espacioLibreBytes()` y se suman los `bytesDone` de las filas para el "ocupado
por descargas".

Elegir una fila abre un diálogo con las acciones que corresponden a su estado
—Reproducir, Cancelar, Reintentar, Borrar—, en vez de repartir botones dentro de
la fila: con el D-pad, varios botones por fila multiplican los saltos de foco y
hacen fácil apretar el equivocado.

## Bordes y errores

- Cada sección tiene su propio estado vacío, con qué hacer para llenarlo (no un
  "no hay nada" pelado).
- Cancelar y borrar piden confirmación: con el D-pad un clic de más cuesta
  gigabytes.
- "Quitar de mi biblioteca" hace `removeItem` (soft delete, viaja por el sync) y
  **no** borra los archivos ya bajados; esos se borran desde Descargas. El
  diálogo lo dice, en vez de dejarlo a adivinar.
- Arte faltante: `TvPosterCard` ya tiene su placeholder.
- Un ítem con capítulos vistos que se quitó de la biblioteca no aparece en "Ya
  visto": la consulta cruza contra `libraryGroups`, que ya excluye los borrados.

## Pruebas

JVM puras en `app/src/test/java/...`, que es lo que el repo ya hace. La UI
Compose de TV no se testea acá (no hay tests de UI en el repo); se verifica en el
Fire Stick por ADB.

1. **`VistosDeLaBiblioteca`** (nuevo, puro): dadas las filas
   `(itemId, episodios, ultimoVistoMs)` y los `LibraryGroup`, devuelve los grupos
   vistos ordenados por último visto y con el conteo por grupo. Casos: grupo con
   varios miembros vistos (toma el máximo, no la suma), grupo sin vistos (queda
   afuera), fila de vistos cuyo ítem ya no está en ningún grupo (se ignora).
2. **Filtro del menú** sobre `List<LibraryGroup>`: Todo / Series / Películas.
3. **Formato del espacio**: "12,4 GB libres · 3,1 GB en descargas".

## Fuera de alcance

- No se toca el buscador ni el guardado de Magis.
- No se agrega marcar visto / no visto desde el TV (existe en el detalle del
  teléfono).
- El bug de los capítulos de Magis entrando como películas **ya está arreglado**
  en `MagisEntities` (un ítem por temporada, `categoryOverride = "series"` desde
  el primer capítulo). Lo que falta ahí es commitear e instalar el build en el
  Fire Stick, no rediseñar. Esta pantalla no lo reemplaza: sin ese arreglo, una
  temporada de Magis seguiría apareciendo como N películas sueltas, ahora dentro
  de la pantalla nueva en vez del home.
