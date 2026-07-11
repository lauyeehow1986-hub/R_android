#!/usr/bin/env bash
# Vendors the WebR runtime into app/src/main/assets/webr/dist so the app runs
# R fully offline. Re-run to update; commit the resulting dist/ directory.
set -euo pipefail
VERSION="0.4.2"
DEST="$(cd "$(dirname "$0")/.." && pwd)/dist"
TMP="$(mktemp -d)"
echo "Fetching webr@${VERSION} from npm..."
( cd "$TMP" && npm pack "webr@${VERSION}" >/dev/null )
TARBALL="$(ls "$TMP"/webr-*.tgz)"
tar -xzf "$TARBALL" -C "$TMP"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$TMP/package/dist/." "$DEST/"
rm -rf "$TMP"

# Android's AAPT auto-gunzips `.gz` assets at packaging time and strips the
# extension, so WebR's runtime request for `*.data.gz` would 404 in the APK
# ("Can't download filesystem image data"). Pre-decompress the VFS images to
# `*.data` and flip each metadata's `gzip` flag so WebR fetches `*.data` directly.
echo "Decompressing WebR VFS images for AAPT compatibility..."
find "$DEST" -name '*.data.gz' -print0 | while IFS= read -r -d '' f; do gzip -df "$f"; done
find "$DEST" -name '*.js.metadata' -print0 | while IFS= read -r -d '' m; do
  sed -i 's/"gzip":true/"gzip":false/g' "$m"
done

echo "Vendored WebR ${VERSION} into $DEST"
