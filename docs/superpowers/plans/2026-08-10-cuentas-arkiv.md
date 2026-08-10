# Cuentas Arkiv (la "persona") — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar a la app cuentas de "persona" propias (registro/login/logout) sobre PocketBase, para que el historial siga a la persona entre dispositivos y quede la base para colgar Magis por usuario (Spec 2).

**Architecture:** `accountId` ya agrupa los devices de una persona en cloudsync. Agregamos una colección auth `users` (email+clave) cuyo record lleva ese `accountId`. Login = autenticar la persona y hacer que la sesión del device **adopte** su `accountId` (`switchAccount`), reusando el camino de `adoptIdentity`/pairing; la fusión del historial anónimo es `cloudSync.syncNow()` (reset de cursores + push de todo lo local bajo el nuevo `accountId` + pull), que ya existe. Sync sigue usando el token del **device**, no el de la persona.

**Tech Stack:** Kotlin, Jetpack Compose, PocketBase (self-host en `blog`), OkHttp, Room, JUnit4 + okhttp `MockWebServer`, coroutines (`runBlocking` en tests).

## Global Constraints

- **Alcance v1:** registro + login + logout. **Sin** verificación de email ni recuperación de contraseña.
- **Login opcional:** sin login el device opera anónimo exactamente como hoy; distintas instalaciones son independientes (una logueada, otra anónima) sin interferir.
- **NO tocar el gateway `arkiv-api`.** Magis es el Spec 2.
- **Commits con identidad `lordmacu`** (`user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`), **sin** pie de coautoría. Verificar antes de commitear.
- **Repo con sesiones concurrentes:** al commitear, agregar SOLO los archivos de cada tarea (`git add <paths>`), **nunca** `git add -A`.
- Tests JVM puros: nada que dependa de `Context` en el `src/test` (por eso el `DeviceStore` se abstrae). Los DAO (Room) y los Composables no se testean en unитario en este repo — se verifican por build + ejecución.

---

## File Structure

**Nuevos:**
- `app/src/main/java/com/arkiv/player/pocketbase/DeviceStore.kt` — interfaz de persistencia de identidad + estado de cuenta (fake-able).
- `app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt` — register/login/logout + `StateFlow<AccountState>`.
- `app/src/main/java/com/arkiv/player/data/LibraryWiper.kt` — borra las tablas de biblioteca locales + resetea cursores (para logout).
- `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt` — sección "Cuenta" reutilizable (móvil).
- `app/src/test/java/com/arkiv/player/pocketbase/FakeDeviceStore.kt` — store en memoria para tests.
- `app/src/test/java/com/arkiv/player/pocketbase/PocketBaseClientAuthRecordTest.kt`
- `app/src/test/java/com/arkiv/player/pocketbase/DeviceAuthManagerSwitchTest.kt`
- `app/src/test/java/com/arkiv/player/pocketbase/AccountManagerTest.kt`

**Modificados:**
- `pocketbase/PocketBaseConfig.kt` — `COLLECTION_USERS`.
- `pocketbase/SecureDeviceStore.kt` — implementa `DeviceStore` + claves de email de persona.
- `pocketbase/PocketBaseClient.kt` — `authWithPasswordRecord`.
- `pocketbase/DeviceAuthManager.kt` — depende de `DeviceStore`; `switchAccount`, `resetToAnonymous`.
- `data/db/Daos.kt` — hard-delete por tabla.
- `AppGraph.kt` — construir `AccountManager` + `LibraryWiper`, wiring.
- `ui/settings/SettingsScreen.kt` — usar `AccountSection`.
- `ui/tv/TvSettingsScreen.kt` — sección "Cuenta" (TV).

---

## Task 1: Colección `users` en PocketBase (server) + config

Infra + una constante. No lleva test unitario (es config de servidor). El resto del plan asume que la colección existe.

**Files:**
- Server: PocketBase admin en `db.comparadorinternet.co` (host `blog`).
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/PocketBaseConfig.kt`

**Interfaces:**
- Produces: colección auth `users` con campo `accountId` (text). Constante `PocketBaseConfig.COLLECTION_USERS = "users"`.

- [ ] **Step 1: Crear la colección `users` (auth) en el admin de PocketBase**

En el admin UI (o vía API de migración), crear una colección **auth** llamada `users` con:
- Campo extra `accountId`: type `text`, **required**, min 1.
- Regla **Create** (registro público): vacía/`""` (permitido a cualquiera) o la que ya usa `devices` para alta anónima.
- Auth con `identity`=email habilitado.

- [ ] **Step 2: Reglas de acceso de las colecciones de sync**

Verificar que `library_items`, `episodes`, `progress`, `markers` permitan a un device autenticado (colección `devices`) crear/leer/actualizar filas cuyo `accountId` no es el suyo original. Es el mismo modelo que ya usa el pairing (`newTvDevice(accountId)` + `adoptIdentity`), así que en principio ya está cubierto. Si las reglas están atadas a `@request.auth.accountId`, ajustarlas para permitir el `accountId` que el device declara (documentarlo aquí).

- [ ] **Step 3: Verificar por API**

```bash
# Registro
curl -sX POST https://db.comparadorinternet.co/api/collections/users/records \
  -H 'Content-Type: application/json' \
  -d '{"email":"probe1@example.com","password":"probeprobe","passwordConfirm":"probeprobe","accountId":"acc-probe"}'
# Login (debe devolver token + record.accountId)
curl -sX POST https://db.comparadorinternet.co/api/collections/users/auth-with-password \
  -H 'Content-Type: application/json' \
  -d '{"identity":"probe1@example.com","password":"probeprobe"}'
```
Expected: el primero devuelve `{"id":...}`; el segundo, `{"token":...,"record":{...,"accountId":"acc-probe"}}`. Borrar el record de prueba después.

- [ ] **Step 4: Agregar la constante**

En `PocketBaseConfig.kt`:
```kotlin
object PocketBaseConfig {
    const val BASE_URL = "https://db.comparadorinternet.co"
    const val COLLECTION_DEVICES = "devices"
    const val COLLECTION_USERS = "users"
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/PocketBaseConfig.kt
git commit -m "feat(cuentas): coleccion users en PocketBase + constante"
```

---

## Task 2: Interfaz `DeviceStore` + claves de persona

Extrae la superficie de `SecureDeviceStore` a una interfaz para poder testear `DeviceAuthManager`/`AccountManager` con un fake en memoria (JVM puro, sin `Context`). Agrega la persistencia del email de persona.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pocketbase/DeviceStore.kt`
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/SecureDeviceStore.kt`
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/DeviceAuthManager.kt:16-19` (tipo del parámetro)
- Create: `app/src/test/java/com/arkiv/player/pocketbase/FakeDeviceStore.kt`

**Interfaces:**
- Produces: `interface DeviceStore { fun save(id: DeviceIdentity); fun load(): DeviceIdentity?; fun saveToken(t: String); fun token(): String?; fun clear(); fun savePersonEmail(email: String); fun personEmail(): String?; fun clearPersonEmail() }`
- Consumes (later tasks): `FakeDeviceStore : DeviceStore` para tests.

- [ ] **Step 1: Crear la interfaz `DeviceStore`**

`pocketbase/DeviceStore.kt`:
```kotlin
package com.arkiv.player.pocketbase

/** Persistencia de la identidad del device + estado de cuenta de persona. */
interface DeviceStore {
    fun save(identity: DeviceIdentity)
    fun load(): DeviceIdentity?
    fun saveToken(token: String)
    fun token(): String?
    fun clear()

    /** Email de la persona logueada (solo para UI/estado; la clave NO se guarda). */
    fun savePersonEmail(email: String)
    fun personEmail(): String?
    fun clearPersonEmail()
}
```

- [ ] **Step 2: `SecureDeviceStore` implementa `DeviceStore` + claves de persona**

En `SecureDeviceStore.kt`: cambiar la firma a `class SecureDeviceStore(context: Context) : DeviceStore {`, poner `override` en `save/load/saveToken/token/clear`, y agregar:
```kotlin
    override fun savePersonEmail(email: String) { prefs.edit().putString(K_PERSON_EMAIL, email).apply() }
    override fun personEmail(): String? = prefs.getString(K_PERSON_EMAIL, null)
    override fun clearPersonEmail() { prefs.edit().remove(K_PERSON_EMAIL).apply() }
```
y en el `companion object`: `const val K_PERSON_EMAIL = "personEmail"`.

Nota: `clear()` (que borra todo) también borra `K_PERSON_EMAIL` — correcto para el logout.

- [ ] **Step 3: `DeviceAuthManager` depende de la interfaz**

En `DeviceAuthManager.kt`, cambiar el parámetro del constructor de `private val store: SecureDeviceStore` a `private val store: DeviceStore`. No hay otros cambios (los métodos usados existen en la interfaz). `AppGraph` sigue pasando un `SecureDeviceStore` (que ahora es un `DeviceStore`).

- [ ] **Step 4: Crear `FakeDeviceStore` (test)**

`src/test/.../pocketbase/FakeDeviceStore.kt`:
```kotlin
package com.arkiv.player.pocketbase

class FakeDeviceStore(private var identity: DeviceIdentity? = null) : DeviceStore {
    private var token: String? = null
    private var personEmail: String? = null
    override fun save(identity: DeviceIdentity) { this.identity = identity }
    override fun load(): DeviceIdentity? = identity
    override fun saveToken(token: String) { this.token = token }
    override fun token(): String? = token
    override fun clear() { identity = null; token = null; personEmail = null }
    override fun savePersonEmail(email: String) { personEmail = email }
    override fun personEmail(): String? = personEmail
    override fun clearPersonEmail() { personEmail = null }
}
```

- [ ] **Step 5: Compilar**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: compila (los cambios son de tipo/override, sin lógica nueva).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/DeviceStore.kt \
        app/src/main/java/com/arkiv/player/pocketbase/SecureDeviceStore.kt \
        app/src/main/java/com/arkiv/player/pocketbase/DeviceAuthManager.kt \
        app/src/test/java/com/arkiv/player/pocketbase/FakeDeviceStore.kt
git commit -m "refactor(cuentas): DeviceStore interfaz + email de persona (testable)"
```

---

## Task 3: `PocketBaseClient.authWithPasswordRecord`

`authWithPassword` hoy solo devuelve token+recordId; el login necesita el `accountId` que vive en el record. Agregamos un método que devuelve el record completo.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/PocketBaseClient.kt:36-46`
- Create: `app/src/test/java/com/arkiv/player/pocketbase/PocketBaseClientAuthRecordTest.kt`

**Interfaces:**
- Produces: `data class AuthRecord(val token: String, val recordId: String, val record: JSONObject)` y `suspend fun authWithPasswordRecord(collection: String, identity: String, password: String): AuthRecord`.

- [ ] **Step 1: Escribir el test que falla**

`PocketBaseClientAuthRecordTest.kt`:
```kotlin
package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketBaseClientAuthRecordTest {
    @Test
    fun authWithPasswordRecord_devuelveTokenYRecord() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"token":"tok-1","record":{"id":"rec-1","accountId":"acc-9","email":"a@b.co"}}"""))
        server.start()
        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val r = client.authWithPasswordRecord("users", "a@b.co", "secret12")

        assertEquals("tok-1", r.token)
        assertEquals("rec-1", r.recordId)
        assertEquals("acc-9", r.record.getString("accountId"))
        val req = server.takeRequest()
        assertEquals("/api/collections/users/auth-with-password", req.path)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Correr el test — debe fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "*PocketBaseClientAuthRecordTest*"`
Expected: FAIL (unresolved reference `authWithPasswordRecord`).

- [ ] **Step 3: Implementar**

En `PocketBaseClient.kt`, después de `authWithPassword` agregar:
```kotlin
    data class AuthRecord(val token: String, val recordId: String, val record: org.json.JSONObject)

    suspend fun authWithPasswordRecord(collection: String, identity: String, password: String): AuthRecord =
        withContext(Dispatchers.IO) {
            val body = JSONObject(mapOf("identity" to identity, "password" to password))
                .toString().toRequestBody(jsonType)
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/auth-with-password")
                .post(body)
                .build()
            val json = execute(req)
            val record = json.getJSONObject("record")
            AuthRecord(json.getString("token"), record.getString("id"), record)
        }
```

- [ ] **Step 4: Correr el test — debe pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "*PocketBaseClientAuthRecordTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/PocketBaseClient.kt \
        app/src/test/java/com/arkiv/player/pocketbase/PocketBaseClientAuthRecordTest.kt
git commit -m "feat(cuentas): authWithPasswordRecord devuelve el record (accountId)"
```

---

## Task 4: `DeviceAuthManager.switchAccount` + `resetToAnonymous`

`switchAccount` hace que el device adopte el `accountId` de la persona: actualiza el record del device en PocketBase, la identidad guardada y la sesión viva. `resetToAnonymous` re-bootstrapea una identidad anónima nueva (logout).

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/DeviceAuthManager.kt`
- Create: `app/src/test/java/com/arkiv/player/pocketbase/DeviceAuthManagerSwitchTest.kt`

**Interfaces:**
- Consumes: `DeviceStore` (Task 2), `PocketBaseClient.updateRecord`, `DeviceSession(accountId, deviceId, recordId, token)`.
- Produces: `suspend fun switchAccount(newAccountId: String): DeviceSession`, `suspend fun resetToAnonymous(): DeviceSession?`.

- [ ] **Step 1: Escribir el test que falla**

`DeviceAuthManagerSwitchTest.kt`:
```kotlin
package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthManagerSwitchTest {
    @Test
    fun switchAccount_adoptaElAccountIdEnSesionStoreYServidor() = runBlocking {
        val server = MockWebServer()
        // ensureBootstrapped() -> authExisting() -> auth-with-password (devices)
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        // switchAccount() -> updateRecord (PATCH devices/devrec)
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}"""))
        server.start()

        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))
        val seed = DeviceIdentity("A_anon", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone")
        val store = FakeDeviceStore(seed)
        val mgr = DeviceAuthManager(client, store)

        mgr.ensureBootstrapped()
        val session = mgr.switchAccount("A_person")

        assertEquals("A_person", session.accountId)
        assertEquals("A_person", mgr.session.value?.accountId)
        assertEquals("A_person", store.load()?.accountId)     // persistido
        server.takeRequest() // auth
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/collections/devices/records/devrec", patch.path)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Correr el test — debe fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeviceAuthManagerSwitchTest*"`
Expected: FAIL (unresolved reference `switchAccount`).

- [ ] **Step 3: Implementar en `DeviceAuthManager`**

Agregar dentro de la clase (usa `col`, `mutex`, `_session`, `store`, `client`, `createNewAccount` ya existentes):
```kotlin
    /** El device adopta un accountId (login de persona): actualiza server + store + sesión viva. */
    suspend fun switchAccount(newAccountId: String): DeviceSession = mutex.withLock {
        val current = _session.value ?: error("switchAccount sin sesión de dispositivo")
        client.updateRecord(col, current.recordId, mapOf("accountId" to newAccountId), current.token)
        store.load()?.let { store.save(it.copy(accountId = newAccountId)) }
        val updated = current.copy(accountId = newAccountId)
        _session.value = updated
        updated
    }

    /** Logout: descarta la identidad actual y crea una anónima nueva (accountId nuevo, vacío). */
    suspend fun resetToAnonymous(): DeviceSession? {
        mutex.withLock {
            _session.value = null
            store.clear()
        }
        return ensureBootstrapped()   // store vacío -> createNewAccount()
    }
```

- [ ] **Step 4: Correr el test — debe pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeviceAuthManagerSwitchTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/DeviceAuthManager.kt \
        app/src/test/java/com/arkiv/player/pocketbase/DeviceAuthManagerSwitchTest.kt
git commit -m "feat(cuentas): switchAccount (adoptar accountId) + resetToAnonymous"
```

---

## Task 5: Borrado local de biblioteca para logout

`LibraryWiper` borra las tablas sincronizables locales y resetea los cursores, para que tras logout la identidad anónima nueva arranque vacía (sin filtrar datos de la persona). Los DAO ganan hard-delete por tabla.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (ItemDao, PlaybackDao, SkipMarkerDao)
- Create: `app/src/main/java/com/arkiv/player/data/LibraryWiper.kt`

**Interfaces:**
- Consumes: `ItemDao`, `PlaybackDao`, `SkipMarkerDao`, `SyncCursors.resetAll(cols)`.
- Produces: `class LibraryWiper(...) { suspend fun wipe() }`. Cols reseteadas: `["library_items","episodes","progress","markers"]` (mismos nombres que `CloudSyncManager`).

- [ ] **Step 1: Agregar hard-delete a los DAO**

En `Daos.kt`, dentro de `ItemDao`:
```kotlin
    @Query("DELETE FROM items")
    suspend fun deleteAllItems()

    @Query("DELETE FROM episodes")
    suspend fun deleteAllEpisodes()
```
dentro de `PlaybackDao`:
```kotlin
    @Query("DELETE FROM playback")
    suspend fun deleteAllPlayback()
```
dentro de `SkipMarkerDao`:
```kotlin
    @Query("DELETE FROM skip_markers")
    suspend fun deleteAllMarkers()
```

- [ ] **Step 2: Crear `LibraryWiper`**

`data/LibraryWiper.kt`:
```kotlin
package com.arkiv.player.data

import com.arkiv.player.cloudsync.SyncCursors
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao

/**
 * Borra la biblioteca local sincronizable + resetea cursores. Se usa en logout: la nueva identidad
 * anónima arranca en blanco y no re-empuja los datos de la persona (que quedan a salvo en el server).
 */
class LibraryWiper(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val cursors: SyncCursors,
) {
    suspend fun wipe() {
        itemDao.deleteAllEpisodes()
        itemDao.deleteAllItems()
        playbackDao.deleteAllPlayback()
        skipMarkerDao.deleteAllMarkers()
        cursors.resetAll(listOf("library_items", "episodes", "progress", "markers"))
    }
}
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compila (los `@Query DELETE` los valida Room en build).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt \
        app/src/main/java/com/arkiv/player/data/LibraryWiper.kt
git commit -m "feat(cuentas): LibraryWiper + hard-delete por tabla para logout"
```

---

## Task 6: `AccountManager` (register / login / logout / estado)

El orquestador. Sin red propia: usa `PocketBaseClient` + `DeviceAuthManager`; el merge y el wipe entran por lambdas (`onAccountSwitched`, `onLocalWipe`) para no acoplar con `CloudSyncManager`.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt`
- Create: `app/src/test/java/com/arkiv/player/pocketbase/AccountManagerTest.kt`

**Interfaces:**
- Consumes: `PocketBaseClient` (`createRecord`, `authWithPasswordRecord`, `PocketBaseException`), `DeviceAuthManager` (`ensureBootstrapped`, `switchAccount`, `resetToAnonymous`), `DeviceStore` (`personEmail`, `savePersonEmail`, `clearPersonEmail`), `PocketBaseConfig.COLLECTION_USERS`.
- Produces:
  - `sealed interface AccountState { data object Anonimo; data class Conectado(val email: String) }`
  - `class AccountException(message: String) : Exception(message)`
  - `class AccountManager(client, deviceAuth, store, onAccountSwitched: suspend () -> Unit, onLocalWipe: suspend () -> Unit)` con `val state: StateFlow<AccountState>`, `suspend fun register(email, password)`, `suspend fun login(email, password)`, `suspend fun logout()`.

- [ ] **Step 1: Escribir los tests que fallan**

`AccountManagerTest.kt`:
```kotlin
package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagerTest {
    private fun clientFor(server: MockWebServer) =
        PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun seededAuth(client: PocketBaseClient, store: DeviceStore) =
        DeviceAuthManager(client, store)

    @Test
    fun register_creaUsersConAccountIdDelDeviceYQuedaConectado() = runBlocking {
        val server = MockWebServer()
        // ensureBootstrapped -> authExisting (devices auth)
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}"""))
        // register -> createRecord(users)
        server.enqueue(MockResponse().setBody("""{"id":"usr-1"}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        var merged = false
        val mgr = AccountManager(client, deviceAuth, store, onAccountSwitched = { merged = true }, onLocalWipe = {})

        mgr.register("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co"), mgr.state.value)
        assertEquals("a@b.co", store.personEmail())
        // register NO dispara merge (accountId no cambia)
        assertTrue(!merged)
        // el body de createRecord(users) lleva el accountId del device
        server.takeRequest() // auth
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().contains("\"accountId\":\"A_anon\""))
        server.shutdown()
    }

    @Test
    fun login_adoptaAccountIdDeLaPersonaYDisparaMerge() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setBody("""{"token":"utok","record":{"id":"usr-1","accountId":"A_person"}}""")) // users auth
        server.enqueue(MockResponse().setBody("""{"id":"devrec"}""")) // switchAccount PATCH
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val deviceAuth = seededAuth(client, store)
        var merged = false
        val mgr = AccountManager(client, deviceAuth, store, onAccountSwitched = { merged = true }, onLocalWipe = {})

        mgr.login("a@b.co", "secret12")

        assertEquals(AccountState.Conectado("a@b.co"), mgr.state.value)
        assertEquals("A_person", deviceAuth.session.value?.accountId)
        assertTrue("login debe disparar el merge", merged)
        server.shutdown()
    }

    @Test
    fun login_conCredencialesInvalidas_lanzaAccountException() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")) // bootstrap
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Failed to authenticate."}""")) // users auth
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_anon","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        val mgr = AccountManager(client, seededAuth(client, store), store, onAccountSwitched = {}, onLocalWipe = {})

        var threw = false
        try { mgr.login("a@b.co", "bad") } catch (e: AccountException) { threw = true }
        assertTrue(threw)
        assertEquals(AccountState.Anonimo, mgr.state.value)
        server.shutdown()
    }

    @Test
    fun logout_limpiaLocalYVuelveAnonimo() = runBlocking {
        val server = MockWebServer()
        // resetToAnonymous -> createNewAccount: createRecord(devices) + authWithPassword(devices)
        server.enqueue(MockResponse().setBody("""{"id":"devrec2"}"""))
        server.enqueue(MockResponse().setBody("""{"token":"dtok2","record":{"id":"devrec2"}}"""))
        server.start()
        val client = clientFor(server)
        val store = FakeDeviceStore(DeviceIdentity("A_person","dev-1","dev-1@arkiv.local","pw12345678","phone"))
        store.savePersonEmail("a@b.co")
        var wiped = false
        val mgr = AccountManager(client, seededAuth(client, store), store, onAccountSwitched = {}, onLocalWipe = { wiped = true })

        mgr.logout()

        assertEquals(AccountState.Anonimo, mgr.state.value)
        assertTrue("logout debe limpiar local", wiped)
        assertEquals(null, store.personEmail())
        server.shutdown()
    }
}
```

- [ ] **Step 2: Correr — deben fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "*AccountManagerTest*"`
Expected: FAIL (unresolved `AccountManager`/`AccountState`).

- [ ] **Step 3: Implementar `AccountManager`**

`pocketbase/AccountManager.kt`:
```kotlin
package com.arkiv.player.pocketbase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountState {
    data object Anonimo : AccountState
    data class Conectado(val email: String) : AccountState
}

class AccountException(message: String) : Exception(message)

/**
 * Cuentas de persona sobre PocketBase (colección `users`). Login = el device ADOPTA el accountId de
 * la persona (switchAccount) y se fusiona el historial anónimo vía [onAccountSwitched] (= syncNow).
 * Logout limpia lo local ([onLocalWipe]) y re-bootstrapea anónimo. La clave nunca se persiste.
 */
class AccountManager(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val store: DeviceStore,
    private val onAccountSwitched: suspend () -> Unit,
    private val onLocalWipe: suspend () -> Unit,
) {
    private val users = PocketBaseConfig.COLLECTION_USERS
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AccountState>(
        store.personEmail()?.let { AccountState.Conectado(it) } ?: AccountState.Anonimo
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    suspend fun register(email: String, password: String) = mutex.withLock {
        val session = deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        try {
            client.createRecord(users, mapOf(
                "email" to email,
                "password" to password,
                "passwordConfirm" to password,
                "accountId" to session.accountId,
            ))
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code == 400) "ese email ya está registrado o los datos son inválidos"
                else e.message ?: "no se pudo crear la cuenta"
            )
        }
        store.savePersonEmail(email)
        _state.value = AccountState.Conectado(email)
    }

    suspend fun login(email: String, password: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        val auth = try {
            client.authWithPasswordRecord(users, email, password)
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code in 400..403) "email o contraseña inválidos"
                else e.message ?: "no se pudo iniciar sesión"
            )
        }
        deviceAuth.switchAccount(auth.record.getString("accountId"))
        onAccountSwitched()   // cloudSync.syncNow() = reset cursores + push local + pull => MERGE
        store.savePersonEmail(email)
        _state.value = AccountState.Conectado(email)
    }

    suspend fun logout() = mutex.withLock {
        store.clearPersonEmail()
        onLocalWipe()
        deviceAuth.resetToAnonymous()
        _state.value = AccountState.Anonimo
    }
}
```

- [ ] **Step 4: Correr — deben pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "*AccountManagerTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt \
        app/src/test/java/com/arkiv/player/pocketbase/AccountManagerTest.kt
git commit -m "feat(cuentas): AccountManager (registro/login/logout + estado)"
```

---

## Task 7: Wiring en `AppGraph`

Construir `LibraryWiper` y `AccountManager`, cableando `onAccountSwitched = cloudSync.syncNow` y `onLocalWipe = libraryWiper.wipe`.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (zona de PocketBase/cloudsync, ~309-328)

**Interfaces:**
- Consumes: `cloudSync` (`suspend fun syncNow()`), `itemDao`, `playbackDao`, `skipMarkerDao`, `syncCursors`, `pbClient`, `deviceAuth`, `deviceStore`.
- Produces: `val accountManager: AccountManager` disponible en el grafo para la UI.

- [ ] **Step 1: Agregar los providers**

Después del provider de `cloudSync` en `AppGraph.kt`:
```kotlin
    val libraryWiper: com.arkiv.player.data.LibraryWiper by lazy {
        com.arkiv.player.data.LibraryWiper(itemDao, playbackDao, skipMarkerDao, syncCursors)
    }
    val accountManager: com.arkiv.player.pocketbase.AccountManager by lazy {
        com.arkiv.player.pocketbase.AccountManager(
            client = pbClient,
            deviceAuth = deviceAuth,
            store = deviceStore,
            onAccountSwitched = { cloudSync.syncNow() },
            onLocalWipe = { libraryWiper.wipe() },
        )
    }
```
(Confirmar los nombres exactos de `itemDao`/`playbackDao`/`skipMarkerDao` en `AppGraph`; son los mismos que ya se pasan a `CloudSyncManager` — reutilizarlos.)

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compila.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(cuentas): wiring de AccountManager + LibraryWiper en AppGraph"
```

---

## Task 8: Sección "Cuenta" en Ajustes (móvil)

UI Compose en `SettingsScreen`. No hay test unitario de Compose en este repo; se verifica por build + ejecución. La lógica async se delega a `AccountManager` (ya testeado).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt` (agregar la sección en el `Column`, ~línea 125-131)

**Interfaces:**
- Consumes: `AppGraph.accountManager` (`state: StateFlow<AccountState>`, `register`, `login`, `logout`).
- Produces: `@Composable fun AccountSection(account: AccountManager)`.

- [ ] **Step 1: Crear `AccountSection`**

`ui/settings/AccountSection.kt`:
```kotlin
package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountState
import kotlinx.coroutines.launch

@Composable
fun AccountSection(account: AccountManager) {
    val state by account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val s = state) {
        is AccountState.Conectado -> {
            Text("Conectado como ${s.email}", style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !busy,
                onClick = { scope.launch { busy = true; runCatching { account.logout() }; busy = false } },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Cerrar sesión") }
        }
        AccountState.Anonimo -> {
            OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Contraseña") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun run(op: suspend (String, String) -> Unit) = scope.launch {
                    busy = true
                    try { op(email.trim(), password) } catch (e: AccountException) { error = e.message }
                    busy = false
                }
                Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { run(account::login) }) { Text("Iniciar sesión") }
                OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { run(account::register) }) { Text("Crear cuenta") }
            }
        }
    }
}
```

- [ ] **Step 2: Insertar en `SettingsScreen`**

`SettingsScreen.kt` recibe hoy solo `contentPadding`. Cambiar la firma a
`fun SettingsScreen(contentPadding: PaddingValues, account: AccountManager)` y, dentro del `Column`
(después de `UpdateSection(...)`), agregar:
```kotlin
        AccountSection(account)
```
Actualizar el llamador (donde se navega a `SettingsScreen`, en `ui/ArkivRoot.kt` o el `NavHost`) para pasar `appGraph.accountManager`.

- [ ] **Step 3: Compilar y verificar en device**

Run: `./gradlew :app:assembleDebug`
Expected: compila. Instalar y abrir Ajustes → aparece "Cuenta": crear cuenta / iniciar sesión / cerrar sesión, con estado reflejado. (Requiere Task 1 hecha en el server.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt \
        app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt \
        app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(cuentas): seccion Cuenta en Ajustes (movil)"
```

---

## Task 9: Sección "Cuenta" en Ajustes (TV)

Misma funcionalidad en `TvSettingsScreen`, adaptada al foco/estilo TV (componentes `androidx.tv.material3` si el resto de la pantalla los usa; si no, Material3 estándar como el resto del archivo).

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt`

**Interfaces:**
- Consumes: `AppGraph.accountManager`, y `AccountSection` si el estilo TV lo permite reutilizar; si no, una variante local `TvAccountSection`.

- [ ] **Step 1: Revisar el estilo de `TvSettingsScreen`**

Run: `sed -n '1,80p' app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt`
Decidir: si usa los mismos componentes Material3 que `SettingsScreen`, reutilizar `AccountSection(account)`; si usa `androidx.tv.material3` con foco de D-pad, escribir `TvAccountSection` equivalente (mismos llamados a `account.login/register/logout`, campos con foco TV).

- [ ] **Step 2: Insertar la sección**

Pasar `account: AccountManager` a `TvSettingsScreen` (actualizar el llamador en `ui/tv/ArkivTvRoot.kt`) y agregar la sección "Cuenta" en su layout, con los mismos tres caminos (crear cuenta / iniciar sesión / cerrar sesión) y el estado `Conectado como X`.

- [ ] **Step 3: Compilar y verificar en el Fire TV**

Run: `./gradlew :app:assembleDebug`
Expected: compila. Instalar en el Fire Stick y verificar que la sección es navegable con el control remoto y que login/logout funcionan.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt \
        app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
git commit -m "feat(cuentas): seccion Cuenta en Ajustes (TV)"
```

---

## Verificación final

- [ ] `./gradlew :app:testDebugUnitTest` — toda la suite verde.
- [ ] `./gradlew :app:assembleDebug` — build OK.
- [ ] Manual (con Task 1 en el server):
  - Registrar en el celu → Ajustes muestra "Conectado como X"; el historial anónimo previo sigue visible (promoción en el lugar).
  - Loguear la MISMA cuenta en la TV/Fire Stick → tras el sync aparece el historial de la persona (fusión).
  - Cerrar sesión → el device vuelve a anónimo y en blanco; otra instalación sin login sigue anónima e independiente.

## Cobertura del spec (self-review)

- Modelo `users` + `accountId` → Task 1.
- Registro (promoción en el lugar, sin merge) → Task 6 (`register`) + test.
- Login + fusión (`switchAccount` + `syncNow`) → Tasks 4, 6, 7 + tests.
- Logout (anónimo nuevo, sin fugas) → Tasks 4, 5, 6 + test.
- Anónimo por default / aislamiento entre installs → intrínseco (login opcional; sync por `accountId`).
- UI móvil + TV → Tasks 8, 9.
- Errores (email tomado, login inválido, red caída) → Task 6 (`AccountException`, `ensureBootstrapped` null-safe) + tests.
- Fuera de alcance (recuperación, verificación, Magis) → no hay tareas, correcto.

## Handoff al Spec 2 (Magis)

Con `AccountManager` en pie, el Spec 2 keyea las credenciales + token de Magis por el `accountId` de la persona en el gateway `arkiv-api`, con la cache de `resolve` scopeada por sesión. Ver `docs/superpowers/specs/2026-08-10-cuentas-arkiv-design.md` §Handoff.
