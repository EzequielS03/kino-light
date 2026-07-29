# Plan 5 — UI de conexión + servicio foreground del TV + WorkManager

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** (1) Presencia (heartbeat online/lastSeen) para saber qué dispositivos están vivos. (2) Servicio foreground en el TV que mantiene la conexión (SSE de comandos + sync) viva aunque no estés en la app — best-effort en Fire OS. (3) WorkManager que revive el servicio si Fire OS lo mata. (4) UI de conexión en el celu: estado, re-parear, desvincular.

**Architecture:** Nuevo paquete `com.arkiv.player.presence` (heartbeat) y `com.arkiv.player.tvservice` (foreground service + worker). El servicio no re-implementa las suscripciones (ya corren en `AppGraph.applicationScope`); su función es mantener el PROCESO vivo (notificación foreground) para que esas suscripciones sigan. Reutiliza `DeviceAuthManager`, `PocketBaseClient`, `PairingManager`.

**Tech Stack:** Kotlin, Android foreground Service, WorkManager, Coroutines.

## Global Constraints
- Paquetes nuevos: `com.arkiv.player.presence`, `com.arkiv.player.tvservice`. Código nuevo se commitea limpio; cambios a archivos WIP (AppGraph, manifest, UI) van al working tree o staging quirúrgico.
- Commits lordmacu; sin coautoría. Tests JUnit4 puro donde aplique.
- Colección `devices` ya tiene `online` (bool) y `lastSeen` (date). No crear colecciones nuevas.
- El servicio foreground es **best-effort**: Fire OS puede matarlo; WorkManager mitiga pero no garantiza (documentado en el spec §5).

---

## Task 1: Presencia (heartbeat) — `PresenceManager`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/presence/PresenceManager.kt`

**Interfaces:**
- `class PresenceManager(client: PocketBaseClient, deviceAuth: DeviceAuthManager, scope: CoroutineScope)`
- `fun start()` — loop cada ~30s: si hay sesión, `client.updateRecord("devices", session.recordId, mapOf("online" to true, "lastSeen" to <ISO now>), token)`. Offline-safe (runCatching re-throw Cancellation). Al cancelarse el scope, best-effort marca `online=false` (opcional).

- [ ] **Step 1** Implementar. Compilar. Commit: `feat(presence): heartbeat online/lastSeen del dispositivo`.
- [ ] **Step 2** Cablear en AppGraph (working tree): exponer `presence` y arrancarlo tras bootstrap (junto a cloudSync).

---

## Task 2: Servicio foreground del TV — `TvConnectionService`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/tvservice/TvConnectionService.kt`
- Modify: `AndroidManifest.xml` (permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; declarar el `<service>`).

**Interfaces:**
- `class TvConnectionService : Service` — en `onStartCommand`: crea canal de notificación, `startForeground(id, notification)` ("Arkiv conectado — recibiendo del teléfono"), y toca `AppGraph.from(this)` para asegurar que las suscripciones (applicationScope) estén vivas. Devuelve `START_STICKY`. `onBind` = null.
- `companion object { fun start(context) { ContextCompat.startForegroundService(context, Intent(context, TvConnectionService::class.java)) } }`

- [ ] **Step 1** Implementar el servicio (canal + startForeground + START_STICKY). Manifest: permisos + `<service android:foregroundServiceType="dataSync" android:exported="false"/>`.
- [ ] **Step 2** Arrancarlo desde el TV: en `MainActivity` (rama `isTv`) o `ArkivTvRoot`, llamar `TvConnectionService.start(context)` al entrar. Pedir `POST_NOTIFICATIONS` en Android 13+ (en TV suele no requerir UI). (Working tree.)
- [ ] **Step 3** Compilar. Commit del servicio (código nuevo): `feat(tv): servicio foreground que mantiene viva la conexión`.

---

## Task 3: WorkManager que revive el servicio

**Files:**
- Create: `app/src/main/java/com/arkiv/player/tvservice/TvKeepAliveWorker.kt`
- Modify: `app/build.gradle.kts` (dependencia `androidx.work:work-runtime-ktx`).

**Interfaces:**
- `class TvKeepAliveWorker(context, params) : CoroutineWorker` — `doWork()`: si es un dispositivo TV, `TvConnectionService.start(applicationContext)`; return success. 
- Programar un `PeriodicWorkRequest` cada 15 min (mínimo de WorkManager) desde el arranque del TV.

- [ ] **Step 1** Añadir dependencia WorkManager (staging quirúrgico de build.gradle.kts). Implementar el worker + programarlo (enqueueUniquePeriodicWork) desde el arranque del TV. Compilar. Commit: `feat(tv): WorkManager revive el servicio foreground (best-effort)`.

---

## Task 4: UI de conexión en el celu — hoja de estado

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/pairing/ConnectionSheet.kt`
- Modify: pantalla home del celu (botón "Conexión" que ya abre el escáner → cambiar para abrir esta hoja con opciones). (Working tree / coordinar con el usuario que edita HomeScreen.)

**Interfaces:**
- `@Composable fun ConnectionSheet(pairing: PairingManager, onRepair: () -> Unit, onUnlink: () -> Unit, onDismiss: () -> Unit)` — muestra: estado del TV pareado (nombre, online/lastSeen consultando `devices` kind=tv), estado de la nube (sesión activa), botones **Re-parear** (abre escáner) y **Desvincular** (borra el device tv de la cuenta + limpia el pareo local).

- [ ] **Step 1** Implementar la hoja (observando `PairingState` + consultando el device tv). Desvincular: `client.deleteRecord("devices", tvRecordId, token)` + estado local. Compilar. Commit: `feat(celu): hoja de conexión (estado, re-parear, desvincular)`.
- [ ] **Step 2** Cablear el botón "Conexión" del home para abrir esta hoja (en vez de ir directo al escáner). (Working tree.)

---

## Task 5: Verificación e2e
- [ ] Instalar en celu y Fire Stick.
- [ ] Presencia: verificar en PocketBase que `devices` tiene `online=true` y `lastSeen` reciente para ambos.
- [ ] Foreground service: en el Fire Stick, salir de la app Arkiv (Home de Fire TV); verificar (notificación persistente + que un comando `play` remoto insertado en `commands` sigue siendo recibido con la app en background).
- [ ] Desvincular desde el celu: el device tv desaparece de la cuenta; re-parear vuelve a funcionar.

---

## Self-Review (cobertura vs spec §5, §8)
- Presencia (online/lastSeen) → Task 1 ✔.
- Foreground service TV (best-effort) → Task 2 ✔.
- WorkManager revival → Task 3 ✔.
- UI conexión (estado/re-parear/desvincular) → Task 4 ✔.
- **Fuera de alcance / notas:** el coalescing de teclas del pad por nube (spec §6) se deja como mejora futura; el servicio es best-effort (Fire OS puede matarlo). Cloudflare Access para el panel PocketBase (mencionado antes) no es parte de la app.
