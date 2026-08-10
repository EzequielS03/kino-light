# Magis por usuario — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que cada persona (accountId de Arkiv) use SU cuenta de Magis: el gateway guarda creds+token por `accountId`, la app manda `X-Arkiv-Account` y ofrece "Vincular Magis"; un device sin vincular sigue anónimo.

**Architecture:** El gateway pasa de UNA sesión Magis global a N keyeadas por `accountId`. `MagisSession` se parametriza con un `identity` (anon = claves viejas, retrocompat con el CLI; accountId = claves suffixadas). Un `MagisSessions` devuelve la sesión por request. El adapter resuelve la sesión según el `account_id` que llega en el header (via `SearchContext.account_id` para search, y `payload["account_id"]` para resolve/episodes). La cache de `resolve` se scopea por sesión. La app manda el header y tiene UI para vincular.

**Tech Stack:** Python (FastAPI, redis async, pytest-asyncio, fakeredis) en `arkiv-api`; Kotlin/Compose (OkHttp, MockWebServer) en `archive`.

## Global Constraints

- **Fallback anónimo intacto:** sin header o accountId sin vínculo → la sesión anónima de hoy (claves `magis:session` / `magis:credenciales`). No romper el CLI ni `GET /v1/magis/session` ni `POST/DELETE /v1/magis/credentials` (globales, se conservan).
- **Vincular Magis requiere sesión Arkiv** (accountId de persona); la app solo manda el header/UI si hay login.
- **Cache:** `magis:play:*` scopeado por sesión (`<accountId>` o `anon`); `magis:search:*` y `magis:eps:*` quedan globales.
- **Commits con identidad `lordmacu`** (`user.email=10134930+lordmacu@users.noreply.github.com`), sin coautoría. `git add <paths>` explícito (repos con sesiones concurrentes; nunca `git add -A`).
- **Despliegue del gateway:** NO es git pull — es `rsync` + `docker compose build` (ver memoria [Deploy del gateway en blog]). El plan solo deja el código+tests verdes; el deploy es un paso aparte manual.
- Gateway tests: `pytest`. App tests: JVM puro (JUnit4 + MockWebServer).

---

## File Structure

**Gateway (`arkiv-api`) — modificados:**
- `src/arkiv_api/adapters/magis/session.py` — `MagisSession` gana `identity`; nuevo `MagisSessions`.
- `src/arkiv_api/adapters/magis/adapter.py` — resuelve la sesión por `account_id`; cache de play scopeada.
- `src/arkiv_api/models.py` — `SearchContext.account_id`.
- `src/arkiv_api/router/search.py` — lee `X-Arkiv-Account` → `ctx.account_id`.
- `src/arkiv_api/router/resolve.py` — inyecta `payload["account_id"]` desde el header.
- `src/arkiv_api/router/magis.py` — endpoints `/v1/magis/link` (POST/GET/DELETE) por accountId; globales intactos.
- `src/arkiv_api/app.py` — construye `MagisSessions`; `app.state.magis_sessions`.
- Tests: extender `tests/test_magis_session.py`, `tests/test_router_magis.py`, `tests/test_adapter_magis.py`, `tests/test_router_search.py`.

**App (`archive`) — modificados/nuevos:**
- `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` — header `X-Arkiv-Account`.
- `app/src/main/java/com/arkiv/player/pocketbase/MagisLinkClient.kt` (nuevo) — link/unlink/status.
- `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt` + `ui/tv/TvSettingsScreen.kt` — "Vincular Magis".
- `AppGraph.kt` — proveer accountId efectivo a `ArkivApiClient` + `MagisLinkClient`.

---

## Task 1: `MagisSession` parametrizada por `identity`

**Files:**
- Modify: `src/arkiv_api/adapters/magis/session.py`
- Test: `tests/test_magis_session.py`

**Interfaces:**
- Produces: `MagisSession(redis, bucket, factory, llave_maestra, identity="anon")`. Con `identity="anon"` usa `magis:session` / `magis:credenciales` (como hoy). Con `identity="<acc>"` usa `magis:session:<acc>` / `magis:credenciales:<acc>`.

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_magis_session.py`:
```python
async def test_identity_scopea_las_claves_redis():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    bucket = TokenBucket(redis, "magis", 1)
    anon = MagisSession(redis, bucket, ClienteFalso, MAESTRA)                    # identity por defecto
    acc = MagisSession(redis, bucket, ClienteFalso, MAESTRA, identity="acc-9")
    assert anon._KEY == "magis:session" and anon._KEY_CRED == "magis:credenciales"
    assert acc._KEY == "magis:session:acc-9" and acc._KEY_CRED == "magis:credenciales:acc-9"
    # sesiones independientes: activar la anónima no crea la de la cuenta
    await anon.client()
    assert await redis.exists("magis:session") == 1
    assert await redis.exists("magis:session:acc-9") == 0
```

- [ ] **Step 2: Correr — debe fallar**

Run: `cd /Users/cristian/arkiv-api && python -m pytest tests/test_magis_session.py::test_identity_scopea_las_claves_redis -q`
Expected: FAIL (`_KEY`/`_KEY_CRED` son constantes de clase, no dependen de identity).

- [ ] **Step 3: Implementar**

En `session.py`, quitar `_KEY`/`_KEY_CRED` como constantes de clase y computarlas en `__init__`:
```python
    def __init__(self, redis, bucket, factory, llave_maestra: str, identity: str = "anon") -> None:
        self._r = redis
        self._bucket = bucket
        self._factory = factory
        self._maestra = llave_maestra
        self._lock = asyncio.Lock()
        self._identity = identity
        # anon conserva las claves viejas (retrocompat CLI); por-cuenta van suffixadas.
        suf = "" if identity == "anon" else f":{identity}"
        self._KEY = f"magis:session{suf}"
        self._KEY_CRED = f"magis:credenciales{suf}"
```
Reemplazar todos los usos de `self._KEY`/`self._KEY_CRED` que hoy referencian las constantes de clase por los atributos de instancia (ya se llaman igual, así que solo hay que borrar las dos líneas `_KEY = "..."` / `_KEY_CRED = "..."` de nivel de clase).

- [ ] **Step 4: Correr — debe pasar (y no romper los tests viejos)**

Run: `python -m pytest tests/test_magis_session.py -q`
Expected: PASS (todos, incluidos los existentes).

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/magis/session.py tests/test_magis_session.py
git commit -m "feat(magis): MagisSession parametrizada por identity (claves por accountId)"
```

---

## Task 2: `MagisSessions` (registry por accountId)

**Files:**
- Modify: `src/arkiv_api/adapters/magis/session.py`
- Test: `tests/test_magis_session.py`

**Interfaces:**
- Consumes: `MagisSession` (Task 1).
- Produces: `MagisSessions(redis, bucket, factory, llave_maestra)` con `for_account(account_id: str | None) -> MagisSession`. `None`/`""`/`"anon"` → la sesión anónima. Cachea instancias por identity.

- [ ] **Step 1: Escribir el test que falla**

```python
def test_magissessions_devuelve_por_cuenta_o_anonima():
    import fakeredis.aioredis
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    from arkiv_api.adapters.magis.session import MagisSessions
    mgr = MagisSessions(redis, TokenBucket(redis, "magis", 1), ClienteFalso, MAESTRA)
    anon1 = mgr.for_account(None); anon2 = mgr.for_account("anon")
    acc = mgr.for_account("acc-9")
    assert anon1 is anon2                       # misma instancia anónima
    assert acc._identity == "acc-9"
    assert acc is mgr.for_account("acc-9")      # cacheada
    assert acc is not anon1
```

- [ ] **Step 2: Correr — debe fallar** (`ImportError: MagisSessions`).

Run: `python -m pytest tests/test_magis_session.py::test_magissessions_devuelve_por_cuenta_o_anonima -q`

- [ ] **Step 3: Implementar**

En `session.py`:
```python
class MagisSessions:
    """Fábrica/registro de sesiones Magis por identity. La anónima usa las claves viejas
    (retrocompat CLI); cada accountId tiene la suya, con token compartido entre los devices
    de esa persona."""

    def __init__(self, redis, bucket, factory, llave_maestra: str) -> None:
        self._args = (redis, bucket, factory, llave_maestra)
        self._por_identity: dict[str, MagisSession] = {}

    def for_account(self, account_id: str | None) -> MagisSession:
        identity = account_id or "anon"
        if identity not in self._por_identity:
            self._por_identity[identity] = MagisSession(*self._args, identity=identity)
        return self._por_identity[identity]
```

- [ ] **Step 4: Correr — debe pasar**

Run: `python -m pytest tests/test_magis_session.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/magis/session.py tests/test_magis_session.py
git commit -m "feat(magis): MagisSessions (registro de sesiones por accountId)"
```

---

## Task 3: `MagisAdapter` resuelve sesión por `account_id` + cache scopeada

**Files:**
- Modify: `src/arkiv_api/adapters/magis/adapter.py`
- Test: `tests/test_adapter_magis.py`

**Interfaces:**
- Consumes: `MagisSessions.for_account` (Task 2), `SearchContext.account_id` (Task 4 — usar `getattr(ctx, "account_id", "")` para no acoplar el orden), `payload.get("account_id")`.
- Produces: `MagisAdapter(sessions, cache, signing_key)` — ahora toma el **manager**, no una sola sesión. `_intento`/`_llamar` reciben la `session` a usar. La clave de cache de `resolve` incluye `<sesskey>`.

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_adapter_magis.py`, agregar (mirar el estilo existente del archivo para los fakes de sessions/cache):
```python
async def test_resolve_usa_la_sesion_del_accountId_y_scopea_la_cache():
    # Fake sessions que registra con qué identity se pidió el cliente.
    pedidos = []
    class FakeSessions:
        def for_account(self, acc):
            pedidos.append(acc or "anon")
            return FAKE_SESSION           # una sesión falsa que devuelve un client dummy
    cache = FakeCache()
    ad = MagisAdapter(FakeSessions(), cache, "k")
    await ad.resolve({"content_id": "cid1", "program_type": "movie", "account_id": "acc-9"})
    assert pedidos == ["acc-9"]
    # la clave guardada lleva el sesskey de la cuenta
    assert any(k.startswith("magis:play:") and ":acc-9:" in k for k in cache.claves_set())
```
(Reusar/extender los fakes ya presentes en `tests/test_adapter_magis.py`; si no hay `FakeCache` que exponga las claves, agregarle un `claves_set()`.)

- [ ] **Step 2: Correr — debe fallar**

Run: `python -m pytest tests/test_adapter_magis.py -k accountId -q`
Expected: FAIL (hoy el adapter usa `self._session` fijo y la clave no lleva sesskey).

- [ ] **Step 3: Implementar**

En `adapter.py`:
```python
    def __init__(self, sessions, cache, signing_key: str) -> None:
        self._sessions = sessions
        self._cache = cache
        self._key = signing_key

    @staticmethod
    def _sesskey(account_id: str | None) -> str:
        return account_id or "anon"

    async def _intento(self, session, metodo: str, que: str, *args, **kwargs) -> dict:
        cliente = await session.client()
        await session.throttle()
        crudo = await asyncio.to_thread(getattr(cliente, metodo), *args, **kwargs)
        return _revisar(crudo, que)

    async def _llamar(self, session, metodo: str, que: str, *args, **kwargs) -> dict:
        try:
            return await self._intento(session, metodo, que, *args, **kwargs)
        except PortalError:
            await session.invalidate()
            return await self._intento(session, metodo, que, *args, **kwargs)
```
- `search(ctx)`: `acc = getattr(ctx, "account_id", "") ; session = self._sessions.for_account(acc)` y pasar `session` a cada `self._llamar(session, ...)`.
- `episodes(payload)` y `resolve(payload)`: `acc = payload.get("account_id", ""); session = self._sessions.for_account(acc)`; pasar `session` a `_llamar`/`_intento` (incluye `_capitulos_crudos`, `_capitulo`, `_duracion_pelicula`, `get_slb`).
- La clave de cache de `resolve`: `clave = f"magis:play:v{_VERSION_CACHE}:{self._sesskey(acc)}:{cid}:{episodio}"`.
- `health`: `await self._sessions.for_account(None).client()`.
- `magis:search:*` y `magis:eps:*` NO cambian (catálogo global).

- [ ] **Step 4: Correr — debe pasar**

Run: `python -m pytest tests/test_adapter_magis.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/magis/adapter.py tests/test_adapter_magis.py
git commit -m "feat(magis): adapter resuelve sesion por accountId y scopea la cache de play"
```

---

## Task 4: `SearchContext.account_id` + routers leen `X-Arkiv-Account`

**Files:**
- Modify: `src/arkiv_api/models.py`, `src/arkiv_api/router/search.py`, `src/arkiv_api/router/resolve.py`
- Test: `tests/test_router_search.py`, `tests/test_router_resolve.py`

**Interfaces:**
- Produces: `SearchContext.account_id: str = ""`. El header `X-Arkiv-Account` llega a `ctx.account_id` (search) y a `payload["account_id"]` (resolve/episodes).

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_router_search.py` (mirar cómo arma el TestClient/app):
```python
def test_search_pasa_el_accountId_del_header_al_contexto(client_y_registry_espia):
    client, ctxs = client_y_registry_espia    # espía que captura el SearchContext que reciben los adapters
    client.get("/v1/search?q=x&type=movie", headers={"X-Arkiv-Key": KEY, "X-Arkiv-Account": "acc-9"})
    assert ctxs[-1].account_id == "acc-9"
```
(Si no existe un fixture espía, usar el registry con un `FakeAdapter` que guarde el `ctx` recibido.)

- [ ] **Step 2: Correr — debe fallar**

Run: `python -m pytest tests/test_router_search.py -k accountId -q`

- [ ] **Step 3: Implementar**

- `models.py`: agregar `account_id: str = ""` al `SearchContext` (después de `lang`).
- `router/search.py`, dentro de `search(...)`, al construir el `ctx` agregar
  `account_id=request.headers.get("X-Arkiv-Account", ""),`.
- `router/resolve.py`, en `episodes` y `resolve`, tras decodificar `payload` y antes de llamar al
  adapter: `payload["account_id"] = request.headers.get("X-Arkiv-Account", "")`.

- [ ] **Step 4: Correr — debe pasar**

Run: `python -m pytest tests/test_router_search.py tests/test_router_resolve.py tests/test_models.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/models.py src/arkiv_api/router/search.py src/arkiv_api/router/resolve.py tests/test_router_search.py tests/test_router_resolve.py
git commit -m "feat(magis): X-Arkiv-Account -> ctx.account_id (search) y payload (resolve/episodes)"
```

---

## Task 5: Endpoints `/v1/magis/link` por accountId

**Files:**
- Modify: `src/arkiv_api/router/magis.py`
- Test: `tests/test_router_magis.py`

**Interfaces:**
- Consumes: `request.app.state.magis_sessions` (Task 6), header `X-Arkiv-Account`.
- Produces: `POST /v1/magis/link {username,password}`, `DELETE /v1/magis/link`, `GET /v1/magis/link` — todos operan sobre `magis_sessions.for_account(<header>)`. Los globales `/magis/session` y `/magis/credentials` quedan igual.

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_router_magis.py` (mirar cómo montan el app.state.magis_sessions con fakes):
```python
def test_link_guarda_por_accountId_y_status_lo_refleja(client_con_magis):
    client = client_con_magis
    h = {"X-Arkiv-Key": KEY, "X-Arkiv-Account": "acc-9"}
    r = client.post("/v1/magis/link", json={"username": "u", "password": "p"}, headers=h)
    assert r.status_code == 200 and r.json()["mode"] == "cuenta"
    s = client.get("/v1/magis/link", headers=h).json()
    assert s["linked"] is True
    # otra cuenta sigue sin vínculo
    s2 = client.get("/v1/magis/link", headers={"X-Arkiv-Key": KEY, "X-Arkiv-Account": "otra"}).json()
    assert s2["linked"] is False
```

- [ ] **Step 2: Correr — debe fallar** (404 en `/v1/magis/link`).

Run: `python -m pytest tests/test_router_magis.py -k link -q`

- [ ] **Step 3: Implementar**

En `router/magis.py` agregar (reusa `LoginRechazado`, `_sesion` se generaliza a `_sessions`):
```python
def _cuenta(request: Request) -> str:
    return request.headers.get("X-Arkiv-Account", "") or "anon"

def _sessions(request: Request):
    s = getattr(request.app.state, "magis_sessions", None)
    if s is None:
        raise HTTPException(status_code=503, detail="magis no esta configurado")
    return s

@router.post("/magis/link")
async def link(body: CredencialesIn, request: Request) -> dict:
    ses = _sessions(request).for_account(_cuenta(request))
    try:
        modo = await ses.guardar_credenciales(body.username, body.password)
    except LoginRechazado as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    cliente = await ses.client()
    return {"mode": modo, "account": cliente.user_id}

@router.delete("/magis/link")
async def unlink(request: Request) -> dict:
    ses = _sessions(request).for_account(_cuenta(request))
    # v5/loginOut fire-and-forget antes de borrar (best-effort).
    try:
        cliente = await ses.client()
        await asyncio.to_thread(getattr(cliente, "logout", lambda: None))
    except Exception:
        pass
    await ses.borrar_credenciales()
    return {"mode": await ses.modo()}

@router.get("/magis/link")
async def link_status(request: Request) -> dict:
    ses = _sessions(request).for_account(_cuenta(request))
    return {"linked": await ses.tiene_credenciales(), "expires_in": await ses.expires_in()}
```
(Los endpoints globales `/magis/session` y `/magis/credentials` existentes se dejan; si hoy usan `_sesion(request)` que lee `app.state.magis_session`, apuntarlos a `_sessions(request).for_account("anon")`.)

Nota: `IPTVClient.logout()` (v5/loginOut) existe en el vendor (`vendor/iptv_client.py`); si no, portarlo del CLI `/Users/cristian/magia/iptv_client.py` (método `logout`). Verificar antes de este task.

- [ ] **Step 4: Correr — debe pasar**

Run: `python -m pytest tests/test_router_magis.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/router/magis.py tests/test_router_magis.py
git commit -m "feat(magis): endpoints /v1/magis/link (POST/GET/DELETE) por accountId"
```

---

## Task 6: Wiring en `app.py`

**Files:**
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_app_wiring.py`

**Interfaces:**
- Consumes: `MagisSessions` (Task 2), `MagisAdapter(sessions, ...)` (Task 3).
- Produces: `app.state.magis_sessions`. El adapter se registra con el manager.

- [ ] **Step 1: Implementar**

En `build_registry` (donde hoy crea `MagisSession(...)` y registra `MagisAdapter(sesion_magis, ...)`):
```python
    sessions_magis = None
    if settings.credential_namespaces["magis"]:
        sessions_magis = MagisSessions(
            redis,
            TokenBucket(redis, "magis", _MAGIS_INTERVALO_MS),
            _magis_factory(),
            settings.ref_signing_key,
        )
        reg.register(MagisAdapter(sessions_magis, Cache(redis), settings.ref_signing_key))
    return reg, breakers, sessions_magis
```
- Importar `MagisSessions` desde `.adapters.magis.session`.
- En `create_app` (lifespan): `app.state.registry, app.state.breakers, app.state.magis_sessions = build_registry(...)` (renombrar de `magis_session` a `magis_sessions`). El fallback (sin credenciales) deja `app.state.magis_sessions = None`.
- Ajustar `router/magis.py` global (`/magis/session`, `/magis/credentials`) para leer `app.state.magis_sessions.for_account("anon")` (hecho en Task 5).

- [ ] **Step 2: Correr los tests de wiring + suite magis**

Run: `python -m pytest tests/test_app_wiring.py tests/test_router_magis.py tests/test_adapter_magis.py -q`
Expected: PASS.

- [ ] **Step 3: Suite completa del gateway**

Run: `python -m pytest -q`
Expected: PASS (verde toda la suite).

- [ ] **Step 4: Commit**

```bash
git add src/arkiv_api/app.py tests/test_app_wiring.py
git commit -m "feat(magis): wiring de MagisSessions en app.py"
```

---

## Task 7: App — header `X-Arkiv-Account` en `ArkivApiClient`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (y `PlayerViewModel.kt` donde también construye el client)
- Test: `app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt`

**Interfaces:**
- Consumes: accountId efectivo (del `DeviceAuthManager.session.value?.accountId`).
- Produces: `ArkivApiClient(..., magisAccountId: () -> String? = { null })`; el `Request.Builder` agrega `X-Arkiv-Account` cuando no es null/blank, en search/resolve/episodes.

- [ ] **Step 1: Escribir el test que falla**

En `ArkivApiClientTest.kt`:
```kotlin
@Test
fun search_mandaElHeaderXArkivAccountCuandoHayAccountId() = runBlocking {
    val server = MockWebServer(); server.enqueue(MockResponse().setBody("")); server.start()
    val c = ArkivApiClient(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        apiKey = { "k" },
        magisAccountId = { "acc-9" },
    )
    runCatching { c.search(/* args mínimos como en los otros tests */) }
    val req = server.takeRequest()
    assertEquals("acc-9", req.getHeader("X-Arkiv-Account"))
    server.shutdown()
}
```
(Ajustar la llamada a `search` a la firma real usada por los tests existentes del archivo.)

- [ ] **Step 2: Correr — debe fallar** (no existe el parámetro `magisAccountId`).

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*ArkivApiClientTest*" --console=plain`

- [ ] **Step 3: Implementar**

- En `ArkivApiClient`: agregar `private val magisAccountId: () -> String? = { null }` al constructor.
- En el helper `pedido(url)` (el que hoy hace `.header("X-Arkiv-Key", apiKey())`):
```kotlin
    private fun pedido(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("X-Arkiv-Key", apiKey())
        magisAccountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        return b
    }
```
- En `AppGraph.kt` y `PlayerViewModel.kt`, al construir `ArkivApiClient(...)`, pasar
  `magisAccountId = { deviceAuth.session.value?.accountId }`.

- [ ] **Step 4: Correr — debe pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "*ArkivApiClientTest*" --console=plain`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt \
        app/src/main/java/com/arkiv/player/AppGraph.kt \
        app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt \
        app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt
git commit -m "feat(magis): app manda X-Arkiv-Account (accountId efectivo) al gateway"
```

---

## Task 8: App — "Vincular Magis" en Ajustes (móvil + TV)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pocketbase/MagisLinkClient.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt`, `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt`
- Modify: `AppGraph.kt` (proveer `MagisLinkClient`)

**Interfaces:**
- Consumes: baseUrl/apiKey del gateway + accountId efectivo; endpoints `/v1/magis/link`.
- Produces: `MagisLinkClient` con `suspend fun status(): Boolean`, `suspend fun link(user, pass)`, `suspend fun unlink()`. UI que solo aparece si hay sesión Arkiv (`AccountState.Conectado`).

- [ ] **Step 1: `MagisLinkClient`**

`pocketbase/MagisLinkClient.kt` (OkHttp, mismo estilo que `PocketBaseClient`; manda `X-Arkiv-Key` + `X-Arkiv-Account`):
```kotlin
package com.arkiv.player.pocketbase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MagisLinkException(val code: Int, message: String) : Exception(message)

/** Cliente de los endpoints /v1/magis/link del gateway. Manda X-Arkiv-Key + X-Arkiv-Account. */
class MagisLinkClient(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val accountId: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val jsonType = "application/json".toMediaType()

    private fun req(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("X-Arkiv-Key", apiKey())
        accountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        return b
    }

    private fun exec(request: Request): JSONObject =
        client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                throw MagisLinkException(r.code, msg?.ifBlank { raw } ?: raw)
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }

    suspend fun status(): Boolean = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").get().build()).optBoolean("linked", false)
    }

    suspend fun link(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("username" to username, "password" to password))
            .toString().toRequestBody(jsonType)
        exec(req("${baseUrl()}/v1/magis/link").post(body).build())   // 422 -> MagisLinkException
    }

    suspend fun unlink(): Unit = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").delete().build())
    }
}
```

- [ ] **Step 2: UI en `AccountSection` (móvil)**

Dentro del branch `AccountState.Conectado` de `AccountSection`, debajo de "Cerrar sesión", agregar un sub-bloque "Magis": si `status()` es true → "Vinculado" + botón "Desvincular" (`unlink()`); si false → campos email+clave de Magis + botón "Vincular Magis" (`link(...)`), capturando la excepción para mostrar el error inline. Lanzar en `rememberCoroutineScope`. Obtener `MagisLinkClient` de `rememberGraph().magisLinkClient`.

- [ ] **Step 3: UI en `TvSettingsScreen`**

Equivalente TV-nativo (mismo patrón que la sección Cuenta del Spec 1): solo visible con `AccountState.Conectado`.

- [ ] **Step 4: Wiring**

En `AppGraph.kt`: `val magisLinkClient by lazy { MagisLinkClient(baseUrl = { gatewayBase }, apiKey = { gatewayKey }, accountId = { deviceAuth.session.value?.accountId }) }` (usar las mismas fuentes de baseUrl/apiKey que `ArkivApiClient`).

- [ ] **Step 5: Compilar + verificar en device**

Run: `./gradlew :app:assembleDebug`
Expected: compila. Con el gateway desplegado (ver nota), probar: logueado en Arkiv → "Vincular Magis" con creds → aparece "Vinculado"; búsqueda/reproducción usan la cuenta.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/pocketbase/MagisLinkClient.kt \
        app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt \
        app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt \
        app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(magis): UI Vincular Magis en Ajustes (movil + TV)"
```

---

## Despliegue (paso manual aparte)

El gateway NO se despliega con git pull. Tras mergear las Tasks 1–6:
- `rsync` del código a `blog` + `docker compose build && docker compose up -d` del `arkiv-api` (ver memoria [Deploy del gateway en blog]).
- La app (Tasks 7–8): reconstruir/instalar el APK.

## Verificación final

- [ ] Gateway: `cd /Users/cristian/arkiv-api && python -m pytest -q` → verde.
- [ ] App: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest` → verde; `assembleDebug` OK.
- [ ] Manual (con gateway desplegado): dos cuentas Arkiv distintas vinculan Magis distinto; la búsqueda/reproducción de cada una usa su token; un device sin login queda anónimo; el CLI (`GET /v1/magis/session`) sigue andando.

## Cobertura del spec (self-review)

- Sesión por accountId (anon = claves viejas) → Tasks 1, 2.
- Adapter resuelve sesión por account_id + cache scopeada → Task 3.
- Header `X-Arkiv-Account` → ctx/payload → Task 4.
- Endpoints `/magis/link` (v8/login / v5/loginOut) + globales intactos → Tasks 5, 6.
- App header + UI "Vincular Magis" (solo con sesión Arkiv) → Tasks 7, 8.
- Fallback anónimo, cache global de search/eps, retrocompat CLI → constraints respetados en 1/3/5/6.
- Fuera de alcance (validación fuerte de identidad, TV en vivo) → sin tareas, correcto.
