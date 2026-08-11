# Canal en vivo (TV IP de Magis) en Arkiv

Fecha: 2026-08-11

## Qué queremos

Una sección **"En vivo"** en Arkiv con los ~1.000 canales de TV IP de Magis: guía de programación
tipo decodificador de cable en el TV, grilla de canales en el celular, favoritos, zapping y envío al
TV / Chromecast. Entrada nueva: un icono en la barra superior del TV y un tab al lado de Inicio en el
celular.

Todo el camino técnico ya está resuelto y verificado en `/Users/cristian/magia` (`live_cfl.py`,
`sign_o3.py`, `iptv_client.py`, `MAGIA_RUNBOOK.md` §5). Este documento describe cómo se trae a Arkiv.

## El camino del vivo, tal como magia lo resolvió

```
live_categories()          live_data(columnId)         play_live(channelCode)
GET next_columns           GET v6/getLiveData          POST v6/startPlayLive
  → 30+ categorías           → channelList[]             → liveAddressList[0].license
                               channelCode, name,
                               channelNumber
                                       │
                                       ▼
                          get_slb(type="merge", live_codes=[canal])
                             → cdn_list[] con tag="live" y sign_type=cfl
                               main_addr = host CDN (ROTA en cada llamada)
                               url       = base del Content-Auth, con &token=<32 hex>
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        GET http://<cflHost>/live/<canal>.m3u8   GET <segHost>/live/…/<seg>.ts
        Headers: Content-Auth, Content-License   Mismos headers, con Content-Auth
                 User-Agent: Ranger/4.9.4-…      re-firmado por request
        → m3u8 con URLs de segmento ABSOLUTAS    → los segmentos CADUCAN en segundos
```

El `Content-Auth` de cada request es la url base más `&sign2_method=sign_o3&instance=0
&start_moment=<epoch_ms>&sign2=<firma>`.

**Dato que define la arquitectura: la firma no depende de la URL del segmento.** Solo de
`(token, start_moment)`:

```
SALT  = b"salt3333=4" + bytes.fromhex("980d0a1532c9c3821708c0")
msg   = f"token={TOKEN}&sign2_method=sign_o3&instance=0&start_moment={MOMENT}" + SALT
sign2 = tweaked_md5(msg)
```

Por eso el que firma y el que descarga los bytes pueden ser dos máquinas distintas.

## Fase 0 (bloqueante): `tweaked_md5` sin el binario

Hoy `sign_o3.py` **necesita `libranger-jni.so`**: mapea el ELF, aplica las relocaciones
`R_AARCH64_RELATIVE`/`DT_RELR` y emula con Unicorn la función de compresión en `0x529178`. El "100%
Python" del README significa "sin APK ni Frida en runtime", no "sin el binario". Ese `.so` está en el
`.gitignore` de magia, es propietario y no se redistribuye.

El VOD de Magis que ya funciona en el gateway **no usa `sign2`** — solo `Content-Auth` con el token
de sesión. La emulación es exclusiva del vivo, y es la única pieza nueva realmente difícil.

**El tweak es acotado.** El runbook (§5) confirma que conserva IV, constantes K, shifts y padding de
MD5 estándar: lo que cambia es el *message schedule*. Se recupera por diferencial en el Mac —variar
una palabra del bloque a la vez sobre la emulación de Unicorn y observar qué rondas se mueven— hasta
reconstruir el schedule completo, incluidas las rondas que no encajen con MD5 estándar.

- **Entregable:** `tweaked_md5` en Python puro dentro de arkiv-api, sin `unicorn` y sin el `.so`.
- **Criterio de aceptación:** los **5 vectores reales** de `sign_o3.py:213` pasan, los cinco.
- **Time-box:** una sesión de trabajo. Si al terminarla el schedule no está completo y verificado,
  se activa la contingencia y el vivo sigue adelante sin esperar.
- **Contingencia:** se firma con Unicorn y el `.so` copiado a blog por scp, fuera de git. **El
  contrato de la API es idéntico**, así que la app y el resto del gateway no se enteran del cambio, y
  el port puro puede retomarse después sin tocar nada más.

## Gateway: `/v1/live/*`

| Ruta | Devuelve | Coste en el portal |
|------|----------|--------------------|
| `GET /v1/live/categories` | Categorías (Deportes, Colombia, Cine y Series, Vivo gratis…) | 1 llamada, cache de horas |
| `GET /v1/live/channels?category=&page=&size=` | `channelCode`, nombre, número y logo si existe | 1 llamada, cache de horas |
| `GET /v1/live/epg?channels=a,b,c` | Programación por canal desde Redis; lo ausente se encola y se responde parcial | **0 llamadas en caliente** |
| `POST /v1/live/resolve` | `{cflHost, authBase, license, channel, expiresAt}` | 2 llamadas (~3 s por el rate-limit) |
| `POST /v1/live/sign` | `[{moment, sign2}, …]` | **cómputo puro**, no toca el portal |

Reglas que se heredan y no se negocian:

- **Los bytes de video no cruzan el gateway.** blog es un NUC Celeron N3050 en swap; el gateway solo
  mueve JSON diminuto. Un proxy HLS del lado servidor lo tumbaría con un solo espectador.
- **El bucket de 1,5 s de magis vive en Redis** (`store/ratelimit.py`) y aplica a `categories`,
  `channels`, `epg` y `resolve`. `sign` no lo toca porque no habla con el portal.
- La sesión de Magis es la que ya administra `adapters/magis/session.py`, con el header
  `X-Arkiv-Account` que el cliente ya envía.

**Sin verificar todavía: si `channelList[]` trae logo del canal.** El CLI de magia solo pinta `name`
y `channelNumber`, así que el campo de imagen no está confirmado. Se comprueba en la primera llamada
real de la Fase 1. Si no viene, el fallback es una tarjeta con el número grande y el nombre —el mismo
tratamiento tipográfico que ya usan las tarjetas sin póster—, y la guía del TV no cambia de forma.

### La EPG y el rate-limit

`v3/getProgram` es **una llamada por canal**. A 1,5 s por llamada, barrer 1.000 canales toma ~25
minutos: pedir la guía en caliente es inviable. Por eso:

- Un worker de fondo llena Redis respetando el bucket, con prioridad **canales pedidos hace poco →
  favoritos → el resto**.
- `GET /v1/live/epg` responde **siempre al instante** con lo que hay y una lista `missing` de lo que
  encoló. Nunca bloquea.
- Los datos de programación tienen TTL propio y se refrescan por franjas.

### Excepción consciente al patrón de `ref` opaco

En el resto del gateway la app recibe un `ref` firmado que nunca interpreta. Aquí no se puede: el
proxy del dispositivo necesita `cflHost`, `authBase` y `license` en claro para armar los headers de
cada request. Es una excepción deliberada, limitada al vivo y a datos que caducan con la sesión.

## App: proxy local y reproducción

VLC solo acepta `referer` y `user-agent` como opciones (`VlcPlayer.kt:640`), no headers arbitrarios
como `Content-Auth`. **Un proxy HLS local es obligatorio**, en cualquier variante de la arquitectura.
Ya existe el patrón: `ArchiveCacheProxy` sirve en `127.0.0.1:$port`.

`LiveHlsProxy` (nuevo, junto a `ArchiveCacheProxy`):

- `GET /live.m3u8` — baja el playlist de `http://<cflHost>/live/<canal>.m3u8` con los tres headers y
  reescribe cada URL absoluta de `.ts` hacia `/seg?u=<url>`.
- `GET /seg?u=` — hace streaming del segmento upstream con el `Content-Auth` firmado vigente.
- Mantiene un **pool de firmas** pedido al gateway y lo rellena antes de agotarse.
- Ante un `403`: invalida el pool, pide firma fresca y reintenta **una** vez. Si vuelve a fallar,
  re-resuelve el canal completo, porque lo que caducó es la sesión y no la firma.

**A verificar en Fase 0, porque define el tamaño del lote:** si el CDN acepta `start_moment`
futuros, la app pide ~20 firmas de una y queda autónoma varios minutos; si exige "ahora", pide una
por ventana (~1 request cada 5–10 s). El endpoint sirve a los dos casos sin cambiar de forma.

VLC abre `http://127.0.0.1:<puerto>/live.m3u8` y no sabe nada de todo lo anterior.

## Interfaz

### Entrada

- **TV:** `TvNavButton` con icono `LiveTv` y etiqueta "En vivo" en la barra superior del home
  (`TvHomeScreen.kt:244`), junto a Buscar y Mi biblioteca. Ruta `live` en `ArkivTvRoot`.
- **Celular:** cuarto tab "En vivo" al lado de Inicio en `TABS` (`ArkivRoot.kt:96`).

### TV — la guía es la pantalla

Columna izquierda fija con el canal (logo, número, nombre). A la derecha, timeline por franjas
horarias: cada programa es un bloque de ancho proporcional a su duración, con una línea roja vertical
marcando el ahora. Arriba, chips de categoría con **Favoritos** y **Recientes** de primeros.

El D-pad se comporta como un decodificador: arriba/abajo cambia de canal, izquierda/derecha viaja en
el tiempo dentro de la fila, y al volver al borde del "ahora" el foco sube a los chips.

- OK sobre el programa en curso → reproduce el canal.
- OK sobre un programa futuro → ficha con sinopsis y horario, y un botón "Ver canal ahora". **No hay
  grabación ni recordatorios**, y es mejor decirlo que fingir un botón que no hace nada.

Las filas sin programación cargada muestran un esqueleto "Cargando programación…" y se rellenan
solas. **La navegación nunca se bloquea esperando la EPG.** Solo se pide la guía de las filas
visibles más un margen, en lotes.

### Celular — grilla, guía a un botón

Buscador por nombre o número arriba, chips de categoría, y tarjetas con logo grande, número, nombre y
"Ahora: <programa>" con una barra fina de avance del programa en curso. Mantener pulsado marca
favorito.

Un botón "Guía" abre la programación en **formato vertical** —lista de canales que despliegan su
día—, porque un timeline 2D en un teléfono se pelea con el scroll.

### Reproductor en vivo

- Modo vivo explícito: distintivo rojo **EN VIVO**, sin barra de progreso, sin seek y sin "continuar
  viendo". Esto además esquiva de raíz el problema conocido de la barra sin duración del CDN de
  Magis: en vivo no hay duración que sondear.
- Overlay de 3 s al abrir y al zapear: número, nombre, logo, "ahora" y "a continuación".
- **Zapping** con arriba/abajo (TV) o swipe vertical (celular), recorriendo **la lista con la que se
  entró** —la categoría, los favoritos o el resultado de búsqueda—, que es la que el usuario tiene en
  la cabeza.
- **Pre-resolve del vecino:** resolver un canal cuesta ~3 s por el rate-limit, así que cuando el
  overlay lleva ~1 s quieto se resuelve por lo bajo el canal siguiente y el anterior. Mismo patrón que
  la precarga del siguiente capítulo: best-effort y cancelable.

### Favoritos, recientes y envío al TV

Favoritos y recientes son tablas nuevas en Room con el trigger de sync existente, así que viajan
entre celu y TV como el resto de la biblioteca (LWW + tombstones).

El envío al TV / Chromecast reusa el diálogo de destino del VOD. Dos avisos:

- Para el Chromecast el proxy local debe bindearse a la **IP de la LAN**, no a `127.0.0.1`.
- Varios canales traen audio **AC-3**: les aplica el transcode de solo audio ya resuelto para los MKV.

## Datos, errores y pruebas

**Dónde vive cada cosa.** Categorías y canales se cachean en Room con TTL de horas, para que la
sección abra al instante y sobreviva a un gateway lento. La EPG vive en Redis del lado del gateway y
en memoria del lado de la app. Favoritos y recientes son tablas sincronizadas. **Nada de esto guarda
tokens:** `authBase`, licencia y firmas son de sesión y mueren con el reproductor.

**Errores, dichos en voz alta.**

- Sin cuenta Magis conectada, la sección muestra un estado vacío con la acción de conectar (reusa lo
  de Ajustes), no una pantalla rota.
- Si un canal no resuelve, el mensaje lo dice y el zapping sigue funcionando hacia los vecinos.
- Si el gateway está caído, la grilla se pinta desde la caché local y solo falla al reproducir, con
  un mensaje concreto en vez de un spinner eterno.

**Pruebas.**

- Los 5 vectores reales son el test de aceptación de `tweaked_md5`. Si pasan, la firma es correcta.
- El proxy, contra un m3u8 fijo: reescribe las URLs absolutas, inyecta los tres headers, y ante un
  403 refresca y reintenta exactamente una vez.
- Router de vivo con el portal simulado; y una prueba de que el worker de EPG respeta el bucket de
  1,5 s aunque le pidan mil canales de golpe.

## Fuera de alcance, a propósito

Grabación, timeshift o pausa del directo, fuentes de vivo que no sean Magis (listas M3U propias) y
guía de más de 24 horas.
