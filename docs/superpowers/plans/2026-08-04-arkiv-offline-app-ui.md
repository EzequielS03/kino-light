# arkiv-offline: sub-proyecto #3 (lado app) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Botón "Descargar offline" (pack + episodio, solo fuentes web) que dispara jobs en
`arkiv-offline`, una pantalla de Descargas con progreso en vivo, y que la app reproduzca desde la
NUC en vez de en vivo cuando corresponda — con preferencia por serie y funcionando también desde
fuera de la red de casa vía el túnel de Cloudflare.

**Architecture:** SSE + poll de respaldo (mismo patrón ya probado en `CloudTransport.kt`/
`PocketBaseRealtime.kt` para el control remoto de TV) alimenta tanto la pantalla de Descargas como
una caché local en Room de "qué está descargado". Un `WorkManager` periódico cubre notificaciones
cuando la app está en segundo plano. La resolución LAN-vs-túnel prueba la IP local primero con
timeout corto y cae al hostname público si no responde.

**Tech Stack:** Kotlin/Compose (app), OkHttp + `okhttp3.sse.EventSource` (cliente SSE), Room
(caché + preferencias), WorkManager (chequeo en segundo plano); Python/Flask (backend
`arkiv-offline`, repo separado en `/Users/cristian/arkiv-offline`).

## Global Constraints

- Repos: la app vive en `/Users/cristian/archive` (`com.arkiv.player`); el backend vive en
  `/Users/cristian/arkiv-offline` (repo Python separado, sin remoto — se despliega a `blog` vía
  rsync+docker compose, mismo proceso ya usado en sub-proyectos #1 y #2).
- Solo fuentes **web** en esta iteración — torrent/archive quedan fuera de alcance.
- `GET /library` requiere header `X-Api-Key`. `GET /stream/<item_id>` requiere el header
  `X-Api-Key` **o** el query param `?api_key=` (libVLC no soporta headers HTTP custom — solo
  `:http-referrer`/`:http-user-agent` — así que el reproductor usa el query param; los demás
  clientes usan el header). Ambos caminos deben aceptar la MISMA clave.
- Nombrar las entidades/tablas nuevas de Room sin colisión con `DownloadEntity`/`downloadDao`
  (tabla `downloads`, ya existente en `data/db/Entities.kt` — es una feature DISTINTA: descargas
  de archive.org al almacenamiento del propio dispositivo vía `DownloadManager` de Android, no
  relacionada con la NUC). Usar el prefijo `Nuc` en los nombres nuevos para que la distinción sea
  obvia a simple vista.
- `ArkivDatabase` está en versión 11 (`app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt`)
  — este plan la sube a 12 con una migración explícita (`ALTER`/`CREATE TABLE IF NOT EXISTS`,
  nunca `fallbackToDestructiveMigration`).
- Todo cliente HTTP nuevo del lado de la app sigue el patrón ya establecido en
  `WebResolverApi.kt` (OkHttp plano + `org.json.JSONObject`, sin Retrofit, funciones `suspend`
  con `withContext(Dispatchers.IO)`) — no introducir una librería HTTP nueva.
- El cliente SSE nuevo replica la configuración exacta de `sseClient()` en
  `pocketbase/PocketBaseRealtime.kt`: `readTimeout(0)`, `pingInterval(20, SECONDS)`, y
  **`.protocols(listOf(okhttp3.Protocol.HTTP_1_1))` es obligatorio** — sin esto, Cloudflare
  resetea la conexión SSE con `RST_STREAM` en conexiones de larga vida (gotcha ya documentado y
  arreglado una vez en este proyecto; no repetirlo).

---

### Task 1: Backend — endpoint SSE `GET /jobs/<id>/events`

**Files:**
- Modify: `/Users/cristian/arkiv-offline/offline/api.py`
- Test: `/Users/cristian/arkiv-offline/tests/test_api.py`

**Interfaces:**
- Produces: `GET /jobs/<int:job_id>/events` — **requiere `X-Api-Key`, igual que `GET /jobs/<id>`**
  (corrección post-revisión: la primera versión de este plan afirmaba incorrectamente que
  `GET /jobs/<id>` no pide key hoy — sí la pide, desde su commit de introducción; el endpoint SSE
  debe requerirla también, mismo chequeo `require_api_key()` ya usado por `get_job`, ANTES de
  empezar a streamear. El consumidor real es el cliente SSE de OkHttp de la app Android —Task 7—
  que sí puede mandar headers custom, a diferencia de un `EventSource` nativo de navegador, así
  que no hace falta el camino dual header-o-query-param que sí necesitan `/library`/`/stream` por
  la limitación de libVLC). Devuelve `Content-Type: text/event-stream`. Cada evento es una línea
  `data: <json>\n\n` donde el JSON es el mismo shape que ya devuelve `GET /jobs/<id>` (incluye
  `status`, `progress`, `items`). El stream termina (cierra la conexión) en cuanto el job llega a
  `status` `done` o `failed`.

- [ ] **Step 1: Escribir el test que falla**

```python
def test_jobs_events_streams_until_terminal_status(client, conn, aria2_stub):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="s1", show_title="S1", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")

    resp = client.get(f"/jobs/{job_id}/events")
    assert resp.status_code == 200
    assert resp.mimetype == "text/event-stream"

    # Simular que el job pasa a done ANTES de leer el body -- el generador debe verlo y cerrar.
    dbmod.update_item_status(conn, job_id_item_id(conn, job_id), "done", file_path="/x", size_bytes=1)
    dbmod.update_job_status(conn, job_id, "done")

    body = b"".join(resp.response)
    events = [line for line in body.decode().split("\n\n") if line.startswith("data:")]
    assert len(events) >= 1
    last = json.loads(events[-1][len("data:"):].strip())
    assert last["status"] == "done"


def test_jobs_events_404_for_unknown_job(client):
    resp = client.get("/jobs/9999/events")
    assert resp.status_code == 404
```

Agregar el helper `job_id_item_id` si no existe ya un equivalente en el archivo de tests (buscar
`dbmod.get_job(conn, job_id)["items"][0]["id"]` como patrón ya usado en otros tests de este
archivo, y reusar esa expresión inline si es más simple que agregar un helper nuevo).

- [ ] **Step 2: Correr el test, confirmar que falla**

Run: `.venv/bin/python -m pytest tests/test_api.py -k jobs_events -v`
Expected: FAIL (404, la ruta no existe todavía)

- [ ] **Step 3: Implementar el endpoint**

En `offline/api.py`, agregar después de la ruta `get_job` existente (dentro de `create_app`):

```python
    @app.get("/jobs/<int:job_id>/events")
    def job_events(job_id):
        job = dbmod.get_job(conn, job_id)
        if job is None:
            return jsonify({"error": "not found"}), 404

        def gen():
            last_payload = None
            while True:
                current = dbmod.get_job(conn, job_id)
                if current is None:
                    break
                current["progress"] = _compute_progress(current, aria2)
                payload = json.dumps(current)
                if payload != last_payload:
                    yield f"data: {payload}\n\n"
                    last_payload = payload
                if current["status"] in ("done", "failed"):
                    break
                time.sleep(1)

        return Response(gen(), mimetype="text/event-stream")
```

Agregar `import json` y `import time` al tope del archivo si no están ya importados (verificar
antes de agregar — `json` casi seguro ya está vía `jsonify`, pero el `json.dumps` directo acá
necesita el módulo importado explícito).

- [ ] **Step 4: Correr el test, confirmar que pasa**

Run: `.venv/bin/python -m pytest tests/test_api.py -k jobs_events -v`
Expected: PASS

- [ ] **Step 5: Correr el suite completo, confirmar que sigue verde**

Run: `.venv/bin/python -m pytest tests/ -q`
Expected: todos los tests existentes (152 antes de este task) más los 2 nuevos, todos en verde.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-offline
git add offline/api.py tests/test_api.py
git commit -m "feat(api): endpoint SSE /jobs/<id>/events para progreso en tiempo real"
```

---

### Task 2: Backend — auth en `/library` y `/stream` (header o query param)

**Files:**
- Modify: `/Users/cristian/arkiv-offline/offline/api.py`
- Test: `/Users/cristian/arkiv-offline/tests/test_api.py`

**Interfaces:**
- Consumes: `require_api_key()` ya existe en `create_app` (usado hoy por `POST /jobs` y
  `DELETE /jobs/<id>`) — comparar contra `cfg.api_key`.
- Produces: `GET /library` y `GET /stream/<item_id>` ahora devuelven `401` si no llega una key
  válida, vía header `X-Api-Key` **o** query param `?api_key=`.

- [ ] **Step 1: Escribir los tests que fallan**

```python
def test_library_requires_api_key(client):
    resp = client.get("/library?series_id=s1")
    assert resp.status_code == 401


def test_library_accepts_header_key(client, cfg):
    resp = client.get("/library?series_id=s1", headers={"X-Api-Key": cfg.api_key})
    assert resp.status_code == 200


def test_library_accepts_query_param_key(client, cfg):
    resp = client.get(f"/library?series_id=s1&api_key={cfg.api_key}")
    assert resp.status_code == 200


def test_stream_requires_api_key(client, conn):
    item_id = _make_done_item(conn)  # helper: crea un job+item en status 'done' con file_path real
    resp = client.get(f"/stream/{item_id}")
    assert resp.status_code == 401


def test_stream_accepts_query_param_key(client, conn, cfg, tmp_path):
    item_id = _make_done_item(conn, tmp_path)
    resp = client.get(f"/stream/{item_id}?api_key={cfg.api_key}")
    assert resp.status_code == 200


def test_stream_wrong_key_rejected(client, conn, tmp_path):
    item_id = _make_done_item(conn, tmp_path)
    resp = client.get(f"/stream/{item_id}?api_key=wrong")
    assert resp.status_code == 401
```

Si no existe ya un fixture `cfg` que exponga la api_key configurada en el `client` de test,
revisar cómo los tests existentes de `POST /jobs`/`DELETE /jobs/<id>` obtienen la key correcta
(buscar `X-Api-Key` en `test_api.py`) y seguir el mismo patrón exacto. `_make_done_item` es un
helper nuevo: crear un job `kind=web` con un item, escribir un archivo real chico en `tmp_path`, y
marcar el item `done` con `file_path` apuntando a ese archivo — mirar `test_stream_*` existentes
en el archivo para el patrón exacto ya usado (probablemente ya existe algo similar para los tests
de `/stream` sin auth que hay que actualizar de todas formas en este mismo task).

- [ ] **Step 2: Correr los tests, confirmar que fallan**

Run: `.venv/bin/python -m pytest tests/test_api.py -k "library or stream" -v`
Expected: los tests de "requires_api_key" fallan (hoy devuelven 200, no 401); revisar también si
hay tests PREVIOS de `/library`/`/stream` sin header que ahora deban actualizarse para incluir la
key (si existen, van a empezar a fallar con este cambio — actualizarlos en el Step 3 junto con la
implementación, no dejarlos rotos).

- [ ] **Step 3: Implementar el chequeo**

En `offline/api.py`, agregar un helper que lea la key de header O de query param:

```python
    def require_api_key_flex():
        key = request.headers.get("X-Api-Key") or request.args.get("api_key")
        if key != cfg.api_key:
            return jsonify({"error": "unauthorized"}), 401
        return None
```

Agregar la llamada al principio de `get_library` y de `stream`:

```python
    @app.get("/library")
    def get_library():
        auth = require_api_key_flex()
        if auth:
            return auth
        series_id = request.args.get("series_id", "")
        ...

    @app.get("/stream/<int:item_id>")
    def stream(item_id):
        auth = require_api_key_flex()
        if auth:
            return auth
        item = dbmod.get_item(conn, item_id)
        ...
```

- [ ] **Step 4: Actualizar cualquier test preexistente que llame a estos endpoints sin key**

Buscar todo uso previo de `client.get("/library...")` o `client.get(f"/stream/...")` en
`test_api.py` que no pase `X-Api-Key` ni `?api_key=`, y agregarle la key correcta — de otro modo
esos tests ahora fallan con 401 en vez de con el resultado que originalmente verificaban.

- [ ] **Step 5: Correr el suite completo, confirmar que pasa**

Run: `.venv/bin/python -m pytest tests/ -q`
Expected: todo en verde, incluyendo los tests nuevos y los actualizados.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-offline
git add offline/api.py tests/test_api.py
git commit -m "feat(api): X-Api-Key (header o query param) tambien en /library y /stream

Antes de exponer arkiv-offline por el tunel publico de Cloudflare,
estos 2 endpoints eran los unicos sin auth -- cualquiera que
adivinara la URL podia ver la biblioteca y streamear contenido. El
query param existe porque libVLC no soporta headers HTTP custom."
```

---

### Task 3: Infra — exponer `arkiv-offline` por el túnel de Cloudflare

Este task no tiene tests automatizados — es configuración de infraestructura en `blog`, verificada
en vivo. Sigue el mismo patrón ya usado para `jackett.comparadorinternet.co`
(`~/.cloudflared/jackett.yml`, ver memoria de sesión) y para `torrents.comparadorinternet.co`.

**Files (en `blog`, no en ningún repo local):**
- Modify: el archivo de ingress de cloudflared correspondiente en `~/.cloudflared/` en blog (el
  mismo mecanismo ya usado para los otros hostnames — inspeccionar `~/.cloudflared/*.yml`
  existentes en blog antes de decidir si esto va en un archivo nuevo o se agrega como una entrada
  más a uno ya existente).

- [ ] **Step 1: Elegir el hostname público**

Usar `arkiv-offline.comparadorinternet.co` (consistente con el resto: `jackett.`,
`torrents.`, `apk.`, `db.` ya en uso bajo el mismo dominio).

- [ ] **Step 2: Agregar la entrada de ingress**

```yaml
  - hostname: arkiv-offline.comparadorinternet.co
    service: http://127.0.0.1:8099
```

Insertarla en el archivo de ingress correspondiente (el que ya cubra el tunnel que llega a blog),
ANTES de cualquier regla catch-all (`service: http_status:404`) — el orden importa en la config
de cloudflared, la primera regla que matchea gana.

- [ ] **Step 3: Reiniciar cloudflared y verificar**

```bash
ssh blog 'sudo systemctl restart cloudflared'   # o systemctl --user, verificar cual gestiona este tunnel especifico
```

Confirmar con una request real, sin tocar producción de otra forma:

```bash
curl -s -o /dev/null -w "%{http_code}\n" "https://arkiv-offline.comparadorinternet.co/jobs/1" -H "X-Api-Key: <la key real>"
```

Esperado: `200` o `404` (si el job 1 no existe) — cualquiera de los dos confirma que el tunnel
enruta correctamente al contenedor real. Un timeout o `502` indica que el ingress no está bien.

- [ ] **Step 4: Confirmar que no rompió nada existente**

```bash
curl -s -o /dev/null -w "%{http_code}\n" "https://jackett.comparadorinternet.co/resolve?url=https://example.com"
```

Esperado: sigue devolviendo una respuesta válida (200 con `{"ok":false,...}` para ese URL de
prueba) — confirma que el reinicio de cloudflared no afectó al resto de los hostnames del mismo
túnel.

No hay commit de código para este task — si el archivo de ingress vive fuera de cualquier repo con
git, no hace falta commitear nada (documentar el cambio en la ficha del task del ledger de
ejecución en su lugar).

---

### Task 4: App — entidades Room + migración 11→12

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt`
- Test: `app/src/androidTest/java/com/arkiv/player/data/db/NucLibraryMigrationTest.kt` (si existe
  ya un archivo de tests de migración equivalente para versiones previas, seguir exactamente ese
  patrón y ubicación — buscar `MIGRATION_10_11` o la migración más reciente y su test asociado
  antes de crear uno nuevo desde cero)

**Interfaces:**
- Produces: `NucLibraryItemEntity` (tabla `nuc_library_items`), `NucLibraryItemDao`,
  `SeriesPlaybackPrefEntity` (tabla `series_playback_prefs`), `SeriesPlaybackPrefDao`. Ambos DAOs
  expuestos desde `ArkivDatabase` como `nucLibraryItemDao()`/`seriesPlaybackPrefDao()`.

- [ ] **Step 1: Agregar las entidades**

En `app/src/main/java/com/arkiv/player/data/db/Entities.kt`, agregar:

```kotlin
/**
 * Caché local de qué episodios ya están descargados en la NUC (arkiv-offline). Se alimenta de
 * GET /library -- ver ArkivOfflineApi -- tanto al abrir el detalle de una serie como por el canal
 * SSE+poll de la pantalla de Descargas. NO confundir con [DownloadEntity]: esa tabla es para
 * descargas al almacenamiento del propio dispositivo (archive.org vía DownloadManager); esta es
 * para contenido que vive en la NUC y se reproduce por streaming remoto.
 */
@Entity(tableName = "nuc_library_items")
data class NucLibraryItemEntity(
    @PrimaryKey val itemId: Long,       // id del item en arkiv-offline (job_items.id)
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val status: String,                 // "done" (unico status que GET /library devuelve)
    val sizeBytes: Long,
    val syncedAt: Long,
)

/**
 * Preferencia de reproducción por serie: NUC (streamear desde arkiv-offline cuando el episodio
 * puntual esté descargado) o LIVE (siempre en vivo). [asked] distingue "todavia no se preguntó"
 * de "el usuario eligió LIVE explícitamente" -- ambos casos empiezan sin fila, así que sin este
 * flag no se podría diferenciar "preguntar" de "ya preguntado y祖 dijo que no".
 */
@Entity(tableName = "series_playback_prefs")
data class SeriesPlaybackPrefEntity(
    @PrimaryKey val seriesId: String,
    val preference: String,             // "NUC" | "LIVE"
    val asked: Boolean,
)
```

(Nota: revisar que no haya un typo accidental como el `祖` de arriba al escribir el archivo real —
eso es un placeholder de ejemplo que NO debe aparecer en el código final, escribir el comentario
limpio.)

- [ ] **Step 2: Agregar los DAOs**

En `app/src/main/java/com/arkiv/player/data/db/Daos.kt`, agregar (mirar la forma de
`DownloadDao` existente en el mismo archivo para el estilo de queries a replicar):

```kotlin
@Dao
interface NucLibraryItemDao {
    @Query("SELECT * FROM nuc_library_items WHERE seriesId = :seriesId")
    suspend fun forSeries(seriesId: String): List<NucLibraryItemEntity>

    @Query("SELECT * FROM nuc_library_items WHERE seriesId = :seriesId AND season = :season AND episode = :episode LIMIT 1")
    suspend fun find(seriesId: String, season: Int, episode: Int): NucLibraryItemEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<NucLibraryItemEntity>)

    @Query("DELETE FROM nuc_library_items WHERE seriesId = :seriesId")
    suspend fun clearForSeries(seriesId: String)
}

@Dao
interface SeriesPlaybackPrefDao {
    @Query("SELECT * FROM series_playback_prefs WHERE seriesId = :seriesId LIMIT 1")
    suspend fun get(seriesId: String): SeriesPlaybackPrefEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: SeriesPlaybackPrefEntity)
}
```

- [ ] **Step 3: Registrar en `ArkivDatabase` con migración 11→12**

En `ArkivDatabase.kt`: agregar ambas entidades a la lista `entities`, subir `version = 12`,
agregar los métodos abstractos `nucLibraryItemDao()`/`seriesPlaybackPrefDao()`, y agregar la
migración (seguir el estilo exacto de `MIGRATION_1_2`/`MIGRATION_2_3` ya en el archivo):

```kotlin
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS nuc_library_items (" +
                        "itemId INTEGER NOT NULL PRIMARY KEY, seriesId TEXT NOT NULL, " +
                        "season INTEGER NOT NULL, episode INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, syncedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_playback_prefs (" +
                        "seriesId TEXT NOT NULL PRIMARY KEY, preference TEXT NOT NULL, " +
                        "asked INTEGER NOT NULL)",
                )
            }
        }
```

Y agregar `MIGRATION_11_12` al array de migraciones que se le pasa al builder de Room (buscar
dónde se listan `MIGRATION_1_2, MIGRATION_2_3, ...` en el `companion object` y agregar la nueva al
final de esa lista).

- [ ] **Step 4: Verificar que compila y las migraciones son válidas**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Si existe un test de migraciones de Room en el proyecto (`MigrationTestHelper` de
`androidx.room:room-testing`), agregar un caso para 11→12 siguiendo el patrón del test de la
migración anterior más reciente; si no existe ningún test de migración en el proyecto hoy, no
introducir el arnés de testing de migraciones desde cero solo para esto — validar manualmente
instalando sobre una versión anterior de la app y confirmando que no crashea al abrir (dejar esto
anotado como verificación manual pendiente para el Task de verificación en vivo al final del plan).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/
git commit -m "feat(db): tablas Room para cache de biblioteca NUC y preferencia por serie

nuc_library_items espeja GET /library de arkiv-offline (que episodios
ya estan descargados). series_playback_prefs guarda si cada serie
reproduce desde la NUC o en vivo, y si ya se pregunto. Migracion 11->12."
```

---

### Task 5: App — `SettingsStore`: URLs de arkiv-offline + API key

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`

**Interfaces:**
- Produces: `nucLanBaseUrl: StateFlow<String>`, `nucTunnelBaseUrl: StateFlow<String>`,
  `nucApiKey: StateFlow<String>`, con sus `setNucLanBaseUrl(v)`/`setNucTunnelBaseUrl(v)`/
  `setNucApiKey(v)` — exactamente el mismo patrón que `webResolverUrl`/`setWebResolverUrl` ya
  presente en este archivo.

- [ ] **Step 1: Agregar los 3 settings nuevos**

Siguiendo el patrón exacto de `webResolverUrl` (líneas ~38-39, ~73, ~99, ~105 del archivo actual —
`_webResolverUrl`/`webResolverUrl`/`setWebResolverUrl`/`KEY_WEB_RESOLVER_URL`/
`DEFAULT_WEB_RESOLVER_URL`), agregar en las secciones correspondientes de la clase:

```kotlin
    private val _nucLanBaseUrl = MutableStateFlow(prefs.getString(KEY_NUC_LAN_URL, DEFAULT_NUC_LAN_URL)!!)
    val nucLanBaseUrl: StateFlow<String> = _nucLanBaseUrl

    private val _nucTunnelBaseUrl = MutableStateFlow(prefs.getString(KEY_NUC_TUNNEL_URL, DEFAULT_NUC_TUNNEL_URL)!!)
    val nucTunnelBaseUrl: StateFlow<String> = _nucTunnelBaseUrl

    private val _nucApiKey = MutableStateFlow(prefs.getString(KEY_NUC_API_KEY, "")!!)
    val nucApiKey: StateFlow<String> = _nucApiKey
```

Junto a los demás setters (buscar el bloque donde vive `fun setWebResolverUrl`):

```kotlin
    fun setNucLanBaseUrl(v: String) { prefs.edit().putString(KEY_NUC_LAN_URL, v).apply(); _nucLanBaseUrl.value = v }
    fun setNucTunnelBaseUrl(v: String) { prefs.edit().putString(KEY_NUC_TUNNEL_URL, v).apply(); _nucTunnelBaseUrl.value = v }
    fun setNucApiKey(v: String) { prefs.edit().putString(KEY_NUC_API_KEY, v).apply(); _nucApiKey.value = v }
```

Junto a las demás `private const val KEY_...`/`const val DEFAULT_...` en el `companion object`:

```kotlin
        private const val KEY_NUC_LAN_URL = "nuc_lan_base_url"
        private const val KEY_NUC_TUNNEL_URL = "nuc_tunnel_base_url"
        private const val KEY_NUC_API_KEY = "nuc_api_key"
        const val DEFAULT_NUC_LAN_URL = "http://192.168.1.100:8099"
        const val DEFAULT_NUC_TUNNEL_URL = "https://arkiv-offline.comparadorinternet.co"
```

(El default de `DEFAULT_NUC_LAN_URL` es un placeholder de IP local razonable — el usuario lo
ajusta desde Ajustes a la IP real de blog en su red; no hay forma de conocerla de antemano desde
el código.)

- [ ] **Step 2: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SettingsStore.kt
git commit -m "feat(settings): URLs LAN/tunel y api key de arkiv-offline"
```

---

### Task 6: App — `ArkivOfflineApi` (cliente REST con fallback LAN→túnel)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/offline/ArkivOfflineApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/offline/ArkivOfflineApiTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.nucLanBaseUrl`/`nucTunnelBaseUrl`/`nucApiKey` (Task 5).
- Produces:
  ```kotlin
  data class NucJobItem(val itemId: Long, val season: Int, val episode: Int, val status: String, val error: String?)
  data class NucJob(val jobId: Long, val status: String, val progress: Float?, val items: List<NucJobItem>)
  data class NucLibraryEntry(val itemId: Long, val season: Int, val episode: Int, val sizeBytes: Long)

  class ArkivOfflineApi(private val settings: SettingsStore, private val client: OkHttpClient = ...) {
      suspend fun createJob(seriesId: String, showTitle: String, items: List<Pair<Int, Pair<Int, String>>>): Long?
      suspend fun getJob(jobId: Long): NucJob?
      suspend fun deleteJob(jobId: Long): Boolean
      suspend fun library(seriesId: String): List<NucLibraryEntry>
      suspend fun deleteLibraryItem(itemId: Long): Boolean
      suspend fun baseUrlResolved(): String   // LAN si responde, si no túnel -- usado también por el cliente SSE del Task 7
      fun streamUrl(itemId: Long, baseUrl: String): String   // agrega ?api_key= -- usado por PlayerViewModel (Task 11)
  }
  ```
  (El tipo exacto de `items` en `createJob` es una simplificación de notación en este documento —
  al implementar, usar una data class clara en vez de `Pair<Int, Pair<Int, String>>` anidado, p.ej.
  `data class NucDownloadItem(val season: Int, val episode: Int, val pageUrl: String)`.)

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
class ArkivOfflineApiTest {
    @Test
    fun `resolves LAN when it responds`() = runTest {
        // MockWebServer en dos instancias (una simula LAN, otra simula tunel); settings apunta
        // a ambas; confirmar que baseUrlResolved() devuelve la LAN cuando esa responde 200.
    }

    @Test
    fun `falls back to tunnel when LAN times out`() = runTest {
        // settings.nucLanBaseUrl apunta a una IP/puerto que nadie escucha (timeout real, corto);
        // confirmar que baseUrlResolved() devuelve la URL del tunel.
    }

    @Test
    fun `createJob posts items array matching arkiv-offline contract`() = runTest {
        // MockWebServer captura el POST /jobs; verificar que el body JSON tiene kind=web,
        // series_id, show_title, items=[{season,episode,source_ref}], y que se envia X-Api-Key.
    }

    @Test
    fun `streamUrl appends api_key query param`() {
        val api = ArkivOfflineApi(fakeSettingsWithKey("secret123"))
        assertEquals("http://x/stream/42?api_key=secret123", api.streamUrl(42, "http://x"))
    }
}
```

Usar `MockWebServer` de `okhttp3.mockwebserver` (verificar si ya es una dependencia del proyecto —
buscar en `app/build.gradle.kts`; si no está, agregarla como `testImplementation`, siguiendo el
patrón de cómo se agregaron las demás dependencias de test existentes). Para el timeout corto de
LAN, usar el mismo patrón de `connectTimeout` corto explícito que ya usa `WebResolverApi` (8s) pero
más corto todavía para este caso (p.ej. 2s) ya que es solo para decidir LAN-vs-túnel, no para
esperar una respuesta de negocio lenta.

- [ ] **Step 2: Correr los tests, confirmar que fallan**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ArkivOfflineApiTest"`
Expected: FAIL (la clase no existe)

- [ ] **Step 3: Implementar `ArkivOfflineApi`**

```kotlin
package com.arkiv.player.data.offline

import com.arkiv.player.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NucDownloadItem(val season: Int, val episode: Int, val pageUrl: String)
data class NucJobItem(val itemId: Long, val season: Int, val episode: Int, val status: String, val error: String?)
data class NucJob(val jobId: Long, val status: String, val progress: Float?, val items: List<NucJobItem>)
data class NucLibraryEntry(val itemId: Long, val season: Int, val episode: Int, val sizeBytes: Long)

class ArkivOfflineApi(
    private val settings: SettingsStore,
    private val lanProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    suspend fun baseUrlResolved(): String = withContext(Dispatchers.IO) {
        val lan = settings.nucLanBaseUrl.value
        val reachable = runCatching {
            lanProbeClient.newCall(Request.Builder().url("$lan/library?series_id=__probe__")
                .header("X-Api-Key", settings.nucApiKey.value).build()).execute().use { it.isSuccessful || it.code == 404 }
        }.getOrDefault(false)
        if (reachable) lan else settings.nucTunnelBaseUrl.value
    }

    fun streamUrl(itemId: Long, baseUrl: String): String =
        "$baseUrl/stream/$itemId?api_key=${settings.nucApiKey.value}"

    suspend fun createJob(seriesId: String, showTitle: String, posterUrl: String, items: List<NucDownloadItem>): Long? =
        withContext(Dispatchers.IO) {
            val base = baseUrlResolved()
            val itemsJson = JSONArray()
            items.forEach { i ->
                itemsJson.put(JSONObject().apply {
                    put("season", i.season); put("episode", i.episode); put("source_ref", i.pageUrl)
                })
            }
            val body = JSONObject().apply {
                put("kind", "web"); put("series_id", seriesId); put("show_title", showTitle)
                put("poster_url", posterUrl); put("items", itemsJson)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/jobs").post(body)
                .header("X-Api-Key", settings.nucApiKey.value).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    JSONObject(resp.body?.string() ?: return@withContext null).optLong("job_id").takeIf { it > 0 }
                }
            }.getOrNull()
        }

    suspend fun getJob(jobId: Long): NucJob? = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/jobs/$jobId")
            .header("X-Api-Key", settings.nucApiKey.value).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                parseJob(JSONObject(resp.body?.string() ?: return@withContext null))
            }
        }.getOrNull()
    }

    suspend fun deleteJob(jobId: Long): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/jobs/$jobId").delete()
            .header("X-Api-Key", settings.nucApiKey.value).build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun library(seriesId: String): List<NucLibraryEntry> = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/library?series_id=$seriesId")
            .header("X-Api-Key", settings.nucApiKey.value).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string() ?: return@withContext emptyList())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    NucLibraryEntry(o.getLong("id"), o.getInt("season"), o.getInt("episode"), o.optLong("size_bytes"))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun deleteLibraryItem(itemId: Long): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/library/$itemId").delete()
            .header("X-Api-Key", settings.nucApiKey.value).build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private fun parseJob(o: JSONObject): NucJob {
        val itemsArr = o.getJSONArray("items")
        val items = (0 until itemsArr.length()).map { i ->
            val it = itemsArr.getJSONObject(i)
            NucJobItem(it.getLong("id"), it.getInt("season"), it.getInt("episode"), it.getString("status"), it.optString("error").ifBlank { null })
        }
        val progress = if (o.isNull("progress")) null else o.optDouble("progress").toFloat()
        return NucJob(o.getLong("id"), o.getString("status"), progress, items)
    }
}
```

Agregar `import okhttp3.MediaType.Companion.toMediaType` si no está cubierto ya por el import de
`toRequestBody`.

- [ ] **Step 4: Correr los tests, confirmar que pasan**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ArkivOfflineApiTest"`
Expected: PASS

- [ ] **Step 5: Registrar en `AppGraph`**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, agregar junto a `webResolverApi` (línea
~111, mismo patrón `by lazy`):

```kotlin
    val arkivOfflineApi: com.arkiv.player.data.offline.ArkivOfflineApi by lazy {
        com.arkiv.player.data.offline.ArkivOfflineApi(settings)
    }
```

(Ajustar el nombre real de la propiedad de `SettingsStore` ya expuesta en `AppGraph` — buscar
cómo `webResolverApi` arriba obtiene su instancia de `SettingsStore` y replicar esa misma
referencia.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/offline/ArkivOfflineApi.kt app/src/test/java/com/arkiv/player/data/offline/ app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(offline): cliente ArkivOfflineApi con fallback LAN->tunel"
```

---

### Task 7: App — cliente SSE + `merge(sse, poll)` de progreso

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/offline/NucJobEvents.kt`
- Test: `app/src/test/java/com/arkiv/player/data/offline/NucJobEventsTest.kt`

**Interfaces:**
- Consumes: `ArkivOfflineApi.getJob(jobId)` (Task 6, para el poll de respaldo),
  `ArkivOfflineApi.baseUrlResolved()`, `SettingsStore.nucApiKey`.
- Produces: `fun observeJob(jobId: Long): Flow<NucJob>` — emite cada vez que cambia el estado del
  job (vía SSE con poll de respaldo), termina cuando el job llega a `done`/`failed`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
class NucJobEventsTest {
    @Test
    fun `merges SSE and poll without duplicate terminal emission`() = runTest {
        // Fake ArkivOfflineApi cuyo getJob() devuelve progresivamente downloading->downloading->done.
        // Fake fuente SSE (Flow inyectable) que emite un evento "done" antes de que el poll lo note.
        // observeJob() debe completar el Flow apenas ve el primer "done" (de cualquiera de los dos
        // caminos) sin esperar al segundo, y sin emitir el estado "done" dos veces.
    }
}
```

Diseñar `NucJobEvents`/`observeJob` de forma que la fuente SSE sea inyectable (un parámetro
`sseSource: (Long) -> Flow<NucJob>` con un default real que hable HTTP, y un fake en el test que
emite valores controlados) — mismo principio de DI ya usado en todo `arkiv-offline` (Python) esta
sesión, aplicado acá del lado Kotlin.

- [ ] **Step 2: Correr el test, confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*.NucJobEventsTest"`
Expected: FAIL (la clase no existe)

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.offline

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

private const val POLL_MS = 4000L

/** OkHttpClient para SSE de larga vida: misma config que pocketbase/PocketBaseRealtime.kt --
 * HTTP/1.1 forzado porque Cloudflare resetea SSE por HTTP/2 con RST_STREAM en conexiones largas. */
private fun sseClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
    .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
    .build()

class NucJobEvents(
    private val api: ArkivOfflineApi,
    private val apiKey: () -> String,
    private val client: OkHttpClient = sseClient(),
) {
    fun observeJob(jobId: Long): Flow<NucJob> {
        val sse = sseSource(jobId)
        val poll = flow {
            while (true) {
                api.getJob(jobId)?.let { emit(it) }
                delay(POLL_MS)
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
        return merge(sse, poll)
            .distinctUntilChanged()
            .takeWhileInclusive { it.status != "done" && it.status != "failed" }
    }

    private fun sseSource(jobId: Long): Flow<NucJob> = callbackFlow {
        val base = api.baseUrlResolved()
        // El endpoint SSE requiere X-Api-Key igual que GET /jobs/<id> (ver Task 1) -- a
        // diferencia de un EventSource nativo de navegador, OkHttp SI puede mandar headers
        // custom en una conexion SSE, asi que no hace falta ningun camino alternativo.
        val req = Request.Builder().url("$base/jobs/$jobId/events")
            .header("X-Api-Key", apiKey()).build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { parseJobEvent(JSONObject(data)) }.getOrNull()?.let { trySend(it) }
            }
        }
        val source = EventSources.createFactory(client).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
}

// takeWhile pero incluyendo el elemento que hace fallar la condición (el estado terminal debe
// emitirse una vez antes de cerrar el Flow -- si no, el consumidor nunca ve el "done" final).
private fun <T> Flow<T>.takeWhileInclusive(pred: (T) -> Boolean): Flow<T> = flow {
    kotlinx.coroutines.flow.collect { value ->
        emit(value)
        if (!pred(value)) throw kotlinx.coroutines.flow.internal.AbortFlowException(this@takeWhileInclusive as kotlinx.coroutines.flow.FlowCollector<*>)
    }
}
```

(La implementación de `takeWhileInclusive` de arriba usa una API interna de coroutines que puede
no estar disponible según la versión exacta de `kotlinx-coroutines` del proyecto — al implementar,
verificar la versión real en `build.gradle.kts` y, si `AbortFlowException` no es accesible, usar
en su lugar un `MutableStateFlow<Boolean>` de "terminado" combinado con `takeWhile` sobre un flow
que primero emite el valor y LUEGO evalúa si debe seguir, o simplemente escribir el operador
manualmente con `collect` + `emit` + un `break` explícito envuelto en su propio `flow { }` sin
depender de ninguna API interna. Priorizar código que compile con la versión real del proyecto por
sobre el snippet exacto de arriba.)

`parseJobEvent(JSONObject)` reusa la misma lógica de parseo que `ArkivOfflineApi.parseJob` (Task
6) — extraerla a una función `internal` compartida en vez de duplicar el parsing en dos archivos.

- [ ] **Step 4: Correr el test, confirmar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*.NucJobEventsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/offline/NucJobEvents.kt app/src/test/java/com/arkiv/player/data/offline/NucJobEventsTest.kt
git commit -m "feat(offline): SSE+poll para progreso de jobs, mismo patron que CloudTransport"
```

---

### Task 8: App — botones de descarga (pack + episodio)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`

**Interfaces:**
- Consumes: `ArkivOfflineApi.createJob(...)` (Task 6), `MirrorWebPack`/`MirrorWebSource` (ya
  existen, ver `WebPackDialog.kt`).
- Produces: nada nuevo que otro task consuma directamente — es wiring de UI terminal.

- [ ] **Step 1: Agregar el botón de "pack completo" junto a `WebPackRow`**

En `AnimeShowDetailScreen.kt`, modificar `WebPackRow` (línea ~793) para aceptar un callback
adicional y mostrar un ícono de descarga (`Icons.Default.Download` o
`Icons.Default.CloudDownload`) al final de la fila:

```kotlin
@Composable
private fun WebPackRow(pack: MirrorWebPack, enabled: Boolean, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(/* igual que hoy */) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFFFB74D))
        Column(Modifier.weight(1f)) { /* igual que hoy */ }
        IconButton(onClick = onDownload, enabled = enabled) {
            Icon(Icons.Default.Download, contentDescription = "Descargar offline", tint = Color(0xFFFFB74D))
        }
    }
}
```

Actualizar los 2 call sites de `WebPackRow` (líneas ~547 y ~565) para pasar el nuevo
`onDownload = { downloadPack(p) }` (función a agregar en el Step 2).

- [ ] **Step 2: Confirmar el campo de temporada real en `MirrorWebSource`**

Antes de escribir `downloadPack`, leer `WebMirrorModels.kt` (creado en el Task 1 de
`2026-08-03-web-packs-detail-screens.md`, sub-proyecto previo de este mismo repo) y confirmar si
`MirrorWebSource` ya trae un campo de temporada por episodio, o si `MirrorWebPack.episodes` es una
lista plana sin ese dato explícito por ítem. Usar el campo real si existe; si la única forma de
saber la temporada de cada episodio es iterar `pack.seasons` y cruzarla, hacerlo así en el Step 3
— no asumir `season=1` fijo sin haber confirmado esto primero.

- [ ] **Step 3: Implementar `downloadPack` con manejo de error visible**

Agregar en el composable padre (junto a `addWebPack`, línea ~302) — el `error` mostrado es el
mismo `MutableStateFlow<String?>`/variable ya usado por `addWebPack` para mostrar fallos en esta
misma pantalla (reusar esa vía, no crear un canal de error nuevo):

```kotlin
    fun downloadPack(pack: MirrorWebPack) {
        val s = show ?: return
        scope.launch {
            val items = pack.episodes.map {
                com.arkiv.player.data.offline.NucDownloadItem(seasonFor(it), it.episode, it.pageUrl)
            }
            val jobId = graph.arkivOfflineApi.createJob(
                seriesId = "anilist$anilistId", showTitle = s.title, posterUrl = s.posterUrl, items = items,
            )
            if (jobId == null) {
                error = "No se pudo iniciar la descarga (revisá la conexión con la NUC)"
            }
            // El aviso de "descarga terminada" (WorkManager + notificacion local) se conecta
            // aca mismo en el Task 12, Step 2 -- ese task agrega la llamada
            // NucDownloadCheckWorker.schedule(context, jobId) en esta rama del if, una vez que
            // esa clase existe. No adelantarla en este task: todavia no hay nada que llamar.
        }
    }
```

(`seasonFor(it)` es el helper que resuelve del Step 2 — su firma exacta depende de lo que se haya
encontrado ahí. `graph.arkivOfflineApi` se registra en el Task 6, Step 5 de este plan.)

`createJob` devolviendo `null` cubre tanto errores de red genéricos como el caso `409` (sin
espacio en la NUC) — `arkiv-offline` no distingue el motivo en el código de respuesta que el
cliente ve hoy más allá del status, así que el mensaje de error es genérico; no hace falta
parsear el body del 409 para esta primera versión.

- [ ] **Step 4: Agregar el botón de episodio individual**

Buscar el composable que renderiza cada fila de episodio dentro de un pack ya abierto/expandido
(dentro del flujo de `epPacks`/episodios individuales visibles en la pantalla, no dentro del
diálogo) y agregar un ícono de descarga análogo, disparando un `createJob` con un solo item.

- [ ] **Step 5: Refrescar la caché local al abrir el detalle de una serie**

Esto es lo que garantiza que "¿está este episodio descargado?" (usado por
`PlaybackPreferenceStore`, Task 10) tenga datos frescos incluso si el usuario nunca visitó la
pantalla de Descargas para esta serie — por ejemplo, si la descarga se disparó desde otro
dispositivo o después de reinstalar la app.

En el `LaunchedEffect`/bloque de carga inicial que ya existe al abrir `AnimeShowDetailScreen`
(buscar el `LaunchedEffect(Unit)` o equivalente que hoy carga los datos de la serie al entrar a la
pantalla), agregar:

```kotlin
    LaunchedEffect(anilistId) {
        scope.launch {
            val entries = graph.arkivOfflineApi.library("anilist$anilistId")
            val items = entries.map {
                com.arkiv.player.data.db.NucLibraryItemEntity(
                    it.itemId, "anilist$anilistId", it.season, it.episode, "done", it.sizeBytes,
                    System.currentTimeMillis(),
                )
            }
            graph.database.nucLibraryItemDao().clearForSeries("anilist$anilistId")
            graph.database.nucLibraryItemDao().upsertAll(items)
        }
    }
```

Repetir el mismo bloque en `CineDetailScreen.kt` en el Step 6 (con el `seriesId` real de esa
pantalla, que puede no tener el prefijo `anilist` — usar el mismo identificador que esa pantalla
ya usa para consultar el resto de sus datos).

- [ ] **Step 6: Repetir Steps 1-5 en `CineDetailScreen.kt`**

Mismo patrón, adaptado a la estructura de esa pantalla (buscar su propio uso de `WebPackDialog`
en la línea ~486 ya identificada, y el equivalente de fila de pack ahí).

- [ ] **Step 7: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat(ui): boton Descargar offline en packs web y episodios individuales

Tambien refresca la cache local de biblioteca NUC al abrir el detalle
de una serie, para que la preferencia de reproduccion tenga datos
frescos aunque nunca se haya visitado la pantalla de Descargas."
```

---

### Task 9: App — pantalla de Descargas

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/downloads/DownloadsScreen.kt`
- Create: `app/src/main/java/com/arkiv/player/ui/downloads/DownloadsViewModel.kt`

**Interfaces:**
- Consumes: `ArkivOfflineApi.library(...)`, `NucJobEvents.observeJob(...)` (Task 7).
- Produces: `DownloadsScreen(onBack: () -> Unit)` — registrar la ruta de navegación siguiendo el
  patrón ya existente para otras pantallas nuevas de este proyecto (buscar cómo se registra
  `SettingsScreen` en el grafo de navegación y replicar exactamente esa forma).

- [ ] **Step 1: `DownloadsViewModel`**

```kotlin
package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.NucJob
import com.arkiv.player.data.offline.NucJobEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val api: ArkivOfflineApi,
    private val events: NucJobEvents,
    private val activeJobIds: () -> List<Long>,   // jobs disparados en esta sesion -- ver nota abajo
) : ViewModel() {
    private val _activeJobs = MutableStateFlow<List<NucJob>>(emptyList())
    val activeJobs: StateFlow<List<NucJob>> = _activeJobs

    init {
        activeJobIds().forEach { id ->
            viewModelScope.launch {
                events.observeJob(id).collect { job ->
                    _activeJobs.value = (_activeJobs.value.filterNot { it.jobId == job.jobId } + job)
                }
            }
        }
    }

    fun cancel(jobId: Long) {
        viewModelScope.launch { api.deleteJob(jobId) }
    }
}
```

Nota sobre `activeJobIds`: `arkiv-offline` no tiene hoy un endpoint "listame todos los jobs" (solo
`GET /jobs/<id>` por id puntual) — la app necesita su PROPIO registro local de qué `job_id`
disparó, para saber cuáles consultar/observar. Agregar esto como una tabla Room mínima adicional
(`local_active_jobs`, solo `jobId: Long` + `createdAt: Long`) en el Task 4 si no se contempló, o
como parte de este mismo task si es más simple — un registro que se llena cuando `downloadPack`
(Task 8) crea un job exitosamente, y se limpia cuando el job llega a estado terminal. Documentar
en el reporte de este task cuál de los dos caminos se tomó.

- [ ] **Step 2: `DownloadsScreen` (Compose)**

```kotlin
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit) {
    val active by viewModel.activeJobs.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Descargas") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(active) { job ->
                ListItem(
                    headlineContent = { Text("Job #${job.jobId} — ${job.status}") },
                    supportingContent = { job.progress?.let { LinearProgressIndicator(progress = { it }) } },
                    trailingContent = {
                        IconButton(onClick = { viewModel.cancel(job.jobId) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar")
                        }
                    },
                )
            }
        }
    }
}
```

Esta es una primera versión funcional — mejoras visuales (poster, nombre de la serie en vez del
job id crudo, agrupar por serie) quedan abiertas para iterar después de la verificación en vivo,
no son parte del criterio de aceptación de este task.

- [ ] **Step 3: Registrar la navegación**

Seguir el patrón exacto usado para registrar `SettingsScreen` en el NavHost del proyecto (buscar
`"settings"` como ruta en el archivo de navegación principal) y agregar una entrada análoga para
`DownloadsScreen`, más una entrada de menú (donde sea que hoy se accede a Ajustes) para llegar ahí.

- [ ] **Step 4: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/downloads/
git commit -m "feat(ui): pantalla de Descargas con progreso en vivo"
```

---

### Task 10: App — preferencia de reproducción por serie (diálogo + Room)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/offline/PlaybackPreferenceStore.kt`
- Test: `app/src/test/java/com/arkiv/player/data/offline/PlaybackPreferenceStoreTest.kt`

**Interfaces:**
- Consumes: `SeriesPlaybackPrefDao`, `NucLibraryItemDao` (Task 4).
- Produces:
  ```kotlin
  enum class PlaybackChoice { NUC, LIVE }
  class PlaybackPreferenceStore(prefDao: SeriesPlaybackPrefDao, libraryDao: NucLibraryItemDao) {
      suspend fun decide(seriesId: String, season: Int, episode: Int): PlaybackDecision
      suspend fun remember(seriesId: String, choice: PlaybackChoice)
  }
  sealed class PlaybackDecision {
      data class Play(val choice: PlaybackChoice, val itemId: Long?) : PlaybackDecision()
      data class AskFirst(val itemId: Long) : PlaybackDecision()   // primera vez que hay copia disponible para esta serie
  }
  ```

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
class PlaybackPreferenceStoreTest {
    @Test fun `no downloaded copy -> always LIVE, no ask`() = runTest { /* ... */ }
    @Test fun `first time with a downloaded copy -> AskFirst`() = runTest { /* ... */ }
    @Test fun `preference NUC and episode downloaded -> Play NUC with itemId`() = runTest { /* ... */ }
    @Test fun `preference NUC but THIS episode not downloaded -> Play LIVE, no re-ask`() = runTest { /* ... */ }
    @Test fun `preference LIVE -> always Play LIVE regardless of downloaded status`() = runTest { /* ... */ }
    @Test fun `remember persists the choice and sets asked=true`() = runTest { /* ... */ }
}
```

Usar una base de datos Room en memoria (`Room.inMemoryDatabaseBuilder`) para estos tests, mismo
patrón que cualquier otro test de DAO ya existente en el proyecto (buscar un ejemplo real de test
de DAO existente y replicar el setup exacto).

- [ ] **Step 2: Correr los tests, confirmar que fallan**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PlaybackPreferenceStoreTest"`
Expected: FAIL

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.offline

import com.arkiv.player.data.db.NucLibraryItemDao
import com.arkiv.player.data.db.SeriesPlaybackPrefDao
import com.arkiv.player.data.db.SeriesPlaybackPrefEntity

enum class PlaybackChoice { NUC, LIVE }

sealed class PlaybackDecision {
    data class Play(val choice: PlaybackChoice, val itemId: Long?) : PlaybackDecision()
    data class AskFirst(val itemId: Long) : PlaybackDecision()
}

class PlaybackPreferenceStore(
    private val prefDao: SeriesPlaybackPrefDao,
    private val libraryDao: NucLibraryItemDao,
) {
    suspend fun decide(seriesId: String, season: Int, episode: Int): PlaybackDecision {
        val downloaded = libraryDao.find(seriesId, season, episode)
        val pref = prefDao.get(seriesId)
        if (downloaded == null) return PlaybackDecision.Play(PlaybackChoice.LIVE, null)
        if (pref == null || !pref.asked) return PlaybackDecision.AskFirst(downloaded.itemId)
        return when (pref.preference) {
            "NUC" -> PlaybackDecision.Play(PlaybackChoice.NUC, downloaded.itemId)
            else -> PlaybackDecision.Play(PlaybackChoice.LIVE, null)
        }
    }

    suspend fun remember(seriesId: String, choice: PlaybackChoice) {
        prefDao.upsert(SeriesPlaybackPrefEntity(seriesId, choice.name, asked = true))
    }
}
```

- [ ] **Step 4: Correr los tests, confirmar que pasan**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PlaybackPreferenceStoreTest"`
Expected: PASS

- [ ] **Step 5: Registrar en `AppGraph`**

Igual que en el Task 6, agregar en `AppGraph.kt`:

```kotlin
    val playbackPreferenceStore: PlaybackPreferenceStore by lazy {
        PlaybackPreferenceStore(database.seriesPlaybackPrefDao(), database.nucLibraryItemDao())
    }
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/offline/PlaybackPreferenceStore.kt app/src/test/java/com/arkiv/player/data/offline/PlaybackPreferenceStoreTest.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(offline): logica de preferencia de reproduccion por serie (preguntar 1 vez)"
```

---

### Task 11: App — `SourceKind.NUC`, `PlayerViewModel.loadFromNuc`, botón de override manual

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`
- Modify: el archivo donde vive el enum `SourceKind` (buscar su definición — probablemente en el
  mismo `PlayerViewModel.kt` o en un archivo de modelos del player cercano)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (botón de override)

**Interfaces:**
- Consumes: `PlaybackPreferenceStore.decide(...)` (Task 10), `ArkivOfflineApi.streamUrl(...)`
  (Task 6).
- Produces: `SourceKind.NUC` nuevo valor de enum; `PlayerViewModel` decide automáticamente entre
  `loadWeb`/`loadFromNuc` según `PlaybackPreferenceStore`, con un método público
  `forcePlayLive(episodeId)` que el botón de override invoca para saltarse la preferencia
  guardada en esa reproducción puntual.

- [ ] **Step 1: Agregar `SourceKind.NUC` al enum**

Localizar la definición de `SourceKind` (usado hoy con `TORRENT`, `WEB`, y presumiblemente
`ARCHIVE`/otros — leer el archivo completo antes de tocarlo) y agregar `NUC` como un valor más.

- [ ] **Step 2: Implementar `loadFromNuc`**

En `PlayerViewModel.kt`, agregar junto a `loadWeb` (línea ~155):

```kotlin
    private suspend fun loadFromNuc(episodeId: String, itemId: Long) {
        val ep = repo.getEpisode(episodeId)
        val base = withContext(Dispatchers.IO) { arkivOfflineApi.baseUrlResolved() }
        val url = arkivOfflineApi.streamUrl(itemId, base)
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: "NUC",
            subtitle = ep?.section ?: "",
            mediaUrl = url,
            castUrl = url,   // /stream soporta Range directo, no necesita el rewrite de proxy que si necesita HLS
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.NUC,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.NUC)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
    }
```

- [ ] **Step 3: Decisión de fuente antes de reproducir**

Localizar el punto donde `PlayerViewModel` hoy decide entre `loadWeb`/`loadTorrent`/etc. según el
tipo de fuente del episodio (buscar el `when`/`if` que llama a `loadWeb(episodeId)` — es la misma
función ya vista en el Step de exploración de este plan). Antes de esa decisión, para fuentes web,
consultar `PlaybackPreferenceStore.decide(seriesId, season, episode)`:

- `Play(NUC, itemId)` → llamar `loadFromNuc(episodeId, itemId)`.
- `Play(LIVE, _)` → llamar `loadWeb(episodeId)` (comportamiento actual, sin cambios).
- `AskFirst(itemId)` → exponer un nuevo estado observable (p.ej. `_askPlaybackSource: MutableStateFlow<AskPlaybackSourceState?>`) que `PlayerScreen` observa para mostrar un diálogo de confirmación ("¿Reproducir esta serie desde tu NUC cuando esté disponible?"); al responder, llamar `PlaybackPreferenceStore.remember(seriesId, choice)` y luego proceder con `loadFromNuc`/`loadWeb` según la elección.

Extraer `seriesId`/`season`/`episode` del `episodeId` requiere revisar cómo el resto del código ya
obtiene esos campos a partir de un `episodeId` (buscar `repo.getEpisode(episodeId)` — el objeto
`Episode` devuelto probablemente ya trae `season`/`episode`, y el `seriesId` sale de la porción
antes de `::` en el propio `episodeId`, mismo patrón que `itemId = episodeId.substringBefore("::")`
ya usado arriba).

- [ ] **Step 4: `forcePlayLive` para el botón de override**

```kotlin
    fun forcePlayLive(episodeId: String) {
        viewModelScope.launch { loadWeb(episodeId) }
    }
```

(Este método NO llama a `PlaybackPreferenceStore.remember` — es una excepción puntual para ESTE
capítulo, no cambia la preferencia guardada de la serie, tal como decidido en el spec.)

- [ ] **Step 5: Botón de override en `PlayerScreen`**

Agregar un botón/ícono siempre visible en los controles del reproductor (junto a los demás
controles existentes — subtítulos, calidad, etc., seguir el estilo visual ya establecido ahí) que
invoque `viewModel.forcePlayLive(episodeId)` cuando la reproducción actual vino de `SourceKind.NUC`
(mostrar "Reproducir en vivo" en ese caso) — no mostrar el botón cuando la fuente ya es `WEB`/otra
(no hay "otro camino" al que ofrecer volver en ese caso, dado que esta iteración no tiene un
fallback NUC→ninguna-otra-fuente definido).

- [ ] **Step 6: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/
git commit -m "feat(player): reproducir desde la NUC segun preferencia, boton de override manual"
```

---

### Task 12: App — `WorkManager` de fondo + notificación local

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/offline/NucDownloadCheckWorker.kt`

**Interfaces:**
- Consumes: `ArkivOfflineApi.getJob(...)` (Task 6), el registro de jobs activos (mismo mecanismo
  del Task 9).
- Produces: `NucDownloadCheckWorker.schedule(context)` / `NucDownloadCheckWorker.scheduleFor(context, jobId)`.

- [ ] **Step 1: Implementar el worker**

Siguiendo el patrón exacto de `TvKeepAliveWorker.kt` (ya visto) y el patrón de notificación de
`TorrentServingService.kt` (canal + `NotificationCompat.Builder`):

```kotlin
package com.arkiv.player.data.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class NucDownloadCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId <= 0) return Result.failure()
        val api = /* obtener ArkivOfflineApi -- mismo grafo de dependencias usado en el resto de la app */
        val job = api.getJob(jobId) ?: return Result.retry()
        return when (job.status) {
            "done" -> { notify(job.jobId, "Descarga completa", "Tu descarga terminó"); Result.success() }
            "failed" -> { notify(job.jobId, "Descarga falló", "No se pudo completar la descarga"); Result.success() }
            else -> {
                // sigue en curso: re-encolar otra pasada en 30s (WorkManager no soporta un poll
                // continuo dentro de un mismo doWork() de forma confiable en background)
                schedule(applicationContext, jobId, delaySeconds = 30)
                Result.success()
            }
        }
    }

    private fun notify(jobId: Long, title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Descargas NUC", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()
        nm.notify(jobId.toInt(), notif)
    }

    companion object {
        private const val KEY_JOB_ID = "job_id"
        private const val CHANNEL_ID = "arkiv_nuc_downloads"

        fun schedule(context: Context, jobId: Long, delaySeconds: Long = 0) {
            val work = OneTimeWorkRequestBuilder<NucDownloadCheckWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "nuc_download_check_$jobId", ExistingWorkPolicy.REPLACE, work,
            )
        }
    }
}
```

Verificar el ícono real disponible para `setSmallIcon` (el placeholder
`android.R.drawable.stat_sys_download_done` es un ícono del sistema válido pero genérico — si el
proyecto ya tiene íconos propios para notificaciones, como los usados en
`TorrentServingService`/`TvConnectionService`, preferir uno consistente con esos en vez del
genérico de Android).

- [ ] **Step 2: Disparar el schedule al crear un job**

En `downloadPack` (`AnimeShowDetailScreen.kt`) y su equivalente en `CineDetailScreen.kt` (ambos
del Task 8), y en la función del download de episodio individual (también Task 8) — buscar el
comentario `// El aviso de "descarga terminada"...` que el Task 8 dejó como marcador exacto de
dónde va esta línea — reemplazar ese comentario por:

```kotlin
NucDownloadCheckWorker.schedule(context, jobId)
```

dentro del bloque `if (jobId == null) { ... } else { ... }` ya existente (rama `else`, jobId no
nulo).

- [ ] **Step 3: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/offline/NucDownloadCheckWorker.kt
git commit -m "feat(offline): WorkManager + notificacion local cuando termina una descarga en background"
```

---

### Task 13: Verificación en vivo (manual, sin subagente)

No es un task de código — es la verificación end-to-end en un dispositivo real + `blog` real,
siguiendo el mismo estándar de rigor que sub-proyectos #1 y #2 (probar contra infraestructura
real, no solo tests unitarios).

- [ ] **Step 1:** Compilar e instalar la app en un dispositivo/emulador real conectado a la red de
  casa. Configurar `nucLanBaseUrl`/`nucTunnelBaseUrl`/`nucApiKey` en Ajustes con los valores reales
  de `blog`.
- [ ] **Step 2:** Disparar la descarga de un pack web real desde la pantalla de detalle. Confirmar
  en `blog` (`docker logs`/`GET /jobs/<id>` directo) que el job se creó correctamente.
- [ ] **Step 3:** Abrir la pantalla de Descargas, confirmar que el progreso se actualiza en vivo
  (sin refrescar manualmente) mientras la descarga corre.
- [ ] **Step 4:** Al terminar, confirmar que aparece la notificación local (con la app en segundo
  plano) y que la pantalla de Descargas refleja el estado `done`.
- [ ] **Step 5:** Abrir esa serie y reproducir el episodio recién descargado — confirmar que
  aparece el diálogo de preferencia la primera vez, que elegir NUC reproduce vía `/stream`
  (verificar en los logs de `arkiv-offline` que la request realmente llegó), y que el botón de
  override fuerza la reproducción en vivo sin cambiar la preferencia guardada.
- [ ] **Step 6:** Desconectar el dispositivo de la red de casa (datos móviles), repetir el Step 5
  — confirmar que el fallback LAN→túnel funciona y la reproducción desde la NUC sigue andando vía
  `arkiv-offline.comparadorinternet.co`.
- [ ] **Step 7:** Confirmar que un usuario sin la API key correcta NO puede acceder a
  `https://arkiv-offline.comparadorinternet.co/library` ni `/stream/<id>` (probar con `curl` sin
  header ni query param, esperar 401).

---

### Task 14: App — "Descargar offline" en `WebPackDialog` (el único camino real hasta hoy)

**Contexto (hallazgo de la verificación en vivo del Task 13):** los botones de descarga del Task 8
viven en `AnimeShowDetailScreen.kt`/`CineDetailScreen.kt`, pero esas pantallas solo son alcanzables
por la pestaña "Catálogo" — oculta por defecto desde antes de este sub-proyecto (`ArkivRoot.kt`,
comentario "el home de descubrimiento lo reemplaza"). El flujo que el usuario usa de verdad
(`Buscar` → elegir serie/anime → "Continuar" sin filtrar temporada/capítulo → tocar un resultado
`PACK`) abre `WebPackDialog` (`ui/catalog/WebPackDialog.kt`), que hoy SOLO ofrece "Guardar
seleccionados" (guardado local, `addWebSeriesEpisode`) — nunca la descarga a la NUC. Este task
agrega la acción de descarga ahí, para que sea alcanzable desde el flujo real sin depender de la
pestaña oculta.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/WebPackDialog.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`

**Interfaces:**
- Consumes: `ArkivOfflineApi.createJob(...)` (Task 6), `NucDownloadCheckWorker.schedule(...)` (Task
  12), `LocalActiveJobEntity`/su DAO (Task 9) — los mismos tres pasos que ya hace `downloadPack` en
  `AnimeShowDetailScreen.kt`/`CineDetailScreen.kt` (Task 8), reusados acá, no reinventados.
- Produces: `WebPackDialog` gana un parámetro nuevo
  `onDownload: (title: String, episodes: List<MirrorWebSource>) -> Unit`.

- [ ] **Step 1: Agregar el botón y el callback a `WebPackDialog`**

En `WebPackDialog.kt`, agregar el parámetro `onDownload` a la firma del composable, y un botón
nuevo junto a "Guardar seleccionados" (mismo `confirmButton`/fila de acciones — revisar el layout
real del `AlertDialog` antes de decidir si entra como tercer botón o como fila aparte arriba de
`dismissButton`/`confirmButton`, dado que `AlertDialog` de Material3 solo expone dos slots
nombrados; puede hacer falta un `Row` custom con los 3 botones dentro de `confirmButton` o mover
"Cancelar" a un ícono):

```kotlin
onDownload: (title: String, episodes: List<MirrorWebSource>) -> Unit,
```

Botón: `"Descargar offline (${selected.size})"`, `enabled = selected.isNotEmpty()` (mismo criterio
que "Guardar seleccionados"), invoca
`onDownload(finalTitle(), pack.episodes.filter { it.pageUrl in selected })` — MISMA lista de
episodios seleccionados que ya usa `onSave`, no todo el pack a ciegas.

- [ ] **Step 2: Confirmar que compila con el nuevo parámetro obligatorio**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAILA en los 3 call sites de `WebPackDialog(...)` que todavía no pasan `onDownload` — es
la señal de que hay que tocarlos los tres, no una casualidad a ignorar.

- [ ] **Step 3: Wiring en `SearchScreen.kt` (el camino que de verdad se usa)**

Leer `SearchPlayback.kt:202-224` (`addWholeWebSeries`) ANTES de escribir código — ahí está la
lógica exacta de `isAnime`/`seriesId` que hay que replicar para que el `seriesId` de la descarga
NUC calce con el que ya usa el guardado local (evita el mismo bug de season/seriesId divergente que
se encontró y arregló en el Task 11):

```kotlin
val isAnime = card.kind == "anime"
val seriesId = if (isAnime) "anilist${card.anilistId ?: animeShow?.id}" else seriesIdFor(card, detail)
```

(`card`, `detail`, `animeShow` ya están en scope en `SearchScreen.kt` alrededor de la función
`addWholeSeries` existente, línea ~198 — `seriesIdFor` es una función privada de
`SearchPlayback.kt`; si no es accesible desde `SearchScreen.kt`, exponerla o replicar su lógica de
3 líneas ahí mismo, lo que sea menos invasivo dado el resto del archivo.)

Agregar una función nueva `downloadWholeSeries(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>)`
en `SearchScreen.kt`, hermana de `addWholeSeries` (línea ~198), que:
1. Calcula `isAnime`/`seriesId` como arriba.
2. Construye `items = episodes.map { NucDownloadItem(it.season, it.episode, it.pageUrl) }`.
3. Llama `graph.arkivOfflineApi.createJob(seriesId, title, resultPoster, items)`.
4. Si `jobId != null`: inserta `LocalActiveJobEntity(jobId, ...)` (mismo patrón que Task 9/12 en
   `AnimeShowDetailScreen.kt`) y llama `NucDownloadCheckWorker.schedule(context, jobId)`. Necesita
   un `Context` — confirmar si `SearchScreen.kt` ya tiene uno en scope (`LocalContext.current`) o
   hay que agregarlo, mismo patrón que el Task 12 tuvo que agregar en las otras dos pantallas.
5. Si `jobId == null`: mostrar error visible reusando el estado `playError` que la función
   `addWholeSeries`/`playWeb` ya usa en este archivo (línea ~190) — no crear un canal de error
   nuevo.

Conectar `onDownload = { title, episodes -> downloadWholeSeries(p, title, episodes) }` en la
llamada a `WebPackDialog` (línea ~300).

- [ ] **Step 4: Wiring en `AnimeShowDetailScreen.kt` y `CineDetailScreen.kt`**

Ambas pantallas ya tienen `downloadPack(pack: MirrorWebPack)` (Task 8, línea ~352/~330
respectivamente) que hoy siempre descarga `pack.episodes` completo. Adaptar (o agregar un overload)
para que acepte una lista explícita de episodios, y conectar el `onDownload` de su propio
`WebPackDialog(...)` a esa función con la selección real del diálogo — mismo principio que el Step
1: no ignorar la selección del usuario y descargar el pack entero a ciegas.

- [ ] **Step 5: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/WebPackDialog.kt app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat(ui): Descargar offline tambien en WebPackDialog, alcanzable desde el flujo real de busqueda"
```

---

### Task 15: App — `NucDownloadsScreen` también muestra los trabajos terminados

**Contexto (hallazgo de la verificación en vivo del Task 13):** el spec original pedía
"activos primero (progreso en vivo)... terminados debajo (de `GET /library` agrupado por serie)",
pero el brief del Task 9 sólo capturó la mitad activa. Verificado en vivo con una descarga real
completa (Ranma1/2, job #5, 2 episodios, `status: done` confirmado en `arkiv-offline`): apenas el
job termina, `NucDownloadsViewModel` lo borra de `local_active_jobs` (a propósito, ver
`NucDownloadsViewModel.kt:44-46`) y la pantalla vuelve a mostrar "Sin descargas activas" — la
descarga sigue perfecta en el servidor, pero desaparece de la UI sin dejar rastro.

`arkiv-offline` no tiene un endpoint "listame todo lo terminado de todas las series" — `GET
/library` exige `series_id` exacto (`db.py:list_library`, `WHERE j.series_id = ?`, un `series_id`
vacío no matchea nada). Por eso este task usa la **caché local** `nuc_library_items` (Task 4/8) en
vez de pedirle al backend una vista cross-series que no existe — mismo principio que ya usa
`PlaybackPreferenceStore` para "¿está esto descargado?".

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/offline/NucDownloadsViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/offline/NucDownloadsScreen.kt`

**Interfaces:**
- Consumes: `NucLibraryItemDao` (Task 4, ya existe).
- Produces: `NucDownloadsViewModel.finished: StateFlow<List<NucLibraryItemEntity>>` — la lista de
  items ya descargados, agrupados por serie en la UI.

- [ ] **Step 1: Agregar la query "todo lo descargado" al DAO**

En `Daos.kt`, dentro de `NucLibraryItemDao` (línea ~263), agregar:

```kotlin
@Query("SELECT * FROM nuc_library_items ORDER BY seriesId, season, episode")
suspend fun getAll(): List<NucLibraryItemEntity>
```

- [ ] **Step 2: Cargar la lista en el ViewModel**

En `NucDownloadsViewModel.kt`, agregar junto a `_activeJobs`:

```kotlin
private val _finished = MutableStateFlow<List<com.arkiv.player.data.db.NucLibraryItemEntity>>(emptyList())
val finished: StateFlow<List<com.arkiv.player.data.db.NucLibraryItemEntity>> = _finished
```

Cargarla en el mismo bloque `init { viewModelScope.launch { ... } }` que ya lee
`jobDao.getAll()` (línea ~33-35), con `_finished.value = libraryDao.getAll()` — el constructor de
`NucDownloadsViewModel` necesita el nuevo parámetro `libraryDao: NucLibraryItemDao`.

Refrescar también cuando un job activo pasa a `done` (el bloque `observe()`, línea ~44): agregar
`_finished.value = libraryDao.getAll()` justo después del `jobDao.delete(job.jobId)` existente —
así un item recién terminado aparece en "terminados" sin tener que salir y reabrir la pantalla.

- [ ] **Step 3: Registrar el nuevo parámetro donde se construye el ViewModel**

En `NucDownloadsScreen.kt` (línea ~53), el `viewModelFactory` que construye
`NucDownloadsViewModel` necesita pasar `graph.database.nucLibraryItemDao()` como el nuevo
parámetro `libraryDao`.

- [ ] **Step 4: Mostrar la sección "Terminados" en la UI**

En `NucDownloadsScreen.kt`, después de la `LazyColumn` de activos (o del `EmptyState` si no hay
ninguno activo), agregar una segunda sección agrupada por `seriesId`:

```kotlin
val finished by vm.finished.collectAsStateWithLifecycle()
// ... dentro del Column, después de la sección de activos:
if (finished.isNotEmpty()) {
    Text(
        "Terminados",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        finished.groupBy { it.seriesId }.forEach { (seriesId, items) ->
            item(key = "header-$seriesId") {
                Text(seriesId, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
            }
            items(items, key = { it.itemId }) { item ->
                ListItem(
                    headlineContent = { Text("T${item.season} · E${item.episode}") },
                    supportingContent = { Text("${item.sizeBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall) },
                    colors = ListItemDefaults.colors(containerColor = ArkivSurface),
                )
            }
        }
    }
}
```

(Si ya hay una `LazyColumn` para "activos" arriba en el mismo `Column`, dos `LazyColumn` anidadas
dentro de un `Column` sin peso/altura fija puede dar problemas de medida en Compose — si eso pasa
al compilar/renderizar, envolver todo en una única `LazyColumn` con secciones via `item{}`/`items{}`
en vez de dos `LazyColumn` separadas.)

Esta es una primera versión funcional — no tiene botón de borrar por ahora (`DELETE
/library/<item_id>` ya existe en `ArkivOfflineApi`, ver Task 6, para un follow-up).

- [ ] **Step 5: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/ui/offline/NucDownloadsViewModel.kt app/src/main/java/com/arkiv/player/ui/offline/NucDownloadsScreen.kt
git commit -m "feat(ui): NucDownloadsScreen tambien muestra los trabajos ya terminados"
```
