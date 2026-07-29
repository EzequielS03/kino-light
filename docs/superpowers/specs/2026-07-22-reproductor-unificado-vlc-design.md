# Reproductor unificado sobre VLC — Diseño

**Fecha:** 2026-07-22
**Estado:** Aprobado (pendiente de plan de implementación)

## Contexto y objetivo

Hoy la app tiene **dos reproductores separados**:

- **archive.org** — [`PlayerScreen.kt`](../../../app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt):
  usa **media3/ExoPlayer** a través de un `PlaybackService` (`MediaSessionService`). Eso le da
  segundo plano + notificación con carátula y controles de pantalla de bloqueo, caché en disco
  (`CacheDataSource`, 512 MB), playlist de episodios con autoplay, y marcadores intro/outro.
- **torrent** — [`TorrentPlayerScreen.kt`](../../../app/src/main/java/com/arkiv/player/ui/torrent/TorrentPlayerScreen.kt):
  usa **libVLC** directo. Le gana en formatos raros (.avi/XviD, Dolby Vision, TrueHD/DTS) que
  ExoPlayer no decodifica, y en selección de pistas de audio/subtítulos embebidas + OpenSubtitles.
  No tiene segundo plano, ni notificación, ni playlist, ni caché.

**Objetivo:** un **único reproductor** que use **VLC como motor** para ambas fuentes, motivado por
(1) formatos de archive.org que fallan en ExoPlayer, (2) un solo código en vez de dos pantallas
duplicadas, y (3) experiencia consistente de controles/subtítulos/audio.

**Restricción dura:** conservar las **cuatro** funciones que hoy da media3 en archive.org:
playlist/autoplay, marcadores intro/outro, segundo plano + notificación, y caché en disco.

## Enfoque elegido

**VLC como motor, alojado dentro del `MediaSession` de media3** (opción "B" de la lluvia de ideas).

Se escribe un `VlcPlayer` que implementa la interfaz `Player` de media3 (vía `SimpleBasePlayer`)
envolviendo el `MediaPlayer` de libVLC, y se enchufa en el `PlaybackService` **existente**. Así el
`MediaSessionService` sigue dando gratis notificación, segundo plano, pantalla de bloqueo, botones
de auriculares, audio focus, playlist/autoplay y el swap a Chromecast — pero el motor de
decodificación pasa a ser VLC.

Alternativas descartadas:
- **A — VLC puro reimplementando todo a mano** (foreground service + `MediaSessionCompat` +
  notificación + audio focus + playlist): reescribe justo lo que media3 ya resolvió; más trabajo y
  más riesgo de regresión en el archive.org que hoy anda bien.
- **C — dos motores, unificar solo la UI**: no cumple el pedido de "todo en VLC" y sigue con dos
  motores.

## Arquitectura

```
PlayerScreen (una sola)  ──MediaController──►  PlaybackService
   │ (render: VLCVideoLayout)                     │ (MediaSession, notificación,
   │                                              │  segundo plano, playlist)
   └─ controles Compose + markers + zoom + cast   └─ VlcPlayer : SimpleBasePlayer
                                                        └─ libVLC MediaPlayer

   Fuentes → MediaItem:
     archive.org → ArchiveCacheProxy (http://127.0.0.1:p) → archive.org + caché disco (LRU 512MB)
     torrent     → TorrentStreamServer (ya existe)
```

## Componentes

### 1. `VlcPlayer : SimpleBasePlayer` (pieza central, la más delicada)

Clase nueva (`com.arkiv.player.playback`) que implementa `Player` de media3 envolviendo `LibVLC` +
`MediaPlayer`.

- **Traduce el modelo de estado media3 ↔ VLC:** `setMediaItems`/playlist, `seekTo`,
  `seekToNext/Previous`, `play/pause`, `playWhenReady`, posición/duración,
  `STATE_BUFFERING/READY/ENDED`, transición de pista (autoplay al siguiente episodio). Mapea los
  `MediaPlayer.Event.*` de VLC a `SimpleBasePlayer.State`.
- **Render:** la UI le entrega el `VLCVideoLayout`; `VlcPlayer` hace `attachViews`/`detachViews`
  sobre el `MediaPlayer` interno. El render se desacopla del control (el control va por el
  `MediaController`).
- **Pistas embebidas** (audio/subtítulos VLC) y subtítulos externos (`addSlave`): se exponen con
  una interfaz chica propia del `VlcPlayer` (el modelo de tracks de media3 no cubre bien todo esto).
- **Parámetros por fuente:** lee el tag del `MediaItem` (ver §6) para cargar en VLC con
  network-caching alto en torrent, o vía proxy en archive.
- Instancia **única** en el proceso, dentro del `PlaybackService`.

### 2. `PlaybackService` (cambio mínimo)

Se reemplaza `ExoPlayer.Builder(...)` por `VlcPlayer(...)`. **Todo lo demás queda igual:**
`MediaSession`, notificación con carátula, controles de pantalla de bloqueo, segundo plano, botones
de auriculares, audio focus — funcionan contra la interfaz `Player`, no contra ExoPlayer. El swap a
Chromecast (que hoy vive en la UI, intercambiando el `MediaController` local por un `CastPlayer`) no
cambia.

### 3. `ArchiveCacheProxy` (caché en disco para archive.org)

Server HTTP local nuevo (`com.arkiv.player.playback` o `...torrent` reusando patrón), calcado de
[`TorrentStreamServer`](../../../app/src/main/java/com/arkiv/player/torrent/TorrentStreamServer.kt)
(ServerSocket + soporte Range). VLC pega a `http://127.0.0.1:<port>/...`; el proxy baja de
archive.org, **escribe a una caché LRU en disco (512 MB)** por URL y sirve desde disco en
seek/replay. Reemplaza al `CacheDataSource` de media3 que VLC no puede usar. Ciclo de vida atado a
la reproducción (arranca al cargar la fuente archive, se detiene al soltar el player).

### 4. `PlayerScreen` unificada

Una sola pantalla (se conserva `ui/player/`, se elimina `ui/torrent/TorrentPlayerScreen.kt`):

- **Renderiza** `VLCVideoLayout` (no más `PlayerView` de media3).
- **Transporte por el `MediaController`** para que playlist, markers y notificación queden
  coherentes con el service.
- **Controles Compose custom** = los "modernos" del reproductor de torrent (transporte central
  -10s/play/+10s, barra inferior con slider, fade auto-ocultar) — decisión: se usan estos, no los
  nativos de media3 `PlayerView`, por ser agnósticos del motor y consistentes. **Más** los extras
  de archive: editor de marcadores intro/outro, botones saltar intro/outro, zoom pinch, swipe-down
  a la lista de episodios.
- **Selección de audio/subtítulos:** el diálogo del torrent (pistas embebidas + OpenSubtitles),
  ahora disponible para ambas fuentes vía la interfaz de tracks del `VlcPlayer`.
- **Overlay de descarga** (peers/%/velocidad): solo si la fuente es torrent.
- **Cast (Chromecast) + DLNA:** se conservan igual; torrent usa la URL LAN, archive la
  `castUrl`/proxy.
- **TV D-pad:** se unifica el manejo de teclas (hoy casi idéntico en ambas pantallas).

### 5. Fuentes → `MediaItem` y ViewModel

- Se **unifica el `PlayerViewModel`** para armar `PlaylistData` en ambos casos:
  - **archive** = varios episodios de la sección (como hoy), URLs vía `ArchiveCacheProxy`.
  - **torrent** = 1 ítem; la resolución del `.torrent`/magnet y el arranque del
    `TorrentStreamServer` (hoy en `TorrentPlayerScreen`) pasan al ViewModel, dejando la pantalla
    agnóstica de la fuente.
- Cada `MediaItem` lleva un **tag** con: tipo de fuente (`archive`/`torrent`), markers
  (opening/ending), `castUrl`, y —si es torrent— referencia al progreso. `VlcPlayer` y la UI leen
  ese tag.

### 6. Navegación

Se colapsan las dos rutas (`torrentPlayer/{episodeId}` y `player/{episodeId}`) en **una sola**. La
fuente se decide por el episodio (`repository.torrentSourceForEpisode(ep) != null` → torrent). Se
actualizan [`ArkivRoot.kt`](../../../app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt) y
[`ArkivTvRoot.kt`](../../../app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt).

## Manejo de errores

- **VLC `EncounteredError`** (formato no soportado): overlay de error como hoy en torrent.
- **Fuente torrent sin peers / metadata**: mensajes existentes ("No se encontró ningún peer…").
- **Proxy archive caído / sin red**: el proxy propaga el error HTTP; la UI muestra el overlay de
  error. Si el proxy no levanta, fallback a URL directa de archive.org sin caché.
- **Bridge `VlcPlayer`**: cualquier excepción en el mapeo de estado se aísla con `runCatching` para
  no tumbar la `MediaSession`.

## Tests

- `VlcPlayer`: mapeo de eventos VLC → `State` (con un `MediaPlayer` mockeado): buffering→ready,
  end-reached→transición de pista, seek, play/pause.
- `ArchiveCacheProxy`: requests con Range, hit/miss de caché, evicción LRU al superar 512 MB.
- Resolución de fuente → tag de `MediaItem` (archive vs torrent, markers presentes).
- Los tests existentes (EpisodeFilePicker, MetadataParser, etc.) no se tocan.

## Migración / limpieza

- Se elimina `ui/torrent/TorrentPlayerScreen.kt` y los bits específicos de ExoPlayer.
- `PlaybackService` se mantiene, con motor VLC.
- `media3-ui` / `PlayerView` deja de usarse para render; la dependencia se puede quitar más
  adelante (no en el primer corte, para no romper `R.layout.arkiv_player_view` si se referencia).
- `media3-cast` y `media3-session` se quedan (cast + session siguen en media3).

## Riesgo principal y mitigación

El puente `SimpleBasePlayer` ↔ VLC es lo intrincado (`@UnstableApi`, casos borde de
seek/buffering/fin de pista/cambio de episodio). **Mitigación:** construir el `VlcPlayer` primero
con una UI mínima y validar reproducción de archive **y** torrent **antes** de fusionar toda la UI
y borrar lo viejo. Orden sugerido de implementación:

1. `VlcPlayer` + swap en `PlaybackService`, validado con la UI actual de archive apuntando a VLC.
2. `ArchiveCacheProxy` + integración de la fuente archive.
3. Unificar `PlayerViewModel` + resolución de fuente torrent en el VM.
4. Fusionar la UI en una sola `PlayerScreen` (controles + markers + tracks + overlays).
5. Unificar navegación y eliminar `TorrentPlayerScreen`.
6. Tests + limpieza de dependencias.
