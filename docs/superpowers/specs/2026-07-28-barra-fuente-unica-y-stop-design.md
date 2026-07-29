# La barra dice la verdad: fuente única (TV o Chromecast) y botón de parar

Fecha: 2026-07-28 · Estado: implementado (falta verificación en dispositivo)

## Objetivo

Tres cosas, una sola idea de fondo — **de dónde saca la barra la verdad sobre qué se está
reproduciendo**:

1. Que **desaparezca** cuando el Fire TV ya no está reproduciendo. Hoy se queda mostrando el último
   capítulo para siempre.
2. Que **refleje y controle el Chromecast** cuando se está casteando. Hoy no sabe que existe.
3. Que tenga un botón de **parar**, que corte lo que suene donde suene.

## El bug, verificado

`NowPlayingPublisher` solo se calla cuando `NowPlaying.episodeId` es null. Ese valor tiene **tres
escrituras y ningún borrado** (`ui/player/PlayerScreen.kt`, líneas ~707, ~774, ~793). Así que en
cuanto el Fire TV reproduce algo, sigue publicando ese episodio en `PAUSED` con `at` fresco
indefinidamente — y por eso la cota de vida de 2 minutos del celu no lo detecta: el `at` es genuino.

Confirmado en uso real: la barra seguía mostrando un capítulo que hacía rato no se reproducía.

**No se arregla limpiando `NowPlaying.episodeId`.** Ese valor lo leen otras dos cosas:
`MainActivity:100` para el deep-link de la notificación ("reanudá lo que veías") y `ArkivTvRoot`
(~102, ~106) para resolver siguiente/anterior. Borrarlo rompería ambas.

## Diseño

### 1. El publisher tiene su propia señal

Se agrega un flag dedicado —vive junto a `NowPlaying`, en `playback/`— que la pantalla del
reproductor **del TV** enciende al componerse y apaga al destruirse. `NowPlayingPublisher` publica
`null` cuando está apagado, sin mirar `NowPlaying.episodeId`.

Con el reproductor cerrado en el TV, la barra del celu desaparece en el siguiente poll (≤3s).

`NowPlaying.episodeId` queda intacto para sus otros dos consumidores.

### 2. Un coordinador elige la fuente

`NowPlayingCoordinator` (nuevo, en `remote/`), colgado del `AppGraph`, observa las dos fuentes y
expone **un solo** `StateFlow` con lo que la barra debe dibujar. La barra queda tonta: dibuja lo que
le den y no sabe de dónde viene.

**La regla es "gana la que arrancó último": una fuente saca a la otra de la barra.** Castear tapa lo
que muestre el Fire TV; poner algo nuevo en el app del TV recupera la barra aunque el casteo siga
vivo. Empate —las dos marcas selladas en el mismo tick del coordinador— lo gana el Chromecast. En la
práctica es defensivo y no se alcanza: al arrancar la app de cero el Chromecast no tiene petición
pendiente (vive en memoria y muere con el proceso), así que no produce foto y manda el Fire TV.

Esto cambia **solo lo que la barra muestra y controla**. La fuente que pierde sigue reproduciendo
donde esté: la barra no corta nada. Para eso está el botón de parar.

> Nota de revisión (2026-07-28): la primera versión de esta spec eligió la regla más simple —"si hay
> sesión de cast manda el Chromecast, si no el Fire TV"— argumentando que tener las dos cosas a la
> vez no era un escenario real. Lo es: sin el cambio de vuelta, castear dejaba la barra clavada en
> el Chromecast aunque después se pusiera algo en el app del TV.

Las marcas de arranque son instantes del reloj **local del celu** (`0` = esa fuente no reproduce),
nunca relojes de los dispositivos: compararlos exigiría sincronizarlos. Se re-sellan cuando cambia
el episodio —poner otro capítulo es un arranque nuevo— y vuelven a `0` cuando la fuente se apaga.
Quien las sella es el coordinador, que es el único que ve las dos fuentes cambiar; `BarSource` las
recibe ya calculadas y se mantiene puro.

### 3. Normalizar, no solo elegir

Las dos fuentes son distintas de raíz:

| | Fire TV | Chromecast |
|---|---|---|
| Estado | Fotos por PocketBase, poll de 3s | `CastPlayer` local, inmediato |
| Posición | Extrapolada entre fotos | Se lee directo |
| Comandos | Records en `commands`, con latencia | Llamadas directas al player |
| UI optimista | Necesaria | Innecesaria |
| Cota de rancio | Necesaria (el TV puede morir sin avisar) | Innecesaria (`casting` se apaga solo) |

Así que el coordinador **normaliza** ambas al mismo `TvNowPlaying` que la barra ya dibuja, y añade
una marca de si la posición hay que extrapolarla. La barra aplica extrapolación y overlay optimista
solo cuando esa marca lo pide.

**De dónde sale cada campo cuando la fuente es el Chromecast:**

| Campo | Origen |
|---|---|
| `episodeId`, `title`, `subtitle`, `posterUrl` | El `CastRequest` que el manager ya guarda como `pending`: los trae desde que se cargó el medio |
| `positionMs`, `durationMs`, `state` | Leídos del `CastPlayer`, en el hilo principal |
| `hasNext`, `hasPrev` | El coordinador los resuelve con `repository.nextEpisode` / `previousEpisode` sobre el `episodeId` del `pending` — la misma fuente que usa el publisher del TV, así que celu y TV nunca ofrecen un salto distinto |

Que `hasNext`/`hasPrev` salgan del repositorio y no del receptor es deliberado: el Chromecast tiene
un solo ítem cargado y no sabe nada de la serie.

### 4. Los comandos, por fuente

- **Fire TV**: como hoy, `RemoteController.sendTransport(...)`.
- **Chromecast**: directo sobre el `CastPlayer` del `CastSessionManager`.
- **Siguiente / anterior con Chromecast**: el celu resuelve el vecino con
  `repository.nextEpisode` / `previousEpisode` —igual que ya hace para el TV— y lo manda con
  `castSession.setMedia(...)`, el camino construido el 2026-07-27.

### 5. El botón de parar

Corta **la fuente que la barra esté mostrando**, sea cual sea. Con las dos sonando a la vez para
una sola, no las dos: la regla de "gana la que arrancó último" decide cuál. Es recuperable —al
callarse esa fuente la barra pasa a la otra y un segundo toque la corta también— pero conviene
saberlo antes de suponer que un toque apaga todo.

**Fire TV.** Requiere un **tipo de comando nuevo**, `stop`, que el TV aplica deteniendo la
reproducción y saliendo del reproductor. La barra desaparece sola, porque al cerrarse esa pantalla
se apaga la señal del punto 1.

> **Paso manual en el servidor:** el select `type` de la colección `commands` en PocketBase debe
> admitir `stop`. Sin eso el server rechaza el record y el comando falla **en silencio** — que es
> exactamente cómo `subprefs` y `webquality` estuvieron rotos durante meses (ver
> `docs/pocketbase/collections.md`).

**Chromecast.** Corta la reproducción y **termina la sesión**, para que la TV vuelva a lo suyo.

Acá hay una interacción que hay que resolver a propósito: el trabajo del 2026-07-27 hace que, al
terminar una sesión, el celu **reanude localmente** donde llegó el receptor. Eso es correcto cuando
el usuario desconecta desde el botón de cast —quiere seguir viendo en el teléfono— pero sería
absurdo tras pulsar "parar": pidió silencio y el teléfono se pondría a reproducir.

Por eso el stop termina la sesión **marcándola como intencional**, y el camino de reanudación local
consulta esa marca y se saltea. La marca se limpia en cuanto se procesa, para que una desconexión
posterior desde el botón de cast vuelva a reanudar normalmente.

**Ubicación.** Al final de la fila de controles, separado del resto con un poco de aire y con el
ícono de stop cuadrado, que no se confunde con los de transporte. Igual en "Reproduciendo ahora",
con más espacio.

**No borra el progreso.** Lo último guardado queda, así que el capítulo se retoma donde iba.

## Bordes

| Situación | Comportamiento |
|---|---|
| Sin Google Play Services | No hay `castSession`; el coordinador solo ve el Fire TV; todo como hoy |
| Ni TV ni cast activos | No hay barra |
| Casteando, con el Chromecast | Sin cota de rancio: el estado es local y `casting` se apaga solo si la sesión cae |
| "Reproduciendo ahora" abierta | Lee del mismo coordinador, para no contradecir a la barra |
| El TV publica `null` estando la barra visible | La barra desaparece en el siguiente poll |
| Se pulsa parar con el Chromecast | `casting` se apaga → el coordinador cae al Fire TV → si ése tampoco reproduce, no hay barra |
| Se castea estando el Fire TV reproduciendo | La barra pasa al Chromecast; el Fire TV sigue reproduciendo, solo deja de verse |
| Se pone algo en el app del TV estando casteando | La barra vuelve al Fire TV en ≤3s **con la app al frente**; en segundo plano el poll duerme y se entera al volver |
| Se arranca la app de cero con las dos ya sonando | Manda el Fire TV: el Chromecast no tiene petición pendiente tras morir el proceso |
| Se cae la sesión de cast un segundo y vuelve sola | No cuenta como arranque nuevo: la marca se congela y la barra no cambia de dueño |
| El Fire TV se muere sin publicar `null` estando casteando | Su foto deja de competir a los 45s y la barra vuelve al Chromecast, en vez de esconderse 75s |

## Fuera de alcance

Que la barra maneje DLNA. Su control de play/pausa vive dentro del reproductor y su problema es
otro: nada consulta la posición del renderer.

## Pruebas

Hay dos piezas aislables, las dos con tests JVM:

- **La elección de fuente y la normalización** (`BarSource.pick`): función pura de (¿hay cast?, foto
  del cast, su marca de arranque, foto del TV, su marca, ahora) → qué dibujar y si extrapolar.
  Incluye que la marca de extrapolación sea falsa para el Chromecast y verdadera para el TV, que
  gane la que arrancó último en las dos direcciones, y la dirección del empate.
- **El sellado de las marcas** (`sellarMarca`): que una identidad nula **congele** la marca en vez
  de borrarla —un hipo de WiFi no es un arranque— y que el mismo episodio con apertura nueva **sí**
  re-selle. Esta segunda es la que atrapa el bug de "reiniciar lo mismo en el TV no recuperaba la
  barra".

El resto se verifica en dispositivo:

1. Reproducir algo en el Fire TV, **salir del reproductor allá**: la barra desaparece en ≤3s.
2. Castear desde el celu: la barra aparece con lo que se está casteando.
3. Sus botones mueven el Chromecast: pausa, ±10s, siguiente capítulo.
   Los ±10s quedan **deshabilitados** si el audio va transcodificado: ese stream sale en vivo y no
   se puede buscar, y reposicionarlo exige rearmar la petición de cast, que solo sabe el reproductor.
4. **Parar** con el Chromecast: la TV vuelve a lo suyo y **el celu no empieza a reproducir**.
5. **Parar** con el Fire TV: el TV corta y sale del reproductor; la barra desaparece.
6. Desconectar desde el botón de cast (no desde "parar"): el celu **sí** reanuda donde llegó el
   receptor — la marca de intencionalidad no debe afectar este camino.
7. Estando casteando, **poner algo en el app del TV**: la barra vuelve al Fire TV. Con la app al
   frente tarda lo que tarde el poll (≤3s); con el celu en el bolsillo, hasta volver a mirarlo,
   porque el poll duerme en segundo plano. **Requiere que el Fire TV también tenga este build**: el
   caso de reiniciar el *mismo* capítulo se apoya en un campo nuevo del payload que un TV viejo no
   manda (con un TV viejo, cambiar de capítulo sí funciona; repetir el mismo, no).
8. Estando el Fire TV reproduciendo, **castear**: la barra pasa al Chromecast y el TV sigue sonando
   donde esté. Ninguna de las dos corta a la otra: para eso está parar.

## Archivos

**Nuevos:** `remote/NowPlayingCoordinator.kt`, `remote/BarSource.kt` (la parte pura: `pick` y
`sellarMarca`) y `remote/BarSourceTest.kt`, `ui/remote/BarCommands.kt` (rutear el comando a la
fuente que se esté mostrando).

**Modificados:** `playback/PlaybackService.kt` (la señal nueva), `remote/NowPlayingPublisher.kt`,
`cast/CastSessionManager.kt` (parada intencional), `remote/TransportCommand.kt` (tipo `stop`),
`remote/RemoteController.kt`, `ui/tv/ArkivTvRoot.kt` (aplicar `stop`),
`ui/player/PlayerScreen.kt` (encender/apagar la señal; no reanudar tras parada intencional),
`ui/remote/MiniPlayerBar.kt` y `ui/remote/NowPlayingScreen.kt` (botón de parar, consumir el
coordinador), `AppGraph.kt`, `docs/pocketbase/collections.md`.
