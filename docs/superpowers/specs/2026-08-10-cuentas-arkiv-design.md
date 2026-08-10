# Spec 1 — Cuentas Arkiv (la "persona")

- **Fecha:** 2026-08-10
- **Estado:** Diseño aprobado (pendiente de plan de implementación)
- **Repos afectados:** `archive` (app Android) + PocketBase self-host en `blog` (`db.comparadorinternet.co`)
- **NO afectado:** `arkiv-api` (gateway). Magis se toca en el Spec 2.

## Contexto y problema

Hoy la app consume todas las fuentes (incluida Magis) de forma **anónima**. El objetivo del
usuario es que Magis se use con la **cuenta de cada persona** y que el token persista en el
backend por esa persona, sin que las búsquedas/reproducciones de un usuario interfieran con las
de otro, y permitiendo que un device esté logueado mientras otro está anónimo.

Al aterrizarlo se identificaron **dos subsistemas** con dependencia entre ellos:

1. **Cuentas Arkiv (la "persona")** — auth propia en nuestro backend, para que exista una
   identidad de persona que cruza dispositivos. *Este spec.*
2. **Vínculo Magis por persona** — guardar credenciales + token del portal Magis colgados de la
   persona del Spec 1, y usarlos en búsqueda/reproducción con aislamiento por usuario. *Spec 2,
   documento aparte.*

El usuario eligió construir **(1) primero** (fundacional). Este documento cubre solo (1).

### Lo que ya existe (base sobre la que construimos)

- PocketBase self-host en `db.comparadorinternet.co`, colección **`devices`** (auth), un record
  por dispositivo con `email = <deviceId>@arkiv.local` + clave generada de 32 chars.
  Ver `pocketbase/DeviceAuthManager.kt`, `pocketbase/DeviceIdentity.kt`,
  `pocketbase/SecureDeviceStore.kt`, `pocketbase/PocketBaseConfig.kt`.
- Campo **`accountId`** (UUID) que **agrupa los devices de una persona**. El celu crea el
  `accountId`; la TV lo **adopta** al parear (`DeviceIdentityFactory.newTvDevice(accountId)` +
  `DeviceAuthManager.adoptIdentity(...)`, disparado por `pairing/PairingManager.kt`).
- **Cloudsync** (`cloudsync/*`) sincroniza items/episodes/playback/markers con PocketBase.
  Cada fila lleva un campo `accountId` (lo agrega `SyncMappers` **en el push**, no vive en las
  entidades Room) y se filtra por `accountId` al leer (`PbSyncClient`). Merge por LWW
  (`LwwMerge.kt`), offline-first.

**Consecuencia clave:** `accountId` ya *es* el concepto de "persona"; lo único que falta es poder
**autenticarse como esa persona** (email+clave elegidos por el usuario, no la credencial
generada por device) y la UI de registro/login. "Loguearte en tu cuenta" ≈ el pairing de hoy,
pero sin QR.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Modelo de cuenta (Magis, contexto general) | **Por usuario** (multi-cuenta), no una cuenta global. |
| Dueño del token / la "persona" | **Cuenta Arkiv** (login a nuestro backend), no el device. |
| Orden de construcción | **Cuentas Arkiv primero**; Magis después (Spec 2). |
| Alcance v1 de cuentas | **Mínimo:** registro + login + logout. Sin verificación de email, sin recuperar contraseña. |
| Historial anónimo al loguear | **Fusionar** el historial anónimo del device en la cuenta. |
| Identidad de cliente hacia el gateway (Spec 2) | Reusar `deviceId` existente. *(Aplica al Spec 2; anotado aquí para continuidad.)* |

## Modelo de datos (PocketBase)

- **Colección auth nueva `users`** (la persona):
  - `email` (auth), `password` (auth).
  - `accountId` (text, requerido): el id canónico de agrupación de esa persona. Es el **mismo
    valor** que ya usa `devices.accountId` / cloudsync.
- **`devices`** sin cambios de forma. En login, el record del device pasa a llevar el `accountId`
  de la persona (para que la persona "posea" ese device; mismo efecto que `newTvDevice`).
- **Colecciones de sync** (items/episodes/playback/markers): sin cambios. Siguen keyeadas por el
  campo `accountId`.

`PocketBaseConfig` gana `COLLECTION_USERS = "users"`.

## Flujos

### Registro (crear persona)
1. `DeviceAuthManager.ensureBootstrapped()` garantiza que el device tenga un `accountId` (A_anon).
2. `createRecord("users", {email, password, passwordConfirm, accountId: A_anon})`.
3. Se marca el estado local "logueado como `<email>`".
4. **Sin fusión:** el `accountId` no cambia (A_anon), así que el historial anónimo actual del
   device pasa a ser el de la cuenta *en el lugar*.
5. Error `email ya registrado` → mensaje inline, no se crea nada.

### Login (persona existente)
1. `authWithPassword("users", email, password)` → persona `{id, accountId: A_persona, token}`.
2. Se actualiza el record `devices` de este aparato: `accountId = A_persona` (con el token del
   device; mismo permiso que ya usa el pairing para operar sobre un `accountId` ajeno).
3. Se fija el **accountId efectivo** = A_persona y se persiste "logueado como `<email>`".
4. **Fusión** (si A_anon ≠ A_persona): se reinician los cursores/frontier de push y se re-empujan
   todas las filas locales bajo A_persona; luego se hace pull de lo que la persona ya tenía. LWW
   resuelve los choques. Las filas viejas bajo A_anon quedan huérfanas en el server (inofensivas;
   limpieza opcional futura).
   - Como las entidades Room **no** almacenan `accountId` (lo pone `SyncMappers` en el push), la
     "fusión" es cambiar el accountId efectivo + forzar re-push, sin re-keyear la base local.
5. Login inválido → error inline; el device sigue anónimo, sin tocar nada.

### Logout
1. Se corta el estado de persona.
2. El device vuelve a anónimo: se re-bootstrapea una identidad de device nueva (nuevo `accountId`),
   igual que `createNewAccount()`. La biblioteca local refleja el estado anónimo (vacío / re-pull).
3. Los datos de la persona quedan a salvo en el server bajo A_persona.

### Anónimo (default)
- Login es **opcional**. Sin login, el device opera anónimo exactamente como hoy. Otro device
  puede estar logueado mientras este sigue anónimo, sin interferencia (aislamiento por `accountId`).

## Arquitectura en la app (Android)

- **`AccountManager`** (nuevo, en `pocketbase/`), paralelo a `DeviceAuthManager`:
  - `register(email, password)`, `login(email, password)`, `logout()`.
  - Expone `StateFlow<AccountState>`: `Anonimo` | `Conectado(email, accountId)`.
  - Es la fuente del **accountId efectivo**. `CloudSyncManager` (hoy lee
    `deviceAuth.session.value?.accountId`) pasa a leer el accountId efectivo del `AccountManager`
    (persona si hay, device si no).
  - Offline-first y con mutex, como `DeviceAuthManager` (red caída → reintento, nunca crashea).
- **Persistencia:** el estado de cuenta (email + accountId efectivo + flag logueado) se guarda en
  `SecureDeviceStore` (o un store análogo). No se guarda la clave de la persona en claro tras el
  login (solo se usa para autenticar y obtener token).
- **UI:** sección **"Cuenta"** en `ui/settings/SettingsScreen.kt` y `ui/tv/TvSettingsScreen.kt`:
  - Anónimo → formulario email+clave con tabs/botones "Iniciar sesión" / "Crear cuenta".
  - Conectado → "Conectado como `<email>`" + "Cerrar sesión".
- **Wiring:** `AppGraph.kt` construye e inyecta el `AccountManager`; `CloudSyncManager` recibe el
  accountId efectivo desde ahí.
- **Reúso:** la adopción de `accountId` sigue el camino ya probado de
  `adoptIdentity`/`newTvDevice`; no se inventa mecánica nueva de sync.

## Cambios en el servidor (PocketBase en `blog`)

- Crear la colección auth **`users`** con el campo `accountId` y reglas:
  - **Create** público (registro).
  - **Auth** por email+clave.
  - Reglas de lectura/escritura de las colecciones de sync: ya permiten operar por `accountId` con
    token de device (es lo que usa el pairing). Verificar que sigan cubriendo el caso "device con
    token propio escribiendo el `accountId` de la persona".
- Sin SMTP en v1 (no hay recuperación ni verificación de email).

## Manejo de errores

- Email ya registrado → inline, sin crear.
- Login inválido (400/401/403) → inline, device sigue anónimo.
- Red caída → offline-first, reintento; nunca crashea (patrón `ensureBootstrapped`).
- Fusión interrumpida → idempotente por LWW (`updatedAt`); se completa en el próximo sync.
- Update del `accountId` del device falla tras auth OK → se reintenta; el login no se da por
  bueno hasta que el accountId efectivo quedó fijado.

## Testing

- **`AccountManager`:** register / login / logout; que login fija el accountId efectivo correcto;
  que logout re-bootstrapea anónimo.
- **Fusión:** que tras login con A_anon ≠ A_persona se re-empujan las filas locales bajo
  A_persona (y que registrar no dispara fusión).
- **UI:** estado Anónimo ↔ Conectado en `SettingsScreen`/`TvSettingsScreen`.
- **Integración cloudsync:** que `CloudSyncManager` toma el accountId efectivo del `AccountManager`
  y no el del device cuando hay persona logueada.

## Fuera de alcance (a propósito)

- **Recuperar contraseña / verificación de email** (requieren SMTP en `blog`) — posible spec futuro.
- **Todo lo de Magis** — Spec 2: colgará las credenciales + token del portal Magis del
  `accountId` (o `users.id`) de la persona, con aislamiento por usuario y cache scopeada por
  sesión, sobre el gateway `arkiv-api`.
- Relación explícita `devices.owner → users.id`, pantalla "mis dispositivos", revocación remota —
  no hacen falta para v1 (basta la agrupación por `accountId`).

## Riesgos / preguntas abiertas

- **Reglas de PocketBase:** confirmar que un device (con su token) puede escribir/leer filas de
  sync bajo el `accountId` de la persona sin abrir un hueco de seguridad (hoy el pairing ya asume
  este modelo; conviene revisar las reglas al agregar `users`).
- **Filas huérfanas** bajo A_anon tras la fusión: inofensivas, pero conviene una limpieza futura.
- **Colisión de email** entre `users` y los `devices` (`@arkiv.local`): distinto dominio, sin
  colisión esperada; verificar que son colecciones auth independientes.

## Handoff al Spec 2 (Magis)

Con el Spec 1 en pie, el Spec 2 keyea las credenciales + token de Magis por la **persona**
(`accountId`/`users.id`), no por device: el token sigue a la persona entre celu/TV/Fire Stick,
un device anónimo usa la sesión Magis anónima compartida, y la cache de `resolve` del gateway se
scopea por sesión para no cruzar el `Content-Auth` de una cuenta con otra.
