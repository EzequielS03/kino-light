# OTA Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app auto-checks for new versions every 6 hours, shows a modal dialog with download progress, and launches the system installer.

**Architecture:** A static `latest.json` on the existing nginx. A WorkManager periodic worker checks it. A Compose dialog handles download + install intent. All wired through `AppGraph`.

**Tech Stack:** OkHttp (already in deps), WorkManager (already in deps), Compose, FileProvider, `ACTION_INSTALL_PACKAGE`.

## Global Constraints

- `minSdk = 26`, `targetSdk = 35`
- No new dependencies (OkHttp 4.12, WorkManager 2.9.1 already present)
- Manual DI via `AppGraph` (no Hilt)
- TV + phone: same code, no platform branching
- Update URL: `https://apk.comparadorinternet.co/latest.json`

---

### Task 1: UpdateInfo data class + UpdateChecker

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/update/UpdateInfo.kt`
- Create: `app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt`
- Test: `app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class UpdateInfo(val versionCode: Int, val versionName: String, val url: String, val notes: String)`
  - `class UpdateChecker(private val client: OkHttpClient)` with `suspend fun check(currentVersionCode: Int): UpdateInfo?` — returns `UpdateInfo` if remote versionCode > currentVersionCode, null otherwise.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt
package com.arkiv.player.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = UpdateChecker(OkHttpClient(), server.url("/latest.json").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `returns UpdateInfo when remote versionCode is higher`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"versionCode":2,"versionName":"0.2.0","url":"https://example.com/app.apk","notes":"fix"}
        """.trimIndent()))
        val result = checker.check(currentVersionCode = 1)
        assertNotNull(result)
        assertEquals(2, result!!.versionCode)
        assertEquals("0.2.0", result.versionName)
        assertEquals("https://example.com/app.apk", result.url)
        assertEquals("fix", result.notes)
    }

    @Test
    fun `returns null when remote versionCode equals current`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"versionCode":1,"versionName":"0.1.0","url":"https://example.com/app.apk","notes":""}
        """.trimIndent()))
        assertNull(checker.check(currentVersionCode = 1))
    }

    @Test
    fun `returns null when server returns error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(checker.check(currentVersionCode = 1))
    }

    @Test
    fun `returns null when JSON is malformed`() = runTest {
        server.enqueue(MockResponse().setBody("not json"))
        assertNull(checker.check(currentVersionCode = 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.update.UpdateCheckerTest" -q`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Write UpdateInfo and UpdateChecker**

```kotlin
// app/src/main/java/com/arkiv/player/data/update/UpdateInfo.kt
package com.arkiv.player.data.update

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
)
```

```kotlin
// app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt
package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateChecker(
    private val client: OkHttpClient,
    private val url: String = "https://apk.comparadorinternet.co/latest.json",
) {
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val body = client.newCall(Request.Builder().url(url).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val json = JSONObject(body)
            val remote = UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                url = json.getString("url"),
                notes = json.optString("notes", ""),
            )
            if (remote.versionCode > currentVersionCode) remote else null
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.update.UpdateCheckerTest" -q`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/update/UpdateInfo.kt \
       app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt \
       app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt
git commit -m "feat(update): UpdateInfo + UpdateChecker con tests"
```

---

### Task 2: UpdateWorker (WorkManager periodic check)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/update/UpdateWorker.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` — add `updateChecker`, `updateInfo` StateFlow, `checkForUpdate()` suspend fun.
- Modify: `app/src/main/java/com/arkiv/player/ArkivApp.kt` — enqueue periodic worker + initial check.

**Interfaces:**
- Consumes: `UpdateChecker.check(currentVersionCode): UpdateInfo?`
- Produces:
  - `AppGraph.updateInfo: StateFlow<UpdateInfo?>` — observed by the UI to show the dialog.
  - `AppGraph.checkForUpdate()` — runs an immediate check (called by the Worker and at app start).
  - `UpdateWorker` — periodic WorkManager worker, every 6 hours.

- [ ] **Step 1: Add updateChecker and updateInfo to AppGraph**

In `AppGraph.kt`, add after the `settings` declaration:

```kotlin
val updateChecker: UpdateChecker by lazy {
    UpdateChecker(okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build())
}

private val _updateInfo = kotlinx.coroutines.flow.MutableStateFlow<UpdateInfo?>(null)
val updateInfo: kotlinx.coroutines.flow.StateFlow<UpdateInfo?> = _updateInfo

suspend fun checkForUpdate() {
    _updateInfo.value = updateChecker.check(BuildConfig.VERSION_CODE)
}
```

Add import: `import com.arkiv.player.data.update.UpdateChecker` and `import com.arkiv.player.data.update.UpdateInfo`.

- [ ] **Step 2: Create UpdateWorker**

```kotlin
// app/src/main/java/com/arkiv/player/data/update/UpdateWorker.kt
package com.arkiv.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arkiv.player.ArkivApp

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        graph.checkForUpdate()
        return Result.success()
    }
}
```

- [ ] **Step 3: Enqueue worker and initial check in ArkivApp.onCreate**

In `ArkivApp.kt`, add at the end of `onCreate()`:

```kotlin
// OTA: chequeo periódico cada 6 horas + chequeo inmediato al arrancar.
androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "update_check",
    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
    androidx.work.PeriodicWorkRequestBuilder<com.arkiv.player.data.update.UpdateWorker>(
        6, java.util.concurrent.TimeUnit.HOURS,
    ).setConstraints(
        androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
    ).build(),
)
graph.applicationScope.launch { graph.checkForUpdate() }
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/update/UpdateWorker.kt \
       app/src/main/java/com/arkiv/player/AppGraph.kt \
       app/src/main/java/com/arkiv/player/ArkivApp.kt
git commit -m "feat(update): UpdateWorker periódico + AppGraph.updateInfo"
```

---

### Task 3: ApkDownloader (download with progress)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/update/ApkDownloader.kt`

**Interfaces:**
- Consumes: nothing (takes a URL string)
- Produces: `class ApkDownloader(private val context: Context)` with:
  - `fun download(url: String): Flow<DownloadState>` — emits `Downloading(progress: Float)`, `Ready(file: File)`, `Failed(error: String)`.

- [ ] **Step 1: Write ApkDownloader**

```kotlin
// app/src/main/java/com/arkiv/player/data/update/ApkDownloader.kt
package com.arkiv.player.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val error: String) : DownloadState
}

class ApkDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun download(url: String): Flow<DownloadState> = flow {
        val dest = File(context.cacheDir, "update.apk")
        if (dest.exists()) dest.delete()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            emit(DownloadState.Failed("HTTP ${response.code}"))
            return@flow
        }
        val body = response.body ?: run {
            emit(DownloadState.Failed("Empty response"))
            return@flow
        }
        val total = body.contentLength()
        var downloaded = 0L
        dest.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    emit(DownloadState.Downloading(progress))
                }
            }
        }
        emit(DownloadState.Ready(dest))
    }.flowOn(Dispatchers.IO)
}
```

- [ ] **Step 2: Add ApkDownloader to AppGraph**

In `AppGraph.kt`, add:

```kotlin
val apkDownloader: ApkDownloader by lazy { ApkDownloader(appContext) }
```

Add import: `import com.arkiv.player.data.update.ApkDownloader`.

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/update/ApkDownloader.kt \
       app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(update): ApkDownloader con progreso vía Flow"
```

---

### Task 4: FileProvider + manifest permissions

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` — add `REQUEST_INSTALL_PACKAGES` permission + FileProvider declaration.

**Interfaces:**
- Consumes: nothing
- Produces: FileProvider authority `com.arkiv.player.fileprovider` that exposes `cache/` directory.

- [ ] **Step 1: Create file_paths.xml**

```xml
<!-- app/src/main/res/xml/file_paths.xml -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apk_updates" path="." />
</paths>
```

- [ ] **Step 2: Add permission and provider to AndroidManifest.xml**

Add before the `<application>` tag:
```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Add inside the `<application>` tag:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="com.arkiv.player.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml
git commit -m "feat(update): FileProvider + REQUEST_INSTALL_PACKAGES"
```

---

### Task 5: UpdateDialog composable + integration in MainActivity

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/update/UpdateDialog.kt`
- Modify: `app/src/main/java/com/arkiv/player/MainActivity.kt` — observe `updateInfo`, show dialog.

**Interfaces:**
- Consumes:
  - `AppGraph.updateInfo: StateFlow<UpdateInfo?>`
  - `AppGraph.apkDownloader.download(url): Flow<DownloadState>`
- Produces: `UpdateDialog(info: UpdateInfo, graph: AppGraph, onDismiss: () -> Unit)` composable.

- [ ] **Step 1: Write UpdateDialog composable**

```kotlin
// app/src/main/java/com/arkiv/player/ui/update/UpdateDialog.kt
package com.arkiv.player.ui.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.arkiv.player.AppGraph
import com.arkiv.player.data.update.DownloadState
import com.arkiv.player.data.update.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(info: UpdateInfo, graph: AppGraph, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "com.arkiv.player.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startDownload() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        downloading = true
        error = null
        scope.launch {
            graph.apkDownloader.download(info.url).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> progress = state.progress
                    is DownloadState.Ready -> installApk(state.file)
                    is DownloadState.Failed -> { downloading = false; error = state.error }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nueva versión ${info.versionName}", style = MaterialTheme.typography.titleLarge)
                if (info.notes.isNotBlank()) {
                    Text(info.notes, style = MaterialTheme.typography.bodyMedium)
                }

                if (downloading) {
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.End),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                error?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!downloading) {
                        TextButton(onClick = onDismiss) { Text("Cerrar") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { startDownload() },
                            modifier = Modifier.focusRequester(buttonFocus),
                        ) { Text("Actualizar ahora") }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Integrate in MainActivity**

In `MainActivity.kt`, after `setContent { ArkivTheme {`, add observation of `updateInfo` and show the dialog. Insert inside the `Box(Modifier.fillMaxSize())` block, after the splash and before the closing `}` of Box:

```kotlin
val graph = (application as ArkivApp).graph
val updateAvailable by graph.updateInfo.collectAsState()
var dismissed by remember { mutableStateOf(false) }
updateAvailable?.let { info ->
    if (!dismissed) {
        com.arkiv.player.ui.update.UpdateDialog(
            info = info,
            graph = graph,
            onDismiss = { dismissed = true },
        )
    }
}
```

Add import: `import androidx.compose.runtime.collectAsState`.

- [ ] **Step 3: Build and install on device to verify**

Run: `./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

Install on Fire Stick or phone and verify the dialog does NOT appear (because `latest.json` doesn't exist on the server yet, so `updateInfo` stays null).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/update/UpdateDialog.kt \
       app/src/main/java/com/arkiv/player/MainActivity.kt
git commit -m "feat(update): UpdateDialog + integración en MainActivity"
```

---

### Task 6: Server-side latest.json + end-to-end test

**Files:**
- No app code changes. Server-side setup + manual verification.

**Interfaces:**
- Consumes: the full OTA pipeline built in Tasks 1-5.
- Produces: working `latest.json` on `apk.comparadorinternet.co`.

- [ ] **Step 1: Upload latest.json to server**

Create `latest.json` pointing to the current version (versionCode=1) so the app does NOT show the dialog yet (remote == current):

```bash
echo '{"versionCode":1,"versionName":"0.1.0","url":"https://apk.comparadorinternet.co/Arkiv-0.1.0.apk","notes":""}' | ssh blog 'cat > /var/www/arkiv/latest.json'
```

- [ ] **Step 2: Verify the JSON is accessible**

```bash
curl -s https://apk.comparadorinternet.co/latest.json | python3 -m json.tool
```

Expected: valid JSON with versionCode=1.

- [ ] **Step 3: Bump versionCode to 2, build, upload, and test the full flow**

In `app/build.gradle.kts`, change `versionCode = 1` to `versionCode = 2` and `versionName = "0.1.0"` to `versionName = "0.2.0"`.

Build: `./gradlew assembleDebug assembleRelease`

Upload release APK:
```bash
scp app/build/outputs/apk/release/app-release.apk blog:/var/www/arkiv/Arkiv-0.2.0.apk
```

Update `latest.json` to point to version 2:
```bash
echo '{"versionCode":2,"versionName":"0.2.0","url":"https://apk.comparadorinternet.co/Arkiv-0.2.0.apk","notes":"Actualización OTA, fix seek TV, modo noche"}' | ssh blog 'cat > /var/www/arkiv/latest.json'
```

Install the **old** debug APK (versionCode=1) on the Fire Stick. Open the app. The update dialog should appear because remote versionCode (2) > installed versionCode (1).

Verify:
1. Dialog shows with "Nueva versión 0.2.0" and the notes.
2. Press "Actualizar ahora" — progress bar fills.
3. System installer prompt appears.
4. After installing, app restarts with new version.

- [ ] **Step 4: Commit the version bump**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 0.2.0 (primera versión con OTA)"
```
