# Login unificado "Magis-first" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Un solo login: la cuenta del usuario ES su cuenta de Magis. Login valida Magis → auto-crea/loguea PocketBase (invisible) + vincula. Registro crea la cuenta en Magis (con código por email) Y en PocketBase a la vez. Campos de clave con 👁 (mostrar/ocultar).

**Architecture:** El gateway porta el flujo de registro Magis del CLI (`sendEmailVerifyCode`/`validateVerifyCode`/`bindEmail`, hash `MD5(pwd+cloudstream)`) y expone endpoints por `accountId`. La app orquesta: login = PocketBase→(si falta)→validar Magis→registrar PocketBase+vincular; registro = pedir código→confirmar (Magis + PocketBase). La cuenta PocketBase (accountId del device) queda como plomería invisible; su clave = la clave de Magis.

**Tech Stack:** Python (arkiv-api, pytest/fakeredis), Kotlin/Compose (archive, MockWebServer).

## Global Constraints
- La clave PocketBase de la persona = su clave de Magis (misma credencial). El `accountId` de la persona = el `accountId` del device al momento (se promueve, como hoy).
- Registro Magis usa un **device FRESCO minteado** (v3/snToken) por registro (el `bindEmail` ata el email a ese device); se persiste temporal entre "pedir código" y "confirmar" en `magis:reg:<accountId>` (TTL ~15 min).
- NO romper: login/anon/link/cache actuales siguen. Los endpoints `/magis/link` y globales quedan.
- Deploy gateway = rsync + `sudo docker compose up -d --build` (NO git pull). App = `assembleDebug` (los devices corren DEBUG) + `.env` de la raíz copiado al worktree.
- Commits `lordmacu`, sin coautoría, `git add` explícito.

## File Structure
**Gateway:** `vendor/iptv_client.py` (+4 métodos), `adapters/magis/session.py` (helpers de registro), `router/magis.py` (2 endpoints), tests.
**App:** `pocketbase/AccountManager.kt` (login unificado + register 2 pasos), `pocketbase/MagisLinkClient.kt` (endpoints register), `ui/settings/AccountSection.kt` + `ui/tv/TvSettingsScreen.kt` (UI unificada + código + 👁), `AppGraph.kt`, tests.

---

## Task G1: Métodos de registro Magis en el vendored client
**Files:** `src/arkiv_api/adapters/magis/vendor/iptv_client.py`, `tests/test_magis_sntoken.py`
- [ ] **Step 1: test que falla** — monkeypatch `call`, asserts para cada método.
```python
def test_registro_magis_endpoints_y_hash(monkeypatch):
    monkeypatch.setenv("IPTV_HOSTS","h"); monkeypatch.setenv("IPTV_APP_ID","a"); monkeypatch.setenv("IPTV_3DES_KEY","a"*48)
    monkeypatch.setattr(IPTVClient,"HOSTS",["h"])
    c = IPTVClient(user_id="uid", user_token="tok", auto_activate=False)
    seen = {}
    monkeypatch.setattr(c,"call", lambda p,b=None,base_fields=True: (seen.update({p:b}) or {"returnCode":"0"}))
    c.send_email_verify_code("e@x.co"); assert seen["v2/sendEmailVerifyCode"]["email"]=="e@x.co" and seen["v2/sendEmailVerifyCode"]["type"]=="1"
    c.validate_verify_code("e@x.co","123456"); assert seen["v2/validateVerifyCode"]["verifyCode"]=="123456"
    c.bind_email("e@x.co","Clave1"); import hashlib; assert seen["v2/bindEmail"]["pwd"]==hashlib.md5(("Clave1"+"cloudstream").encode()).hexdigest()
```
- [ ] **Step 2:** `.venv/bin/python -m pytest tests/test_magis_sntoken.py -q` → FAIL.
- [ ] **Step 3: implementar** (portar del CLI `/Users/cristian/magia/iptv_client.py`):
```python
    @staticmethod
    def _hash_pwd(password):
        import hashlib
        return hashlib.md5(((password or "") + "cloudstream").encode("utf-8")).hexdigest()

    def send_email_verify_code(self, email, type_="1"):
        return self.call("v2/sendEmailVerifyCode",
                         {"email": email, "type": type_, "userId": self.user_id, "userToken": self.user_token},
                         base_fields=False)

    def validate_verify_code(self, email, code, type_="1"):
        return self.call("v2/validateVerifyCode",
                         {"type": type_, "email": email, "verifyCode": code,
                          "userToken": self.user_token, "userId": self.user_id}, base_fields=False)

    def bind_email(self, email, password, type_="1"):
        return self.call("v2/bindEmail",
                         {"email": email, "pwd": self._hash_pwd(password), "type": type_,
                          "userId": self.user_id, "userToken": self.user_token}, base_fields=False)
```
- [ ] **Step 4:** tests PASS + suite completa verde.
- [ ] **Step 5:** commit `feat(magis): registro (sendEmailVerifyCode/validate/bindEmail) en el vendored client`.

---

## Task G2: Helpers de registro en MagisSession/MagisSessions
**Files:** `src/arkiv_api/adapters/magis/session.py`, `tests/test_magis_session.py`
**Interfaces:** en la sesión por-accountId: `async def registro_enviar_codigo(email)` y `async def registro_confirmar(email, password, code)`.
- `registro_enviar_codigo(email)`: mintea un device fresco (factory + `new_anonymous_device` en hilo), guarda `{user_id,user_token,sn}` en `magis:reg:<identity>` (TTL 900), y con ese device llama `send_email_verify_code(email)`. Devuelve el dict del portal (o `_error`).
- `registro_confirmar(email, password, code)`: carga `magis:reg:<identity>` (si no hay → error "expiró"), reconstruye el cliente con ese user_id/user_token/sn, llama `validate_verify_code` → si error, propaga; `bind_email(email,password)`; `login(email,password)` (hash cloudstream ya arreglado); si `userToken` → persiste creds+sesión como `guardar_credenciales` (reusa `_r.set(_KEY_CRED,...)` + `_persistir`), borra `magis:reg:<identity>`. Devuelve `{"user_id":...}` o lanza `LoginRechazado`.
- [ ] Tests con `fakeredis` + un `ClienteRegistro` fake que cuente las llamadas y simule código bueno/malo. Casos: enviar guarda `magis:reg`; confirmar con código malo → error y NO persiste creds; confirmar OK → persiste creds y borra `magis:reg`.
- [ ] TDD (FAIL→impl→PASS+suite), commit `feat(magis): registro por accountId (codigo email + bind + login) en la sesion`.

---

## Task G3: Endpoints de registro + redeploy
**Files:** `src/arkiv_api/router/magis.py`, `tests/test_router_magis.py`
- [ ] `POST /v1/magis/register/send-code {email}` + `X-Arkiv-Account` → `_sessions(request).for_account(_cuenta).registro_enviar_codigo(email)`; error del portal → 422.
- [ ] `POST /v1/magis/register/confirm {email,password,code}` + header → `.registro_confirmar(...)`; `LoginRechazado`/código malo → 422; OK → `{"status":"ok","account":<magis user_id>}`.
- [ ] Tests del router (con fakes), TDD, suite verde. Commit `feat(magis): endpoints /v1/magis/register/{send-code,confirm}`.
- [ ] **Redeploy:** rsync + `sudo docker compose up -d --build` en blog; verificar `GET /v1/health` 200.

---

## Task A1: AccountManager unificado + MagisLinkClient register
**Files:** `pocketbase/AccountManager.kt`, `pocketbase/MagisLinkClient.kt`, tests
- [ ] `MagisLinkClient`: agregar `suspend fun registerSendCode(email)` (POST /v1/magis/register/send-code) y `suspend fun registerConfirm(email,password,code)` (POST /v1/magis/register/confirm) — mismo patrón que `link` (headers X-Arkiv-Key + X-Arkiv-Account; 422 → MagisLinkException con detail). Test MockWebServer.
- [ ] `AccountManager`:
  - `login(email,password)` (unificado): `ensureBootstrapped()`; intentar `authWithPasswordRecord(users,...)` → si OK: `switchAccount(record.accountId)` + `onAccountSwitched()` (como hoy) → Conectado. Si `PocketBaseException(400..403)`: intentar `magisLink.link(email,password)` (valida Magis contra el accountId del device); si OK → `createRecord(users,{email,password,passwordConfirm,accountId=device.accountId}, deviceToken)` (registra PocketBase con la MISMA credencial) → savePersonEmail → Conectado; si falla → `AccountException("email o contraseña inválidos")`.
  - `registerSendCode(email)` → `magisLink.registerSendCode(email)` (mapea 422→AccountException).
  - `registerConfirm(email,password,code)` → `magisLink.registerConfirm(email,password,code)`; si OK → `createRecord(users,{...accountId=device.accountId}, deviceToken)` → savePersonEmail → Conectado.
  - `magisLink` se inyecta en el constructor de AccountManager (desde AppGraph).
- [ ] Tests (MockWebServer + FakeDeviceStore): login-existente PocketBase OK; login-nuevo (PB 400 → link OK → PB register) queda Conectado; registerConfirm OK queda Conectado. TDD, commit.

## Task A2: UI unificada (móvil + TV) + 👁 + código + spam
**Files:** `ui/settings/AccountSection.kt`, `ui/tv/TvSettingsScreen.kt`, `AppGraph.kt`
- [ ] `AccountSection` (Anonimo): un form email + clave con **`IconButton` de ojo** que togglea `PasswordVisualTransformation`/`VisualTransformation.None`. Botón **"Entrar"** → `account.login(email,clave)`. Botón **"Crear cuenta"** → `account.registerSendCode(email)` → muestra un **campo "Código"** + texto *"Te enviamos un código a tu email. Si no aparece, revisá la carpeta de spam."* + botón **"Confirmar"** → `account.registerConfirm(email,clave,codigo)`. Errores inline (AccountException). `Conectado` → "Conectado como X" + "Cerrar sesión".
- [ ] `TvSettingsScreen`: equivalente TV (mismo flujo; el ojo con un toggle de estado; inputs estilo TvAddScreen).
- [ ] `AppGraph`: pasar `magisLinkClient` al `AccountManager`.
- [ ] Compilar (`assembleDebug`), commit.

## Task A3: Build + install
- [ ] `assembleDebug` en el worktree (con `.env` copiado), `adb -s R5CX7251VRM install -r` (celu) y `adb -s 192.168.1.22:5555 install -r` (Fire TV). Smoke launch.

## Verificación final
- [ ] Gateway `pytest -q` verde + health 200. App suite verde + assembleDebug. Manual: login con Magis existente entra de una; crear cuenta pide código (mail/spam) y crea en ambos.
