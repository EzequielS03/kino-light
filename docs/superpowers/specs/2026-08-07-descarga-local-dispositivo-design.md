# Descarga local al dispositivo — diseño

Fecha: 2026-08-07

## Problema

Arkiv puede guardar contenido en la NUC (`arkiv-offline` en blog) y reproducirlo por streaming
remoto, pero no puede guardar nada **en el propio dispositivo**. La única excepción es archive.org:
`Downloader` baja episodios de archive con el `DownloadManager` del sistema y `loadArchive()`
prefiere el archivo local sobre el streaming. Ese camino:

- se dispara solo desde `DetailScreen` (detalle de un ítem de archive.org),
- exige `episode.original` / `episode.derivative`, o sea variantes dentro de un ítem de archive,
- no existe para las dos fuentes que traen películas y anime: **torrent** y **web**.

Se quiere poder guardar películas y anime en el dispositivo desde **búsqueda** y desde **librería**,
para las tres fuentes, y que al dar play se reproduzca el archivo local en vez del streaming.

## Decisiones tomadas

| Decisión | Valor |
|---|---|
| Fuentes a soportar | torrent + web + archive.org (las tres) |
| Destino de los archivos | privado de la app: `Android/data/com.arkiv.player/files/Movies/` |
| Botón "Descargar offline" actual | el archivo final queda **en el dispositivo**; deja de ser un destino en la NUC |
| Camino web | pasa por la NUC como **estación de paso**, nunca como destino |
| Play con archivo local | automático, sin preguntar |
| Torrent grande | avisa y pide confirmación si el archivo a bajar supera **5 GB** |
| Cast de lo guardado | se sirve por HTTP local para no perder Chromecast/DLNA |
| Orden de construcción | cola + torrent + archive primero; web/NUC después |

### Por qué el destino es el almacenamiento privado de la app

Es lo que ya usa `Downloader` (`getExternalFilesDir(DIRECTORY_MOVIES)`). No necesita permisos, no
necesita MediaStore, y funciona igual en el Fire TV Stick. El costo aceptado: los archivos no se ven
desde el explorador ni la galería, y desinstalar la app los borra.

### Por qué web pasa siempre por la NUC

El resolver web devuelve con frecuencia **HLS (`.m3u8`)**, que no es un archivo descargable. El
backend `arkiv-offline` ya resuelve exactamente ese problema con **yt-dlp** (ver
`offline/ytdlp_download.py`), y ya expone todo lo necesario:

| Endpoint | Rol en este diseño |
|---|---|
| `POST /jobs` con `source_ref` = pageUrl | la NUC re-resuelve y baja, HLS incluido |
| `GET /jobs/{id}/events` (SSE) | progreso de la fase de staging |
| `GET /stream/{item}` → `send_file(conditional=True)` | soporta **Range (RFC 7233)** → transferencia reanudable |
| `DELETE /library/{item}` | libera el archivo del servidor tras la transferencia |

`ArkivOfflineApi.baseUrlResolved()` prueba LAN antes que túnel, así que en casa la transferencia
NUC→dispositivo va por LAN.

Se descartó bajar HLS en el dispositivo con `sout` de libVLC: era la única pieza del diseño con
riesgo técnico real, y el servidor ya la resuelve.

Se descartó un camino híbrido (directo cuando el stream es mp4 progresivo, NUC cuando es HLS):
duplica la lógica y el manejo de errores, y el camino directo se rompe con hosts token-gated cuyo
enlace caduca a mitad de una descarga larga — el mismo problema que ya obligó al `firstPlayable`.

Costos aceptados de esta decisión:

- guardar contenido web **requiere blog vivo y con espacio**; torrent y archive no dependen de nada,
- el capítulo se baja dos veces en serie (origen→NUC, después NUC→dispositivo): aproximadamente el
  doble de tiempo,
- blog es un NUC saturado, así que el ítem se borra apenas termina la transferencia y la cola
  procesa de a uno.

## Arquitectura

Una tabla, una cola, tres estrategias. La cola no sabe cómo se baja cada cosa; ninguna estrategia
sabe que existe una cola.

### Tabla

Se **extiende** la tabla `downloads` existente con una migración Room, en vez de crear una nueva, para
no perder lo ya descargado de archive.org. `variant` sigue siendo NOT NULL y vale `""` para torrent y
web: SQLite no puede cambiar la nulabilidad de una columna con `ALTER TABLE`, y reconstruir la tabla
entera no se justifica por un campo que solo usa archive.

Columnas nuevas:

| Columna | Tipo | Para qué |
|---|---|---|
| `source` | String | `"archive"` \| `"torrent"` \| `"web"` |
| `filePath` | String? | ruta final en el dispositivo |
| `bytesDone` | Long | bytes ya escritos (reanudación con `Range`) |
| `stagingItemId` | Long? | ítem de la NUC mientras es paso intermedio (web) |
| `error` | String? | motivo del fallo, mostrado en la UI |
| `createdAt` | Long | orden de la cola |
| `sizeConfirmed` | Boolean | el usuario ya aceptó la compuerta de 5 GB para esta fila |

Estados de `state`: `queued` → `staging` (solo web) → `downloading` → `completed`, con `failed` como
estado terminal reintentable y `needs_confirmation` como pausa a la espera del usuario (ver "Aviso de
tamaño en torrent").

### Componentes

**`LocalDownloadManager`** — fachada, lo único que toca la UI.

```
enqueue(request: LocalDownloadRequest)
cancel(episodeId: String)
remove(episodeId: String)          // borra fila + archivo
observeAll(): Flow<List<LocalDownloadRow>>
```

`LocalDownloadRequest` lleva lo mínimo para que una estrategia pueda trabajar sin volver a resolver
nada: `episodeId` (el mismo con el que después se pide el play, así que sale del ítem ya guardado
localmente — `addWebSeriesEpisode`, `addSeriesEpisode` o el episodio de archive), `source`, título y
subtítulo para la notificación y la lista, y el puntero a la fuente: pageUrl para web, magnet o bytes
del `.torrent` más `fileIndex` para torrent, `itemId` para archive.

**`LocalLibrary`** — lo único que consulta `PlayerViewModel`.

```
fileFor(episodeId: String): String?   // null si no está completo o el archivo ya no existe
```

**`LocalDownloadWorker`** — WorkManager con foreground service `DATA_SYNC`. Toma la cola y procesa
**de a uno**. Secuencial y no en paralelo por tres razones concretas: `TorrentEngine` es de un stream
activo a la vez, el disco de blog no aguanta varios staging simultáneos, y en el Fire TV Stick el
ancho de banda no sobra. WorkManager (2.9.1) y los permisos `FOREGROUND_SERVICE_DATA_SYNC` /
`POST_NOTIFICATIONS` ya están en el proyecto.

**`DownloadStrategy`** — una interfaz, tres implementaciones.

```
suspend fun download(row: LocalDownloadRow, onProgress: (Float, Long) -> Unit): Result<File>
```

**`LocalFileServer`** — servidor HTTP local con soporte de `Range` que sirve un archivo del disco,
para que el cast y el DLNA sigan funcionando con contenido guardado. Sigue el patrón de
`TorrentStreamServer` y `ArchiveCacheProxy`, que ya hacen esto mismo para otras fuentes.

### Unificación del transporte HTTP

Se **saca el `DownloadManager` del sistema** y las tres estrategias usan OkHttp con `Range`. Hoy el
progreso de archive vive en SharedPreferences con un mapeo `dmId ↔ episodeId` consultado por polling;
las otras dos fuentes no pueden usar ese mecanismo, y mantenerlo dejaría dos modelos de progreso
conviviendo en la misma pantalla. Implica reescribir las ~150 líneas de `Downloader`, que hoy
funcionan.

## Flujo por fuente

Las tres escriben a `files/Movies/`, siempre a un `.part` que se renombra al final. La reanudación es
`Range: bytes=<bytesDone>-` en todos los casos.

### Web (NUC como estación de paso)

1. Consulta `GET /library`: si ya existe ítem para `(seriesId, season, episode, sourceRef)`, se salta
   la creación del job y baja directo. Reusa lo que ya esté en la NUC.
2. `POST /jobs` con `source_ref` = pageUrl exacta. Guarda `stagingItemId`, estado `staging`.
3. SSE `GET /jobs/{id}/events` → progreso mapeado a **0 – 50 %**.
4. Job en `done` → `GET /stream/{item}` con `Range` → progreso **50 – 100 %**.
5. Verifica bytes escritos contra `Content-Length`, renombra el `.part`, marca `completed`.
6. `DELETE /library/{item}`, best-effort.

### Torrent

`savePath` propio en `files/Movies/torrents/<episodeId>/`. El handle se agrega a la **misma** sesión
de libtorrent que ya existe — no una segunda sesión, que competiría por puerto y DHT. Prioridad
`NORMAL` en las piezas del archivo elegido e `IGNORE` en el resto del pack. Progreso desde
`handle.status()`.

Al completar: mover el archivo a su nombre final y `session.remove(handle)` **sin** el flag
`DELETE_FILES`. Ese borrado es precisamente lo que hace `stopStream` hoy —
`TorrentEngine` está documentado como "descarga al cacheDir y borra al parar", y esta estrategia es
la excepción a esa regla.

### Aviso de tamaño en torrent

Un torrent de más de **5 GB** no se baja sin confirmación explícita.

La comparación es contra el tamaño del **archivo que se va a bajar**, no el del torrent completo.
`TorrentResult.sizeBytes` es el peso del pack entero: usarlo directo haría que un pack de temporada
de 30 GB con capítulos de 1,2 GB avise en falso en cada capítulo.

Compuerta única, en el worker, después de resolver la metadata del torrent — que es el primer momento
en que se conoce el tamaño real del archivo elegido:

1. Metadata resuelta → tamaño del archivo en `fileIndex`.
2. Si supera el umbral: la fila pasa a `needs_confirmation`, **no se descarga un solo byte**, y se
   emite una notificación.
3. `DownloadsScreen` muestra la fila como "Necesita confirmación · 8,4 GB" con dos acciones:
   **Descargar igual** y **Descartar**.
4. Confirmada, la fila vuelve a `queued` con una marca que salta la compuerta en el siguiente intento.

Como atajo de UX, cuando el tamaño ya se conoce al tocar el botón (resultado de búsqueda de un
torrent de un solo archivo, con `TorrentResult.sizeBytes` fiable) se muestra además el diálogo inline,
para no encolar algo que se va a descartar igual. Es solo un atajo: la compuerta del worker es la que
garantiza el comportamiento, y es la única que ve el tamaño correcto en packs.

Umbral: constante `WARN_TORRENT_SIZE_BYTES = 5 GB`, junto a la configuración, promovible a ajuste si
hace falta. **No confundir con `settings.maxTorrentSizeGb`** (21 GB por defecto): ese es un filtro que
se aplica a los resultados de búsqueda vía `MirrorFilter`, no tiene nada que ver con avisar antes de
descargar, y los dos valores conviven sin pisarse.

El aviso es solo para torrent. En web el tamaño no se conoce hasta que la NUC termina el staging, y en
archive.org la variante ya la elige `settings.downloadQuality`.

### Archive

`Range` sobre `ArchiveUrls.download(itemId, variant.path)`. La variante se elige con el mismo
`variantFor()` de hoy, según `settings.downloadQuality`.

## Reproducción

En `PlayerViewModel.load()`, antes de ramificar por fuente:

```kotlin
localLibrary.fileFor(episodeId)?.let { loadLocal(episodeId, it); return@launch }
```

`loadLocal` arma un `PlayerData` con `mediaUrl = "file://$path"` y `kind = SourceKind.LOCAL` (valor
nuevo del enum). `castUrl` apunta al `LocalFileServer`, no al `file://`, porque un Chromecast o un
renderer DLNA hacen su propio GET y no pueden abrir una ruta del sistema de archivos del teléfono.

**Archive queda intacto.** `loadArchive()` ya construye la playlist de la sección pasando
`completedDownloadUri` por episodio, o sea que ya mezcla local y remoto correctamente. El atajo de
arriba aplica solo a torrent y web, que hoy son de ítem único.

## Cambios de UI

- **Búsqueda y detalles**: `SourceSection` ya acepta `onDownload`; hoy solo se lo pasa la sección WEB.
  Pasárselo también a TORRENT y ARCHIVE en `SearchScreen`, `CineDetailScreen` y
  `AnimeShowDetailScreen`.
- **Librería**: `SheetAction("Guardar en el dispositivo")` en el bottom sheet del long-press, más un
  indicador en la tarjeta cuando el ítem ya está guardado.
- **`WebPackDialog`**: el botón de N seleccionados encola N descargas al dispositivo.
- **`DownloadsScreen`**: muestra las tres fuentes con badge de origen y los estados *en cola /
  preparando en el servidor / bajando / listo / falló*, con acciones cancelar y borrar. Se le quita el
  polling de 1,5 s: el estado sale de Room, que actualiza el worker.
- **NUC**: `NucDownloadsScreen`, el diálogo "¿NUC o en vivo?" y `PlaybackPreferenceStore` se
  desconectan de la navegación y de los botones. El código no se borra.

## Errores y casos borde

| Caso | Comportamiento |
|---|---|
| Sin espacio en el dispositivo | `StatFs` antes de encolar; falla con mensaje, no a medio camino |
| blog caído, o `fits` rechaza por cuota | fila `failed` con motivo, reintentable desde la pantalla |
| Corte de red | `.part` + `Range` reanudan; WorkManager reintenta con backoff |
| App matada a mitad | el `.part` sobrevive; reanuda al re-encolar |
| Torrent sin peers | timeout de 180 s (el mismo de `resolveTorrentUrl`) → `failed` |
| `DELETE /library` falla | queda el `stagingItemId`; un barrido al arrancar borra los huérfanos de filas ya `completed` |
| Archivo borrado por fuera de la app | `fileFor()` chequea `exists()`; si falta, limpia la fila y cae a streaming |

El último no es opcional: sin esa verificación el play apunta a un archivo fantasma y VLC da pantalla
negra sin explicación.

## Testing

Unitarios sin device, con fakes de `ArkivOfflineApi` y del engine de torrent:

- máquina de estados de la cola (`queued` → `staging` → `downloading` → `completed` / `failed`),
- mapeo de progreso: staging 0–50 %, transferencia 50–100 %,
- armado de nombre y extensión del archivo destino,
- chequeo de espacio antes de encolar,
- `fileFor()` devolviendo null cuando la fila dice `completed` pero el archivo no existe,
- reanudación: `Range` calculado a partir de `bytesDone`,
- compuerta de 5 GB: dispara con un archivo de 6 GB, **no** dispara con un pack de 30 GB cuyo archivo
  elegido pesa 1,2 GB, y no vuelve a disparar una vez confirmada.

Verificación en dispositivo real sobre el Samsung S24+ (ADB WiFi) y el Fire TV Stick (ADB de red).

## Orden de construcción

**Fase 1 — infraestructura y fuentes independientes.** Migración de la tabla, `LocalDownloadManager`,
`LocalDownloadWorker`, `LocalLibrary`, `LocalFileServer`, las estrategias de torrent y archive, el
atajo en `load()`, y la UI completa. Al terminar la fase 1 se puede guardar y reproducir local sin
que blog tenga que estar vivo.

**Fase 2 — web.** `NucStagedStrategy` sobre una base ya probada, más el barrido de huérfanos en la
NUC y el desconectado de la maquinaria de reproducción remota.

## Fuera de alcance

- Guardar en carpeta pública, MediaStore, tarjeta SD o USB.
- Descargas en paralelo.
- Bajar HLS en el dispositivo sin pasar por la NUC.
- Borrar el código de la maquinaria NUC de reproducción remota (se desconecta, no se elimina).
