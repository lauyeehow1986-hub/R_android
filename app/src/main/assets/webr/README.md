# Vendored WebR runtime

This directory bundles [WebR](https://docs.r-wasm.org/webr/) — real GNU R
compiled to WebAssembly — so the Android app can run R **fully offline**, with
no backend round-trip, inside a hidden WebView.

## What's here

- `scripts/fetch-webr.sh` — vendors the runtime into `dist/` (and applies the
  AAPT `.gz` fix below).
- `index.html` + `bridge.js` — the page loaded in the offscreen WebView; boots
  WebR and exposes `window.webrRun(id, requestJson)` /
  `window.webrReset(id, sessionId, purge)` (plus the session-scoped package/preview
  entrypoints) to Kotlin, running the harness and posting back
  `ExecuteResponse`-shaped JSON.
- `harness.R` — the R harness sourced per run (mirrors the backend `/execute`
  wrapper: a `withVisible` loop + the `TABLE_EMIT_HELPERS` table emitter).
- `dist/` — the WebR runtime itself (committed, so the app is
  offline-from-install). Populated by the fetch script.

## Version

**WebR 0.4.2.**

## How `dist/` is produced

Run, from the repo root, on a machine with `npm` + internet:

```bash
bash app/src/main/assets/webr/scripts/fetch-webr.sh
```

The script runs `npm pack webr@0.4.2`, copies the package's `dist/` directory
into `app/src/main/assets/webr/dist/`, then applies the AAPT fix below. After
running, verify these exist and then commit `dist/`:

- `dist/webr.mjs` — the WebR ES module entry point.
- `dist/R.bin.wasm` — the R interpreter compiled to WebAssembly.
- `dist/vfs/` — the R filesystem image (base packages, etc.), as `*.data` +
  `*.js.metadata` pairs (WebR 0.4.2 has no single `R.bin.data`).
- the worker / service-worker JS files.

The `dist/` directory is committed to the repo so the app ships the runtime and
works from first install without any download.

## AAPT `.gz` fix (important)

WebR ships its VFS images as `*.data.gz` with metadata marked `"gzip":true`.
Android's AAPT **auto-gunzips any `.gz` asset at packaging time and drops the
`.gz` extension**, so in the built APK only `*.data` exists — and WebR's runtime
request for `*.data.gz` 404s with *"Can't download filesystem image data"*.
`fetch-webr.sh` therefore pre-decompresses every `*.data.gz` to `*.data` and
rewrites each `*.js.metadata` to `"gzip":false`, so WebR fetches the `*.data`
directly (which survives AAPT untouched). Do not re-introduce `.gz` assets here.

## Bundled package repo (`repo/`)

The Local engine can install R packages on-device. A small, curated set installs
**fully offline** from a mini-repo bundled here; anything else downloads from
WebR's public repo (`https://repo.r-wasm.org`). The bridge calls
`webr::install(pkg, repos = c(<bundled repo>, "https://repo.r-wasm.org"))`, so the
bundled repo is searched first and the network is the fallback.

- `repo/bin/emscripten/contrib/<R-minor>/` mirrors r-wasm's binary layout: a
  `PACKAGES` index plus `<pkg>_<ver>.tgz` WASM binaries.
- `scripts/fetch-webr-packages.mjs` (run via `scripts/fetch-webr-packages.sh`,
  needs Node 18+ and internet) resolves the **full recursive dependency closure**
  of the seed set from r-wasm and vendors every `.tgz`, then writes a local
  `PACKAGES`. The seed set is a single array at the top of the `.mjs`
  (tidyverse core: dplyr, tidyr, ggplot2, readr, stringr, tibble, purrr, forcats,
  lubridate; easystats core: parameters, performance, effectsize, insight,
  datawizard; plus jsonlite, cli). Edit it to change what ships offline.
- These are `.tgz`, not `.gz`, so the AAPT auto-gunzip issue that affects the VFS
  images (above) does **not** apply — do not introduce bare `.gz` files under
  `repo/`.
- Installed packages land in a **per-session** user library
  (`/rmobile/library/<session>`, prepended to `.libPaths()` by the package ops /
  `ensureSession` — never at boot, so the run path is unaffected). Each session's
  library **persists across app restarts** via snapshot/restore: after each
  install/uninstall it's `utils::tar`'d and streamed to Kotlin in base64 chunks
  (stored under `filesDir` as `webr-library-<session>.tar.gz`), then written back and
  `utils::untar(..., tar = "internal")`'d when that session first becomes live.
  `tar="internal"` is required — the default `untar` shells out via `system()`, which
  Emscripten/WebR forbids. This deliberately avoids WebR's IDBFS `FS.mount`, which
  destabilised the eval channel.
- The Local **workspace** persists the same way, **per session**: `save.image` to
  `/rmobile/workspace.RData`, streamed to `filesDir` as `webr-workspace-<session>.RData`
  and `load()`ed back into `globalenv()` when that session becomes live. It's
  snapshotted after each successful run (fire-and-forget, guarded so it can't race the
  next run) and skipped above a 200 MB blob; a `webrReset` or an emptied workspace
  clears it. Kotlin's `SnapshotStore` (selected per `(kind, session)`, kinds
  `library` | `workspace`, filenames from `SnapshotNaming`) is the shared transport.
- **Session swap (`ensureSession`).** WebR is one instance, so each project (a
  `proj-<id>` session) takes turns owning the live `globalenv()` and `.libPaths()`.
  `ensureSession(sessionId)` is the choke point called at the top of every
  session-scoped op (run, preview, install/uninstall/list, reset). When the incoming
  session differs from the live one it **swaps**: `save.image` the outgoing session's
  workspace, clear `globalenv()`, **unload the outgoing project's `library()`'d
  packages** (`resetPackagesToBase` detaches/unloads everything beyond the base set
  captured at boot — R namespaces load into the one shared process, so this stops a
  package loaded in one project from staying live in another), `load()` the incoming
  session's workspace, and point `.libPaths()` at its lib dir (restoring the tarball
  into the VFS if not already resident). A same-session op is a no-op. Like an app
  restart, a swap does not re-attach the incoming project's packages — user code
  re-runs `library(...)` (the workspace snapshot stores objects, not the search path). Migration of the pre-per-project shared
  `webr-workspace.RData` / `webr-library.tar.gz` into the last-open project is a
  one-time Kotlin file rename (`LegacyLocalStateMigration`) run at startup.
- **LRU library eviction.** `residentLibs` tracks which session lib dirs are in the
  VFS (use-order); past `MAX_RESIDENT_LIBS` (3) the least-recently-used one is
  unmounted + unlinked (it re-restores from its Kotlin tarball on next visit),
  bounding in-process VFS growth. Only libraries accumulate — `globalenv()` holds one
  session at a time.
- **Swap progress + error surfacing.** `ensureSession` emits phases via
  `AndroidBridge.onSwapProgress(SAVING_WORKSPACE|LOADING_WORKSPACE|RESTORING_LIBRARY|
  RESTORE_FAILED|IDLE)` (a "Switching project…" indicator). A failed workspace restore
  boots that session empty **and** records a `lastSwapWarning` that the next run
  prepends to its stderr, so the user sees it rather than silently getting a blank
  workspace.
- `bridge.js` strips `\r` from `harness.R` on load, and `.gitattributes` pins
  `webr/*.R` to LF: a CRLF checkout otherwise leaves a stray `\r` after `local({`
  that WebR's R parser rejects, breaking every run.

## Session data files (`/rmobile/data`)

The Local engine can read uploaded data files. They're stored on-device by
`LocalSessionDataStore` under `filesDir/localdata/<session>/`; `bridge.js` mirrors
them into WebR's in-memory FS on demand:

- `WebRController` exposes them to JS via `AndroidBridge.dataList(session)` and
  `dataChunk(session, name, offset, length)` (base64, chunked — like the library
  snapshot).
- `syncData(session)` mirrors the session's files into `/rmobile/data/<session>`,
  pulling only new/size-changed files and dropping deleted ones (a no-op once the
  VFS already matches, so ordinary runs stay fast; after an app restart the VFS is
  empty and the next run re-pulls).
- `runOnce` calls `syncData` after `resetRunDir`, then — after writing the run's
  own files — `linkData(session)` symlinks each data file into `/rmobile/run` so
  `read.csv("x.csv")` resolves by bare name. Linking after the run files means a
  same-named run file shadows a data file. **If `file.symlink` isn't supported by
  this WebR build, switch `linkData` to `file.copy`.**
- `window.webrPreview(id, requestJson)` renders a file (by extension) or a
  workspace object as one `RTable` (read-only), for the on-device data viewer. A
  preview is a peek: for `.csv`/`.tsv` it pulls only an **8 MB prefix** of the file
  into the VFS and reads the first 201 rows (`nrows = 201L`), so a large CSV
  previews on-device without loading it all into memory; `.rds` still needs a full
  sync (and shows the "too large for on-device" message if it doesn't fit).

## License

WebR is distributed under the **GPL** (GNU General Public License) — the same
license as GNU R itself. The vendored contents of `dist/` are therefore
GPL-licensed. See the WebR project (https://github.com/r-wasm/webr) for the full
license text and details.
