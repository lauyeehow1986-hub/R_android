# On-device help + symbols (engine-routed, local-first) — Design

**Date:** 2026-07-19
**Status:** Approved (ready for planning)

## Problem

In-app R **help** (`?aes`, the `? <token>` chip, the top-bar `?` search) and
autocomplete's **symbol/package index** are backend-only. `EditorViewModel.showHelp`
calls `repository.help(...)` → the Remote backend `/help`, and `refreshSymbols` calls
`repository.listSymbols(...)` **and** `repository.listPackages(...)` → the backend
`/symbols` and `/packages`. None of these are engine-routed.

On a **Local (WebR) project with no reachable backend** — the default, offline setup —
these calls fail. On a physical phone still pointed at the emulator alias
`10.0.2.2:8000`, tapping `? aes` produces:

> failed to connect to /10.0.2.2 (port 8000) from /192.168.4.29 (port 52216) after 15000ms

Code execution already runs fully on-device via WebR; help and completions should too.

## Goal

- `?aes` and autocomplete work **offline** on Local projects (on-device via WebR).
- **No behavior change** for Remote projects.
- When a Local lookup comes up empty, **fall back to the Remote backend** if one is
  reachable (local-first, remote-fallback). A failed fallback degrades quietly.

## Non-goals

- No change to the backend endpoints (`/help`, `/symbols`, `/packages` stay as-is).
- No new response models — Local produces the existing `HelpResponse` / `SymbolsResponse`
  shapes.
- No change to how completions are assembled/filtered (`CompletionOps`, `BaseRSymbols`,
  the suggestion strip) — only the *source* of the dynamic index changes.

## Approach

Mirror the existing `preview` pattern exactly. `help` and `symbols` become
`ExecutionEngine` methods; both engines return the same contract, so the ViewModel is
engine-agnostic. The **local-first / remote-fallback policy lives in the ViewModel**, so
the engines stay pure:

- `LocalExecutionEngine` depends only on `WebRController`.
- `RemoteExecutionEngine` depends only on the repository.

This is the same separation used for `execute`/`preview`/`install`.

## Components

### 1. `ExecutionEngine` (interface + both implementations)

Add two methods, shaped like `preview`:

```kotlin
suspend fun help(topic: String, sessionId: String, libraryKey: String? = null): Result<HelpResponse>
suspend fun symbols(sessionId: String, libraryKey: String? = null): Result<SymbolsResponse>
```

- **`RemoteExecutionEngine`**: `help` → `repository.help(topic, sessionId)`;
  `symbols` → `repository.listSymbols(sessionId)`. (`libraryKey` ignored, as with other ops.)
- **`LocalExecutionEngine`**: `help` → `WebRController.help(json, libraryKey)`;
  `symbols` → `WebRController.symbols(sessionId, libraryKey)`. Parse the returned JSON
  into `HelpResponse` / `SymbolsResponse` with kotlinx.serialization (same as the
  bridge's other results).

### 2. `WebRController`

Two new bridge methods, using the existing `callBridge` helper (as `preview` does):

```kotlin
suspend fun help(requestJson: String, libraryKey: String): String =
    callBridge("window.webrHelp", JSONObject.quote(requestJson), JSONObject.quote(libraryKey))
suspend fun symbols(sessionId: String, libraryKey: String): String =
    callBridge("window.webrSymbols", JSONObject.quote(sessionId), JSONObject.quote(libraryKey))
```

### 3. `assets/webr/bridge.js`

Two new functions, each `ensureSession(sessionId, libraryKey)` first (so `.libPaths()`
points at the project's session library), then run R in the live WebR instance and post
an `ExecuteResponse`-style JSON via `AndroidBridge.onResult`.

- **`webrHelp(id, requestJson, libraryKey)`** — run the **same R the backend uses**
  ([plumber.R `/help`](../../backend/plumber.R)):
  1. `h <- tryCatch(eval(substitute(utils::help(TT), list(TT = as.name(topic)))), error = function(e) NULL)`
  2. if resolved: `rd <- utils:::.getHelpFile(as.character(h)[1])`, then
     `tools::Rd2txt(rd, out = "/tmp/rmobile_help.txt")`, and record the package name
     (`basename(dirname(dirname(path)))`).
  3. Read the text back, **strip terminal overstrike** with `gsub(".\010", "", text)`
     (byte-for-byte with the backend — `\010` is backspace/`\b`).
  4. Post `{ topic, packageName, text, found }` (`found = nzchar(text)`).
  - Topic is validated app-side already, but the bridge still passes it as a quoted R
    string (never interpolated into code paths beyond `as.name`).
- **`webrSymbols(id, sessionId, libraryKey)`** — enumerate completion names: exports of
  the base + default-attached packages plus the session's `library()`-d packages
  (mirroring the backend `/symbols` set). Post `{ symbols: [...] }`.

The help/symbols R is **not** a byte-for-byte parity contract like `harness.R` /
`TABLE_EMIT_HELPERS` — `/help` and `/symbols` are standalone endpoints, so only
*functional* parity is required (same resolution + same overstrike stripping).

### 4. `EditorViewModel`

Route both lookups through the resolved engine, keeping the existing guards.

- **`showHelp(topic)`**: resolve `choice = ExecutionEngineChoice.resolve(project.engine, default)`,
  `engine = engineProvider(choice)`, `session = ProjectSession.of(project)`,
  `libraryKey = ProjectSession.libraryKey(project)`. Call `engine.help(topic, session, libraryKey)`.
  **If `choice == LOCAL`** and the result is a failure **or** `!found`, best-effort call
  `repository.help(topic, session)` and use it if it succeeds and is found. Keep the
  `helpRequestId` generation guard so a stale/dismissed lookup can't resurface.
- **`refreshSymbols()`**: resolve the engine the same way. Call `engine.symbols(session, libraryKey)`
  and `engine.listPackages(session, libraryKey)`. **If `choice == LOCAL`** and either call
  fails, best-effort fall back to `repository.listSymbols(session)` / `repository.listPackages(session)`.
  Keep the stale-session guard (`ProjectSession.of(project) == session` before applying).

## Data flow

- **Remote project:** `showHelp`/`refreshSymbols` → engine (Remote) → repository → backend.
  Identical to today.
- **Local project:** → engine (Local) → `WebRController` → `bridge.js` → WebR on-device,
  returning synchronously offline. The backend is touched **only** when the on-device
  result is empty *and* a backend is configured/reachable.

## Error handling

- On-device help **not found** + no/unreachable backend → `HelpState.NotFound(topic)`
  (a plain "No help found for X"), **not** `HelpState.Error` — no connection banner. This
  is what removes the screenshot's 15-second `10.0.2.2` timeout.
- **WebR not ready** → the engine returns `Result.failure` → triggers the remote fallback
  (or NotFound if no backend).
- A **failed remote fallback** is swallowed; the local NotFound is shown.
- Symbols failures are non-fatal (as today): the strip still shows `BaseRSymbols` +
  workspace objects; a failed refresh just leaves the dynamic index empty.

## Testing

**JVM (unit):**
- `EditorViewModelTest`:
  - `showHelp` on a Local project routes to `engine.help` (not the repository) and shows
    the engine's result when found.
  - Local + engine returns `!found` → repository fallback is consulted; its found result
    is shown.
  - Local + engine failure → repository fallback consulted.
  - Remote project → repository fallback is **never** consulted (engine only).
  - Fallback also failing → `HelpState.NotFound`.
  - `refreshSymbols` on Local routes to `engine.symbols`/`engine.listPackages`; on failure
    falls back to the repository.
- `RemoteExecutionEngineTest`: `help`/`symbols` delegate to the repository.
- Update every `ExecutionEngine` fake (in `EditorViewModelTest`, `PreviewViewModelTest`,
  `PackagesViewModelTest`, `RemoteExecutionEngineTest`) with the two new methods.

**On-device acceptance (manual — WebR can't run under JVM tests):**
1. Airplane mode ON, Local project: `?sum` renders help text offline.
2. Install ggplot2 on-device, then `?aes` renders offline.
3. Autocomplete shows an installed package's completions offline.
4. Remote project still resolves help/completions via the backend.

## Feasibility risk (de-risked by local-first)

Whether WebR ships the base packages' Rd help databases (`<pkg>/help/*.rdb`) determines
on-device coverage for base functions. The **local-first-then-remote** policy fully
de-risks this: if base help is absent on-device it silently falls back to Remote, and
installed r-wasm.org packages (which do ship help) still resolve locally. **Plan Task 1 is
an on-device spike** (`?sum` offline) to measure real coverage before building the rest.

## Files touched

- `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`
- `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- `app/src/main/assets/webr/bridge.js`
- `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test fakes: `EditorViewModelTest.kt`, `PreviewViewModelTest.kt`, `PackagesViewModelTest.kt`,
  `RemoteExecutionEngineTest.kt`
- `CLAUDE.md` (note help/symbols are now engine-routed, local-first with remote fallback)
