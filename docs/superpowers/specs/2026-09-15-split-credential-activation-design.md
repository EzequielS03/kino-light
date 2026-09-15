# Split-credential activation

## Context

Kino Light's release APK is now public (see
`docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md`).
`app/build.gradle.kts` still compiles five third-party credentials straight
into `BuildConfig` string constants:

| `BuildConfig` field | Source `.env` key | What it is |
|---|---|---|
| `IPTV_3DES_KEY` | `IPTV_3DES_KEY` | Magis's cracked 24-byte master key (hex). Encrypts/decrypts portal traffic locally -- **never appears on the wire**. |
| `IPTV_HOSTS` | `IPTV_HOSTS` | Magis portal hostnames. |
| `IPTV_APP_ID` | `IPTV_APP_ID` | App identifier the portal expects. |
| `IPTV_APK_VERSION` | `IPTV_APK_VERSION` | Version string the portal expects. |
| `TMDB_API_KEY` | `API_KEY` | The user's own TMDB v3 API key. **Sent as a plain query parameter on every TMDB request.** |

A `BuildConfig` string constant is a plain, findable string in the compiled
APK -- `apktool`/`jadx` extract it with no special tooling. Six real call
sites read these directly: `AppGraph.kt:112-115` (wires `MagisCrypto` +
`MagisPortalClient`), `TmdbApi.kt:132`, `MagisResolve.kt:37-38`,
`MagisLive.kt:20`. (`CastOptionsProvider.kt:29` reads `CAST_RECEIVER_ID`,
which stays out of scope -- it's a public Cast Developer Console app ID, not
a secret.)

**Goal:** ship a release APK with none of these five values compiled in.
Fetch them, encrypted, from the same public GitHub repo, decrypt on-device
only after the user explicitly consents, and store the result locally --
raising the bar against casual extraction without pretending to make the
values unreadable to a determined reverse engineer (they can't be, once the
decryption logic itself ships in a public APK -- see "Threat model" below).

## Threat model (agreed with the user before design)

Client-side secret protection has a hard ceiling: if the app's own code can
decrypt something, so can anyone willing to reverse-engineer that same code.
Every choice below is a **friction increase**, not a guarantee:

- Moving decryption logic to native code (NDK/JNI) defeats casual, purely
  static extraction (`unzip` + `strings`, or a `jadx` string search) and
  raises the bar against dynamic instrumentation (tools like Frida hook
  JVM/Kotlin method calls far more easily than native ones).
- Splitting each value into two halves -- one inside the encrypted
  downloaded file, one inside the native library -- means a single leak
  (just the file, or just the APK) is useless alone; both must be extracted
  and correctly recombined. This is a real, additional step for an
  attacker, not just cosmetic: **without any split, a determined attacker
  who defeats the AES layer once gets everything at once; with the split,
  defeating the AES layer only yields half of each value**, and getting the
  other half requires a *second*, different extraction technique.
- **This does not equally benefit all five values.** `TMDB_API_KEY` is a
  plain query parameter on every outgoing TMDB request -- anyone capturing
  the app's own network traffic (a MITM proxy, zero APK analysis required)
  reads it complete, regardless of how well it's hidden at rest.
  `IPTV_HOSTS`/`IPTV_APP_ID`/`IPTV_APK_VERSION` are likely also visible in
  the app's real requests to Magis's portal. Only `IPTV_3DES_KEY` never
  appears on the wire, so it's the one value where this whole mechanism
  provides protection traffic-capture can't already defeat.
  **The user explicitly chose to apply the same mechanism to all five
  anyway, for consistency, accepting that four of them gain less real
  protection than the 3DES key does.** This is a deliberate, informed
  trade-off, not an oversight.
- A server-mediated design (a small stateless endpoint that hands back
  credentials after some check) was considered and rejected: it would be a
  server of the project's own, which this project's non-negotiable rule
  forbids.

## Consequence accepted: rotation of a split value needs a new app release

Splitting couples each value's *content* to the currently-installed APK's
native library (which holds one half). If any of the five values' actual
content ever changes (Magis rotates the 3DES key, a TMDB key gets replaced),
the new value's two new halves can't both reach an already-installed device
without a new APK: the new "file half" can be republished freely, but the
new "native half" only exists once compiled into a new release. **The user
explicitly accepted this**, given credential rotation is expected to be
rare. The periodic refresh described below still has value in this world:
it re-applies the *current* app version's already-matching blob (recovering
from a corrupted/lost local copy, or picking up a same-version blob fix)
without needing the user to do anything -- it just can't silently survive an
actual value rotation.

## Architecture

```
First app launch (no local credentials found)
        │
        ▼
Activation screen (blocks all other navigation)
  "Para funcionar, Kino necesita traer credenciales de terceros a este
   dispositivo. Al activar, entiendes que lo haces bajo tu propia
   responsabilidad, y que la app no es responsable por su uso."
  [Activar]
        │ (tap)
        ▼
Download credentials.enc from
github.com/lordmacu/kino-light/releases/latest/download/credentials.enc
        │
        ▼
Native library (JNI): AES-GCM-decrypt the blob using the embedded blob key
→ five "file halves" (plaintext JSON)
        │
        ▼
Native library: for each of the 5 values, interleave the file half with
the matching "native half" baked into this same library at build time
→ five complete, usable values
        │
        ▼
Store the five complete values in EncryptedSharedPreferences
(RemoteCredentialsStore, same EncryptedPrefs.openOrRepair pattern as the
existing EncryptedMagisCredentialStore)
        │
        ▼
AppGraph wires MagisCrypto / MagisPortalClient / TmdbApi from
RemoteCredentialsStore instead of BuildConfig
        │
        ▼
Every 6h, the existing UpdateWorker also re-downloads and re-applies
credentials.enc in the background (no re-prompt -- consent already given
once) -- recovers a lost/corrupted local copy or a same-version blob fix,
does NOT survive an actual value rotation (see above)
```

**Publishing side** (run from the Mac, or added to CI later if needed):
a new script splits each of the five real `.env` values into (file-half,
native-half) by character-position interleaving, bundles the five file-halves
into a JSON, AES-GCM-encrypts it with a new secret (`CREDENTIALS_BLOB_KEY`,
alongside the existing ten from the OTA pipeline), and uploads/updates
`credentials.enc` as an asset on the **current latest** GitHub release --
independent of the app's own version tag, exactly like `latest.json`.

The **native halves** are baked into the native library at compile time,
read from the same `.env`/secrets the release workflow already assembles --
this needs a new codegen step before the NDK build compiles, parallel to how
`readEnv()` already feeds `BuildConfig` fields today.

## Components

**`app/src/main/cpp/`** (new native module, JNI, `armeabi-v7a` +
`arm64-v8a` only -- matches this app's existing `abiFilters`):
- A generated header (never committed; regenerated from secrets before
  every build, same posture as the existing `.env`) holding the AES blob
  key and the five native halves as C string constants.
- One small `.cpp` file exposing five JNI functions, one per credential --
  `resolveIptv3desKey`, `resolveIptvHosts`, `resolveIptvAppId`,
  `resolveIptvApkVersion`, `resolveTmdbApiKey` -- each taking the raw
  downloaded `credentials.enc` bytes and returning that one field's fully
  combined, ready-to-use `String`. Five small, independently testable
  functions rather than one function returning a bundle: matches this
  spec's "smaller well-bounded units" preference, and means a future field
  can be added without changing every other field's call site. Each
  function internally: AES-GCM-decrypts the blob (once per call is wasteful
  but this runs at most a few times a day, never in a hot path -- simplicity
  wins here) to get the JSON of five file-halves, reads its own field's
  file-half, and interleaves it with its own field's native-embedded half.
  AES-GCM decryption AND the interleave-recombine step both happen here, not
  in Kotlin, per the user's choice to keep this off the easily-hookable
  JVM/Kotlin call path.
- `CMakeLists.txt` wiring it into the Gradle build.

**`com.arkiv.player.data.credentials`** (new Kotlin package):
- `RemoteCredentials` -- a data class holding the five reconstructed
  values.
- `RemoteCredentialsStore` -- reads/writes `RemoteCredentials` via
  `EncryptedSharedPreferences`, following `EncryptedPrefs.openOrRepair`'s
  existing pattern (a Keystore that can't decrypt its own file must never
  be able to brick the app -- same rule as the existing session store).
  A **different** preferences file name than `MagisCredentialStore`'s --
  these are a different concern (app-level third-party secrets vs. a
  per-device session).
- `CredentialsActivator` -- orchestrates: download `credentials.enc` (OkHttp,
  same client-building conventions as `UpdateChecker`/`ApkDownloader`) →
  call each of the five native `resolve*` functions with the raw bytes →
  bundle the five results into a `RemoteCredentials` → write it via
  `RemoteCredentialsStore`. Used both by the explicit "Activar" tap and by
  the periodic worker (silently, no UI).

**UI**: a new Compose screen (phone + TV variants, matching this project's
existing dual-layout convention) shown at the root of navigation whenever
`RemoteCredentialsStore.read()` returns null -- nothing else in the app
becomes reachable until activation succeeds. Shows the consent text above,
an "Activar" button, a loading state while the download/decrypt runs, and a
retry affordance on failure (network error, decrypt failure) with a plain
"algo salió mal, intenta de nuevo" message -- no attempt at detailed
diagnostics for a personal project with no support team.

**`AppGraph.kt`**: the `magisPortal` lazy block's four `BuildConfig.IPTV_*`
reads become `RemoteCredentialsStore.read()!!.iptv*` (safe to assume
non-null here, since nothing that touches `magisPortal` is reachable before
activation completes). Same substitution in `TmdbApi.kt`'s default
parameter, `MagisResolve.kt`, `MagisLive.kt`.

**`UpdateWorker`**: gains a second responsibility -- after (or alongside)
its existing OTA check, also run `CredentialsActivator`'s silent refresh if
`RemoteCredentialsStore` already has a value (i.e. only for already-activated
devices; never prompts, never activates a fresh install on its own).

**Publishing**: `scripts/publish-credentials-blob.sh` (new, alongside the
existing `scripts/upload-github-secrets.sh`) -- reads the five `.env`
values, splits, bundles, encrypts, uploads to the latest release. A Gradle
task (or a `doFirst` hook on the native build task) generates the native
header from the same `.env`/secrets before `externalNativeBuild` compiles.

## Data flow: the interleave split/combine

Deterministic, works for any string length, implemented identically in the
publishing script (Python, for straightforward string handling) and the
native module (C++):

```
split(value):    fileHalf  = value[0], value[2], value[4], ...  (even indices)
                  nativeHalf = value[1], value[3], value[5], ...  (odd indices)
combine(fileHalf, nativeHalf):
                  interleave them back: result[0]=fileHalf[0], result[1]=nativeHalf[0],
                  result[2]=fileHalf[1], result[3]=nativeHalf[1], ...
                  (one half is exactly one character longer than the other
                  when the original value's length is odd -- combine must
                  handle that, not assume equal lengths)
```

## Error handling

- **Download fails** (no network, GitHub unreachable): Activation screen
  shows the retry state; the periodic worker's silent refresh just skips
  this cycle and tries again in 6h (matching `UpdateChecker`'s existing
  `runCatching { }.getOrNull()` posture).
- **Decrypt/combine fails** (corrupted download, or a genuine
  app-version/blob mismatch after a rotation neither side warned about):
  same retry affordance on the Activation screen for a first-time failure.
  For the periodic worker, a persistent failure across refreshes is a
  signal worth a developer log line (not a user-facing error -- the app
  keeps working on its last-known-good local credentials).
- **Local store becomes undecryptable** (Keystore reset, restored backup on
  a new device): `EncryptedPrefs.openOrRepair`'s existing discard-and-retry
  behavior applies -- treated the same as "never activated," which means
  the Activation screen reappears and the user re-consents once.

## Testing

- The interleave split/combine algorithm is pure Kotlin (or at minimum has
  a pure-Kotlin equivalent used by unit tests, even though production combine
  runs in native code) -- fully unit-testable on the JVM, including the
  odd-length edge case.
- `RemoteCredentialsStore` follows `MagisCredentialStore`'s existing test
  pattern (this codebase already has JVM-testable coverage for the
  Keystore-repair path via `EncryptedPrefsTest`).
- The native module itself (JNI glue, actual AES-GCM correctness) is **not**
  unit-testable in this project's existing pure-JVM test setup (no
  Robolectric, no device in CI). Verification here means: a real device
  test after the first build that includes the native module, checked
  manually against a real `credentials.enc` produced by the publishing
  script -- this is the same "first tag push IS the test" posture already
  established for the OTA pipeline's own CI-only logic.
- `CredentialsActivator`'s orchestration (download → call the five native
  functions → store) is testable with `MockWebServer`, following
  `UpdateCheckerTest`'s existing pattern, using **fake** implementations of
  the five `resolve*` functions injected for the test (since the real ones
  need the native library, unavailable on the JVM test runner) -- this
  means `CredentialsActivator` must take its five resolver functions as
  constructor parameters (defaulting to the real native ones), the same
  dependency-injection shape `UpdateChecker` already uses for its `client`.

## Open implementation risk, flagged for the plan

GitHub Actions' `ubuntu-latest` runners are not confirmed to have the
Android NDK pre-installed (unlike the SDK platform tools already relied on
implicitly through Gradle). The plan needs an early spike task: confirm
whether `./gradlew :app:assembleRelease` with a `externalNativeBuild` block
just works on the existing runner, or whether an explicit
`sdkmanager --install "ndk;<version>"` (plus license acceptance) step needs
to be added to `.github/workflows/release.yml` before the native module can
build there. This is exactly the kind of environment difference the OTA
pipeline's own test-gate fix already caught once this session -- worth
derisking before committing to the rest of the implementation plan's shape.
