# Inventario de llaves del APK

Qué credenciales viajan realmente dentro del APK de Arkiv, cuáles no, y qué implica cada una.

Verificado el **2026-08-12** contra el `BuildConfig` generado de la build 0.5.3 (versionCode 9), no
contra lo que dice la documentación.

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

## Lo que SÍ viaja dentro del APK

Son **dos**, y las dos salen del `.env` de la raíz por `buildConfigField`
(`app/build.gradle.kts`).

| Campo | Para qué sirve | Dónde se lee |
|---|---|---|
| `ARKIV_API_KEY` | Credencial **única** del gateway `api.comparadorinternet.co`. Cubre búsqueda, catálogo, Magis y resolución de fuentes. Viaja en la cabecera `X-Arkiv-Key`. | `SettingsStore.DEFAULT_ARKIV_API_KEY` |
| `REFRESH_API_KEY` | La credencial **anterior**. Sigue viva en un solo call site: `SearchViewModel.kt:581`. | `SettingsStore.DEFAULT_REFRESH_API_KEY` |

Las dos entran como **valor por defecto** de una preferencia, así que se pueden sobrescribir sin
recompilar: desde ajustes, o llegando por el pareo del celular al TV (`PairingManager`).

---

## Lo que está en el `.env` pero NO llega al APK

De las 11 variables del `.env`, **nueve no viajan**.

**Firma** — solo se usan al construir; no quedan en el binario:

- `RELEASE_KEYSTORE_PATH`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

**Residuales** — ningún `buildConfigField` las lee. Esas credenciales viven hoy en el gateway, y la
app las alcanza por `/v1/catalog/*`:

- `API_KEY` y `READ_ACCESS_TOKEN` (TMDB)
- `SIMKL_CLIENT_ID` y `SIMKL_CLIENT_SECRET`
- `SUBITLE_API` (OpenSubtitles)

---

## Credenciales hardcodeadas: ninguna

Buscando literales largos en todo `app/src/main/java` solo aparecen alfabetos (`PairCode`,
`DeviceIdentity`) y nombres de preferencias. Tampoco hay llaves en `res/values`.

**Nada de Magis viaja en el APK.** Las credenciales del portal (`IPTV_*`) viven solo en `blog`; la
app llega a Magis a través del gateway. Por eso este inventario es tan corto.

---

## Lo que hay que tener presente

**La llave única ES todo el acceso.** Cualquiera que tenga el APK la extrae —está en texto plano en
el `BuildConfig`— y con eso tiene el gateway completo: catálogo, TMDB, Magis, el resolver. La URL de
distribución (`apk.comparadorinternet.co`) es pública, poco descubrible pero no privada.

**Rotarla obliga a redistribuir.** Al ser el valor por defecto compilado, cambiarla en el gateway
deja fuera a todo aparato que no reciba un APK nuevo. Hoy no hay forma de revocar el acceso de UN
dispositivo sin afectar a los demás.

**`REFRESH_API_KEY` es deuda.** Dos credenciales para lo mismo, con la migración a medio hacer. Se
puede terminar: hay un solo call site.

**Ofuscar no protege esto.** R8/ProGuard renombra símbolos, no oculta strings: la llave sigue
siendo legible en el APK. Lo único que cambia el modelo de amenaza es que el secreto no esté en el
cliente — por ejemplo, credencial por dispositivo emitida tras el alta en PocketBase, que es
revocable de a uno.
