# On-Device R Execution (WebR local engine) — Design

**Date:** 2026-07-11
**Status:** Approved (design), pending spec review

## Goal

Run R code **on the device**, with no code or data leaving the phone, by
embedding **WebR** (real GNU R compiled to WebAssembly) as a *local* execution
engine. Local becomes the **default** engine; the existing HTTP backend stays
available as an opt-in "full compatibility" engine.

## Motivation

The app was built as a thin client because real GNU R can't run natively on
Android (Fortran/C internals don't build). `CLAUDE.md` records that the JVM
**Renjin** reimplementation was considered and rejected for the MVP over poor
CRAN/package compatibility. That reasoning still holds for Renjin — but the
landscape changed: **WebR** is *actual* GNU R compiled to WebAssembly, so its
language and package compatibility are far closer to real R, it ships a canvas
graphics device (for plots), and it has a virtual filesystem (which lets us
reuse the backend's table-capture harness). WebR in a WebView is now a viable
on-device engine.

The driving motivation here is **privacy**: a user's code and data should be
able to stay entirely on the device. So the local engine is the default and the
network backend becomes opt-in.

## Scope decisions (from brainstorming)

- **Primary driver:** privacy → **local is the default engine**, backend opt-in.
- **v1 capabilities (must-have):**
  - Baseline: run code on-device → `stdout`/`stderr` + plots.
  - **Table / `View(df)` parity** — printed data frames and the data viewer work
    locally, reusing the existing `RTable` capture logic.
  - **Workspace persists across runs** within an app session (same live WebR
    instance).
- **Runtime delivery:** the WebR runtime (WASM interpreter + base R) is
  **bundled in the APK** → offline from install, zero network ever for local
  execution.

## Non-goals (YAGNI — explicit v1 boundaries)

- **On-device package installation** (WebR's WASM package repo) — deferred
  follow-up. In v1, `library(<CRAN pkg>)` beyond WebR's pre-bundled base/default
  packages is a *remote-engine* capability.
- **Local session data import** — uploaded data files live in the backend
  session; they are **not** visible to local runs in v1 (see "Deliberate v1
  boundary" below). Deferred follow-up.
- **Persist workspace across app restarts** — v1 keeps the workspace only for the
  life of the app process (the live WebR instance). Snapshotting WebR's VFS/global
  env to disk is a deferred follow-up.
- **Per-project engine selection** — v1 has a single global engine choice, not a
  per-project override.
- **On-device help/symbols** (`/help`, `/symbols`) — those stay backend-served in
  v1; code assist is unchanged.

## Deliberate v1 boundary: data & packages are remote-engine features

With the local engine as default, two existing features remain wired to the
**backend session** and do **not** feed local runs in v1:

- **Data screen** (`/upload`, `/data`): uploaded files land in the backend
  session's `data/` dir. A local run's WebR VFS does not contain them, so
  `read.csv("sales.csv")` won't resolve under the local engine.
- **Packages screen** (`/install`, `/packages`): installs into a backend
  per-session library, invisible to WebR.

**Mitigation:** the engine selector makes the active engine obvious, and the
Data/Packages screens surface a short note that they apply to the **Remote**
engine. Local data-import (into WebR's VFS) and local package install (from
WebR's binary repo) are the named fast-follows. This boundary is a documented
limitation, not a bug.

## Architecture

```
                         ┌──────────────── EditorViewModel.runCode() ───────────┐
                         │        builds ExecuteRequest (code | files+entry)     │
                         └───────────────────────┬──────────────────────────────┘
                                                 ▼
                                     ExecutionEngine (interface)
                                     execute(req) -> Result<ExecuteResponse>
                                       ▲                        ▲
                    selected by Settings (default=Local)        │
                                       │                        │
                 ┌─────────────────────┘                        └──────────────────────┐
                 ▼                                                                       ▼
   LocalExecutionEngine                                                     RemoteExecutionEngine
     └─ WebRController                                                        └─ RExecutionRepository
          hidden persistent WebView                                              (existing backend)
          + JS↔Kotlin bridge
          + bundled assets/webr/ (WASM runtime + harness)
                 │
                 ▼
   in-WebView harness (JS + R):
     • withVisible top-level eval loop
     • TABLE_EMIT_HELPERS -> table*.json in VFS -> RTable
     • canvas device -> base64 PNG plots
     • ls(globalenv()) -> workspaceObjects
     • JS timer + webR.interrupt() -> timedOut
                 │
                 ▼
   JSON shaped exactly like ExecuteResponse  ── parsed by kotlinx.serialization
   (stdout, stderr, plots, tables, workspaceObjects, error, timedOut)
```

### `ExecutionEngine` abstraction

```kotlin
interface ExecutionEngine {
    suspend fun execute(request: ExecuteRequest): Result<ExecuteResponse>
    suspend fun reset(sessionId: String): Result<Unit>
}
```

`reset` clears the workspace: `RemoteExecutionEngine` calls the existing
`/reset`; `LocalExecutionEngine` reinitializes the WebR global environment
(`rm(list=ls(globalenv()))` in the live instance). `EditorViewModel.resetSession`
routes through the active engine.

- `RemoteExecutionEngine(repository: RExecutionRepository)` — delegates to the
  existing `repository.execute(...)`. No behavior change.
- `LocalExecutionEngine(controller: WebRController)` — delegates to the WebR
  bridge.
- `EditorViewModel` gains an `ExecutionEngine` dependency (default-arg seam, like
  every other VM dependency) and calls it instead of the repository directly.
  Everything downstream of `ExecuteResponse` (output text, plot decoding,
  `RTableView`, workspace chips, run history) is **unchanged**.

**Engine selection.** `SettingsStore` persists an `ExecutionEngineChoice`
(`LOCAL` default / `REMOTE`). `ServiceLocator` exposes the currently-selected
`ExecutionEngine` (rebuilt when the setting changes, same pattern it already uses
to apply the backend URL). A new **"Execution engine"** control on the Settings
screen flips it. `reset` (workspace clear) routes to whichever engine is active:
the local engine reinitializes the WebR global env; the remote engine calls the
existing `/reset`.

### `WebRController` (the local engine's core)

- Owns **one** offscreen `WebView` created against the Application context,
  created lazily on first local run and kept alive for the app process (this is
  what makes the workspace persist across runs).
- The WebView loads a bundled page via **`WebViewAssetLoader`** under a secure
  `https://appassets.androidplatform.net/…` origin. A custom `WebViewClient`
  (`shouldInterceptRequest`) injects the cross-origin-isolation headers
  (`Cross-Origin-Opener-Policy: same-origin`,
  `Cross-Origin-Embedder-Policy: require-corp`, plus `require-corp` on served
  assets) so `SharedArrayBuffer` is available for WebR's channel.
  **Fallback:** if the `SharedArrayBuffer` channel can't be brought up on a target
  device, fall back to WebR's PostMessage channel (documented limitation: no
  mid-run interrupt; timeout enforced by tearing down/recreating the instance).
- Bridge:
  - Kotlin → JS: `evaluateJavascript("webRBridge.run(<json>)")` with a request id.
  - JS → Kotlin: an `@JavascriptInterface` callback posts the result JSON keyed by
    request id; the controller completes a `CompletableDeferred<String>` awaited
    by the suspend `execute`.
  - All WebView interaction is marshaled to the main thread; `execute` is a
    `suspend` function safe to call from a VM coroutine.
- Boot readiness: `execute` suspends until the page signals WebR is initialized
  (a one-time ready deferred), then runs.

### In-WebView harness (`assets/webr/`)

Bundled files:

- `index.html` — minimal page that loads WebR and `bridge.js`.
- WebR runtime assets (WASM + base R image + JS glue) — vendored under
  `assets/webr/`.
- `bridge.js` — boots a single `WebR` instance (SharedArrayBuffer channel), and
  exposes `webRBridge.run(request)`:
  1. Parse `request` = `{ code?, files?: [{name,content}], entryFile?, sessionId?,
     timeoutSeconds }`.
  2. Write project files (or a single `script.R`) into WebR's VFS.
  3. Run an **R harness** (shipped as an R string, mirroring the backend's
     `/execute` wrapper): a `withVisible` top-level eval loop that
     - captures `stdout`/`stderr`,
     - routes plots through the **canvas device**, collected as base64 PNGs,
     - emits every top-level data-frame / 2-D value as `table*.json` via the
       **same `TABLE_EMIT_HELPERS` logic** the backend uses (rows capped at 200,
       true `totalRows`),
     - `source()`s the entry file for multi-file projects.
  4. After the run: `ls(globalenv())` → `workspaceObjects`.
  5. Read back any `table*.json` from the VFS (`FS.readFile`), decode with the
     unboxed-safe parse (so a 1×1 table isn't collapsed).
  6. Enforce timeout with a JS timer → `webR.interrupt()` → `timedOut = true`.
  7. Post `{ stdout, stderr, plots, tables, workspaceObjects, error, timedOut }`
     back to Kotlin.

The harness **never** contacts the network. The `TABLE_EMIT_HELPERS` R source is
duplicated from the backend into `assets/webr/harness.R` (kept byte-compatible so
`RTable` JSON is identical); the contract note below records this coupling.

### Feasibility spike (plan Task 1)

The WebView + WASM + cross-origin-isolation + WebR-channel path is the real
unknown, and it **cannot be JVM-unit-tested** (needs an emulator/device or manual
run). The plan therefore **opens with a spike**: wire the offscreen WebView +
`WebViewAssetLoader` + COOP/COEP + bundled WebR, boot WebR, and evaluate `1+1`
returning `"[1] 2"` through the bridge. Gate: if the SharedArrayBuffer channel
won't come up, switch to the PostMessage-channel fallback before proceeding. Only
after the spike proves the round-trip do we build the full harness/engine.

## App — models & wiring

- **No new response model.** The bridge returns JSON that deserializes into the
  existing `ExecuteResponse` (`data/model/ExecuteModels.kt`). `RTable` reused
  as-is.
- `data/execution/ExecutionEngine.kt` — the interface + `RemoteExecutionEngine`.
- `data/execution/LocalExecutionEngine.kt` — wraps `WebRController`.
- `data/execution/WebRController.kt` — the WebView/bridge owner (Android-dependent;
  covered by instrumented/manual tests, not JVM unit tests).
- `data/settings/SettingsStore` — persist `ExecutionEngineChoice`; `AppSettings`
  interface gains the getter/setter; `SettingsViewModel` exposes it.
- `ui/settings/SettingsScreen` — an "Execution engine" segmented control
  (Local / Remote) with a one-line explanation and the data/packages caveat.
- `EditorViewModel` — depends on `ExecutionEngine` (via `ServiceLocator`); `runCode`
  and `resetSession` route through it. Run history / output rendering unchanged.

## Testing

**JVM unit tests (CI, no device):**
- `RemoteExecutionEngine` delegates to a fake repository and returns its
  `ExecuteResponse`.
- Engine **selection**: `ServiceLocator`/settings returns the local engine when
  choice is `LOCAL` (default) and the remote engine when `REMOTE`.
- `SettingsStore` persists/restores `ExecutionEngineChoice`; `SettingsViewModel`
  reflects and updates it.
- **Bridge JSON → model**: a captured sample of the bridge's output JSON
  deserializes into `ExecuteResponse` with expected `stdout`/`plots`/`tables`/
  `workspaceObjects`/`timedOut` (proves the contract shape without a WebView).
- `EditorViewModel.runCode` calls the injected `ExecutionEngine` (fake) and maps
  its `ExecuteResponse` into UI state exactly as today.

**Instrumented / manual (device or emulator — can't run in CI JVM):**
- Spike: WebR boots and `1+1` → `"[1] 2"` through the bridge.
- End-to-end: a script producing text + a `plot()` + a printed data frame returns
  all three; a second run sees a variable set by the first (workspace persists);
  an infinite loop hits the timeout → `timedOut`.

**Build/verify note:** the Android SDK isn't available in the remote sandbox, so
app builds and instrumented tests run **locally or in CI**, not in this
environment (same constraint as every prior app feature).

## Error handling

- R error in user code → `error` set, partial `stdout`/`stderr` returned,
  `timedOut=false` (same shape as backend).
- Timeout → `timedOut=true`, `error` describing the cap.
- WebR failed to boot / bridge failure → repository-style `Result.failure` with a
  user-facing message; the editor surfaces it like any run failure. Because the
  runtime is bundled, boot failure is a device/WebView issue, not a network one —
  the message says so and suggests switching to the Remote engine.

## Contract sync note

The local engine emits the **same** `ExecuteResponse` JSON contract as
`POST /execute` (`stdout`, `stderr`, `plots`, `tables`, `error`, `timedOut`,
`workspaceObjects`). The in-WebView `assets/webr/harness.R` duplicates the
backend's `TABLE_EMIT_HELPERS` table-capture logic and must stay byte-compatible
with `backend/plumber.R` so `RTable` JSON is identical on both engines — if you
change table capture on one side, change it on the other. Multi-file execution
mirrors the backend's `files` + `entryFile` `source()` semantics. No new
Kotlin models are introduced.
