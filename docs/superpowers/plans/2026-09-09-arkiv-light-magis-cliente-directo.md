# Arkiv Light — Sub-proyecto 2A (Cliente Magis + TMDB directos) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar el gateway `arkiv-api` para todo lo de Magis (VOD + canal en vivo) y TMDB por clientes directos en Kotlin, portando el protocolo ya validado en producción en `/Users/cristian/arkiv-api`.

**Architecture:** Un paquete nuevo `com.arkiv.player.data.magis/` con capas independientes (crypto → transporte HTTP → sesión → catálogo/resolución), cada una testeable sin red real gracias a los vectores de aceptación ya capturados contra el portal en producción. Los componentes existentes que consumen esto (`MagisExoPlayer`, `LiveExoPlayer`/`LiveHlsProxy`, las pantallas de búsqueda/detalle) no cambian — solo cambia quién les da la URL/headers finales.

**Tech Stack:** Kotlin, `javax.crypto` (3DES), OkHttp, `EncryptedSharedPreferences` (Jetpack Security), JUnit + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-09-arkiv-light-magis-cliente-directo-design.md`

## Estado (actualizado al ejecutar)

Tasks 1-7 **hechas** (commits `d80ca3b1`, `621faa3e`, `9d573537`, `85185f27`, `3e394d12`,
`359f6578`, `c11f7c5b`, `f70d6769`, `baecdae6`). Suite completa en verde (1636 tests) y cada pieza
con mutaciones verificadas. Lo que el plan decía y la ejecución tuvo que corregir:

- **Task 1 era redundante**: la app YA firmaba segmentos de vivo en el aparato
  (`playback/TweakedMd5.kt`, usado por `FirmaLocal`), con el mismo salt, el mismo mensaje y los
  mismos 5 vectores. Quedó una sola implementación, la que tenía el test más fuerte, mudada a
  `data/magis/`. `Sign2` no existe: su `signO3` ya estaba en `TweakedMd5`.
- **Task 2**: `MagisResult.Ok` genérico no se puede castear sin argumento de tipo; se agregaron
  `dato()`, `map()` y `comoError()`. `MagisPortalClient` recibe además un `snProvider` (el `sn` es
  estado mutable del device, igual que en el puerto de Python).
- **Task 3**: `conSesionValida` reautentica ante CUALQUIER `PortalError`, no solo ante
  `aaa100027/28` (el portal mata la sesión con códigos no documentados; lo aprendió el gateway a la
  fuerza, ver `adapter.py:_llamar`). Se agregó el re-acuñado de device ante `aaa100080/aaa100082`,
  que el plan no mencionaba y sin el cual toda la rama anónima muere en silencio. Se agregó
  `ensureSession()` (con cuenta vinculada no se cae a anónimo: el vivo lo rechaza).
- **Task 4**: sin `v3/getColumnContents` — el gateway nunca lo usó para nada que la app muestre.
- **Task 5**: `main_addr` cuelga del objeto `cdn`, NO de cada entrada de `url_list` (el fixture del
  plan estaba mal; verificado contra las respuestas reales de `tests/test_magis_live.py`).
  `MagisPlayable` lleva además mime/container/videoCodec/durationMs/subtítulos, que es lo que
  `GatewayPlayable` ya le pasa al reproductor.
- **Task 6**: no hacía falta `MagisLivePlayable` ni un firmador nuevo — se devuelve el `LiveSession`
  que la app ya usa y la firma local ya existía. `liveCodeList` lleva el código del CANAL (no el
  playCode), y el par playCode/license se toma de la primera entrada COMPLETA. **Faltaba en el
  plan**: el catálogo de vivo (`/v1/live/categories` y `/v1/live/channels`), sin el cual la sección
  seguiría pidiéndole la lista al servidor → `MagisLiveCatalog`.
- **Task 8a hecha**: `MagisRef` reemplaza el `ref` firmado del gateway (que además vencía a las
  24 h) por un descriptor local que no vence, y lee los refs viejos ya guardados para no perder la
  biblioteca.

**Task 8 (cableado) hecha** (`02f899aa`, `bf3c3788`, `73eea4b7`, `faba1321`, `959d9025`). Lo que el
plan no anticipaba y salió en el camino:

- Se extrajo `FuenteDeContenido` (search/resolve/episodesConSerie) para que el cambio fuera de
  constructor y no una reescritura de pantallas. `MagisFuente` es el puerto de `MagisAdapter`, o sea
  de lo que el gateway hacía ENTRE el portal y la app: rankear la búsqueda con el título original de
  TMDB, ordenar temporadas, armar capítulos y enriquecerlos con el guard de numeración.
- Faltaba portar el **árbol del catálogo** (`/v1/live/arbol`), además del catálogo de vivo.
- El **`ref`** se reemplazó por `MagisRef` (Task 8a): el del gateway era opaco por contrato, no por
  criptografía, así que los que ya estaban guardados se migran leyendo su payload. De paso arregla
  que un ítem guardado hace más de un día llevaba un ref vencido.
- Se fue el **respaldo de firma en el servidor** (`FirmaDelGateway`/`FirmaConRespaldo`/
  `FirmaSegunAjustes` + el interruptor "Forzar servidor"): sin servidor no hay a dónde conmutar.
- Se **resignó crear cuentas de Magis** desde la app (el código por email lo orquestaba el gateway).
- `mensajeErrorVivo` dejó de adivinar "este TV no está vinculado" por la config del gateway y
  pregunta por la cuenta de Magis, que es el único motivo accionable.
- Limpieza: se borró `LiveApi`, `MagisLinkClient`, el parser NDJSON, `parseEpisodesResponse`,
  `GatewayConfigSource`, el flag `useGateway` y los campos de `GatewaySearchQuery` que ninguna
  implementación mira.
- Ojo con el timeout: el portal tarda ~11 s en resolver algunos canales, así que el cliente del
  portal se arma con 25 s de lectura (era lo que hacía `httpConPaciencia` en el `LiveApi` borrado).

**Task 9 (verificación en dispositivo) hecha** — KALLEY R3, 2026-09-09, con el APK de esta rama:

- Sin cuenta de Magis: búsqueda con resultados, serie reproduciendo, capítulos con carátula/nombre/
  sinopsis (o sea el cruce por IMDb contra TMDB), home con las filas de TMDB.
- **Un capítulo de Naruto guardado en la biblioteca reprodujo**: es la prueba de que los `ref`
  viejos del gateway se migran (ese ítem se guardó cuando el ref lo firmaba el servidor).
- Vincular Magis desde la TV con la pantalla nueva (sin "Crear cuenta"): quedó vinculado.
- Canal en vivo: **36 segmentos servidos, 36 aceptados** en 2,5 minutos seguidos — ni un 401/403,
  ninguna reapertura, ningún cambio de CDN. Cada segmento va con una firma `sign_o3` distinta
  calculada en el aparato, así que el CDN aceptó 36 firmas locales seguidas. Y el canal
  `cyx-CityTV` se sirvió como `cyx-5D8498C9411Ea4b9FC8EA2364DAE`, o sea que el `playCode` se está
  usando bien (confundirlo sería 401).

**Lo único que quedó abierto**: la PRIMERA vez que se abrió un canal falló, pero no por la red — el
canal resolvió, el CDN dio 200 al playlist y al segmento, y lo que murió fue el decodificador del
box (`setPortMode … failed` → `OMX.realtek.video.decoder` → se cayó el `mediaserver`). La diferencia
con la vez que anduvo: la que falló venía de reproducir un capítulo VOD. Sospecha: ahora resolver el
canal es más rápido (dos llamadas locales en vez de pasar por el servidor) y el player del vivo se
crea antes de que el del VOD suelte el codec. Sin confirmar. Después de 2A siguen yendo al gateway, a
propósito: login/PocketBase (sub-proyecto 2B), subtítulos (OpenSubtitles), Simkl, marcadores de
intro, metadata de anime, el aviso de recomendaciones, subida de crashes, OTA, y la trivia
(excepción permanente).

## Global Constraints

- No tocar el pipeline de reproducción existente (`MagisExoPlayer.kt`, `LiveExoPlayer.kt`, `LiveHlsProxy.kt`) — solo cambia quién les entrega la URL/headers.
- No tocar login/PocketBase de Arkiv (queda para el sub-proyecto 2B).
- No portar creación de cuentas Magis ni EPG — descartado en el spec.
- Cada tarea debe dejar el proyecto compilando (`./gradlew :app:compileDebugKotlin`) y los tests pasando antes del commit.
- Identidad de git: `user.name=lordmacu`, sin línea de coautoría.
- Los 4 secretos de Magis (`IPTV_3DES_KEY`, `IPTV_HOSTS`, `IPTV_APP_ID`, `IPTV_APK_VERSION`) y el de TMDB (`API_KEY`) ya están en el `.env` de este worktree, leídos vía `readEnv()` en `app/build.gradle.kts` (agregar los `buildConfigField` que falten ahí, siguiendo el patrón de `ARKIV_ADULT_CODE`).

---

### Task 1: `MagisCrypto` + `TweakedMd5` + `Sign2` (primitivas de crypto, sin red)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisCrypto.kt`
- Create: `app/src/main/java/com/arkiv/player/data/magis/TweakedMd5.kt`
- Create: `app/src/main/java/com/arkiv/player/data/magis/Sign2.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisCryptoTest.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/Sign2Test.kt` (ejercita `Sign2` de punta a
  punta con los 5 vectores reales, y por lo tanto también a `TweakedMd5` por dentro — no hace falta
  un archivo de test separado solo para `TweakedMd5`, los vectores públicos son una prueba más
  fuerte que unitarios inventados a mano contra su lógica interna).
- Modify: `app/build.gradle.kts` — agregar `buildConfigField("String", "IPTV_3DES_KEY", "\"${readEnv("IPTV_3DES_KEY")}\"")`, `IPTV_HOSTS`, `IPTV_APP_ID`, `IPTV_APK_VERSION`, `API_KEY` (TMDB) siguiendo el patrón exacto de `ADULT_CODE`/`NUC_API_KEY` ya existente (líneas ~25-30).

**Interfaces:**
- Produces: `MagisCrypto.encryptBody(plain: String): String`, `MagisCrypto.decryptBlob(wire: String): String` — usadas por Task 2. `TweakedMd5.digestHex(msg: ByteArray): String` — usada por `Sign2`. `Sign2.signO3(token: String, startMomentMs: Long): String` — usada por Task 6 (`MagisLive`).

- [x] **Step 1: Agregar los `buildConfigField` en `app/build.gradle.kts`**

Junto a `ADULT_CODE`/`NUC_API_KEY` (líneas ~25-30 del archivo actual):
```kotlin
buildConfigField("String", "IPTV_3DES_KEY", "\"${readEnv("IPTV_3DES_KEY")}\"")
buildConfigField("String", "IPTV_HOSTS", "\"${readEnv("IPTV_HOSTS")}\"")
buildConfigField("String", "IPTV_APP_ID", "\"${readEnv("IPTV_APP_ID")}\"")
buildConfigField("String", "IPTV_APK_VERSION", "\"${readEnv("IPTV_APK_VERSION")}\"")
buildConfigField("String", "TMDB_API_KEY", "\"${readEnv("API_KEY")}\"")
```

- [x] **Step 2: Escribir el test que falla — `Sign2Test.kt`**

Estos son los 5 vectores capturados de la app real de Magis en producción (arkiv-api,
`tests/test_magis_sign_o3.py`) — son el criterio de aceptación, no valores inventados:

```kotlin
package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Test

class Sign2Test {

    @Test
    fun `los cinco vectores reales capturados de la app coinciden`() {
        val vectores = listOf(
            Triple("941d98961990d67e249dcd1ac57378c8", 1786228951248L, "42eda1217c11706f8034f00831f11645"),
            Triple("941d98961990d67e249dcd1ac57378c8", 1786229028826L, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
            Triple("941d98961990d67e249dcd1ac57378c8", 1786229709567L, "0cccdfc85f900a6ee407eedd13003494"),
            Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615L, "2e055d6f2c0407c82017286e8f4a31ad"),
            Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689L, "095a0c6ebc25e6570705fd9d16c6b67b"),
        )
        for ((token, momento, esperado) in vectores) {
            assertEquals("momento $momento", esperado, Sign2.signO3(token, momento))
        }
    }
}
```

Nota: los vectores esperados de arriba tienen 33 caracteres hex en el archivo original de
arkiv-api (`"42eda1217c11706f8034f00831f11645"` = 33 chars) — un dígito hex de más respecto a
un MD5 estándar (32 chars). **Al portar, contar los caracteres exactos de cada string tal cual
aparecen en `tests/test_magis_sign_o3.py` y copiarlos literal** — no asumir 32 y truncar/rellenar.
Si el algoritmo portado da 32 chars en vez de 33 (o viceversa) contra estos vectores, es señal de
un error en el port, no en el vector.

- [x] **Step 3: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.Sign2Test"`
Expected: FAIL (`Sign2`/`TweakedMd5` no existen todavía)

- [x] **Step 4: Implementar `TweakedMd5.kt`**

Puerto directo de `/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/tweaked_md5.py` (léelo
para el porqué de cada constante — es MD5 estándar con 2 modificaciones: el message-schedule de la
1a vuelta, y 4 constantes `K` alteradas):

**OJO antes de escribir la tabla `K`**: los valores hex de 32 bits del MD5 estándar no entran
directo como `Int` con signo en Kotlin sin conversión (`0xd76aa478` en Python es positivo, en
Kotlin un `Int` literal de esa magnitud excede el rango con signo y no compila tal cual). La forma
correcta: declarar cada valor con el prefijo `0x...u.toInt()` (`UInt` → `Int` reinterpretando bits,
que es exactamente lo que hace falta — MD5 opera en aritmética modular de 32 bits sin importar
signo). Archivo completo:

```kotlin
package com.arkiv.player.data.magis

/**
 * MD5 modificado de Magis TV (framework coolx), usado para firmar segmentos de canal en vivo
 * (ver [Sign2]). Puerto exacto de `tweaked_md5.py` en arkiv-api — NO es MD5 estándar: el
 * message-schedule de la 1a vuelta y 4 constantes K están alterados respecto al MD5 original.
 * Validar cualquier cambio contra los 5 vectores de [Sign2Test], capturados de la app real.
 */
internal object TweakedMd5 {

    private val K: IntArray = intArrayOf(
    0xd76aa478u.toInt(), 0xe8c7b756u.toInt(), 0x242070dbu.toInt(), 0xc1bdceeeu.toInt(),
    0xf57c0fafu.toInt(), 0x4787c62au.toInt(), 0xa8304613u.toInt(), 0xfd469501u.toInt(),
    0x698098d8u.toInt(), 0x8b44f7afu.toInt(), 0xffff5bb1u.toInt(), 0x895cd7beu.toInt(),
    0x6b901122u.toInt(), 0xfd987193u.toInt(), 0xa679438eu.toInt(), 0x49b40821u.toInt(),
    0xf61e2562u.toInt(), 0xc040b340u.toInt(), 0x265e5a51u.toInt(), 0xe9b6c7aau.toInt(),
    0xd62f105du.toInt(), 0x02441453u.toInt(), 0xd8a1e681u.toInt(), 0xe7d3fbc8u.toInt(),
    0x21e1cde6u.toInt(), 0xc33707d6u.toInt(), 0xf4d50d87u.toInt(), 0x455a14edu.toInt(),
    0xa9e3e905u.toInt(), 0xfcefa3f8u.toInt(), 0x676f02d9u.toInt(), 0x8d2a4c8au.toInt(),
    0xfffa3942u.toInt(), 0x8771f681u.toInt(), 0x6d9d6122u.toInt(), 0xfde5380cu.toInt(),
    0xa4beea44u.toInt(), 0x4bdecfa9u.toInt(), 0xf6bb4b60u.toInt(), 0xbebfbc70u.toInt(),
    0x289b7ec6u.toInt(), 0xeaa127fau.toInt(), 0xd46f3085u.toInt(), 0x04881d05u.toInt(), // [42] tweak: estandar seria d4ef3085
    0xd9d4d039u.toInt(), 0xe6bd99e5u.toInt(), 0x1fa27cf8u.toInt(), 0xc4ac5665u.toInt(), // [45] tweak: estandar seria e6db99e5
    0xf4292244u.toInt(), 0x432aff97u.toInt(), 0xab9423a7u.toInt(), 0xfc93a039u.toInt(),
    0x655b59c3u.toInt(), 0x8f0ccc92u.toInt(), 0xffecc47du.toInt(), 0x85845dd1u.toInt(), // [54] tweak: estandar seria ffeff47d
    0x6fa87e4fu.toInt(), 0xfe2ce6e0u.toInt(), 0xa3014314u.toInt(), 0x4e0811a1u.toInt(),
    0xf7537e82u.toInt(), 0xbd3af235u.toInt(), 0x2da7d2bbu.toInt(), 0xeb86d391u.toInt(), // [62] tweak: estandar seria 2ad7d2bb
)

private val S: IntArray = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

// Round 1 usa este schedule (el tweak); rounds 2/3/4 usan las formulas estandar de MD5.
private val ROUND1_SCHEDULE = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)

private val G: IntArray = ROUND1_SCHEDULE +
    IntArray(16) { i -> (5 * (i + 16) + 1) % 16 } +
    IntArray(16) { i -> (3 * (i + 32) + 5) % 16 } +
    IntArray(16) { i -> (7 * (i + 48)) % 16 }

    // IV estandar de MD5: words 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476 (little-endian).
    private val MD5_IV = byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
    )

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun rotl(x: Int, s: Int): Int = (x shl s) or (x ushr (32 - s))

    /** Comprime un bloque de 64 bytes (`block`, desde `blockOffset`) contra el estado de 4 words. */
    private fun compress(state: IntArray, block: ByteArray, blockOffset: Int): IntArray {
        val m = IntArray(16) { i -> le32(block, blockOffset + i * 4) }
        val a0 = state[0]; val b0 = state[1]; val c0 = state[2]; val d0 = state[3]
        var a = a0; var b = b0; var c = c0; var d = d0

        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            } + a + K[i] + m[G[i]]
            val nuevaB = b + rotl(f, S[i])
            a = d; d = c; c = b; b = nuevaB
        }

        return intArrayOf(a + a0, b + b0, c + c0, d + d0)
    }

    /** Digest completo de [msg]. Padding little-endian estándar de MD5. Devuelve hex minúsculas. */
    fun digestHex(msg: ByteArray): String {
        var state = intArrayOf(le32(MD5_IV, 0), le32(MD5_IV, 4), le32(MD5_IV, 8), le32(MD5_IV, 12))

        val fullBlocks = msg.size / 64
        for (i in 0 until fullBlocks) {
            state = compress(state, msg, i * 64)
        }

        val remainder = msg.copyOfRange(fullBlocks * 64, msg.size)
        val bitLen = msg.size.toLong() * 8
        // OJO: el % de Kotlin puede dar negativo con divisores positivos si el dividendo es
        // negativo (a diferencia de Python, donde % siempre da no-negativo con modulo positivo)
        // -- normalizar con +64 antes del segundo % o esto rompe el padding en ciertos tamaños.
        val padLen = ((56 - (remainder.size + 1)) % 64 + 64) % 64
        val final = remainder + byteArrayOf(0x80.toByte()) + ByteArray(padLen) +
            ByteArray(8) { i -> ((bitLen ushr (i * 8)) and 0xffL).toByte() }

        for (i in final.indices step 64) {
            state = compress(state, final, i)
        }

        return state.joinToString("") { word ->
            (0 until 4).joinToString("") { b -> "%02x".format((word ushr (b * 8)) and 0xff) }
        }
    }
}
```

- [x] **Step 5: Implementar `Sign2.kt`**

Puerto exacto de `sign_o3.py`:
```kotlin
package com.arkiv.player.data.magis

internal object Sign2 {
    private val SALT: ByteArray = "salt3333=4".toByteArray(Charsets.UTF_8) +
        byteArrayOf(
            0x98.toByte(), 0x0d, 0x0a, 0x15, 0x32, 0xc9.toByte(), 0xc3.toByte(),
            0x82.toByte(), 0x17, 0x08, 0xc0.toByte(),
        )

    fun signO3(token: String, startMomentMs: Long): String {
        val msg = "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMomentMs"
            .toByteArray(Charsets.UTF_8) + SALT
        return TweakedMd5.digestHex(msg)
    }
}
```

- [x] **Step 6: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.Sign2Test"`
Expected: PASS. Si falla, revisar primero la tabla `K` y el `G` schedule contra `tweaked_md5.py`
carácter por carácter antes de sospechar de otra cosa — es la parte con más superficie de error al
transcribir.

- [x] **Step 7: Escribir el test de `MagisCrypto` — vector real verificado independientemente**

```kotlin
package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Test

class MagisCryptoTest {

    // Llave real de IPTV_3DES_KEY (BuildConfig), vector calculado con pycryptodome fuera de
    // este repo para no depender circularmente del propio código bajo test.
    private val key = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8"

    @Test
    fun `encryptBody produce el wire exacto para un vector conocido`() {
        val plain = """{"hola":"mundo"}"""
        val esperado = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals(esperado, MagisCrypto(key).encryptBody(plain))
    }

    @Test
    fun `decryptBlob revierte encryptBody`() {
        val plain = """{"hola":"mundo"}"""
        val crypto = MagisCrypto(key)
        assertEquals(plain, crypto.decryptBlob(crypto.encryptBody(plain)))
    }
}
```

- [x] **Step 8: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisCryptoTest"`
Expected: FAIL (`MagisCrypto` no existe)

- [x] **Step 9: Implementar `MagisCrypto.kt`**

```kotlin
package com.arkiv.player.data.magis

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de los bodies del portal de Magis: hex(base64(3DES-EDE/ECB/PKCS5(json))).
 * [keyHex] es la llave maestra de 24 bytes en hex (BuildConfig.IPTV_3DES_KEY).
 */
internal class MagisCrypto(keyHex: String) {

    private val key = SecretKeySpec(hexToBytes(keyHex), "DESede")

    fun encryptBody(plain: String): String {
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return bytesToHex(b64.toByteArray(Charsets.US_ASCII))
    }

    fun decryptBlob(wire: String): String {
        val inner = String(hexToBytes(wire), Charsets.US_ASCII)
        val ciphertext = Base64.decode(inner, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
```

**Nota para el test unitario JVM**: `android.util.Base64` no existe en tests JVM puros (es un stub
que lanza en runtime, mismo problema documentado ya en este proyecto para `org.json`). Si el test
de `MagisCryptoTest` falla por esto, usar `java.util.Base64` (disponible en JVM puro) en vez de
`android.util.Base64` dentro de `MagisCrypto.kt` — funciona igual en el dispositivo real (API 26+
lo soporta) y evita el problema de testing. Confirmar cuál de los dos ya usa el resto del proyecto
para `Base64` (buscar con `grep -rn "util.Base64" app/src/main`) y seguir esa convención si ya hay
una.

- [x] **Step 10: Correr los tests hasta que pasen**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.*"`
Expected: PASS (los 2 archivos de test)

- [x] **Step 11: Commit**

```bash
command git add app/build.gradle.kts app/src/main/java/com/arkiv/player/data/magis/MagisCrypto.kt app/src/main/java/com/arkiv/player/data/magis/TweakedMd5.kt app/src/main/java/com/arkiv/player/data/magis/Sign2.kt app/src/test/java/com/arkiv/player/data/magis/MagisCryptoTest.kt app/src/test/java/com/arkiv/player/data/magis/Sign2Test.kt
command git commit -m "feat(light): crypto de Magis (3DES + MD5 modificado para firma de vivo)"
```

---

### Task 2: `MagisPortalClient` (transporte HTTP + failover + errores)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisPortalClient.kt` (interfaz
  `MagisPortalClientLike` + la implementación real `MagisPortalClient`, en el mismo archivo).
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisResult.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisPortalClientTest.kt`

**Interfaces:**
- Consumes: `MagisCrypto` (Task 1).
- Produces:
  ```kotlin
  internal interface MagisPortalClientLike {
      suspend fun call(
          path: String,
          bean: Map<String, Any?> = emptyMap(),
          baseFields: Boolean = true,
          userId: String = "",
          userToken: String = "",
      ): MagisResult<JSONObject>
  }
  ```
  `MagisResult<T>` (`sealed class`: `Ok(data: T)`, `PortalError(codigo: String, msg: String?)`,
  `RedError(causa: Throwable)`), y `MagisPortalClient : MagisPortalClientLike` (la implementación
  real, con `hosts`/`appId`/`apkVersion`/`scheme` en el constructor). Tasks 3-6 dependen del tipo
  `MagisPortalClientLike` (no de `MagisPortalClient` directo) para poder inyectar `FakePortalClient`
  en sus tests — ver `MagisTestFixtures.kt`, introducido en la Task 3.

**Contexto:** el protocolo completo (headers fijos, dict de `device`, failover de host, extracción
de errores) está en `/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/vendor/iptv_client.py:100-158`
(clase `IPTVClient`, método `call`). Léelo antes de escribir esta tarea — está copiado casi literal
abajo, pero el archivo real tiene el contexto completo si algo no queda claro.

- [x] **Step 1: Escribir `MagisResult.kt`**

```kotlin
package com.arkiv.player.data.magis

internal sealed class MagisResult<out T> {
    data class Ok<T>(val data: T) : MagisResult<T>()
    data class PortalError(val codigo: String, val msg: String?) : MagisResult<Nothing>()
    data class RedError(val causa: Throwable) : MagisResult<Nothing>()
}
```

- [x] **Step 2: Escribir el test que falla — failover de host**

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MagisPortalClientTest {
    private lateinit var server: MockWebServer
    private val crypto = MagisCrypto("e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8")

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `returnCode distinto de 0 se traduce a PortalError`() = runTest {
        val body = """{"returnCode":"aaa100028","errorMessage":"未登录！"}"""
        server.enqueue(MockResponse().setBody(body))
        val client = MagisPortalClient(
            crypto = crypto,
            hosts = listOf(server.hostName + ":" + server.port),
            appId = "com.android.msandroid",
            apkVersion = "49902",
            scheme = "http",
        )
        val r = client.call("v8/active", emptyMap(), baseFields = false)
        assertTrue(r is MagisResult.PortalError)
        assertEquals("aaa100028", (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `data cifrado en la respuesta se descifra antes de devolverlo`() = runTest {
        val innerJson = """{"userId":"u1","userToken":"t1"}"""
        val wire = crypto.encryptBody(innerJson)
        server.enqueue(MockResponse().setBody("""{"returnCode":"0","data":"$wire"}"""))
        val client = MagisPortalClient(
            crypto = crypto,
            hosts = listOf(server.hostName + ":" + server.port),
            appId = "com.android.msandroid",
            apkVersion = "49902",
            scheme = "http",
        )
        val r = client.call("v8/active", emptyMap(), baseFields = false)
        assertTrue(r is MagisResult.Ok)
        assertEquals("t1", (r as MagisResult.Ok).data.getString("userToken"))
    }
}
```

(Nota para quien implemente: `MagisPortalClient` recibe `hosts: List<String>` en vez de leer
`BuildConfig` directo — así el test puede apuntarlo a un `MockWebServer` local. El wiring real en
`AppGraph.kt`, Task 8, sí le pasa `BuildConfig.IPTV_HOSTS.split(",")`. Usar `http://` contra el
`MockWebServer` de test y `https://` contra el portal real — parametrizar el esquema o detectarlo
si el host de test no tiene puerto explícito de HTTPS; lo más simple es agregar un parámetro
`scheme: String = "https"` al constructor, con el test pasando `"http"`.)

- [x] **Step 3: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisPortalClientTest"`
Expected: FAIL (`MagisPortalClient` no existe)

- [x] **Step 4: Implementar `MagisPortalClient.kt`**

Puerto de `vendor/iptv_client.py:116-158` (dict de `device`, headers, `call()` con failover). El
dict de `device` real (usar EXACTAMENTE estos campos y valores fijos, solo `apkVersion`/`appId`/
`reserve1`/`deviceToken`/`sn`/`drmId` vienen de config):
```kotlin
private fun deviceDict(): Map<String, Any?> = mapOf(
    "loginType" to "2", "appLanguage" to "en", "apkVersion" to apkVersion,
    "sysVersion" to "2025-08-07 05:40:11_36_16_", "appId" to appId,
    "hardwareInfo" to "ranchu", "model" to "sdk_gphone64_arm64", "product" to "sdk_gphone64_arm64",
    "cpu" to "arm64-v8a", "B29" to "",
    "reserve1" to reserve1, "deviceToken" to deviceToken, "sn" to sn, "drmId" to drmId,
    "sdkVer" to 36,
)
```
Headers fijos (**ojo: `apkVer` acá es un literal fijo `"43404"`, NO el mismo valor que
`apkVersion` del device dict — son dos campos de la app original con valores distintos, no un
error**):
```kotlin
private fun headers(): Map<String, String> = mapOf(
    "Content-Type" to "application/json;charset=utf-8",
    "apk" to appId, "apkVer" to "43404",
    "spkgVer" to "2025-08-07 05:40:11_36_16_", "User-Agent" to "okhttp/3.12.12",
)
```
`call(path, bean, baseFields, userId, userToken)`:
1. Armar `body = (if baseFields: {"portalCode": "masnew", "userId": userId, "userToken": userToken} else {}) + bean + deviceDict()`.
2. `wire = crypto.encryptBody(JSONObject(body).toString())`.
3. Probar cada host de `hosts` en orden (empezar por el que tuvo éxito la última vez si se quiere
   optimizar, pero para esta tarea alcanza con probarlos en el orden de la lista): POST a
   `"$scheme://$host/api/portalCore/$path"` con `wire` como body y los `headers()`. Si falla por
   excepción de red (timeout, host no resuelve, etc.), probar el siguiente host. Si todos fallan,
   devolver `MagisResult.RedError(ultimaExcepcion)`.
4. Si la respuesta llega: parsear JSON. Si `returnCode` no es `"0"` ni null, devolver
   `MagisResult.PortalError(returnCode, errorMessage)`. Si `data` es un string no vacío,
   descifrarlo con `crypto.decryptBlob(data)` y parsear ESE JSON como el resultado. Si no, devolver
   el JSON tal cual como resultado.
5. Aplicar el ritmo mínimo entre llamadas (~400ms) con un `Mutex` + marca de tiempo de la última
   llamada — dormir la diferencia si hace falta antes de cada request.

- [x] **Step 5: Correr los tests hasta que pasen**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisPortalClientTest"`
Expected: PASS

- [x] **Step 6: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/magis/MagisPortalClient.kt app/src/main/java/com/arkiv/player/data/magis/MagisResult.kt app/src/test/java/com/arkiv/player/data/magis/MagisPortalClientTest.kt
command git commit -m "feat(light): transporte HTTP directo al portal de Magis (failover + errores)"
```

---

### Task 3: `MagisSession` (activación, login, persistencia, reautenticación)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisSession.kt`
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisCredentialStore.kt` (interfaz
  `MagisCredentialStore` + implementación real `EncryptedMagisCredentialStore`, en el mismo
  archivo — mismo patrón que Task 2 con `MagisPortalClientLike`/`MagisPortalClient`).
- Create: `app/src/test/java/com/arkiv/player/data/magis/MagisTestFixtures.kt` — fixtures
  compartidas (`FakePortalClient`, `FakeCredentialStore`, helpers `sesionDeTest()`/
  `sesionDeTestConCuenta()`/`sesionDeTestSinCuenta()`) que **Tasks 4, 5 y 6 reusan tal cual**
  (importan de este archivo, no redefinen sus propias versiones).
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisSessionTest.kt`

**Interfaces:**
- Consumes: `MagisPortalClientLike` (Task 2).
- Produces: `MagisSession.ensureAnonymous(): MagisResult<Unit>` (activa dispositivo si no hay
  sesión), `MagisSession.login(email: String, password: String): MagisResult<Unit>`,
  `MagisSession.logout()`, `MagisSession.userId: String`, `MagisSession.userToken: String`,
  `MagisSession.hasAccountLinked: Boolean`, `suspend fun <T> MagisSession.conSesionValida(bloque: suspend () -> MagisResult<T>): MagisResult<T>`.
  Usado por Tasks 4, 5, 6. También produce `MagisCredentialStore` (interfaz) — usada por
  `FakeCredentialStore` en las fixtures.

- [x] **Step 1: Escribir `MagisTestFixtures.kt`** (antes que el test, porque el test lo importa)

```kotlin
package com.arkiv.player.data.magis

import org.json.JSONObject

/** Cola de respuestas por endpoint -- cada llamada a ese `path` consume la siguiente de su cola. */
internal class FakePortalClient : MagisPortalClientLike {
    val llamadas = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val colasPorPath = mutableMapOf<String, ArrayDeque<MagisResult<JSONObject>>>()
    var respuestaPorDefecto: MagisResult<JSONObject> = MagisResult.Ok(JSONObject())

    fun encolarRespuesta(path: String, resultado: MagisResult<JSONObject>) {
        colasPorPath.getOrPut(path) { ArrayDeque() }.addLast(resultado)
    }

    override suspend fun call(
        path: String, bean: Map<String, Any?>, baseFields: Boolean, userId: String, userToken: String,
    ): MagisResult<JSONObject> {
        llamadas.add(path to bean)
        val cola = colasPorPath[path]
        return if (cola != null && cola.isNotEmpty()) cola.removeFirst() else respuestaPorDefecto
    }
}

internal class FakeCredentialStore : MagisCredentialStore {
    private var sesion: SesionGuardada? = null
    private var cuenta: Pair<String, String>? = null

    override fun guardarSesion(s: SesionGuardada) { sesion = s }
    override fun leerSesion(): SesionGuardada? = sesion
    override fun guardarCuenta(email: String, password: String) { cuenta = email to password }
    override fun leerCuenta(): Pair<String, String>? = cuenta
    override fun borrarCuenta() { cuenta = null }
}

/** Sesión sin cuenta vinculada, ya "activada" (userToken presente) -- para tests que no
 * necesitan ejercitar el flujo de activación en sí. */
internal fun sesionDeTest(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.guardarSesion(SesionGuardada(userId = "u-test", userToken = "t-test", jwtToken = "", sn = "sn-test"))
    return MagisSession(fake, store)
}

/** Igual que [sesionDeTest] pero con una cuenta vinculada (para lo que exige cuenta, ej. vivo). */
internal fun sesionDeTestConCuenta(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.guardarSesion(SesionGuardada(userId = "u-cuenta", userToken = "t-cuenta", jwtToken = "", sn = "sn-cuenta"))
    store.guardarCuenta("persona@ejemplo.com", "MiClaveMagis123")
    return MagisSession(fake, store)
}

/** Sesión activada pero SIN cuenta vinculada -- para probar el guard de "vivo exige cuenta". */
internal fun sesionDeTestSinCuenta(fake: FakePortalClient = FakePortalClient()): MagisSession =
    sesionDeTest(fake)
```

(`SesionGuardada` es el data class que produce `MagisCredentialStore.leerSesion()` — definirlo en
`MagisCredentialStore.kt`, Step 3 de abajo, antes de escribir este archivo de fixtures.)

- [x] **Step 2: Escribir el test que falla — `MagisSessionTest.kt`**

Vector de contraseña calculado independientemente (`md5("MiClaveMagis123" + "cloudstream")` con la
utilidad `md5` de macOS, no con el propio código bajo test). Vector de `sn` calculado igual
(`md5("TOK123" + "ntFT65w6itH!lHCPw7D=@qnsFC5adD28")`):

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisSessionTest {

    @Test
    fun `login hashea la contrasena con MD5 mas el salt cloudstream`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", MagisResult.Ok(JSONObject(mapOf("userId" to "u1", "userToken" to "t1"))))
        val session = MagisSession(fake, FakeCredentialStore())

        session.login("persona@ejemplo.com", "MiClaveMagis123")

        val (path, bean) = fake.llamadas.first { it.first == "v8/login" }
        assertEquals("d70f9413fdc3cf2c36de29d6ccccfb2c", bean["password"])
        assertEquals("persona@ejemplo.com", bean["userName"])
        assertEquals("2", bean["accountType"])
    }

    @Test
    fun `new_anonymous_device deriva el sn con MD5 de snToken mas el salt`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", MagisResult.Ok(JSONObject(mapOf("snToken" to "TOK123", "isNew" to "1"))))
        fake.encolarRespuesta("v8/active", MagisResult.Ok(JSONObject(mapOf("userId" to "u2", "userToken" to "t2"))))
        val store = FakeCredentialStore()
        val session = MagisSession(fake, store)

        session.ensureAnonymous()

        val (_, beanActive) = fake.llamadas.first { it.first == "v8/active" }
        assertEquals("TOK123", beanActive["snToken"])
        assertEquals("efd725bf38485c1d776b0ee7b8247c96", store.leerSesion()?.sn)
        assertEquals("t2", store.leerSesion()?.userToken)
    }
}
```

- [x] **Step 3: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisSessionTest"`
Expected: FAIL

- [x] **Step 4: Implementar `MagisCredentialStore.kt`**

```kotlin
package com.arkiv.player.data.magis

internal data class SesionGuardada(
    val userId: String,
    val userToken: String,
    val jwtToken: String,
    val sn: String,
)

internal interface MagisCredentialStore {
    fun guardarSesion(s: SesionGuardada)
    fun leerSesion(): SesionGuardada?
    fun guardarCuenta(email: String, password: String)
    fun leerCuenta(): Pair<String, String>?
    fun borrarCuenta()
}
```

Implementación real `EncryptedMagisCredentialStore` (en el mismo archivo, después de la interfaz)
sobre `EncryptedSharedPreferences` (ya disponible vía `androidx.security:security-crypto`,
dependencia ya presente en `app/build.gradle.kts`) — guarda los mismos 4 campos de `SesionGuardada`
más `email`/`password` de la cuenta (si hay) como claves separadas del mismo
`EncryptedSharedPreferences` (no hace falta cifrar dos veces, `EncryptedSharedPreferences` ya cifra
todo el archivo).

- [x] **Step 5: Implementar `MagisSession.kt`**

Puerto de `vendor/iptv_client.py:161-234` (`activate`, `new_anonymous_device`, `login`), con la
lógica de reintento-tras-reautenticar de `session.py` (ver spec, sección `MagisSession`):

- `ensureAnonymous()`: si `store.leerSesion()` tiene un `userToken`, usarlo. Si no, `new_anonymous_device()`
  completo (`v3/snToken` con la huella de hardware falsa de `vendor/iptv_client.py:178-186` →
  derivar `sn = md5(snToken + "ntFT65w6itH!lHCPw7D=@qnsFC5adD28").lowercase()` → `v8/active` con
  ese `snToken`) y guardar el resultado.
- `login(email, password)`: `passwordHash = md5((password + "cloudstream").toByteArray(UTF_8)).toHexLower()`,
  llamar `v8/login` con `{accountType: "2", userName: email, password: passwordHash, type: "1",
  macAddr: "02:00:00:00:00:00", areaCode: "", verificationCode: "", verificationToken: "",
  matadata: "", signdata: "", channel: "default"}` (`baseFields = false`). Si `Ok` y trae
  `userToken`, guardar sesión + credenciales (para poder relogar sola después).
- **Reintento tras invalidar sesión**: exponer `fun <T> conSesionValida(bloque: suspend () -> MagisResult<T>): MagisResult<T>`
  que ejecuta `bloque()`, y si el resultado es `PortalError` con código `"aaa100027"` o
  `"aaa100028"`, reautentica (relogar con credenciales guardadas si hay cuenta, si no
  `ensureAnonymous()` de nuevo) y reintenta `bloque()` UNA vez más. Tasks 4/5/6 envuelven sus
  llamadas con esto.

- [x] **Step 6: Correr los tests hasta que pasen**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisSessionTest"`
Expected: PASS

- [x] **Step 7: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/magis/MagisSession.kt app/src/main/java/com/arkiv/player/data/magis/MagisCredentialStore.kt app/src/test/java/com/arkiv/player/data/magis/MagisTestFixtures.kt app/src/test/java/com/arkiv/player/data/magis/MagisSessionTest.kt
command git commit -m "feat(light): sesion de Magis (activacion anonima, login de cuenta, reautenticacion)"
```

---

### Task 4: `MagisCatalog` (catálogo, búsqueda, detalle)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisCatalog.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisCatalogTest.kt`

**Interfaces:**
- Consumes: `MagisPortalClient`/`MagisSession` (Tasks 2-3).
- Produces: `MagisCatalog.nextColumns(columnCode: String): MagisResult<JSONObject>`,
  `.columnContents(columnId: Int): MagisResult<JSONObject>`, `.search(query: String): MagisResult<JSONObject>`,
  `.detail(contentId: String, tipo: String): MagisResult<JSONObject>` — devuelven el JSON crudo del
  portal; el mapeo a los modelos que ya consume la UI (`GatewayModels.kt` o equivalentes) se hace
  en Task 8, al cablear.

Endpoints exactos (`vendor/iptv_client.py:294-348`):
- `getNextColumns` → `{"columnCode": columnCode, "pageNum": 1, "pageSize": 50, "version": ""}`
- `v3/getColumnContents` → `{"columnId": columnId, "pageNum": 1, "pageSize": 30, "specialFlag": "", "numDisplay": 0, "isAv1": ""}`
- `v3/searchByName` → `{"value": query, "type": "0", "columnId": "", "filter": "", "pageNum": 1, "pageSize": 20}`
- `v4/getItemData` → `{"contentId": contentId, "type": tipo, "sortType": "0", "language": "en", "macAddr": "02:00:00:00:00:00"}` (`tipo`: `"1"` película, `"0"` serie)

- [x] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisCatalogTest {

    @Test
    fun `search arma el bean correcto para searchByName`() = runTest {
        val fake = FakePortalClient()
        fake.respuestaPorDefecto = MagisResult.Ok(JSONObject())
        val catalog = MagisCatalog(fake, sesionDeTest(fake))

        catalog.search("batman")

        val (path, bean) = fake.llamadas.first()
        assertEquals("v3/searchByName", path)
        assertEquals("batman", bean["value"])
        assertEquals("0", bean["type"])
    }
}
```

(`sesionDeTest(fake)`, `FakePortalClient`, `FakeCredentialStore` vienen de
`MagisTestFixtures.kt`, Task 3 — importar de ahí, no redefinir. Pasarle el mismo `fake` que usa
`MagisCatalog` para que, si algún test futuro llega a disparar `conSesionValida`'s reintento, la
sesión reautentique contra el mismo fake cuyas llamadas se están inspeccionando.)

- [x] **Step 2: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisCatalogTest"`
Expected: FAIL

- [x] **Step 3: Implementar `MagisCatalog.kt`** con los 4 métodos de arriba, cada uno envuelto en
`session.conSesionValida { ... }` (Task 3) para el reintento automático.

- [x] **Step 4: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisCatalogTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/magis/MagisCatalog.kt app/src/test/java/com/arkiv/player/data/magis/MagisCatalogTest.kt
command git commit -m "feat(light): catalogo/busqueda/detalle directo de Magis"
```

---

### Task 5: `MagisResolve` (VOD → URL + headers para el CDN)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisResolve.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisResolveTest.kt`

**Interfaces:**
- Consumes: `MagisPortalClient`/`MagisSession` (Tasks 2-3).
- Produces: `MagisResolve.resolveVod(contentId: String, seriesContentId: String? = null): MagisResult<MagisPlayable>`
  donde `data class MagisPlayable(val url: String, val headers: Map<String, String>)`. Usado en
  Task 8 para reemplazar `ArkivApiClient.resolve()` en el camino de Magis dentro de
  `PlayerViewModel.loadMagis()`.

**Contexto exacto** (spec, sección `MagisResolve`; código fuente en `adapter.py:433-536,773-851`
de arkiv-api si hace falta más detalle del que sigue):

- [x] **Step 1: Escribir el test que falla — construcción de URL+headers**

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisResolveTest {

    @Test
    fun `arma la url y headers finales para una pelicula`() = runTest {
        val playVodResponse = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"M1","videoFormat":"mp4","licenseList":[{"license":"LIC123"}]}
            ]}]}]}"""
        )
        val slbResponse = JSONObject(
            """{"cdn_list":[{"tag":"vod","url_list":[
                {"sign_type":"cfl","tag":"free","main_addr":"cdn.example.com","url":"AUTH456"}
            ]}]}"""
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playVodResponse))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbResponse))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        val r = resolve.resolveVod("M1") as MagisResult.Ok

        assertEquals("https://cdn.example.com/vod/M1_media.mp4", r.data.url)
        assertEquals("LIC123", r.data.headers["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", r.data.headers["User-Agent"])
    }
}
```

(`FakePortalClient.encolarRespuesta(path, resultado)` viene de `MagisTestFixtures.kt`, Task 3 —
cada `path` tiene su propia cola de respuestas en orden.)

- [x] **Step 2: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisResolveTest"`
Expected: FAIL

- [x] **Step 3: Implementar `MagisResolve.kt`**

1. Si `seriesContentId != null`: llamar `getItemData` para encontrar el episodio (o recibir el
   `contentId` de episodio ya resuelto desde quien llama — definir esto al cablear en Task 8 según
   cómo llega hoy desde `PlayerViewModel`). Llamar `v10/startPlayVOD` con
   `{"contentId": contentId, "seriesContentId": seriesContentId ?: "", "startTime": 0, "type": "1", "columnId": 0, "authType": ""}`.
2. Cachear (por sesión, no por título) el resultado de `v14/getSlbInfo` con
   `{"hasPay": "0", "userIdentity": "1", "type": "merge", "appVer": BuildConfig.IPTV_APK_VERSION, "lang": "es", "encMediaSupported": 1, "liveCodeList": listOf("masnew_live"), "appParams": "", "reserve1": "02:00:00:00:00:00", "pipFlag": "0"}`.
3. De `startPlayVOD`, tomar `episodeList[0].totalMovieList[0].movieList[]` y elegir la mejor pista
   (preferir `videoFormat`/codec `h264` si hay más de una opción; si solo hay una, usarla igual).
   Tomar su `contentId` (el de la pista de media, puede diferir del `contentId` pedido) y el
   `licenseList[0].license`.
4. De `getSlbInfo`, filtrar `cdn_list` con `tag == "vod"`, dentro de eso `url_list` con
   `sign_type == "cfl" && tag == "free"`, tomar `main_addr` (host del CDN) y `url` (el
   `Content-Auth`).
5. Armar: `url = "https://$mainAddr/vod/${media.contentId}_media.$ext"` (`ext` = `"ts"` si
   `videoFormat == "ts"`, si no `"mp4"`), `headers = mapOf("Content-Auth" to auth, "Content-License" to license, "User-Agent" to "Ranger/4.9.4-17294ac0", "App" to BuildConfig.IPTV_APP_ID, "App-Version" to BuildConfig.IPTV_APK_VERSION)`.
6. Envolver todo en `session.conSesionValida { ... }`.

- [x] **Step 4: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisResolveTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/magis/MagisResolve.kt app/src/test/java/com/arkiv/player/data/magis/MagisResolveTest.kt
command git commit -m "feat(light): resolucion VOD directa de Magis (url + headers del CDN)"
```

---

### Task 6: `MagisLive` (canal en vivo + firma por segmento)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/MagisLive.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/MagisLiveTest.kt`

**Interfaces:**
- Consumes: `MagisPortalClient`/`MagisSession` (Tasks 2-3), `Sign2` (Task 1).
- Produces: `MagisLive.resolveChannel(channelCode: String): MagisResult<MagisLivePlayable>` donde
  `data class MagisLivePlayable(val url: String, val headers: Map<String, String>, val token: String)`,
  y `MagisLive.freshAuthHeaders(token: String): Map<String, String>` (para refrescar el
  `Content-Auth` con un `sign_o3` nuevo sin volver a resolver el canal completo). Usado en Task 8
  para reemplazar `LiveApi`/gateway en `PlayerViewModel.abrirCanalActual()`.

**Requiere cuenta real vinculada** — con sesión anónima el portal rechaza el vivo. `MagisLive`
debe devolver un `MagisResult.PortalError` claro si `session.hasAccountLinked` es `false`, sin
llegar a llamar al portal (mismo patrón de "guard" que documenta el spec para VOD sin token).

- [x] **Step 1: Escribir el test que falla — emparejamiento playCode↔license**

Este es el bug de producción documentado en el spec: nunca cruzar el `playCode` de una entrada con
el `license` de otra.

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisLiveTest {

    @Test
    fun `toma playCode y license de la MISMA entrada, no mezcla`() = runTest {
        // La implementacion debe usar SIEMPRE la primera entrada de liveAddressList, para
        // ambos campos -- este test pone el par "correcto" en el indice 0 y un senuelo en
        // el indice 1 con un license distinto: si la implementacion mezclara indices (ej.
        // tomara el playCode de una entrada distinta a la del license), o tomara el senuelo
        // en vez de la primera entrada, el test falla con "LIC-SENUELO".
        val playLiveResponse = JSONObject(
            """{"liveAddressList":[
                {"playCode":"cyx-2EF7E10E40C1ac19D6A9F3ED4CD2","license":"LIC-CORRECTO"},
                {"playCode":"cyx-OTRO","license":"LIC-SENUELO"}
            ]}"""
        )
        val slbResponse = JSONObject(
            """{"cdn_list":[{"tag":"live","url_list":[
                {"sign_type":"cfl","main_addr":"live.example.com","url":"token=ABC"}
            ]}]}"""
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.Ok(playLiveResponse))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbResponse))
        val live = MagisLive(fake, sesionDeTestConCuenta(fake))

        val r = live.resolveChannel("cyx-RCNHD") as MagisResult.Ok

        assertEquals("LIC-CORRECTO", r.data.headers["Content-License"])
        assertEquals("https://live.example.com/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8", r.data.url)
    }

    @Test
    fun `sin cuenta vinculada devuelve error sin llamar al portal`() = runTest {
        val fake = FakePortalClient()
        val live = MagisLive(fake, sesionDeTestSinCuenta(fake))

        live.resolveChannel("cyx-RCNHD")

        assert(fake.llamadas.isEmpty())
    }
}
```

- [x] **Step 2: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisLiveTest"`
Expected: FAIL

- [x] **Step 3: Implementar `MagisLive.kt`**

1. Guard: si `!session.hasAccountLinked`, devolver error propio sin llamar al portal.
2. `v4/startPlayLive` con `{"channelCode": channelCode, "columnId": 0, "type": "1"}` →
   `liveAddressList[]`.
3. **Tomar la PRIMERA entrada de la lista completa** (`playCode` + `license` de esa misma entrada
   — no la busques por separado). Si en el futuro hace falta lógica de selección más fina, que sea
   explícita y siga tomando ambos campos de la misma entrada.
4. `v14/getSlbInfo(type="merge", liveCodeList=[playCode])` → filtrar `cdn_list` con
   `tag == "live"`, `url_list` con `sign_type == "cfl"`. El campo `url` de esa entrada es un
   querystring suelto (puede o no tener el token ya armado — construir el `Content-Auth` a partir
   de eso más un `sign_o3` fresco, ver siguiente punto).
5. `Content-Auth` inicial: usar `Sign2.signO3(token, System.currentTimeMillis())` donde `token`
   sale del `url`/sesión de `getSlbInfo` (el nombre exacto del campo puede variar — inspeccionar la
   respuesta real de `getSlbInfo` en modo `live` con una cuenta vinculada de verdad antes de dar
   esto por cerrado; el spec deja esto marcado como riesgo).
6. `freshAuthHeaders(token)`: recalcula solo el `Content-Auth` con un `Sign2.signO3` nuevo — para
   que quien reproduce (Task 8, wiring con `LiveHlsProxy`) pueda refrescarlo periódicamente sin
   volver a pedir `startPlayLive`/`getSlbInfo` completos.
7. URL final: `"https://$mainAddr/live/$playCode.m3u8"` — **con el `playCode`, no el
   `channelCode` pedido**.
8. Devolver **todos** los CDNs de `cdn_list` filtrados (no solo el primero) para que quien
   reproduce pueda reintentar con el siguiente si el primero da 401 — ajustar `MagisLivePlayable`
   para llevar una lista de candidatos si hace falta, o resolver esto en Task 8 según cómo
   `LiveHlsProxy` ya maneja reintentos hoy (puede que ya tenga su propio mecanismo y no haga falta
   duplicarlo acá — revisar antes de construir algo nuevo).

- [x] **Step 4: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.MagisLiveTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/magis/MagisLive.kt app/src/test/java/com/arkiv/player/data/magis/MagisLiveTest.kt
command git commit -m "feat(light): canal en vivo directo de Magis (playCode/license + firma sign_o3)"
```

---

### Task 7: TMDB directo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/TmdbApi.kt` (o el archivo equivalente
  actual — confirmar el path exacto con `grep -rn "class TmdbApi" app/src/main` antes de empezar,
  puede haber cambiado de ubicación en tareas anteriores del sub-proyecto 1).
- Test: adaptar los tests existentes de `TmdbApi` si los hay (`grep -rn "TmdbApi" app/src/test`).

**Interfaces:** mismas firmas públicas que ya consume la UI hoy (`search`, `searchMulti`, `detail`,
`seasonEpisodes`, `images`, etc.) — solo cambia la implementación interna (base URL + key), no el
contrato.

- [x] **Step 1: Leer el `TmdbApi.kt` actual completo** para confirmar el contrato exacto a
preservar (parámetros, tipos de retorno, manejo de errores).

- [x] **Step 2: Reescribir la construcción de la URL base y la autenticación**

De: `base = "${gatewayUrl()}/v1/catalog/tmdb"` con headers `Authorization`/`X-Arkiv-Device`.
A: `base = "https://api.themoviedb.org/3"`, agregando `api_key=BuildConfig.TMDB_API_KEY` como
query param a cada request (API v3 — no el bearer token v4, para no depender de un segundo
secreto). Sin headers de sesión.

- [x] **Step 3: Compilar y correr los tests existentes de `TmdbApi`**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest --tests "*Tmdb*"`
Expected: BUILD SUCCESSFUL, tests existentes en verde (adaptar si asumían la forma del gateway en
vez de la de TMDB directo — el *shape* de los datos que devuelve TMDB no cambia, solo cómo se
llega a ellos).

- [x] **Step 4: Commit**

```bash
command git add app/src/main/java/com/arkiv/player/data/gateway/TmdbApi.kt
command git commit -m "feat(light): TMDB directo, sin pasar por el gateway"
```

---

### Task 8: Cablear todo (reemplazar el gateway en la app real)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/AppGraph.kt` (wiring de los nuevos componentes,
  quitar `MagisLinkClient`).
- Modify: `app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt` (los métodos
  `vincularMagis*`/`desvincularMagis`/`refrescarMagis` pasan a llamar a `MagisSession` en vez del
  gateway).
- Delete: `app/src/main/java/com/arkiv/player/pocketbase/MagisLinkClient.kt`.
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`loadMagis()` usa
  `MagisResolve` en vez de `ArkivApiClient.resolve()`; `abrirCanalActual()` usa `MagisLive` en vez
  de `LiveApi`/gateway).
- Modify: pantalla(s) de búsqueda que llaman a `SearchViewModel.runSourceSearch()` — cambiar de
  `arkivApiClient.search(sources="magis")` a `MagisCatalog.search()`.
- Modify/Delete: la pantalla de "vincular Magis" (buscar con `grep -rn "vincularMagis" app/src/main/java/com/arkiv/player/ui`) — simplificar a un solo paso email+contraseña, sin el flujo de código/confirmar.
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` — borrar `search()`
  (o dejarlo si trivia lo sigue necesitando de otra forma — confirmar antes de tocar, ver Global
  Constraints), `resolve()`, `episodes()` si eran exclusivos de Magis.
- Delete: `app/src/main/java/com/arkiv/player/data/gateway/LiveApi.kt` (si queda sin otro uso tras
  cablear `MagisLive`).

**Esta tarea es la más grande y la de más riesgo de romper algo que hoy funciona** (Magis VOD +
vivo + búsqueda, todo verificado en dispositivo real al final del sub-proyecto 1). No hay forma de
darla en pasos TDD chicos porque es integración, no lógica nueva — en su lugar:

- [ ] **Step 1: Mapear TODOS los call-sites actuales** de `ArkivApiClient` para Magis (`search`
  acotado a `sources="magis"`, `resolve`, `episodes`) y de `MagisLinkClient`/`AccountManager` para
  el vínculo, y de `LiveApi` para el vivo — `grep -rn` cada uno antes de tocar nada, para no dejar
  ningún caller huérfano.

- [ ] **Step 2: Cablear `AppGraph.kt`** — instanciar `MagisCrypto`, `MagisPortalClient`,
  `MagisSession`, `MagisCatalog`, `MagisResolve`, `MagisLive` con los `BuildConfig.IPTV_*` (Task 1),
  en ese orden de dependencia. Borrar la instanciación de `MagisLinkClient`.

- [ ] **Step 3: Reemplazar cada call-site** encontrado en el Step 1 uno por uno, compilando después
de cada archivo tocado (`./gradlew :app:compileDebugKotlin`).

- [ ] **Step 4: Borrar código que quede sin uso** (`MagisLinkClient.kt`, y `ArkivApiClient`/`LiveApi`
si ya no les queda ningún caller real — confirmar con grep antes de borrar cada uno).

- [ ] **Step 5: Compilar y correr toda la suite**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests en verde.

- [ ] **Step 6: Commit**

```bash
command git add -A  # revisar el diff completo antes de este add dado el tamaño de la tarea
command git commit -m "feat(light): cablear Magis y TMDB directos, sacar el gateway del camino de contenido"
```

---

### Task 9: Verificación en dispositivo real

**Files:** ninguno — solo verificación.

- [ ] **Step 1: Compilar e instalar** (`./gradlew :app:assembleDebug`, `adb install -r ...`) en el
KALLEY R3 u otro dispositivo alcanzable (ver memoria del proyecto para reconectar por ADB
inalámbrico).

- [ ] **Step 2: Sin cuenta vinculada** — confirmar que catálogo/búsqueda/reproducción VOD de Magis
funcionan con la activación anónima (revisar logcat para confirmar que NO hay ninguna llamada a
`api.comparadorinternet.co` durante esto).

- [ ] **Step 3: Vincular una cuenta real** (email+contraseña) y confirmar que el canal en vivo
reproduce con zapeo, igual que al final del sub-proyecto 1 pero ahora sin gateway de por medio.

- [ ] **Step 4: Confirmar TMDB** — carátulas/sinopsis siguen apareciendo, sin llamadas al gateway.

- [ ] **Step 5: Si algo falla, revisar logcat con el mismo método que se usó al final del
sub-proyecto 1** (`adb logcat -d --pid=<pid>`, buscar `FATAL`/`AndroidRuntime`/`PortalError`)
antes de asumir dónde está el problema.

- [ ] **Step 6: Commit final si hizo falta algún arreglo del Step 5.**
