# GitHub Release OTA pipeline

## Context

Kino Light checks for updates against `apk.comparadorinternet.co/latest.json`
(`UpdateChecker`), a self-hosted server. That's an undocumented violation of this
project's own non-negotiable rule ("cero servidor propio" — everything runs
inside the app, no server of our own), which slipped in as host #4 of the eight
allowed network destinations in `.claude/reglas.md`.

The repo also does not exist on GitHub yet: `origin` is configured
(`https://github.com/lordmacu/kino-light.git`) but `git ls-remote origin`
returns "Repository not found." The local `main` branch is 155+ commits ahead
of a remote that has never been created.

**Goal**: replace the self-hosted OTA server with a pipeline where pushing a
version tag to GitHub builds, signs, and publishes a release APK plus a version
manifest JSON, both hosted as GitHub Release assets — nothing outside GitHub.
The app reads the manifest from a URL that always resolves to the latest
release, with no server of our own and no credential embedded in the APK
(same posture as the existing Magis/AniList/Fribb network calls).

## Decisions made during brainstorming

- **Repo visibility: public.** GitHub Release assets and `releases/latest/download/`
  URLs are only fetchable without authentication on a public repo. A private
  repo would require an embedded GitHub token in the APK, which violates the
  "no credential of our own embedded" posture every other network call in this
  app already follows.
- **Trigger: version tags only** (`v*`), not every push to `main`. A tag is
  still "a push to GitHub," but a plain commit to `main` builds and signs
  nothing — only a deliberate `git push origin vX.Y.Z` cuts a release.
- **Distribution: GitHub Releases**, not `raw.githubusercontent.com` on a
  dedicated branch and not GitHub Pages. Release assets live outside git's
  object history, so repeated ~15-20MB APK uploads never bloat `git clone`
  size the way committing them to a branch would. GitHub's built-in
  `releases/latest/download/<asset-name>` alias already gives a permanently
  stable URL for the manifest with zero extra hosting setup.
- **Secrets**: the user uploads them to GitHub directly via `gh secret set`
  (exact commands below); no automation or agent ever sees or handles the
  actual credential values.

## Architecture

```
git tag v0.9.18 && git push origin v0.9.18
        │
        ▼
.github/workflows/release.yml (triggered on tag push matching v*)
        │
        ├─ write a throwaway .env from GitHub Secrets (mirrors local dev's
        │  .env exactly — same readEnv() helper, zero new read path)
        ├─ compute versionCode from the tag (see "Version numbering" below)
        ├─ decode the release keystore secret to a file
        ├─ ./gradlew :app:assembleRelease
        ├─ build latest.json (versionCode, versionName, apk url, notes)
        ├─ extract release notes from the annotated tag's message
        └─ gh release create <tag> --notes-file <notes>
               app-release.apk  latest.json
        │
        ▼
https://github.com/lordmacu/kino-light/releases/latest/download/latest.json
        (always resolves to the most recent release's manifest)
        │
        ▼
UpdateChecker (unchanged flow, new default URL) → UpdateInfo → UpdateDialog
```

No new always-on infrastructure: the workflow only runs when a tag is pushed,
and GitHub serves both files afterward with no server-side code of ours
involved at read time.

## Version numbering

Today `versionCode` (48) and `versionName` ("0.9.17") are unrelated hand-picked
values in `app/build.gradle.kts`. The pipeline needs a `versionCode` that:

1. Is always strictly greater than 48 (Android refuses an "update" with a
   lower or equal versionCode than what's installed).
2. Increases monotonically forever, without depending on GitHub Actions'
   own run-history bookkeeping (a workflow rename or recreation resets
   `github.run_number` back to 1, which would silently break future releases
   if the version code depended on it).

**Decision: derive `versionCode` from the tag's semantic version**, not from
`github.run_number`. For a tag `vMAJOR.MINOR.PATCH`:

```
versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH
```

`v0.9.18` → `0*1_000_000 + 9*1_000 + 18 = 9018`, already comfortably above the
current 48. This assumes `MINOR` and `PATCH` each stay below 1000 between
major bumps, which matches this app's actual release cadence (currently on
`0.9.x`). `versionName` is just the tag with its leading `v` stripped
(`v0.9.18` → `"0.9.18"`).

The workflow enforces the tag shape with a regex (`^v[0-9]+\.[0-9]+\.[0-9]+$`)
and fails loudly, before touching secrets or building anything, if a tag
doesn't match — so a stray tag like `v1` or `latest` can't silently produce a
garbage release.

## Secrets required in GitHub Actions

Ten repository secrets, none of which this session ever sees the value of.
The user runs the `gh secret set` commands below (Approach: "you upload them,
I give you the exact commands").

| Secret | Source (current `.env` key) |
|---|---|
| `TMDB_API_KEY` | `API_KEY` |
| `MAGIS_3DES_KEY` | `IPTV_3DES_KEY` |
| `MAGIS_HOSTS` | `IPTV_HOSTS` |
| `MAGIS_APP_ID` | `IPTV_APP_ID` |
| `MAGIS_APK_VERSION` | `IPTV_APK_VERSION` |
| `CAST_RECEIVER_ID` | `CAST_RECEIVER_ID` (optional; empty is valid — falls back to Google's Default Media Receiver) |
| `RELEASE_KEYSTORE_BASE64` | `base64 -i <path to the .jks from RELEASE_KEYSTORE_PATH>` |
| `RELEASE_KEYSTORE_PASSWORD` | `RELEASE_KEYSTORE_PASSWORD` |
| `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` |
| `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` |

Exact commands (run from the Mac, values read from the real `.env`, never
pasted into chat):

```bash
gh secret set TMDB_API_KEY --repo lordmacu/kino-light --body "$(command grep '^API_KEY=' .env | cut -d= -f2-)"
gh secret set MAGIS_3DES_KEY --repo lordmacu/kino-light --body "$(command grep '^IPTV_3DES_KEY=' .env | cut -d= -f2-)"
gh secret set MAGIS_HOSTS --repo lordmacu/kino-light --body "$(command grep '^IPTV_HOSTS=' .env | cut -d= -f2-)"
gh secret set MAGIS_APP_ID --repo lordmacu/kino-light --body "$(command grep '^IPTV_APP_ID=' .env | cut -d= -f2-)"
gh secret set MAGIS_APK_VERSION --repo lordmacu/kino-light --body "$(command grep '^IPTV_APK_VERSION=' .env | cut -d= -f2-)"
gh secret set CAST_RECEIVER_ID --repo lordmacu/kino-light --body "$(command grep '^CAST_RECEIVER_ID=' .env | cut -d= -f2-)"
gh secret set RELEASE_KEYSTORE_BASE64 --repo lordmacu/kino-light --body "$(base64 -i "$(command grep '^RELEASE_KEYSTORE_PATH=' .env | cut -d= -f2-)")"
gh secret set RELEASE_KEYSTORE_PASSWORD --repo lordmacu/kino-light --body "$(command grep '^RELEASE_KEYSTORE_PASSWORD=' .env | cut -d= -f2-)"
gh secret set RELEASE_KEY_ALIAS --repo lordmacu/kino-light --body "$(command grep '^RELEASE_KEY_ALIAS=' .env | cut -d= -f2-)"
gh secret set RELEASE_KEY_PASSWORD --repo lordmacu/kino-light --body "$(command grep '^RELEASE_KEY_PASSWORD=' .env | cut -d= -f2-)"
```

If any `.env` value is empty (e.g. `CAST_RECEIVER_ID`), the corresponding
`gh secret set` command still runs and sets an empty secret, which is fine —
`readEnv()` already treats a missing/empty key as `""` today.

## App-side changes

**`app/build.gradle.kts`** — `versionCode`/`versionName` become overridable,
defaulting to today's values so local `assembleDebug` never changes behavior:

```kotlin
versionCode = readEnv("VERSION_CODE", "48").toInt()
versionName = readEnv("VERSION_NAME", "0.9.17")
```

**`UpdateChecker.kt`** — new default URL, and the Cloudflare-specific
`)]}'\n`-stripping hack goes away (that was a workaround for the old server's
transform; GitHub serves the raw JSON byte-for-byte):

```kotlin
private val url: String = "https://github.com/lordmacu/kino-light/releases/latest/download/latest.json",
```

```kotlin
val json = JSONObject(raw) // was: JSONObject(raw.substringAfter("{", "").let { "{$it" })
```

Both `check()` and `downloadUrl()` get this same simplification — they share
the identical fetch-and-strip pattern today.

**`.env.example`** — document the two new optional override keys next to the
existing `RELEASE_*` section, so a local `assembleRelease` test build can set
them without needing a real tag.

**`.claude/reglas.md`** — host #4 changes from `apk.comparadorinternet.co` to
`github.com`/`objects.githubusercontent.com` (the actual domains a release
download redirects through), with a note that this migration closes a
pre-existing undocumented "servidor propio" gap rather than opening a new one.

## The workflow file

`.github/workflows/release.yml`, trigger `on: push: tags: ['v*']`,
`permissions: contents: write` (needed for `gh release create`). Steps:

1. Checkout, `actions/setup-java@v4` with Zulu 17 (matches
   `sourceCompatibility`/`targetCompatibility` in `app/build.gradle.kts`).
2. Validate the tag against `^v[0-9]+\.[0-9]+\.[0-9]+$`; fail fast if it
   doesn't match.
3. Compute `VERSION_NAME` (tag minus leading `v`) and `VERSION_CODE`
   (the formula above) as step outputs.
4. Decode `RELEASE_KEYSTORE_BASE64` to `$RUNNER_TEMP/release.jks`.
5. Write `.env` at the repo root from the ten secrets plus the two computed
   version values (`RELEASE_KEYSTORE_PATH` points at the decoded file from
   step 4).
6. `./gradlew :app:assembleRelease`.
7. Extract the annotated tag's message as release notes:
   `git for-each-ref refs/tags/$TAG --format='%(contents)'` (falls back to a
   generic "Release $TAG" line if the tag has no message, e.g. a lightweight
   tag).
8. Build `latest.json`:
   ```json
   {
     "versionCode": <int>,
     "versionName": "<name>",
     "url": "https://github.com/lordmacu/kino-light/releases/download/<tag>/app-release.apk",
     "notes": "<extracted notes, JSON-escaped>"
   }
   ```
9. `gh release create <tag> app/build/outputs/apk/release/app-release.apk latest.json --title <tag> --notes-file <notes file>`.
10. Delete the decoded keystore file and the generated `.env` (belt-and-braces;
    the runner is ephemeral and destroyed after the job regardless).

## Error handling

- **Bad tag shape**: workflow fails at step 2, before any secret is touched or
  any build runs.
- **Missing/wrong secret** (e.g. a typo'd keystore password): `assembleRelease`
  fails at the signing step with Gradle's own clear error; no partial release
  gets created since `gh release create` is the last step.
- **`UpdateChecker` fetch failure** (network down, GitHub unreachable, no
  release published yet): unchanged from today — `runCatching { }.getOrNull()`
  already treats this as "no update available," not a crash. First-ever tag
  push is required before the app has anything to find; until then `check()`
  returns `null` and `downloadUrl()` returns `null`, same as if the server
  were simply down today.

## Testing

- `UpdateChecker`'s existing unit tests (mocked via `MockWebServer`, matching
  this project's established pattern) get one edge updated: the fake server
  response no longer needs the `)]}'\n` prefix, and a case is added for the
  new default URL's exact string (guards against a typo breaking OTA silently,
  same spirit as the `QUERY_ACTIVE_RECOMMENDATIONS` single-source-of-truth
  test elsewhere in this codebase).
- The workflow itself is exercised against reality on the FIRST real tag push
  (`v0.9.18`, the next version bump) — there's no meaningful way to unit-test
  a GitHub Actions YAML file locally; the plan should call out that this first
  tag push IS the test, and to watch the Actions run's logs plus confirm with
  a real `adb`-installed device that the update dialog appears and the
  download completes, per this repo's own "tests verdes mienten, hay que
  mirar la salida real" rule (`.claude/como-trabajar.md`).

## Sequencing (for the implementation plan)

This has a hard ordering constraint the plan must respect:

1. Create the GitHub repo (`gh repo create lordmacu/kino-light --public`) and
   push `main` — **nothing below this line can be tested end-to-end without
   this existing first**, since the workflow, the secrets, and the release
   itself all live on the remote.
2. Land the app-side code changes (`build.gradle.kts`, `UpdateChecker.kt`,
   `.env.example`, `.claude/reglas.md`) and the workflow file, commit, push to
   `main` (this does NOT trigger a release — only a tag push does).
3. The user uploads the ten secrets via the `gh secret set` commands above.
4. Cut the first real tag (`v0.9.18`, or whatever the next bump is) and watch
   the Actions run.
5. Verify on a real device: the app's OTA check finds the new release, the
   dialog shows the right version/notes, and the download+install works.

Steps 1 and 3 are actions with real, hard-to-reverse consequences (a personal
project's source becoming publicly visible; production credentials landing in
a third party's secret store) — the implementation plan must pause for
explicit user confirmation immediately before each, not bundle them into a
larger "apply everything" step.
