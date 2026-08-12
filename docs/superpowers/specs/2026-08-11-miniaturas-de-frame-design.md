# Miniaturas de frame capturado durante la reproducción

**Fecha:** 2026-08-11
**Estado:** diseño aprobado, sin implementar

## El problema

Las tarjetas del home y de la biblioteca muestran arte oficial: backdrops de TMDB, stills de
capítulo de TMDB, o la carátula del ítem. Eso identifica *qué* estás viendo, pero no dice nada de
*dónde vas*. La barra de progreso dice cuánto llevás; no dice qué estaba pasando.

La idea es capturar un frame de lo que se está reproduciendo y usarlo como miniatura, para que el
home se sienta vivo y para que al abrir una serie guardada se vea en qué punto quedó cada capítulo.

## Decisiones tomadas

Las cuatro que definen el producto, todas decididas con el usuario:

1. **El frame gana solo en lo empezado.** Si el capítulo tiene progreso, manda el frame; si no lo
   empezaste, sigue el still de TMDB o la carátula. Así la lista de capítulos se lee sola: lo que
   tenés a medias se ve con tu escena, lo que no empezaste con la imagen oficial.
2. **Se sincroniza ida y vuelta** vía PocketBase, con un frame chico para que no pese.
3. **Una sola imagen viva por capítulo**, sobrescrita en el lugar. Nunca dos.
4. **Se destruye cuando el capítulo se marca como visto.** Sin esto, crece para siempre.

## No-objetivos

- No es un sistema de capturas manuales ni una galería. El usuario no elige el frame.
- No reemplaza el arte de TMDB en lo que no empezaste.
- No hay frame para películas sin progreso ni para ítems recién agregados.

## El mecanismo de captura

### Lo que se verificó (libVLC 3.6.0, `org.videolan.android:libvlc-all`)

- **No existe `takeSnapshot` en la API Java.** Verificado con `javap` sobre el `classes.jar` del
  AAR: ningún método con `snapshot`/`thumbnail` en `MediaPlayer`, y ninguna clase con esos nombres.
- **El filtro `scene` de VLC no está compilado** en `libvlc.so`. Verificado con `strings` sobre el
  `.so` de `arm64-v8a`, con control positivo: `swscale`, `deinterlace` y `adjust` aparecen como
  string exacto; `scene` y sus opciones (`scene-path`, `scene-ratio`, …) dan cero.
- **El render hoy va por SurfaceView**: `VlcPlayer.attachVideo` llama
  `attachViews(layout, null, true, false)`, y ese cuarto booleano es `useTextureView`. Un
  SurfaceView es una capa aparte del compositor y **no se puede leer**.

O sea que los dos caminos "obvios" no existen en esta build.

### La decisión: TextureView + `getBitmap()`

Se pasa el render a TextureView (`attachViews(layout, null, true, true)`, la API ya lo soporta) y
se lee el frame con `TextureView.getBitmap()`.

Es el único mecanismo que da **exactamente lo que estás viendo**, en cualquier posición, para todas
las fuentes (torrent, web, archive, magis), **sin decodificar nada de nuevo**. El costo marginal por
captura es casi cero, y capturar al pausar es trivial porque el frame ya está en pantalla.

### Alternativas descartadas, y por qué

- **`MediaMetadataRetriever` (segundo decodificado).** No toca el render, riesgo cero para la
  reproducción. Pero es una segunda descarga y un segundo decodificado, y falla justo en las
  fuentes que más se usan: Magis sirve TS por un CDN medido entre 0,2 y 20 s por rango, y MMR falla
  seguido con TS y HEVC. Sirve solo para descargas locales.
- **Un segundo `MediaPlayer` efímero al pausar.** Reproduce todo lo que reproduce libVLC (a
  diferencia de MMR), pero paga apertura + seek + decode por captura, duplica red y CPU compitiendo
  con la reproducción en curso, y **no sirve para el periódico**: solo para la pausa.

## Riesgo abierto: el paso 0

El diseño depende de una apuesta técnica **sin verificar**. Antes de escribir una línea de la
funcionalidad hay que responder dos preguntas, en el Fire TV Stick y en el celular:

1. ¿Se degrada la reproducción al pasar de SurfaceView a TextureView? TextureView compone por GPU
   en vez de ir por un plano de hardware. Además el camino de attach/detach es la parte más
   delicada del player (hay lógica dedicada a la destrucción de la Surface, `VideoAttachPolicy`,
   los eventos `Vout 0`).
2. ¿`getBitmap()` devuelve imagen o negro? Algunos decodificadores por hardware usan buffers opacos
   que no se pueden leer.

**Si cualquiera de las dos sale mal, este diseño se cae** y hay que volver al MediaPlayer efímero,
perdiendo la captura periódica. Por eso el paso 0 es medir, no construir.

## Almacenamiento

Tabla `episode_frame`, una fila por capítulo:

```
episodeId   TEXT PRIMARY KEY
positionMs  INTEGER   -- de qué punto del capítulo es el frame
capturedAt  INTEGER
updatedAt   INTEGER   -- el reloj del sync, igual que el resto de las tablas
deleted     INTEGER   -- tombstone, igual que el resto
```

**El JPEG no va en la tabla.** Va a `filesDir/frames/<episodeId hasheado>.jpg`, y la fila guarda
solo la referencia. Meter ~97 blobs de 100 KB en SQLite infla la base unos 10 MB y la vuelve pesada
de leer, cuando lo único que necesita el que la consulta es dónde está la imagen. Coil ya carga
desde un archivo, así que la UI no cambia de forma.

El archivo y la fila se sobrescriben juntos: esa es la invariante de "una sola imagen viva por
capítulo".

### Tamaño: 960×540, JPEG calidad 80 (~100 KB)

Medido contra las superficies donde se muestra:

| Superficie | Ancho real |
|---|---|
| Tarjeta "Continuar viendo" (celu) | 220 dp ≈ 616 px |
| Hero del celu | 1080 px |
| Tarjetas del TV | ~600–800 px |
| Hero del TV (1920×1080, densidad 320) | 1920 px |

960×540 cubre las tarjetas de sobra en los dos dispositivos y deja el hero del celu escalado 1,12×
(imperceptible). El único lugar donde se nota es el hero del TV, a 2×, y ahí el frame va con
gradiente oscuro y texto encima.

1280×720 duplicaría el peso para una mejora visible solo en el hero del TV. 640×360 ahorraría la
mitad pero ablandaría las tarjetas del celu, que es donde más se mira.

## Dónde entra en la cadena de imágenes

La regla "solo en lo empezado" cae sola en cada superficie, porque el frame solo existe si hubo
progreso:

| Superficie | Cadena hoy | Cadena nueva |
|---|---|---|
| Hero del home | backdrop TMDB → carátula | **frame** → backdrop → carátula |
| "Continuar viendo" | still TMDB → thumb → carátula | **frame** → still → thumb → carátula |
| Lista de capítulos | still TMDB | **frame** → still |
| Tarjeta de serie (biblioteca) | carátula | **frame del capítulo en curso** → carátula |

El "capítulo en curso" de la última fila no hay que inventarlo: `ItemDetail.inProgressEpisode` ya
existe y ya resuelve exactamente eso.

Toda la regla vive en **una función pura** que recibe las candidatas y devuelve la que gana —
testeable sin Room, sin red y sin reproductor.

## La captura

Un `FrameCapturer` con una sola responsabilidad: dado un TextureView y una posición, dejar un JPEG
de 960×540 en disco.

### Cuándo

- **Cada 5 minutos** mientras se reproduce → **solo escribe local**. Su único trabajo es que un
  cierre abrupto de la app no pierda el punto; con 5 minutos, lo peor que pasa es que el frame
  quede 5 minutos viejo.
- **Cuando la reproducción se detiene** (pausa *o* salida del reproductor) → captura **y sube**.

Pausa y salida cuentan igual a propósito. Si la subida dependiera solo de la pausa, quien sale con
el botón atrás sin pausar nunca sincronizaría nada.

### Dos guardas, porque un frame feo es peor que no tener frame

- **No captura en los primeros 60 segundos** del capítulo. Ahí viven los logos de distribuidora y
  las pantallas negras. 60 s es un punto de partida, no un número sagrado: se ajusta si en el uso
  real se ve que corta tarde o temprano.
- **Descarta el frame casi negro.** Luminancia media sobre una miniatura del bitmap; si queda por
  debajo de **10 sobre 255**, se conserva el anterior. Sin esto, pausar en un fundido deja la
  tarjeta en negro — y como sobrescribe, se perdería el frame bueno que ya había.

Las dos son funciones puras sobre un bitmap, o sea testeables.

## Sync ida y vuelta

### Por qué no viaja en el snapshot

`SyncSnapshot` es **un solo documento JSON** con items, episodes, playback y markers. Meter los
frames en base64 ahí significaría mover ~13 MB de JSON en cada sync, estuvieran o no
desactualizados. Descartado.

### Colección aparte

Colección `episode_frames` en PocketBase, un record por capítulo:

```
episodeId (índice único) · positionMs · updatedAt · deleted · img (file)
```

- **Subida** cuando se detiene la reproducción. Requiere **multipart en `PocketBaseClient`**, que
  hoy solo postea JSON (`jsonType`, `fields: Map<String, Any?>`). Es código nuevo y es el grueso
  del trabajo de esta parte.
- **Quién gana:** LWW por `updatedAt`, exactamente la misma regla que ya aplica `SyncMerge`. No se
  inventa un criterio nuevo.
- **Borrado con tombstone**, para los borrados que el progreso NO cubre (ver más abajo).

### La metadata viaja por el motor; los bytes, al pintar

Decidido con el usuario (2026-08-11), y precisa lo que el borrador dejaba ambiguo con "bajada
perezosa". Son dos cosas distintas y viajan distinto:

- **La fila** (`episodeId`, `positionMs`, `updatedAt`, `deleted`) va por el motor de sync que ya
  existe: `CloudSyncManager` + `PbSyncClient.upsert(collection, naturalKeyField, naturalKey,
  fields)`, con scoping por `accountId`, LWW y realtime por SSE ya resueltos. Es JSON barato y no
  hay que tocar el diseño del motor.
- **Los bytes del JPEG** se bajan recién cuando hay que pintar esa tarjeta y el archivo no está en
  disco. Meter descargas de imágenes en el ciclo de sync convertiría cada tick en varios MB y
  obligaría a inventar qué pasa si la red se corta a la mitad.

Consecuencia asumida: la primera vez que abrís el home después de haber visto en el otro aparato,
esa miniatura tarda un instante en aparecer (mientras tanto se ve el respaldo de siempre, el still
de TMDB). A cambio, el motor de sync no cambia de naturaleza.

### Lo que la fase 1 ya resolvió, y que achica esta fase

Tres cosas cambiaron mientras se construía la fase 1 y hay que tenerlas en cuenta acá:

1. **El progreso ya borra el frame en el otro dispositivo.** `DestructorDeFrames` se llama desde
   los CUATRO caminos que marcan visto, incluidos los dos de sync (`mergeFromSync` LAN y
   `CloudSyncManager.mergePlayback`). O sea que "vi el capítulo en el TV" ya hace que el celular
   destruya su frame al recibir el progreso, sin que el frame tenga que sincronizar nada.
   El tombstone del frame queda entonces para los borrados que el progreso NO cubre: el wipe de
   logout y sacar el ítem de la biblioteca.
2. **Los frames viven poco.** Con la regla de retención un frame existe solo entre los 60 s y el
   60% del capítulo (medido en device: un capítulo llegó a 64,9% y su frame se destruyó solo). El
   volumen real es menor que los ~10 MB estimados.
3. **La plomería de sync es más rica de lo que asumía el borrador**: hay `SyncCursors`,
   `SyncQuarantine` y realtime por SSE. La fila del frame tiene que entrar por ahí como una
   colección más, no por un camino paralelo.

Ida y vuelta en concreto: se ve en el celu, se pausa, sube la fila y el archivo. El TV recibe la
fila por el sync normal y baja el JPEG cuando lo necesita para pintar. Al revés, igual.

## Volumen

Medido sobre la base real del celular: **120 capítulos con progreso, 97 sin terminar**. Ese es el
conjunto vivo. A ~100 KB por frame son ~10 MB en PocketBase en régimen, y no crece más porque cada
capítulo tiene una sola imagen y se destruye al marcarse visto.

## Testing

Con tests puros (sin Room, sin red, sin reproductor):

- La regla de qué imagen gana en cada superficie.
- El rechazo del frame casi negro y el piso de posición.
- El LWW de frames, incluido el tombstone.
- La retención: marcar visto borra fila y archivo.

**No** se puede cubrir así la captura del TextureView. Eso es verificación en dispositivo, en el
celular y en el Fire TV Stick.

## Alcance: esto se implementa en tres fases

El diseño entero es demasiado para un solo plan de implementación, y además tiene una compuerta.
Las fases van en este orden y cada una vale por sí sola:

- **Fase 0 — medir.** El paso 0 de arriba: TextureView y `getBitmap()` en los dos dispositivos.
  Sin construir nada de la funcionalidad. Si sale mal, no hay fase 1.
- **Fase 1 — captura y miniaturas locales.** Tabla, `FrameCapturer`, guardas, la función pura de
  la cadena de imágenes y las cuatro superficies. Acá ya se ve el resultado completo en un
  dispositivo: es la mitad que da el valor visible.
- **Fase 2 — sync.** Multipart en `PocketBaseClient`, la colección `episode_frames`, la bajada
  perezosa y los tombstones. Es aditiva: la fase 1 no la necesita para funcionar.

## Consecuencias asumidas

- **El frame puede spoilear.** Es una escena del punto donde vas, mostrada en el home. Es inherente
  a la idea y es lo que se pidió.
- **El primer frame tarda en aparecer** en un capítulo recién empezado: hasta que no se pausa o
  pasan 5 minutos, la tarjeta sigue mostrando el still de TMDB.
- **Un dispositivo sin sync no ve los frames del otro.** El progreso ya viaja; la imagen viaja
  aparte y perezosamente, así que puede llegar después.
