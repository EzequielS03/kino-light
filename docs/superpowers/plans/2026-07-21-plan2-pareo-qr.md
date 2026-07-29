# Plan 2 — Emparejamiento por QR (celu ↔ TV)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emparejar el celu y el TV por QR: el TV muestra un QR, el celu lo escanea, y el celu (logueado) provisiona al TV metiéndolo en SU cuenta PocketBase (mismo `accountId`), pasándole credenciales cifradas por la propia DB.

**Architecture:** Rendezvous cross-account vía la colección `pair_requests`. El `code` del QR nunca se guarda en el servidor (solo su hash con separación de dominio); las credenciales del TV viajan cifradas con AES-GCM usando una clave derivada del `code`. El TV escucha su `pair_request` por realtime (SSE, ya construido en Plan 1), descifra y cambia su identidad a la cuenta del celu. Reutiliza `PocketBaseClient`, `PocketBaseRealtime`, `SecureDeviceStore`, `DeviceIdentityFactory` de Plan 1.

**Tech Stack:** Kotlin, `com.google.zxing:core` (generar QR en el TV), CameraX + `com.google.mlkit:barcode-scanning` (escanear en el celu), Coroutines, javax.crypto (AES-GCM/SHA-256), Compose / Compose-for-TV.

## Global Constraints

- Paquete base: `com.arkiv.player`. Paquete nuevo de esta fase: `com.arkiv.player.pairing`.
- Commits con identidad **lordmacu** (`user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`). **Sin** coautoría de Claude.
- Tests unitarios: **JUnit4 puro** (`junit:junit:4.13.2`). Las clases con lógica testeable (`PairCode`, `QrPayload`, `PairCrypto`) NO deben depender de Android ni de `org.json`.
- El repo trackea artefactos de build y hay **WIP del usuario sin commitear** por toda la app. NUNCA `git add -A`/`git add .`. Cada task stagea SOLO sus archivos. Nunca `app/build/`, `.gradle/`, ni archivos WIP ajenos. El módulo compila limpio (verificado en Plan 1) — si un agente cree ver un error de compilación en un archivo ajeno, es su propio test RED mal atribuido: confiar en `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- Instancia PocketBase: `https://db.comparadorinternet.co`. Colecciones: `devices` (Plan 1) y `pair_requests` (esta fase, ya creada).
- Separación de dominio de la cripto (exacta): `codeHash = SHA-256("arkiv-pair-lookup:" + code)` (hex); `encKey = SHA-256("arkiv-pair-key:" + code)` (32 bytes). DISTINTOS a propósito.
- Formato del QR (exacto): `arkiv://pair?u=<urlEncoded dbUrl>&c=<code>`.

---

## Task 0 — Servidor: colección `pair_requests` ✅ HECHO (2026-07-21)

Creada vía la API de PocketBase (superadmin por SSH a `blog`, `127.0.0.1:8095`).

- [x] Colección `pair_requests` (base) con campos: `codeHash` (text, required, índice `idx_pair_requests_codeHash`), `status` (select: pending/claimed, required), `payload` (text, cifrado, max 8000), `tvName` (text), `expiresAt` (date).
- [x] Reglas: list/view/create/update/delete = `@request.auth.id != ""` (permisivas a nivel auth porque el pareo es cross-account; la seguridad real está en la cripto: `codeHash` no revela el `code`, `payload` va cifrado+autenticado con clave derivada del `code`).
- [ ] **Documentar** en `docs/pocketbase/collections.md` (añadir la sección `pair_requests` con el esquema, reglas y el modelo de amenaza: reglas permisivas + confidencialidad/integridad por cripto; riesgo residual = DoS de pareos pendientes, aceptable para uso personal). Commit: `docs(pocketbase): esquema y reglas de pair_requests`.

---

## Task 1: Dependencias + config de pairing

**Files:**
- Modify: `app/build.gradle.kts` (dependencies)
- Create: `app/src/main/java/com/arkiv/player/pairing/PairingConfig.kt`

**Interfaces:**
- Produces: `object PairingConfig { const val COLLECTION = "pair_requests"; const val QR_SCHEME = "arkiv"; const val QR_HOST = "pair"; const val TTL_MS = 300_000L }`

- [ ] **Step 1: Añadir dependencias** en `app/build.gradle.kts` dentro de `dependencies { }`:

```kotlin
    // QR: generar (TV) y escanear (celu)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
```

- [ ] **Step 2: Crear config**

```kotlin
package com.arkiv.player.pairing

/** Constantes del emparejamiento por QR. */
object PairingConfig {
    const val COLLECTION = "pair_requests"
    const val QR_SCHEME = "arkiv"
    const val QR_HOST = "pair"
    const val TTL_MS = 300_000L // 5 min de validez del QR
}
```

- [ ] **Step 3: Compilar** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (solo `app/build.gradle.kts` + `PairingConfig.kt`; staging quirúrgico para no barrer el WIP de build.gradle.kts): `feat(pairing): dependencias (zxing, camerax, mlkit) y config`.

---

## Task 2: `PairCode` + `QrPayload` (puro, testeable)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pairing/PairCode.kt`
- Create: `app/src/main/java/com/arkiv/player/pairing/QrPayload.kt`
- Test: `app/src/test/java/com/arkiv/player/pairing/PairCodeTest.kt`
- Test: `app/src/test/java/com/arkiv/player/pairing/QrPayloadTest.kt`

**Interfaces:**
- Produces:
  - `object PairCode { fun generate(random: java.util.Random = java.security.SecureRandom()): String }` — 26 chars base32 (~130 bits).
  - `data class QrPayload(val dbUrl: String, val code: String)`
  - `object QrPayloadCodec { fun encode(p: QrPayload): String; fun decode(s: String): QrPayload? }` — formato `arkiv://pair?u=<enc>&c=<code>`.

- [ ] **Step 1: Test que falla (PairCode)**

```kotlin
package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairCodeTest {
    @Test fun generatesHighEntropyDistinctCodes() {
        val a = PairCode.generate()
        val b = PairCode.generate()
        assertNotEquals(a, b)
        assertEquals(26, a.length)
        assertTrue("solo base32 A-Z2-7", a.all { it in 'A'..'Z' || it in '2'..'7' })
    }
}
```

- [ ] **Step 2: Test que falla (QrPayload)**

```kotlin
package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test fun roundTrip() {
        val p = QrPayload(dbUrl = "https://db.comparadorinternet.co", code = "ABC234XYZ")
        val s = QrPayloadCodec.encode(p)
        assertEquals("arkiv://pair", s.substringBefore('?'))
        val back = QrPayloadCodec.decode(s)
        assertEquals(p, back)
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(QrPayloadCodec.decode("https://example.com"))
        assertNull(QrPayloadCodec.decode("arkiv://pair?u=x")) // falta code
    }
}
```

- [ ] **Step 3: Correr — deben fallar.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.pairing.PairCodeTest" --tests "com.arkiv.player.pairing.QrPayloadTest"` → FAIL.

- [ ] **Step 4: Implementar PairCode.kt**

```kotlin
package com.arkiv.player.pairing

import java.util.Random

/** Genera códigos de pareo de alta entropía (base32, ~130 bits). */
object PairCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" // base32 RFC4648 sin 0/1/8/9
    private const val LEN = 26

    fun generate(random: Random = java.security.SecureRandom()): String = buildString {
        repeat(LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
```

- [ ] **Step 5: Implementar QrPayload.kt**

```kotlin
package com.arkiv.player.pairing

import java.net.URLDecoder
import java.net.URLEncoder

/** Contenido del QR de pareo. */
data class QrPayload(val dbUrl: String, val code: String)

/** Codifica/decodifica el QR como `arkiv://pair?u=<urlEncoded>&c=<code>`. */
object QrPayloadCodec {
    fun encode(p: QrPayload): String {
        val u = URLEncoder.encode(p.dbUrl, "UTF-8")
        val c = URLEncoder.encode(p.code, "UTF-8")
        return "${PairingConfig.QR_SCHEME}://${PairingConfig.QR_HOST}?u=$u&c=$c"
    }

    fun decode(s: String): QrPayload? {
        val prefix = "${PairingConfig.QR_SCHEME}://${PairingConfig.QR_HOST}?"
        if (!s.startsWith(prefix)) return null
        val query = s.substring(prefix.length)
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val u = params["u"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        val c = params["c"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        if (u.isBlank() || c.isBlank()) return null
        return QrPayload(dbUrl = u, code = c)
    }
}
```

- [ ] **Step 6: Correr — pasan.** Mismo comando del Step 3 → PASS (3 tests).
- [ ] **Step 7: Commit** (4 archivos): `feat(pairing): código de pareo y payload del QR (testeable)`.

---

## Task 3: `PairCrypto` (puro, testeable)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pairing/PairCrypto.kt`
- Test: `app/src/test/java/com/arkiv/player/pairing/PairCryptoTest.kt`

**Interfaces:**
- Produces:
  - `object PairCrypto`
  - `fun PairCrypto.codeHash(code: String): String` — hex de SHA-256("arkiv-pair-lookup:"+code).
  - `fun PairCrypto.encrypt(plaintext: String, code: String): String` — base64(iv12 || AES-GCM ct).
  - `fun PairCrypto.decrypt(b64: String, code: String): String` — lanza si el tag/clave no valida.

- [ ] **Step 1: Test que falla**

```kotlin
package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairCryptoTest {
    @Test fun encryptDecryptRoundTrip() {
        val code = "ABCDEFGHIJKLMNOPQRSTUVWX23"
        val secret = """{"email":"x@arkiv.local","password":"p"}"""
        val ct = PairCrypto.encrypt(secret, code)
        assertNotEquals(secret, ct)
        assertEquals(secret, PairCrypto.decrypt(ct, code))
    }

    @Test fun wrongCodeFailsToDecrypt() {
        val ct = PairCrypto.encrypt("secreto", "CODEAAAAAAAAAAAAAAAAAAAAA2")
        assertThrows(Exception::class.java) { PairCrypto.decrypt(ct, "CODEBBBBBBBBBBBBBBBBBBBBB3") }
    }

    @Test fun codeHashDiffersFromKeyAndIsStable() {
        val code = "ZZZZZZZZZZZZZZZZZZZZZZZZ22"
        val h1 = PairCrypto.codeHash(code)
        val h2 = PairCrypto.codeHash(code)
        assertEquals(h1, h2)          // estable
        assertEquals(64, h1.length)   // hex de 32 bytes
    }
}
```

- [ ] **Step 2: Correr — falla.** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.pairing.PairCryptoTest"` → FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.pairing

import android.util.Base64 as AndroidBase64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cripto del pareo. El `code` (alta entropía, solo en el QR) es el secreto compartido.
 * Separación de dominio: el hash de lookup y la clave de cifrado derivan del code con
 * prefijos DISTINTOS, así el codeHash guardado no revela la clave.
 *
 * NOTA testabilidad: usa java.util.Base64 (JVM) para poder testear sin Android. En Android
 * java.util.Base64 existe desde API 26; el minSdk del proyecto lo soporta.
 */
object PairCrypto {
    private const val LOOKUP_PREFIX = "arkiv-pair-lookup:"
    private const val KEY_PREFIX = "arkiv-pair-key:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun codeHash(code: String): String = sha256(LOOKUP_PREFIX + code).toHex()

    private fun encKey(code: String): ByteArray = sha256(KEY_PREFIX + code) // 32 bytes

    fun encrypt(plaintext: String, code: String): String {
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(code), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64encode(iv + ct)
    }

    fun decrypt(b64: String, code: String): String {
        val all = b64decode(b64)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(code), "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // Base64 sin depender de Android para los unit tests.
    private fun b64encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
```

> **Nota implementador:** elimina el `import android.util.Base64 as AndroidBase64` (no se usa; está solo para recordar la alternativa). Usa `java.util.Base64` como en el código. Verifica que `minSdk >= 26` en `app/build.gradle.kts`; si fuera menor, sustituye por `android.util.Base64` y mueve el test a androidTest — pero el proyecto ya usa API alta, así que java.util.Base64 va bien.

- [ ] **Step 4: Correr — pasan.** Mismo comando → PASS (3 tests).
- [ ] **Step 5: Commit** (2 archivos): `feat(pairing): cripto del pareo (SHA-256 lookup + AES-GCM, testeable)`.

---

## Task 4: `PairingManager` (orquestación TV + celu)

Orquesta ambos roles. Sin unit test (integración con red/realtime); verificación por compilación y e2e posterior.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pairing/PairingManager.kt`

**Interfaces:**
- Consumes: `PocketBaseClient`, `PocketBaseRealtime`, `SecureDeviceStore`, `DeviceAuthManager`, `DeviceIdentityFactory`, `PairCode`, `QrPayloadCodec`, `PairCrypto`, `PairingConfig`, `PocketBaseConfig`.
- Produces:
  - `sealed interface PairingState { object Idle; object WaitingScan; object Claiming; data class Paired(val accountId: String); data class Error(val msg: String) }`
  - `class PairingManager(client, realtime, store, deviceAuth, scope: CoroutineScope)`
  - `val state: StateFlow<PairingState>`
  - `suspend fun startTvPairing(): String` — (rol TV) crea el pair_request, devuelve el string del QR, y arranca la escucha realtime; al reclamarse descifra, guarda la nueva identidad y re-autentica.
  - `suspend fun claimFromQr(qr: String): Boolean` — (rol celu) parsea el QR, crea el device TV en MI cuenta, escribe el payload cifrado + status=claimed. Devuelve éxito.

- [ ] **Step 1: Implementar**

```kotlin
package com.arkiv.player.pairing

import android.util.Log
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentityFactory
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.pocketbase.SecureDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface PairingState {
    data object Idle : PairingState
    data object WaitingScan : PairingState
    data object Claiming : PairingState
    data class Paired(val accountId: String) : PairingState
    data class Error(val msg: String) : PairingState
}

/**
 * Empareja celu↔TV por QR. El TV crea un pair_request y escucha por realtime; el celu
 * escanea, crea el device TV en su cuenta y escribe las credenciales cifradas. El TV
 * descifra, adopta esa identidad (misma cuenta que el celu) y re-autentica.
 */
class PairingManager(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime,
    private val store: SecureDeviceStore,
    private val deviceAuth: DeviceAuthManager,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val col = PairingConfig.COLLECTION

    // ---------- Rol TV ----------

    /** Crea el pair_request, arranca la escucha realtime y devuelve el string para el QR. */
    suspend fun startTvPairing(deviceName: String): String {
        val session = deviceAuth.ensureBootstrapped()
            ?: throw IllegalStateException("sin sesión PocketBase")
        val code = PairCode.generate()
        val codeHash = PairCrypto.codeHash(code)
        val recordId = client.createRecord(
            collection = col,
            fields = mapOf("codeHash" to codeHash, "status" to "pending", "tvName" to deviceName),
            token = session.token,
        )
        _state.value = PairingState.WaitingScan
        // Escuchar el propio pair_request; al claimed, descifrar y adoptar identidad.
        scope.launch {
            realtime.subscribe(listOf("$col/$recordId")).collect { ev ->
                if (ev.record.optString("status") == "claimed") {
                    val payload = ev.record.optString("payload")
                    if (payload.isNotBlank()) {
                        runCatching { adoptIdentity(payload, code, recordId, session.token) }
                            .onFailure { _state.value = PairingState.Error(it.message ?: "fallo al parear") }
                    }
                }
            }
        }
        return QrPayloadCodec.encode(QrPayload(PocketBaseConfig.BASE_URL, code))
    }

    private suspend fun adoptIdentity(payloadCipher: String, code: String, recordId: String, tvToken: String) {
        _state.value = PairingState.Claiming
        val json = JSONObject(PairCrypto.decrypt(payloadCipher, code))
        val id = com.arkiv.player.pocketbase.DeviceIdentity(
            accountId = json.getString("accountId"),
            deviceId = json.getString("deviceId"),
            email = json.getString("email"),
            password = json.getString("password"),
            kind = "tv",
        )
        // Adoptar la identidad de la cuenta del celu y re-autenticar.
        store.clear()
        store.save(id)
        val auth = client.authWithPassword(PocketBaseConfig.COLLECTION_DEVICES, id.email, id.password)
        store.saveToken(auth.token)
        // Limpiar el pair_request (un solo uso).
        runCatching { client.deleteRecord(col, recordId, auth.token) }
        _state.value = PairingState.Paired(id.accountId)
        Log.i("ArkivPair", "TV pareado a cuenta ${id.accountId}")
    }

    // ---------- Rol celu ----------

    /** Escanea el QR, crea el device TV en MI cuenta y escribe las credenciales cifradas. */
    suspend fun claimFromQr(qr: String): Boolean {
        val payload = QrPayloadCodec.decode(qr) ?: run {
            _state.value = PairingState.Error("QR inválido"); return false
        }
        val session = deviceAuth.ensureBootstrapped() ?: run {
            _state.value = PairingState.Error("sin sesión"); return false
        }
        _state.value = PairingState.Claiming
        return runCatching {
            // 1) Crear el device TV en MI cuenta.
            val tvId = DeviceIdentityFactory.newTvDevice(session.accountId)
            client.createRecord(
                collection = PocketBaseConfig.COLLECTION_DEVICES,
                fields = mapOf(
                    "accountId" to tvId.accountId,
                    "kind" to "tv",
                    "email" to tvId.email,
                    "password" to tvId.password,
                    "passwordConfirm" to tvId.password,
                    "deviceName" to "Arkiv TV",
                ),
                token = session.token,
            )
            // 2) Cifrar credenciales con el code y escribirlas en el pair_request.
            val secret = JSONObject(
                mapOf(
                    "accountId" to tvId.accountId,
                    "deviceId" to tvId.deviceId,
                    "email" to tvId.email,
                    "password" to tvId.password,
                ),
            ).toString()
            val cipher = PairCrypto.encrypt(secret, payload.code)
            val codeHash = PairCrypto.codeHash(payload.code)
            val reqs = client.listRecords(col, "codeHash='$codeHash' && status='pending'", session.token)
            val reqId = reqs.firstOrNull()?.getString("id")
                ?: throw IllegalStateException("pair_request no encontrado o expirado")
            client.updateRecord(col, reqId, mapOf("payload" to cipher, "status" to "claimed"), session.token)
            _state.value = PairingState.Paired(session.accountId)
            true
        }.getOrElse {
            _state.value = PairingState.Error(it.message ?: "fallo al parear")
            false
        }
    }
}
```

> **Nota implementador:** `DeviceIdentity` es `data class` pública en `com.arkiv.player.pocketbase`. Verifica el import. Si `DeviceIdentity` no fuese accesible, impórtala explícitamente. Compila con `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 2: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** (1 archivo): `feat(pairing): PairingManager (roles TV y celu)`.

---

## Task 5: Generación del QR (bitmap)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/pairing/QrBitmap.kt`

**Interfaces:**
- Produces: `fun qrImageBitmap(content: String, sizePx: Int = 512): androidx.compose.ui.graphics.ImageBitmap`

- [ ] **Step 1: Implementar**

```kotlin
package com.arkiv.player.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renderiza un QR con el contenido dado a un ImageBitmap para Compose. */
fun qrImageBitmap(content: String, sizePx: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp.asImageBitmap()
}
```

- [ ] **Step 2: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit**: `feat(pairing): generación de QR a ImageBitmap (zxing)`.

---

## Task 6: Pantalla de QR en el TV (Compose for TV)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvPairingScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt` (añadir entrada "Conectar teléfono")

**Interfaces:**
- Consumes: `PairingManager`, `qrImageBitmap`, `PairingState`.
- Produces: `@Composable fun TvPairingScreen(pairing: PairingManager, deviceName: String, onDone: () -> Unit)`

- [ ] **Step 1: Implementar TvPairingScreen.kt**

```kotlin
package com.arkiv.player.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.pairing.PairingState
import com.arkiv.player.pairing.qrImageBitmap

@Composable
fun TvPairingScreen(pairing: PairingManager, deviceName: String, onDone: () -> Unit) {
    val state by pairing.state.collectAsState()
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        runCatching { qrImageBitmap(pairing.startTvPairing(deviceName)) }
            .onSuccess { qr = it }
    }
    LaunchedEffect(state) { if (state is PairingState.Paired) onDone() }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Escanea este código con la app Arkiv de tu teléfono")
        Spacer(Modifier.height(24.dp))
        qr?.let { Image(bitmap = it, contentDescription = "QR de pareo", modifier = Modifier.size(320.dp)) }
        Spacer(Modifier.height(24.dp))
        Text(
            when (val s = state) {
                is PairingState.WaitingScan -> "Esperando escaneo…"
                is PairingState.Claiming -> "Pareando…"
                is PairingState.Paired -> "✅ Pareado"
                is PairingState.Error -> "❌ ${s.msg} — vuelve a intentar"
                else -> "Generando código…"
            },
        )
    }
}
```

- [ ] **Step 2: Añadir la entrada en TvSettingsScreen** — localizar el patrón de items existente (`grep -n "fun TvSettingsScreen" TvSettingsScreen.kt`) y añadir un item "Conectar teléfono" que navegue a `TvPairingScreen`. Seguir el estilo de navegación ya usado en `ArkivTvRoot.kt` (añadir una ruta/estado). Si la navegación del TV usa un enum/sealed de pantallas, añadir el caso `Pairing`.

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (2 archivos): `feat(tv): pantalla de QR para conectar el teléfono`.

---

## Task 7: Escáner de QR en el celu (CameraX + ML Kit)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/pairing/QrScannerScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (permiso de cámara)

**Interfaces:**
- Consumes: CameraX, ML Kit barcode, `PairingManager`.
- Produces: `@Composable fun QrScannerScreen(pairing: PairingManager, onResult: (Boolean) -> Unit)`

- [ ] **Step 1: Permiso de cámara** en `AndroidManifest.xml` (dentro de `<manifest>`, junto a los otros `uses-permission`):

```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

- [ ] **Step 2: Implementar QrScannerScreen.kt**

```kotlin
package com.arkiv.player.ui.pairing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arkiv.player.pairing.PairingManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(pairing: PairingManager, onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { permLauncher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Concede permiso de cámara para escanear el QR del TV")
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            val scanner = BarcodeScanning.getClient()
            val executor = Executors.newSingleThreadExecutor()
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media != null && !handled) {
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                val qr = codes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }
                                    ?.rawValue
                                if (qr != null && !handled) {
                                    handled = true
                                    scope.launch { onResult(pairing.claimFromQr(qr)) }
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else proxy.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
```

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (2 archivos): `feat(celu): escáner de QR para parear con el TV`.

---

## Task 8: Cableado + entrada UI + verificación e2e

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (exponer `PairingManager`) — **staging quirúrgico**, hay WIP del usuario.
- Modify: pantalla principal del celu (botón "Conexión" que abra el escáner) y navegación del TV (ya en Task 6).

**Interfaces:**
- Consumes: todo lo anterior.

- [ ] **Step 1: Exponer PairingManager en AppGraph** (añadir junto a `deviceAuth`):

```kotlin
    val pbRealtime: PocketBaseRealtime by lazy {
        PocketBaseRealtime(token = { deviceAuth.session.value?.token })
    }
    val pairing: PairingManager by lazy {
        PairingManager(pbClient, pbRealtime, deviceStore, deviceAuth, applicationScope)
    }
```
(imports: `com.arkiv.player.pocketbase.PocketBaseRealtime`, `com.arkiv.player.pairing.PairingManager`.)

- [ ] **Step 2: Botón "Conexión" en el celu** — localizar la pantalla home/top bar (`grep -rn "TopAppBar\|IconButton" app/src/main/java/com/arkiv/player/ui/home`), añadir un botón que navegue a `QrScannerScreen(appGraph.pairing) { ok -> ... }`. Seguir el patrón de navegación existente.

- [ ] **Step 3: Compilar** → BUILD SUCCESSFUL. Commit (staging quirúrgico de AppGraph.kt + los archivos de UI): `feat(pairing): cablear PairingManager y entradas de UI`.

- [ ] **Step 4: Verificación e2e (controlador, hardware real)**
  1. Instalar en el Fire Stick y en el celu (`adb -s <fire> install -r ...`, `adb -s <phone> install -r ...`).
  2. En el Fire Stick: abrir Arkiv → Ajustes → "Conectar teléfono" → aparece el QR.
  3. En el celu: abrir Arkiv → botón "Conexión" → escanear el QR del TV.
  4. Verificar en PocketBase (API por SSH): el Fire Stick ahora tiene un device con `kind=tv` y el **MISMO `accountId`** que el celu; el `pair_request` fue borrado (un solo uso).
  5. Verificar re-apertura: el TV reconecta con su nueva identidad (misma cuenta) sin re-escanear.

---

## Self-Review (cobertura vs spec §5)

- **Colección `pair_requests` + reglas + modelo de amenaza (spec §4, §5):** Task 0 ✔.
- **code de alta entropía, un solo uso, TTL (spec §5):** Task 2 (code), Task 4 (status pending→claimed, delete tras consumir); TTL vía `expiresAt`/filtro `status='pending'` ✔ (la limpieza por expiración se refuerza en Plan 5).
- **Payload cifrado con clave derivada del code + separación de dominio (spec §5):** Task 3 ✔.
- **El celu provisiona al TV; canal de vuelta por realtime (spec §2, §5):** Task 4 (claimFromQr crea el device TV; startTvPairing escucha SSE y adopta) ✔.
- **QR mostrado en TV / escaneado en celu (spec §5, §8):** Tasks 5, 6, 7 ✔.
- **Persistencia tras parear (EncryptedSharedPreferences), reconexión sin re-escanear (spec §5):** Task 4 (store.save) + Plan 1 bootstrap reusa identidad ✔.
- **Re-parear/revocar (spec §5):** el re-parear = volver a abrir la pantalla del TV (genera code nuevo). La revocación explícita del device TV viejo y el "desvincular" desde el celu quedan para Plan 5 (UI de gestión) — anotado como no-cubierto aquí.

**Notas:** `PairCode`, `QrPayload`, `PairCrypto` con unit tests JUnit4 puros. `PairingManager`, QR bitmap, y las pantallas se verifican por compilación + e2e en hardware (Fire Stick + celu). El re-uso del `SecureDeviceStore` para adoptar la identidad del celu abandona la cuenta standalone que el TV creó en Plan 1 (esperado).
