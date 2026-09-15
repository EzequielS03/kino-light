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

## Native module source stays out of the public repo

The user asked, directly: can `credentials.enc` be decrypted on a PC? Yes,
without ever running the app: extract the native `.so` from the public APK,
disassemble it (Ghidra, IDA -- free, built for exactly this), read the
embedded key and native-half strings out of its data section, then decrypt
the downloaded blob locally with a throwaway script. This is the same
ceiling stated above, made concrete.

**What DOES help, incrementally**: keeping the native module's actual
implementation (`.cpp`/`.h`, not the `CMakeLists.txt` build config, which
reveals nothing) out of the public git history entirely. This does not
protect the shipped, compiled `.so` -- that still ships in the public APK
and remains reverse-engineerable regardless of whether its source was ever
public. What it removes is the free, zero-effort version of the attack:
reading the exact algorithm (AES-GCM, then character-position interleave)
straight off GitHub before ever touching the APK. Knowing the algorithm in
advance measurably speeds up disassembling the binary later (you know
exactly what pattern to look for); not knowing it means reconstructing the
scheme from raw machine code first. A real, if incremental, friction
increase -- the user chose to add it knowing it doesn't change the ceiling.

**Mechanism**: the same pattern this repo already uses for the release
keystore. `app/src/main/cpp/*.cpp` and `app/src/main/cpp/*.h` (the actual
algorithm) are gitignored and never committed; `CMakeLists.txt` (which only
says "compile these two files," not what's in them) stays committed and
public. A new GitHub Secret, `NATIVE_MODULE_SOURCE_BASE64` (a tarball of the
`.cpp`/`.h` files, base64-encoded, alongside the existing
`RELEASE_KEYSTORE_BASE64`), gets decoded into `app/src/main/cpp/` by
`.github/workflows/release.yml` before the native build compiles -- mirrors
the keystore-decode step already there.

**Cost, stated plainly so it isn't rediscovered by surprise later**: a file
that is never committed does not exist for anyone reading the repo from
git alone -- including a future Claude Code session (this one, next week,
with no memory of today). Neither the assistant nor a fresh contributor can
read or modify this code from the repo; the user is the only durable holder
of it. Two things follow, both to land in the implementation plan itself:
1. Whoever writes this file (this session, when the plan reaches that task)
   must hand the user a copy to keep somewhere outside git (a password
   manager note, a local backup, anything durable) -- the working directory
   alone is not enough, since `git clean -fdx` or a fresh clone would lose
   it silently.
2. Any FUTURE change to this specific file requires the user either pasting
   its current content back into the conversation first, or making the
   change themselves directly -- a plan or session that tries to "just edit
   the native module" without the user first supplying the current source
   will be editing from scratch, not from what's actually deployed.

## String obfuscation of the embedded constants

Confirmed with the user directly: decrypting `credentials.enc` alone never
yields a complete, usable credential -- only the five file-halves. But
since the native-halves live in the *same* `.so` a reverse engineer already
had to open to find the AES blob key, finding them too, once already
inside that binary, is not meaningfully harder in practice -- both are
otherwise just plain, readable C string constants sitting in the binary's
data section, indistinguishable from one another to whoever's looking. This
closes that gap a little further, still within the already-stated ceiling
(nothing here stops a determined disassembler-based analysis; it stops the
*next* tier down: opening the `.so` in a hex editor or running `strings` on
it and reading the secrets directly).

**Mechanism**: every constant this native module embeds -- the AES blob
key AND the five native-halves -- is XORed byte-for-byte against a mask
before being written into the generated header (the same per-build codegen
step already described above), instead of being written as plain,
human-readable text. The static `.cpp` file (already gitignored and hidden
per the section above) holds the small de-obfuscation routine: at the
moment a `resolve*` function actually needs a constant, it XORs the masked
bytes back into a short-lived local buffer, uses it, and the buffer goes
out of scope immediately after. Nothing is ever written back to a
long-lived global in cleartext.

This is deliberately simple -- a fixed XOR mask, not a second real
cipher -- because its job is narrow: defeat a **naive, automated
string-scan** of the compiled binary (exactly the kind of tool that would
otherwise find `IPTV_3DES_KEY`'s value with a single `strings app.so | grep`
pass), not to add cryptographic strength the design doesn't already have
elsewhere. The mask itself lives in the native module too, so it doesn't
survive someone actually tracing the code in a disassembler -- consistent
with every other layer in this spec, this raises the floor, not the
ceiling.

## Anti-instrumentation check: the realistic attack none of the above stops

Everything above -- the split, the native module, hiding its source --
defends against **static** analysis: someone inspecting the APK/`.so`/blob
at rest. There is a more direct attack that defeats all of it in one step
regardless of how well the static data is hidden: **dynamic
instrumentation** (Frida, typically via a rooted device or a repackaged
APK) hooking the five `resolve*` JNI functions themselves and reading their
**return values**. It doesn't matter how obscure the algorithm is or how
well the constants are hidden if an attacker can simply intercept the
function the instant it hands back the real, final, combined credential --
no decryption, no disassembly, no understanding of the scheme required.

This app already has a sophisticated root-detection module
(`app/src/main/java/com/arkiv/player/security/RootDetection.kt`), including
mount-table-inconsistency detection specifically resistant to
Magisk/Shamiko hiding itself. **It's deliberately disabled**
(`MainActivity.kt`'s `BLOCK_ON_ROOT = false`) so the app keeps working on
rooted devices -- an existing, considered product decision this spec does
not reopen. Root alone isn't the threat here anyway (plenty of legitimate
users root their devices for unrelated reasons); the specific threat is
**active instrumentation**, a narrower and rarer condition.

This app also already has `app/src/main/java/com/arkiv/player/security/ApkSignature.kt`,
which checks the running APK's own signing certificate against a hardcoded
expected fingerprint and refuses to start (in release builds) if it doesn't
match -- already gating `MainActivity` today, unconditionally, alongside
root detection. This closes a path the new anti-instrumentation check would
otherwise be exposed to: without it, someone could decompile the app,
delete the Frida check this section adds, and redistribute a re-signed
copy where Frida works freely. With it, that patched copy simply refuses to
run, and the only way left to defeat the check is evading it **live**
against the genuine, unmodified, correctly-signed binary -- a strictly
harder task than editing a hex dump. No new work needed here: the plan just
needs to confirm this existing check isn't accidentally bypassed by
whatever entry point ends up calling the new `resolve*` functions (i.e.
credential resolution should only ever be reachable from the same
already-signature-gated app process, never from a path `ApkSignature`
doesn't already cover).

**Decision: add a targeted, native, Frida-signal check that runs only around
credential resolution**, not a whole-app gate:

- Checked from **inside the native module**, not Kotlin -- if the check
  itself lived in Kotlin, Frida could simply hook the check function and
  force it to report "clean," defeating the entire point. Each `resolve*`
  function runs the check first and returns an empty/failure result instead
  of the real value if instrumentation is detected -- the rest of the app
  keeps working normally either way; only credential resolution refuses.
- Signals checked (standard, well-documented techniques, no exotic
  research needed):
  1. A local connection attempt to `127.0.0.1:27042` (`frida-server`'s
     default port).
  2. `/proc/self/maps` scanned for `frida`-named loaded libraries.
  3. `/proc/self/status`'s `TracerPid` line (nonzero means a
     debugger/tracer is already attached -- catches ptrace-based tools
     generally, not only Frida by name).
  4. **Self-trace**: call `ptrace(PTRACE_TRACEME, 0, 0, 0)` on itself. A
     process can only ever have one tracer; if something is already
     tracing this process (Frida attaches via ptrace under the hood on
     most setups), this call fails. Complements signal 3 -- `TracerPid`
     can read `0` in some indirect-injection setups where this still
     catches it, and vice versa.
  5. **Timing check**: measure the wall-clock cost of a trivial, fixed
     computation. Actively-traced code runs measurably slower (breakpoint
     and step overhead). Treated as the **weakest** signal and never a
     sole trigger by itself -- a busy or thermal-throttled low-end device
     (the Fire TV Stick this project already measures against elsewhere)
     can look slow for entirely innocent reasons. Combined with signals
     1-4, not standalone.

  **Combination rule**: any one of signals 1-4 alone is enough to refuse
  resolution -- each is a specific, deliberate indicator with a low false-
  positive rate on its own. Signal 5 (timing) never triggers by itself; it
  only counts when at least one of 1-4 is also borderline/inconclusive
  (e.g., a read that failed rather than cleanly returning "not present"),
  as a tie-breaker rather than an independent trigger.
- **This is not foolproof either** -- exactly like `RootDetection.kt`'s own
  KDoc already says about itself, matching this project's established
  practice of being honest about a defense's limits rather than
  overselling it. Frida has known techniques to change its own default
  port, hide its loaded libraries, and evade `TracerPid` checks. This
  raises the bar against the common, off-the-shelf case; it does not stop
  a specifically-motivated attacker who knows to route around it.
- **Known false-positive edge case, stated plainly**: a developer debugging
  their own rooted device with Frida for something unrelated to this app
  would also fail to activate credentials. Accepted as reasonable given the
  security goal -- this only blocks the activation/resolve step, never the
  rest of the app.

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
`arm64-v8a` only -- matches this app's existing `abiFilters`), with two
different files kept out of git for two different reasons:

- A **generated header** holding the AES blob key and the five native
  halves as **XOR-masked** byte arrays, not plain C string constants (see
  "String obfuscation of the embedded constants" above) -- regenerated from
  secrets before every single build (never committed, same posture as the
  existing `.env`), because its *content* changes with whatever's currently
  in `.env`/GitHub Secrets.
- A **static `.cpp` file** holding the actual algorithm -- five JNI
  functions, one per credential: `resolveIptv3desKey`, `resolveIptvHosts`,
  `resolveIptvAppId`, `resolveIptvApkVersion`, `resolveTmdbApiKey` -- each
  taking the raw downloaded `credentials.enc` bytes and returning that one
  field's fully combined, ready-to-use `String`, or an empty string if the
  anti-instrumentation check (below) trips. Five small, independently
  testable functions rather than one function returning a bundle: matches
  this spec's "smaller well-bounded units" preference, and means a future
  field can be added without changing every other field's call site. Each
  function internally: runs the Frida/tracer check first (see
  "Anti-instrumentation check" above) and bails with an empty result if it
  trips; otherwise de-obfuscates the masked AES blob key into a short-lived
  buffer, AES-GCM-decrypts the blob with it (once per call is wasteful but
  this runs at most a few times a day, never in a hot path -- simplicity
  wins here) to get the JSON of five file-halves, de-obfuscates its own
  field's masked native-half, and interleaves the two.
  This file is kept out of git for a *different* reason than the header
  above: not because its content varies, but to keep the algorithm itself
  off the public repo -- see "Native module source stays out of the public
  repo" above for the mechanism (`NATIVE_MODULE_SOURCE_BASE64` secret) and
  its cost.
- `CMakeLists.txt` (committed, public -- it only says which files to
  compile, not what's in them) wiring both into the Gradle build.

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
- **Anti-instrumentation check trips** (a `resolve*` function detects Frida
  or a tracer): returns an empty result, indistinguishable from a
  decrypt/combine failure above -- the Activation screen shows the same
  generic retry message, deliberately not a distinct "instrumentation
  detected" error. Telling an attacker exactly which defense caught them
  only helps them route around it next time.

## Testing

- The interleave split/combine algorithm is pure Kotlin (or at minimum has
  a pure-Kotlin equivalent used by unit tests, even though production combine
  runs in native code) -- fully unit-testable on the JVM, including the
  odd-length edge case.
- `RemoteCredentialsStore` follows `MagisCredentialStore`'s existing test
  pattern (this codebase already has JVM-testable coverage for the
  Keystore-repair path via `EncryptedPrefsTest`).
- The native module itself (JNI glue, actual AES-GCM correctness, and the
  anti-instrumentation check) is **not** unit-testable in this project's
  existing pure-JVM test setup (no Robolectric, no device in CI).
  Verification here means: a real device test after the first build that
  includes the native module, checked manually against a real
  `credentials.enc` produced by the publishing script -- this is the same
  "first tag push IS the test" posture already established for the OTA
  pipeline's own CI-only logic. The anti-instrumentation check specifically
  should be verified twice on a real device: once clean (credentials
  resolve normally) and once with `frida-server` actually running against
  it (resolution fails), the same way `RootDetection`'s own KDoc documents
  having been measured against a real device rather than assumed correct.
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
