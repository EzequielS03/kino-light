# Miniplayer remoto del TV (barra "Reproduciendo ahora" en el celu)

Fecha: 2026-07-27 · Estado: diseño aprobado, sin implementar

## Objetivo

Cuando el TV está reproduciendo algo, el celu muestra una barra fija en la parte inferior —sobre
la navegación Inicio/Descargas/Ajustes— con la carátula, el nombre de la serie, el episodio, el
progreso y los controles de transporte. Al tocarla se abre una pantalla "Reproduciendo ahora" con
un slider arrastrable y los mismos controles en grande.

Aplica **solo cuando hay un TV pareado**. Controlar la reproducción local del propio celu queda
fuera de alcance (ver [Fuera de alcance](#fuera-de-alcance)).

## Contexto que condiciona el diseño

- **La LAN entre celu y TV no funciona en la red del usuario** (aislamiento de clientes en el
  router): el celu no alcanza al Fire TV ni por ping ni por TCP. Todo el remoto va por la nube
  (PocketBase en `db.comparadorinternet.co` tras Cloudflare Tunnel).
- **Los SSE largos por Cloudflare son el punto frágil** del sistema; ya hubo que forzar HTTP/1.1 y
  añadir un poll de respaldo de 3s en `CloudTransport` para que los comandos llegaran.
- **El estado de reproducción está atrapado en el composable**: `positionMs`, `durationMs` e
  `isPlaying` son estado local de `PlayerContent` (`ui/player/PlayerScreen.kt:385-387`), no hay
  ningún flujo observable fuera de la pantalla.
- **Sí hay singletons vivos del servicio**: `PlaybackEngine.vlc` y `NowPlaying.episodeId`
  (`playback/PlaybackService.kt:14-27`). Son el punto de lectura correcto: no obligan a tocar
  `PlayerScreen` ni a hoistear estado de Compose.
- **El TV ya sabe abrir el player desde cualquier lado** al recibir un comando `play`
  (colector `incomingPlay` en `ui/tv/ArkivTvRoot.kt`). Siguiente/anterior capítulo puede reusar ese
  camino en vez de inventar resolución de fuente nueva.

## Arquitectura

El TV **publica fotos del estado**; el celu **extrapola el reloj localmente** entre fotos. Ese es el
núcleo del diseño: hace que la barra avance suave a 60fps aunque la foto llegue cada 3-10s, que es
lo único viable yendo por nube.

```
   TV                                                celu
   ──                                                ────
   PlaybackEngine.vlc ──┐
   NowPlaying.episodeId ┤
   ArkivRepository ─────┘
            │
            ▼
   NowPlayingPublisher ──PATCH devices/{tv}──▶ [ PocketBase ] ◀──poll 3s── TvNowPlayingRepository
                              nowPlaying(json)                                      │
                                                                                    ▼
   TransportRouter ◀──commands (SSE + poll)──── [ PocketBase ] ◀──create──  ExtrapolatedClock
            │                                                                       │
            ▼                                                                       ▼
   aplica pause/resume/seek/next/prev                              MiniPlayerBar · NowPlayingScreen
```

### Lado TV — `NowPlayingPublisher`

Corrutina en el `AppGraph`, independiente de la UI. Observa `PlaybackEngine.vlc` +
`NowPlaying.episodeId` y hace `PATCH devices/{miRecord} { nowPlaying: <json> }`:

- ante cambios de evento: `state` (play/pausa/buffer), cambio de `episodeId`, duración ya conocida;
- **latido cada 10s** mientras reproduce, para corregir deriva de la extrapolación;
- `nowPlaying: null` al salir del player o al terminar;
- **throttle mínimo de 1s** entre PATCHes, y se omite el PATCH si el payload es idéntico al anterior.

Enriquece el payload con `repository.headerInfo(episodeId)` (título de serie + etiqueta de episodio)
y con `nextEpisode`/`previousEpisode` para `hasNext`/`hasPrev`.

**Carátula:** se resuelve del item de la biblioteca por `episodeId`, **no** de
`PlayerData.artworkUrl`, que está poblado solo para archive y vale `""` para torrent y web
(`ui/player/PlayerViewModel.kt:141`, `:178`).

### Lado celu — `TvNowPlayingRepository`

Expone `StateFlow<TvNowPlaying?>`. Hace poll del record `kind='tv'` **cada 3s**, y solo si hay TV
pareado (`remoteController.tvPaired`) y la app está en primer plano. En background duerme: cero
tráfico y cero batería.

Con varios TV en la cuenta se reusa el desempate que ya hace `RemoteController.resolveTvId`
(online primero, luego `lastSeen` más reciente).

**No se abre un SSE nuevo.** Con reloj extrapolado el poll de 3s es invisible en la barra de
progreso, y la latencia que sí se percibe —la de los botones— se resuelve con UI optimista. Meter un
tercer stream largo por Cloudflare añadiría el modo de fallo conocido sin comprar nada.

### `ExtrapolatedClock`

```
posiciónMostrada = min(foto.positionMs + (ahora − recibidaEn), foto.durationMs)   si state == PLAYING
posiciónMostrada = foto.positionMs                                                 si PAUSED/BUFFERING
```

`recibidaEn` es el instante del **reloj local del celu** en que llegó la foto. Nunca se comparan
timestamps entre dispositivos, así que no hace falta que el Fire Stick y el celu tengan la hora
sincronizada.

**Consecuencia aceptada:** la barra va hasta ~3s por detrás del TV. En un capítulo de 24 min eso es
0,2% de la barra. Corregirlo exigiría sincronizar relojes; no vale la pena.

### Comandos celu → TV

Reusan la colección `commands` y todo el `TransportRouter` existente, con tipos nuevos:

| type | payload | efecto en el TV |
|---|---|---|
| `pause` | — | `PlaybackEngine.vlc.pause()` |
| `resume` | — | `PlaybackEngine.vlc.play()` |
| `seek` | posición **absoluta** en ms | `seekTo(ms)` |
| `next` / `prev` | — | resuelve episodio y dispara el mismo camino interno de `incomingPlay` |

Los ±10s los calcula el celu sobre la posición extrapolada y manda un **absoluto**: el comando queda
idempotente y no acumula deriva si se repite o llega tarde.

`pause`/`resume`/`seek` se aplican sobre `PlaybackEngine.vlc` posteando al hilo principal (media3
`SimpleBasePlayer` exige su application looper). Se ignoran si `NowPlaying.episodeId` es null (TV a
medio cargar).

**Por qué no se reusa la inyección de teclas.** Hoy los comandos remotos entran como `KeyEvent` de
Android a nivel de Activity (`ui/tv/ArkivTvRoot.kt:55-62`), lo que funciona para la cruceta porque
depende del foco. Un seek absoluto y un salto de capítulo son semánticos, no direccionales: van
directo al player.

### UI optimista

Al tocar un control, la barra cambia de inmediato y **fija** ese estado ~2,5s, o hasta que llegue
una foto que ya lo refleje. Sin esto, un poll en vuelo con la foto anterior revertiría el botón a la
vista medio segundo después de tocarlo.

Si el comando falla (`createRecord` tira), se revierte **en silencio**, sin toast: la siguiente foto
es la fuente de verdad, y el indicador de desconexión ya cuenta la historia.

## Contrato de datos

### `devices.nowPlaying` (json)

```json
{
  "v": 1,
  "episodeId": "…",
  "itemId": "…",
  "kind": "ARCHIVE|TORRENT|WEB",
  "title": "Dandadan",
  "subtitle": "T1E5 · El abuelo turbo",
  "posterUrl": "https://…",
  "positionMs": 754000,
  "durationMs": 1440000,
  "state": "PLAYING|PAUSED|BUFFERING",
  "hasNext": true,
  "hasPrev": true,
  "at": "2026-07-27T18:04:12Z"
}
```

`v` permite ignorar payloads de una versión futura sin romper. `at` es el reloj del TV y se usa
**solo** para deduplicar fotos idénticas y para depurar — nunca para la extrapolación. `null` (o
campo ausente) significa "el TV no está reproduciendo nada".

El payload queda bajo 1KB, muy lejos del límite del campo.

### Codec

`NowPlayingCodec` sigue la convención de `cloudsync/SyncMappers.kt`: `nowPlayingToFields(state)` y
`recordToNowPlaying(json)`, con la lógica pura separada del acceso a `org.json` para que sea
testeable en JVM (mismo criterio que `PlayPayloadCodec`, ver `remote/RemoteModels.kt:14-18`).

## Interfaz

### La barra

Vive en el slot `bottomBar` de `ui/ArkivRoot.kt:219`, que pasa de ser el `NavigationBar` a una
`Column { MiniPlayerBar?; NavigationBar }`. Se muestra bajo la misma condición `isTab` que la nav
bar, así que aparece en Inicio/Descargas/Ajustes y nunca en detalle, búsqueda ni el player.

**Visible** cuando `nowPlaying != null` — o sea, el TV tiene algo cargado, reproduciendo o en pausa.
**Se oculta** cuando el TV sale del player, queda offline, o la última foto lleva >45s sin refrescarse
estando en play. A los 25s sin refresco aparece un indicador tenue de desconexión. Preferimos que la
barra desaparezca antes que mienta sobre lo que pasa en el TV.

> Umbrales subidos de 10s/30s a 25s/45s en la revisión final: con 10s, el latido de 10s del publisher
> más los 3s de fase del poll más los viajes de red cruzaban el umbral en reproducción sana y la
> barra mostraba "Sin conexión" cada ~20s. El invariante es `WARN_MS > HEARTBEAT_MS + POLL_MS` con
> margen.

Alto ~80dp, dos filas:

- **Fila 1 (44dp):** miniatura 48dp redondeada · nombre de la serie (1 línea) sobre
  `T2E5 · título del capítulo` en gris · a la derecha `12:34 / 24:01`.
- **Fila 2 (36dp):** cinco controles centrados a 40dp — ⏮ · −10 · ▶/⏸ (destacado en `ArkivRed`) ·
  +10 · ⏭.
- **Borde inferior:** línea de progreso de 2dp pegada a la nav bar.

Cuando `state == BUFFERING` el botón central muestra un spinner en vez del ícono de play. En el caso
del usuario (torrents) eso pasa seguido, y sin ese estado la barra parecería congelada sin
explicación.

Decisiones deliberadas:

- **La línea de progreso no se arrastra.** Una zona de arrastre de 2dp pegada a la barra de
  navegación es una trampa de toques accidentales. Para precisión están los ±10s y el slider de la
  pantalla grande.
- **⏮/⏭ se deshabilitan, no se ocultan**, cuando `hasNext`/`hasPrev` son falsos. Si desaparecieran,
  los otros tres controles cambiarían de posición entre una película y una serie.
- **Sin swipe-para-descartar.** La barra solo existe mientras el TV reproduce, que es justo cuando
  la querés. Costo asumido: 80dp de barra + 80 de nav = 160dp de cromo inferior en ese rato.

### Pantalla "Reproduciendo ahora"

`ui/remote/NowPlayingScreen.kt`, ruta `nowplaying`, se abre al tocar la barra.

Póster grande sobre un degradado tomado de la carátula, título y subtítulo, **slider arrastrable**
con tiempos a los lados (al soltar manda un `seek` absoluto; mientras arrastrás se congela la
extrapolación para que no pelee con el dedo), la misma fila de cinco controles a 56/64dp, un chip
"Reproduciendo en <nombre del TV>" y un botón secundario **"Control remoto"** que navega al
`RemoteScreen` con cruceta ya existente. El D-pad no se reimplementa.

## Bordes y fallos

| Situación | Comportamiento |
|---|---|
| No hay TV pareado | No hay poll ni barra. Se reusa `remoteController.tvPaired` |
| Poll falla / sin red | Se conserva la última foto y se sigue extrapolando; indicador a los 25s, ocultar a los 45s |
| TV apagado a mitad de capítulo | El `at` del payload acota la vida de la foto a 2 min: sin eso, `nowPlaying` queda poblado para siempre y la barra sale viva en el arranque en frío |
| Comando falla | Se revierte el estado optimista en silencio, sin toast |
| Carátula vacía en el payload | El celu la busca en su biblioteca local por `episodeId`; si tampoco, placeholder |
| Varios TV en la cuenta | Desempate ya existente: online primero, luego `lastSeen` más reciente |
| Comando con el TV a medio cargar | Se ignora si `NowPlaying.episodeId` es null |
| App en background | Poll dormido; al volver al frente, poll inmediato |
| Seek en torrent sin bufferizar | Se aplica igual; VLC bufferea y el estado `BUFFERING` lo comunica |

**Seguridad:** `nowPlaying` viaja en el record `devices`, bajo las mismas reglas por cuenta ya
verificadas (`docs/pocketbase/collections.md`). No agrega superficie de ataque.

## Fuera de alcance

Que la barra controle la reproducción **local del celu**. `TvNowPlayingRepository` no hace supuestos
que impidan añadirlo después, pero no se implementa ahora.

## Cambios en el servidor (PocketBase)

1. `devices`: campo nuevo `nowPlaying` (json, opcional, maxSize 4000).
2. `commands`: ampliar el select `type` con `pause`, `resume`, `seek`, `next`, `prev`.

Actualizar `docs/pocketbase/collections.md` en el mismo cambio.

## A verificar antes de implementar

- **`commands.type` es un `select`** que el doc lista solo como `play`/`key`, pero el código ya envía
  `subprefs` y `webquality` (`remote/CloudTransport.kt:39-40`). O el select se amplió en el servidor
  y el doc quedó viejo, o esos dos comandos fallan callados. Hay que mirarlo en el admin: define si
  los tipos nuevos requieren cambio de esquema o no.
- **`previousEpisode` no existe** en `ArkivRepository` (solo `nextEpisode`, en
  `data/ArkivRepository.kt:615-623`). Hay que agregarlo espejando la lógica de `section`.
- Si `org.json` es usable en los tests JVM existentes, o si el codec debe evitarlo por completo.

## Pruebas

JVM puras, sin dependencias de Android, en `app/src/test/java/com/arkiv/player/remote/`:

- `NowPlayingCodec` — ida y vuelta, campos ausentes, payload con `v` mayor a la soportada.
- `ExtrapolatedClock` — avanza con reloj inyectado; se capa en `durationMs`; **no** avanza en
  `PAUSED`/`BUFFERING`; se congela mientras se arrastra el slider.
- `OptimisticState` — se fija pausa; llega una foto vieja (no revierte); llega una nueva (la adopta);
  expira a los 2,5s.
- `previousEpisode` — el método nuevo del repositorio.

Verificación en dispositivo (celu + Fire Stick, con el fix-loop habitual): latencia percibida de los
botones, que la barra no revierta sola, y next/prev **tanto en serie de archive como en pack de
torrent**, que son caminos de resolución distintos.

## Archivos

**Nuevos**

- `remote/NowPlayingModels.kt` — `TvNowPlaying`, `PlaybackState`
- `remote/NowPlayingCodec.kt`
- `remote/NowPlayingPublisher.kt` (TV)
- `remote/TvNowPlayingRepository.kt` (celu)
- `ui/remote/ExtrapolatedClock.kt`
- `ui/remote/MiniPlayerBar.kt`
- `ui/remote/NowPlayingScreen.kt`

**Modificados**

- `remote/RemoteController.kt` — `sendPause/sendResume/sendSeek/sendNext/sendPrev` + flujos entrantes
- `ui/tv/ArkivTvRoot.kt` — colector de comandos de transporte
- `ui/ArkivRoot.kt` — `bottomBar` y ruta `nowplaying`
- `data/ArkivRepository.kt` — `previousEpisode`
- `AppGraph.kt` — wiring del publisher (TV) y del repositorio (celu)
- `docs/pocketbase/collections.md`
