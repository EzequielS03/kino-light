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
(`api.comparadorinternet.co`) y la URL de distribución del APK, que ya no es
`apk.comparadorinternet.co` sino `github.com/lordmacu/kino-light/releases`
(pipeline de OTA vía GitHub Releases, ver
`docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md`).
Ninguna de las dos necesita protegerse -- son endpoints públicos que exigen
autenticación por sesión, no por conocer la URL.

**Rotar credenciales ya no obliga a redistribuir.** Antes, cambiar `ARKIV_API_KEY` en el gateway
dejaba fuera a todo aparato que no recibiera un APK nuevo. Ahora cada aparato tiene la suya: se
revoca una sesión (o se saca un aparato de la cuenta) sin tocar a los demás ni publicar nada.

---

## El repo (y el APK) son públicos: las llaves de terceros salieron del `BuildConfig` (2026-09-15)

Con el pipeline de OTA por GitHub Releases (2026-09-14) el repo pasó a público y el APK de
release quedó como una descarga pública sin autenticación (`github.com/lordmacu/kino-light/releases`).
Hasta ese momento quedaban cinco llaves de terceros compiladas en `BuildConfig` -- la 3DES de Magis
(`IPTV_3DES_KEY`), la de TMDB (`API_KEY`), y `IPTV_HOSTS` / `IPTV_APP_ID` / `IPTV_APK_VERSION` --
extraíbles con `apktool` o `jadx` sobre el APK publicado, sin acceso al repo ni al `.env`.

**Eso ya no es así.** Ninguno de esos cinco valores se compila más al binario: el `buildConfigField`
que los inyectaba no existe. Ahora cada valor viaja **partido en dos mitades**
(ver `docs/superpowers/specs/2026-09-15-split-credential-activation-design.md`):

- Una mitad va en un blob cifrado, `credentials.enc`, publicado como asset del último release de
  GitHub y descargado por la app.
- La otra va dentro de una librería nativa (`.so`) cuyo código fuente (`app/src/main/cpp/*.cpp`,
  `*.h`) está en `.gitignore` y nunca se commitea; el workflow de release lo decodifica desde un
  secreto de GitHub justo antes de compilar.
- Las dos mitades se recombinan **en el aparato**, y solo después de que la persona toque
  "Activar": la pantalla de consentimiento que bloquea toda la navegación en el primer arranque.
  El resultado queda en `EncryptedSharedPreferences` (`RemoteCredentialsStore`); nadie más lee esos
  valores.

### Qué sigue sin resolver

Esto **sube el costo, no cierra la puerta**, y el propio spec lo dice sin adornos: si el código de
la app puede descifrar algo, también puede hacerlo quien esté dispuesto a desensamblar ese mismo
código. Concretamente:

- El `.so` compilado viaja en el APK público. Con Ghidra o IDA se le puede sacar la llave AES y las
  mitades nativas, y descifrar `credentials.enc` en un PC sin siquiera correr la app. Lo que se
  ganó es que ya no alcanza con un `unzip` + `strings` ni con buscar una constante en `jadx`:
  hacen falta dos extracciones distintas, y una de ellas es ingeniería inversa de binario.
- El reparto no beneficia parejo a las cinco. `TMDB_API_KEY` viaja como parámetro de query en cada
  request a TMDB, y `IPTV_HOSTS` / `IPTV_APP_ID` / `IPTV_APK_VERSION` muy probablemente también
  aparezcan en las peticiones reales al portal: cualquiera con un proxy MITM las lee completas, sin
  tocar el APK. La única que nunca viaja por la red es `IPTV_3DES_KEY`, y es por eso la única donde
  este mecanismo protege contra algo que la captura de tráfico no resolvía ya. Aplicarlo a las cinco
  fue una decisión consciente por consistencia, no un descuido.
- Rotar los valores de verdad sigue exigiendo una release nueva: el refresco periódico solo
  recupera una copia local perdida o corrupta, no sobrevive a un cambio de valor
  (ver "Consequence accepted" en el spec).
