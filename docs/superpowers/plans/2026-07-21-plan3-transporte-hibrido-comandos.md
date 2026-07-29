# Plan 3 — Transporte híbrido (LAN/DB) + comandos (play + pad)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que "enviar al TV" (play) y el pad viajen por **LAN cuando el celu y el TV están en la misma WiFi** (instantáneo, vía el `SyncManager` actual) y por **PocketBase cuando están en redes distintas** (remoto), con failover, sin romper el camino LAN existente. Añadir el **popup de reproducción** "Ver aquí / Enviar al TV / Castear directo".

**Architecture:** Una interfaz `RemoteTransport` con dos implementaciones — `LanTransport` (envuelve `SyncManager`) y `CloudTransport` (colección `commands` + realtime SSE) — y un `TransportRouter` que elige por alcanzabilidad (LAN si el peer aparece en el descubrimiento multicast, si no la nube) con failover. Un `RemoteController` de alto nivel expone `sendPlay`/`sendKey` (celu) e `incomingPlay`/`incomingKeys` (TV, merge de ambos transportes). Reutiliza Plan 1 (`PocketBaseClient`, `PocketBaseRealtime`, `DeviceAuthManager`) y Plan 2 (cuenta compartida celu↔TV).

**Tech Stack:** Kotlin, Coroutines/Flow, OkHttp (ya), org.json, el `SyncManager` LAN existente.

## Global Constraints

- Paquete base `com.arkiv.player`. Paquete nuevo: `com.arkiv.player.remote`.
- Commits con identidad **lordmacu**; **sin** coautoría de Claude.
- Tests unitarios JUnit4 puro; las clases testeables (`PlayPayload`/codec, `SeqTracker`, `TransportRouter` con transportes fake) NO dependen de Android ni de org.json (para el codec, operar sobre mapas/strings; el JSON de red queda en las capas Android).
- El repo trackea artefactos y hay **WIP del usuario** en varios archivos de UI. NUNCA `git add -A`. Cada task stagea SOLO sus archivos; para archivos con WIP usar **staging quirúrgico** (probado en Plan 1/2). El módulo compila limpio; un "error" en archivo ajeno es el propio test RED mal atribuido o una edición concurrente del usuario — reintentar `./gradlew :app:compileDebugKotlin`.
- Instancia PocketBase `https://db.comparadorinternet.co`. Colección `commands` (esta fase, ya creada) con aislamiento estricto por `accountId`.
- **No romper el LAN actual:** el `SyncManager` (multicast + `/play` + `/key`, y los flows `remotePlay`/`remoteKeys`) NO se elimina; `LanTransport` lo envuelve. El consumo del TV se migra de `syncManager.remotePlay/remoteKeys` a `remoteController.incomingPlay/incomingKeys` (que incluyen el LAN).
- Compat de payload: `remotePlay` LAN históricamente emite un `episodeId` crudo (String). El codec de `PlayPayload` DEBE aceptar tanto un JSON de `PlayPayload` como un String suelto (legacy → `PlayPayload(kind=UNKNOWN, episodeId=string)`), para no romper reproducciones LAN viejas.

---

## Task 0 — Servidor: colección `commands` ✅ HECHO (2026-07-21)

Creada vía API (superadmin por SSH). Campos: `accountId` (text, req), `targetDeviceId` (text, req, índice `idx_commands_target`), `fromDeviceId` (text), `type` (select play/key, req), `payload` (json, max 20000), `seq` (number), `ack` (bool). Reglas list/view/create/update/delete = `@request.auth.id != "" && accountId = @request.auth.accountId` (aislamiento estricto por cuenta).

- [ ] **Documentar** en `docs/pocketbase/collections.md` (sección `commands`). Commit: `docs(pocketbase): esquema de commands`.

---

## Task 1: Modelos + interfaz `RemoteTransport` + codec de `PlayPayload` (testeable)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/RemoteModels.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/RemoteTransport.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/PlayPayloadTest.kt`

**Interfaces:**
- Produces:
  - `enum class PlayKind { ARCHIVE, TORRENT, UNKNOWN }`
  - `data class PlayPayload(val kind: PlayKind, val id: String, val episodeId: String, val startPositionMs: Long = 0)`
  - `object PlayPayloadCodec { fun encode(p: PlayPayload): String; fun decode(s: String): PlayPayload }` — `encode` → JSON; `decode` acepta JSON de PlayPayload O un String legacy (→ `PlayPayload(UNKNOWN, id=s, episodeId=s)`). Puro (usa un mini-parser JSON manual o formato `k=..;i=..;e=..;p=..` — para no depender de org.json en unit tests, usar formato delimitado `arkivplay|<kind>|<id>|<episodeId>|<startMs>` y el fallback legacy si no matchea).
  - `data class RemoteCommand(val type: String, val play: PlayPayload?, val key: String?, val seq: Long)`
  - `interface RemoteTransport { suspend fun reachable(): Boolean; suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean; suspend fun sendKey(key: String, seq: Long): Boolean; fun incoming(): kotlinx.coroutines.flow.Flow<RemoteCommand> }`

- [ ] **Step 1: Test que falla** (`PlayPayloadTest.kt`)

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPayloadTest {
    @Test fun roundTrip() {
        val p = PlayPayload(PlayKind.TORRENT, id = "magnet:xyz", episodeId = "ep1", startPositionMs = 4200)
        val back = PlayPayloadCodec.decode(PlayPayloadCodec.encode(p))
        assertEquals(p, back)
    }

    @Test fun legacyRawStringIsAccepted() {
        val back = PlayPayloadCodec.decode("archive-identifier-123")
        assertEquals(PlayKind.UNKNOWN, back.kind)
        assertEquals("archive-identifier-123", back.episodeId)
    }

    @Test fun encodeIsPrefixed() {
        assertEquals(true, PlayPayloadCodec.encode(PlayPayload(PlayKind.ARCHIVE, "i", "e")).startsWith("arkivplay|"))
    }
}
```

- [ ] **Step 2: Correr — falla.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.PlayPayloadTest"` → FAIL.

- [ ] **Step 3: Implementar `RemoteModels.kt`**

```kotlin
package com.arkiv.player.remote

enum class PlayKind { ARCHIVE, TORRENT, UNKNOWN }

data class PlayPayload(
    val kind: PlayKind,
    val id: String,
    val episodeId: String,
    val startPositionMs: Long = 0,
)

data class RemoteCommand(val type: String, val play: PlayPayload?, val key: String?, val seq: Long)

/**
 * Serializa PlayPayload a un string delimitado (sin depender de org.json → testeable en JVM).
 * Formato: `arkivplay|<kind>|<id>|<episodeId>|<startMs>` con los campos URL-encoded.
 * `decode` acepta además un String legacy suelto (un episodeId crudo del LAN antiguo).
 */
object PlayPayloadCodec {
    private const val PREFIX = "arkivplay|"

    fun encode(p: PlayPayload): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return PREFIX + listOf(p.kind.name, e(p.id), e(p.episodeId), p.startPositionMs.toString()).joinToString("|")
    }

    fun decode(s: String): PlayPayload {
        if (!s.startsWith(PREFIX)) {
            // Legacy: un episodeId crudo del LAN antiguo.
            return PlayPayload(PlayKind.UNKNOWN, id = s, episodeId = s)
        }
        val parts = s.removePrefix(PREFIX).split("|")
        fun d(i: Int) = parts.getOrNull(i)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        val kind = runCatching { PlayKind.valueOf(parts.getOrNull(0) ?: "") }.getOrDefault(PlayKind.UNKNOWN)
        return PlayPayload(kind, id = d(1), episodeId = d(2), startPositionMs = parts.getOrNull(3)?.toLongOrNull() ?: 0)
    }
}
```

- [ ] **Step 4: Implementar `RemoteTransport.kt`**

```kotlin
package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow

/** Un transporte para enviar comandos al otro dispositivo y recibir los suyos. */
interface RemoteTransport {
    /** ¿Este transporte puede alcanzar al otro dispositivo ahora mismo? */
    suspend fun reachable(): Boolean
    suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean
    suspend fun sendKey(key: String, seq: Long): Boolean
    /** Comandos entrantes dirigidos a este dispositivo (lo consume el TV). */
    fun incoming(): Flow<RemoteCommand>
}
```

- [ ] **Step 5: Correr — pasan.** Mismo comando del Step 2 → PASS (3 tests).
- [ ] **Step 6: Commit** (3 archivos): `feat(remote): modelos, interfaz de transporte y codec de PlayPayload (testeable)`.

---

## Task 2: `SeqTracker` (idempotencia, testeable)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/SeqTracker.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/SeqTrackerTest.kt`

**Interfaces:**
- Produces:
  - `class SeqTracker` con `fun next(): Long` (monótono, empieza en 1) y `fun isFresh(seq: Long): Boolean` (true la primera vez que se ve un seq mayor a los vistos; false si repetido/viejo).

- [ ] **Step 1: Test que falla**

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqTrackerTest {
    @Test fun nextIsMonotonic() {
        val t = SeqTracker()
        assertEquals(1L, t.next()); assertEquals(2L, t.next()); assertEquals(3L, t.next())
    }

    @Test fun isFreshRejectsRepeatsAndOld() {
        val t = SeqTracker()
        assertTrue(t.isFresh(5)); assertFalse(t.isFresh(5)); assertFalse(t.isFresh(3)); assertTrue(t.isFresh(6))
    }
}
```

- [ ] **Step 2: Correr — falla.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.SeqTrackerTest"` → FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.remote

import java.util.concurrent.atomic.AtomicLong

/** Genera seqs monótonos (lado emisor) y descarta repetidos/viejos (lado receptor). */
class SeqTracker {
    private val counter = AtomicLong(0)
    @Volatile private var highestSeen = 0L

    fun next(): Long = counter.incrementAndGet()

    @Synchronized
    fun isFresh(seq: Long): Boolean {
        if (seq <= highestSeen) return false
        highestSeen = seq
        return true
    }
}
```

- [ ] **Step 4: Correr — pasan.** → PASS (2 tests).
- [ ] **Step 5: Commit** (2 archivos): `feat(remote): SeqTracker para idempotencia de comandos (testeable)`.

---

## Task 3: `CloudTransport` (commands + realtime)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/CloudTransport.kt`

**Interfaces:**
- Consumes: `PocketBaseClient`, `PocketBaseRealtime`, `DeviceAuthManager`, `PocketBaseConfig`, `RemoteTransport`, `PlayPayloadCodec`.
- Produces: `class CloudTransport(client, realtime, deviceAuth, targetDeviceId: () -> String?) : RemoteTransport`
  - `reachable()` = hay `targetDeviceId` y sesión (`deviceAuth.session.value != null`).
  - `sendPlay/sendKey` = `client.createRecord("commands", {accountId, targetDeviceId, fromDeviceId, type, payload, seq, ack:false}, token)`. Payload play = `PlayPayloadCodec.encode(p)`; payload key = la tecla.
  - `incoming()` = `realtime.subscribe(["commands"])` filtrado a `record.targetDeviceId == mi recordId && ack != true`, mapeado a `RemoteCommand`, y tras emitir marca `ack=true` (o borra el record) — best-effort.

- [ ] **Step 1: Implementar**

```kotlin
package com.arkiv.player.remote

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

class CloudTransport(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val targetDeviceId: () -> String?,
) : RemoteTransport {

    private val col = "commands"

    override suspend fun reachable(): Boolean =
        targetDeviceId() != null && deviceAuth.session.value != null

    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean =
        send("play", PlayPayloadCodec.encode(p), seq)

    override suspend fun sendKey(key: String, seq: Long): Boolean = send("key", key, seq)

    private suspend fun send(type: String, payload: String, seq: Long): Boolean {
        val s = deviceAuth.session.value ?: return false
        val target = targetDeviceId() ?: return false
        return runCatching {
            client.createRecord(
                collection = col,
                fields = mapOf(
                    "accountId" to s.accountId,
                    "targetDeviceId" to target,
                    "fromDeviceId" to s.recordId,
                    "type" to type,
                    "payload" to payload,
                    "seq" to seq,
                    "ack" to false,
                ),
                token = s.token,
            )
            true
        }.getOrDefault(false)
    }

    override fun incoming(): Flow<RemoteCommand> =
        realtime.subscribe(listOf(col)).mapNotNull { ev ->
            val myId = deviceAuth.session.value?.recordId ?: return@mapNotNull null
            val rec = ev.record
            if (rec.optString("targetDeviceId") != myId) return@mapNotNull null
            if (rec.optBoolean("ack", false)) return@mapNotNull null
            val type = rec.optString("type")
            val seq = rec.optLong("seq", 0)
            val payload = rec.optString("payload")
            // Best-effort: marcar consumido para no re-ejecutar.
            val recId = rec.optString("id")
            val token = deviceAuth.session.value?.token
            if (recId.isNotBlank() && token != null) {
                runCatching { client.updateRecord(col, recId, mapOf("ack" to true), token) }
            }
            when (type) {
                "play" -> RemoteCommand("play", PlayPayloadCodec.decode(payload), null, seq)
                "key" -> RemoteCommand("key", null, payload, seq)
                else -> null
            }
        }
}
```

- [ ] **Step 2: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit**: `feat(remote): CloudTransport (comandos vía PocketBase + realtime)`.

---

## Task 4: `LanTransport` (envuelve SyncManager)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/LanTransport.kt`
- Modify: `app/src/main/java/com/arkiv/player/sync/SyncManager.kt` (añadir un `sendPlayRaw(json: String)` que use el endpoint `/play` con un string arbitrario — o reutilizar `playOnTv` si ya manda el string tal cual; verificar). **Staging quirúrgico** si tiene WIP.

**Interfaces:**
- Consumes: `SyncManager`, `RemoteTransport`, `PlayPayloadCodec`, `SyncManager.keyCodeFor`.
- Produces: `class LanTransport(sync: SyncManager) : RemoteTransport`
  - `reachable()` = `sync.connectRemote()` (descubre por multicast; true si hay peer).
  - `sendPlay(p, _)` = `sync.playOnTv(PlayPayloadCodec.encode(p))` (manda el JSON como cuerpo del `/play`).
  - `sendKey(key, _)` = `sync.sendKey(key)`.
  - `incoming()` = `merge(sync.remotePlay.map { play cmd }, sync.remoteKeys.map { key cmd })`. Para `remotePlay` (emite String), decodificar con `PlayPayloadCodec.decode(it)`. Para `remoteKeys` (emite Int keycode), convertir a nombre de tecla — **pero** el TV inyecta keycodes; ver Task 8: el TV necesita el keycode. Emitir `RemoteCommand("key", key=<keycode.toString()>)` y que el consumidor del TV lo interprete; el `RemoteController` del TV expondrá `incomingKeys: Flow<Int>` (ver Task 6).

- [ ] **Step 1: Verificar/ajustar SyncManager** — confirmar que `playOnTv(episodeId)` manda `episodeId` tal cual como cuerpo del POST `/play` (lo hace, según el código actual: `.post(episodeId.toRequestBody(...))`). Entonces NO hace falta tocar SyncManager para el play (se le pasa el JSON como "episodeId"). El TV lo recibe en `remotePlay` como String y `LanTransport.incoming` lo decodifica. **No modificar SyncManager** salvo que falte algo.

- [ ] **Step 2: Implementar `LanTransport.kt`**

```kotlin
package com.arkiv.player.remote

import com.arkiv.player.sync.SyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Transporte LAN: envuelve el SyncManager (descubrimiento multicast + HTTP /play y /key). */
class LanTransport(private val sync: SyncManager) : RemoteTransport {

    override suspend fun reachable(): Boolean = sync.connectRemote()

    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean =
        sync.playOnTv(PlayPayloadCodec.encode(p))

    override suspend fun sendKey(key: String, seq: Long): Boolean = sync.sendKey(key)

    override fun incoming(): Flow<RemoteCommand> = merge(
        sync.remotePlay.map { RemoteCommand("play", PlayPayloadCodec.decode(it), null, 0) },
        sync.remoteKeys.map { RemoteCommand("key", null, it.toString(), 0) },
    )
}
```

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (1 archivo, `LanTransport.kt`): `feat(remote): LanTransport (envuelve SyncManager)`.

---

## Task 5: `TransportRouter` (selección + failover + merge)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/TransportRouter.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/TransportRouterTest.kt`

**Interfaces:**
- Produces:
  - `class TransportRouter(lan: RemoteTransport, cloud: RemoteTransport)`
  - `suspend fun sendPlay(p, seq): Boolean` — si `lan.reachable()` → `lan.sendPlay`; si falla o no alcanza → `cloud.sendPlay`. `sendKey` igual.
  - `fun incoming(): Flow<RemoteCommand>` = `merge(lan.incoming(), cloud.incoming())`.

- [ ] **Step 1: Test que falla** (con transportes fake)

```kotlin
package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeTransport(
    val reachableValue: Boolean,
    val sendResult: Boolean = true,
    var playCount: Int = 0,
) : RemoteTransport {
    override suspend fun reachable() = reachableValue
    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean { playCount++; return sendResult }
    override suspend fun sendKey(key: String, seq: Long) = sendResult
    override fun incoming(): Flow<RemoteCommand> = emptyFlow()
}

class TransportRouterTest {
    private val payload = PlayPayload(PlayKind.ARCHIVE, "i", "e")

    @Test fun prefersLanWhenReachable() = runBlocking {
        val lan = FakeTransport(reachableValue = true)
        val cloud = FakeTransport(reachableValue = true)
        TransportRouter(lan, cloud).sendPlay(payload, 1)
        assertEquals(1, lan.playCount); assertEquals(0, cloud.playCount)
    }

    @Test fun usesCloudWhenLanUnreachable() = runBlocking {
        val lan = FakeTransport(reachableValue = false)
        val cloud = FakeTransport(reachableValue = true)
        TransportRouter(lan, cloud).sendPlay(payload, 1)
        assertEquals(0, lan.playCount); assertEquals(1, cloud.playCount)
    }

    @Test fun failsOverToCloudWhenLanSendFails() = runBlocking {
        val lan = FakeTransport(reachableValue = true, sendResult = false)
        val cloud = FakeTransport(reachableValue = true)
        val ok = TransportRouter(lan, cloud).sendPlay(payload, 1)
        assertEquals(1, lan.playCount); assertEquals(1, cloud.playCount); assertEquals(true, ok)
    }
}
```

- [ ] **Step 2: Correr — falla.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TransportRouterTest"` → FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** Enruta cada comando por LAN (si el peer está en la misma red) o por la nube; con failover. */
class TransportRouter(
    private val lan: RemoteTransport,
    private val cloud: RemoteTransport,
) {
    suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean = route({ it.sendPlay(p, seq) })

    suspend fun sendKey(key: String, seq: Long): Boolean = route({ it.sendKey(key, seq) })

    private suspend fun route(action: suspend (RemoteTransport) -> Boolean): Boolean {
        if (lan.reachable()) {
            if (action(lan)) return true          // LAN primero
            // failover a la nube si el LAN falló el envío
        }
        if (cloud.reachable()) return action(cloud)
        return false
    }

    fun incoming(): Flow<RemoteCommand> = merge(lan.incoming(), cloud.incoming())
}
```

- [ ] **Step 4: Correr — pasan.** → PASS (3 tests).
- [ ] **Step 5: Commit** (2 archivos): `feat(remote): TransportRouter con failover (testeable)`.

---

## Task 6: `RemoteController` + cableado en AppGraph

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/RemoteController.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (exponer `remoteController`) — **staging quirúrgico** (WIP).

**Interfaces:**
- Consumes: `TransportRouter`, `LanTransport`, `CloudTransport`, `SyncManager`, `PocketBaseClient`, `PocketBaseRealtime`, `DeviceAuthManager`, `SeqTracker`, `PocketBaseConfig`.
- Produces:
  - `class RemoteController(sync, client, realtime, deviceAuth, scope)`
  - `suspend fun tvAvailable(): Boolean` — hay un device `kind=tv` en la cuenta Y (LAN alcanzable O sesión nube).
  - `suspend fun sendPlay(p: PlayPayload): Boolean` y `suspend fun sendKey(key: String): Boolean` (usan `SeqTracker.next()` + router).
  - `val incomingPlay: Flow<PlayPayload>` y `val incomingKeys: Flow<Int>` — para el TV; `incomingKeys` mapea `RemoteCommand.key` (string keycode o nombre) a Int (usa `SyncManager.keyCodeFor` para nombres LAN o `toIntOrNull` para keycodes nube); descarta seqs no frescos con `SeqTracker.isFresh`.
  - Internamente: `targetDeviceId` se resuelve consultando `commands`… no — consultando `devices` con filtro `kind='tv'` (cachea el recordId del TV).

- [ ] **Step 1: Implementar `RemoteController.kt`**

```kotlin
package com.arkiv.player.remote

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * API de alto nivel para el control remoto. El celu llama sendPlay/sendKey (enrutados LAN/nube);
 * el TV consume incomingPlay/incomingKeys (merge de ambos transportes, con idempotencia por seq).
 */
class RemoteController(
    private val sync: SyncManager,
    private val client: PocketBaseClient,
    realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val scope: CoroutineScope,
) {
    @Volatile private var cachedTvId: String? = null
    private val seq = SeqTracker()
    private val inSeq = SeqTracker()

    private val lan = LanTransport(sync)
    private val cloud = CloudTransport(client, realtime, deviceAuth, { cachedTvId })
    private val router = TransportRouter(lan, cloud)

    /** Busca (y cachea) el device kind=tv de mi cuenta. */
    private suspend fun resolveTvId(): String? {
        cachedTvId?.let { return it }
        val s = deviceAuth.session.value ?: return null
        val id = runCatching {
            client.listRecords(PocketBaseConfig.COLLECTION_DEVICES, "kind='tv'", s.token)
                .firstOrNull()?.getString("id")
        }.getOrNull()
        cachedTvId = id
        return id
    }

    suspend fun tvAvailable(): Boolean {
        resolveTvId()
        return lan.reachable() || (cachedTvId != null && deviceAuth.session.value != null)
    }

    suspend fun sendPlay(p: PlayPayload): Boolean { resolveTvId(); return router.sendPlay(p, seq.next()) }

    suspend fun sendKey(key: String): Boolean { resolveTvId(); return router.sendKey(key, seq.next()) }

    /** (TV) Plays entrantes, deduplicados por seq. */
    val incomingPlay: Flow<PlayPayload> = router.incoming().mapNotNull { cmd ->
        if (cmd.type != "play" || cmd.play == null) return@mapNotNull null
        if (cmd.seq != 0L && !inSeq.isFresh(cmd.seq)) return@mapNotNull null
        cmd.play
    }

    /** (TV) Teclas entrantes como keycodes de Android. Acepta nombres (LAN) o keycodes (nube). */
    val incomingKeys: Flow<Int> = router.incoming().mapNotNull { cmd ->
        if (cmd.type != "key" || cmd.key == null) return@mapNotNull null
        if (cmd.seq != 0L && !inSeq.isFresh(cmd.seq)) return@mapNotNull null
        cmd.key.toIntOrNull() ?: SyncManager.keyCodeFor(cmd.key)
    }
}
```

> **Nota implementador:** `merge` de dos flows fríos suscribe dos veces si se colecta `incomingPlay` e `incomingKeys` por separado (dos suscripciones SSE). Para evitarlo, comparte una sola suscripción: crea `private val incoming = router.incoming().shareIn(scope, SharingStarted.Eagerly, 0)` y deriva `incomingPlay`/`incomingKeys` de `incoming`. Ajusta los imports (`shareIn`, `SharingStarted`). Verifica que compila.

- [ ] **Step 2: Exponer en AppGraph** (staging quirúrgico; junto a `pairing`):

```kotlin
    val remoteController: com.arkiv.player.remote.RemoteController by lazy {
        com.arkiv.player.remote.RemoteController(syncManager, pbClient, pbRealtime, deviceAuth, applicationScope)
    }
```

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (RemoteController.kt + AppGraph.kt): `feat(remote): RemoteController + cableado en AppGraph`.

---

## Task 7: Popup de reproducción (celu)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/components/PlayTargetSheet.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (donde hoy se llama `playOnTv` en la línea ~339: en vez de mandar directo, abrir el popup) — **staging quirúrgico** (WIP).

**Interfaces:**
- Produces: `@Composable fun PlayTargetSheet(onHere: () -> Unit, onTv: () -> Unit, onCast: () -> Unit, onDismiss: () -> Unit)` — hoja con 3 opciones.

- [ ] **Step 1: Implementar `PlayTargetSheet.kt`** — un `ModalBottomSheet` (Material3) con tres filas: "Ver aquí", "Enviar al TV", "Castear directo". (Seguir el estilo de otros sheets/diálogos del proyecto; buscar con `grep -rn "ModalBottomSheet\|AlertDialog" app/src/main/java/com/arkiv/player/ui`.)

- [ ] **Step 2: Cablear en ArkivRoot** — donde hoy hace `graph.syncManager.playOnTv(epId)`: si `graph.remoteController.tvAvailable()` → mostrar `PlayTargetSheet`:
  - **Ver aquí** → reproducir local (la misma acción que reproducir normalmente).
  - **Enviar al TV** → `graph.remoteController.sendPlay(PlayPayload(kind, id, epId, startMs))` (construir el PlayPayload según el item: archive → `PlayKind.ARCHIVE, id=identifier`; torrent → `PlayKind.TORRENT, id=magnet`).
  - **Castear directo** → la acción de Chromecast/DLNA existente.
  Si no hay TV → reproducir local directo (comportamiento actual sin el botón de enviar).

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (PlayTargetSheet.kt + ArkivRoot.kt): `feat(celu): popup de destino de reproducción (aquí/TV/castear)`.

---

## Task 8: Pad por el router + TV consume RemoteController

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/remote/RemoteScreen.kt` (el pad usa `remoteController.sendKey` en vez de `sync.sendKey`) — **staging quirúrgico** si tiene WIP.
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` (consumir `remoteController.incomingPlay/incomingKeys` en vez de `syncManager.remotePlay/remoteKeys`) — **staging quirúrgico** (WIP).

- [ ] **Step 1: Pad → RemoteController** — en `RemoteScreen.kt`, cambiar `rememberGraph().syncManager` → usar `rememberGraph().remoteController`, y `sync.sendKey(k)` → `remoteController.sendKey(k)`. `connectRemote` → `remoteController.tvAvailable()`.
- [ ] **Step 2: TV consume el router** — en `ArkivTvRoot.kt`, cambiar:
  - `graph.syncManager.remoteKeys.collect { code -> ... }` → `graph.remoteController.incomingKeys.collect { code -> ... }`
  - `graph.syncManager.remotePlay.collect { episodeId -> goToPlayer(episodeId) }` → `graph.remoteController.incomingPlay.collect { p -> goToPlayer(p) }` — donde `goToPlayer` ahora recibe un `PlayPayload` (usar `p.episodeId`, o resolver torrent/archive según `p.kind`; para paridad con hoy, `goToPlayer(p.episodeId)` cubre el caso archive/legacy; para `TORRENT` abrir el flujo de torrent con `p.id` como magnet).
- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (RemoteScreen.kt + ArkivTvRoot.kt): `feat(remote): pad y recepción del TV vía RemoteController (LAN+nube)`.

---

## Task 9: Verificación e2e (controlador, hardware)

- [ ] **Step 1** Instalar en celu y Fire Stick.
- [ ] **Step 2 (misma WiFi → LAN):** con ambos en la misma red, en el celu tocar play en un item → popup → "Enviar al TV" → el TV abre el player. Abrir el pad → las flechas mueven el TV (instantáneo). Confirmar en logs que fue por LAN (no se creó record en `commands`).
- [ ] **Step 3 (redes distintas → nube):** poner el celu en datos móviles (o el TV en otra red) → "Enviar al TV" → el TV (con la app abierta) abre el player vía un record en `commands` (verificar en PocketBase que se creó y quedó `ack=true`). El pad viaja por la nube (con latencia).
- [ ] **Step 4:** verificar idempotencia (no doble-ejecución) y que `commands` no se acumula (ack).

---

## Self-Review (cobertura vs spec §6)

- **Interfaz de transporte + LAN/Cloud + router con failover (spec §6):** Tasks 1,3,4,5 ✔.
- **Co-ubicación por descubrimiento multicast (spec §6):** `LanTransport.reachable()` = `sync.connectRemote()` ✔.
- **Play = comando gordo {kind,id,episodeId,startMs}; el TV resuelve y reproduce (spec §6):** `PlayPayload` + Task 8 ✔.
- **Idempotencia por seq + ack + limpieza (spec §6):** `SeqTracker` (Task 2) + `CloudTransport` ack (Task 3) ✔.
- **Popup Ver aquí/Enviar al TV/Castear directo (spec §6,§8):** Task 7 ✔.
- **Pad por el router (spec §6,§8):** Task 8 ✔.
- **No romper el LAN existente:** `LanTransport` envuelve `SyncManager`; compat legacy en el codec ✔.
- **Fuera de alcance (Plan 5):** coalescing de teclas por la nube, y el servicio foreground del TV (aquí el TV solo escucha con la app abierta) — anotado.

**Notas:** `PlayPayloadCodec`, `SeqTracker`, `TransportRouter` con unit tests JUnit4 puros (transportes fake). `CloudTransport`/`LanTransport`/`RemoteController` y la UI se verifican por compilación + e2e en hardware (matriz misma-WiFi vs redes-distintas).
