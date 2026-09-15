# Split-Credential Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a release APK with none of the five third-party credentials (Magis's 3DES key, IPTV_HOSTS/APP_ID/APK_VERSION, TMDB API key) compiled into `BuildConfig`; fetch them, split, encrypted, from the public GitHub repo only after explicit user consent, and decrypt/recombine them in native code.

**Architecture:** Each credential is split by character position into a "file half" (shipped inside an AES-GCM-encrypted blob hosted on the latest GitHub release) and a "native half" (baked into a gitignored native library at build time). A new NDK/JNI module does the AES-GCM decrypt, de-obfuscation, interleave-recombine, and a Frida/tracer check, all in native code so JVM-level instrumentation can't simply hook the JVM call and read the result. A new Compose screen gates all navigation behind an explicit "Activar" consent tap on first launch; the existing 6h `UpdateWorker` silently re-applies the blob afterward.

**Tech Stack:** Kotlin/Compose (existing), NDK/JNI + CMake (new), vendored mbedTLS (AES-GCM, SHA-256, base64 -- Apache 2.0, fetched via CMake `FetchContent`), OkHttp (existing conventions), EncryptedSharedPreferences (existing pattern via `EncryptedPrefs`), GitHub Releases (existing OTA infrastructure).

**Spec:** `docs/superpowers/specs/2026-09-15-split-credential-activation-design.md`

## Global Constraints

- No server of the project's own: only the native module (on-device) and GitHub Releases (already-approved third-party host) are involved. No new endpoints, no new hosts.
- Code, identifiers, comments, and KDoc: English (project `CLAUDE.md`). User-facing text (the activation screen's consent copy and states): Bogotá Spanish, tuteo, never voseo.
- Native ABIs: `arm64-v8a` and `armeabi-v7a` only -- matches this app's existing `ndk { abiFilters }` in `app/build.gradle.kts`.
- `app/src/main/cpp/*.cpp` and `app/src/main/cpp/*.h` must NEVER be `git add`ed, not even accidentally -- Task 1 gitignores them before any such file is created, and every later task that touches that directory must leave `git status` showing nothing new there.
- `CMakeLists.txt` inside `app/src/main/cpp/` stays committed and public -- it only says which files to compile, not what's in them.
- The five real credential values only ever exist in plaintext in: the developer's own `.env`, GitHub Secrets, and (transiently, in memory) the two halves being recombined on a real device after activation. They must never be logged, and never appear as a plain compiled-in constant anywhere in `BuildConfig`.
- Two tasks in this plan (13 and 14) are user-action gates for irreversible/visible operations (`gh secret set`, publishing `credentials.enc` to the live public release, cutting a real tag). Do not bundle them into a larger step or perform them without the user's explicit go-ahead immediately before each.

---

## Task 1: Gitignore the native module's real source, before any such file exists

**Files:**
- Modify: `.gitignore`

**Interfaces:**
- Produces: a guarantee, verified by this task, that `app/src/main/cpp/*.cpp` and `app/src/main/cpp/*.h` can never be accidentally committed, while `app/src/main/cpp/CMakeLists.txt` stays trackable. Every later task that creates files under `app/src/main/cpp/` depends on this.

- [ ] **Step 1: Add the gitignore entries**

Add this block to `.gitignore` (placement: anywhere after the existing `# Secrets` block is fine; keep it as its own labeled section):

```gitignore
# Native module (app/src/main/cpp/): the algorithm that resolves split third-party credentials
# at runtime. Gitignored on purpose -- see
# docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Native module source
# stays out of the public repo". CMakeLists.txt (which only says which files to compile, not what's
# in them) is NOT ignored and stays public.
app/src/main/cpp/*.cpp
app/src/main/cpp/*.h
```

- [ ] **Step 2: Verify the ignore rule works for files that don't exist yet**

Run:

```bash
mkdir -p app/src/main/cpp
git check-ignore -v app/src/main/cpp/native_credentials.cpp
git check-ignore -v app/src/main/cpp/native_credentials.h
git check-ignore -v app/src/main/cpp/CMakeLists.txt; echo "exit=$?"
```

Expected: the first two commands each print a line naming `.gitignore` and the pattern that matched. The third command prints nothing and `exit=1` (not ignored).

- [ ] **Step 3: Verify a real file in that directory is invisible to git**

```bash
touch app/src/main/cpp/scratch.cpp
git status --porcelain app/src/main/cpp/
rm app/src/main/cpp/scratch.cpp
```

Expected: `git status --porcelain` prints nothing.

- [ ] **Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore: gitignore the native credential module's real source"
```

---

## Task 2: Interleave split/combine algorithm (pure Kotlin, TDD)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt`
- Test: `app/src/test/java/com/arkiv/player/data/credentials/CredentialSplitTest.kt`

**Interfaces:**
- Produces: `object CredentialSplit { fun split(value: String): Pair<String, String>; fun combine(fileHalf: String, nativeHalf: String): String }`. Task 6's Gradle codegen duplicates the `split` rule (documented cross-reference); Task 7's native module re-implements `combine` in C++ against the identical rule.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.arkiv.player.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialSplitTest {
    @Test fun `splits an even-length value into equal halves`() {
        val (file, native) = CredentialSplit.split("ABCDEF")
        assertEquals("ACE", file)
        assertEquals("BDF", native)
    }

    @Test fun `splits an odd-length value with the file half one character longer`() {
        val (file, native) = CredentialSplit.split("ABCDE")
        assertEquals("ACE", file)
        assertEquals("BD", native)
    }

    @Test fun `splitting an empty string gives two empty halves`() {
        val (file, native) = CredentialSplit.split("")
        assertEquals("", file)
        assertEquals("", native)
    }

    @Test fun `a single character goes entirely to the file half`() {
        val (file, native) = CredentialSplit.split("A")
        assertEquals("A", file)
        assertEquals("", native)
    }

    @Test fun `combine reverses split for an even-length value`() {
        val (file, native) = CredentialSplit.split("ABCDEF")
        assertEquals("ABCDEF", CredentialSplit.combine(file, native))
    }

    @Test fun `combine reverses split for an odd-length value`() {
        val (file, native) = CredentialSplit.split("ABCDE")
        assertEquals("ABCDE", CredentialSplit.combine(file, native))
    }

    @Test fun `combine reverses split for a realistic hex key`() {
        val key = "5f3a9c1e7b2d4468091acaffe12300de45ab6c7"
        val (file, native) = CredentialSplit.split(key)
        assertEquals(key, CredentialSplit.combine(file, native))
    }

    @Test fun `combine rejects halves whose lengths cannot come from a valid split`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialSplit.combine("A", "XY")
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.credentials.CredentialSplitTest"`
Expected: FAIL -- `CredentialSplit` is unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.arkiv.player.data.credentials

/**
 * Splits a credential's characters by position into two halves -- one to ship inside the
 * downloaded encrypted blob, one to bake into the native library at build time -- so neither half
 * alone is a complete, usable value. See
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Data flow: the
 * interleave split/combine".
 *
 * `app/build.gradle.kts`'s `generateNativeSecretsHeader` task duplicates [split]'s rule at build
 * time (a plain Gradle script here can't import this class), and the native module
 * (`app/src/main/cpp`, gitignored) re-implements [combine] in C++. If this rule ever changes,
 * update all three.
 */
object CredentialSplit {
    /** Even indices (0, 2, 4, ...) go to the file half; odd indices go to the native half. */
    fun split(value: String): Pair<String, String> {
        val fileHalf = StringBuilder()
        val nativeHalf = StringBuilder()
        for (i in value.indices) {
            if (i % 2 == 0) fileHalf.append(value[i]) else nativeHalf.append(value[i])
        }
        return fileHalf.toString() to nativeHalf.toString()
    }

    /**
     * Reassembles [split]'s output. `fileHalf` must be exactly as long as `nativeHalf`, or exactly
     * one character longer (when the original value's length was odd).
     */
    fun combine(fileHalf: String, nativeHalf: String): String {
        require(fileHalf.length == nativeHalf.length || fileHalf.length == nativeHalf.length + 1) {
            "fileHalf (${fileHalf.length}) and nativeHalf (${nativeHalf.length}) lengths don't match a valid split"
        }
        val result = StringBuilder()
        for (i in fileHalf.indices) {
            result.append(fileHalf[i])
            if (i < nativeHalf.length) result.append(nativeHalf[i])
        }
        return result.toString()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.credentials.CredentialSplitTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt \
        app/src/test/java/com/arkiv/player/data/credentials/CredentialSplitTest.kt
git commit -m "feat: add the interleave split/combine algorithm for split credentials"
```

---

## Task 3: RemoteCredentials data class + RemoteCredentialsStore

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/credentials/RemoteCredentials.kt`
- Create: `app/src/main/java/com/arkiv/player/data/credentials/RemoteCredentialsStore.kt`

**Interfaces:**
- Consumes: `com.arkiv.player.data.magis.EncryptedPrefs.openOrRepair` (existing, `internal`, same Gradle module so visible across packages).
- Produces: `data class RemoteCredentials(iptv3desKey: String, iptvHosts: String, iptvAppId: String, iptvApkVersion: String, tmdbApiKey: String)`; `interface RemoteCredentialsStore { fun read(): RemoteCredentials?; fun save(credentials: RemoteCredentials); fun clear() }`; `class EncryptedRemoteCredentialsStore(context: Context) : RemoteCredentialsStore`. Task 8 (UI), Task 9 (AppGraph wiring), and Task 10 (UpdateWorker) all consume `RemoteCredentialsStore`.

- [ ] **Step 1: Create the data class**

```kotlin
package com.arkiv.player.data.credentials

/** The five third-party credentials this app needs, once downloaded, decrypted and recombined. */
data class RemoteCredentials(
    val iptv3desKey: String,
    val iptvHosts: String,
    val iptvAppId: String,
    val iptvApkVersion: String,
    val tmdbApiKey: String,
)
```

- [ ] **Step 2: Create the store**

```kotlin
package com.arkiv.player.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.data.magis.EncryptedPrefs

interface RemoteCredentialsStore {
    fun read(): RemoteCredentials?
    fun save(credentials: RemoteCredentials)
    fun clear()
}

/**
 * Where the five downloaded-and-recombined third-party credentials live on the device, once
 * activation succeeds. Own preferences file, separate from
 * [com.arkiv.player.data.magis.MagisCredentialStore]: a different concern (app-level third-party
 * secrets vs. a per-device Magis session). Same [EncryptedPrefs.openOrRepair] pattern: a Keystore
 * that can no longer decrypt this file must not be able to brick the app -- it's treated the same
 * as "never activated", so the activation screen reappears and the person re-consents once.
 */
class EncryptedRemoteCredentialsStore(context: Context) : RemoteCredentialsStore {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        EncryptedPrefs.openOrRepair(
            create = { encrypted(app) },
            discardUndecryptable = {
                Log.w(TAG, "Credentials prefs undecryptable: starting fresh (need to re-activate)")
                runCatching { app.deleteSharedPreferences(PREFS) }
            },
            unencrypted = {
                Log.e(TAG, "the Keystore won't even work freshly thrown: credentials left UNENCRYPTED")
                app.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            },
        )
    }

    override fun read(): RemoteCredentials? {
        val key = prefs.getString(K_3DES_KEY, null) ?: return null
        return RemoteCredentials(
            iptv3desKey = key,
            iptvHosts = prefs.getString(K_HOSTS, null).orEmpty(),
            iptvAppId = prefs.getString(K_APP_ID, null).orEmpty(),
            iptvApkVersion = prefs.getString(K_APK_VERSION, null).orEmpty(),
            tmdbApiKey = prefs.getString(K_TMDB_KEY, null).orEmpty(),
        )
    }

    override fun save(credentials: RemoteCredentials) {
        prefs.edit()
            .putString(K_3DES_KEY, credentials.iptv3desKey)
            .putString(K_HOSTS, credentials.iptvHosts)
            .putString(K_APP_ID, credentials.iptvAppId)
            .putString(K_APK_VERSION, credentials.iptvApkVersion)
            .putString(K_TMDB_KEY, credentials.tmdbApiKey)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "CredentialsStore"
        const val PREFS = "arkiv_credentials_secure"

        /** Only if the Keystore is broken at the root. See [EncryptedPrefs]. */
        const val PREFS_PLAIN = "arkiv_credentials_plano"

        const val K_3DES_KEY = "iptv3desKey"
        const val K_HOSTS = "iptvHosts"
        const val K_APP_ID = "iptvAppId"
        const val K_APK_VERSION = "iptvApkVersion"
        const val K_TMDB_KEY = "tmdbApiKey"

        fun encrypted(app: Context): SharedPreferences = EncryptedSharedPreferences.create(
            app,
            PREFS,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
```

No dedicated test file for `EncryptedRemoteCredentialsStore` itself: this matches
`EncryptedMagisCredentialStore`'s own precedent in this codebase (zero direct tests) -- the
`openOrRepair` logic it delegates to is already fully covered by `EncryptedPrefsTest`, and
`EncryptedSharedPreferences` itself needs a real Android Keystore, unavailable on the JVM test
runner this project uses (no Robolectric).

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/credentials/RemoteCredentials.kt \
        app/src/main/java/com/arkiv/player/data/credentials/RemoteCredentialsStore.kt
git commit -m "feat: add RemoteCredentials and its encrypted on-device store"
```

---

## Task 4: Native build wiring (CMake + Gradle) and local NDK derisking

This task resolves the spec's flagged open risk ("is the NDK available on GitHub Actions'
`ubuntu-latest` runners?") concretely: rather than merely checking, Task 11 will install NDK and
CMake explicitly and unconditionally in CI, so the question never matters. This task proves the
Gradle/CMake wiring itself is correct, locally, with a throwaway placeholder file that is deleted
before this task ends (the real algorithm file is Task 7's, gitignored, authored later).

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: a working `externalNativeBuild` pointed at `app/src/main/cpp/CMakeLists.txt`, building a shared library named `credentials`. Task 7 adds the real source file and a crypto dependency to this same `CMakeLists.txt`.

- [ ] **Step 1: Create the CMake build file**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(credentials)

add_library(credentials SHARED native_credentials.cpp)

find_library(log-lib log)

target_link_libraries(credentials ${log-lib})
```

- [ ] **Step 2: Wire it into `app/build.gradle.kts`**

Add `ndkVersion` and a `defaultConfig { externalNativeBuild { cmake { ... } } }` block, plus a
top-level `android { externalNativeBuild { ... } }` block. In `app/build.gradle.kts`, inside the
`android { ... }` block, right after `compileSdk = 35`:

```kotlin
    compileSdk = 35
    ndkVersion = "26.1.10909125"
```

Inside `defaultConfig { ... }`, right after the existing `ndk { abiFilters += ... }` block:

```kotlin
        ndk {
            // Only real-device ABIs (phone arm64, Fire Stick armeabi-v7a).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
```

And as a new top-level block inside `android { ... }` (sibling of `defaultConfig`, `buildTypes`,
etc. -- placement inside the file doesn't matter, put it after `buildTypes { ... }`):

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 3: Prove the wiring compiles, with a throwaway placeholder**

This file is gitignored (Task 1) and is deleted at the end of this step -- it exists only to prove
the Gradle/CMake/NDK toolchain works before Task 7 writes the real algorithm.

Create `app/src/main/cpp/native_credentials.cpp`:

```cpp
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptv3desKey(
        JNIEnv *env, jobject /* thiz */, jbyteArray /* blob */) {
    return env->NewStringUTF("placeholder");
}
```

Run:

```bash
./gradlew :app:assembleDebug
find app/build -name "libcredentials.so"
```

Expected: `BUILD SUCCESSFUL`, and at least one `libcredentials.so` printed (one per ABI: `arm64-v8a`,
`armeabi-v7a`).

- [ ] **Step 4: Remove the placeholder**

```bash
rm app/src/main/cpp/native_credentials.cpp
git status --porcelain app/src/main/cpp/
```

Expected: no output (the `.cpp` was gitignored, never staged).

- [ ] **Step 5: Commit the Gradle/CMake wiring**

```bash
git add app/build.gradle.kts app/src/main/cpp/CMakeLists.txt
git commit -m "feat: wire an NDK/CMake native module into the Gradle build"
```

---

## Task 5: NativeCredentialResolver (JNI bridge) + CredentialsActivator

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/credentials/NativeCredentialResolver.kt`
- Create: `app/src/main/java/com/arkiv/player/data/credentials/CredentialsActivator.kt`
- Test: `app/src/test/java/com/arkiv/player/data/credentials/CredentialsActivatorTest.kt`

**Interfaces:**
- Consumes: `RemoteCredentials` (Task 3).
- Produces: `internal object NativeCredentialResolver` with five `external fun resolve*(blob: ByteArray): String` functions (exact names and JNI symbol shape Task 7's native module must match exactly); `class CredentialsActivator(client: OkHttpClient, blobUrl: String = DEFAULT_BLOB_URL, resolveIptv3desKey: (ByteArray) -> String = ..., ...) { suspend fun activate(): RemoteCredentials? }`. Task 8 (UI) and Task 10 (UpdateWorker) call `CredentialsActivator.activate()`.

- [ ] **Step 1: Create the JNI bridge (public, reveals nothing about the algorithm)**

```kotlin
package com.arkiv.player.data.credentials

/**
 * JNI bridge into the native module (`app/src/main/cpp`, gitignored -- see
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Native module source
 * stays out of the public repo"). Declaring these five function names here is public; what each
 * one actually does (AES-GCM decrypt, de-obfuscate, interleave-combine, refuse under
 * instrumentation) is not.
 */
internal object NativeCredentialResolver {
    init { System.loadLibrary("credentials") }

    /**
     * Each function takes the raw bytes of the downloaded `credentials.enc` and returns that
     * one field's fully combined value, or an empty string if decryption or the
     * anti-instrumentation check fails.
     */
    external fun resolveIptv3desKey(blob: ByteArray): String
    external fun resolveIptvHosts(blob: ByteArray): String
    external fun resolveIptvAppId(blob: ByteArray): String
    external fun resolveIptvApkVersion(blob: ByteArray): String
    external fun resolveTmdbApiKey(blob: ByteArray): String
}
```

- [ ] **Step 2: Write the failing tests for `CredentialsActivator`**

```kotlin
package com.arkiv.player.data.credentials

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CredentialsActivatorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun activator(
        resolve3des: (ByteArray) -> String = { "3desKey" },
        resolveHosts: (ByteArray) -> String = { "host1.com,host2.com" },
        resolveAppId: (ByteArray) -> String = { "appId" },
        resolveApkVersion: (ByteArray) -> String = { "1.2.3" },
        resolveTmdb: (ByteArray) -> String = { "tmdbKey" },
    ) = CredentialsActivator(
        client = OkHttpClient(),
        blobUrl = server.url("/credentials.enc").toString(),
        resolveIptv3desKey = resolve3des,
        resolveIptvHosts = resolveHosts,
        resolveIptvAppId = resolveAppId,
        resolveIptvApkVersion = resolveApkVersion,
        resolveTmdbApiKey = resolveTmdb,
    )

    @Test fun `downloads the blob and combines the five resolved fields`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val result = activator().activate()
        assertEquals(RemoteCredentials("3desKey", "host1.com,host2.com", "appId", "1.2.3", "tmdbKey"), result)
    }

    @Test fun `returns null when the download fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(activator().activate())
    }

    @Test fun `returns null when any resolver comes back empty`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertNull(activator(resolve3des = { "" }).activate())
    }

    @Test fun `passes the raw downloaded bytes to every resolver`() = runBlocking {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        var seen: ByteArray? = null
        activator(resolve3des = { seen = it; "k" }).activate()
        assertEquals(bytes.toList(), seen!!.toList())
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.credentials.CredentialsActivatorTest"`
Expected: FAIL -- `CredentialsActivator` is unresolved.

- [ ] **Step 4: Implement `CredentialsActivator`**

```kotlin
package com.arkiv.player.data.credentials

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the encrypted credentials blob and calls the five native resolvers to get back
 * complete, usable values. Used both by the explicit "Activar" tap and by
 * [com.arkiv.player.data.update.UpdateWorker]'s silent periodic refresh.
 *
 * The five resolver functions are constructor parameters (defaulting to the real native ones) so
 * this class is testable with MockWebServer on the JVM, the same shape
 * [com.arkiv.player.data.update.UpdateChecker] already uses for its `client`: the real native
 * library isn't available on the JVM test runner.
 */
class CredentialsActivator(
    private val client: OkHttpClient,
    private val blobUrl: String = DEFAULT_BLOB_URL,
    private val resolveIptv3desKey: (ByteArray) -> String = NativeCredentialResolver::resolveIptv3desKey,
    private val resolveIptvHosts: (ByteArray) -> String = NativeCredentialResolver::resolveIptvHosts,
    private val resolveIptvAppId: (ByteArray) -> String = NativeCredentialResolver::resolveIptvAppId,
    private val resolveIptvApkVersion: (ByteArray) -> String = NativeCredentialResolver::resolveIptvApkVersion,
    private val resolveTmdbApiKey: (ByteArray) -> String = NativeCredentialResolver::resolveTmdbApiKey,
) {
    companion object {
        const val DEFAULT_BLOB_URL = "https://github.com/lordmacu/kino-light/releases/latest/download/credentials.enc"
    }

    /**
     * Null on any failure: no network, a bad download, a decrypt/combine failure, or the native
     * anti-instrumentation check tripping -- all indistinguishable on purpose (see the spec's
     * Error Handling section: telling an attacker exactly which defense caught them only helps
     * them route around it next time).
     */
    suspend fun activate(): RemoteCredentials? = withContext(Dispatchers.IO) {
        runCatching {
            val blob = client.newCall(
                Request.Builder().url(blobUrl).cacheControl(CacheControl.FORCE_NETWORK).build(),
            ).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@withContext null

            val key = resolveIptv3desKey(blob)
            val hosts = resolveIptvHosts(blob)
            val appId = resolveIptvAppId(blob)
            val apkVersion = resolveIptvApkVersion(blob)
            val tmdbKey = resolveTmdbApiKey(blob)
            if (key.isEmpty() || hosts.isEmpty() || appId.isEmpty() || apkVersion.isEmpty() || tmdbKey.isEmpty()) {
                return@withContext null
            }
            RemoteCredentials(key, hosts, appId, apkVersion, tmdbKey)
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.credentials.CredentialsActivatorTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/credentials/NativeCredentialResolver.kt \
        app/src/main/java/com/arkiv/player/data/credentials/CredentialsActivator.kt \
        app/src/test/java/com/arkiv/player/data/credentials/CredentialsActivatorTest.kt
git commit -m "feat: add the JNI bridge and CredentialsActivator orchestration"
```

---

## Task 6: Gradle codegen for the native module's generated header

Generates `app/src/main/cpp/generated_secrets.h` (gitignored -- its *content* varies per build)
from `.env`/secrets before the native build compiles: the AES blob key and the five native halves,
each XOR-masked (see "String obfuscation" in the spec).

**Design ruling on the XOR mask (recorded here since the spec didn't fully resolve it):** the mask
value is a single fixed constant, hardcoded identically in this Gradle script AND in Task 7's
`native_credentials.cpp`. This is not a weaker design than deriving the mask from a secret: the
native module *must* embed the mask to de-obfuscate at runtime, so anyone who disassembles the
compiled `.so` finds the mask right there regardless of whether `build.gradle.kts` (public, in git)
also shows it. The mask's only real job -- defeating a naive `strings`/grep scan of the compiled
binary -- works the same either way. Adding a `NATIVE_XOR_MASK` secret to hide a value that's
necessarily embedded in the shipped binary anyway would add ceremony without adding protection.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.env.example`

**Interfaces:**
- Consumes: `readEnv()` (existing helper in `app/build.gradle.kts`).
- Produces: `app/src/main/cpp/generated_secrets.h` with `GEN_BLOB_KEY`/`GEN_BLOB_KEY_LEN`,
  `GEN_NATIVE_3DES`/`_LEN`, `GEN_NATIVE_HOSTS`/`_LEN`, `GEN_NATIVE_APP_ID`/`_LEN`,
  `GEN_NATIVE_APK_VERSION`/`_LEN`, `GEN_NATIVE_TMDB`/`_LEN` -- exact names Task 7's
  `native_credentials.cpp` includes and reads. Generation is wired as a dependency of every CMake
  task, so it always runs before native compilation.

- [ ] **Step 1: Add the new `.env` key**

In `.env.example`, add a new section (English comment, matching this file's newest section's
convention):

```
# --- Split-credential activation (see docs/superpowers/specs/2026-09-15-split-credential-activation-design.md) ---
# The AES-256-GCM key protecting credentials.enc. Generate a fresh one with:
#   openssl rand -hex 32
# Needed both to build the app (bakes the de-obfuscation half in) and to run
# scripts/publish-credentials-blob.sh (encrypts with it).
CREDENTIALS_BLOB_KEY=
```

- [ ] **Step 2: Add the codegen task to `app/build.gradle.kts`**

Add near the top of the file, after the existing `readEnv` function:

```kotlin
/**
 * Fixed XOR mask for embedded native constants (see "String obfuscation of the embedded
 * constants" in docs/superpowers/specs/2026-09-15-split-credential-activation-design.md).
 * Hardcoded here AND in native_credentials.cpp's identical `kObfuscationMask` -- not a secret:
 * whoever disassembles the compiled .so finds this same constant there too, since the native
 * module needs it to de-obfuscate at runtime. Its only job is defeating a naive `strings`/grep
 * scan of the compiled binary, which it does regardless of whether this file (public, in git)
 * also shows it.
 */
val nativeObfuscationMask = byteArrayOf(0x5A, 0x3C, 0x91.toByte(), 0x0F, 0x7E, 0x22, 0xC8.toByte(), 0x64)

fun xorMask(bytes: ByteArray): ByteArray =
    ByteArray(bytes.size) { i -> (bytes[i].toInt() xor nativeObfuscationMask[i % nativeObfuscationMask.size].toInt()).toByte() }

/**
 * Same rule as `CredentialSplit.split` (app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt)
 * -- duplicated here because this Gradle script has no `buildSrc` to share code with the app
 * module. If the split rule ever changes, update both.
 */
fun nativeHalf(value: String): String {
    val sb = StringBuilder()
    for (i in value.indices) if (i % 2 != 0) sb.append(value[i])
    return sb.toString()
}
```

Then, still at the top level of the file (outside `android { ... }`):

```kotlin
val generateNativeSecretsHeader = tasks.register("generateNativeSecretsHeader") {
    doLast {
        val cppDir = file("src/main/cpp")
        cppDir.mkdirs()

        fun maskedLiteral(value: String): String =
            xorMask(value.toByteArray(Charsets.UTF_8)).joinToString(", ") { "0x%02x".format(it) }

        fun field(name: String, value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return "static const unsigned char $name[] = { ${maskedLiteral(value)} };\n" +
                "static const int ${name}_LEN = ${bytes.size};\n"
        }

        val content = buildString {
            appendLine("// GENERATED -- do not edit by hand, never committed (see .gitignore).")
            appendLine("// Regenerated by the generateNativeSecretsHeader Gradle task before every native build.")
            appendLine("// Every array here is XOR-masked against native_credentials.cpp's kObfuscationMask --")
            appendLine("// none of these bytes are the real value until de-obfuscated at resolve time.")
            appendLine("#pragma once")
            append(field("GEN_BLOB_KEY", readEnv("CREDENTIALS_BLOB_KEY")))
            append(field("GEN_NATIVE_3DES", nativeHalf(readEnv("IPTV_3DES_KEY"))))
            append(field("GEN_NATIVE_HOSTS", nativeHalf(readEnv("IPTV_HOSTS"))))
            append(field("GEN_NATIVE_APP_ID", nativeHalf(readEnv("IPTV_APP_ID"))))
            append(field("GEN_NATIVE_APK_VERSION", nativeHalf(readEnv("IPTV_APK_VERSION"))))
            append(field("GEN_NATIVE_TMDB", nativeHalf(readEnv("API_KEY"))))
        }
        file("src/main/cpp/generated_secrets.h").writeText(content)
    }
}

tasks.matching { it.name.contains("CMake") }.configureEach {
    dependsOn(generateNativeSecretsHeader)
}
```

- [ ] **Step 3: Verify with known scratch values**

```bash
cp .env .env.bak 2>/dev/null || true
cat >> .env <<'EOF'
CREDENTIALS_BLOB_KEY=testkey123
EOF
./gradlew generateNativeSecretsHeader
command cat app/src/main/cpp/generated_secrets.h
```

Expected: a file with `GEN_BLOB_KEY[]` holding exactly 10 byte literals (`testkey123` is 10
characters). Spot-check the first byte by hand: `'t'` is `0x74`; XORed with the mask's first byte
`0x5A` gives `0x2e`. Confirm `GEN_BLOB_KEY[0]` is `0x2e`.

- [ ] **Step 4: Clean up the scratch value**

```bash
mv .env.bak .env 2>/dev/null || command grep -v '^CREDENTIALS_BLOB_KEY=testkey123$' .env > .env.tmp && mv .env.tmp .env
rm -f app/src/main/cpp/generated_secrets.h
```

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .env.example
git commit -m "feat: generate the native module's obfuscated secrets header at build time"
```

---

## Task 7: The native algorithm -- AES-GCM decrypt, anti-instrumentation check, JNI functions

**This is the file the spec calls "the magic": gitignored, never committed, and the one piece of
this whole feature no future session can read back from git.** At the end of this task, its full
content (both files below) must be handed to the user, verbatim, to store somewhere durable
outside git (a password manager note, a local backup) -- the working directory alone is not
enough, since `git clean -fdx` or a fresh clone loses it silently. Any future change to this file
requires the user pasting its current content back first, or making the change themselves.

**Design ruling on the crypto primitive:** hand-writing AES-GCM from scratch in this plan is the
kind of exacting, easy-to-get-subtly-wrong code this project should not carry unreviewed. Vendor
**mbedTLS** (Apache 2.0, a small, well-established embedded/mobile crypto library) via CMake
`FetchContent`, using only its `mbedcrypto` target (AES-GCM, SHA-256, base64 -- no TLS, no X.509).
This is a real, permissively-licensed, generic dependency, not part of "the algorithm that must
stay hidden" -- it could be committed to `CMakeLists.txt` in the clear with no loss of the
protection this feature provides.

**Blob wire format** (produced by Task 12's publishing script, consumed here): `IV (12 bytes) ||
AES-256-GCM ciphertext || tag (16 bytes)`. The AES-256 key is `SHA-256(the de-obfuscated blob key
bytes)` -- hashing removes any constraint on the human-facing `CREDENTIALS_BLOB_KEY` secret's exact
length. The decrypted plaintext is a small, fixed-shape JSON object with the five base64-encoded
file-halves: `{"3des":"<b64>","hosts":"<b64>","appId":"<b64>","apkVersion":"<b64>","tmdb":"<b64>"}`
-- a full JSON parser is unnecessary for one fixed shape this same codebase's publishing script
also produces; a tiny fixed-key extractor is enough.

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Create (gitignored): `app/src/main/cpp/native_credentials.h`
- Create (gitignored): `app/src/main/cpp/native_credentials.cpp`

**Interfaces:**
- Consumes: `app/src/main/cpp/generated_secrets.h` (Task 6's codegen output: `GEN_BLOB_KEY`,
  `GEN_NATIVE_3DES`, `GEN_NATIVE_HOSTS`, `GEN_NATIVE_APP_ID`, `GEN_NATIVE_APK_VERSION`,
  `GEN_NATIVE_TMDB`, each with a matching `_LEN`).
- Produces: the five JNI functions `NativeCredentialResolver` (Task 5) declares as `external`.

- [ ] **Step 1: Add mbedTLS to `CMakeLists.txt`**

Replace `app/src/main/cpp/CMakeLists.txt`'s content with:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(credentials)

include(FetchContent)
FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG v3.6.2
)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mbedtls)

add_library(credentials SHARED native_credentials.cpp)

find_library(log-lib log)

target_link_libraries(credentials mbedcrypto ${log-lib})
```

- [ ] **Step 2: Write the JNI header**

```cpp
#pragma once
#include <jni.h>

extern "C" {
JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptv3desKey(
    JNIEnv *env, jobject thiz, jbyteArray blob);
JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvHosts(
    JNIEnv *env, jobject thiz, jbyteArray blob);
JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvAppId(
    JNIEnv *env, jobject thiz, jbyteArray blob);
JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvApkVersion(
    JNIEnv *env, jobject thiz, jbyteArray blob);
JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveTmdbApiKey(
    JNIEnv *env, jobject thiz, jbyteArray blob);
}
```

- [ ] **Step 3: Write the algorithm**

```cpp
#include "native_credentials.h"
#include "generated_secrets.h"

#include <mbedtls/gcm.h>
#include <mbedtls/sha256.h>
#include <mbedtls/base64.h>

#include <sys/ptrace.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

// Same mask as build.gradle.kts's nativeObfuscationMask -- see that file's comment for why this
// is not a secret: whoever disassembles this .so finds this same constant right here.
static const unsigned char kObfuscationMask[] = {0x5A, 0x3C, 0x91, 0x0F, 0x7E, 0x22, 0xC8, 0x64};

static std::string deobfuscate(const unsigned char *masked, int len) {
    std::string out;
    out.resize(len);
    for (int i = 0; i < len; i++) {
        out[i] = (char) (masked[i] ^ kObfuscationMask[i % sizeof(kObfuscationMask)]);
    }
    return out;
}

// --- Anti-instrumentation: signals 1-4 must positively and cleanly detect instrumentation to
// count. An unreadable/ambiguous signal reads as "not detected" -- see the spec's fail-safe rule.

// Kill switch: same shape as MainActivity's BLOCK_ON_ROOT. If a real device ever false-positives,
// flip this to false -- the detection code stays exactly as built and tested.
static const bool kBlockOnInstrumentation = true;

static bool signalFridaPort() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false; // can't even open a socket: ambiguous, not detected
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    bool connected = connect(sock, (sockaddr *) &addr, sizeof(addr)) == 0;
    close(sock);
    return connected; // a clean refusal/timeout is "not detected", not ambiguous
}

static bool signalMapsHasFrida() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return false; // unreadable: not detected, per the fail-safe rule
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("frida") != std::string::npos) return true;
    }
    return false;
}

static bool signalTracerPid() {
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) return false; // unreadable: not detected
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            std::istringstream iss(line.substr(strlen("TracerPid:")));
            long pid = -1;
            if (!(iss >> pid)) return false; // couldn't parse: ambiguous, not detected
            return pid != 0;
        }
    }
    return false; // line missing entirely: ambiguous, not detected
}

static bool signalSelfTraceFails() {
    // A process can only ever have one tracer. If Frida (or anything else) is already attached,
    // this call fails -- but a permission error unrelated to tracing must not read as "detected".
    errno = 0;
    long result = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
    if (result == 0) {
        ptrace(PTRACE_DETACH, 0, nullptr, nullptr); // undo: this thread must stay traceable normally
        return false;
    }
    return errno == EPERM; // EPERM specifically means "already traced"; anything else is ambiguous
}

/**
 * Combination rule: any one of signals 1-4 alone is enough to refuse. Signal 5 (timing, not
 * separately broken out here) is never a sole trigger -- see the spec's "Anti-instrumentation
 * check" section. Since each of 1-4 above already reads its own ambiguous case as "false" (the
 * fail-safe rule), there is nothing left for a timing tie-breaker to resolve in this
 * implementation -- it's the union of four clean, positive signals, with no separate ambiguous
 * bucket to break a tie on.
 */
static bool isInstrumented() {
    return signalFridaPort() || signalMapsHasFrida() || signalTracerPid() || signalSelfTraceFails();
}

static bool shouldRefuse() {
    return kBlockOnInstrumentation && isInstrumented();
}

// --- AES-256-GCM decrypt: blob = IV(12) || ciphertext || tag(16). Key = SHA-256 of the
// de-obfuscated blob key (so the human-facing secret can be any length).

static bool decryptBlob(const unsigned char *blob, size_t blobLen, std::string &plaintextOut) {
    if (blobLen < 12 + 16) return false;
    std::string rawKey = deobfuscate(GEN_BLOB_KEY, GEN_BLOB_KEY_LEN);

    unsigned char key[32];
    mbedtls_sha256((const unsigned char *) rawKey.data(), rawKey.size(), key, 0);

    const unsigned char *iv = blob;
    const unsigned char *ciphertext = blob + 12;
    size_t ciphertextLen = blobLen - 12 - 16;
    const unsigned char *tag = blob + 12 + ciphertextLen;

    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }

    std::vector<unsigned char> plain(ciphertextLen);
    int ret = mbedtls_gcm_auth_decrypt(
        &ctx, ciphertextLen, iv, 12, nullptr, 0, tag, 16, ciphertext, plain.data());
    mbedtls_gcm_free(&ctx);
    if (ret != 0) return false;

    plaintextOut.assign((char *) plain.data(), plain.size());
    return true;
}

// --- Tiny fixed-shape JSON field extractor: the blob decrypts to exactly
// {"3des":"<b64>","hosts":"<b64>","appId":"<b64>","apkVersion":"<b64>","tmdb":"<b64>"} -- a full
// JSON parser is unnecessary for one fixed, known shape we also control the producer of.

static bool extractField(const std::string &json, const std::string &key, std::string &out) {
    std::string needle = "\"" + key + "\":\"";
    size_t start = json.find(needle);
    if (start == std::string::npos) return false;
    start += needle.size();
    size_t end = json.find('"', start);
    if (end == std::string::npos) return false;
    out = json.substr(start, end - start);
    return true;
}

static bool base64Decode(const std::string &in, std::string &out) {
    size_t decodedLen = 0;
    mbedtls_base64_decode(nullptr, 0, &decodedLen, (const unsigned char *) in.data(), in.size());
    if (decodedLen == 0) return false;
    std::vector<unsigned char> buf(decodedLen);
    if (mbedtls_base64_decode(buf.data(), buf.size(), &decodedLen,
                               (const unsigned char *) in.data(), in.size()) != 0) {
        return false;
    }
    out.assign((char *) buf.data(), decodedLen);
    return true;
}

/**
 * The shared resolve pipeline every JNI entry point runs: check instrumentation, decrypt the
 * blob, pull this field's base64 file-half out of the JSON, decode it, de-obfuscate this field's
 * native half from the generated header, and interleave them back together (mirrors
 * `CredentialSplit.combine` in `app/src/main/java/com/arkiv/player/data/credentials/CredentialSplit.kt`).
 */
static std::string resolve(
    jbyteArray blobArray, JNIEnv *env, const char *jsonKey,
    const unsigned char *nativeHalfMasked, int nativeHalfLen) {
    if (shouldRefuse()) return "";

    jsize len = env->GetArrayLength(blobArray);
    std::vector<unsigned char> blob(len);
    env->GetByteArrayRegion(blobArray, 0, len, (jbyte *) blob.data());

    std::string json;
    if (!decryptBlob(blob.data(), blob.size(), json)) return "";

    std::string fileHalfB64;
    if (!extractField(json, jsonKey, fileHalfB64)) return "";

    std::string fileHalf;
    if (!base64Decode(fileHalfB64, fileHalf)) return "";

    std::string nativeHalf = deobfuscate(nativeHalfMasked, nativeHalfLen);

    // Interleave: result[0]=fileHalf[0], result[1]=nativeHalf[0], result[2]=fileHalf[1], ...
    std::string result;
    for (size_t i = 0; i < fileHalf.size(); i++) {
        result += fileHalf[i];
        if (i < nativeHalf.size()) result += nativeHalf[i];
    }
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptv3desKey(
    JNIEnv *env, jobject, jbyteArray blob) {
    return env->NewStringUTF(resolve(blob, env, "3des", GEN_NATIVE_3DES, GEN_NATIVE_3DES_LEN).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvHosts(
    JNIEnv *env, jobject, jbyteArray blob) {
    return env->NewStringUTF(resolve(blob, env, "hosts", GEN_NATIVE_HOSTS, GEN_NATIVE_HOSTS_LEN).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvAppId(
    JNIEnv *env, jobject, jbyteArray blob) {
    return env->NewStringUTF(resolve(blob, env, "appId", GEN_NATIVE_APP_ID, GEN_NATIVE_APP_ID_LEN).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveIptvApkVersion(
    JNIEnv *env, jobject, jbyteArray blob) {
    return env->NewStringUTF(
        resolve(blob, env, "apkVersion", GEN_NATIVE_APK_VERSION, GEN_NATIVE_APK_VERSION_LEN).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_arkiv_player_data_credentials_NativeCredentialResolver_resolveTmdbApiKey(
    JNIEnv *env, jobject, jbyteArray blob) {
    return env->NewStringUTF(resolve(blob, env, "tmdb", GEN_NATIVE_TMDB, GEN_NATIVE_TMDB_LEN).c_str());
}

}
```

- [ ] **Step 4: Verify it compiles, with scratch secrets**

```bash
cat >> .env <<'EOF'
CREDENTIALS_BLOB_KEY=0000000000000000000000000000000000000000000000000000000000
EOF
./gradlew :app:assembleDebug
find app/build -name "libcredentials.so"
```

Expected: `BUILD SUCCESSFUL` (this proves mbedTLS fetched, configured, and linked, and that the
JNI symbol names match exactly what `NativeCredentialResolver.kt` declares), and `libcredentials.so`
present per ABI. Remove the scratch line from `.env` afterward.

- [ ] **Step 5: Hand the two files to the user**

Post the full, final content of `app/src/main/cpp/native_credentials.h` and
`app/src/main/cpp/native_credentials.cpp` back to the user in the conversation, and say plainly:
save these somewhere durable outside git (a password manager note, a local backup file) -- this
session's copy on disk is gitignored and will not survive a fresh clone or `git clean -fdx`.

- [ ] **Step 6: Commit the public parts only**

```bash
git add app/src/main/cpp/CMakeLists.txt
git status --porcelain app/src/main/cpp/
git commit -m "feat: add mbedTLS to the native build for AES-GCM"
```

Expected `git status --porcelain app/src/main/cpp/` output: nothing beyond `CMakeLists.txt`
already staged -- confirms `native_credentials.h`/`.cpp` are correctly ignored, not silently
untracked-but-stageable.

---

## Task 8: Compose activation screens (phone + TV) and MainActivity wiring

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/ActivationScreen.kt`
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvActivationScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/MainActivity.kt`

**Interfaces:**
- Consumes: `CredentialsActivator` (Task 5), `RemoteCredentialsStore` (Task 3), `graph.credentialsStore` / `graph.credentialsActivator` (Task 9 -- if Task 9 hasn't landed yet when this task runs, add temporary `by lazy` properties directly on `AppGraph` here; Task 9 will then just wire their real consumers).
- Produces: `@Composable fun ActivationScreen(activator: CredentialsActivator, store: RemoteCredentialsStore, onActivated: () -> Unit)` and `@Composable fun TvActivationScreen(...)` with the identical signature.

- [ ] **Step 1: Phone activation screen**

```kotlin
package com.arkiv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.credentials.CredentialsActivator
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.ui.theme.ArkivBlack
import kotlinx.coroutines.launch

private sealed interface ActivationUiState {
    data object Idle : ActivationUiState
    data object Loading : ActivationUiState
    data object Failed : ActivationUiState
}

/**
 * Shown at the root of navigation whenever [RemoteCredentialsStore.read] returns null: nothing
 * else in the app is reachable until activation succeeds. See
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Architecture", for the
 * exact flow this implements.
 */
@Composable
fun ActivationScreen(
    activator: CredentialsActivator,
    store: RemoteCredentialsStore,
    onActivated: () -> Unit,
) {
    var state by remember { mutableStateOf<ActivationUiState>(ActivationUiState.Idle) }
    val scope = rememberCoroutineScope()

    fun activate() {
        state = ActivationUiState.Loading
        scope.launch {
            val credentials = activator.activate()
            if (credentials != null) {
                store.save(credentials)
                onActivated()
            } else {
                state = ActivationUiState.Failed
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Para funcionar, Kino necesita traer credenciales de terceros a este dispositivo. " +
                "Al activar, entiendes que lo haces bajo tu propia responsabilidad, y que la app " +
                "no es responsable por su uso.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        when (state) {
            is ActivationUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.padding(top = 24.dp),
                color = Color.White,
            )
            is ActivationUiState.Failed -> {
                Text(
                    "Algo salió mal, intenta de nuevo.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Button(onClick = ::activate, modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.6f)) {
                    Text("Reintentar")
                }
            }
            is ActivationUiState.Idle -> Button(
                onClick = ::activate,
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(0.6f),
            ) {
                Text("Activar")
            }
        }
    }
}
```

- [ ] **Step 2: TV activation screen**

TV needs D-pad-focusable controls, not a plain `material3.Button` -- follow this project's existing
TV pattern (`app/src/main/java/com/arkiv/player/ui/tv/TvMagisLinkOffer.kt`'s `TvOfferAction`: an
`androidx.tv.material3.Surface` with `onClick`).

```kotlin
package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CircularProgressIndicator
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.credentials.CredentialsActivator
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.launch

private sealed interface TvActivationUiState {
    data object Idle : TvActivationUiState
    data object Loading : TvActivationUiState
    data object Failed : TvActivationUiState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvActivationScreen(
    activator: CredentialsActivator,
    store: RemoteCredentialsStore,
    onActivated: () -> Unit,
) {
    var state by remember { mutableStateOf<TvActivationUiState>(TvActivationUiState.Idle) }
    val scope = rememberCoroutineScope()

    fun activate() {
        state = TvActivationUiState.Loading
        scope.launch {
            val credentials = activator.activate()
            if (credentials != null) {
                store.save(credentials)
                onActivated()
            } else {
                state = TvActivationUiState.Failed
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(1.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Para funcionar, Kino necesita traer credenciales de terceros a este dispositivo. " +
                    "Al activar, entiendes que lo haces bajo tu propia responsabilidad, y que la app " +
                    "no es responsable por su uso.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            when (state) {
                is TvActivationUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(top = 24.dp),
                )
                is TvActivationUiState.Failed -> {
                    Text(
                        "Algo salió mal, intenta de nuevo.",
                        color = ArkivRed,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TvActivationButton("Reintentar", onClick = ::activate)
                }
                is TvActivationUiState.Idle -> TvActivationButton("Activar", onClick = ::activate)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActivationButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp).padding(top = 24.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
```

Note: `ArkivBlack` is imported but unused if the TV theme already paints a dark background by
default -- if `./gradlew :app:compileDebugKotlin` warns about the unused import, remove it.

- [ ] **Step 3: Wire the gate into `MainActivity.kt`**

In `MainActivity.kt`, inside `setContent { ArkivTheme { ... } }`, the existing code (around line 101
of the pre-existing file) is:

```kotlin
                Box(Modifier.fillMaxSize()) {
                    if (loadContent) {
                        // No session gate: Kino L goes straight to the home, without asking
                        // PocketBase or the gateway whether there's a session. The accounts
                        // subsystem (ui/entrada/, EntradaViewModel) was deleted entirely in Task 9
                        // (sub-project 2B): there's nothing left to call from here.
                        if (isTv) {
                            ArkivTvRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        } else {
                            ArkivRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        }
                    }
```

Replace it with:

```kotlin
                Box(Modifier.fillMaxSize()) {
                    if (loadContent) {
                        var credentials by remember { mutableStateOf(graph.credentialsStore.read()) }
                        if (credentials == null) {
                            // Blocks all other navigation until activation succeeds -- see
                            // docs/superpowers/specs/2026-09-15-split-credential-activation-design.md.
                            if (isTv) {
                                com.arkiv.player.ui.tv.TvActivationScreen(
                                    activator = graph.credentialsActivator,
                                    store = graph.credentialsStore,
                                    onActivated = { credentials = graph.credentialsStore.read() },
                                )
                            } else {
                                com.arkiv.player.ui.ActivationScreen(
                                    activator = graph.credentialsActivator,
                                    store = graph.credentialsStore,
                                    onActivated = { credentials = graph.credentialsStore.read() },
                                )
                            }
                        } else if (isTv) {
                            ArkivTvRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        } else {
                            ArkivRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        }
                    }
```

(This references `graph.credentialsStore` / `graph.credentialsActivator`, added properly in
Task 9; if Task 9 hasn't run yet, add temporary `val credentialsStore by lazy { com.arkiv.player.data.credentials.EncryptedRemoteCredentialsStore(appContext) }` and a matching `credentialsActivator` directly to `AppGraph.kt` now so this compiles, and let Task 9 replace them with the full wiring.)

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/ActivationScreen.kt \
        app/src/main/java/com/arkiv/player/ui/tv/TvActivationScreen.kt \
        app/src/main/java/com/arkiv/player/MainActivity.kt
git commit -m "feat: add the activation screens and gate app startup behind them"
```

---

## Task 9: AppGraph wiring -- replace BuildConfig reads with RemoteCredentialsStore

**This task is what actually achieves the feature's stated goal ("ship a release APK with none of
these five values compiled in") -- not just Task 9's original steps 1-5 below, but also removing
the now-dead `buildConfigField` declarations themselves (Step 6) and the `BuildConfig` defaults
still sitting in three other files (Step 7). Skipping those would leave the real secret values
compiled into `BuildConfig` in the release APK exactly as before, with the new split-credential
mechanism running alongside them for nothing.**

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/magis/MagisResolve.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/magis/MagisLive.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/magis/MagisPortalClient.kt` (KDoc only)
- Modify: `app/src/main/java/com/arkiv/player/data/magis/MagisCrypto.kt` (KDoc only)

**Interfaces:**
- Consumes: `EncryptedRemoteCredentialsStore` (Task 3), `CredentialsActivator` (Task 5).
- Produces: `AppGraph.credentialsStore: RemoteCredentialsStore`, `AppGraph.credentialsActivator: CredentialsActivator`, `AppGraph.refreshCredentialsIfActivated(): suspend Unit` (consumed by Task 10).

- [ ] **Step 1: Add the two new lazy properties**

If Task 8 already added temporary versions of these to make itself compile, replace them with this
final form; otherwise add fresh, right after the existing `apkDownloader` property:

```kotlin
    val credentialsStore: com.arkiv.player.data.credentials.RemoteCredentialsStore by lazy {
        com.arkiv.player.data.credentials.EncryptedRemoteCredentialsStore(appContext)
    }

    val credentialsActivator: com.arkiv.player.data.credentials.CredentialsActivator by lazy {
        com.arkiv.player.data.credentials.CredentialsActivator(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }
```

- [ ] **Step 2: Replace `magisPortal`'s BuildConfig reads**

Change:

```kotlin
    private val magisPortal: com.arkiv.player.data.magis.MagisPortalClientLike by lazy {
        com.arkiv.player.data.magis.MagisPortalClient(
            crypto = com.arkiv.player.data.magis.MagisCrypto(BuildConfig.IPTV_3DES_KEY),
            hosts = BuildConfig.IPTV_HOSTS.split(",").map { it.trim() }.filter { it.isNotBlank() },
            appId = BuildConfig.IPTV_APP_ID,
            apkVersion = BuildConfig.IPTV_APK_VERSION,
            snProvider = { magisStore.readSession()?.sn.orEmpty() },
            http = portalHttp.newBuilder()
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }
```

to:

```kotlin
    private val magisPortal: com.arkiv.player.data.magis.MagisPortalClientLike by lazy {
        val creds = credentialsStore.read()!! // never null here: nothing reaching magisPortal is reachable before activation
        com.arkiv.player.data.magis.MagisPortalClient(
            crypto = com.arkiv.player.data.magis.MagisCrypto(creds.iptv3desKey),
            hosts = creds.iptvHosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
            appId = creds.iptvAppId,
            apkVersion = creds.iptvApkVersion,
            snProvider = { magisStore.readSession()?.sn.orEmpty() },
            http = portalHttp.newBuilder()
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }
```

- [ ] **Step 3: Replace `magisSource`'s `MagisResolve` construction**

Change:

```kotlin
    private val magisSource: com.arkiv.player.data.gateway.ContentSource by lazy {
        com.arkiv.player.data.magis.MagisSource(
            catalog = magisCatalog,
            vodResolver = com.arkiv.player.data.magis.MagisResolve(magisPortal, magisSession),
            tmdb = tmdbApi,
        )
    }
```

to:

```kotlin
    private val magisSource: com.arkiv.player.data.gateway.ContentSource by lazy {
        val creds = credentialsStore.read()!!
        com.arkiv.player.data.magis.MagisSource(
            catalog = magisCatalog,
            vodResolver = com.arkiv.player.data.magis.MagisResolve(
                magisPortal, magisSession,
                appId = creds.iptvAppId,
                apkVersion = creds.iptvApkVersion,
            ),
            tmdb = tmdbApi,
        )
    }
```

- [ ] **Step 4: Replace `magisLive`'s BuildConfig read**

Change:

```kotlin
    internal val magisLive: com.arkiv.player.data.magis.MagisLive by lazy {
        com.arkiv.player.data.magis.MagisLive(magisPortal, magisSession)
    }
```

to:

```kotlin
    internal val magisLive: com.arkiv.player.data.magis.MagisLive by lazy {
        com.arkiv.player.data.magis.MagisLive(
            magisPortal, magisSession,
            apkVersion = credentialsStore.read()!!.iptvApkVersion,
        )
    }
```

- [ ] **Step 5: Replace `tmdbApi`'s BuildConfig read**

Change:

```kotlin
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(language = "es-MX")
    }
```

to:

```kotlin
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(apiKey = credentialsStore.read()!!.tmdbApiKey, language = "es-MX")
    }
```

- [ ] **Step 6: Remove the now-dead `buildConfigField` declarations**

In `app/build.gradle.kts`'s `defaultConfig { ... }` block, delete these five lines entirely (leave
`CAST_RECEIVER_ID`, `VERSION_CODE`, `VERSION_NAME` exactly as they are -- unrelated to this
feature):

```kotlin
        buildConfigField("String", "IPTV_3DES_KEY", "\"${readEnv("IPTV_3DES_KEY")}\"")
        buildConfigField("String", "IPTV_HOSTS", "\"${readEnv("IPTV_HOSTS")}\"")
        buildConfigField("String", "IPTV_APP_ID", "\"${readEnv("IPTV_APP_ID")}\"")
        buildConfigField("String", "IPTV_APK_VERSION", "\"${readEnv("IPTV_APK_VERSION")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${readEnv("API_KEY")}\"")
```

Do NOT remove the `.env`/secret keys themselves (`IPTV_3DES_KEY`, `IPTV_HOSTS`, `IPTV_APP_ID`,
`IPTV_APK_VERSION`, `API_KEY`) from `.env`, `.env.example`, or `release.yml` -- those five raw
values are still read directly by name via `readEnv()`, just by Task 6's native-header codegen and
Task 12's publishing script now, never by a `buildConfigField`.

- [ ] **Step 7: Remove the three orphaned `BuildConfig` defaults this leaves behind**

Removing Step 6's fields breaks compilation wherever a default parameter still reads them. Neither
of the two affected files' many test call sites pass `appId`/`apkVersion` explicitly (only one, in
`MagisResolveTest.kt`, does) -- verified before this task was dispatched -- so replacing the
default with a plain empty string changes no test's behavior; it only removes the compile-time
dependency on `BuildConfig`.

In `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt`, change:

```kotlin
    private val apiKey: String = BuildConfig.TMDB_API_KEY,
```

to:

```kotlin
    private val apiKey: String = "",
```

In `app/src/main/java/com/arkiv/player/data/magis/MagisResolve.kt`, change:

```kotlin
    private val appId: String = BuildConfig.IPTV_APP_ID,
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
```

to:

```kotlin
    private val appId: String = "",
    private val apkVersion: String = "",
```

In `app/src/main/java/com/arkiv/player/data/magis/MagisLive.kt`, change:

```kotlin
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
```

to:

```kotlin
    private val apkVersion: String = "",
```

In all three files, remove the now-unused `import com.arkiv.player.BuildConfig` line if nothing
else in that file still references `BuildConfig` (check with `grep -n BuildConfig` on each file
after the edit -- if the only remaining hits are inside comments, the import is safe to remove).

- [ ] **Step 8: Update two now-stale KDoc comments**

In `app/src/main/java/com/arkiv/player/data/magis/MagisCrypto.kt`, the comment "`[keyHex] is the
24-byte master key in hex (BuildConfig.IPTV_3DES_KEY).`" now names a field that no longer exists --
change it to say the value comes from `RemoteCredentialsStore` instead (see
`com.arkiv.player.data.credentials.RemoteCredentials.iptv3desKey`).

In `app/src/main/java/com/arkiv/player/data/magis/MagisPortalClient.kt`, the comment "`the real
wiring passes it BuildConfig.IPTV_HOSTS.split(",")`" is now wrong for the same reason -- update it
to say the real wiring passes it from `RemoteCredentialsStore` (see `AppGraph.magisPortal`).

- [ ] **Step 9: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, same test count as this task's baseline (no test relies on the
defaults just changed -- this run confirms that empirically, not just by the grep already done).

- [ ] **Step 11: Confirm no compiled secret remains**

```bash
./gradlew :app:assembleDebug
command grep -c "buildConfigField" app/build.gradle.kts
```

The `grep -c` count must be exactly 1 (only `CAST_RECEIVER_ID`'s remains -- it is not a secret, see
the spec's Context section).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/build.gradle.kts \
        app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt \
        app/src/main/java/com/arkiv/player/data/magis/MagisResolve.kt \
        app/src/main/java/com/arkiv/player/data/magis/MagisLive.kt \
        app/src/main/java/com/arkiv/player/data/magis/MagisPortalClient.kt \
        app/src/main/java/com/arkiv/player/data/magis/MagisCrypto.kt
git commit -m "feat: wire AppGraph's Magis/TMDB clients from RemoteCredentialsStore

Removes the five now-dead BuildConfig fields these values used to compile
into -- this is what actually stops the release APK from shipping them."
```

---

## Task 10: UpdateWorker's silent periodic credentials refresh

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/update/UpdateWorker.kt`

**Interfaces:**
- Consumes: `AppGraph.credentialsStore`, `AppGraph.credentialsActivator` (Task 9).
- Produces: `AppGraph.refreshCredentialsIfActivated(): suspend Unit`.

- [ ] **Step 1: Add the refresh function to `AppGraph.kt`**

Add near `checkForUpdate()`:

```kotlin
    /**
     * Silent periodic refresh (see [com.arkiv.player.data.update.UpdateWorker]): only re-applies
     * credentials for a device that already activated once -- never prompts, never activates a
     * fresh install on its own. Recovers a lost/corrupted local copy or a same-version blob fix;
     * does NOT survive an actual credential-value rotation (see the spec's "Consequence accepted"
     * section -- that needs a new app release).
     */
    suspend fun refreshCredentialsIfActivated() {
        if (credentialsStore.read() == null) return
        val refreshed = credentialsActivator.activate() ?: return
        credentialsStore.save(refreshed)
    }
```

- [ ] **Step 2: Call it from `UpdateWorker`**

Change:

```kotlin
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        return runCatching { graph.checkForUpdate() }.fold({ Result.success() }, { Result.retry() })
    }
}
```

to:

```kotlin
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        return runCatching {
            graph.checkForUpdate()
            graph.refreshCredentialsIfActivated()
        }.fold({ Result.success() }, { Result.retry() })
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/data/update/UpdateWorker.kt
git commit -m "feat: refresh split credentials silently in the periodic OTA worker"
```

---

## Task 11: `.github/workflows/release.yml` -- NDK/CMake, native source decode, new secret

**Files:**
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: GitHub Secrets `NATIVE_MODULE_SOURCE_BASE64` and `CREDENTIALS_BLOB_KEY` (Task 13 uploads these; this task assumes they will exist by the time a real tag is pushed).

- [ ] **Step 1: Install NDK and CMake explicitly**

Add a new step right after "Set up JDK 17" and before "Decode the release keystore":

```yaml
      - name: Install the NDK and CMake explicitly
        run: |
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;26.1.10909125" "cmake;3.22.1"
```

(This makes the spec's flagged open risk -- "is the NDK preinstalled on this runner?" -- moot: it
is now always installed explicitly, whether or not it already was.)

- [ ] **Step 2: Decode the native module's real source**

Add a new step right after "Decode the release keystore":

```yaml
      - name: Decode the native module's real source
        run: echo "${{ secrets.NATIVE_MODULE_SOURCE_BASE64 }}" | base64 -d | tar -xzf - -C app/src/main/cpp
```

- [ ] **Step 3: Add `CREDENTIALS_BLOB_KEY` to the written `.env`**

In the existing "Write the .env the build reads" step, add one line to the heredoc (after
`IPTV_APK_VERSION`):

```
          IPTV_APK_VERSION=${{ secrets.MAGIS_APK_VERSION }}
          CREDENTIALS_BLOB_KEY=${{ secrets.CREDENTIALS_BLOB_KEY }}
          CAST_RECEIVER_ID=${{ secrets.CAST_RECEIVER_ID }}
```

- [ ] **Step 4: Confirm the workflow YAML is still well-formed**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))" && echo OK`
Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: install the NDK explicitly and decode the native module's source"
```

---

## Task 12: Publishing script -- `scripts/publish-credentials-blob.sh`

**Files:**
- Create: `scripts/publish-credentials-blob.sh`

**Interfaces:**
- Produces: `credentials.enc` as a release asset on whatever GitHub release is currently "latest",
  in the exact wire format Task 7's `decryptBlob` expects (`IV(12) || ciphertext || tag(16)`, AES
  key `SHA-256(CREDENTIALS_BLOB_KEY)`) and the exact JSON shape Task 7's `extractField` expects.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Splits, bundles, and encrypts the five third-party credentials into credentials.enc, then
# uploads it as an asset on the CURRENT LATEST GitHub release -- independent of the app's own
# version tag, same as latest.json. Run this any time a credential value changes; the periodic
# UpdateWorker refresh on already-activated devices picks it up within 6h.
#
# Needs: pip3 install cryptography
#
#     ./scripts/publish-credentials-blob.sh
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="lordmacu/kino-light"
ENV_FILE=".env"

if [ ! -f "$ENV_FILE" ]; then
  echo "No $ENV_FILE found at repo root -- copy .env.example to .env and fill it in first." >&2
  exit 1
fi

env_value() {
  local key="$1"
  command grep -m1 "^$key=" "$ENV_FILE" | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//"
}

KEY_3DES="$(env_value IPTV_3DES_KEY)"
HOSTS="$(env_value IPTV_HOSTS)"
APP_ID="$(env_value IPTV_APP_ID)"
APK_VERSION="$(env_value IPTV_APK_VERSION)"
TMDB_KEY="$(env_value API_KEY)"
BLOB_KEY="$(env_value CREDENTIALS_BLOB_KEY)"

if [ -z "$KEY_3DES" ] || [ -z "$HOSTS" ] || [ -z "$APP_ID" ] || [ -z "$APK_VERSION" ] || [ -z "$TMDB_KEY" ] || [ -z "$BLOB_KEY" ]; then
  echo "One of IPTV_3DES_KEY, IPTV_HOSTS, IPTV_APP_ID, IPTV_APK_VERSION, API_KEY, CREDENTIALS_BLOB_KEY is missing or empty in $ENV_FILE -- aborting." >&2
  exit 1
fi

python3 - "$KEY_3DES" "$HOSTS" "$APP_ID" "$APK_VERSION" "$TMDB_KEY" "$BLOB_KEY" <<'PY'
import base64
import hashlib
import json
import os
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

key_3des, hosts, app_id, apk_version, tmdb_key, blob_key = sys.argv[1:7]

def file_half(value):
    return value[0::2]

bundle = {
    "3des": base64.b64encode(file_half(key_3des).encode()).decode(),
    "hosts": base64.b64encode(file_half(hosts).encode()).decode(),
    "appId": base64.b64encode(file_half(app_id).encode()).decode(),
    "apkVersion": base64.b64encode(file_half(apk_version).encode()).decode(),
    "tmdb": base64.b64encode(file_half(tmdb_key).encode()).decode(),
}
plaintext = json.dumps(bundle).encode()

aes_key = hashlib.sha256(blob_key.encode()).digest()
iv = os.urandom(12)
aesgcm = AESGCM(aes_key)
ciphertext_and_tag = aesgcm.encrypt(iv, plaintext, None)  # cryptography appends the 16-byte tag

with open("credentials.enc", "wb") as f:
    f.write(iv + ciphertext_and_tag)
PY

echo "credentials.enc built ($(wc -c < credentials.enc) bytes)."

LATEST_TAG="$(gh release list --repo "$REPO" --limit 1 --json tagName --jq '.[0].tagName')"
if [ -z "$LATEST_TAG" ]; then
  echo "No existing GitHub release found on $REPO -- publish an app release (git tag) first." >&2
  exit 1
fi

gh release upload "$LATEST_TAG" credentials.enc --repo "$REPO" --clobber
rm -f credentials.enc
echo "Uploaded credentials.enc to release $LATEST_TAG."
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/publish-credentials-blob.sh
```

- [ ] **Step 3: Dry-run the crypto locally (does not upload anything)**

```bash
pip3 install --quiet cryptography
cat >> .env <<'EOF'
CREDENTIALS_BLOB_KEY=0000000000000000000000000000000000000000000000000000000000
EOF
python3 - <<'PY'
# Same logic as the script's embedded PY block, run standalone to verify round-trip decrypt/combine
# without needing gh or a real release.
import base64, hashlib, json, os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def file_half(v): return v[0::2]
def native_half(v): return v[1::2]
def combine(f, n):
    out = []
    for i, c in enumerate(f):
        out.append(c)
        if i < len(n): out.append(n[i])
    return "".join(out)

key_3des = "5f3a9c1e7b2d4468091acaffe12300de45ab6c7"
bundle = {"3des": base64.b64encode(file_half(key_3des).encode()).decode()}
plaintext = json.dumps(bundle).encode()
aes_key = hashlib.sha256(b"0000000000000000000000000000000000000000000000000000000000").digest()
iv = os.urandom(12)
ct = AESGCM(aes_key).encrypt(iv, plaintext, None)
blob = iv + ct

# Decrypt exactly like the native module: iv=first 12, tag=last 16, ciphertext=middle.
iv2, tag, ciphertext = blob[:12], blob[-16:], blob[12:-16]
plain2 = AESGCM(aes_key).decrypt(iv2, ciphertext + tag, None)
recovered_file_half = base64.b64decode(json.loads(plain2)["3des"]).decode()
recovered = combine(recovered_file_half, native_half(key_3des))
assert recovered == key_3des, f"round trip broke: {recovered} != {key_3des}"
print("round trip OK")
PY
command grep -v '^CREDENTIALS_BLOB_KEY=0000' .env > .env.tmp && mv .env.tmp .env
```

Expected: `round trip OK`.

- [ ] **Step 4: Commit**

```bash
git add scripts/publish-credentials-blob.sh
git commit -m "feat: add the credentials.enc publishing script"
```

---

## Task 13: USER ACTION -- upload the two new GitHub Secrets

**This is an irreversible/visible action on the shared GitHub repo. Stop here and ask the user to
run these themselves, exactly as `scripts/upload-github-secrets.sh`'s own comment already commits
this project to doing -- never run `gh secret set` on the user's behalf.**

Tell the user to run, from the repo root, after Task 7 has handed them the two native source files
and they've saved a copy of `native_credentials.cpp`/`.h` at `app/src/main/cpp/` locally:

```bash
tar -czf - -C app/src/main/cpp native_credentials.cpp native_credentials.h | base64 | gh secret set NATIVE_MODULE_SOURCE_BASE64 --repo lordmacu/kino-light
```

And to generate and store a fresh blob key, add it to their local `.env` as `CREDENTIALS_BLOB_KEY=`,
and upload it:

```bash
openssl rand -hex 32
# paste the output into .env as CREDENTIALS_BLOB_KEY=<output>, then:
printf '%s' "<the same value just added to .env>" | gh secret set CREDENTIALS_BLOB_KEY --repo lordmacu/kino-light
```

Wait for explicit confirmation both commands succeeded before proceeding to Task 14.

---

## Task 14: USER ACTION -- first real tag, first real credentials.enc publish, device verification

**Two irreversible/visible actions here: cutting a real release tag (triggers the public CI build
and publish) and running `scripts/publish-credentials-blob.sh` against the live public release for
the first time. Confirm with the user immediately before each, not bundled together.**

- [ ] **Step 1: Confirm with the user, then cut the real tag**

The exact tag/version-bump mechanics already exist from the OTA pipeline work
(`docs/superpowers/plans/2026-09-14-github-release-ota-pipeline.md`) -- this step is simply
running that same process again, now that `.github/workflows/release.yml` also builds the native
module. Watch the Actions run: it must succeed through `Build the signed release APK` (this is the
NDK/CMake/mbedTLS build's first real test on GitHub's runner) and publish the APK + `latest.json` as
before.

- [ ] **Step 2: Confirm with the user, then publish `credentials.enc` for the first time**

```bash
./scripts/publish-credentials-blob.sh
```

Expected: "Uploaded credentials.enc to release vX.Y.Z."

- [ ] **Step 3: Real-device verification -- clean path**

Install the freshly published APK on a real device (per this project's `.claude/como-trabajar.md`
test-device conventions). Confirm:
- Fresh install shows the activation screen, blocking all other navigation.
- Tapping "Activar" downloads, decrypts, and combines all five credentials; the app then shows the
  normal home screen with a working catalog (Magis) and working metadata (TMDB posters/synopses).
- Force-stop and reopen the app: activation is NOT shown again (already-activated devices skip it).

- [ ] **Step 4: Real-device verification -- anti-instrumentation path**

On the same or another real device with root and `frida-server` available: start `frida-server`,
then trigger a fresh activation attempt (clear app data first). Confirm activation fails with the
generic "Algo salió mal, intenta de nuevo." message -- not a distinct "instrumentation detected"
message (per the spec's Error Handling section). Stop `frida-server` and confirm activation then
succeeds normally.

- [ ] **Step 5: Report results to the user**

Summarize what passed and what didn't. If the anti-instrumentation check produced a false positive
on a device with NO active instrumentation (echoing the real Xiaomi/root false-positive precedent
that motivated `BLOCK_ON_ROOT`), that's the signal to flip `kBlockOnInstrumentation` to `false` in
`native_credentials.cpp` (Task 7's file) and ship a follow-up release -- not to weaken the
detection signals themselves.

---

## Plan Self-Review Notes

- **Spec coverage:** every named section of the spec maps to a task -- threat model and native
  source hiding (Tasks 1, 4, 7, 13), string obfuscation (Tasks 6, 7), anti-instrumentation check
  incl. kill switch and fail-safe (Task 7), rotation trade-off (documented in Task 10's KDoc),
  architecture/components/data flow (Tasks 2, 3, 5, 6, 7, 8, 9), error handling (Tasks 5, 8),
  testing (Tasks 2, 5, 14), and the open implementation risk (resolved concretely in Tasks 4 and 11
  by installing the NDK unconditionally rather than merely checking for it).
- **Type/name consistency checked:** `RemoteCredentials` field names (`iptv3desKey`, `iptvHosts`,
  `iptvAppId`, `iptvApkVersion`, `tmdbApiKey`) are identical across Tasks 3, 5, 8, 9, 10. The five
  JNI function names are identical across Tasks 5 and 7 (Kotlin `external fun` and the C++
  `Java_com_arkiv_player_data_credentials_NativeCredentialResolver_*` symbols). The generated
  header's constant names (`GEN_BLOB_KEY`, `GEN_NATIVE_3DES`, etc.) are identical across Tasks 6
  and 7. The JSON keys (`3des`, `hosts`, `appId`, `apkVersion`, `tmdb`) are identical across Tasks 7
  and 12.
- **Two design decisions this plan made that the spec left open**, both recorded inline at the
  task that resolves them: the XOR mask is a fixed constant duplicated in Gradle and native code
  rather than secret-derived (Task 6), and AES-GCM is implemented via vendored mbedTLS rather than
  hand-written (Task 7).
