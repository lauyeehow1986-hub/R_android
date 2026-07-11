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
echo "Vendored WebR ${VERSION} into $DEST"
