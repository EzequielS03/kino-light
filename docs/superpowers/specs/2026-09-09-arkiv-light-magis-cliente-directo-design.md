# Arkiv Light — Sub-proyecto 2A: cliente Magis + TMDB directos

Rama: `light-magis`. Continúa el sub-proyecto 1 (poda estructural, completo y verificado en
dispositivo — ver `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md`).

## Contexto

Magis y TMDB siguen resolviéndose hoy vía el gateway `arkiv-api` (temporal, según ese mismo spec).
Este sub-proyecto reemplaza eso por clientes directos en Kotlin — Magis habla directo con su portal,
TMDB con su API — sin pasar por ningún servidor propio.

**Ya estaba resuelto en producción, no hace falta re-investigar nada.** El backend `arkiv-api`
(`/Users/cristian/arkiv-api`) tiene una implementación completa y probada del protocolo de Magis,
más una batería de tests de aceptación capturados contra el portal real. Este spec es en gran parte
un plan de **puerto** (Python → Kotlin), no de ingeniería inversa nueva. El origen de cada pieza:
`/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/` (`vendor/iptv_client.py`, `session.py`,
`adapter.py`, `live.py`, `sign_o3.py`, `tweaked_md5.py`) y sus tests en `tests/test_magis_*.py`.

## Alcance

**Sub-proyecto 2A** (este spec): cliente Magis directo (VOD + canal en vivo) + TMDB directo.
**Sub-proyecto 2B** (después, spec aparte): sacar login/PocketBase del todo.
**Sub-proyecto 3** (al final): Ditu directo.

Decisiones ya tomadas:
- Vincular cuenta Magis = solo email+contraseña de una cuenta **existente**. No se porta la
  creación de cuentas nuevas (`v3/snToken`+registro con código de verificación por email).
- EPG y recomendaciones ("Para ti") quedan fuera — el portal no da EPG real, y "Para ti" ya está
  roto desde el sub-proyecto 1 sin relación con esto.
- Login/PocketBase de Arkiv (el de la propia app, no el de Magis) no se toca en este sub-proyecto.

## Secretos (ya resueltos, en `.env` de este worktree)

`IPTV_3DES_KEY`, `IPTV_HOSTS`, `IPTV_APP_ID`, `IPTV_APK_VERSION` — mismos valores que usa
`arkiv-api` en producción (Coolify local), reusados en vez de re-derivar. `API_KEY` (TMDB) también
ya estaba en el `.env` de referencia del proyecto (de antes de que existiera el gateway). Se leen
con el mismo helper `readEnv()` que ya usa `app/build.gradle.kts` para `ARKIV_ADULT_CODE`/etc.

## Componentes nuevos

Todos bajo un paquete nuevo, ej. `com.arkiv.player.magis.directo/` (nombre exacto a definir en el
plan; no reusar el paquete `pocketbase/` existente aunque el patrón de sesión se le parezca):

1. **`MagisCrypto`** — cifrado/descifrado de bodies.
2. **`MagisPortalClient`** — HTTP de bajo nivel + failover + normalización de errores.
3. **`MagisSession`** — activación de dispositivo, login de cuenta, persistencia, reautenticación.
4. **`MagisCatalog`** — catálogo, búsqueda, detalle.
5. **`MagisResolve`** — resolución VOD (URL+headers finales para el CDN).
6. **`MagisLive`** — catálogo/resolución de canal en vivo + firma `sign_o3` por segmento.
7. **`TmdbApi`** (reescritura de la clase existente) — llamada directa a `api.themoviedb.org`.

### 1. `MagisCrypto`

Body de request y campo `data` de la respuesta, ambos igual:
```
wire = hex( base64( 3DES-EDE/ECB/PKCS5(json_utf8) ) )
```
En Kotlin: `Cipher.getInstance("DESede/ECB/PKCS5Padding")` con la llave de 24 bytes
(`IPTV_3DES_KEY`, ya en hex en el `.env` — decodificar a `ByteArray` antes de pasarla a
`SecretKeySpec`). Sin vectores de inicialización (modo ECB). Encriptar: JSON→UTF8 bytes→cifrar→
Base64→hex. Desencriptar: hex→bytes→Base64-decode→descifrar→UTF8 string.

### 2. `MagisPortalClient`

- Headers fijos en cada request: `Content-Type: application/json;charset=utf-8`, `apk` (=
  `IPTV_APP_ID`), `apkVer` (= `IPTV_APK_VERSION`), `spkgVer` (string tipo fecha, ver nota abajo),
  `User-Agent: okhttp/3.12.12`.
- Cada body lleva mezclado un diccionario `device` con ~16 campos (algunos fijos, algunos vacíos:
  `matadata`/`signdata` siempre vacíos salvo hardware STB real).
- **Failover de host**: `IPTV_HOSTS` trae 2 hosts separados por coma. Probar el primero; si falla
  por red (no por rechazo del portal), probar el segundo, en el mismo intento lógico.
- **Traducción de errores, sin excepciones en esta capa**: la respuesta puede traer
  `{"_error": "<código>", "_msg": "..."}` (portal respondió pero rechazó) o una excepción de red
  (`{"_exception": "..."}`). Devolver un tipo sellado (`sealed class MagisResult<T>`: `Ok`,
  `PortalError(codigo, msg)`, `RedError(causa)`) — las capas de arriba deciden qué hacer con cada
  uno, no esta.
- **Ritmo entre llamadas**: mínimo ~400ms entre pedidos al portal (medido contra el portal real por
  arkiv-api). Un `Mutex` + `System.currentTimeMillis()` de la última llamada alcanza — es un solo
  dispositivo, no hace falta nada distribuido.
- `spkgVer`: string con forma `"<fecha ISO> <número> <número>_"` — arkiv-api lo trae fijo desde su
  config; portar el mismo valor literal (ver el `.env`/config de referencia, no hace falta que
  cambie).

### 3. `MagisSession`

**Activación anónima (sin cuenta, alcanza para VOD):**
1. `v3/snToken` con datos de hardware inventados → devuelve `snToken`.
2. `sn = MD5(snToken + "ntFT65w6itH!lHCPw7D=@qnsFC5adD28").lower()` (salt fijo del framework
   coolx).
3. `v8/active` con ese `snToken` → `{userId, userToken, jwtToken}`.

**Login de cuenta real (necesario para canal en vivo — el portal rechaza vivo con sesión anónima):**
```kotlin
val passwordHash = MessageDigest.getInstance("MD5")
    .digest((password + "cloudstream").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }  // hex minúsculas
```
Body a `v8/login`: `{accountType: "2", userName: email, password: passwordHash, type: "1",
macAddr: "02:00:00:00:00:00", areaCode: "", verificationCode: "", verificationToken: "",
matadata: "", signdata: "", channel: "default"}`. **No hace falta activar el device primero** —
`login()` se llama directo, ahorra una activación.

**Persistencia**: `EncryptedSharedPreferences` — guarda `userId`/`userToken`/`jwtToken` de la
sesión activa, y (si hay cuenta vinculada) el email+password para poder relogar sola cuando el
token muere. **No hay heartbeat proactivo** (el portal no lo exige ni arkiv-api lo usa en
producción) — la detección de sesión muerta es reactiva: cuando una llamada devuelve
`aaa100027`/`aaa100028` ("no logueado"), reautenticar (con las credenciales guardadas si hay
cuenta, si no re-acuñar anónimo) y reintentar esa llamada **una sola vez**.

**Ping-pong de `sn`**: nunca reusar el mismo `sn` para dos "identidades" — el portal expulsa la
sesión anterior cada vez que se reactiva el mismo `sn`. Como acá hay un solo dispositivo con una
sola identidad a la vez, esto no debería morder — pero si el usuario desvincula y vuelve a vincular
una cuenta *distinta*, hay que re-acuñar un `sn` nuevo, no reusar el guardado.

### 4. `MagisCatalog`

Endpoints: `getNextColumns(portalCode)` → columnas → `getColumnContents(columnId)` → items;
`searchByName(query)`; `getItemData(contentId, type)` (`type`: `"1"` película, `"0"` serie —
trae `simpleProgramList` con los episodios si es serie).

### 5. `MagisResolve` (VOD)

1. Si es serie: resolver primero el capítulo concreto contra la lista de `getItemData` (por
   `contentId` de episodio), y llamar `startPlayVOD(episodioContentId, seriesContentId=serieId)`.
   Si es película: `startPlayVOD(contentId)` directo.
2. `getSlbInfo(type="merge")` — llamar **una vez por sesión**, no por título, y cachear (rota, no
   es fijo: no cachear "para siempre", sí durante la sesión activa). Trae el CDN de VOD y el
   `Content-Auth` de sesión.
3. Elegir la mejor pista: preferir `h264` sobre otros codecs; no exigir todas las condiciones a la
   vez (arkiv-api aprendió que exigir de más hacía caer a un codec peor cuando había uno mejor
   disponible).
4. Armar la URL final:
   ```
   url = "{cdn_base}/vod/{media.contentId}_media.{ext}"   // ext = "ts" si videoFormat=="ts", si no "mp4"
   headers = {
       "Content-Auth": auth,              // de getSlbInfo, cdn_list[tag=vod].url_list[sign_type=cfl,tag=free]
       "Content-License": license,        // de startPlayVOD().episodeList[0].totalMovieList[0].movieList[0].licenseList[0].license
       "User-Agent": "Ranger/4.9.4-17294ac0",  // el CDN exige este UA exacto, si no responde 401
       "App": IPTV_APP_ID,
       "App-Version": IPTV_APK_VERSION,
   }
   ```
   Esta URL+headers son exactamente lo que hoy le llega a `MagisExoPlayer` vía el proxy local — el
   proxy y el player **no cambian**, solo cambia quién arma esos 2 valores.
5. El `Content-Auth` trae su propio `expired=<unix>` embebido en el querystring — considerarlo
   vencido con 300s de margen antes del vencimiento real, no justo al filo.

### 6. `MagisLive`

**Requiere cuenta real vinculada** — con sesión anónima el portal rechaza el vivo
(`aaa100028 未登录!`), a diferencia del VOD que sí funciona anónimo.

1. `startPlayLive(channelCode)` → `liveAddressList[]`.
2. **Trampa a no repetir**: tomar `playCode` y `license` de **la misma** entrada de la lista —
   nunca cruzar el `playCode` de una entrada con el `license` de otra. El `playCode` (nombre real
   en el CDN) casi nunca coincide con el `channelCode` pedido.
3. `getSlbInfo(type="merge", live_codes=[playCode])` → filtrar `cdn_list[tag="live"]` con
   `sign_type=cfl`. Ojo: el campo `url` de estas entradas es un **querystring suelto sin `?`**
   (ej. `"cdn_type=1&sign_type=cfl&token=ABC"`), no una URL completa.
4. Devolver **todos** los CDNs de respaldo que traiga la respuesta, no solo el primero — un CDN
   puede rechazar con 401 habiendo otro disponible en la misma respuesta.
5. URL final: `http://{cflHost}/live/{playCode}.m3u8` (con el `playCode`, no el `channelCode`).
6. `Content-Auth` fresco **por request/segmento** — incluye un `sign2` que vence rápido, calculado
   con `sign_o3`:
   ```
   msg = "token={token}&sign2_method=sign_o3&instance=0&start_moment={momento}"
       + bytes("salt3333=4") + hex("980d0a1532c9c3821708c0")
   sign2 = tweaked_md5(msg)   // MD5 con el message-schedule y 4 constantes K alteradas
   ```
   Portar `tweaked_md5.py` (`/Users/cristian/arkiv-api/.../adapters/magis/tweaked_md5.py`) línea
   por línea a Kotlin: es MD5 estándar con el message-schedule de la primera vuelta cambiado a
   `[10,11,12,13,14,15,6,7,8,9,0,1,2,3,4,5]` y las constantes `K` en los índices 42,45,54,62
   alteradas — no depende de nada nativo. **Validar contra los 5 vectores de
   `tests/test_magis_sign_o3.py` antes de darlo por bueno** — son el contrato exacto (mismo
   `token`+`momento` → mismo `sign2` que produce la app real).
7. Headers adicionales del segmento: `Ranger-Id`, `X-Buffer`, `App`, `App-Version`,
   `User-Agent: Ranger/4.9.4-...` (mismo patrón que VOD).
8. `LiveHlsProxy`/`LiveExoPlayer` (del sub-proyecto 1) **no cambian** — siguen sirviendo la URL
   local de siempre; lo único que cambia es que `MagisLive` (no ya el gateway) les da la URL+auth
   reales por detrás, y ahora hay que refrescar el `sign_o3` periódicamente en vez de pedirle al
   gateway un `Content-Auth` ya armado.

### 7. `TmdbApi` (reescritura)

Llamada REST directa a `api.themoviedb.org/3/...` con `api_key=<API_KEY del .env>` como query
param (API v3, no el bearer token v4). Mismo shape de datos que ya consume la UI (`TmdbItem`,
`TmdbDetail`, capítulos por temporada) — solo cambia la base URL y quién pone la key.

## Reintentos (resumen)

- VOD: reintentar **cualquier** `PortalError` una vez, tras reautenticar (los mensajes de error
  vienen en chino, no discriminar por texto).
- Vivo: reintentar **solo** ante los códigos de sesión muerta (`aaa100027`, `aaa100028`) — no ante
  cualquier error, para no doblar carga en errores de canal que no se arreglan reautenticando.
- Antes de llamar al portal, si no hay `userToken` en memoria, fallar directo con un error propio
  en vez de pegarle al portal (evita 2 llamadas desperdiciadas en un caso ya sabido).

## Testing

**Ventaja clave: hay vectores de aceptación ya capturados contra el portal real**, no hace falta
inventar tests ni volver a probar contra Magis para validar el crypto:
- `tests/test_magis_sntoken.py` (`test_login_hashea_password_con_salt_cloudstream`) — fija el
  contrato de `MD5(password + "cloudstream")`.
- `tests/test_magis_sign_o3.py` — 5 vectores `(token, momento) → sign2` para validar
  `tweaked_md5`.

Portar estos como tests JVM unitarios de las funciones de crypto/hash en Kotlin, sin red — deben
pasar exactos antes de conectar nada contra el portal real.

## UX de vincular Magis

Pantalla simple de email+contraseña (existe algo así hoy, se simplifica sacando el paso de
código/confirmar). Llama a `MagisSession.login()` en vez de al gateway. Si falla (contraseña
incorrecta, cuenta no existe), mostrar el error tal cual — no hay forma de crear cuenta desde acá.

## Se retira

- `pocketbase/MagisLinkClient.kt` (reemplazado por `MagisSession`).
- Los métodos de Magis en `AccountManager.kt` (`vincularMagis*`, `desvincularMagis`,
  `refrescarMagis`) — pasan a llamar a `MagisSession` en vez del gateway.
- `TmdbApi.kt` actual (vía gateway) — se reescribe para llamar directo.
- De `ArkivApiClient.kt`: `search()` (acotado a Magis), `resolve()`, `episodes()`, y el módulo
  `LiveApi.kt` — todo lo que hoy resuelve Magis/vivo vía gateway.

## Criterio de éxito

- Sin cuenta vinculada: catálogo/búsqueda/reproducción VOD de Magis funcionan (activación
  anónima).
- Con cuenta vinculada (email+password): además funciona el canal en vivo con zapeo.
- TMDB (carátulas, sinopsis) funciona sin pasar por ningún servidor propio.
- Cero llamadas al gateway `arkiv-api` para Magis/TMDB — solo quedan las de login/PocketBase (sin
  tocar, sub-proyecto 2B) y trivia (excepción permanente).
- Los tests de crypto/hash portados pasan exacto contra los vectores de arkiv-api.
- Verificado en dispositivo real (celular + TV): mismo checklist que el sub-proyecto 1, ahora sin
  gateway de por medio para Magis/TMDB.

## Riesgos / decisiones para el plan de implementación

- `spkgVer` y los ~16 campos del diccionario `device` — copiar los valores exactos que usa
  `arkiv-api` (`vendor/iptv_client.py`/config), no inventarlos; un valor mal puesto puede rechazar
  con "versión detenida" en vez de un error claro.
- Confirmar en el plan el nombre final del paquete Kotlin para estos componentes.
- El `getSlbInfo` rota de host — no cachear su resultado más allá de la sesión activa.
- Verificar que Android permite `Cipher.getInstance("DESede/ECB/PKCS5Padding")` sin configuración
  extra (debería, es JCE estándar, pero confirmar en el dispositivo real de prueba — algunas builds
  de Android restringen algoritmos legacy).
