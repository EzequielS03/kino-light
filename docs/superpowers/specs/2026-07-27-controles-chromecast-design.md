# Controles de reproducción mientras se castea a Chromecast

Fecha: 2026-07-27 · Estado: diseño aprobado, sin implementar

## Objetivo

Poder pausar, reanudar y hacer seek desde la app mientras se castea a un Chromecast. Hoy no se
puede: al conectar la sesión, el reproductor **esconde todos sus controles** y muestra solo un
cartel estático "Reproduciendo en Chromecast" en el centro de la pantalla.

## El problema, concretamente

`PlayerContent` maneja la reproducción a través de `controller`, el `MediaController` del
`VlcPlayer` local. Cuando arranca una sesión de cast:

- `SessionAvailabilityListener.onCastSessionAvailable` pausa el player local, carga la URL en
  `castPlayer` y pone `casting = true`.
- Cuatro condiciones de la UI llevan `!casting`. La de la visibilidad de los controles apaga la
  fila entera de transporte.
- El bucle de 500ms y el `Player.Listener` siguen leyendo `controller`, que ya no es lo que suena.

Resultado: la app queda sin controles, sin barra de progreso, y **sin guardar el progreso** —
porque el guardado cuelga de ese mismo bucle y depende de `controller.isPlaying`.

## La simetría que hace esto barato

`CastPlayer` (media3) y el `MediaController` local **implementan los dos la interfaz `Player`**:
`play()`, `pause()`, `seekTo()`, `currentPosition`, `duration`, `isPlaying`, `addListener`. La UI
de controles ya está escrita contra esa API.

Por eso **no se escribe UI nueva**: alcanza con cambiar a qué `Player` le habla.

## Diseño

### La indirección

Una sola variable derivada en `PlayerContent`:

```kotlin
// El player que estamos manejando: el del Chromecast mientras haya sesión, el local si no.
// `?: controller` cubre el caso sin Google Play Services (castContext y castPlayer nulos).
val activePlayer: Player = if (casting) castPlayer ?: controller else controller
```

Tres lugares pasan de `controller` a `activePlayer`:

1. El `DisposableEffect` que instala el `Player.Listener` — **y su clave** pasa a ser
   `activePlayer`. Así, al conectar o desconectar el cast, el listener se desengancha del player
   viejo y se engancha al nuevo por el propio ciclo de vida del efecto, sin lógica adicional.
2. El bucle de 500ms que lee `currentPosition` / `duration` y dispara el guardado de progreso.
3. Los callbacks de los botones de transporte (play/pausa y seek).

Todo lo que **no** es transporte sigue hablando con `controller` o con `PlaybackEngine.vlc` según
corresponda: carga de la playlist, `setMediaItems`, pistas de audio y subtítulos, render de video.

### Los controles dejan de esconderse — pero no todos

Se quita `!casting` de la condición de visibilidad del bloque de controles. **Ojo: ese bloque no
contiene solo el transporte.** Dentro viven también el botón CC/audio y "Siguiente episodio", que
no deben aparecer mientras se castea. Así que destapar el bloque entero no alcanza: los elementos
que dependen del motor local llevan **su propia guarda** `!casting` dentro del bloque ya visible.

El cartel "Reproduciendo en Chromecast" se conserva, pero movido a una etiqueta superior en lugar
de ocupar el centro, para no tapar los controles.

### Lo que sigue oculto mientras se castea, y por qué

| Elemento | Dónde | Motivo |
|---|---|---|
| Botón CC / pistas de audio | Dentro del bloque de controles → guarda propia | No pasa por la API de `Player`: va contra `PlaybackEngine.vlc`, el reproductor local. El receptor de Chromecast maneja sus propias pistas. Mostrarlo ofrecería opciones que no harían nada |
| Siguiente / anterior episodio | Dentro del bloque de controles → guarda propia | Ver [Limitación conocida](#limitación-conocida) |
| Superficie de video y UI de buffering del torrent | Guardas `!casting` ya existentes | Se conservan tal cual: protegen cosas del reproductor local que no aplican mientras se castea |

Los dos marcadores **no son iguales**, aunque a primera vista lo parezcan:

- **"Saltar intro" sí funciona**: hace `seekTo(openingEndMs)`, que el `CastPlayer` soporta igual.
- **"Saltar outro" no**: hace `seekToNextMediaItem()`, y el cast tiene un solo ítem cargado. Se
  oculta al castear, por el mismo motivo que siguiente/anterior episodio.

Velocidad y zoom viven en la barra superior, fuera de este bloque; el plan debe verificar si hoy
quedan visibles al castear y, si aplican solo al reproductor local, guardarlas igual.

### Limitación conocida

Al conectar la sesión, el cast carga **un solo** ítem (`cp.setMediaItem`, en singular), así que no
hay lista por la que avanzar. Y navegar al siguiente capítulo mientras se castea cargaría el
episodio nuevo en el reproductor **local**, dejando el Chromecast con el anterior — porque el
camino de carga del cast solo se dispara en `onCastSessionAvailable`, no al cambiar de medio.

Se ocultan siguiente/anterior mientras se castea: es preferible a dejar un botón que hace algo
incorrecto. Hacer que funcionen exige re-disparar la carga del cast ante cada cambio de medio, que
es otra pieza con más riesgo y queda **explícitamente fuera de alcance**.

## Bordes

- **Sin Google Play Services:** `castContext` y `castPlayer` son null; `casting` nunca se pone en
  true y el `?: controller` mantiene el comportamiento actual intacto.
- **La sesión se corta sola:** `onCastSessionUnavailable` ya pone `casting = false` y reanuda el
  player local. Como el `DisposableEffect` tiene clave `activePlayer`, el listener se re-engancha
  al local por sí solo.
- **Progreso:** empieza a guardarse también mientras se castea. Es un arreglo, no un efecto
  secundario a vigilar: hoy castear te hace perder dónde ibas.

## Fuera de alcance

- Que la barra del miniplayer refleje el Chromecast al salir del reproductor. Eso exige sacar el
  `CastPlayer` a un singleton y darle a la barra una abstracción de fuente (TV o Cast), y es un
  trabajo aparte.
- DLNA. Su control de play/pausa ya existe dentro del reproductor; su problema es otro (nada
  consulta la posición del renderer).

## Pruebas

**No hay tests unitarios honestos que escribir acá.** Es cableado de Compose contra un dispositivo
real; lo único aislable sería la condición de una línea que elige el player, y un test sobre eso
sería vacuo. Inventar una suite para aparentar cobertura sería peor que no tenerla.

La verificación es en dispositivo, con un Chromecast:

1. Al castear aparecen los controles (antes no aparecía ninguno).
2. Pausa y play mueven el Chromecast de verdad.
3. La barra de progreso avanza, y un seek cae donde se toca.
4. Al cortar la sesión, los controles vuelven a manejar el celu.
5. Al salir, la posición quedó guardada (antes se perdía).
6. Audio/subtítulos y siguiente/anterior no aparecen mientras se castea.

## Archivos

Solo se modifica `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`.
