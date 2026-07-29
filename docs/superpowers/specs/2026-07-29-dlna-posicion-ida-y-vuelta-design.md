# DLNA con memoria: arrancar donde ibas, y que lo visto en la TV deje huella

Fecha: 2026-07-29 · Estado: diseñado (sin implementar)

## Objetivo

Hoy, mandar un capítulo a la TV por DLNA lo arranca **desde cero**, y verlo entero ahí **no deja
ninguna marca** en la app. Dos caras del mismo problema: la posición no cruza en ninguna dirección.

1. **Ida** — el receptor arranca en el minuto donde venías viendo.
2. **Vuelta** — mientras la TV reproduce se guarda el progreso, y al detener, el reproductor local
   retoma donde quedó la TV.

## El problema, verificado

`DlnaController` **no tiene ninguna acción de posición**. Su API completa es `setUrlAndPlay`,
`playRawUrl`, `play`, `pause` y `stop`; no hay `Seek` ni forma de preguntarle nada al receptor.

En el handoff (`ui/player/PlayerScreen.kt`, ~2088) se hace `controller.pause()` y después
`setUrlAndPlay(...)`, que por dentro es `SetAVTransportURI` + `Play`. La posición local nunca se lee.

El progreso tampoco se guarda, aunque no hay ninguna regla que lo prohíba explícitamente. Las dos
llamadas a `vm.saveProgress(...)` —la periódica (~893) y la de salida (~1113)— están condicionadas a
`activePlayer.isPlaying`, y el handoff deja el player **local en pausa** (`controller.pause()`). No
existe un "player DLNA": `activePlayer` sigue siendo el local, pausado, así que ninguna de las dos
dispara mientras la TV reproduce.

Y "Detener" (~2053) solo corta el receptor y pone `dlnaActive = null`, sin traerse su posición.

Es una asimetría con el Chromecast, que sí arranca donde ibas: ahí se arma
`castRequestFor(pl, idx, pl.startPositionMs)`.

### Lo difícil ya está hecho

Para que un receptor DLNA pueda saltar, el servidor tiene que servir rangos de bytes.
`DlnaProxyServer` **ya lo hace bien**: reenvía el header `Range`, responde `206 Partial Content` con
su `Content-Range` y anuncia `Accept-Ranges: bytes`. Esa suele ser la pieza que falta en
implementaciones caseras; acá no hay que tocarla.

## Decisión de diseño: cuándo mandar el Seek

**Play → sondear `GetTransportInfo` hasta `PLAYING` → Seek → verificar.**

Casi ningún renderer acepta saltar con el transport en `STOPPED` o `TRANSITIONING`: necesitan el
media cargado. Las alternativas se descartaron:

- *Esperar un tiempo fijo tras el Play* — el tiempo de carga depende de la red y del receptor, así
  que falla en silencio con archivos pesados. Peor: no distingue "no lo soporta" de "llegué
  temprano", y esa distinción es la que sostiene el aviso al usuario.
- *Seek antes del Play* — más limpio en teoría, rechazado por la mayoría de los receptores.

Sondear es lo único que permite **verificar** el resultado, y de ahí sale todo el manejo de errores.

## Diseño

### Acciones SOAP nuevas en `DlnaController`

Tres, todas sobre el helper `soap()` que ya existe:

| acción | para qué |
|---|---|
| `seek(device, ms)` | `Seek` con `Unit=REL_TIME`, `Target=HH:MM:SS` |
| `transportState(device)` | `GetTransportInfo` → saber cuándo llegó a `PLAYING` |
| `positionMs(device)` | `GetPositionInfo` → el sondeo de la vuelta |

Requiere un cambio previo: hoy `soap()` **descarta la respuesta**. Las dos últimas necesitan leer el
body, así que `soap()` pasa a devolverlo (o null si falló).

### Dos piezas puras

Siguiendo el patrón de `ResumePolicy` y `VoutTracker` — la lógica sale de la capa de red para poder
probarse sin device:

- **`DlnaTime`** — formatear ms a `HH:MM:SS` y parsear el `RelTime` que devuelve el receptor.
- **`DlnaProgressPolicy`** — la regla de no retroceder (ver más abajo).

El parseo del XML de respuesta (sacar `CurrentTransportState` y `RelTime`) también vive aparte y se
prueba con respuestas de ejemplo.

### La ida

1. Capturar `contentPositionMs()` **antes** del `controller.pause()`.
2. Pasarla por **`ResumePolicy.startPosition(...)`**: menos de 10 s o casi al final → arranca en 0.
   Se reusa a propósito la regla del reproductor local, para no inventar una segunda definición de
   "dónde ibas".
3. `SetAVTransportURI` + `Play`, como hoy.
4. Sondear `GetTransportInfo` cada 500 ms hasta `PLAYING`, con tope de 8 s.
5. Si llegó y la posición objetivo es > 0 → `Seek`.
6. **Verificar** con `GetPositionInfo`: si la posición reportada no se acerca al objetivo (tolerancia
   10 s), el seek no prendió.

### La vuelta

Mientras `dlnaActive != null`, sondear `GetPositionInfo` cada 5 s y guardar por la **misma vía que el
reproductor local**, `vm.saveProgress(episodeId, pos, dur)`, para que "visto" y "continuar viendo" se
recalculen igual que siempre. Como el guardado de hoy exige `activePlayer.isPlaying` y durante el
DLNA el local está en pausa, este sondeo es una vía aparte: no se relaja aquella condición, porque
protege el caso de guardar posiciones de un player que no está reproduciendo.

Al apretar **Detener**: se aplica la última posición conocida al player local con un `seekTo` y **se
lo deja en pausa**, igual que hoy al volver del DLNA. La única diferencia es dónde quedó la aguja; que
arranque o no lo sigue decidiendo el usuario.

### El riesgo de datos, y la regla que lo evita

Si el receptor ignora el `Seek` y arranca en cero, el sondeo empezaría a guardar "minuto 1" **encima**
de la marca real del minuto 12: el feature que arregla la posición te la borraría.

`DlnaProgressPolicy` lo impide: **el progreso guardado solo avanza**. Nunca se sobrescribe con una
posición menor a la que ya había. Si después se ve el capítulo entero en la TV, al pasar la marca
vieja se guarda normal. Además se avisa con un toast en cuanto se detecta que el seek falló.

## Manejo de errores

Todo degrada al comportamiento actual, que es "arranca en cero":

- Cualquier acción SOAP que falle se ignora y se sigue — `soap()` ya es a prueba de fallos.
- Timeout esperando `PLAYING` → no se manda `Seek` → toast.
- `Seek` mandado pero no verificado → toast, y el guardado no retrocede.

Lo único visible que se agrega es el toast. No hay camino en el que esto deje al usuario peor que hoy.

## Torrent

Funciona igual, sin caso especial. El servidor de streaming del torrent ancla la descarga secuencial
en el punto pedido (`setSequentialRange`) apenas alguien pide ese rango, así que un salto en frío
prioriza solo las piezas que hacen falta.

## Testing

Unitarios, sin red ni device:

- `DlnaTime`: formato y parseo, con bordes (0, más de una hora, valores mal formados).
- `DlnaProgressPolicy`: no retrocede, sí avanza, primera escritura.
- Parseo del XML de `GetTransportInfo` y `GetPositionInfo` con respuestas de ejemplo.

## Qué no se puede testear sin la TV

La conversación real con el receptor: si acepta `REL_TIME` o exige `ABS_TIME`, si respeta el `Seek`,
cuánto tarda en llegar a `PLAYING`. El seek es la parte más frágil de DLNA y los receptores varían
mucho.

Por eso el plan **no implementa a ciegas el fallback a `ABS_TIME`**: primero se mide contra la TV real
y se agrega solo si hace falta. El tope de 8 s y la tolerancia de 10 s son puntos de partida, sujetos
a lo que se observe en device.

⚠️ Nota de contexto: el Fire TV Stick de pruebas viene inestable (se cayó de la red dos veces el
2026-07-28, arranca con ~700 MB en swap y produjo tres ANR). Ante un fallo en device, descartar
primero el aparato.
