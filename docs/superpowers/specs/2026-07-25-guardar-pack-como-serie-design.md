# Guardar un pack como serie (elegir capítulos) — Diseño

**Fecha:** 2026-07-25
**Branch:** `feat/torrents-ondevice-sin-servidor`
**Alcance:** Al tocar una fuente marcada **PACK** (temporada/serie completa/rango), abrir un diálogo que
lista los capítulos del pack y permite **guardarlo como una entrada propia en la biblioteca** (con
póster + descripción del catálogo) para luego **elegir qué capítulo reproducir** desde el detalle.

---

## 1. Contexto

Hoy, cuando una fuente es un pack (varios episodios en un solo torrent, ej.
`House.of.the.Dragon.S01.COMPLETE...`):
- En **películas** el tap llama `addTorrent(...)` — que ya guarda 1 ítem con 1 episodio por archivo —
  pero **auto-reproduce el primer archivo** y nunca te muestra que quedó guardado con los N capítulos.
- En **series/anime** el tap usa `EpisodeFilePicker` para extraer **un solo** archivo y reproducirlo.

El badge naranja "PACK · varios episodios" ya avisa (via `PackDetector.isPack`), pero no hay forma de
**ver los capítulos del pack y elegir**. Esta feature agrega esa ventana de decisión.

Piezas que YA existen y se reutilizan:
- **Resolver metadata + listar archivos**: `TorrentEngine.resolveMagnet/resolveTorrent` + `videoFiles(meta)`
  (`List<TorrentFile(index, name, sizeBytes)>`).
- **Modelo de datos "1 ítem, N episodios por archivo"**: `ArkivRepository.addTorrent(...)` ya crea un
  `ItemEntity` con un `EpisodeEntity` por archivo (cada uno con su `torrentFileIndex`).
- **Navegar capítulos por temporada + reproducir el elegido**: `DetailScreen` ya agrupa
  `episodes.groupBy { section }` y reproduce `onPlayEpisode(ep.id)`.
- **Parseo de nombre → episodio**: regex en `EpisodeFilePicker` (S01E02 / 1x05 / Cap 102 / absoluto).
- **Calidad**: `QualityLabel.extract(name)`. **Nombre limpio**: `MetadataParser.cleanName(name)`.
- **Detección de pack**: `PackDetector.isPack(name)` (estricto; un episodio suelto nunca es pack).

## 2. Diseño

### 2.1 Identidad en biblioteca — ítem NUEVO y aparte, con "Pack" en el título

Cada pack guardado es su **propia entrada** (NO fusiona con la serie del catálogo). Id estable por
torrent: `torrent:<infoHashHex>` (igual que `addTorrent`), inherentemente único por pack. El **título
lleva la palabra "Pack"** para diferenciarlo de un vistazo en la grilla: default `"<Título> — Pack"`
(ej. `House of the Dragon — Pack`), **editable por el usuario** antes de guardar (§2.5). Guarda
**póster + descripción** del catálogo (TMDB/AniList), que ya están disponibles en la pantalla de
detalle desde la que se abre el diálogo.

Consecuencia intencional: si guardás dos packs de la misma serie quedan como dos entradas distintas
(distinto hash). Volver a guardar el mismo pack reemplaza la entrada (mismo hash) — sin duplicar.

### 2.2 `PackFileInfo` (data, nuevo)

Pequeño parser de nombres de archivo, **factorizado desde las regex de `EpisodeFilePicker`** para que
ambos compartan la misma lógica (sin duplicar regex):

```
data class PackFileInfo(val season: Int?, val episode: Int?, val absolute: Int?)
object PackFileParser { fun parse(name: String): PackFileInfo }
```

`EpisodeFilePicker.pick(...)` se reescribe para usar `PackFileParser.parse` internamente (mismo
comportamiento observable; los tests existentes de picker siguen verdes). Reconoce: `SxxEyy`, `NxNN`
(1x05), `Cap NEE` español (1er dígito = temporada, 2 finales = episodio) y número absoluto/relativo
(anime), ignorando resolución/codec/año (1080p, x264, 2019).

### 2.3 `PackContents` + resolución (data)

Al tocar un pack, un helper resuelve la metadata y arma la lista mostrable:

```
data class PackFileRow(val index: Int, val label: String, val sizeBytes: Long, val quality: String,
                       val section: String, val orderIndex: Int)
data class PackContents(val infoHashHex: String, val infoBytes: ByteArray, val rows: List<PackFileRow>)
```

- `resolveSource(result)` → magnet o .torrent → `resolveMagnet/resolveTorrent` → `videoFiles(meta)`.
- Cada archivo → `PackFileParser.parse` → etiqueta y sección:
  - Con temporada+episodio: `label = "T{season} · E{episode}"`, `section = "Temporada {season}"`,
    `orderIndex = season*1000 + episode` (igual criterio que `addSeriesEpisode`).
  - Solo absoluto (anime): `label = "Ep {absolute}"`, `section = ""`, `orderIndex = absolute`.
  - Sin parseo: `label = MetadataParser.cleanName(name)`, `section = ""`, `orderIndex = posición`.
- `quality = QualityLabel.extract(name)`, `sizeBytes` del `TorrentFile`.

La resolución de magnet puede tardar (hasta ~45s buscando peers): el diálogo muestra spinner mientras.

### 2.4 `repository.savePackAsSeries(...)` (data, nuevo)

```
suspend fun savePackAsSeries(
    title: String, posterUrl: String, description: String?,
    infoHashHex: String, infoBytes: ByteArray, files: List<PackFileRow>,
): String   // devuelve itemId
```

- `itemId = "torrent:$infoHashHex"`.
- `title` llega **ya final** desde el diálogo (el usuario pudo renombrarlo; ver §2.5). El método lo usa
  tal cual, sin forzar sufijo.
- `ItemEntity(identifier=itemId, title=title, description=description, thumbnailUrl=posterUrl,
  categoryOverride="series", source="torrent", torrentData=Base64(infoBytes))`.
- Un `EpisodeEntity` por `PackFileRow`: `id="$itemId::${index}"`, `section`, `displayName=label`,
  `orderIndex`, `originalSize=sizeBytes`, `torrentFileIndex=index`, `torrentData=null` (el .torrent vive
  en el ítem, no por episodio — un solo torrent para todo el pack).
- `itemDao.replaceItem(item, episodes)` (reemplaza si se re-guarda el mismo pack).

### 2.5 `PackDialog` (UI, composable compartido)

Reusado por `CineDetailScreen`, `ShowDetailScreen` y `AnimeShowDetailScreen`. Estados: resolviendo /
error / listo. Muestra póster + **campo de título editable** (renombrar); mientras resuelve, spinner
"Leyendo el pack…"; al listo, la lista de `PackFileRow` (label + tamaño + calidad), cada fila con
**checkbox** y **tap = reproducir ese**; y botones **"Guardar todo como serie"** /
**"Guardar seleccionados (N)"**.

El campo de título arranca con el default `"<Título> — Pack"` y es editable: el usuario puede
renombrarlo antes de guardar. El texto final del campo es el que se pasa a `savePackAsSeries` (§2.4).

Acciones (todas via callbacks que la pantalla implementa con `savePackAsSeries` + navegación):
- **Guardar todo / seleccionados** → `savePackAsSeries(tituloEditado, rowsElegidas)` → navegar a
  `DetailScreen(itemId)`.
- **Reproducir uno** → `savePackAsSeries(todas)` (el player siempre lee de la DB; el ítem-pack es el
  contenedor natural, no deja huérfanos) → `onPlay("$itemId::${index}")` directo al player.

### 2.6 Wiring del disparador

En cada pantalla de detalle, al tocar una fuente torrent: si `PackDetector.isPack(result.name)` →
abrir `PackDialog(result)` en vez de reproducir. Fuentes de un solo episodio siguen reproduciendo al
instante (comportamiento actual intacto). Aplica a **películas, series y anime**.

### 2.7 Errores

- Resolver falla / timeout / sin seeds → el diálogo muestra "No se pudo leer el pack (sin seeds ahora)".
- Torrent sin videos → "El pack no tiene video reproducible".
- Archivos sin S/E parseable → etiqueta = nombre limpio, sección en blanco; igual se pueden guardar.

### 2.8 Fuera de alcance

- No se cambia la reproducción de fuentes de un solo episodio ni el resolver web.
- No se fusiona el pack con la serie del catálogo (decisión explícita: entrada aparte con "Pack").
- No se descargan los archivos por adelantado: guardar solo indexa; la descarga/streaming ocurre al
  reproducir un capítulo (como hoy).

## 3. Testing

- **`PackFileParser.parse`** (TDD): `S01E02`→(1,2,null); `1x05`→(1,5,null); `Cap 102`→(1,2,null);
  `One Piece - 1085`→(null,null,1085); `House.of.the.Dragon.S01E02.1080p.x264.2019`→(1,2,null) sin
  confundir 1080/264/2019; basura → (null,null,null).
- **`EpisodeFilePicker`**: sus tests actuales siguen verdes tras el refactor a `PackFileParser`.
- **`savePackAsSeries`** (repositorio con DAO falso): crea ítem `torrent:<hash>` con título con "Pack",
  descripción y póster; N episodios con `torrentFileIndex`/`section`/`orderIndex` correctos; re-guardar
  reemplaza sin duplicar.
- **Device (gate real)**: en House of the Dragon, tocar la fuente pack `S01.COMPLETE` → diálogo lista
  T1·E1…E10 con tamaños → "Guardar todo" → biblioteca muestra "House of the Dragon — Pack" → detalle
  agrupa por Temporada 1 → tocar E2 reproduce el archivo correcto. Probar también "Reproducir uno".

## 4. Criterios de éxito

- Tocar una fuente PACK abre un diálogo con los capítulos (etiqueta S/E + tamaño + calidad), no
  reproduce a ciegas.
- Se puede guardar el pack completo o un subconjunto como **entrada propia** "… — Pack" (título
  **renombrable** antes de guardar) con póster y descripción del catálogo.
- Desde el detalle del pack se navegan los capítulos por temporada y se reproduce el elegido (archivo
  correcto vía `torrentFileIndex`).
- "Reproducir uno" reproduce ese capítulo al instante y deja el pack guardado sin huérfanos.
- Fuentes de un solo episodio no cambian. Tests existentes verdes; verificado en device.
