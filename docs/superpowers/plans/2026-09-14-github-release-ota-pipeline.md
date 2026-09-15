# GitHub Release OTA Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the self-hosted `apk.comparadorinternet.co/latest.json` OTA
server with a tag-triggered GitHub Actions workflow that builds, signs, and
publishes the release APK plus a version manifest as GitHub Release assets —
no server of our own, no credential embedded in the APK.

**Architecture:** Pushing a `vMAJOR.MINOR.PATCH` tag triggers
`.github/workflows/release.yml`, which reconstructs a throwaway `.env` from
GitHub Secrets, runs `./gradlew :app:assembleRelease`, derives `versionCode`
from the tag itself, builds a `latest.json` manifest, and publishes both as
assets of a new GitHub Release. The app's `UpdateChecker` polls
`.../releases/latest/download/latest.json`, a URL GitHub itself always
resolves to the newest release.

**Tech Stack:** Kotlin, OkHttp, `org.json`, JUnit + MockWebServer, GitHub
Actions (`ubuntu-latest`), `gh` CLI, Python 3 (pre-installed on the runner,
used only to write well-escaped JSON).

**Spec:** `docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md`

## Global Constraints

- Repo visibility must be **public** — GitHub Release assets and
  `releases/latest/download/` URLs are only fetchable with no auth on a
  public repo, and the app must never carry an embedded GitHub credential.
- Trigger is a **version tag** (`v*`), never a plain push to `main`.
- `versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH`, derived from the
  tag — never from `github.run_number` (resets on workflow rename).
- All ten GitHub secrets are uploaded by the user directly via `gh secret
  set`, run in their own terminal — no agent or automation ever sees the
  values pass through this session.
- Commits: author `lordmacu <10134930+lordmacu@users.noreply.github.com>`, no
  `Co-Authored-By: Claude` footer, stage only the files touched (never `git
  add -A`) — see `.claude/reglas.md`.
- Every task that changes `app/` code ends with `./gradlew :app:assembleDebug
  :app:testDebugUnitTest` green before committing.

---

## Task 1: Create the public GitHub repo and push `main`

**Files:** none — this is a git/GitHub operation, no source changes.

**Interfaces:** none consumed. Produces: an existing `lordmacu/kino-light`
GitHub repo with `main` pushed, which every later task depends on (the
workflow, the secrets, and the release itself all live on this remote).

**This is a real, hard-to-reverse, publicly-visible action — a personal
project's full source and 155+ commit history becoming visible to anyone.
Do not run the commands below without the user explicitly confirming "yes,
make it public and push now" in this exact session, immediately before
running them. If the plan is being executed by a subagent, the subagent must
stop and hand this task back to the orchestrator for that confirmation
rather than proceeding on its own.**

- [ ] **Step 1: Confirm with the user, out loud, right before running anything**

Ask: "About to run `gh repo create lordmacu/kino-light --public` and push
155+ commits, making this project's full source public on GitHub. Confirm?"
Wait for an explicit yes. Do not proceed on an assumed or earlier approval —
this is the moment the action becomes irreversible in spirit (once public,
anyone could have already cloned it even if you flip it back to private
later).

- [ ] **Step 2: Verify there is nothing sensitive staged to be pushed**

```bash
git log --all --oneline -- .env
git ls-files | command grep -i '\.env$'
```

Expected: both commands print nothing. `.env` has never been tracked (per
`.claude/CLAUDE.md`), but this is the moment to double-check before the
history becomes public, not after.

- [ ] **Step 3: Create the repo (remote already configured, so no `--source`)**

```bash
gh repo create lordmacu/kino-light --public --description "Kino Light — Android TV/phone video player (Magis, Ditu/Caracol, AniList)"
```

Expected output: `✓ Created repository lordmacu/kino-light on github.com`

- [ ] **Step 4: Push `main`**

```bash
git push -u origin main
```

Expected: the push succeeds and prints the new branch tracking line
(`branch 'main' set up to track 'origin/main'`).

- [ ] **Step 5: Verify from the remote side**

```bash
gh repo view lordmacu/kino-light --json visibility,defaultBranchRef
```

Expected: `"visibility":"PUBLIC"` and `"defaultBranchRef":{"name":"main"}`.

---

## Task 2: App-side code changes — version override and `UpdateChecker`

**Files:**
- Modify: `app/build.gradle.kts:39-40`
- Modify: `app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt`
- Modify: `app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt`
- Modify: `.env.example`
- Modify: `.claude/reglas.md:19-20`

**Interfaces:**
- Consumes: `readEnv(key: String, default: String = ""): String`, already
  defined at the top of `app/build.gradle.kts`.
- Produces: `UpdateChecker.url: String` (visibility changes from `private` to
  `internal` so the test in this task can read it — no other consumer
  outside this file and its test needs it).

- [ ] **Step 1: Make `UpdateChecker.url` visible to its test**

In `app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt`, change:

```kotlin
class UpdateChecker(
    private val client: OkHttpClient,
    private val url: String = "https://apk.comparadorinternet.co/latest.json",
) {
```

to:

```kotlin
class UpdateChecker(
    private val client: OkHttpClient,
    internal val url: String = "https://apk.comparadorinternet.co/latest.json",
) {
```

- [ ] **Step 2: Write the failing test for the new default URL**

Add to `app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt`,
inside the `UpdateCheckerTest` class:

```kotlin
    @Test
    fun `default url points at the GitHub Release manifest, not the old server`() {
        val checker = UpdateChecker(OkHttpClient())
        assertEquals(
            "https://github.com/lordmacu/kino-light/releases/latest/download/latest.json",
            checker.url,
        )
    }
```

- [ ] **Step 3: Run the test and confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.update.UpdateCheckerTest"
```

Expected: FAIL — the assertion mismatches because the current default is
still `https://apk.comparadorinternet.co/latest.json`.

- [ ] **Step 4: Change the default URL and drop the Cloudflare-prefix hack**

In `UpdateChecker.kt`, change the default url:

```kotlin
    internal val url: String = "https://github.com/lordmacu/kino-light/releases/latest/download/latest.json",
```

Then simplify both `check()` and `downloadUrl()` — the Cloudflare-bypass
prefix-stripping (`raw.substringAfter("{", "").let { "{$it" }`) was a
workaround for the old server only; GitHub serves the manifest byte-for-byte.
The full file becomes:

```kotlin
package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateChecker(
    private val client: OkHttpClient,
    internal val url: String = "https://github.com/lordmacu/kino-light/releases/latest/download/latest.json",
) {
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val json = JSONObject(raw)
            val remote = UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                url = json.getString("url"),
                notes = json.optString("notes", ""),
            )
            if (remote.versionCode > currentVersionCode) remote else null
        }.getOrNull()
    }

    /**
     * The APK's URL, whether or not there's a newer version than the installed one.
     *
     * [check] returns `null` when you're already up to date, which is right for the update notice
     * but useless for the TV entry screen's download QR: there the URL is needed always, and it
     * comes from the same `latest.json` so it doesn't go stale once a new version is published
     * (the URL carries the number inside).
     */
    suspend fun downloadUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            JSONObject(raw).getString("url")
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.update.UpdateCheckerTest"
```

Expected: PASS, all 5 tests in the class (4 existing + the new one) green.

- [ ] **Step 6: Make `versionCode`/`versionName` overridable in the Gradle build**

In `app/build.gradle.kts`, change:

```kotlin
        versionCode = 48
        versionName = "0.9.17"
```

to:

```kotlin
        versionCode = readEnv("VERSION_CODE", "48").toInt()
        versionName = readEnv("VERSION_NAME", "0.9.17")
```

This defaults to today's values, so a local `assembleDebug`/`assembleRelease`
with no `VERSION_CODE`/`VERSION_NAME` in `.env` behaves exactly as before.

- [ ] **Step 7: Document the two new optional keys in `.env.example`**

In `.env.example`, right after the `RELEASE_*` block (after line 44), add:

```
# --- Version override (optional, used by the release CI pipeline) ---
# Only needed to test a local `assembleRelease` with a specific version.
# Left empty, the build falls back to the versionCode/versionName hardcoded
# in app/build.gradle.kts.
VERSION_CODE=
VERSION_NAME=
```

- [ ] **Step 8: Update the allowed-hosts list in `.claude/reglas.md`**

In `.claude/reglas.md`, replace lines 19-20:

```
  4. Al **OTA** (`apk.comparadorinternet.co/latest.json`, `UpdateChecker`), para avisar de una
     versión nueva del APK.
```

with:

```
  4. Al **OTA** (`github.com`/`objects.githubusercontent.com`, `UpdateChecker` apunta a
     `github.com/lordmacu/kino-light/releases/latest/download/latest.json`), para avisar de una
     versión nueva del APK y bajar el APK firmado. Reemplazó a un servidor propio
     (`apk.comparadorinternet.co`) que era una violación no documentada de la regla de arriba —
     esta migración la cierra, no abre un hueco nuevo. Ver
     `docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md`.
```

- [ ] **Step 9: Run the full test suite and verify the exact count**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
command grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml | \
  command grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | \
  awk -F'"' '{tests+=$2; skipped+=$4; failures+=$6; errors+=$8} END {print "tests="tests, "skipped="skipped, "failures="failures, "errors="errors}'
```

Expected: `BUILD SUCCESSFUL`, and `tests=1705 skipped=0 failures=0 errors=0`
(one more than the prior 1704 baseline, for the new default-URL test).

- [ ] **Step 10: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/arkiv/player/data/update/UpdateChecker.kt app/src/test/java/com/arkiv/player/data/update/UpdateCheckerTest.kt .env.example .claude/reglas.md
git commit -m "$(cat <<'EOF'
feat(update): point OTA checks at GitHub Releases, not our own server

UpdateChecker's default URL moves to
github.com/lordmacu/kino-light/releases/latest/download/latest.json,
a URL GitHub itself always resolves to the newest release -- and the
Cloudflare-prefix-stripping hack goes with it, since that was only
needed for the old server's JSON transform.

versionCode/versionName become overridable via readEnv() (still
defaulting to today's values for local builds) so the release CI
pipeline can set them per tag without editing committed source on
every release.

This closes an undocumented "servidor propio" gap: apk.comparadorinternet.co
was never meant to exist under this project's own non-negotiable rule.
See docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md.
EOF
)"
```

---

## Task 3: The release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: the ten GitHub Secrets named in the spec's "Secrets required in
  GitHub Actions" table; `readEnv()` and the `VERSION_CODE`/`VERSION_NAME`
  override from Task 2; `UpdateChecker`'s expected `latest.json` shape
  (`versionCode: Int`, `versionName: String`, `url: String`, `notes: String`).
- Produces: on a `vX.Y.Z` tag push, a GitHub Release named `vX.Y.Z` carrying
  `app-release.apk` and `latest.json` as assets.

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Validate tag shape and compute version
        run: |
          TAG="${GITHUB_REF#refs/tags/}"
          if ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "Tag '$TAG' does not match vMAJOR.MINOR.PATCH -- aborting before touching secrets."
            exit 1
          fi
          VERSION_NAME="${TAG#v}"
          MAJOR="$(echo "$VERSION_NAME" | cut -d. -f1)"
          MINOR="$(echo "$VERSION_NAME" | cut -d. -f2)"
          PATCH="$(echo "$VERSION_NAME" | cut -d. -f3)"
          VERSION_CODE=$((MAJOR * 1000000 + MINOR * 1000 + PATCH))
          {
            echo "TAG=$TAG"
            echo "VERSION_NAME=$VERSION_NAME"
            echo "VERSION_CODE=$VERSION_CODE"
            echo "REPO=${{ github.repository }}"
          } >> "$GITHUB_ENV"

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: '17'

      - name: Decode the release keystore
        run: echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Write the .env the build reads
        run: |
          cat > .env <<EOF
          API_KEY=${{ secrets.TMDB_API_KEY }}
          IPTV_3DES_KEY=${{ secrets.MAGIS_3DES_KEY }}
          IPTV_HOSTS=${{ secrets.MAGIS_HOSTS }}
          IPTV_APP_ID=${{ secrets.MAGIS_APP_ID }}
          IPTV_APK_VERSION=${{ secrets.MAGIS_APK_VERSION }}
          CAST_RECEIVER_ID=${{ secrets.CAST_RECEIVER_ID }}
          RELEASE_KEYSTORE_PATH=${{ runner.temp }}/release.jks
          RELEASE_KEYSTORE_PASSWORD=${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS=${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD=${{ secrets.RELEASE_KEY_PASSWORD }}
          VERSION_CODE=${{ env.VERSION_CODE }}
          VERSION_NAME=${{ env.VERSION_NAME }}
          EOF

      - name: Build the signed release APK
        run: ./gradlew :app:assembleRelease

      - name: Extract release notes from the tag's message
        run: |
          NOTES="$(git for-each-ref "refs/tags/$TAG" --format='%(contents)')"
          if [ -z "$NOTES" ]; then
            NOTES="Release $TAG"
          fi
          {
            echo "NOTES<<EOF_NOTES"
            echo "$NOTES"
            echo "EOF_NOTES"
          } >> "$GITHUB_ENV"

      - name: Build latest.json
        run: |
          python3 - <<'PY'
          import json
          import os

          manifest = {
              "versionCode": int(os.environ["VERSION_CODE"]),
              "versionName": os.environ["VERSION_NAME"],
              "url": "https://github.com/{}/releases/download/{}/app-release.apk".format(
                  os.environ["REPO"], os.environ["TAG"],
              ),
              "notes": os.environ["NOTES"],
          }
          with open("latest.json", "w") as f:
              json.dump(manifest, f)
          PY

      - name: Publish the GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "$TAG" \
            app/build/outputs/apk/release/app-release.apk \
            latest.json \
            --title "$TAG" \
            --notes "$NOTES"

      - name: Remove secrets from the runner
        if: always()
        run: rm -f .env "$RUNNER_TEMP/release.jks"
```

- [ ] **Step 2: Lint the YAML locally**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('valid YAML')"
```

Expected: `valid YAML`, no exception.

- [ ] **Step 3: Confirm the workflow references exactly the ten secrets from the spec, no more, no fewer**

```bash
command grep -oE 'secrets\.[A-Z0-9_]+' .github/workflows/release.yml | sort -u
```

Expected output (10 lines):
```
secrets.CAST_RECEIVER_ID
secrets.MAGIS_3DES_KEY
secrets.MAGIS_APK_VERSION
secrets.MAGIS_APP_ID
secrets.MAGIS_HOSTS
secrets.RELEASE_KEY_ALIAS
secrets.RELEASE_KEY_PASSWORD
secrets.RELEASE_KEYSTORE_BASE64
secrets.RELEASE_KEYSTORE_PASSWORD
secrets.TMDB_API_KEY
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "$(cat <<'EOF'
ci: add tag-triggered release workflow

Pushing a vMAJOR.MINOR.PATCH tag builds a signed release APK from ten
GitHub Secrets (Magis/TMDB/Cast keys plus the signing key, mirroring
the local .env), derives versionCode from the tag itself, and
publishes both the APK and a latest.json manifest as assets of a new
GitHub Release.

See docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md
for the full design and the exact `gh secret set` commands to run
before the first tag.
EOF
)"
```

- [ ] **Step 5: Push to `main`**

```bash
git push origin main
```

Pushing to `main` does NOT trigger this workflow (it only listens for `v*`
tags) — this is safe to push immediately, no gate needed here.

---

## Task 4: Upload the ten GitHub Secrets

**Files:** none — this is a `gh` CLI operation against GitHub's servers, run
by the user in their own terminal, never inside this session.

**Interfaces:** Consumes: the real `.env` file's values (never read into this
session). Produces: the ten secrets Task 3's workflow reads at release time.

**This step touches real production credentials (the cracked Magis 3DES key,
the TMDB API key, the release signing password) landing in a third party's
secret store. The agent must not run these commands itself even though it
technically could via the Bash tool — hand them to the user verbatim and wait
for them to confirm they've run them, exactly as decided during
brainstorming ("tú subes los secrets, yo te doy los comandos exactos").**

- [ ] **Step 1: Hand the user these exact commands to run themselves**

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

Tell the user: run these from the repo root, in a terminal with the real
`.env` present (the same one `assembleRelease` already uses locally).

- [ ] **Step 2: Wait for the user to confirm they ran all ten**

Do not proceed to Task 5 on an assumption. Ask directly: "Did all ten `gh
secret set` commands succeed?"

- [ ] **Step 3: Verify the secret names landed (not the values — `gh` never shows those)**

```bash
gh secret list --repo lordmacu/kino-light
```

Expected: ten rows, names matching exactly
`TMDB_API_KEY, MAGIS_3DES_KEY, MAGIS_HOSTS, MAGIS_APP_ID, MAGIS_APK_VERSION,
CAST_RECEIVER_ID, RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD,
RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD`.

---

## Task 5: Cut the first real release tag

**Files:** none.

**Interfaces:** Consumes: Task 3's workflow, Task 4's secrets. Produces: the
first real `latest.json` + `app-release.apk` the app will ever fetch.

**Pushing a tag here immediately triggers a real signed release build and
publishes it publicly. Confirm the exact version number with the user before
tagging — do not guess the next version.**

- [ ] **Step 1: Ask the user what the next version number should be**

The current `versionName` is `0.9.17` (soon to be superseded by whatever this
release bumps to — e.g. `0.9.18`, or higher if other work landed since).
Confirm the exact `vX.Y.Z` before tagging.

- [ ] **Step 2: Confirm with the user immediately before tagging**

Ask: "About to push tag `vX.Y.Z`, which will build and publish a real signed
release right now. Confirm?" Wait for an explicit yes.

- [ ] **Step 3: Create an annotated tag with real release notes**

```bash
git tag -a vX.Y.Z -m "<one or two lines describing what changed, in the same voice as this repo's other release notes — see the 'Nueva versión 0.9.18' example: 'Velo de brillo solo en series y películas, no en canales en vivo.'>"
```

(Replace `vX.Y.Z` and the message with the real, user-confirmed values —
this step cannot be filled in ahead of time since it depends on what the
release actually contains.)

- [ ] **Step 4: Push the tag**

```bash
git push origin vX.Y.Z
```

- [ ] **Step 5: Watch the Actions run**

```bash
gh run watch --repo lordmacu/kino-light
```

Expected: the `Release` workflow run completes with a green checkmark. If it
fails, read the failed step's log — most likely causes are a missing/typo'd
secret (Task 4) or a keystore password mismatch.

- [ ] **Step 6: Verify the release and its assets**

```bash
gh release view vX.Y.Z --repo lordmacu/kino-light
curl -sL https://github.com/lordmacu/kino-light/releases/latest/download/latest.json
```

Expected: the release page lists both `app-release.apk` and `latest.json`;
the `curl` prints the manifest JSON with the right `versionCode`/`versionName`.

---

## Task 6: Verify the OTA flow on a real device

**Files:** none.

**Interfaces:** Consumes: Task 5's published release; `UpdateChecker`,
`UpdateWorker`, `UpdateDialog` (unchanged code, exercised end-to-end for the
first time against the new URL).

- [ ] **Step 1: Install a debug build with a versionCode BELOW the new release, on a real device**

This repeats the pattern already established earlier in this project: connect
over `adb` (USB or wifi via `adb connect <ip>:5555`, using
`~/Library/Android/sdk/platform-tools/adb`, never the Homebrew one), then:

```bash
~/Library/Android/sdk/platform-tools/adb devices -l
~/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Trigger the update check and read the device's own logs**

```bash
~/Library/Android/sdk/platform-tools/adb -s <serial> shell am start -n com.arkiv.player.light/com.arkiv.player.MainActivity
~/Library/Android/sdk/platform-tools/adb -s <serial> logcat -d -t 500 | command grep -iE "ArkivStartup|update"
```

Per this project's own rule ("los tests verdes y los informes mienten, hay
que mirar la salida real" — `.claude/como-trabajar.md`), do not declare this
working from the workflow's green checkmark alone.

- [ ] **Step 3: Confirm the update dialog appears with the right version and notes**

Take a screenshot (`adb shell screencap -p /sdcard/check.png` then `adb pull`)
and visually confirm the dialog shows the new `versionName` and the notes
text from Task 5's tag message — same UI already seen working with the old
server (`UpdateDialog.kt`, unchanged by this plan).

- [ ] **Step 4: Confirm the download completes**

Tap through to download; watch for `DownloadState.Ready` being reached (no
crash, no `Failed` state) — either by observing the UI directly or via
logcat if `ApkDownloader` logs are visible.

- [ ] **Step 5: Report the result plainly**

If every step above was actually observed working, say so with what was
seen (log lines, screenshot description). If anything couldn't be verified
end-to-end (e.g. no second real device available to test installing the
downloaded APK over the running one), say that explicitly rather than
declaring the feature done.
