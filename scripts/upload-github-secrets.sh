#!/usr/bin/env bash
# Uploads the ten GitHub Actions secrets the release workflow needs
# (.github/workflows/release.yml), reading their values from the real .env
# at the repo root. Run this once after setting up .env, and again any time
# a key rotates.
#
#     ./scripts/upload-github-secrets.sh
#
# Values never appear on screen or in shell history beyond this script's own
# command substitutions -- gh secret set only prints a confirmation line.
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
  local required="${2:-required}"
  local value
  value="$(command grep -m1 "^$key=" "$ENV_FILE" | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//")"
  if [ "$required" = "required" ] && [ -z "$value" ]; then
    echo "Missing or empty $key in $ENV_FILE -- aborting." >&2
    exit 1
  fi
  printf '%s' "$value"
}

# Piped via stdin, not --body: gh reads an empty value fine this way (needed
# for CAST_RECEIVER_ID, which is legitimately blank when the custom Cast
# receiver isn't in use) without ever falling back to its interactive
# "Paste your secret" prompt, which only triggers when stdin is a terminal.
set_secret() {
  printf '%s' "$2" | gh secret set "$1" --repo "$REPO"
}

# Each value is captured in its own plain assignment, not inline as another
# command's argument: `set -e` only actually catches a failing command
# substitution when it's the whole right-hand side of a bare assignment.
# env_value()'s `exit 1` on a missing key silently gets swallowed if the
# substitution instead sits inline inside `set_secret NAME "$(env_value ...)"`.
val="$(env_value API_KEY)"; set_secret TMDB_API_KEY "$val"
val="$(env_value IPTV_3DES_KEY)"; set_secret MAGIS_3DES_KEY "$val"
val="$(env_value IPTV_HOSTS)"; set_secret MAGIS_HOSTS "$val"
val="$(env_value IPTV_APP_ID)"; set_secret MAGIS_APP_ID "$val"
val="$(env_value IPTV_APK_VERSION)"; set_secret MAGIS_APK_VERSION "$val"
val="$(env_value CAST_RECEIVER_ID optional)"; set_secret CAST_RECEIVER_ID "$val"
val="$(env_value RELEASE_KEYSTORE_PATH)"; b64="$(base64 -i "$val")"; set_secret RELEASE_KEYSTORE_BASE64 "$b64"
val="$(env_value RELEASE_KEYSTORE_PASSWORD)"; set_secret RELEASE_KEYSTORE_PASSWORD "$val"
val="$(env_value RELEASE_KEY_ALIAS)"; set_secret RELEASE_KEY_ALIAS "$val"
val="$(env_value RELEASE_KEY_PASSWORD)"; set_secret RELEASE_KEY_PASSWORD "$val"

echo "Done. Verify with: gh secret list --repo $REPO"
