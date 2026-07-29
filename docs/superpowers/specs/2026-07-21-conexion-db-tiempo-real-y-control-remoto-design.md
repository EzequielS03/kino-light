# Diseño — Conexión a la DB (PocketBase) en tiempo real + control remoto celu↔TV

**Fecha:** 2026-07-21
**App:** Arkiv (Android/Compose, reproductor de archive.org + torrents, con app de Android TV)
**Objetivo:** añadir un segundo transporte vía **PocketBase (realtime)** que permita emparejar celu↔TV por QR, controlar el TV y enviarle reproducciones **incluso de forma remota** (fuera de la WiFi), y sincronizar la biblioteca en tiempo real — sin perder lo que ya funciona por LAN/Chromecast/DLNA.

> Prioridad transversal explícita del usuario: **que quede lo más robusto posible** (reconexión, cola offline, idempotencia, LWW con tombstones, aislamiento por cuenta, errores siempre visibles).

---

## 1. Contexto actual (lo que ya existe)

- **Transporte "enviar al TV" = 100% LAN**: `SyncManager` (paquete `sync/`) hace descubrimiento por **multicast UDP** + un **servidor HTTP** en cada dispositivo con endpoints `/sync/export`, `/sync/import`, `/play` (reproducir episodio) y `/key` (teclas del pad). Solo funciona en la misma red WiFi.
- **Sync de biblioteca**: LAN, bidireccional, last-write-wins; la TV refleja la biblioteca (`mirrorItems=isReceiver`), el progreso va en ambas vías.
- **Pad / control remoto**: `ui/remote/RemoteScreen.kt` manda teclas (UP/DOWN/LEFT/RIGHT/OK/BACK/PLAYPAUSE) al TV vía `/key`.
- **Casting**: Chromecast (`cast/`) y DLNA (`dlna/`) para TVs sin Arkiv.
- **App de TV**: `ui/tv/` (Compose for TV), **mismo módulo** que el celu → `TorrentEngine`, `TorrentStreamServer` y el playback de archive **ya están disponibles en el TV**.
- **PocketBase**: NO hay integración todavía (ni dependencia en Gradle). "Configurada" era conceptual. La instancia corre en `https://db.comparadorinternet.co` (auth + db + realtime).

## 2. Decisiones tomadas (brainstorming)

1. **Emparejamiento**: permanente + botón para **re-parear** (mostrar QR de nuevo) y **desvincular/revocar**.
2. **Auth**: el **celu (logueado) provisiona al TV**; no hay credenciales embebidas en el TV. El canal de vuelta al TV es la propia DB (realtime).
3. **Transporte**: **híbrido inteligente** — si celu y TV están en la misma WiFi → **LAN** (instantáneo); si están en redes distintas → **DB** (remoto). Con **failover** LAN→nube.
4. **Alcance**: **todo junto** (control remoto + biblioteca en tiempo real) en un solo diseño.
5. **TV remoto**: **servicio foreground** en el TV que mantiene viva la conexión SSE (best-effort; Fire OS puede matarlo).
6. **Popup de reproducción**: **Ver aquí / Enviar al TV (Arkiv) / Castear directo**, sin "recordar preferencia".
7. **Fuente de verdad**: **offline-first** — Room local manda, PocketBase es el espejo en tiempo real.

## 3. Arquitectura general

Abstracción del transporte. Hoy `SyncManager` mezcla descubrimiento LAN + HTTP + comandos; se envuelve detrás de una interfaz común y se suma la nube.

```
                    ┌─────────────────────────────┐
                    │      RemoteController        │  ← lógica de alto nivel (la UI habla con esto)
                    │ (play popup, pad, pairing)   │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │      TransportRouter         │  ← elige transporte en cada acción
                    │  peer en LAN? → Lan          │
                    │  no, y pareado+online? → Cloud│
                    └───────┬──────────────┬───────┘
                            │              │
              ┌─────────────▼───┐   ┌──────▼────────────┐
              │  LanTransport   │   │  CloudTransport   │
              │ (envuelve el    │   │ (PocketBase:      │
              │  SyncManager)   │   │  REST + SSE)      │
              └─────────────────┘   └───────────────────┘
```

**Paquetes/componentes nuevos:**

- `pocketbase/` — `PocketBaseClient` (auth, CRUD REST), `PocketBaseRealtime` (suscripciones SSE con OkHttp EventSource, reconexión), modelos de records.
- `remote/` — `RemoteTransport` (interfaz: `sendPlay`, `sendKey`, `observeCommands`), `LanTransport` (envuelve `SyncManager`), `CloudTransport` (PocketBase), `TransportRouter`, `RemoteController`, modelo de `Command`.
- `pairing/` — `PairingManager` (genera/consume QR, provisiona el TV, revoca), `PairingState`.
- `CloudSyncManager` — sync de biblioteca en tiempo real (outbox local→PB, subscribe PB→local, LWW+tombstones), conviviendo con el LAN sync como camino extra.
- `TvConnectionService` — foreground service del TV que mantiene SSE + presencia y ejecuta comandos.
- UI: botón "Conexión", pantalla de QR (TV), escáner QR (celu), popup de destino de reproducción, pad ruteando por el router.

**Principio:** el `SyncManager` LAN **no se elimina**; se envuelve como `LanTransport` y sigue siendo el camino rápido en la misma WiFi.

## 4. Modelo de datos en PocketBase

**Tenant = `accountId`** (UUID aleatorio que genera el celu en el primer arranque). Es el límite de seguridad: cada record lleva `accountId` y las reglas solo permiten ver/escribir lo de tu cuenta. Al ser un UUID secreto funciona como bearer.

| Colección | Tipo | Para qué | Campos clave |
|---|---|---|---|
| `devices` | auth | Cada dispositivo (celu/TV) es un record auth con su **propio token**. El celu se crea solo (cuenta nueva); el TV lo crea el celu al parear. | `accountId`, `kind` (phone/tv), `name`, `online`, `lastSeen`, `caps` (json) |
| `pair_requests` | base | Canal de arranque del pareo (el TV aún no está en la cuenta). Vive por el `code` del QR, TTL corto, un solo uso. | `code` (alta entropía), `status`, `payload` (credenciales del TV **cifradas** con clave derivada del `code`), `expiresAt` |
| `commands` | base (realtime) | Comandos remotos (play y teclas del pad) cuando NO están en la misma WiFi. | `accountId`, `targetDeviceId`, `fromDeviceId`, `type`, `payload` (json), `seq`, `ack` |
| `library_items` | base (realtime) | Biblioteca sincronizada en vivo. | `accountId`, campos del item, clave estable = id de archive/torrent, `updatedAt`, `deleted` (tombstone) |
| `progress` | base (realtime) | Progreso de reproducción + marcadores intro/outro. | `accountId`, `episodeId`, `positionMs`, `introMs`, `outroMs`, `updatedAt` |

**Reglas de acceso (resumen):** todo scoped por `accountId = @request.auth.accountId`. El primer `devices` (celu) se crea anónimo con `accountId` nuevo; el `devices` del TV lo crea el celu autenticado con `accountId` coincidente. El `pair_requests` es el único con lectura gated por `code` (no por `accountId`), porque el TV aún no está en la cuenta. (Expresiones exactas de las reglas → en el plan de implementación.)

## 5. Flujo de emparejamiento (QR)

```
TV (Arkiv TV)                         PocketBase                      Celu (Arkiv)
1. Genera pairCode (alta entropía) + par de claves efímeras
2. Crea pair_request{code, pending} ──►
3. Muestra QR = {dbUrl, code}
4. Se suscribe (SSE) a su pair_request
                                                        5. Escaneas el QR
                                      ◄─ 6. Lee code; crea devices{tv} en MI cuenta + credenciales
                                      ◄─ 7. Escribe payload CIFRADO (cred. del TV) en pair_request, status=claimed
8. SSE le entrega el update ◄─
9. Descifra con clave del code, se autentica como su device TV
10. Borra el pair_request (un solo uso)
11. ✅ Pareado: ambos en la misma cuenta
```

**Robustez del pareo:**
- **Un solo uso + TTL** (~5 min); el `pair_request` se borra al reclamarse. QR viejo = inservible.
- **Payload cifrado** con clave derivada del `code` (que solo viaja en el QR): el record por sí solo no basta si se filtrara.
- **Reintentos** con backoff si el SSE o la escritura fallan a mitad.
- **Re-parear**: botón en el TV regenera todo; el device TV viejo se puede **revocar** (`status=revoked`). "Desvincular" desde el celu.
- **Estados UI**: "Esperando escaneo… / Pareando… / ✅ Pareado con Fire TV Salón / ❌ expiró, generá otro".
- **Persistencia**: tras parear, ambos guardan token/credenciales en **EncryptedSharedPreferences / Keystore**; al reabrir reconectan sin re-escanear.

## 6. Transporte híbrido y comandos

**El router decide en cada acción:**
```
enviarComando(target, cmd):
  1. ¿TV en descubrimiento LAN (multicast)?         → sí → LanTransport (HTTP, ~instantáneo)
  2. ¿no, pero pareado y "online" en PB?            → sí → CloudTransport (commands + SSE)
  3. ¿ninguno?                                       → error claro: "TV no alcanzable"
  + Failover: si el LAN falla en el intento, reintenta por Cloud.
```
- **Co-ubicación**: reutiliza `discover()` (multicast, con cache del último peer). Resultado cacheado unos segundos para no re-descubrir en cada tecla del pad.
- **Comando por nube (tecla del pad remota):**
  ```
  Celu: POST commands{target:tvId, type:key, payload:"UP", seq:n, ack:false}
  TV (SSE a commands where target=yo && ack=false): recibe → inyecta KeyEvent → ack=true (o borra)
  Celu: espera ack (timeout) → si no llega, avisa
  ```
- **Play = comando gordo**: `type:play, payload:{kind:torrent|archive, id/magnet, episodeId, startPositionMs}`. El TV **resuelve y reproduce solo** (tiene TorrentEngine + archive).

**Robustez de comandos:**
- **Idempotencia**: `seq` monótono por sesión; el TV ignora `seq` repetidos/viejos.
- **Ack + timeout**: el celu sabe si llegó; reintenta o avisa.
- **Coalescing** de teclas del pad por la nube (agrupa ráfagas).
- **Auto-limpieza** (TTL) de `commands` viejos/ack.
- **Misma interfaz** `RemoteTransport` para LAN y Cloud; `SyncManager` queda intacto detrás de `LanTransport`.
- **Latencia**: LAN instantáneo; nube ~150–500 ms (aceptado para navegar, no para juegos).

## 7. Sincronización de biblioteca en tiempo real (offline-first)

Room manda; PocketBase es el espejo vivo. `CloudSyncManager` con dos mitades:

```
Room (fuente de verdad)
  │  ▲
  │  └── aplica merge LWW + tombstones ◄── SSE library_items/progress
  └── outbox (cola persistente en Room) ──► upsert PocketBase
```

- **Subida (local→PB):** cada cambio en Room encola un "outbox"; un worker lo vacía a PB (upsert por `updatedAt`). Sin red → queda en cola y se envía al reconectar.
- **Bajada (PB→local):** SSE a `library_items` y `progress`; cada evento se mergea a Room con **LWW por `updatedAt`** y **tombstones** (borrado gana si es más nuevo). Al parear/reabrir: **pull completo de reconciliación** y luego en vivo.

**Robustez del sync:**
- **Cola persistente (outbox)**: ningún cambio se pierde offline; reintento con backoff hasta confirmar.
- **LWW + tombstones**: sin resurrección de borrados; reloj `updatedAt` monótono con protección si el reloj va atrás (`max(local, lastKnown+1)`).
- **Reconciliación inicial**: pull completo al arrancar/parear.
- **Convivencia con LAN sync**: ambos alimentan el mismo merge LWW → idempotente, no chocan.
- **Dedupe**: clave estable por item (id de archive/torrent).
- **Anti-tormenta**: el progreso se **throttlea** (~cada 5 s o en pausa/seek), no por frame.

**Qué se sincroniza:** biblioteca (items), progreso, marcadores intro/outro. **Qué NO:** archivos descargados offline (siguen locales; solo se sincroniza el metadato, no los GB).

## 8. UI y servicio del TV

**Celu:**
- **Botón "Conexión" arriba** (junto a sync/cast): hoja con estado ("TV: ✅ pareado (Fire TV Salón) · en línea · misma WiFi / remoto"), **Re-parear (escanear QR)**, **Desvincular**, estado de la nube.
- **Escáner QR**: CameraX + ML Kit barcode.
- **Popup de reproducción**: al tocar play en un item con TV pareado disponible → **Ver aquí · Enviar al TV · Castear directo**. Sin TV → reproduce directo. El botón Chromecast/DLNA sigue aparte.
- **Pad** (`RemoteScreen`): manda por el **router** (LAN o nube).

**TV (Arkiv TV):**
- **Pantalla de QR** ("Conectar teléfono"): genera/muestra QR con estado.
- **`TvConnectionService`** (foreground): mantiene SSE a `commands` + presencia (`lastSeen`), reconecta con backoff, ejecuta teclas y play. Notificación persistente discreta.
- Recibe play remoto → resuelve (torrent/archive) → abre el player solo.

**Robustez UI/servicio:**
- Reconexión **backoff exponencial + jitter** en SSE; **heartbeat** para detectar socket muerto (Fire OS).
- **WorkManager** de respaldo para revivir el servicio si Fire OS lo mata (best-effort).
- Errores **siempre visibles** ("reintentando…", "TV no alcanzable", "QR expiró").
- Permisos: cámara (escáner) y `POST_NOTIFICATIONS` (Android 13+), pedidos en contexto.

## 9. Manejo de errores

- **Sin red / PB caído**: app funciona igual (offline-first); outbox acumula y reintenta; UI muestra "nube: reintentando…"; LAN sigue.
- **SSE se cae**: reconexión backoff+jitter; al reconectar, pull de reconciliación.
- **Pareo fallido/expirado**: mensaje claro + regenerar QR; credenciales corruptas → forzar re-pareo.
- **Comando sin ack**: aviso + reintento acotado.
- **Token expirado**: refresh automático; si falla, re-auth con credenciales guardadas.
- **Reloj hacia atrás**: `max(updatedAt local, lastKnown+1)` para no romper LWW.

## 10. Estrategia de testing

- **Unit**: merge LWW + tombstones (borrado viejo vs edición nueva, duplicados, reloj atrás), idempotencia por `seq`, cifrado/descifrado del payload de pareo, cola outbox (persistencia y reintento).
- **Cliente PocketBase**: auth, CRUD y parseo de eventos SSE (contra PB de prueba o mock).
- **Router de transporte**: "peer en LAN / solo nube / ninguno" → elige y hace failover (transportes fake).
- **Integración**: pareo end-to-end (device fake TV + celu), play remoto, tanda de teclas con coalescing.
- **Manual**: matriz misma-WiFi vs redes-distintas × (play / pad / sync); caso Fire TV mata el servicio → WorkManager lo revive.

## 11. Fuera de alcance (YAGNI por ahora)

- Despertar el TV con push/FCM (Fire OS no lo trae; se descartó).
- Sincronizar los archivos descargados (solo metadatos).
- Multi-usuario/compartir cuenta entre personas (esto es de uso personal single-account).
- "Recordar preferencia" en el popup de reproducción.

## 12. Riesgos conocidos

- **Fire OS mata el foreground service**: el control remoto estando fuera es best-effort; WorkManager mitiga pero no garantiza.
- **Latencia del pad por nube**: aceptable para navegar, no instantáneo.
- **PocketBase sin SDK Kotlin**: cliente propio (REST+SSE); más control pero más código a mantener y testear.
- **Reglas de acceso de PocketBase**: el aislamiento depende de reglas bien escritas + `accountId` secreto; hay que testearlas explícitamente.
