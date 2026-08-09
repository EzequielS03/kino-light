# Sesión de magis gestionada por el gateway — Design Spec

Fecha: 2026-08-09

## Objetivo

Que **el gateway sea el único que activa** la sesión del portal magis, y que esa sesión pueda
establecerse de dos formas —anónima o por cuenta— sirviendo a todos los dispositivos (celular y
TV) sin que se expulsen entre sí.

## El problema

El portal es de **sesión única por dispositivo**. Cuando otro cliente activa, el anterior queda
muerto y el portal avisa con `您的账号已经在其他设备登录` ("tu cuenta ya inició sesión en otro
dispositivo").

Hoy el gateway se recupera solo: ante cualquier `PortalError` descarta la sesión y reintenta una
vez. Pero si un segundo cliente —el CLI de `magia` corriendo en el Mac— activa por su cuenta, los
dos se roban el token en ciclo: **ping-pong**.

## Restricción que define el diseño

**El `sn` del dispositivo no se puede generar.** Verificado el 2026-08-09 contra el portal: dos
seriales nuevos con el mismo formato (32 hex) fueron rechazados con `snToken已经失效`. Los
seriales están en una lista blanca del portal y salen de dispositivos reales.

Consecuencia: **con un solo serial válido existe una sola identidad de dispositivo posible.** No
se pueden crear sesiones anónimas por usuario. La única salida es que haya un solo activador y
todos los demás le pidan el token prestado.

## Arquitectura

```
                    ┌──────────────────────────┐
   celular ──────┐  │  arkiv-api               │
   TV ───────────┼─▶│  MagisSession (Redis)    │──▶ portal magis
   CLI de magia ─┘  │  el UNICO que activa     │
                    └──────────────────────────┘
```

`MagisSession` ya existe y guarda `userId`/`userToken` en Redis con TTL de 48 h. Este diseño le
suma dos cosas: **modo** (anónimo o cuenta) y **exposición** (que otros la consuman en vez de
crear la suya).

## Dos modos, una sola sesión

| Modo | Cómo se establece | Cuándo |
|------|-------------------|--------|
| `anonimo` | `activate()` con el `sn` de la lista blanca | por defecto, siempre disponible |
| `cuenta` | `login(usuario, clave)` con `accountType=2` | si hay credenciales cargadas y válidas |

El gateway **prefiere `cuenta`** si tiene credenciales, y **cae a `anonimo` si el login falla** —
nunca queda sin servicio por una credencial vencida. El modo vigente se reporta en `/v1/health` y
en `/v1/sources`.

> Estado de las credenciales: las que hay hoy en `magia/.env` **no autentican**
> (`用户名密码校验失败`, probado con la contraseña en claro y con su MD5). El camino de cuenta se
> implementa completo, pero su verificación end-to-end queda pendiente de credenciales válidas.

## Contrato

### `GET /v1/magis/session`

Devuelve la sesión que el gateway **ya tiene**, sin activar de nuevo. Es lo que elimina el
ping-pong.

```json
{"user_id": "…", "user_token": "…", "mode": "anonimo", "expires_in": 171234}
```

`expires_in` va en **segundos** (lo que le queda al TTL en Redis).

Si no hay sesión viva, la establece (una sola vez, detrás del lock y del token bucket) y la
devuelve. Nunca expone la contraseña.

### `POST /v1/magis/credentials`

```json
{"username": "…", "password": "…"}
```

El gateway hace `login`. Si sale bien, guarda la sesión y pasa a modo `cuenta`; responde
`{"mode": "cuenta", "user_id": "…"}`. Si el portal rechaza, responde **422** con el motivo y
**deja intacta** la sesión anónima que estaba funcionando.

### `DELETE /v1/magis/credentials`

Borra las credenciales guardadas y vuelve a modo `anonimo`. Responde `{"mode": "anonimo"}`.

## Las credenciales guardadas

La contraseña **no se guarda en claro**. Se cifra con **AES-GCM** (ya está `pycryptodome` en el
proyecto por el 3DES del portal) con una llave derivada de `REF_SIGNING_KEY` vía HKDF-SHA256, y
se deja en Redis, que está atado a loopback y no sale del NUC.

Se guarda solo para poder **re-loguear cuando el token vence a las 48 h**; sin eso habría que
reingresarla cada dos días.

Reglas:

1. Ninguna ruta devuelve la contraseña, ni siquiera a quien tiene la `X-Arkiv-Key`.
2. `/v1/health` reporta si hay credenciales cargadas como **booleano**, nunca el valor.
3. Los logs nunca imprimen la contraseña ni el `userToken` completo.
4. Si `REF_SIGNING_KEY` cambia, las credenciales guardadas dejan de descifrarse: se descartan y
   se vuelve a `anonimo` sin romper el servicio.

## Cambio en el CLI de magia

En `lordmacu/magia`, `IPTVClient` gana un camino previo: si su `.env` tiene `ARKIV_API` y
`ARKIV_API_KEY`, pide el token a `GET /v1/magis/session` en vez de llamar a `activate()`. Si el
gateway no responde, activa como hoy — el CLI no queda dependiendo de la red del NUC.

Es un cambio chico y aislado, en el mismo lugar donde hoy decide si activarse.

## Manejo de errores

| Situación | Qué pasa |
|-----------|----------|
| Login falla al cargar credenciales | 422 con el motivo; la sesión anónima sigue intacta |
| Token vencido en modo `cuenta` | Re-login automático con las credenciales guardadas |
| Token vencido en modo `cuenta`, sin credenciales descifrables | Cae a `anonimo` y lo reporta en `/v1/health` |
| El portal expulsa la sesión | Ya cubierto: se descarta y se reintenta una vez |
| Redis caído | `/v1/magis/session` responde 503; la búsqueda de magis emite `source_error` y las otras fuentes siguen |

## Testing

| Nivel | Qué se prueba |
|-------|---------------|
| Cifrado | Ida y vuelta; una llave distinta no descifra; un blob corrupto no explota |
| Sesión | Modo anónimo por defecto; prefiere cuenta si hay credenciales; cae a anónimo si el login falla; una sola activación entre varias llamadas |
| Rutas | `session` no activa dos veces; `credentials` con login inválido da 422 y no pisa la sesión; `DELETE` vuelve a anónimo; ninguna respuesta contiene la contraseña |
| En vivo | Test opt-in fuera de CI que verifica contra el portal real (consume rate-limit) |

## Fuera de alcance

- **Sesiones por dispositivo.** El portal valida el `sn`; con un serial no es posible. Si más
  adelante hay más seriales, este diseño se extiende a un pool sin rehacerlo.
- **Crear cuentas en el portal desde la app.** Solo usar una que ya exista.
- **TV en vivo.** Sigue fuera, como en el spec del gateway.
- **UI en la app para cargar las credenciales.** El endpoint queda listo; la pantalla es parte
  de F5.
