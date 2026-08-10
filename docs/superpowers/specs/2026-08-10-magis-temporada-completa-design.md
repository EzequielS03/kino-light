# Guardar la temporada completa al reproducir un capítulo de Magis

Fecha: 2026-08-10

## Problema

Poner a reproducir un capítulo de una serie de Magis guarda en la biblioteca **ese capítulo y nada
más**. La serie queda como una tarjeta de un episodio: al volver no hay lista de capítulos, no se ve
por dónde ibas, y el resto de la temporada hay que ir a buscarlo otra vez al buscador. Caso real:
Dragon Ball Daima quedó con un solo capítulo.

El resto de las fuentes ya no tiene este problema:

| Fuente | Al tocar un capítulo |
|---|---|
| torrent (pack) | `playPackRow` → `savePack` guarda el pack entero y reproduce el tocado |
| web (mirror) | `saveWebPack` → `addWholeWebSeries` guarda todos los capítulos y reproduce el tocado |
| archive.org | el ítem ya trae todos sus archivos |
| **magis** | **`playMagisEpisode` guarda uno solo** |

Magis es la única que se quedó atrás. El botón "Guardar N" del modal de temporada **no** es la
solución: además de guardar, **encola la descarga** de cada capítulo al dispositivo
(`localDownloads.enqueue`, `SearchScreen.kt:521` y `TvSearchScreen.kt:206`), que es otra cosa.

## Alcance

Solo Magis, y solo el camino "toco un capítulo para verlo". No se toca el botón de guardar/descargar,
ni las otras fuentes, ni el buscador.

## Diseño

### 1. Guardar la temporada entera al tocar un capítulo

Cuando se abre una temporada de Magis, la pantalla **ya tiene todos los capítulos en memoria**:
`client.episodes(season.ref)` los cargó para pintar la lista (`TvMagisSeasonContent`,
`MagisSeasonDialog`). Guardarlos todos no cuesta ni una llamada de red más — es un puñado de
escrituras locales.

**`MagisEntities.buildSeason(...)`** (nuevo). Puro/JVM, mismo molde que `build`, pero para la
temporada completa:

```kotlin
fun buildSeason(
    contentId: String,
    title: String,
    capitulos: List<CapituloDeTemporada>,   // number, title, ref
    posterUrl: String,
    ahora: Long,
    seriesRef: String,
    existente: ItemEntity?,
): Pair<ItemEntity, List<EpisodeEntity>>
```

- El `ItemEntity` sale exactamente como lo arma hoy `build` para un capítulo: `categoryOverride =
  "series"`, `source = "magis"`, `torrentData = seriesRef` (con la misma regla de "en blanco no pisa
  el ref guardado, porque uno vencido es mejor que ninguno"), y preservando de `existente` los campos
  que `upsertItem` (REPLACE) borraría: `addedAt`, `episodiosVistosEnLista`, `tmdbId`.
- Un `EpisodeEntity` por capítulo, idéntico al que arma `build`: `id = "$itemId::e$number"`,
  `orderIndex = number`, `season = null`, `episode = number`, `torrentData = ref` del capítulo.
- `CapituloDeTemporada` es un modelo mínimo del paquete `data` (number/title/ref) para que
  `MagisEntities` siga sin depender de `data.gateway`; el llamador mapea `GatewayEpisode` a él.

**`ArkivRepository.addMagisSeason(...)`** (nuevo). Gemelo de `savePackAsSeries`:

- Un `upsertItem` + un `upsertEpisodes(lista)`, no N escrituras sueltas.
- `upsert` y **no** `replaceItem`: los capítulos ya guardados de esa temporada no se pueden borrar
  (mismo motivo que en `addMagisSource`).
- Escribe el backdrop del portal en `artwork` igual que `addMagisSource`, solo si viene.
- Barre las filas legacy (`magis:<contentId>:e<n>`) de **todos** los capítulos que guarda, con el
  mismo `barrerItemLegacyDeCapitulo` de hoy.
- Re-sella el badge (ver sección 3).
- Devuelve `Map<Int, String>` (número de capítulo → `episodeId`), para que el llamador sepa cuál
  reproducir sin re-derivar ids a mano.

**`SearchPlayback.playMagisSeason(temporada, capitulos, elegido)`** (nuevo). Gemelo de `playPackRow`:
guarda la temporada entera y devuelve `PlaybackResult.Ready` con el `episodeId` del capítulo tocado.
Si por lo que sea el elegido no quedó guardado, `Failed` con el mismo texto de hoy
("No se pudo preparar el capítulo.").

Llamadores que cambian:

- `TvSearchScreen.TvMagisSeasonContent` → `onPlayOne` pasa a llamar `playMagisSeason(temporada, caps,
  cap)`.
- `MagisSeasonDialog` (celu) → `onPlay` igual.

`playMagisEpisode`/`magisEpisodeIdDe` se quedan como están: los usa el guardado-con-descarga, y
`BuscadorDeCapitulos` sigue llamando `addMagisSource` por capítulo cuando busca novedades.

### 2. "Vas en E5" en el detalle

Hoy la línea de datos del detalle dice `"20 episodios"` (`TvDetailScreen.kt:197`) o `"20 videos"`
(`DetailScreen.kt:407`), y el botón dice `"▶ Reproducir"`. Con la temporada completa guardada, el
dato que falta es por dónde vas.

- Línea: `"Vas en E5  ·  20 episodios"` cuando `ItemDetail.inProgressEpisode != null` (ya existe:
  último episodio empezado y sin terminar). Sin capítulo en curso, queda como hoy.
- Botón: `"▶ Reproducir E5"` en ese mismo caso; `"▶ Reproducir"` si no.
- Aplica al detalle del TV y al del celu, con el "videos"/"episodios" que ya usa cada uno.

**Helper compartido** `EtiquetaDeCapitulo` (nuevo, puro): arma `"T1 · E5"` / `"E5"` a partir de un
`Episode`. Sale de `episodeMeta`, que hoy es `private` en `TvDetailScreen.kt` y **tiene un bug que se
nota justo en este caso**: exige `season` y `episode` a la vez, y los capítulos de Magis guardan
`season = null`, así que caen a la rama de `orderIndex` y el capítulo 5 se muestra como **"E6"**
(`E${orderIndex + 1}` sobre un `orderIndex` que en Magis ya es 1-based). El helper usa `episode`
aunque no haya `season`; el orden de preferencia queda:

1. `season` y `episode` → `"T1 · E5"`
2. solo `episode` → `"E5"`
3. `orderIndex >= 1000` → `"T{/1000} · E{%1000}"`
4. resto → `"E{orderIndex + 1}"`

`TvDetailScreen.episodeMeta` pasa a usarlo (le agrega los minutos como hoy).

### 3. El badge de "nuevos"

`episodiosVistosEnLista` guarda cuántos capítulos tenía la serie la última vez que abriste su
detalle; el badge es la diferencia contra los de ahora (`ContadorDeNuevos`). Arranca en `null` y solo
se sella al abrir el detalle, así que una serie que entra por primera vez no prende nada.

El caso a cuidar es el otro: una temporada que ya tenías con 3 capítulos y el detalle ya abierto
(contador sellado en 3) pasaría a 20 y encendería **"17 nuevos"** por algo que hiciste vos, no por
algo que salió en el portal.

Regla: al terminar `addMagisSeason`, si el ítem **ya tenía** `episodiosVistosEnLista` no nulo, se
re-sella al total nuevo (`marcarEpisodiosVistos`); si era `null`, sigue `null`. El badge es para
"salieron capítulos nuevos", no para "acabás de guardar la temporada".

## Qué NO cambia

- El botón "Guardar N" / "Guardar toda la temporada" sigue guardando **y descargando**, igual que hoy.
- Las otras fuentes.
- `BuscadorDeCapitulos`: sigue trayendo los capítulos que salgan después, por su cuenta, al arrancar
  la app.
- El sync: son filas normales de `items`/`episodes`, viajan como cualquier otra.

## Tests

Unitarios, sin Room ni red (mismo molde que `MagisEntitiesTest`/`PackEntitiesTest`):

- **`MagisEntitiesTest.buildSeason`**: N capítulos → N episodios con `id`/`orderIndex`/`episode`
  correctos y el `ref` de cada uno en su episodio; el ítem queda `categoryOverride = "series"` con el
  `seriesRef` en `torrentData`; preserva `addedAt`, `episodiosVistosEnLista` y `tmdbId` del
  existente; con `seriesRef` en blanco no pisa el ref guardado.
- **`EtiquetaDeCapituloTest`**: `season = null, episode = 5` → `"E5"` (hoy da `"E6"`);
  `season = 1, episode = 5` → `"T1 · E5"`; `orderIndex = 1003` sin season/episode → `"T1 · E3"`;
  `orderIndex = 0` pelado → `"E1"`.
- **Badge**: guardar una temporada sobre un ítem con `episodiosVistosEnLista = 3` deja el contador en
  el total nuevo (badge apagado); sobre uno con `null` lo deja en `null`.

## Verificación en device

Es la parte que ningún test cubre: la lista de capítulos viene del portal y los refs caducan.

1. Buscar una serie de Magis (Dragon Ball Daima), abrir la temporada, tocar un capítulo del medio.
2. Que reproduzca **ese** capítulo, no el primero.
3. Volver a la biblioteca: la tarjeta tiene que decir la temporada completa, no "1 episodio".
4. Abrir el detalle: `"Vas en E5 · 20 episodios"`, botón `"▶ Reproducir E5"`, carrusel posicionado en
   el 5 y sin badge de nuevos.
5. Tocar otro capítulo de la lista y que reproduzca ese (los refs recién guardados siguen vivos).
