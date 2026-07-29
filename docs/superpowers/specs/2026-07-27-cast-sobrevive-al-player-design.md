# La sesión de Chromecast sobrevive al salir del reproductor

Fecha: 2026-07-27 · Estado: diseño aprobado, sin implementar

## Objetivo

Que castear deje de estar atado a tener el reproductor abierto. Hoy, salir de la pantalla del
player **termina la sesión de Chromecast**: la TV deja de reproducir y el ícono de cast se apaga.

Además, una vez casteando, **todo lo que reproduzcas va a la TV** hasta que desconectes — como se
comportan Netflix y YouTube.

## La causa, verificada

`PlayerContent` crea su propio `CastPlayer` con `remember` y lo libera al desmontarse:

```kotlin
val castPlayer = remember { castContext?.let { CastPlayer(it) } }   // ~línea 386
…
onDispose {
    cp.setSessionAvailabilityListener(null)
    cp.release()                                                    // ~línea 833
}
```

Desensamblando `media3-cast-1.5.1.aar`, `CastPlayer.release()` termina con:

```
SessionManager.removeSessionManagerListener(...)
SessionManager.endCurrentSession(false)     ← acá muere la sesión
```

No es un efecto secundario ni una casualidad: `release()` corta la sesión por contrato de media3.
Y no se arregla borrando esa línea, porque el `CastPlayer` es un `remember` del composable: sin
liberarlo queda huérfano con su listener enganchado, y al volver a entrar se crea otro apuntando a
la misma sesión.

## Diseño

### `CastSessionManager` (nuevo, en `cast/`)

Pasa a ser dueño de lo que hoy vive dentro del composable:

- **El `CastPlayer`**, creado **una sola vez** desde el `castContext`. Nunca se llama a `release()`
  mientras viva la app.
- **El `SessionAvailabilityListener`**, instalado permanentemente.
- **`casting: StateFlow<Boolean>`**, que la pantalla consume en lugar de su `mutableStateOf` local.
- **El guardado de progreso mientras se castea** (ver [Progreso](#progreso)).

Expone una operación de carga:

```kotlin
fun setMedia(
    uri: String,
    mimeType: String,
    episodeId: String,
    title: String,
    subtitle: String,
    artworkUrl: String,
    startPositionMs: Long,
)
```

Hoy esa lógica está enterrada en `onCastSessionAvailable`, leyendo el `playlistRef` del composable.
Al mudarse necesita que se la pasen desde afuera: el manager no tiene playlist ni debe tenerla.

**Se arregla de arrastre un problema preexistente.** Hoy media3 **no** re-dispara
`onCastSessionAvailable` si la sesión ya estaba abierta cuando se construye el `CastPlayer`, así que
volver a entrar al reproductor con el cast andando dejaba `casting = false` —y con él, todas las
guardas que dependen de que ese flag sea exacto—. Con un único `CastPlayer` que vive siempre, el
listener recibe todas las sesiones y el problema desaparece solo.

**El único caso que el listener no cubre** es arrancar la app con una sesión ya viva (la mataste y
la reabriste casteando), porque ocurrió antes de que el manager existiera. Para eso, el manager
consulta `sessionManager.currentCastSession` en su construcción y se pone en `casting = true`.

### `AppGraph`

`val castSession: CastSessionManager` como lazy, junto a `presence` / `cloudSync` /
`nowPlayingPublisher`, y arrancado en el `init` igual que ellos. El `AppGraph` ya es dueño del
`castContext`, así que es su lugar natural.

### `PlayerScreen`

Desaparecen el `remember { CastPlayer(...) }`, el `DisposableEffect` que instala el
`SessionAvailabilityListener`, y el `release()` del `onDispose`.

- `castPlayer` pasa a ser `graph.castSession.player`
- `casting` pasa a leerse de `graph.castSession.casting`
- **`activePlayer` no cambia ni una letra** — sigue eligiendo entre esos dos.

### A dónde va la reproducción

Es el cambio de fondo. Hoy la carga siempre hace `controller.setMediaItems(...)` + `prepare()`
sobre el reproductor local. Pasa a bifurcarse:

- **`casting` activo** → `castSession.setMedia(...)`, y **el reproductor local no arranca**.
- **`casting` inactivo** → exactamente lo de hoy.

Esa bifurcación es el único punto donde la reproducción cambia de destino, y debe quedar como una
decisión explícita en un solo lugar, no repartida por la pantalla.

**Pero el local igual se carga, en pausa.** Si sólo se cargara al cast, el reproductor local
seguiría conteniendo el capítulo **anterior**: castear A, salir, abrir B y desconectar te reanudaría
**A**, que no es lo que estabas viendo. Así que al castear B también se hace `setMediaItems(B)` en el
`controller` **sin** `playWhenReady`, dejándolo cebado con el contenido correcto. De ese modo la
continuidad al desconectar —adelantar el local hasta la posición del receptor y reanudar— funciona
sin lógica adicional, porque el local ya tiene el medio que corresponde.

### Pausar y reanudar el local

Se mueve de sitio. Hoy vive dentro de `onCastSessionAvailable`; pasa a ser la pantalla observando
`casting`: al encenderse pausa el local, al apagarse lo reanuda.

**La continuidad de posición se conserva tal cual**: al empezar a castear, el receptor arranca en la
posición local; al terminar la sesión, el local se adelanta hasta donde llegó el receptor antes de
reanudar. Sin eso, el sondeo persiste la posición vieja encima de la buena a los pocos segundos.

### Progreso

**Sin esto, el progreso dejaría de guardarse en cuanto salgas del reproductor.** Hoy lo persiste el
bucle de sondeo de la pantalla; si la sesión sobrevive pero la pantalla no, castear un capítulo
entero desde el home guardaría la posición solo hasta el instante en que saliste.

Es la misma pérdida silenciosa que el resto del trabajo de esta jornada vino arreglando, así que
entra en alcance: **el manager persiste el progreso mientras castea**, usando el `episodeId` que le
llegó en `setMedia`. Ésa es la razón por la que necesita el repositorio y no es un mero contenedor.

Mientras el reproductor está abierto, ambos caminos escriben la misma posición del mismo player, así
que coinciden; no hace falta arbitrar entre ellos.

## Bordes

| Situación | Comportamiento |
|---|---|
| Desconectar el cast fuera del reproductor | Solo apaga `casting`. No se busca un player local que no existe |
| Sin Google Play Services | `castContext` es null → el manager no se crea → todo queda como hoy |
| Arrancar un torrent nuevo mientras se castea | `TorrentEngine` detiene el stream anterior, pero en ese mismo acto se carga el medio nuevo al cast: consistente |
| Volver a entrar al reproductor casteando | El manager ya tenía `casting = true`; la pantalla lo lee y no arranca el local |

## Límite conocido

**Si matás la app, el stream del torrent muere con ella.** El servidor LAN vive en el proceso, así
que el Chromecast se queda sin fuente a mitad de capítulo. Evitarlo exigiría un servicio en primer
plano, que es otro proyecto. Se documenta, no se esconde.

Verificado que **salir del reproductor NO detiene el stream**: `PlayerScreen` no llama a
`stopStream()` en ningún lado. Sin eso, todo este diseño no tendría sentido.

## Fuera de alcance

Que la barra del miniplayer refleje el Chromecast. Este trabajo es su prerrequisito —el `CastPlayer`
y el estado de casteo quedan accesibles fuera de la pantalla—, pero la barra es su propio spec.

## Pruebas

Hay una pieza genuinamente aislable y va con test JVM: **la decisión de a dónde va la reproducción**
es función pura de `casting` y de la fuente, y merece cubrirse — es el punto donde un error manda el
capítulo al aparato equivocado.

El resto es cableado de Android y del SDK de Cast; se verifica en dispositivo:

1. Castear y **salir al home**: la TV sigue reproduciendo y el ícono de cast sigue encendido.
2. Abrir **otro capítulo** estando casteando: también va a la TV, y el celu no empieza a sonar.
3. **Volver a entrar** al reproductor: la app sabe que seguís casteando (controles de cast, no locales).
4. **Desconectar** desde el botón de cast: el local reanuda donde llegó el receptor, sin retroceder.
5. Ver un capítulo entero **fuera del reproductor** y comprobar que **el progreso quedó guardado**.
6. Sin sesión de cast, nada de lo anterior cambia el comportamiento normal del reproductor.

## Archivos

**Nuevos:** `cast/CastSessionManager.kt` y su test JVM de la decisión de destino.

**Modificados:** `AppGraph.kt` (wiring), `ui/player/PlayerScreen.kt` (consume el manager, bifurca la
carga, observa `casting`).
