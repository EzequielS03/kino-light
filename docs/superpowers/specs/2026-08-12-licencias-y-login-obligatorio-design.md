# Spec — Login obligatorio y licencias

- **Fecha:** 2026-08-12
- **Estado:** Diseño aprobado (pendiente de plan de implementación)
- **Repos afectados:** `archive` (app Android), `arkiv-api` (gateway) y PocketBase en `blog`
- **Relación con specs previos:** construye sobre el [Spec 1 — Cuentas Arkiv](2026-08-10-cuentas-arkiv-design.md)
  y el [Spec 2 — Magis por usuario](2026-08-10-magis-por-usuario-design.md), y **cambia dos
  decisiones de ellos** (ver abajo).

## Problema

Cualquiera que consiga el APK puede usar el gateway. La credencial (`ARKIV_API_KEY`) es una
**constante compilada, igual para todos los aparatos**: se extrae con un `unzip`, sin root y sin
tocar el dispositivo. Rotarla obliga a recompilar y redistribuir a todos, y no hay forma de cortarle
el acceso a UNA persona.

El objetivo es que **nadie use la app sin permiso explícito**, y que ese permiso se pueda **quitar**.

### Qué cambia de los specs previos

| Spec previo decía | Ahora |
|---|---|
| "Login es **opcional**. Sin login, el device opera anónimo exactamente como hoy." | Login **obligatorio**. Sin sesión no se entra. Deja de existir el modo anónimo. |
| "**NO afectado:** `arkiv-api` (gateway)." | El gateway **sí** cambia: valida sesión en vez de llave compartida. |

Todo lo demás de esos specs se mantiene: modelo de `accountId` como "persona", fusión del historial
al loguear, y el vínculo Magis colgado de la persona.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Quién puede registrarse | **Solo con licencia**. Sin código no hay cuenta. |
| Naturaleza del código | Una **licencia**, no una invitación: se consume al registrarse pero **sigue viva**, y el gateway la mira en cada pedido. |
| Vida de la licencia | Vale **hasta que se revoque**. Sin vencimiento ni renovación. |
| Aparatos por licencia | **1 celular + 1 TV**, tipado. El segundo del mismo tipo se rechaza. |
| Liberar un aparato | Pantalla **"Mis aparatos"** en ajustes; la persona saca el que no usa. |
| Login en la TV | **Solo por pareo con QR** desde el celular. No hay login manual en TV. |
| Backend caído | **Estricto: no abre.** Ni siquiera para contenido ya descargado. |
| Credencial hacia el gateway | El **token de PocketBase**. El APK deja de llevar secretos. |
| Migración | **Corte limpio**: la versión nueva no manda la llave vieja; los aparatos sin actualizar dejan de funcionar. |
| Cómo se crean las licencias | **CLI en `blog`** (repo `arkiv-api`), no desde la app ni por endpoint. |
| Magis | Sin vincular → sesión anónima por identidad (lo que ya funciona). Vinculando → credenciales propias (Spec 2). |

## Modelo de datos (PocketBase)

**Colección nueva `licencias`:**

| Campo | Tipo | Para qué |
|---|---|---|
| `codigo` | text, único | La licencia. La genera el CLI. |
| `estado` | select: `activa` \| `revocada` | Revocar es cambiar esto. |
| `maxCelulares` | number, default 1 | Tope de devices `kind = "phone"`. |
| `maxTvs` | number, default 1 | Tope de devices `kind = "tv"`. |
| `usadaPor` | text, opcional | Id de la cuenta que la consumió. Vacío = sin usar. |
| `notas` | text, opcional | Para el dueño: "hermana", "TV del living". |

**Colección nueva `users`** (auth, la "persona" del Spec 1): `email`, `password`, `accountId`
(text, requerido) y `licencia` (text, requerido).

Ni `usadaPor` ni `licencia` son *relations*: guardan el id y el código sueltos. El gateway resuelve
la licencia por código en cada validación, y una relación lo obligaría a expandirla en cada consulta
sin darle nada a cambio.

**`devices`**: sin cambios de forma. Ya tiene `accountId` y `kind` (`phone` / `tv`), que es lo que
el conteo por tipo necesita.

### El huevo y la gallina

Sin licencia no hay registro, y sin registro no hay app. La primera se crea con el CLI de abajo, y
con esa se registra el dueño. **No hay bootstrap automático desde la app ni desde el gateway** a
propósito: un camino que crea licencias solo, alcanzable desde internet, es exactamente el agujero
que este spec viene a cerrar.

## CLI de licencias (en `blog`)

Una herramienta de línea de comandos en el repo `arkiv-api`, que corre en `blog` y habla con
PocketBase con las credenciales de admin que ya viven ahí. Es el único camino para crear licencias.

| Comando | Qué hace |
|---|---|
| `crear [--notas "hermana"]` | Genera un código nuevo, lo guarda como `activa` y lo imprime. |
| `listar` | Todas las licencias: código, estado, quién la usa, cuántos aparatos tiene. |
| `revocar <codigo>` | La pasa a `revocada`. En ≤60 s esa persona queda afuera. |
| `reactivar <codigo>` | La vuelve a `activa`, por si se revocó por error. |
| `reasignar <email>` | Le da una licencia **nueva** a una cuenta que ya existe y revoca la vieja. La cuenta y sus datos quedan intactos. |
| `liberar <codigo> --si` | **Borra la cuenta** que usó esa licencia y la deja lista para registrarse de nuevo. |

`reasignar` y `liberar` cubren dos problemas distintos que es fácil confundir:

- **`reasignar`** — la persona sigue siendo la misma y conserva todo; lo que cambia es su licencia.
  Para cuando revocaste por error, o el código se filtró y querés cortarlo sin castigar a nadie.
- **`liberar`** — la cuenta se va. Es la salida del callejón "olvidé la contraseña": no hay
  recuperación de clave y el código figura consumido, así que sin esto esa persona no puede volver
  a entrar de ninguna forma.

**Por qué CLI y no un endpoint**: crear licencias es la operación más sensible del sistema. Un
endpoint hay que autenticarlo, exponerlo y cuidarlo; el CLI solo lo puede correr quien ya tiene SSH
a `blog`, que es el mismo que tiene las credenciales de admin. La seguridad sale gratis.

**El código lo genera el CLI**, no una persona: `secrets` con alfabeto sin caracteres ambiguos
(sin `0`/`O`, sin `1`/`l`), en grupos separados por guiones para poder dictarlo por teléfono sin
equivocarse.

## Flujos en la app

### Arranque
1. Si hay sesión de persona válida → la app como siempre.
2. Si no hay → **pantalla de entrada** (login / registro). No se compone nada más: ni servicios, ni
   sync.
3. Si no se puede hablar con el backend → aviso y no se entra. **Riesgo aceptado**, ver más abajo.

### Registro (solo desde el celular)
1. `DeviceAuthManager.ensureBootstrapped()` garantiza el `accountId` anónimo del aparato (A_anon).
2. Se valida el código contra `licencias`: existe, `estado = activa`, `usadaPor` vacío.
3. Se crea el `user` con `accountId = A_anon` y `licencia` = esa. Se marca `usadaPor`.
4. Como el `accountId` **no cambia**, la biblioteca local de ese aparato queda en su lugar sin
   migrar nada (mismo razonamiento del Spec 1).
5. Código inexistente, revocado o ya usado → error inline; no se crea nada.

### Login (celular)
Igual que el Spec 1: `authWithPassword`, el device adopta el `accountId` de la persona, y se fusiona
el historial local bajo ese `accountId`.

### Pareo de la TV
Sin cambios de mecánica: QR desde el celular, la TV adopta el `accountId` (`newTvDevice`). Lo nuevo
es que **cuenta contra el tope de TVs** de la licencia.

### Límite por tipo
Al registrar, loguear o parear se cuentan los `devices` de ese `accountId` por `kind`. Si el tipo ya
está completo, el aparato **no entra** y se muestra "llegaste al límite de aparatos", con el camino
a "Mis aparatos".

### Mis aparatos (ajustes)
Lista los `devices` de la persona (tipo, nombre, cuándo se usó) y permite sacar uno. Sacar un
aparato lo desconecta: su próxima llamada al gateway falla y vuelve a la pantalla de entrada.

### Logout
Corta la sesión y vuelve a la pantalla de entrada. **Ya no se re-bootstrapea una identidad anónima**
(eso decía el Spec 1 cuando el anónimo existía): sin sesión no hay app.

### Revocación
El dueño pone `estado = revocada`. En el próximo pedido —a lo sumo un minuto después, ver el TTL de
caché— todos los aparatos de esa persona reciben 403 y vuelven a la pantalla de entrada.

### Qué hace la app cuando el gateway la rechaza

Regla general: **cualquier rechazo de identidad manda a la pantalla de entrada**, se corta la sesión
local y hay que volver a entrar. Cubre licencia revocada, token vencido, aparato sacado desde "Mis
aparatos" y cuenta borrada — desde la app son el mismo hecho: esta sesión ya no vale.

Hay que separarlo de **no poder hablar con el backend**, que NO es un rechazo:

| Qué pasó | Qué ve la persona |
|---|---|
| 401 / 403 del gateway | Vuelve a la pantalla de entrada, sesión cortada. |
| Sin red, 5xx, timeout | Aviso de "no se pudo conectar" con reintentar. **No** se corta la sesión. |

La distinción importa porque mandarlo a la pantalla de entrada cuando el backend está caído lo
dejaría en un callejón: la pantalla de entrada tampoco puede funcionar sin backend, y encima habría
perdido la sesión que tenía.

## Gateway (`arkiv-api`)

La app manda el **token de PocketBase** en cada pedido. `require_key` se reemplaza por
`require_sesion`, que:

1. Valida el token contra PocketBase y obtiene la persona.
2. Lee su `licencia`. Si no existe o está revocada → **403**.
3. Cachea el resultado en Redis con **TTL de 60 s**. Sin caché, cada búsqueda le pega a PocketBase
   (que corre en un NUC saturado); con un TTL largo, revocar tardaría demasiado en surtir efecto.

`ARKIV_API_KEY` desaparece del `buildConfigField` y de `SettingsStore`. **El APK queda sin ningún
secreto**, que es el objetivo de todo esto: extraerlo deja de servir de nada.

El `accountId` deja de viajar en una cabecera propia (`X-Arkiv-Account`): sale del token, que es
más confiable porque el cliente no lo elige.

## Riesgos aceptados

**El backend es punto único de falla para USAR la app.** Con la regla estricta, una caída de
PocketBase o quedarse sin internet deja la app sin abrir, incluso para ver algo ya descargado en el
aparato. Se planteó una alternativa —validar contra la última verificación exitosa durante unos
días— y se descartó a favor del control estricto.

**Un token robado sirve hasta que se revoque la licencia.** No hay expiración corta ni rotación. Es
el compromiso de no construir emisión y renovación de tokens propios (la opción B que se descartó).

## Fuera de alcance (YAGNI)

- Recuperar contraseña y verificación de email (ya excluidos en el Spec 1).
- Vencimiento y renovación de licencias.
- Generación de licencias desde la app o desde el gateway: solo por el CLI en `blog`.
- Aviso automático cuando alguien se registra: se ve en el panel.
- Límite de reproducciones simultáneas: el tope es de aparatos registrados, no de streams.
