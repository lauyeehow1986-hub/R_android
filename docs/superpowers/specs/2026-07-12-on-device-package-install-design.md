# On-device package installation (Local/WebR engine) — Design

**Date:** 2026-07-12
**Status:** Approved (pending spec review)

## Goal

Let the **Local (WebR) engine** install, list, and uninstall R packages on-device,
so the on-device engine is no longer limited to WebR's built-in package set. This
closes one of the three documented v1 Local-engine gaps (the others — local data
import, per-project engine choice — stay out of scope here).

## Motivation and the offline/privacy tension

The Local engine's identity is *privacy-preserving* (code and data never leave the
phone) and *offline-from-install*. Installing packages is inherently different from
running code:

- WebR can only load **pre-compiled WASM binaries** — there is no compiler
  on-device, so arbitrary CRAN *source* packages are impossible. The only source of
  WebR-compatible binaries is WebR's public repo (`https://repo.r-wasm.org`), a
  curated ~20k-package catalog.
- Reaching that catalog requires a **network fetch at install time**. This does not
  weaken the privacy guarantee — code and data still never leave the device; only
  the package *download* comes IN — but it does mean an online install is not an
  offline operation.

This design resolves the tension with a **hybrid** source (below) so common packages
can be installed fully offline, while the long tail remains reachable online.

## Decisions (locked during brainstorming)

1. **Source: hybrid.** A single install path with a **repos vector** —
   `[bundled-local-repo, https://repo.r-wasm.org]`. WebR searches the APK-bundled
   mini-repo first (offline); anything not bundled falls through to the network.
   One code path, not two.
2. **Persistence: survive app restarts.** Installed packages are stored in a
   persistent library directory (IndexedDB-backed VFS mount) and restored on WebR
   boot. This is the app's *first* cross-restart local persistence; the deferred
   *workspace* persistence stays deferred and separate.
3. **Bundled seed: tidyverse + easystats CORE (plus dependency closures), rest
   online.** Bundling the *full* families would add ~60–120 MB to the APK; bundling
   the packages users call directly gives most of the offline value at ~30–60 MB.
   Everything else installs online on demand.

## Bundled seed set (offline)

The vendoring script downloads these **plus their full recursive dependency
closure** (so each installs with zero network):

- **tidyverse core:** `dplyr`, `tidyr`, `ggplot2`, `readr`, `stringr`, `tibble`,
  `purrr`, `forcats`, `lubridate`
- **easystats core:** `parameters`, `performance`, `effectsize`, `insight`,
  `datawizard`
- **tiny utilities:** `jsonlite`, `cli`

The meta-packages `tidyverse` and `easystats` themselves, and the long tail
(`bayestestR`, `modelbased`, `see`, `report`, `correlation`, …), are **not**
bundled — they install online.

## Architecture: extend the engine abstraction

Package management becomes engine-routed exactly like `execute`/`reset` already are.
Three methods are added to `ExecutionEngine`:

```kotlin
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse>
    suspend fun reset(sessionId: String): Result<Unit>
    // new:
    suspend fun listPackages(sessionId: String): Result<PackagesResponse>
    suspend fun install(request: InstallRequest): Result<InstallResponse>
    suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse>
}
```

- **`RemoteExecutionEngine`** delegates to the existing repository calls
  (`repository.listPackages(sessionId)`, `repository.install(pkg, sessionId)`,
  `repository.uninstall(pkg, sessionId)`), unpacking the request objects. Remote
  behavior is byte-for-byte unchanged.
- **`LocalExecutionEngine`** delegates to new `WebRController` methods backed by new
  bridge JS functions, and maps the bridge JSON into `InstallResponse` /
  `UninstallResponse` / `PackagesResponse`.

The Local engine is **app-wide, not per-project** (unchanged), so its package
library is one global WebR library shared across projects. `sessionId` is accepted
but ignored by the Local engine, consistent with today's Local `execute`/`reset`.

`importLegacy` stays a **Remote-only** concept (there is no legacy library on-device)
and remains on the repository, called directly by the ViewModel only when the Remote
engine is active.

## Component design

### `LocalExecutionEngine` (Kotlin)

New methods serialize the request, call the controller, and parse the bridge's JSON:

```kotlin
override suspend fun listPackages(sessionId: String): Result<PackagesResponse> = runCatching {
    json.decodeFromString<PackagesResponse>(controller.listPackages())
}
override suspend fun install(request: InstallRequest): Result<InstallResponse> = runCatching {
    json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName))
}
override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> = runCatching {
    json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName))
}
```

### `WebRController` (Kotlin)

Three new suspend methods mirror `execute`/`reset`, each `callBridge`-ing a new JS
entry point and returning its JSON:

```kotlin
suspend fun installPackage(pkg: String): String =
    callBridge("window.webrInstall", org.json.JSONObject.quote(pkg))
suspend fun uninstallPackage(pkg: String): String =
    callBridge("window.webrUninstall", org.json.JSONObject.quote(pkg))
suspend fun listPackages(): String =
    callBridge("window.webrListPackages", null)
```

No change to the WebView plumbing except that the bundled repo directory (below) is
served by the existing `WebViewAssetLoader` automatically (it already serves
everything under `assets/`).

### `bridge.js`

- **Boot:** after `webR.init()`, mount the persistent library dir and prepend it to
  `.libPaths()` (see Persistence). This runs once, before `onReady()`.
- **`window.webrInstall(id, pkg)`:** calls
  `webR.installPackages([pkg], { repos: [LOCAL_REPO_URL, "https://repo.r-wasm.org"], quiet: true })`
  inside try/catch; on success `syncfs(false)` to flush the library to IndexedDB;
  posts back `InstallResponse`-shaped JSON
  (`{ installed, stdout, stderr, error, timedOut:false, systemRequirements:null }`).
  `LOCAL_REPO_URL = new URL('./repo', document.baseURI).href`.
- **`window.webrUninstall(id, pkg)`:** `remove.packages(pkg, lib=<persist dir>)` via
  `evalRVoid`, then `syncfs(false)`; posts `{ removed:true }` (or `{ removed:false,
  error }` if the package wasn't there / call threw).
- **`window.webrListPackages(id)`:** `rownames(installed.packages())` (or
  `.packages(all.available=TRUE)`) → `{ packages: [...] }`. Includes both bundled
  built-ins and anything installed into the persistent lib.

Install has no artificial timeout guard for v1 (unlike `execute`'s 20s) — a
first-time online install of a large family legitimately takes a while; the UI shows
an indeterminate spinner (existing `installing` state). *(If needed, a generous
timeout can be added later; noted as a possible follow-up, not built now.)*

### Bundled mini-repo (`app/src/main/assets/webr/repo/`)

Mirrors r-wasm's binary layout so WebR treats it as a normal repo:

```
assets/webr/repo/bin/emscripten/contrib/<R-minor>/PACKAGES        # index
assets/webr/repo/bin/emscripten/contrib/<R-minor>/<pkg>_<ver>.tgz # binaries
```

`<R-minor>` matches WebR 0.4.2's R version (verified during implementation).
Committed as binary (extend `.gitattributes` `-text` coverage to `repo/**`). These
are `.tgz`, not `.gz`, so the AAPT auto-gunzip issue that bit the VFS images does
**not** apply here — but implementation double-checks nothing under `repo/` ends in
a bare `.gz` that AAPT would rewrite.

### Vendoring script (`scripts/fetch-webr-packages.sh`)

New sibling to `fetch-webr.sh`. Given the seed list, it:

1. Fetches r-wasm's `PACKAGES` index for the matching R minor version.
2. Resolves the **full recursive dependency closure** of the seed set from that
   index (Depends/Imports/LinkingTo, minus base packages).
3. Downloads each resolved `<pkg>_<ver>.tgz` into the local repo layout.
4. Writes a local `PACKAGES` index containing exactly the vendored set.

Re-runnable; its package list is a single array at the top of the script, so the
bundled set is trivially edited. Documented in the webr `README.md`.

### `PackagesViewModel`

- Add an `engineProvider: () -> ExecutionEngine = { ServiceLocator.currentExecutionEngine() }`
  constructor seam (mirrors `EditorViewModel.engineProvider`), and route `refresh`,
  `install`, `uninstall` through `engineProvider().listPackages/install/uninstall`
  instead of `repository.*`. Behavior/branching (installed flag, systemRequirements,
  error messages) is unchanged — only the call target moves.
- `importLegacy` stays on the repository (Remote-only) and the "Import packages from
  legacy library" affordance is hidden when the Local engine is active.
- `PackagesUiState` gains an `engineIsLocal: Boolean` (read once in `init` from
  `ServiceLocator`), used purely for captions and to hide `importLegacy`.

### UI (`PackagesScreen`)

Reuse the existing screen unchanged in layout — it already does install / uninstall /
list. Changes:

- The caption reflects the active engine: "On-device (Local) library" vs the current
  Remote wording. The existing "these apply to the Remote engine" note is replaced by
  this engine-aware caption.
- The "Import from legacy library" row is shown only when `!engineIsLocal`.

`DataScreen`'s "applies to the Remote engine" note is unaffected (data import is still
Remote-only). `CLAUDE.md`/`README.md` "Local engine has no package install" claims are
updated to reflect the new capability and its boundaries.

## Persistence mechanism

On boot, before `onReady()`:

```js
// exact FS API verified on-device
await webR.evalRVoid('dir.create("/rmobile/library", showWarnings = FALSE, recursive = TRUE)');
await webR.FS.mount("IDBFS", {}, "/rmobile/library");   // IndexedDB-backed
await webR.FS.syncfs(true);                              // populate from IndexedDB
await webR.evalRVoid('.libPaths(c("/rmobile/library", .libPaths()))');
```

- Installs land in `/rmobile/library` (first on `.libPaths()`), so they are found by
  `library()` and survive because IndexedDB lives in the app's WebView data dir.
- After each successful install/uninstall, `syncfs(false)` flushes to IndexedDB.
- IndexedDB is cleared on app uninstall / clear-data (acceptable).

**Device-verify item (honest caveat):** whether IDBFS is compiled into WebR 0.4.2 is
confirmed on-device first. If IDBFS is unavailable, the fallback is a **manual
snapshot/restore**: after a successful install, tar the library dir inside R
(`utils::tar`) → read the archive bytes via `webR.FS.readFile` → hand them to Kotlin
to write under `filesDir`; on boot, Kotlin reads the archive back, `webR.FS.writeFile`s
it into the VFS, and R untars it into `/rmobile/library`. The bridge/controller
surface is the same either way; only the persistence internals differ. The chosen
mechanism is recorded in the plan's first task.

## Data flow

- **Install (offline hit):** ViewModel → engine.install → controller.installPackage →
  bridge `installPackages(repos=[local,remote])` resolves package + deps from the
  bundled repo (no network) → `syncfs(false)` → `InstallResponse{installed:true}` →
  list refreshes.
- **Install (online fallback):** same path; WebR fetches the `.tgz` from
  `repo.r-wasm.org` because it's absent from the local repo → persisted → refresh.
- **List:** ViewModel → engine.listPackages → bridge `installed.packages()` →
  `PackagesResponse`.
- **Uninstall:** ViewModel → engine.uninstall → bridge `remove.packages` →
  `syncfs(false)` → `UninstallResponse{removed}` → refresh.
- **Boot restore:** WebView (re)created → bridge boot mounts IDBFS + `syncfs(true)` →
  previously installed packages present before the first run.

## Error handling

- **No network + package not bundled:** `installPackages` throws; bridge catches and
  returns `{ installed:false, error: "<pkg> isn't bundled and couldn't be downloaded
  (no network?)." , stderr:<msg> }`. UI shows it via the existing error state.
- **Unknown package name:** WebR error surfaced as `error` text.
- **`systemRequirements`:** always `null` for Local (WASM binaries carry their own
  system libs); the field stays in the contract for Remote parity.
- **Bridge/JS exceptions:** caught and returned as `{ installed:false, error }`,
  never crashing the WebView; `runCatching` in the engine maps any decode failure to
  `Result.failure`.

## Testing

- **JVM / CI-covered (pure logic):**
  - `PackagesViewModel` routes install/uninstall/refresh through a `FakeEngine`
    (install success, install failure, uninstall, list) — mirrors the existing
    `EditorViewModel` engine-routing test.
  - `engineIsLocal` caption/`importLegacy`-visibility logic.
  - `RemoteExecutionEngine`'s new methods unpack requests to the right repository
    calls (via a `FakeApi`/fake repository), preserving current Remote behavior.
- **Device-gated (WebR specifics, CI cannot cover — consistent with the whole
  on-device feature):**
  - Offline install of a bundled package (airplane mode) succeeds.
  - Online install of a non-bundled package succeeds and persists.
  - Packages survive a WebView teardown + app relaunch (persistence).
  - `library()` of an installed package works in a subsequent run.
  These are listed as an explicit manual verification checklist in the plan's final
  task and documented as device-verified in `CLAUDE.md`.

## Boundaries / non-goals

- **Not per-project:** the Local library is global (engine choice is app-wide). A
  Local install is visible to every project while Local is selected.
- **No source packages / no compilation:** WASM binaries only; a package absent from
  both the bundled repo and r-wasm's catalog simply can't be installed.
- **Unchanged:** Local-engine data import and cross-restart *workspace* persistence
  remain deferred. Remote package management is untouched.
- **APK growth is expected** (~30–60 MB from the bundled core) and is the accepted
  cost of offline installs for the core families.

## Docs to update

- `CLAUDE.md` — Local engine now supports package install (hybrid source, persistent
  library); update the v1-boundaries paragraph and the harness/bridge parity notes;
  document the bundled repo + vendoring script.
- `README.md` — Features list (Local engine can install packages; core families
  offline, rest online) and the "Not built yet" list (drop on-device package
  install).
- `app/src/main/assets/webr/README.md` — the bundled repo layout, the seed set, and
  `fetch-webr-packages.sh`.
