# Plan 4 — Sincronización de biblioteca en tiempo real (offline-first)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** Que la biblioteca (items, episodes) y el progreso (playback, markers) se sincronicen **en tiempo real** entre celu y TV vía PocketBase, offline-first (Room manda; PocketBase es el espejo vivo), con LWW + tombstones, conviviendo con el sync LAN existente.

**Architecture:** Room es la fuente de verdad. `CloudSyncManager` tiene dos mitades: **push** (filas con `updatedAt` > cursor → upsert a PocketBase) y **pull** (SSE a las 4 colecciones + reconciliación por cursor → merge a Room con LWW, respetando `deleted`). Los borrados pasan a ser **soft-delete** (`deleted=true` + `updatedAt=now`) para que se propaguen. Reutiliza `PocketBaseClient`, `PocketBaseRealtime`, `DeviceAuthManager` (Plan 1) y la cuenta compartida (Plan 2).

**Tech Stack:** Kotlin, Room (v6→v7 + migración), Coroutines/Flow, PocketBase REST+SSE.

## Global Constraints

- Paquete nuevo: `com.arkiv.player.cloudsync` (NO tocar el paquete `sync/` LAN existente).
- Commits con identidad **lordmacu**; sin coautoría de Claude.
- Tests JUnit4 puro para lógica pura (merge LWW+tombstones, mapeo record↔entity). Las clases de merge operan sobre data classes puras (no Android, no org.json).
- **WIP del usuario:** las entidades/DAOs/DB/repositorio están en edición activa. Los cambios a esos archivos van como **ediciones en el working tree** (parte del WIP del usuario, sin commit separado) o staging quirúrgico. El código NUEVO (`cloudsync/`) se commitea limpio.
- Colecciones ya creadas: `library_items`, `episodes`, `progress`, `markers` (aislamiento estricto por cuenta, con `accountId`+`updatedAt`+`deleted`).
- Offline-first: sin red la app funciona igual; el push acumula y reintenta; la nube nunca es punto único de fallo.

---

## Task 0 — Colecciones ✅ HECHO (2026-07-21)
`library_items`, `episodes`, `progress`, `markers` creadas vía API con `accountId`+`updatedAt`+`deleted` e índice por `accountId`. Reglas `@request.auth.id != "" && accountId = @request.auth.accountId`.
- [ ] Documentar en `docs/pocketbase/collections.md`.

---

## Task 1: Columnas de sync en las entidades + migración v6→v7

**Files (working tree, WIP del usuario):**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt`

- [ ] **Step 1: Añadir columnas** a las entidades:
  - `ItemEntity`: `val updatedAt: Long = 0`, `val deleted: Boolean = false`
  - `EpisodeEntity`: `val updatedAt: Long = 0`, `val deleted: Boolean = false`
  - `PlaybackEntity`: `val updatedAt: Long = 0`, `val deleted: Boolean = false`
  - `SkipMarkerEntity`: ya tiene `updatedAt`; añadir `val deleted: Boolean = false`

- [ ] **Step 2: Migración** en `ArkivDatabase.kt`: subir `version = 7` y añadir:
```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (t in listOf("items", "episodes", "playback")) {
            db.execSQL("ALTER TABLE $t ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $t ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE skip_markers ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}
```
  y registrarla en `.addMigrations(..., MIGRATION_6_7)`.

- [ ] **Step 3: Compilar** (KSP regenera el schema) → BUILD SUCCESSFUL.
- [ ] (Sin commit separado — parte del WIP del usuario. Confirmar con el usuario si commitear.)

---

## Task 2: Modelo de sync + mapeo record↔entity (puro, testeable)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/cloudsync/SyncMappers.kt`
- Create: `app/src/main/java/com/arkiv/player/cloudsync/LwwMerge.kt`
- Test: `app/src/test/java/com/arkiv/player/cloudsync/LwwMergeTest.kt`

**Interfaces:**
- Produces:
  - `object LwwMerge { fun <T> pickWinner(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean }` — devuelve true si el remoto gana (remoto.updatedAt > local.updatedAt).
  - `fun clampUpdatedAt(candidate: Long, lastKnown: Long): Long` — `max(candidate, lastKnown+1)` para no retroceder si el reloj va atrás.

- [ ] **Step 1: Test que falla**
```kotlin
package com.arkiv.player.cloudsync
import org.junit.Assert.*
import org.junit.Test
class LwwMergeTest {
    @Test fun remoteWinsWhenNewer() {
        assertTrue(LwwMerge.pickWinner(localUpdatedAt = 10, remoteUpdatedAt = 20))
        assertFalse(LwwMerge.pickWinner(localUpdatedAt = 20, remoteUpdatedAt = 10))
        assertFalse(LwwMerge.pickWinner(localUpdatedAt = 20, remoteUpdatedAt = 20)) // empate: local se queda
    }
    @Test fun clampNeverGoesBackwards() {
        assertEquals(101, clampUpdatedAt(candidate = 50, lastKnown = 100))
        assertEquals(200, clampUpdatedAt(candidate = 200, lastKnown = 100))
    }
}
```
- [ ] **Step 2: Correr — falla.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.cloudsync.LwwMergeTest"`
- [ ] **Step 3: Implementar `LwwMerge.kt`**
```kotlin
package com.arkiv.player.cloudsync
import kotlin.math.max
object LwwMerge { fun pickWinner(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean = remoteUpdatedAt > localUpdatedAt }
fun clampUpdatedAt(candidate: Long, lastKnown: Long): Long = max(candidate, lastKnown + 1)
```
- [ ] **Step 4: Implementar `SyncMappers.kt`** — funciones `itemToFields(ItemEntity, accountId): Map<String,Any?>` y `recordToItem(JSONObject): ItemEntity` para cada una de las 4 entidades (items/episodes/playback/markers). (Mapeo campo a campo; el JSON usa org.json → estas funciones son Android-side, NO en el test puro.) Clave natural: item=identifier, episode=epId(=EpisodeEntity.id), playback=episodeId, marker=itemId.
- [ ] **Step 5: Correr — pasan.** → PASS.
- [ ] **Step 6: Commit** (3 archivos): `feat(cloudsync): merge LWW + mapeo record↔entity (testeable)`.

---

## Task 3: DAOs — dirty rows + soft-delete + upsert-sin-tocar-updatedAt

**Files (working tree, WIP):**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt`

- [ ] **Step 1** Añadir queries a cada DAO para el push/merge:
  - `getItemsSince(cursor: Long): List<ItemEntity>` = `SELECT * FROM items WHERE updatedAt > :cursor` (idem episodes/playback/markers).
  - `softDeleteItem(id, now)` = `UPDATE items SET deleted=1, updatedAt=:now WHERE identifier=:id` (idem por tabla). Reemplaza los `DELETE FROM` en las rutas de borrado del repositorio.
  - `upsertFromRemote(entity)` = insert REPLACE (ya existe upsert; reutilizar). El merge decide si aplicar.
- [ ] **Step 2** En `ArkivRepository`, en cada escritura local (agregar item, guardar progreso, marcador): setear `updatedAt = System.currentTimeMillis()` en la entidad antes de persistir; y cambiar los borrados a `softDelete*`. (Working tree — parte del WIP del usuario.)
- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (solo si el usuario confirma; si no, queda en WIP).

---

## Task 4: `PbSyncClient` — upsert por clave natural + pull incremental

**Files:**
- Create: `app/src/main/java/com/arkiv/player/cloudsync/PbSyncClient.kt`

**Interfaces:**
- Consumes: `PocketBaseClient` (listRecords, createRecord, updateRecord), `DeviceAuthManager`.
- Produces:
  - `class PbSyncClient(client, deviceAuth)`
  - `suspend fun upsert(collection: String, naturalKeyField: String, naturalKey: String, fields: Map<String,Any?>)` — busca por `accountId && <naturalKeyField>='<key>'`; si existe → updateRecord; si no → createRecord.
  - `suspend fun pullSince(collection: String, cursor: Long): List<JSONObject>` — `listRecords(collection, "accountId='..' && updatedAt > cursor")`, ordenado por updatedAt.

- [ ] **Step 1: Implementar** (REST sobre PocketBaseClient; runCatching con re-throw de CancellationException; offline → propaga para reintentar). Compilar. Commit: `feat(cloudsync): PbSyncClient (upsert por clave natural + pull incremental)`.

---

## Task 5: `CloudSyncManager` — push + subscribe + reconciliación

**Files:**
- Create: `app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt`
- Create: `app/src/main/java/com/arkiv/player/cloudsync/SyncCursors.kt` (guarda lastPushedAt/lastPulledAt por colección en SharedPreferences)

**Interfaces:**
- Produces:
  - `class CloudSyncManager(dao's, pbSync, realtime, deviceAuth, cursors, scope)`
  - `fun start()` — arranca: (1) reconciliación (pullSince por colección → merge a Room), (2) suscripción SSE a las 4 colecciones → merge en vivo, (3) loop de push periódico (cada ~5s: dirty rows → upsert; avanza lastPushedAt). Todo idempotente y offline-safe.
  - Merge: por cada record remoto, `LwwMerge.pickWinner(localRow.updatedAt, remote.updatedAt)`; si gana el remoto → upsert a Room (o soft-delete si `deleted`); nunca revivir un borrado más nuevo.
  - Throttle del progreso: el push lee dirty rows cada ~5s, así que ráfagas de `positionMs` se colapsan naturalmente.

- [ ] **Step 1: Implementar** (loop con delay; SSE merge; reconciliación al arrancar). Compilar. Commit: `feat(cloudsync): CloudSyncManager (push + realtime + reconciliación)`.

---

## Task 6: Cableado en AppGraph + arranque

**Files (working tree, WIP):**
- Modify: `AppGraph.kt` (exponer `cloudSync` + `applicationScope.launch { cloudSync.start() }` tras bootstrap) — staging quirúrgico.

- [ ] **Step 1** Exponer `cloudSync: CloudSyncManager` y arrancarlo. Compilar. Commit (staging quirúrgico de AppGraph, solo la adición).

---

## Task 7: Verificación e2e

- [ ] Instalar en celu y Fire Stick (misma cuenta pareada).
- [ ] En el celu: agregar un item → verificar en PocketBase que aparece en `library_items` con el accountId; y que en el Fire Stick aparece en la biblioteca (sin estar en la misma WiFi).
- [ ] Progreso: reproducir en un dispositivo → el `positionMs` se refleja en el otro (LWW).
- [ ] Borrado: eliminar un item → `deleted=true` se propaga (no revive en el merge).
- [ ] Offline: sin red, cambios locales se acumulan; al reconectar, se suben.

---

## Self-Review (cobertura vs spec §7)
- Offline-first, Room fuente de verdad, PB espejo: Tasks 4,5 ✔.
- Push (dirty rows por updatedAt) + pull (SSE + reconciliación): Task 5 ✔.
- LWW + tombstones (soft-delete): Tasks 1,2,3,5 ✔.
- Reloj-atrás (clampUpdatedAt): Task 2 ✔.
- Throttle del progreso (push cada ~5s): Task 5 ✔.
- Convivencia con LAN sync (paquete separado, no toca sync/): ✔.
- Qué NO se sincroniza: descargas offline (DownloadEntity local por dispositivo) — fuera de alcance.

**Riesgos:** migración v6→v7 sobre entidades en WIP del usuario (alto riesgo de conflicto); cambio de semántica de borrado (hard→soft) en el repositorio WIP. Ejecutar sobre una base de datos-layer estable.
