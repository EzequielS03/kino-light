# Miniaturas de frame — Fase 2: sincronización entre dispositivos

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el frame capturado en un dispositivo se vea también en el otro.

**Architecture:** La **fila** del frame viaja por el motor de sync que ya existe (`CloudSyncManager` + `PbSyncClient`, con LWW por `updatedAt`, cursores, cuarentena y realtime por SSE) como una colección más. Los **bytes del JPEG** se suben por multipart al detenerse la reproducción y se bajan autenticados recién cuando hay que pintar la tarjeta y el archivo no está en disco.

**Tech Stack:** Kotlin, Room, OkHttp, PocketBase, Coil.

**Spec:** [`docs/superpowers/specs/2026-08-11-miniaturas-de-frame-design.md`](../specs/2026-08-11-miniaturas-de-frame-design.md), sección "Sync ida y vuelta".

## Global Constraints

- Todo en español: código, comentarios, nombres de test y mensajes de commit.
- Los mensajes de commit **nunca** llevan pie de coautoría.
- **Nunca** `git add -A` ni `git add .`: varias sesiones comparten el repositorio. Agregar por ruta explícita.
- Tests: `./gradlew :app:testDebugUnitTest`.
- La base está en **versión 21**. Las migraciones se registran a mano al final de `ArkivDatabase.addMigrations(...)`.
- El nombre de la colección en PocketBase es **`episode_frames`**, y su natural key es **`episodeId`**.
- El tamaño y la calidad del JPEG no cambian: 960×540, calidad 80.
- Todo el sync es **best-effort**: ninguna falla de red puede tumbar el proceso ni molestar al que está viendo algo.

## Decisión de diseño ya tomada (no rediscutir)

Los bytes se bajan **autenticados** (con el token de la sesión del dispositivo) y se guardan por `AlmacenDeFrames`, en vez de pasarle a Coil la URL pública del archivo en PocketBase. Dos motivos: mantiene TODOS los frames en `filesDir/frames`, que es lo que hace que `borrar()`/`borrarTodo()` de verdad recuperen el disco (se arregló en la fase 1); y no depende de que los archivos del servidor sean legibles sin token, que para datos personales es una apuesta que no hace falta hacer.

---

## Tarea previa que NO hace el plan: crear la colección

Antes de la Task 4 tiene que existir la colección `episode_frames` en `db.comparadorinternet.co`, con estos campos:

```
accountId  text
episodeId  text   (índice: accountId + episodeId único)
positionMs number
updatedAt  number
deleted    number
img        file   (1 archivo, image/jpeg, máx ~200 KB)
```

Las reglas de acceso tienen que copiar las de `progress`, que ya está scopeada por `accountId`.

**Es una acción sobre el servidor del usuario y la hace él, o el coordinador con su permiso explícito.** Las tasks 1 a 3 no la necesitan; la 4 en adelante sí.

---

### Task 1: Multipart en el cliente de PocketBase

Hoy `PocketBaseClient` postea solo JSON (`jsonType`, `fields: Map<String, Any?>`). Para adjuntar el JPEG hace falta `multipart/form-data`.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pocketbase/PocketBaseMultipart.kt`
- Test: `app/src/test/java/com/arkiv/player/pocketbase/PocketBaseMultipartTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/PocketBaseClient.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `PocketBaseMultipart.build(fields: Map<String, Any?>, campoArchivo: String, nombre: String, bytes: ByteArray): okhttp3.MultipartBody`
  - `PocketBaseClient.createRecordConArchivo(collection, fields, campoArchivo, nombre, bytes, token): String`
  - `PocketBaseClient.updateRecordConArchivo(collection, id, fields, campoArchivo, nombre, bytes, token)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.pocketbase

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cuerpo multipart con el que se sube el JPEG de un frame. Se testea la CONSTRUCCIÓN del
 * cuerpo, no la llamada de red: es la parte donde se cometen los errores (un campo que no viaja,
 * un tipo de contenido mal puesto) y la única que se puede probar sin un servidor.
 */
class PocketBaseMultipartTest {

    private fun cuerpoComoTexto(b: okhttp3.MultipartBody): String {
        val buf = Buffer()
        b.writeTo(buf)
        return buf.readUtf8()
    }

    @Test
    fun `viajan todos los campos y el archivo`() {
        val body = PocketBaseMultipart.build(
            fields = mapOf("episodeId" to "ep-1", "updatedAt" to 123L),
            campoArchivo = "img",
            nombre = "frame.jpg",
            bytes = byteArrayOf(1, 2, 3),
        )
        val texto = cuerpoComoTexto(body)
        assertTrue(texto.contains("name=\"episodeId\""))
        assertTrue(texto.contains("ep-1"))
        assertTrue(texto.contains("name=\"updatedAt\""))
        assertTrue(texto.contains("123"))
        assertTrue(texto.contains("name=\"img\""))
        assertTrue(texto.contains("filename=\"frame.jpg\""))
    }

    @Test
    fun `el archivo va como image jpeg`() {
        val body = PocketBaseMultipart.build(
            fields = emptyMap(), campoArchivo = "img", nombre = "f.jpg", bytes = byteArrayOf(9),
        )
        assertTrue(cuerpoComoTexto(body).contains("image/jpeg"))
    }

    /** Un campo null se manda como cadena vacía: PocketBase no acepta la ausencia como "borrar". */
    @Test
    fun `un campo null viaja como cadena vacia`() {
        val body = PocketBaseMultipart.build(
            fields = mapOf("x" to null), campoArchivo = "img", nombre = "f.jpg", bytes = byteArrayOf(1),
        )
        assertTrue(cuerpoComoTexto(body).contains("name=\"x\""))
    }

    @Test
    fun `es multipart form-data`() {
        val body = PocketBaseMultipart.build(emptyMap(), "img", "f.jpg", byteArrayOf(1))
        assertEquals("multipart", body.contentType().type)
        assertEquals("form-data", body.contentType().subtype)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.pocketbase.PocketBaseMultipartTest"`
Expected: FAIL — no compila, `PocketBaseMultipart` no existe.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.pocketbase

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Arma el cuerpo `multipart/form-data` con el que se sube el JPEG de un frame.
 *
 * Vive aparte de [PocketBaseClient] para poder testear la construcción del cuerpo sin servidor:
 * los errores de esta parte (un campo que no viaja, un content-type mal puesto) son silenciosos y
 * solo se ven como un 400 del servidor en runtime.
 */
object PocketBaseMultipart {

    private val jpeg = "image/jpeg".toMediaType()

    fun build(
        fields: Map<String, Any?>,
        campoArchivo: String,
        nombre: String,
        bytes: ByteArray,
    ): MultipartBody {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        // Null como cadena vacía: PocketBase interpreta la AUSENCIA del campo como "no lo toques",
        // no como "vacialo", y acá siempre se manda la fila entera.
        fields.forEach { (k, v) -> b.addFormDataPart(k, v?.toString() ?: "") }
        b.addFormDataPart(campoArchivo, nombre, bytes.toRequestBody(jpeg))
        return b.build()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.pocketbase.PocketBaseMultipartTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Agregar los dos métodos al cliente**

En `PocketBaseClient`, al lado de `createRecord`/`updateRecord`, con la misma forma (`withContext(Dispatchers.IO)`, header `Authorization`, `execute(req)`):

```kotlin
/** Igual que [createRecord] pero adjuntando un archivo. Ver [PocketBaseMultipart]. */
suspend fun createRecordConArchivo(
    collection: String,
    fields: Map<String, Any?>,
    campoArchivo: String,
    nombre: String,
    bytes: ByteArray,
    token: String,
): String = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url("$baseUrl/api/collections/$collection/records")
        .header("Authorization", token)
        .post(PocketBaseMultipart.build(fields, campoArchivo, nombre, bytes))
        .build()
    execute(req).getString("id")
}

/** Igual que [updateRecord] pero adjuntando un archivo. */
suspend fun updateRecordConArchivo(
    collection: String,
    id: String,
    fields: Map<String, Any?>,
    campoArchivo: String,
    nombre: String,
    bytes: ByteArray,
    token: String,
) {
    withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/collections/$collection/records/$id")
            .header("Authorization", token)
            .patch(PocketBaseMultipart.build(fields, campoArchivo, nombre, bytes))
            .build()
        execute(req)
    }
}
```

- [ ] **Step 6: Verificar que la suite sigue verde y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/PocketBaseMultipart.kt app/src/test/java/com/arkiv/player/pocketbase/PocketBaseMultipartTest.kt app/src/main/java/com/arkiv/player/pocketbase/PocketBaseClient.kt
git commit -m "feat(sync): multipart en el cliente de pocketbase para subir el jpeg"
```

---

### Task 2: La fila del frame aprende de dónde bajar el archivo

Cuando llega una fila remota no se baja el JPEG en el momento (ver el spec). Hay que guardar de dónde bajarlo después.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `EpisodeFrameEntity.remoteUrl: String?`
  - `EpisodeFrameDao.getFramesSince(cursor: Long): List<EpisodeFrameEntity>`
  - `EpisodeFrameDao.pendientesDeBajar(): List<EpisodeFrameEntity>`

- [ ] **Step 1: Agregar la columna a la entidad**

```kotlin
/**
 * URL del archivo en PocketBase cuando la fila vino de otro dispositivo y el JPEG todavía no se
 * bajó. Null = el frame es local (se capturó acá) o ya se bajó. Es lo que hace posible la bajada
 * perezosa: la fila llega por el sync barato y los bytes recién cuando hay que pintarlos.
 */
val remoteUrl: String? = null,
```

- [ ] **Step 2: Agregar las dos queries al DAO**

```kotlin
/** Filas cambiadas después del cursor, para el push. Espeja a `getPlaybackSince`. */
@Query("SELECT * FROM episode_frame WHERE updatedAt > :cursor ORDER BY updatedAt ASC")
suspend fun getFramesSince(cursor: Long): List<EpisodeFrameEntity>

/** Filas que vinieron de otro dispositivo y cuyo JPEG todavía no está en disco. */
@Query("SELECT * FROM episode_frame WHERE deleted = 0 AND remoteUrl IS NOT NULL")
suspend fun pendientesDeBajar(): List<EpisodeFrameEntity>
```

- [ ] **Step 3: Migración 21 → 22**

Subir `version = 21` a `22`, y agregar al final de `addMigrations(...)`:

```kotlin
/** v21 -> v22: de dónde bajar el JPEG de un frame que vino de otro dispositivo. */
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episode_frame ADD COLUMN remoteUrl TEXT")
    }
}
```

Nullable y sin DEFAULT a propósito: en las filas que ya existen queda NULL, que significa "es local, no hay nada que bajar" — que es exactamente la verdad para todo lo capturado en la fase 1.

- [ ] **Step 4: Verificar y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS (Room valida el esquema al compilar).

```bash
git add app/src/main/java/com/arkiv/player/data/db/Entities.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt
git commit -m "feat(sync): la fila del frame guarda de donde bajar su jpeg (migracion 21-22)"
```

---

### Task 3: Los mapeadores de la fila

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/SyncMappers.kt`
- Test: `app/src/test/java/com/arkiv/player/cloudsync/FrameMappersTest.kt`

**Interfaces:**
- Consumes: `EpisodeFrameEntity` (Task 2).
- Produces:
  - `frameToFields(entity: EpisodeFrameEntity, accountId: String): Map<String, Any?>`
  - `recordToFrame(json: JSONObject, baseUrl: String): EpisodeFrameEntity`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.EpisodeFrameEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La fila del frame de ida y de vuelta contra PocketBase. */
class FrameMappersTest {

    @Test
    fun `la fila local viaja con todos sus campos`() {
        val f = frameToFields(
            EpisodeFrameEntity("ep-1", positionMs = 90_000, capturedAt = 7, updatedAt = 42, deleted = 0),
            "acct-1",
        )
        assertEquals("acct-1", f["accountId"])
        assertEquals("ep-1", f["episodeId"])
        assertEquals(90_000L, f["positionMs"])
        assertEquals(42L, f["updatedAt"])
        assertEquals(0, f["deleted"])
    }

    /** `remoteUrl` NO viaja: es estado local (de dónde bajar), no un dato de la fila. */
    @Test
    fun `remoteUrl no se sube`() {
        val f = frameToFields(
            EpisodeFrameEntity("ep-1", 1, 1, 1, 0, remoteUrl = "https://x/y.jpg"), "acct-1",
        )
        assertEquals(false, f.containsKey("remoteUrl"))
    }

    /** El registro remoto arma la URL del archivo con el id de la colección, el del record y el nombre. */
    @Test
    fun `el registro remoto trae de donde bajar el jpeg`() {
        val json = JSONObject(
            """{"episodeId":"ep-1","positionMs":5000,"updatedAt":9,"deleted":0,
                "id":"rec1","collectionId":"col1","img":"frame.jpg"}""",
        )
        val e = recordToFrame(json, "https://pb.test")
        assertEquals("ep-1", e.episodeId)
        assertEquals(5000L, e.positionMs)
        assertEquals(9L, e.updatedAt)
        assertEquals("https://pb.test/api/files/col1/rec1/frame.jpg", e.remoteUrl)
    }

    /** Un tombstone no trae archivo: sin `img` no hay nada que bajar. */
    @Test
    fun `un registro sin archivo no deja url de bajada`() {
        val json = JSONObject("""{"episodeId":"ep-1","updatedAt":9,"deleted":1,"id":"r","collectionId":"c","img":""}""")
        assertNull(recordToFrame(json, "https://pb.test").remoteUrl)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.cloudsync.FrameMappersTest"`
Expected: FAIL — no compila.

- [ ] **Step 3: Write minimal implementation**

En `SyncMappers.kt`, con la misma forma que `playbackToFields`/`recordToPlayback`:

```kotlin
/**
 * La fila del frame hacia PocketBase. `capturedAt` y `remoteUrl` NO viajan: el primero es
 * diagnóstico local y el segundo es estado local (de dónde bajar), no un dato de la fila.
 */
fun frameToFields(entity: EpisodeFrameEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "episodeId" to entity.episodeId,
    "positionMs" to entity.positionMs,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

/**
 * La fila del frame desde PocketBase, con la URL de su archivo ya armada.
 *
 * `capturedAt` toma el `updatedAt` remoto: el instante real de captura vivía en el otro
 * dispositivo y no viaja, y este campo solo se usa para diagnóstico.
 */
fun recordToFrame(json: JSONObject, baseUrl: String): EpisodeFrameEntity {
    val archivo = json.optString("img")
    val url = archivo.takeIf { it.isNotBlank() }?.let {
        "$baseUrl/api/files/${json.optString("collectionId")}/${json.optString("id")}/$it"
    }
    return EpisodeFrameEntity(
        episodeId = json.optString("episodeId"),
        positionMs = json.optLong("positionMs"),
        capturedAt = json.optLong("updatedAt"),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optInt("deleted"),
        remoteUrl = url,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.cloudsync.FrameMappersTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/cloudsync/SyncMappers.kt app/src/test/java/com/arkiv/player/cloudsync/FrameMappersTest.kt
git commit -m "feat(sync): mapeadores de la fila del frame"
```

---

### Task 4: `episode_frames` entra como una colección más del motor

**Requiere que la colección ya exista en el servidor** (ver "Tarea previa").

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt`

**Interfaces:**
- Consumes: `frameToFields`, `recordToFrame` (Task 3); `EpisodeFrameDao.getFramesSince` (Task 2).
- Produces: nada que consuman tareas posteriores.

Son SEIS puntos de enganche, y el patrón está a la vista en el archivo. Faltar uno deja el sync a medias y en silencio:

- [ ] **Step 1: La constante**, al lado de `COL_MARKERS`: `private const val COL_FRAMES = "episode_frames"`
- [ ] **Step 2: `pushAll`**, sumando la línea que espeja a la de progreso:
  `pushRows(COL_FRAMES, episodeFrameDao.getFramesSince(cursors.lastPushed(COL_FRAMES)), { it.episodeId }, { frameToFields(it, acct) }, { it.updatedAt })`
- [ ] **Step 3: `pushRows`**, el `when (col)` del `keyField`: agregar `COL_FRAMES -> "episodeId"`. **OJO**: hoy el `else` cae en `"itemId"`, así que sin esta línea los frames se subirían con la llave equivocada y cada push crearía un record nuevo.
- [ ] **Step 4: `reconcileAll`**, agregar `COL_FRAMES` a la lista del `for (col in listOf(...))`.
- [ ] **Step 5: `subscribeAll`**, agregar `COL_FRAMES` a la lista de `realtime.subscribe(listOf(...))`.
- [ ] **Step 6: `mergeRecord`**, agregar `COL_FRAMES -> mergeFrame(json, remoteUpdatedAt)` al `when`, más el método:

```kotlin
/**
 * Aplica una fila de frame remota si gana el LWW.
 *
 * Cuando el remoto llega con `deleted = 1` no alcanza con guardar la fila: hay que destruir el
 * JPEG local, o el archivo queda ocupando disco para siempre y —peor— se seguiría pintando, porque
 * la ruta la resuelve el disco y no la fila (decisión de la fase 1).
 */
private suspend fun mergeFrame(json: JSONObject, remoteUpdatedAt: Long): Boolean {
    val episodeId = json.optString("episodeId")
    if (episodeId.isBlank()) return false
    val local = episodeFrameDao.get(episodeId)
    if (!LwwMerge.pickWinner(local?.updatedAt ?: 0L, remoteUpdatedAt)) return false
    episodeFrameDao.upsert(recordToFrame(json, PocketBaseConfig.BASE_URL))
    if (json.optInt("deleted") == 1) destructorDeFrames.destruir(episodeId)
    return true
}
```

**OJO con el orden**: primero el `upsert` de la fila y después el `destruir`. Al revés, `destruir`
—que deja tombstone desde la Task 7— sería pisado por el upsert y el borrado no quedaría marcado.
Verificá la firma real de `LwwMerge.pickWinner` en `mergePlayback` antes de copiarla: si toma las
entidades en vez de los timestamps, adaptá la llamada.
- [ ] **Step 7: `syncNow`**, agregar `COL_FRAMES` a la lista de `cursors.resetAll(...)`.

`CloudSyncManager` necesita el `EpisodeFrameDao`: agregarlo como parámetro de constructor con el mismo estilo que los otros DAOs, y pasarlo desde `AppGraph` (único sitio de construcción).

- [ ] **Step 8: Verificar y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS

```bash
git add app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(sync): episode_frames entra al motor de sync como una coleccion mas"
```

---

### Task 5: Subir el JPEG junto con la fila

La fila ya sincroniza, pero sin bytes no se ve nada del otro lado.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/PbSyncClient.kt`
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt`

**Interfaces:**
- Consumes: `PocketBaseClient.createRecordConArchivo` / `updateRecordConArchivo` (Task 1); `AlmacenDeFrames.archivoDe` (fase 1).
- Produces: `PbSyncClient.upsertConArchivo(collection, naturalKeyField, naturalKey, fields, campoArchivo, nombre, bytes)`

- [ ] **Step 1: `upsertConArchivo` en PbSyncClient**

Copiar el cuerpo de `upsert` (misma sesión, mismo `filter` con `accountId`, mismo find-or-create) cambiando las dos llamadas finales por sus variantes con archivo.

- [ ] **Step 2: Que el push de frames lo use**

En `pushRows` el `fields` es genérico y no sabe de archivos. Para no ensuciar el genérico, darle a los frames su propio camino: en `pushAll`, en vez de la línea de `pushRows`, un `pushFrames()` privado que recorra `episodeFrameDao.getFramesSince(...)` y por cada fila:

- Si `deleted == 1` o el archivo no existe en disco (`almacenDeFrames.rutaSiExiste(episodeId) == null`): subir **solo la fila**, con el `pbSync.upsert` de siempre. Un tombstone no lleva imagen.
- Si el archivo existe: `pbSync.upsertConArchivo(...)` con los bytes de `File(ruta).readBytes()` y nombre `"${episodeId hasheado}.jpg"`.

Conservar la resiliencia por fila y el avance del cursor tal como los hace `pushRows`: cuarentena por fila, `CancellationException` re-lanzada, y `cursors.setLastPushed(COL_FRAMES, PushFrontier.advance(...))`.

- [ ] **Step 3: Verificar y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS

```bash
git add app/src/main/java/com/arkiv/player/cloudsync/PbSyncClient.kt app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt
git commit -m "feat(sync): sube el jpeg del frame junto con su fila"
```

---

### Task 6: Bajar el JPEG al pintar

**Files:**
- Create: `app/src/main/java/com/arkiv/player/miniaturas/BajadorDeFrames.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `AlmacenDeFrames.guardar`, `EpisodeFrameDao`, `DeviceAuthManager.session`.
- Produces: `BajadorDeFrames.bajarPendientes()`

- [ ] **Step 1: El bajador**

Una clase chica, gemela de `FrameCapturer` pero del lado de la lectura: recorre `episodeFrameDao.pendientesDeBajar()`, y por cada fila hace un GET autenticado a `remoteUrl` con el token de `deviceAuth.session`, guarda los bytes con `almacenDeFrames.guardar(episodeId, bytes)` y hace `upsert` de la fila con `remoteUrl = null` (ya bajado).

Best-effort de punta a punta, igual que el capturador: cualquier fallo se traga y se reintenta en la próxima. Nunca lanza.

- [ ] **Step 2: Dispararlo donde ya se leen los frames**

En `ArkivRepository.observeContinueWatching` y en `observeEpisodeFrames` ya se pregunta por el archivo en disco. Ahí es donde se sabe que falta: lanzar `bajarPendientes()` en segundo plano (sin bloquear la emisión) cuando haya pendientes. La tarjeta se pinta con el respaldo de TMDB y, cuando el archivo aterriza, el Flow de Room re-emite y se ve el frame.

- [ ] **Step 3: Verificar y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/BajadorDeFrames.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(sync): baja el jpeg del frame cuando hace falta pintarlo"
```

---

### Task 7: Tombstones de los borrados que el progreso NO cubre

El caso principal ya está resuelto desde la fase 1: si un capítulo se marca visto en otro dispositivo, el progreso llega por sync y `mergePlayback` destruye el frame local. Quedan dos borrados que el progreso no propaga.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/miniaturas/DestructorDeFrames.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`

- [ ] **Step 1: Que destruir deje tombstone en vez de borrar la fila**

Hoy `destruir()` borra el archivo y hace `DELETE` de la fila. Para que el borrado viaje, la fila tiene que quedar con `deleted = 1` y `updatedAt` nuevo, no desaparecer: una fila borrada no se puede empujar.

Cambiar `destruir(episodeId)` para que borre el archivo y haga `upsert` de la fila con `deleted = 1`, `updatedAt = ahora()`.

**OJO con `destruirTodo()` (el wipe de logout): ahí NO se deja tombstone.** El wipe es "esta identidad se va de este aparato", no "borrá esto en todos lados"; dejar tombstones ahí borraría los frames de la cuenta en el otro dispositivo.

- [ ] **Step 2: Verificar y commitear**

Run: `./gradlew :app:testDebugUnitTest` → PASS

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/DestructorDeFrames.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt
git commit -m "feat(sync): el borrado de un frame viaja como tombstone"
```

---

## Verificación en dispositivo (la hace el coordinador)

Nada de esto se puede cubrir con tests unitarios, y es lo único que prueba que la fase 2 funciona:

1. Capturar un frame en el TV (reproducir >60 s, pausar). Confirmar que sube: la fila aparece en la colección y el archivo también.
2. En el celular, abrir el home. La tarjeta tiene que pasar del still de TMDB al frame del TV (puede tardar un instante: es la bajada perezosa).
3. Marcar el capítulo como visto en el celular. El frame tiene que desaparecer en los DOS.
4. Sacar la serie de la biblioteca en un dispositivo y confirmar que el frame muere en el otro.

## Lo que este plan NO cubre

- **Crear la colección en PocketBase.** Es una acción sobre el servidor del usuario.
- **Limitar la bajada por tipo de red.** Se evaluó y se descartó: fuera de casa es justo cuando más se usa el celular.
- **Reconciliar archivos huérfanos en el servidor.** Si una fila se borra sin que el archivo se limpie, queda ocupando espacio en PocketBase. No hay hoy ningún barrido remoto, ni lo había antes de esta fase.
