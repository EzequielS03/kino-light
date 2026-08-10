# Capítulos nuevos en las series que estoy viendo — Plan de implementación

## Estado (2026-08-10)

| Task | Estado |
|---|---|
| 1. `SeriesPorRevisar` | Hecho. Verificado en device: eligió 7 de 89 ítems |
| 2. `CapitulosFaltantes` | Hecho |
| 3. Archive | Hecho. **Verificado**: Dragon Ball GT pasó de 29 a 64 capítulos |
| 4. Magis | Escrito, **nunca corrió**: ninguna de las 7 series elegidas era de magis |
| 5. Web | **Pendiente**. Loguea y sigue |
| 6. Badge | Datos + UI hechos; **el badge no se vio nunca pintado** (ver abajo) |
| 7. Enganche al arranque | Hecho. Verificado, con freno de 6 h |

**Por qué el badge no está verificado visualmente**: al estrenarse, `episodiosVistosEnLista` es
`NULL` en toda la biblioteca y eso significa "sin badge" a propósito. Para verlo hace falta abrir el
detalle de una serie (que sella el conteo) y DESPUÉS que aparezca un capítulo — y el Fire TV no
tiene `sqlite3` para sembrar el estado a mano. La lógica del contador sí está cubierta (12 tests
entre `ContadorDeNuevosTest` y `LibraryGroupingNuevosTest`).


## Qué pasa hoy (medido en el código, 2026-08-10)

Una serie de la biblioteca **no se entera sola** de que salió un capítulo nuevo:

| Fuente | ¿Se actualiza? | Dónde |
|---|---|---|
| Archive | Solo si **abrís el detalle** | `DetailViewModel.init` → `repo.refreshItem(id)` |
| Web | **Nunca** | `refreshItem` rechaza lo que no sea archive (`ArkivRepository:325` + guard de `web:`) |
| Magis | **Nunca** | `addMagisSource` solo se llama desde `CineDetailScreen` |

No hay ningún worker de contenido: `UpdateWorker` es OTA de la app, `TvKeepAliveWorker` es
keep-alive, y `LocalDownloadWorker` / `NucDownloadCheckWorker` son de descargas. El botón de sync
del home de TV es `cloudSync.syncNow()` — progreso entre dispositivos vía PocketBase, no contenido.

## Decisiones tomadas (con el usuario, 2026-08-10)

- **Disparador**: un chequeo al **abrir la app**, en background. Sin worker periódico.
- **Fuentes**: las tres (archive, web, magis).
- **Aviso**: un **badge con el contador** en la tarjeta de la serie en la biblioteca, para verlo
  desde la grilla sin entrar. (Se pidió después de decidir "que aparezca y ya": el capítulo igual
  aparece solo, el badge se suma encima.)

## Global Constraints

- **Nada de esto puede demorar el arranque de la app.** Corre en background, y si falla, falla en
  silencio: es una mejora oportunista, no un camino crítico.
- **El costo tiene que estar acotado por diseño, no por suerte.** Web necesita una búsqueda por
  capítulo candidato; sin cota, seguir 15 series serían cientos de búsquedas por arranque.
- **Nunca borrar ni reordenar lo que ya existe.** Solo se agregan capítulos que faltan. El progreso
  guardado (`playback`) no tiene foreign key hacia `episodes`, pero los ids derivan del nombre del
  archivo: reemplazar de más es perder marcas de "voy por aquí".
- **Solo series con progreso reciente.** Revisar las 50 series de la biblioteca en cada arranque es
  gastar batería y datos en 45 que nadie está viendo.

## Evidencia que fundamenta el diseño

- El gateway expone `/v1/episodes` **solo para magis** ("el resto responde 422",
  `ArkivApiClient:117`). Así que web no puede resolverse por ahí.
- Para web sí hay `search(GatewaySearchQuery)` con `tmdbId`, `season` y `episode`
  (`ArkivApiClient:18`), y el id local de una serie web es `web:series:<seriesId>` con `seriesId`
  canónico (IMDb → `tmdb<id>` → `anilist<id>`, ver `SeriesItemIds`). O sea: se puede volver a
  preguntar por un capítulo concreto.
- El `ref` de magis **se re-emite en cada búsqueda** (comentario en `addMagisSource`): el guardado
  puede estar vencido, así que un fallo de `/v1/episodes` es esperable y no debe ensuciar el log
  como si fuera un error.

## File Structure

```
app/src/main/java/com/arkiv/player/data/nuevos/
  SeriesPorRevisar.kt        # a qué series vale la pena preguntarles (puro)
  CapitulosFaltantes.kt      # qué números faltan y cuántos pedir (puro)
  BuscadorDeCapitulos.kt     # orquesta las 3 fuentes (I/O)
app/src/test/java/com/arkiv/player/data/nuevos/
  SeriesPorRevisarTest.kt
  CapitulosFaltantesTest.kt
```

### Task 1: `SeriesPorRevisar` — a quién preguntarle (lógica pura)

Decide, dada la biblioteca y el progreso, qué series entran en el chequeo y en qué orden.

- Solo ítems de tipo serie (los que tienen más de un episodio o `categoryOverride = "series"`).
- Solo con progreso en los últimos **N días** (arrancar con 30).
- Ordenadas por lo más recientemente visto primero.
- Cortadas a un tope (`MAX_SERIES`, arrancar con 10): si seguís 40 series, las 10 más frescas son
  las que te importan hoy, y el resto se revisa en el próximo arranque.

Tests: serie sin progreso queda afuera; progreso viejo queda afuera; el orden es por
visto-más-reciente; el tope corta; una película nunca entra.

### Task 2: `CapitulosFaltantes` — qué pedir (lógica pura)

Dada la lista de números que YA están en Room y la que reporta la fuente, devuelve los que faltan.

- **Solo números mayores al máximo que ya tenés.** Es la cota que hace barato el caso web: no se
  re-buscan huecos viejos (que suelen ser capítulos que la fuente nunca tuvo), solo lo nuevo.
- Tope por serie (`MAX_POR_SERIE`, arrancar con 5): si aparecieron 20 de golpe, se traen 5 por
  arranque y el resto en el siguiente. Evita que una serie sola se coma todo el presupuesto.
- Sin duplicados y en orden ascendente.

Tests: nada nuevo → lista vacía; hueco viejo NO se pide; 3 nuevos seguidos → los 3; 20 nuevos → 5;
lista de la fuente vacía → vacío; números repetidos → una sola vez.

### Task 3: `BuscadorDeCapitulos` — archive

Por cada serie de archive elegida, `repo.refreshItem(itemId)`. Ya reemplaza los episodios con lo
que diga `/metadata/`, así que los nuevos aparecen solos. Es la fuente barata y la que menos código
nuevo necesita.

Verificación: agregar un ítem de archive, borrarle un episodio a mano de Room, correr el chequeo y
confirmar que vuelve.

### Task 4: `BuscadorDeCapitulos` — magis

`gateway.episodes(ref)` con el ref guardado del ítem → `CapitulosFaltantes` contra lo que hay →
`addMagisSource` por cada uno.

Ojo con el ref vencido: si `/v1/episodes` falla o vuelve vacío, se registra a nivel `info` y se
sigue. No es un error del usuario.

### Task 5: `BuscadorDeCapitulos` — web

El más caro, y por eso el último:

1. `SeriesItemIds.seriesIdOf(itemId)` → seriesId canónico, y de ahí el `tmdbId`.
2. Metadata (TMDB/Cinemeta) dice qué episodios existen en la temporada en curso.
3. `CapitulosFaltantes` acota a los posteriores al máximo conocido.
4. Por cada uno: `gateway.search(tmdbId, season, episode, sources = "web")`. Si hay resultado →
   `addWebSeriesEpisode` con el `pageUrl`.

**No se resuelve el stream acá.** Resolver es caro y se hace al reproducir; para que el capítulo
aparezca en la lista alcanza con el `pageUrl`.

### Task 6: Badge de "hay capítulos nuevos" en la tarjeta

**Por qué NO se hace con fechas.** `EpisodeEntity` no guarda fecha de alta: solo tiene `updatedAt`,
que es el reloj LWW del sync. Y `refreshItem` usa `replaceItem`, que **borra y re-inserta todos los
episodios** del ítem — así que después de cualquier refresco los 26 capítulos tendrían `updatedAt`
fresco y el badge diría "26 nuevos" cada vez. Cualquier diseño basado en timestamps de episodio
está roto de entrada acá.

Se hace comparando contra **lo que ya viste listado**:

- Una columna nueva en `ItemEntity`: `episodiosVistosEnLista: Int?` (nullable, migración Room
  18 → 19; el `null` de las filas viejas significa "nunca se miró", y se inicializa con el conteo
  actual la primera vez que se abre el detalle, para que la biblioteca existente no aparezca toda
  con badge).
- Badge = `cantidadActualDeEpisodios − episodiosVistosEnLista`, y se pinta solo si es > 0.
- Se pone a cero (o sea, se iguala al conteo actual) cuando abrís el detalle de esa serie — que es
  el momento en que efectivamente los viste.

Lógica pura y testeable: `ContadorDeNuevos.cuantos(actuales: Int, vistos: Int?): Int` — nunca
negativo (si borraste capítulos, es 0), y `null` → 0 (nunca badge en la primera pasada).

Tests: sin vistos previos → 0; 26 actuales y 24 vistos → 2; 24 actuales y 26 vistos → 0; iguales → 0.

UI: el badge va en la tarjeta de serie del home de TV y de la biblioteca, al lado del contador de
episodios que ya existe (`"25 ep."`). **Ojo con el trabajo de la otra sesión**: la tarjeta de serie
pasó a ser un grupo de fuentes (`2 FUENTES`), así que el contador tiene que sumarse a nivel grupo,
no de ítem suelto — coordinar con `observeLibraryGroups()` antes de tocar esa vista.

### Task 7: Enganche al arranque

Un disparo único desde `ArkivApp`/`AppGraph`, en background y sin bloquear la UI. Con un
anti-repetición: no volver a chequear si ya se hizo hace menos de X horas (el arranque de la app
pasa muchas veces por día).

## Fuera de alcance (anotado a propósito)

- **Fila "Nuevos capítulos" en el home.** Se evaluó junto al badge y se eligió solo el badge, que
  es mucho menos UI nueva. El dato para armarla después es el mismo de Task 6.
- **Notificación del sistema.** No se pidió, y para una app que se usa en el TV es ruido.
- **Torrent.** No se pidió, y sus series vienen de packs: "capítulo nuevo" ahí significa otra cosa.
- **Worker periódico.** Se descartó a favor del chequeo al abrir. Si más adelante se quiere, Task 6
  es el único punto que cambia.
- **Resolver el stream de los capítulos nuevos por adelantado.** Ver Task 5.
