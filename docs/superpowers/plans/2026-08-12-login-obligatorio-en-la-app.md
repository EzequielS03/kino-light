# Login obligatorio en la app — Plan de implementación (3 de 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app no abra sin una sesión válida, que registrarse exija un código de licencia, y que el APK deje de llevar ningún secreto.

**Architecture:** La app deja de hablarle a PocketBase para todo lo que es identidad y pasa por `/v1/cuenta` del gateway, que ya está desplegado y probado. La sesión de la persona (token de PocketBase) se persiste y se manda en `Authorization`; `MainActivity` no compone nada sin ella. Al final, la llave compartida sale del APK **y al mismo tiempo** `require_sesion` entra en los nueve routers de contenido del gateway.

**Tech Stack:** Kotlin, Jetpack Compose (celular + TV/Leanback), OkHttp, Room, `EncryptedSharedPreferences`. Del lado del gateway: Python 3.12 / FastAPI.

**Repos:** `archive` (la app) y `arkiv-api` (las tareas 7 y 8).

**Specs y planes previos:** [Spec](../specs/2026-08-12-licencias-y-login-obligatorio-design.md) · [Plan 1 — CLI de licencias](2026-08-12-licencias-backend.md) (terminado) · [Plan 2 — identidad en el gateway](2026-08-12-identidad-en-el-gateway.md) (terminado y desplegado).

## El contrato que ya existe

Verificado contra el código desplegado el 2026-08-12, no contra el plan que lo describía. Todos bajo `/v1/cuenta`, y **todos exigen todavía `X-Arkiv-Key`** hasta la Task 8.

| Endpoint | `Authorization` | Body | Éxito |
|---|---|---|---|
| `POST /registrar` | token del **aparato** | `{email, password, licencia}` | `201 {userId, accountId}` |
| `POST /aparatos` | token de la **persona** | `{deviceToken}` | `200 {kind, usados, tope, yaEra}` |
| `GET /aparatos` | token de la **persona** | — | `200 {aparatos: [{id, kind, nombre, ultimoUso}]}` |
| `DELETE /aparatos/{id}` | token de la **persona** | — | `204` |

Todo error trae `{"detail": {"codigo": "...", "mensaje": "..."}}`. Los códigos que la app tiene que saber ramificar:

| `codigo` | HTTP | Qué pasó |
|---|---|---|
| `sesion_invalida` | 401 | El token no vale. **Volver a la pantalla de entrada.** |
| `licencia_no_vigente` | 403 | Revocada. **Volver a la pantalla de entrada.** |
| `identidad_invalida` | 403 | Datos corruptos. Volver a la entrada, mensaje de soporte. |
| `backend_no_disponible` | 503 | **NO cortar la sesión.** Avisar y reintentar. |
| `licencia_invalida` | 400 | El código no existe, ya se usó o fue revocado. Error inline. |
| `email_en_uso` | 409 | Ese email ya tiene cuenta. Error inline. |
| `datos_invalidos` | 400 | Email o contraseña mal. Error inline. |
| `sin_device` | 401 | El aparato no está dado de alta. |
| `device_ya_registrado` | 409 | Este aparato ya tiene una cuenta. |
| `tope_alcanzado` | 403 | Sin cupo de ese tipo → mandar a "Mis aparatos". |
| `aparato_de_otra_cuenta` | 403 | Ese aparato ya es de otra persona. |
| `aparato_no_encontrado` | 404 | |
| `tipo_invalido` | 400 | El aparato no dice si es celular o TV. |
| `candado_ocupado` | 409 | Otra operación sobre el mismo cupo. **Reintentable en el momento.** |

## Global Constraints

- **Comentarios y KDoc en español CON tildes** (es la convención del repo `archive`; la de "sin tildes" es de `arkiv-api`). Explicar el porqué, no el qué.
- **Commits en español sin tildes**, `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. **NUNCA** `Co-Authored-By`.
- **Nunca `git add -A` ni `git add .`** — este working tree lo comparten varias sesiones de Claude y la rama cambia sola. Nombrá los archivos.
- Tests: `./gradlew :app:testDebugUnitTest`. En `arkiv-api`, `uv run pytest -q` (arranca en 618).
- **Compilar SIEMPRE desde `/Users/cristian/archive`**: `app/build.gradle.kts` lee el `.env` de la raíz del repo, y compilar desde otro directorio deja la llave vacía y todo da 401 sin avisar.
- **Un arreglo cuya reversión deja los tests en verde no está cubierto.** Mutá lo que escribas y comprobá que algo se ponga rojo. En el plan 2 aparecieron catorce hallazgos serios debajo de suites en verde.
- Nunca loguear tokens, contraseñas ni códigos de licencia completos.
- **Verificar en device, no solo en tests.** El celular es `R5CX7251VRM` (USB) y el Fire TV `192.168.1.22:5555` (`adb connect`). En el Fire TV **no funciona `input tap`**: usar `input keyevent` (DPAD). Y antes de mandarle taps al celular, confirmar que no lo esté usando una persona.

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `pocketbase/SesionDePersona.kt` | **Nuevo.** Persiste y expone el token de la persona; lo refresca; lo borra al cerrar sesión. |
| `data/gateway/CuentaApi.kt` | **Nuevo.** Los cuatro endpoints y la traducción de `{codigo, mensaje}` a un `sealed` de Kotlin. |
| `pocketbase/AccountManager.kt` | Modificar: `registrar` pasa por el gateway con licencia; `logout` deja de re-bootstrapear anónimo. |
| `ui/entrada/PantallaDeEntrada.kt` | **Nuevo.** Login / registro, en celular. |
| `ui/entrada/EntradaViewModel.kt` | **Nuevo.** |
| `ui/tv/TvPantallaDeEntrada.kt` | **Nuevo.** En TV solo dice cómo parear; sin login manual. |
| `MainActivity.kt` | Modificar: el gate de sesión, junto al de integridad que ya existe. |
| `ui/settings/MisAparatos.kt` | **Nuevo.** Lista y saca aparatos. |
| `pairing/PairingManager.kt` | Modificar: el pareo pasa por `POST /v1/cuenta/aparatos`. |
| Los **7** que mandan `X-Arkiv-Key` | Modificar en la Task 8: `ArkivApiClient`, `LiveApi`, `TmdbApi`, `SimklApi`, `MirrorApiClient`, `SubtitleApi`, `MagisLinkClient`. |


## Sobre el grano de este plan

Las tareas 1 a 4 estan especificadas al detalle porque son la base y no dependen de nada que
todavia no exista. Las tareas 5 a 8 estan al grano de "que hay que lograr y en que orden", no al
de "escribi esta funcion": su codigo depende de la superficie exacta de `CuentaApi` y
`SesionDePersona`, que recien queda fijada al terminar las tareas 1 y 2. Escribir hoy el codigo
literal de la Task 8 seria inventarlo.

**Antes de despachar cada una de las tareas 5 a 8, expandi sus pasos** contra el codigo que para
entonces ya exista, igual que estan las 1 a 4. El orden de los pasos dentro de esas tareas si es
vinculante — sobre todo el de la 7 y el de la 8, donde invertirlo deja aparatos sin arrancar o
endpoints sin autenticacion.

---

### Task 1: La sesión de la persona se persiste

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pocketbase/SesionDePersona.kt`
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/SecureDeviceStore.kt`, `DeviceStore` (la interfaz)
- Test: `app/src/test/java/com/arkiv/player/pocketbase/SesionDePersonaTest.kt`

**Interfaces:**
- Produces: `SesionDePersona` con `fun token(): String?`, `suspend fun iniciar(email, password)`, `suspend fun refrescar(): Boolean`, `fun cerrar()`, y `val estado: StateFlow<EstadoDeSesion>` (`Sin` | `Con(email)`).

Hoy `SecureDeviceStore` guarda la identidad del aparato, su token y el **email** de la persona — pero **no el token de la persona**. `AccountManager.login` autentica y lo tira. Sin ese token no se le puede pedir nada al gateway en nombre de la persona, así que esto va primero.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
class SesionDePersonaTest {
    @Test fun `guarda el token al iniciar y lo devuelve`() { ... }
    @Test fun `cerrar borra el token y el email`() { ... }
    @Test fun `refrescar cambia el token cuando PocketBase da uno nuevo`() { ... }
    @Test fun `refrescar devuelve false y NO borra la sesion si no hay red`() { ... }
}
```

El cuarto es el que importa: **quedarse sin red no puede cerrar la sesión**. Es la misma distinción que costó dos rondas en el gateway — un fallo de transporte no es un rechazo de identidad.

- [ ] **Step 2: Correrlo y ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*SesionDePersonaTest*'`
Expected: FAIL, la clase no existe.

- [ ] **Step 3: Implementar**

El token va en `EncryptedSharedPreferences`, el mismo `SecureDeviceStore` que ya guarda el del aparato — no en un `SharedPreferences` común. Sumá `savePersonToken`/`personToken`/`clearPersonToken` a la interfaz `DeviceStore` y a su implementación.

- [ ] **Step 4: Correr los tests** → PASS

- [ ] **Step 5: Mutar.** Hacé que `refrescar()` borre la sesión ante cualquier excepción y comprobá que el cuarto test se pone rojo.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/SesionDePersona.kt app/src/main/java/com/arkiv/player/pocketbase/SecureDeviceStore.kt app/src/test/java/com/arkiv/player/pocketbase/SesionDePersonaTest.kt
git commit -m "feat(cuenta): persistir la sesion de la persona, no solo su email"
```

---

### Task 2: El cliente de `/v1/cuenta`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/CuentaApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/CuentaApiTest.kt`

**Interfaces:**
- Produces: `CuentaApi` con `suspend fun registrar(email, password, licencia): Registro`, `suspend fun adoptarAparato(deviceToken): Adopcion`, `suspend fun listarAparatos(): List<Aparato>`, `suspend fun sacarAparato(id)`. Y `sealed class ErrorDeCuenta` con una rama por cada `codigo` de la tabla del encabezado, más `Desconocido(codigo, mensaje)`.
- Consumes: `SesionDePersona` (Task 1), `SettingsStore` para la URL y la llave.

**La rama `Desconocido` no es opcional.** El gateway puede sumar códigos después; una app que explote ante un `codigo` que no conoce es una app que se rompe con un deploy del servidor.

- [ ] **Step 1: Escribir los tests con `MockWebServer`** (ya es dependencia del repo). Cubrí: cada `codigo` de la tabla mapeado a su rama; un cuerpo sin `detail`; un `codigo` inventado → `Desconocido`; un 503 → la rama de backend caído; una respuesta que no es JSON.

- [ ] **Step 2: Correrlos y ver que fallan.**

- [ ] **Step 3: Implementar.** Seguí la forma de `ArkivApiClient` para la URL base, los timeouts y el `X-Arkiv-Key`. El `Authorization` sale de `SesionDePersona.token()` salvo en `registrar`, que va con el token del **aparato**.

- [ ] **Step 4-5: Tests en verde, y mutar** — colapsá dos ramas distintas en una sola (por ejemplo `licencia_invalida` y `datos_invalidos`) y comprobá que algún test se queja.

- [ ] **Step 6: Commit**

---

### Task 3: Registrarse con un código

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt`
- Test: `app/src/test/java/com/arkiv/player/pocketbase/AccountManagerRegistroTest.kt`

Hoy `crearPocketBase()` escribe directo en la colección `users` con el token del aparato. **Eso ya no funciona**: `users.createRule` está en `null` desde el 2026-08-12. El registro tiene que pasar por `CuentaApi.registrar`.

- [ ] **Step 1: Escribir el test que falla** — que `registrar` llama al gateway y **no** a PocketBase; que un `licencia_invalida` no deja nada a medias; que al registrarse se guarda la sesión (Task 1).

- [ ] **Step 2-4: Implementar y verde.** Cuidado con el orden: Magis se vincula **después** de que la cuenta exista, no antes — hoy `registerSendCode` le pide un código a Magis primero, y si la licencia es inválida esa cuenta de Magis queda creada al pedo.

- [ ] **Step 5: Mutar** — que `registrar` ignore el error de licencia y siga; algún test tiene que ponerse rojo.

- [ ] **Step 6: Commit**

---

### Task 4: La pantalla de entrada, y que sin sesión no se componga nada

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/entrada/PantallaDeEntrada.kt`, `EntradaViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/MainActivity.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/entrada/EntradaViewModelTest.kt`

**El gate va donde ya está el de integridad** (`motivosParaNoArrancar()` en `MainActivity`), y por el mismo motivo que aquel: si no se puede entrar, no se arma ni Room, ni el sync, ni las filas del home.

- [ ] **Step 1: Test del ViewModel** — sin sesión → estado `Entrada`; con sesión → `Adentro`; un `backend_no_disponible` → **no** limpia la sesión y muestra reintentar; un `sesion_invalida` → sí la limpia.

- [ ] **Step 2-4: Implementar.** La pantalla tiene login y registro (email, contraseña, código). Respetá lo que ya existe: `AccountSection.kt` tiene los campos y los estados, no lo reescribas de cero.

- [ ] **Step 5: Mutar** — que un 503 limpie la sesión; el test tiene que ponerse rojo. Es el callejón que el spec describe: sin sesión y frente a una pantalla que tampoco funciona sin backend.

- [ ] **Step 6: Verificar en device** — instalar en el celular, cerrar sesión, matar la app, abrirla: tiene que pedir entrada. Poner el aparato en modo avión y abrirla: tiene que avisar, **no** mandar al login.

- [ ] **Step 7: Commit**

---

### Task 5: La TV entra solo por pareo

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvPantallaDeEntrada.kt`
- Modify: `MainActivity.kt` (la rama `isTv`), `pairing/PairingManager.kt`

En la TV no hay login manual: la pantalla muestra el QR y explica que se escanea desde el celular. Y el pareo pasa a llamar `POST /v1/cuenta/aparatos` con el token de la TV, así el alta **cuenta contra el tope**.

- [ ] **Step 1-4:** test, implementación, verde. Cubrí `tope_alcanzado` → mensaje que mande a "Mis aparatos" en el celular, y `candado_ocupado` → reintentar solo.

- [ ] **Step 5: Verificar en el Fire TV.** Acordate: `input tap` no funciona, usar `input keyevent`.

- [ ] **Step 6: Commit**

---

### Task 6: Mis aparatos

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/settings/MisAparatos.kt`
- Modify: `ui/settings/SettingsScreen.kt`, `ui/tv/TvSettingsScreen.kt`

- [ ] **Step 1-4:** lista (tipo, nombre, cuándo se usó) y sacar uno, con confirmación. Sacar el aparato **en el que estás** cierra la sesión ahí mismo: decilo en la confirmación.

- [ ] **Step 5: Mutar** — que sacar no confirme; test rojo.

- [ ] **Step 6: Commit**

---

### Task 7: El alta del aparato pasa por el gateway, y se cierran las reglas de `devices`

**Files:**
- `arkiv-api`: `src/arkiv_api/router/cuenta.py`, `src/arkiv_api/identidad/cuentas.py`
- `archive`: `pocketbase/DeviceAuthManager.kt`, y `docs/pocketbase/1786900200_updated_devices_rules.js`

Hoy `devices.createRule` acepta creaciones **sin autenticar y con cualquier `accountId`**, y `updateRule` deja que un aparato se mueva de cuenta solo. Mientras eso siga, el tope de aparatos se puede esquivar escribiendo directo a PocketBase, sin pasar por el candado de Redis. Cerrarlo exige que el alta anónima del aparato la haga el gateway.

**El orden importa y no es negociable:** primero sale el endpoint, después la app que lo usa, y **recién cuando esa app esté instalada en los dos aparatos** se cierran las reglas. Al revés, los aparatos instalados no arrancan.

- [ ] **Step 1:** `POST /v1/cuenta/aparatos/alta` en el gateway: crea el record de `devices` con credenciales de admin y devuelve lo que el aparato necesita para autenticarse. Con tests y mutación.
- [ ] **Step 2:** `DeviceAuthManager.ensureBootstrapped()` llama a ese endpoint en vez de escribir PocketBase.
- [ ] **Step 3:** Instalar en el celular y en el Fire TV, y **confirmar que los dos dan de alta su identidad** por el camino nuevo.
- [ ] **Step 4:** Recién ahí, la migración: `devices.createRule = null`, `devices.updateRule = null`. Aplicar y **probar el ataque**: crear un device sin autenticar tiene que dar 403, y mover un device de cuenta con un PATCH directo también.
- [ ] **Step 5: Commits** (uno por repo).

---

### Task 8: El corte limpio

**Files:**
- `archive`: los **7** que mandan `X-Arkiv-Key`, `data/SettingsStore.kt`, `app/build.gradle.kts`
- `arkiv-api`: los **9** routers de contenido (`catalog`, `resolve`, `stream`, `magis`, `sources`, `stats`, `anime`, `live`, `search` — los 10 que hoy tienen `require_key` menos `cuenta`), `src/arkiv_api/auth.py`

**Esta es UNA tarea, no dos, y ahí está todo el riesgo.** `require_sesion` hoy solo protege `/v1/cuenta`; los otros nueve (`catalog`, `resolve`, `stream`, `magis`, `sources`, `stats`, `anime`, `live`, `search`) van solo con la llave. Si se saca `ARKIV_API_KEY` de la app sin cablear la sesión en los routers de contenido, no quedan más seguros: quedan **sin ninguna autenticación**, abiertos a internet. Y si se cablea la sesión antes de que la app la mande, la app deja de funcionar.

- [ ] **Step 1:** En el gateway, sumar `require_sesion` a los nueve routers **junto a** `require_key` (los dos a la vez, aceptando cualquiera de los dos). Desplegar. La app vieja sigue andando.
- [ ] **Step 2:** En la app, mandar el `Authorization` de la persona en los 7 archivos, **sin sacar todavía** la llave. Instalar en los dos aparatos y verificar.
- [ ] **Step 3:** Sacar `X-Arkiv-Key` de los 7 y el `buildConfigField` de `app/build.gradle.kts`. Subir versión (`versionCode 11`, `versionName 0.6.0`). Instalar y verificar en los dos aparatos.
- [ ] **Step 4:** Recién ahí, sacar `require_key` del gateway y de los nueve routers. Desplegar.
- [ ] **Step 5:** Verificar contra producción que un pedido **sin** `Authorization` da 401, y que **con** la llave vieja también da 401.
- [ ] **Step 6:** Actualizar `docs/INVENTARIO_DE_LLAVES.md`: el APK queda sin ningún secreto.
- [ ] **Step 7: Commits** (uno por repo).

---

## Antes de empezar

Cuando esto salga, **la app instalada hoy deja de servir**: la nueva exige login y la vieja manda una llave que se retira. Hay que instalar en los dos aparatos, y los dos corren builds *debug* firmadas con otra llave que la publicada — el OTA no los alcanza, así que van por ADB.

Y hay una cuenta pendiente de hacer antes de la Task 4: hoy hay **dos cuentas** creadas (`cgarcialord@gmail.com` y `lauravgarcia25@gmail.com`), cada una con su licencia. Ninguna tiene contraseña conocida por este plan. Si el login obligatorio entra y esas contraseñas no se recuerdan, la salida es `python -m arkiv_api.licencias liberar <codigo> --si`, que **borra la cuenta** y deja la licencia lista para registrarse de nuevo.
