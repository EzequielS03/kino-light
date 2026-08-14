# Auditoría del reproductor contra el decompilado de Magis

Fecha: 2026-08-14. Decompilado en `/Users/cristian/mago/decompiled` (jadx + apktool).
Rama: `login-obligatorio` (app) y `main` (arkiv-api).

Su reproductor no es "un ijkplayer": es un **motor de entrega nativo** (`libranger`, P2P+CDN) con
**tres backends intercambiables** (`ijk`/`native`/`exo`, `vc/EnumC5902b`) en **dos instancias**
(`yc/C6283h.java:567` → `new C6280e[2]`). El player y el motor de entrega hablan **en los dos
sentidos, continuamente**. Nosotros tenemos un motor (libVLC tras `SimpleBasePlayer`), una
instancia, y una capa de entrega a la que le hablábamos **solo de ida**. Casi todos los huecos
salen de ahí.

---

## Lo que se implementó

Todo con test primero (se vio fallar cada uno antes de implementar).
**App: 1683 tests. Gateway: 655 tests. Cero fallos.**

### 1. El contenedor sale de los bytes, no del nombre

`ContenedorDeVideo.kt` (31 tests). Firma de 8 contenedores leyendo los primeros 512 bytes.

**Antes**: tres tablas de MIME que se contradecían — lo desconocido era `x-matroska` en
`TorrentStreamServer`, `mp4` en `LocalFileServer` y `mp4` otra vez en `CastRequestBuilder`.
Ese string es con el que Chromecast y DLNA deciden si abren el stream. libVLC lo ignora y sondea;
ellos no.

**Mejora**: un `.avi`/`.ts`/`.m2ts` de torrent, y un mkv de la NUC guardado como `.mp4`, dejan de
anunciarse con el tipo equivocado. Si la cabeza todavía no bajó, cae a la extensión — nunca peor
que antes.

> El test del m2ts corrigió la implementación: el paso entre sincronismos es 192 (188 + los 4 de
> timestamp de Blu-ray), no 188. Buscándolo con paso 188 no se encuentra nunca.

### 2. Una sola lista de extensiones de video

Había cuatro y ninguna coincidía. Efecto verificado por test: un `.ts`/`.m2ts`/`.mpg` en
archive.org ya aparece como episodio (antes la pantalla quedaba vacía **sin error**), y un `.m2ts`
de torrent conserva su nombre. De paso, `LocalFilePaths` dejó de cortar por el último punto sobre
una URL de página (`sitio.com/peli` → `"com/peli"`).

### 3. El idioma de audio se pide al abrir

`OpcionesDeIdioma.kt` (8 tests). Portado de `setOption(4, "audio_language", …)`
(`yc/C6276a.java:37`): ahora va `:audio-language=spa,es` en el `Media`.

**Antes**: se abría con la pista que eligiera libVLC y se corregía después de `Playing` con
reintentos durante ~3 s — una carrera que el KDoc describe como "hay que ganarle" y que se pierde
sola cuando las pistas pueblan tarde.

**Ojo**: `--audio-language` compara contra el código ISO, y Latino y Castellano son los dos `spa`.
`applyPreferredAudio` sigue haciendo el ajuste fino; deja de ser el mecanismo principal.

### 4. El seek le avisa a la capa de entrega

`AvisoDeSalto.kt` (6 tests) + `VlcPlayer.handleSeek` + `PlaybackService`.

Es la técnica central de ellos: `B0(name, pos)` no le manda el seek al player, se lo manda al motor
(`NativeJni.Seek`) y el player se mueve en el callback (`yc/C6280e.java:2115`).

**Resultó que teníamos la mitad**: `precalentarSalto` ya existía, ya estaba probado y ya ahorraba
3,4 s medidos — pero **solo se llamaba al abrir**. Los saltos con la barra no avisaban nada, así
que el proxy se enteraba del destino recién cuando VLC le pedía el rango, contra un CDN que tarda
entre 0,2 s y 20 s. Ahora avisa en los dos caminos del seek, con freno de 1,5 s para que arrastrar
la barra no abra decenas de conexiones.

**Falta la otra mitad**: ellos *esperan* el ack del motor antes de mover el player. Lo nuestro es
best-effort en paralelo.

### 5. El contenedor viaja como dato desde el portal (app + gateway)

`Playable.container` (gateway) → `GatewayPlayable` → `PlayerData` → extras del IPC →
`PlayerSourceTag.contenedorDeLaFuente` → `formatoAvformatDe`.

**Antes**: el gateway sabía el `videoFormat` del portal y lo tiraba, colapsándolo en una extensión
inventada (`ext = "ts" if formato == "ts" else "mp4"`); la app lo re-derivaba de esa extensión. Un
viaje de ida y vuelta por un string que además tiene que ser una clave de objeto válida en el CDN.

**Mejora concreta**: `formatoAvformatDe` siempre tuvo una rama "no lo reconozco → que sondee", pero
era **inalcanzable** porque el gateway solo podía emitir `.ts` o `.mp4`. Ahora un `flv`/`hls`/
`matroska` del portal hace sondear en vez de forzar `mp4` — que es el mismo fallo que se pagó al
revés (forzar `mpegts` sobre un mp4). Es lo que hace la app original con un `format` vacío: no
setear `iformat`.

Versión de caché del adaptador 9 → 10. Compatible en los dos sentidos: gateway viejo → `container`
vacío → la app cae a la extensión; app vieja → ignora el campo.

### 6. Colchón de red por fuente, con el vivo aparte

`CachingDeRed.kt` (6 tests). Los números estaban sueltos dentro de `loadMedia` con un `else` de
1500 ms que se tragaba al canal en vivo.

El vivo pasa a 8000 ms, el mismo que WEB, porque **es el mismo camino**: VLC → proxy local nuestro
→ CDN por internet, con latencia variable por segmento. Y ahí el costo es gratis: arrancar unos
segundos detrás del borde no se nota, cortarse sí.

MAGIS se queda en 1500 a propósito (VLC lee de corrido sobre un rango abierto; la latencia se paga
al abrir y al saltar, para lo que está el punto 4).

> El decompilado respalda el criterio, no los números: configura el vivo aparte (`live_mode=1`
> cuando `buss == "live"`, más `live-streaming` y `delay-optimization`). Esas tres son opciones de
> **su fork** de ijkplayer (tiene `_setSharedBuffer`, `ijklivehook`, `ijksegment` propios) y no
> tienen equivalente literal en libVLC. Se porta la decisión, no el nombre.

### 7. Diagnóstico en cada rescate

`DiagnosticoDeFallo.kt` (3 tests). Los tres rescates ahora escriben:

```
FALLO motivo=hardware-sin-imagen contenedor=mpegts vcodec=hevc 1920x1080 pistas=v1/a3 decodificador=hardware equipo=AFTKM
```

Equivalente de su telemetría `SwitchPlayer{format, vcodec, model}`, al log en vez de a un backend.
Antes un rescate decía solo `hardware sin imagen 10234ms → paso a software`.

### 8. Guardias contra fallos silenciosos

- **`UrlDeProxy.kt`** (9 tests): el desarmado de la URL del proxy estaba escrito tres veces con
  tres criterios distintos, y la diferencia ya costó un bug (el relleno `==` del base64). Ahora es
  uno solo.
- **Guardia del IPC** (`PlayerSourceTagTest`): el tag no cruza `MediaController`→`MediaSession`; un
  campo sin cablear llega en su default **en silencio**. El código lo advertía ("pasó con esto",
  con `preferirSoftware`) y no había nada que lo impidiera. Ahora un test por reflexión falla
  nombrando el campo. Se escribió con la lista vieja a propósito y detectó `contenedorDeLaFuente`.

---

## Lo que se decidió NO hacer

| Qué | Por qué |
|---|---|
| Detección temprana de "abrió sin video" (su error 1102) | Al implementarlo resultó que `RachaSinVideo` **ya usa la misma condición**. La única diferencia es el plazo (10 s), y está documentado que subirlo a 25 s se probó en device y salió peor. El plan estaba equivocado en este punto. |
| Debounce del spinner (su hide diferido de 1 s) | El sitio correcto es el overlay de la UI. Hacerlo en el estado del player significaría reportarle `STATE_BUFFERING` a media3 mientras el reloj avanza — mentirle a la MediaSession, la notificación y el cast por un parpadeo cosmético. |
| `--avcodec-skiploopfilter` en TV | Su `skip_loop_filter=48` es agresivo y siempre activo. Sin medición nuestra, cambiar calidad de imagen por CPU es apostar. |
| Subir `network-caching` de MAGIS | Alargaría el arranque, que es lo que más costó bajar. |

---

## Segunda ronda: lo que queda por tomar

Ordenado por valor.

### A. El estado del dispositivo no llega a la capa de entrega — ✅ HECHO (parte de red)

`vc/EnumC5904d` + `dd/AbstractC3049d`: la app le reporta al motor **cambios de red
(`wired`/`wlan`/`cellular`), pantalla apagada, ir al fondo, entrar en Doze, batería, disco**.

Nuestro `ArchiveCacheProxy` y `TorrentEngine` no se enteraban de nada: **no había un solo
`ConnectivityManager` en toda la app**. El caso que duele: un cambio de WiFi a datos a mitad de
reproducción deja el socket atado a una interfaz que ya no existe, y la lectura espera hasta el
plazo del CUERPO de `PoliticaOrigen` —**90 s en archive, 30 s en magis**— antes de que empiece
siquiera el primer reintento.

**Implementado** (18 tests):

- `CambioDeRed` — cuándo una transición invalida lo abierto. Se compara por identidad de red y no
  por tipo de transporte: cambiar de un WiFi a otro WiFi también mata los sockets.
- `SeguidorDeRed` — la máquina de estados, con la sutileza que importa: Android avisa el `onLost`
  de **cualquier** red, así que con datos andando y el WiFi apagándose atrás llega un `onLost(wifi)`
  mientras la reproducción va perfecta. Cortar ahí sería cortar por las dudas.
- `ConexionesVivas` — el registro de conexiones abiertas, con `cerrarTodas()`. Deliberadamente NO es
  `ConexionUnica`, que hacía algo distinto (cerrar la anterior del mismo origen), resultó ser la
  causa del fallo que decía evitar y **ya no lo llamaba nadie**: quedaba solo la interfaz.
- `VigilanteDeRed` — la cáscara de Android (`registerDefaultNetworkCallback`), sin decisiones.
- Enganchado en `AppGraph` a `ArchiveCacheProxy.abandonarConexiones()`.

Queda sin portar el resto del reporte de estado: pantalla, primer plano, Doze y batería. El
`TorrentEngine` tampoco se toca — libtorrent maneja sus reconexiones por su cuenta.

### B. El buffer que ve el usuario ignora lo que ya bajamos

`j11 = bufferDelPlayer + status.getBuffer()`: lo que muestran es la suma del buffer del player
**más** lo que el motor de entrega tiene descargado por delante.

Nosotros mostramos el `buffering %` de VLC a secas. En un torrent, libtorrent puede tener 40 MB
adelante y el overlay igual dice que está buffereando.

### C. Los seeks internos del demuxer también se reportan

`OnSeekListener` (`yc/C6280e.java`, clase `o`): cuando el propio demuxer salta por byte, se le
avisa al motor. Nosotros lo vemos implícitamente (llega como Range al proxy), así que el hueco es
menor — pero ellos lo saben *antes* de que el byte se pida.

### D. Dos instancias de player

`new C6280e[2]`, ambas creadas al arrancar. La 0 lleva preview y miniaturas; la 1 corre sin
feedback al motor ni shared buffer. Es una arquitectura de zapping/preview. No es portable
directamente, pero es la respuesta a "cómo cambian de canal sin pantalla negra".

### E. Miniaturas de seek servidas por el backend

`snapinfo_url` en el `Status` → `ThumbnailRequest`. Nosotros las generamos localmente del frame.
No es un hueco: es una decisión distinta, y la nuestra funciona sin backend.

### F. Debounce de replay

`OnReplayListener` → 1500 ms antes de reiniciar. Detalle menor.

---

## Medición en el Fire TV (2026-08-14)

Reanudando Dragon Ball E139 en 22:07, con la build del día instalada por ADB:

```
09:14:50.150  loadMedia start=1327653ms          ← pedimos abrir en 22:07
09:14:50.319  ← pide rango=bytes=0-              ← VLC abre en el BYTE 0
09:14:51.155  ⏱ abrió en 1025ms → primera imagen ← ...del PRINCIPIO del capítulo
09:14:51.448  PAUSA (buffering) en pos=0ms
09:14:52.549  ← pide rango=bytes=116649419-      ← recién ACÁ salta a 22:07
09:14:52.621  ventana de salto: servido de memoria (2924KB, sin red)
09:14:52.951  REANUDO tras 1501ms · CORRIENDO a los 2819ms
```

**`:start-time` no abre en el minuto guardado.** VLC abre en el byte 0, decodifica un frame de ahí
y recién entonces salta. O sea que ya hacíamos lo mismo que el decompilado (abrir en 0 → arrancar →
saltar) pero **sin controlarlo**: ellos hacen el salto explícito, le avisan a la capa de entrega y
prenden el spinner en ese instante.

Dos correcciones a lo que suponía antes de medir:

- **El precalentado del salto funciona.** Sirvió el destino de memoria, sin red. No hay carrera: la
  ventana se registra antes de empezar a llenarse y quien sirve espera a que lleguen los bytes.
- **Los 1501 ms no son del CDN**, son del seek interno de VLC. Esta corrida fue SANA.

### El defecto que sí quedó demostrado — ✅ ARREGLADO

Esa primera imagen (la del principio) prendía `huboImagen` y **apagaba el spinner**. El usuario se
quedaba 1,5 s mirando un fotograma congelado del principio, con el audio ya sonando: se ve igual que
un cuelgue y encima muestra contenido equivocado.

`EsperaDePrimeraImagen` ahora sigue esperando mientras el reloj no haya llegado al punto pedido
(5 tests, con margen de aterrizaje de 10 s porque el salto cae en el keyframe anterior). Verificado
en device:

```
09:30:19.747  ⏱ abrió en 842ms (primera imagen)
09:30:19.874  spinner=true · sinImagen=true    ← sigue puesto durante el salto
09:30:21.781  spinner=false                     ← se apaga 146 ms antes de...
09:30:21.927  REANUDO tras 1692ms · CORRIENDO a los 3017ms
```

### Verificación de los otros cambios

| Cambio | Resultado |
|---|---|
| `AvisoDeSalto` | ✅ `aviso de salto al proxy: pos=1475468ms de 1479646ms (fraccion=0.997)` → `salto precalentado: 4450KB en 1095ms`. Salto instantáneo y con imagen |
| `:audio-language` | Probable: con 2 pistas de audio, `applyPreferredAudio` no tuvo que corregir nada (cero líneas `auto-audio ->`). No concluyente |
| Contenedor como dato | Sin verificar — el gateway no está desplegado, así que `container` viene vacío y cae a la extensión |
| Caching de vivo / cambio de red | Sin probar |

### El arranque lento: causa raíz encontrada (5 escenarios, 09:35)

Cinco reproducciones seguidas midiendo `loadMedia → primera imagen`:

| # | Qué | startPos | formato | 1ª imagen | CORRIENDO |
|---|---|---|---|---|---|
| 1 | Peli **nueva** | 0 | **mpegts** | **5376 ms** | **6967 ms** |
| 2 | Peli en curso | 16,7 s | mpegts | 421 ms | 3156 ms |
| 3 | Cap. en curso | 7:05 | mp4 | 854 ms | 1975 ms |
| 4 | Cap. **nuevo** | 0 | mp4 | 1525 ms | 1869 ms |
| 5 | Peli en curso | 22,3 s | mpegts | 393 ms | 2729 ms |

**Son los títulos en MPEG-TS, la primera vez.** Con un TS, libVLC sondea el FINAL del archivo para
deducir la duración, y hasta que ese rango no llega no hay imagen. La comparación de los dos casos
fríos no deja dudas:

- **mpegts nuevo**: pide `bytes=0-` **y `bytes=660308868-`** (el final de 660 MB) → 5376 ms
- **mp4 nuevo**: pide **solo** `bytes=0-` → 1525 ms
- **mpegts ya visto**: 393-421 ms, porque la cola seguía en memoria

Y lo que convirtió eso en 5,4 s fue el CDN rechazando la cola dos veces:

```
09:35:12.667  ← pide rango=bytes=660308868-
09:35:13.921  origen rechazó ... con -1 (intento 1/3)
09:35:15.121  origen rechazó ... con -1 (intento 1/3)
09:35:16.320  precalentada la cola: 256KB en 6205ms
```

> El `intento 1/3` repetido NO es un bug del contador: son los dos tiros de `abrirConDuplicado`,
> cada uno con su propio bucle de reintentos. Esa defensa ya existía y su KDoc describe este mismo
> fallo — o sea que atacar los rechazos del CDN ya estaba hecho y no alcanzaba.

### La cola, guardada en disco — ✅ HECHO y verificado

`ColaEnDisco.kt` (8 tests). `ColaCaliente` ya servía el sondeo de memoria, pero esos 256 KB vivían
en un `ConcurrentHashMap` que se vacía en cada arranque — y un Fire TV mata la app apenas se va al
fondo, así que la "primera vez" era casi siempre. Ahora se persisten (clave = SHA-1 de la URL de
origen, estable porque la de magis no lleva el token adentro).

Medido con el mismo título en mpegts (1,1 GB), matando la app entre corridas:

| | Cola | 1ª imagen | CORRIENDO |
|---|---|---|---|
| Sin caché de disco, CDN rechazando | 6205 ms | **5376 ms** | 6967 ms |
| Run A (descarga limpia, guarda en disco) | 720 ms | 755 ms | 3659 ms |
| Run B (**leída del disco**) | **0 ms, sin red** | **584 ms** | 3198 ms |

```
09:59:56.313  cola del disco: 256KB desde 1145114884 (total=1145377028) sin tocar la red
09:59:57.238  cola caliente: bytes=1145127028- servido de memoria (250000B, sin red)
[...5 sondeos de EOF más, todos de memoria...]
09:59:57.466  ⏱ abrió en 584ms
```

libVLC hizo **6 sondeos de EOF distintos** en ese archivo, todos servidos sin red. El caso
patológico desaparece porque ya no queda ninguna petición que pueda fallar.

### El caso malo de la reanudación no se reprodujo

Las dos reanudaciones medidas salieron sanas (2,8 s y 3,0 s) y sin ningún rescate (`FALLO` no
apareció). Lo que haría fallar el camino, y hay que buscar cuando vuelva a pasar:

- Que `precalentarSalto` NO corra: está guardado por `duracionGuardada > 0` en `PlayerViewModel`.
- Que el CDN esté lento esa vez (0,2 s a 20 s por rango), y la ventana se llene más lento de lo que
  VLC lee.
- Que el decodificador por hardware falle en frío → ahí sí aparece `FALLO motivo=…` y son 10-12 s.

## Pendiente de verificar en device

Ya verificados arriba: el aviso de salto y el spinner de la reanudación. Quedan:

1. El colchón de 8 s en el canal en vivo (abrir un canal y mirar los cortes).
2. El corte de conexiones por cambio de red (poner algo a reproducir y apagar el WiFi).
3. `:audio-language` de forma concluyente (hace falta ver qué pista queda activa, no solo que
   nadie la corrija).
4. El contenedor como dato — no se puede hasta desplegar el gateway.

## Pendiente de desplegar

El gateway se despliega con rsync + `docker compose build` en `blog` (no `git pull`). El bump de
caché deja el primer `resolve` de cada título en frío.
