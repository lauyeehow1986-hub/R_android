#!/usr/bin/env bash
# Vendors the bundled WebR package repo (seed set + dependency closure) into
# app/src/main/assets/webr/repo. Requires Node 18+ and internet. Commit repo/.
set -euo pipefail
node "$(cd "$(dirname "$0")" && pwd)/fetch-webr-packages.mjs"
