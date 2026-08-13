# Inventario de llaves del APK

Qué credenciales viajan realmente dentro del APK de Arkiv, cuáles no, y qué implica cada una.

Verificado el **2026-08-13** contra el `BuildConfig` generado de la build 0.6.0 (versionCode 10),
tras la Task 8 (Paso 3): sacar `ARKIV_API_KEY` de los ocho clientes que la mandaban y del
`buildConfigField` que la inyectaba. No contra lo que dice la documentación.

Cómo reproducir el inventario:

```bash
# 1. Lo único que se inyecta al binario
grep -n "buildConfigField" app/build.gradle.kts

# 2. La verdad definitiva: el BuildConfig generado
find app/build -name BuildConfig.java -path "*debug*" | head -1 | xargs grep "public static final"

# 3. Credenciales escritas a mano en el código (debe salir vacío de secretos)
grep -rEn '"[A-Za-z0-9_-]{24,}"' app/src/main/java | grep -viE "import |package |https?://|android"
```

---

## Lo que SÍ viaja dentro del APK: nada

El comando 1 no imprime ninguna línea: `buildConfigField` no existe más en `app/build.gradle.kts`.
El comando 2 no encuentra ningún campo de credencial en el `BuildConfig` generado -- solo las
constantes propias de Android/Gradle (`APPLICATION_ID`, `VERSION_CODE`, etc.). El comando 3 no
encuentra literales largos que sean secretos (ver la sección de abajo sobre qué SÍ aparece).

### `ARKIV_API_KEY`: ya no está (2026-08-13)

Era la **última** credencial de build que quedaba: una constante compilada, igual para todos los
aparatos, que viajaba en la cabecera `X-Arkiv-Key` de ocho clientes (`ArkivApiClient`, `LiveApi`,
`TmdbApi`, `SimklApi`, `MirrorApiClient`, `SubtitleApi`, `MagisLinkClient`, `CuentaApi`) y se
propagaba a la TV en cada pareo (`PairingManager.payloadDeExito`/`applySyncedGatewayConfig`).

Salió del todo: de los ocho clientes, del `buildConfigField`, de `SettingsStore`
(`DEFAULT_ARKIV_API_KEY`, la preferencia `arkiv_api_key` y `setArkivApiKey`), y del payload de
pareo. La app ya se autentica exclusivamente con la credencial POR DISPOSITIVO que emite el alta
en PocketBase -- sesión de la PERSONA (`Authorization`) + sesión del APARATO (`X-Arkiv-Device`),
revocable de a una desde "Mis aparatos" o `python -m arkiv_api.licencias liberar`. El TV, que no
tiene dónde tipear nada a mano, sigue recibiendo `gatewayUrl` por el mismo pareo -- eso no era un
secreto y no hacía falta sacarlo.

### `REFRESH_API_KEY`: ya no está (2026-08-12)

Era la llave del `POST /api/refresh` del **mirror**, que la app llamaba directo. Se usaba en un solo
lugar — el botón "procesar ahora" de la búsqueda. Ahora esa llamada pasa por el gateway
(`POST /v1/catalog/refresh`), que es quien pone la credencial del mirror; esa llave vive **solo en
`blog`**. Mismo movimiento que ya se había hecho con TMDB, OpenSubtitles y Simkl, y el mismo que
`ARKIV_API_KEY` completó para el resto del gateway.

---

## Lo que está en el `.env` pero NO llega al APK

De las 11 variables del `.env`, **ninguna** viaja ya al binario (antes eran nueve de once).

**Firma** — solo se usan al construir; no quedan en el binario:

- `RELEASE_KEYSTORE_PATH`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

**Residuales** — ningún `buildConfigField` las lee. Esas credenciales viven hoy en el gateway, y la
app las alcanza por `/v1/catalog/*` autenticada con su propia sesión:

- `API_KEY` y `READ_ACCESS_TOKEN` (TMDB)
- `SIMKL_CLIENT_ID` y `SIMKL_CLIENT_SECRET`
- `SUBITLE_API` (OpenSubtitles)
- `ARKIV_API_KEY` (la del gateway unificado, hasta la Task 8 Paso 3 -- ver arriba)

---

## Credenciales hardcodeadas: ninguna

Buscando literales largos en todo `app/src/main/java` solo aparecen alfabetos (`PairCode`,
`DeviceIdentity`) y nombres de preferencias. Tampoco hay llaves en `res/values`.

**Nada de Magis viaja en el APK.** Las credenciales del portal (`IPTV_*`) viven solo en `blog`; la
app llega a Magis a través del gateway. Por eso este inventario es tan corto.

---

## Lo que hay que tener presente

**El APK ya no lleva ningún secreto.** No hay una llave única que extraer del `BuildConfig`: toda
autenticación contra el gateway es la sesión de la PERSONA + la sesión del APARATO, emitidas al
darse de alta y revocables de a una (desde "Mis aparatos", o de raíz con
`python -m arkiv_api.licencias liberar` si hace falta borrar la cuenta entera). Extraer el APK ya
no le da a nadie una credencial que usar: sin una sesión propia, el gateway devuelve 401 (ver
`require_sesion`, Task 8 Paso 1).

**Lo que sigue siendo público, porque nunca fue secreto:** la URL del gateway
(`api.comparadorinternet.co`) y la URL de distribución del APK (`apk.comparadorinternet.co`). Ninguna
de las dos necesita protegerse -- son endpoints públicos que exigen autenticación por sesión, no
por conocer la URL.

**Rotar credenciales ya no obliga a redistribuir.** Antes, cambiar `ARKIV_API_KEY` en el gateway
dejaba fuera a todo aparato que no recibiera un APK nuevo. Ahora cada aparato tiene la suya: se
revoca una sesión (o se saca un aparato de la cuenta) sin tocar a los demás ni publicar nada.
