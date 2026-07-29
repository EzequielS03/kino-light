# Controles de Chromecast — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poder pausar, reanudar y hacer seek desde la app mientras se castea a un Chromecast.

**Architecture:** `CastPlayer` y el `MediaController` local implementan los dos la interfaz `Player` de media3, así que alcanza con una variable derivada que diga cuál se está manejando. Los controles que ya existen empiezan a manejar el Chromecast sin escribir UI nueva.

**Tech Stack:** Kotlin, Jetpack Compose, media3 (`MediaController`, `CastPlayer`), libVLC.

**Spec:** `docs/superpowers/specs/2026-07-27-controles-chromecast-design.md`

## Global Constraints

- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. **Nunca** agregar `Co-Authored-By`.
- **Nunca `git add -A`.** Este repo trackea artefactos en `app/build/`. Listar archivos explícitamente. Además: `git add <archivo>` stagea el archivo **entero** — revisar `git diff --cached` línea por línea antes de commitear, porque puede haber trabajo de otra sesión en el mismo archivo.
- **Un solo archivo se modifica en todo el plan:** `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`.
- **No hay tests unitarios en este plan, y es deliberado.** Es cableado de Compose contra un dispositivo real; lo único aislable sería la condición de una línea que elige el player, y un test sobre eso sería vacuo. La verificación es en dispositivo (Task 3). **No inventar una suite para aparentar cobertura.**
- **Comando de compilación:** `./gradlew :app:assembleDebug` — debe pasar en cada tarea.
- **Suite existente:** `./gradlew :app:testDebugUnitTest` debe seguir en verde (no debe romperse nada).
- **Idioma:** comentarios y textos de UI en español.
- **Los números de línea de este plan son de referencia y pueden haber corrido.** Ubicar cada edición por el texto que la rodea, no por el número. Leer el archivo antes de editarlo.

---

## Estructura

Un solo archivo, `PlayerScreen.kt`, con tres grupos de cambios:

| Grupo | Qué hace |
|---|---|
| La indirección | Una variable derivada `activePlayer` y los tres lugares que leen o mandan transporte |
| La UI | Destapar el bloque de controles y guardar los elementos que solo aplican al reproductor local |
| Verificación | En dispositivo, con un Chromecast (tarea del usuario) |

---

## Task 1: La indirección `activePlayer`

Hace que el estado mostrado (posición, duración, play/pausa) y los comandos de transporte apunten al Chromecast mientras hay sesión. Todavía **no se ve nada**: los controles siguen ocultos hasta la Task 2. El cambio observable de esta tarea es que el progreso empieza a guardarse al castear.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

**Interfaces:**
- Produces: `val activePlayer: Player` dentro de `PlayerContent`, consumido por la Task 2.

- [ ] **Step 1: Declarar la indirección**

Ubicar el bloque de declaraciones de estado que empieza con `val castPlayer = remember { castContext?.let { CastPlayer(it) } }` (~línea 386) y termina con `var casting by remember { mutableStateOf(false) }` (~línea 394).

Inmediatamente **después** de la línea de `casting`, agregar:

```kotlin
    // El player que estamos manejando ahora mismo: el del Chromecast mientras haya sesión, el
    // local si no. Ambos implementan Player, así que los controles no necesitan saber cuál es.
    // El `?: controller` cubre el caso sin Google Play Services (castContext y castPlayer nulos).
    val activePlayer: Player = if (casting) castPlayer ?: controller else controller
```

- [ ] **Step 2: Reapuntar el listener de transporte**

Ubicar el `DisposableEffect(controller)` cuyo comentario previo dice `// Índice/buffering/estado del transporte, por el controller.` (~línea 570).

Reemplazar el bloque completo por:

```kotlin
    // Índice/buffering/estado del transporte. Sigue al player activo: al conectar o desconectar
    // el cast, el efecto se relanza solo y el listener se re-engancha al que corresponda.
    DisposableEffect(activePlayer) {
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        isPlaying = activePlayer.isPlaying
        if (activePlayer.playbackState == Player.STATE_READY) {
            positionMs = activePlayer.currentPosition
            if (activePlayer.duration > 0) durationMs = activePlayer.duration
        }
        currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // La IDENTIDAD de lo que suena la manda siempre la playlist local: el CastPlayer
                // tiene un solo ítem cargado y su índice sería siempre 0.
                currentIndex = controller.currentMediaItemIndex
                NowPlaying.episodeId =
                    playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }
```

- [ ] **Step 3: Reapuntar el bucle de sondeo**

Ubicar el `LaunchedEffect(Unit)` cuyo comentario previo dice `// Sondeo: posición/duración (0,5 s), estado de descarga (torrent) y progreso persistido (5 s).` (~línea 598).

Reemplazar el bloque completo por:

**Ojo con la clave del efecto.** Hoy es `LaunchedEffect(Unit)`, que captura `activePlayer` de la primera composición y **no lo vuelve a leer nunca**: al conectar el cast, la posición seguiría saliendo del reproductor local. Pasa a `LaunchedEffect(activePlayer)` para que se relance al cambiar de player. Reiniciar `tick` en ese relanzamiento es inofensivo: solo reinicia la cadencia de guardado de 5 s.

```kotlin
    // Sondeo: posición/duración (0,5 s), estado de descarga (torrent) y progreso persistido (5 s).
    // Clave = activePlayer: al conectar/desconectar el cast hay que volver a sondear al que suena.
    LaunchedEffect(activePlayer) {
        var tick = 0
        while (true) {
            delay(500)
            if (isTorrent) progress = graph.torrentEngine.streamStatus()
            // Fracción buffereada por delante para la barra: torrent = % de descarga; archive = % cacheado.
            // Casteando no aplica: lo que bufferea es el receptor, no nosotros — mostrar el buffer
            // local sería una barra que miente.
            bufferedFraction = when {
                casting -> 0f
                isTorrent -> progress?.progress ?: 0f
                else -> {
                    val url = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.mediaUrl
                    if (url != null) graph.archiveCacheProxy.bufferedFraction(url) else 0f
                }
            }
            val ready = activePlayer.playbackState == Player.STATE_READY
            if (ready) {
                positionMs = activePlayer.currentPosition
                if (activePlayer.duration > 0) durationMs = activePlayer.duration
            }
            subsOn = vlc.currentSpuTrack() >= 0
            tick++
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            // El episodio lo identifica la playlist LOCAL, no el player activo.
            val epId = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            if (tick % 10 == 0 && epId != null && ready && activePlayer.isPlaying &&
                dur > 0 && pos in 0 until dur
            ) {
                vm.saveProgress(epId, pos, dur)
            }
        }
    }
```

- [ ] **Step 4: Reapuntar los helpers de transporte**

Ubicar `fun seekBy(deltaMs: Long)` (~línea 805) y `fun togglePlayPause()` (~línea 813). Reemplazar ambas por:

```kotlin
    fun seekBy(deltaMs: Long) {
        val dur = activePlayer.duration
        val max = if (dur > 0) dur else Long.MAX_VALUE
        val target = (activePlayer.currentPosition + deltaMs).coerceIn(0L, max)
        activePlayer.seekTo(target)
        positionMs = target
        bump()
    }

    fun togglePlayPause() {
        if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
        bump()
    }
```

- [ ] **Step 5: Reapuntar el scrubber y el "saltar intro"**

Tres sitios sueltos, cada uno una línea. Ubicar por su texto:

- `controller.seekTo(scrubPosition.toLong())` (~1321) → `activePlayer.seekTo(scrubPosition.toLong())`
- `if (inOpening) SkipButton("Saltar intro") { controller.seekTo(d.openingEndMs!!) }` (~1568) → cambiar solo `controller.seekTo` por `activePlayer.seekTo`
- `onSeek = { p -> controller.seekTo(p); positionMs = p },` (~1579) → cambiar solo `controller.seekTo` por `activePlayer.seekTo`

**No tocar** ningún otro uso de `controller`: la carga de la playlist (`setMediaItems`, `prepare`, `playWhenReady`), el `controller.pause()`/`controller.play()` de dentro del `SessionAvailabilityListener` (esos son a propósito sobre el local), ni `controller.seekToNextMediaItem()` del "saltar outro" (se oculta en la Task 2).

- [ ] **Step 6: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Si falta el import de `androidx.media3.common.Player`, verificar: ya debería estar (el archivo usa `Player.STATE_BUFFERING`). No agregar imports que ya existan.

- [ ] **Step 7: Verificar que no se rompió la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Revisar el diff antes de commitear**

Run: `git diff -- app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

Confirmar que **solo** aparecen los cambios de esta tarea. Si aparece código ajeno (otra sesión trabaja en este mismo archivo), **no commitear**: reportarlo.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(cast): el transporte apunta al player activo (Chromecast o local)"
```

---

## Task 2: Destapar los controles y guardar lo que es solo local

Acá el cambio se vuelve visible: al castear aparecen los controles.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `activePlayer` (Task 1), y las variables de estado `casting`, `dlnaActive` ya existentes.

- [ ] **Step 1: Destapar el bloque de controles**

Ubicar el `AnimatedVisibility` precedido por el comentario `// ---- Controles custom (fade in/out) ----` (~línea 1141). Su línea `visible` dice hoy:

```kotlin
            visible = controlsVisible && loadError == null && !casting && dlnaActive == null && markingMode == null,
```

Reemplazarla por:

```kotlin
            // Casteando SÍ se muestran: el transporte maneja el Chromecast (ver activePlayer).
            // Los elementos de adentro que solo aplican al reproductor local llevan su propia
            // guarda `!casting`.
            visible = controlsVisible && loadError == null && dlnaActive == null && markingMode == null,
```

**No tocar** las otras tres condiciones que llevan `!casting` (~1039, ~1100, ~1557): protegen la superficie de video y la UI de buffering del torrent local, que efectivamente no aplican al castear.

- [ ] **Step 2: Guardar el botón de siguiente episodio**

Ubicar el botón con `contentDescription = "Siguiente episodio"` (~línea 1448) y subir hasta el `if` o el composable que controla su aparición. Envolverlo de modo que **no se muestre cuando `casting` es true**, agregando `&& !casting` a su condición de visibilidad existente.

Motivo, que conviene dejar como comentario en el sitio:

```kotlin
                            // Casteando no: el cast carga un solo ítem, y navegar al siguiente
                            // capítulo lo abriría en el reproductor LOCAL dejando el Chromecast
                            // con el anterior.
```

- [ ] **Step 3: Guardar el "Saltar outro"**

Ubicar la línea:

```kotlin
                if (inEnding) SkipButton("Saltar outro", icon = true) { controller.seekToNextMediaItem() }
```

Cambiarla por:

```kotlin
                // "Saltar intro" hace seekTo y funciona casteando; "saltar outro" salta al ítem
                // siguiente, y el cast tiene uno solo cargado — se oculta.
                if (inEnding && !casting) SkipButton("Saltar outro", icon = true) { controller.seekToNextMediaItem() }
```

- [ ] **Step 4: Guardar el botón CC / pistas de audio**

Leer el bloque de controles y ubicar el botón de subtítulos/audio (el comentario en el archivo dice que "el botón CC/audio del teléfono se movió abajo a la derecha, junto a la fila de transporte"). Agregar `&& !casting` a su condición de visibilidad, con el comentario:

```kotlin
                        // Casteando no: las pistas se eligen sobre PlaybackEngine.vlc, el
                        // reproductor local. El receptor de Chromecast maneja las suyas.
```

Si el botón no tiene hoy una condición propia, envolverlo en `if (!casting) { … }`.

- [ ] **Step 5: Revisar velocidad y zoom**

Ubicar `fun cycleSpeed()` y `fun cycleZoom()` (~818 en adelante) y confirmar leyendo su cuerpo si actúan sobre el `VlcPlayer` local (el comentario del archivo dice "Velocidad y zoom nativo de VLC (cíclicos), aplicados al player vivo").

Si actúan sobre VLC, ubicar sus `TextButton` en la barra superior (~1225 y ~1232) y agregarles la misma guarda `!casting`, porque casteando no harían nada. Si resultara que ya están cubiertos por una guarda `!casting` existente, dejarlos como están y anotarlo en el reporte.

- [ ] **Step 6: Mover el cartel de Chromecast**

Ubicar el bloque `if (casting) { … }` con el texto `"Reproduciendo en Chromecast"` (~1125). Hoy usa `.align(Alignment.Center)`, que taparía los controles ahora que son visibles.

Cambiar ese modificador por `.align(Alignment.TopCenter)` y agregarle un padding superior para que no choque con la barra de arriba:

```kotlin
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
```

- [ ] **Step 7: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Verificar que no se rompió la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Revisar el diff antes de commitear**

Run: `git diff -- app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

Confirmar que solo aparecen los cambios de esta tarea, y en particular que **no se quitó** ninguna de las otras tres guardas `!casting`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(cast): mostrar los controles al castear y ocultar lo que es solo local"
```

---

## Task 3: Verificación en dispositivo

**No ejecutable sin un Chromecast y el celu.** Es tarea del usuario; ningún agente debe marcarla como hecha ni simularla.

- [ ] **Step 1: Instalar**

```bash
~/Library/Android/sdk/platform-tools/adb -s adb-R5CX7251VRM-yi9fGB._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Recorrido**

Reproducir algo en el celu, castear a un Chromecast y comprobar:

1. Al castear **aparecen los controles** (antes no aparecía ninguno).
2. **Pausa y play** mueven el Chromecast de verdad.
3. La **barra de progreso avanza**, y un **seek** cae donde se toca.
4. El cartel "Reproduciendo en Chromecast" quedó arriba y **no tapa** los controles.
5. **No aparecen**: botón CC/audio, siguiente episodio, "saltar outro". **Sí** aparece "saltar intro" y funciona.
6. Al **cortar la sesión**, los controles vuelven a manejar el celu y el video local reanuda.
7. Al salir, la **posición quedó guardada** — antes se perdía. Verificar reabriendo el episodio.

- [ ] **Step 3: Revisar crashes**

```bash
~/Library/Android/sdk/platform-tools/adb -s adb-R5CX7251VRM-yi9fGB._adb-tls-connect._tcp logcat -d | grep -iE "FATAL EXCEPTION|E AndroidRuntime"
```

Expected: sin resultados.
