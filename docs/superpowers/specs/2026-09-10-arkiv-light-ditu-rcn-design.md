# Arkiv Light — Sub-proyecto 3: Caracol (Ditu) y RCN directos

Rama `light-magis`. Continúa 2B (`2026-09-09-arkiv-light-sin-login-design.md`), que dejó la app
abriendo sin login y sin una sola línea que hable con servidor propio.

## Contexto

Hoy la app tiene **una sola fuente**: el portal de Magis. La poda del sub-proyecto 1 borró Ditu
(Caracol Streaming) y nunca hubo RCN en esta rama; los dos viven en `main`, donde hablan por el
gateway `arkiv-api`. Este sub-proyecto los trae de vuelta hablando **directo desde el aparato**,
que es la regla no negociable de la rama.

`main` no es solo la referencia: es un árbol **en movimiento**. Al escribir este spec tiene trabajo
de Ditu/RCN sin commitear (`RcnScreen.kt`, `RcnLivePlayerScreen.kt` sin agregar, `CaracolScreen.kt`
modificado) y sus últimos commits pelean con los cortes de publicidad. Por eso la fuente de verdad
del puerto **no es `main`**, sino los adaptadores de Python del gateway
(`/Users/cristian/arkiv-api/src/arkiv_api/adapters/ditu/adapter.py`, 588 líneas, y `.../rcn/adapter.py`,
398), que son código de producción. Es la misma decisión que en 2A, donde el plan escrito tenía
varios errores que solo se veían contra el Python.

## Decisiones tomadas

**Entran las dos fuentes**, Ditu y RCN, y aparecen **mezcladas en la búsqueda Y con sección
propia**. O sea que `FuenteDeContenido` pasa de una implementación a tres, y además cada fuente
tiene su pantalla para navegar catálogo y canales.

**Se parte en 3A (Ditu) y 3B (RCN), en ese orden.** No recorta nada: ordena. Casi toda la
infraestructura es compartida —el DRM en el contrato, el reproductor Widevine, la mezcla de
resultados de varias fuentes, el patrón de "sección propia"— y conviene construirla una vez, con
una sola fuente encima para saber si quedó bien. Al terminar 3A, Caracol funciona de verdad y RCN
todavía no existe: nada queda a medias.

**Ninguna de las dos necesita un servidor propio ni una llave secreta nuestra.** Ditu no pide
autenticación de ningún tipo. RCN acuña un JWT anónimo en el aparato. El `signing_key` que reciben
los dos adaptadores en el gateway era para firmar el `ref` que viaja a la app, y eso ya lo
reemplazó `MagisRef` en 2A con un ref local.

**RCN se busca localmente.** Su API (Unity) **no expone búsqueda por texto** — el `search()` del
adaptador devuelve vacío a propósito y el comentario lo dice: "RCN es solo browse". Como sí tiene
que aparecer en la búsqueda unificada, se filtra por título sobre su catálogo, que es chico
(series paginadas de a 20 más tres películas) y ya se trae entero para la sección propia. Ditu no
tiene ese problema: su API busca de verdad.

**Solo RCN Colombia.** El adaptador soporta una variante `rcn_ar` que pasa por alto el geobloqueo
mandando un `X-Forwarded-For` con una IP de Buenos Aires. Queda afuera: no aporta contenido que
importe acá y no es algo que valga la pena portar a un cliente que se distribuye.

**El catálogo se cachea, no se pide en cada pantalla.** Ditu devuelve ~330 ítems en una sola
llamada y RCN pagina; `main` ya llegó a cachear Ditu 6 h con un botón para recargar a mano
(commit `27879861`), y esa decisión se hereda.

## Alcance

**Entra:** los dos clientes directos, la reproducción DASH+Widevine, los canales en vivo de las
dos, sus catálogos VOD, sus capítulos, la mezcla en la búsqueda y las secciones propias.

**Guardar en biblioteca: Ditu sí, RCN no.** No es una restricción de esfuerzo sino de los datos.
Lo que la biblioteca guarda es el `ref`, y el de Ditu codifica ids de Caracol que son estables: un
capítulo guardado hoy reproduce el mes que viene. Los de RCN no lo son — su API devuelve solo los
capítulos con derechos vigentes y la numeración se rehace sola, así que un `ref` guardado puede
terminar apuntando a otro capítulo o a ninguno. RCN se navega y se reproduce; no se guarda. La
opción de guardar no se muestra en sus resultados, en vez de mostrarla y fallar después.

**No entra:** filtrar la publicidad (en `main` ya se midió que no se puede y se quitó la sonda del
manifiesto, commit `aaa7b7bf`), la variante argentina de RCN, y cualquier fuente nueva.

## El protocolo de Ditu

API **AVS** de Caracol, sin autenticación. Base:

```
https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL
```

Headers obligatorios en TODA llamada; sin ellos el CDN responde 403 tanto en el manifiesto como en
los segmentos:

```
restful: yes
Accept: application/json, text/plain, */*
User-Agent: okhttp/4.12.0
```

### Reproducir: tres pasos

1. `CONTENT/DETAIL/{tipo}/{id}` → de `resultObj.containers[0].assets[]` sale el `assetId` del asset
   con `assetType == "MASTER"`; si no hay MASTER, el primero que tenga `assetId`.
2. `CONTENT/USERDATA/{tipo}/{id}` → **entitlement**. Si cualquiera de estos flags viene en `true`,
   no hay acceso y hay que decir por qué:

   | Flag | Mensaje |
   |---|---|
   | `isGeoBlocked` | solo disponible en Colombia |
   | `isChannelNotSubscribed` | requiere suscripción |
   | `isPCBlocked` | control parental activo |
   | `isContentOOHBlocked` | contenido OOH bloqueado |
   | `isGeofencedBlocked` | geofence bloqueado |
   | `isSportBlackoutBlocked` | deportes en blackout |
   | `isPlatformBlacklisted` | plataforma no permitida |

3. `CONTENT/VIDEOURL/{tipo}/{id}/{assetId}` → `resultObj.src` es el `.mpd`. **Y de la RESPUESTA
   HTTP, no del cuerpo, sale la cookie `playback_token`**, que hay que mandarle después al servidor
   de licencias como `Cookie: playback_token=<valor>`. Sin ella la licencia Widevine responde 500.

La URL de licencia es fija: `{BASE}/CONTENT/LICENSE`. Existe una variante para Android TV
(`.../ANDROIDTV/ALL/CONTENT/LICENSE`) que el gateway define pero **no usa**; se porta el
comportamiento que está probado (la de ANDROID) y se deja anotado que la otra existe.

### En vivo

`TRAY/LIVECHANNELS?orderBy=orderId&sortOrder=asc`. De cada container: `metadata.channelId`,
`channelName`, `channelType`, `orderId`, y se descartan los que no tengan `isActive`. El logo sale
de `assets[].logoMedium` (o `logoBig`/`logoSmall`). **El `assetId` viene en esa misma respuesta** —
importante, porque el EPG devuelve `assets` vacío para el programa en curso y no sirve.

Resolver es igual pero en dos pasos: `CONTENT/USERDATA/LIVE/{channelId}` y
`CONTENT/VIDEOURL/LIVE/{channelId}/{assetId}`, con la misma cookie.

### Catálogo, búsqueda y capítulos

- **Catálogo**: `TRAY/SEARCH/VOD?query=` (vacío) devuelve ~330 containers. Se quedan los de
  `contentType` en `BUNDLE` o `GROUP_OF_BUNDLES` (series) y los `VOD` con `contentSubtype == MOVIE`
  (películas).
- **Búsqueda**: el mismo endpoint con `query=<texto>`. `BUNDLE` es serie, todo lo demás película.
- **Capítulos**: `CONTENT/DETAIL/BUNDLE/{id}` y los episodios salen de
  `containers[0].containers[]`, cada uno con `metadata.episodeNumber`, `season` y `episodeTitle`.
  Un `GROUP_OF_BUNDLES` son varias temporadas: se piden sus hijos con
  `TRAY/SEARCH/VOD?filter_parentId={id}&filter_contentType=BUNDLE` y **el número de temporada es la
  posición del bundle en esa lista**, no un campo del episodio.
- **Imágenes**: del CDN propio, `https://image-registry.ditu.caracoltv.com/{pictureUrl}/` más
  `portrait-thin-promotional-tablet.jpg` (póster) o `landscape-regular-clean-tablet.jpg` (fondo).
  TMDB se usa **solo** para el `tmdbId` y el título canónico; sus imágenes entran únicamente si
  Ditu no trajo ninguna.

## El protocolo de RCN

API **Unity** (`https://unity.tbxapis.com/v0`). Headers: `User-Agent: okhttp/4.9.0`,
`X-Client-Id: <client key de CO>`, `X-Platform: android`, `X-App-Code`.

**Token**: `POST /auth/public` con el client key en el cuerpo devuelve un JWT anónimo. Dura 48 h y
se renueva 5 minutos antes de vencer. `expires_in` viene a veces como segundos y a veces como una
fecha ISO — hay que aguantar las dos formas, es el tipo de detalle que solo se descubre en
producción.

- **Canales**: `GET /contents?contentType=BROADCAST&limit=20` (son cinco en Colombia).
- **Catálogo**: `GET /contents?contentType=SERIE&limit=20&page=N` — Unity pagina por **número de
  página, 1-indexed**, no por offset. Las tres películas (`contentType=MOVIE`) solo se piden en la
  primera página.
- **Capítulos**: `GET /contents/{serieId}/episodes` devuelve **solo los que están dentro de la
  ventana de derechos activa**, así que la numeración se rehace secuencial desde 1 en vez de creer
  en el campo `episode`. El título y la temporada salen de `GET /contents/{serieId}`.
- **Reproducir**: `GET /contents/{id}/url?platform=android` devuelve `entitlements[]`. Se recorre
  buscando `type == "media"`: **primero un DASH sin DRM** (`hasDRM == false`), y si no hay, un
  DASH con `drm.widevine.licenseAcquisitionUrl`. El VOD a veces viene limpio; **el vivo siempre
  tiene DRM**.

## Componentes

### Lo que crece en el contrato (3A)

- **`GatewayPlayable` recupera el DRM**: `drmLicenseUrl: String` y
  `drmLicenseHeaders: Map<String, String>`, que la poda le quitó porque Magis no los usa. Es el
  camino por el que Widevine viaja hasta el reproductor sin que las pantallas se enteren.
- **`PlaySource`** gana `Ditu` en 3A y `Rcn` en 3B, cada una con su color de acento.
- **`FuenteDeContenido`** pasa a tener tres implementaciones. La interfaz **no cambia**: es
  exactamente para esto que existe.

### Los clientes (`data/ditu/`, `data/rcn/`)

Mismo molde que `data/magis/`: un cliente HTTP con sus modelos, una capa de catálogo, una de
resolución, y una clase `*Fuente` que implementa `FuenteDeContenido` y traduce a los modelos
`Gateway*`. Los refs son **locales y legibles**, como `MagisRef`: para Ditu, `contentId` más
`contentType`; para RCN, el `contentId` solo. Nada que firmar, nada que venza.

### El reproductor

Un `DrmExoPlayer` con `DefaultDrmSessionManager` + `FrameworkMediaDrm` y un `HttpMediaDrmCallback`
al que se le pasan los `drmLicenseHeaders` como propiedades de la petición de llave. La rama ya usa
ExoPlayer para Magis (VOD y vivo), pero **nunca negoció una licencia Widevine**: eso es lo nuevo.

VLC no entra en esta discusión: no soporta Widevine y cualquier intento termina en 403 en los
segmentos. Sigue siendo el reproductor de los archivos locales, como dice el `CLAUDE.md` de la
rama.

### La mezcla de la búsqueda

Hoy `search` tiene una sola fuente y sus eventos van derecho a la pantalla. Con tres hay que
decidir orden y qué hacer cuando una falla: **una fuente caída no puede vaciar la búsqueda de las
otras**. Los resultados llegan por `Flow`, así que se emiten a medida que cada fuente contesta, y
el error de una se muestra sin tapar lo que las demás sí trajeron.

## Riesgos

- **Widevine es el riesgo real de 3A.** Es lo único que la rama nunca hizo, falla con errores poco
  descriptivos, y depende de una cookie que sale de una respuesta HTTP anterior. Si la licencia no
  se negocia, no hay imagen: no hay degradación elegante posible.
- **El KALLEY R3 es un box barato.** Ya se vio en 2A que su decodificador se cae al encadenar
  reproducciones (`OMX.realtek.video.decoder` tumbando el `mediaserver`). DASH+Widevine es más
  exigente que el MPEG-TS de Magis; hay que probar en ese aparato temprano, no al final.
- **La publicidad corta el stream.** `main` ya midió que no se puede filtrar; lo que hay es
  recuperarse del `Source error` cuando pasa. Portar el arreglo, no volver a intentar el filtro.
- **El geobloqueo hace que la app parezca rota.** `isGeoBlocked` con un mensaje genérico se lee
  como un bug. Los siete flags de entitlement tienen que llegar a la pantalla con su texto.
- **RCN devuelve solo los capítulos con derechos vigentes**, así que la numeración cambia sola con
  el tiempo. Renumerar secuencial es lo correcto, pero significa que un capítulo guardado en la
  biblioteca puede quedar apuntando a otro número.

## Verificación

Cada paso: suite completa en verde y `assembleDebug`.

En el KALLEY R3, al terminar 3A: un canal en vivo de Caracol reproduce con Widevine, una serie del
catálogo abre sus capítulos y reproduce, la búsqueda muestra resultados de Magis y de Ditu juntos,
y un título de Ditu guardado en la biblioteca sigue reproduciendo al día siguiente (los ids de
Caracol son estables, a diferencia del token de Magis).

Al terminar 3B, lo mismo para RCN, más que su token se renueve solo después de 48 h — que es el
único camino que ningún test puede apurar y que hay que dejar corriendo.
