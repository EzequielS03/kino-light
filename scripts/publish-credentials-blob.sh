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
plaintext = json.dumps(bundle, separators=(',', ':')).encode()  # no whitespace: native_credentials.cpp's extractField() searches for an exact "key":" substring

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
