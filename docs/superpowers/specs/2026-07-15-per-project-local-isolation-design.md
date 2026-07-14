# Per-Project Local (WebR) Isolation + Per-Project Engine Choice — Design

**Date:** 2026-07-15
**Status:** Approved, ready for implementation planning

## Goal

Make each project its own isolated R environment on the **Local (WebR) engine** —
its own workspace variables and its own installed package library — and let each
project **remember which engine (Local or Remote) it runs on**, instead of one
app-wide toggle. This closes the last v1 boundary: today Local is a single shared
session, so variables and packages bleed across every project.

## Background / current state

- **Local = one shared WebR session.** `app/src/main/assets/webr/bridge.js`
  `runOnce` always evaluates the harness against `webR.objs.globalEnv`, regardless
  of `req.sessionId`. The workspace persists to a single app-wide
  `webr-workspace.RData`, and the package library is a single app-wide
  `/rmobile/library` (`USER_LIB`) persisted to `webr-library.tar.gz`. So on Local,
  variables and installed packages are shared across all projects.
- **Data is already per-project on Local.** `LocalSessionDataStore` stores under
  `filesDir/localdata/<session>/` and `bridge.js syncData(sessionId)` mirrors into
  `/rmobile/data/<session>` — keyed by the `proj-<id>` session id. No change needed
  there beyond routing its store selection per-project (below).
- **Remote is already fully per-project.** Each project maps to its own backend
  session via `ProjectSession.of(project)` (`sessionId = "proj-<project.id>"`), with
  an isolated server-side workspace *and* package library.
- **Engine choice is app-wide.** A single `SettingsStore.executionEngine`
  (`ExecutionEngineChoice.LOCAL | REMOTE`, default `LOCAL`) read fresh per run by
  `ServiceLocator.currentExecutionEngine()` / `currentSessionDataStore()`. Nothing
  ties the engine to the active project.
- **Snapshot/restore mechanism (proven).** The library and workspace already
  persist across restarts via a best-effort snapshot path: serialize to a WebR VFS
  file → stream bytes to Kotlin `filesDir` in base64 chunks (`SnapshotStore`,
  keyed today by `kind` ∈ {`library`, `workspace`}) → restore at boot. This
  deliberately avoids WebR's IDBFS `FS.mount`, which destabilised the eval channel.
  This design **extends** that mechanism to be per-session.

## Scope

**In scope:**
1. **Per-project engine choice** — each project remembers Local/Remote; switching
   projects switches engine automatically.
2. **Per-project Local workspace isolation** — each project's `globalenv`
   variables are isolated; no bleed between projects.
3. **Per-project Local package library isolation** — each project's installed
   packages are isolated.
4. **Progress feedback** during a project swap.
5. **LRU eviction** of resident library dirs to bound in-process VFS memory.
6. **Swap error-recovery surfacing** (a failed restore is reported, not silent).
7. **Migration** of the existing shared Local workspace + library to the last-open
   project on upgrade.

**Out of scope (explicitly):**
- Multiple concurrent WebR instances (infeasible on-device — memory).
- A per-project **shared-library** opt-in (deferred — see Future work; the seam is
  already open).
- Any change to the Remote engine's isolation (already per-session server-side).

## Approach

**Swap-on-demand, session-keyed, within one WebR instance.** WebR is one heavy
WASM instance in one offscreen WebView; we cannot run one per project. So `bridge.js`
tracks a `currentSession` and, when a session-scoped op targets a different session,
**swaps** the live state: save the outgoing project's workspace to its snapshot,
clear `globalenv`, load the incoming project's workspace, and point `.libPaths()` at
the incoming project's per-session library (restoring it into the VFS if needed).
Per-session snapshot files replace today's single ones.

**Alternatives considered and rejected:**
- **Per-project R environments** (run harness against a named env instead of
  `globalenv`) — breaks R semantics user code depends on (`<<-`, `ls()`,
  `get(envir = globalenv())`, package attachment); library isolation still needs
  `.libPaths()` swapping; all workspaces resident = more memory.
- **Multiple WebR instances** (one WebView per project) — true isolation but
  infeasible: each instance is tens of MB of WASM + a worker; N projects blow up
  memory and boot time.

## Design

### 1. Session-aware Local engine (`bridge.js`)

A single new function is the choke point for all isolation:

```
ensureSession(sessionId):
  if sessionId === currentSession: return
  // swap out the current session
  if currentSession != null:
    onSwapProgress(SAVING_WORKSPACE)
    await snapshotWorkspace(currentSession)   // save.image → stream to its snapshot
    rm(list = ls(globalenv()), envir = globalenv())
  // swap in the requested session
  onSwapProgress(LOADING_WORKSPACE)
  restoreWorkspace(sessionId)                 // streamIn its snapshot → load(envir=globalenv())
  onSwapProgress(RESTORING_LIBRARY)
  ensureLibResident(sessionId)                // restore its lib tarball into VFS if not resident
  .libPaths(c("/rmobile/library/<sessionId>", <base paths>))
  touchResident(sessionId)                    // mark most-recently-used
  evictLibsIfOverCap(sessionId)               // LRU
  currentSession = sessionId
  onSwapProgress(IDLE)
```

`ensureSession(req.sessionId)` (or the op's session) is called at the top of
`runOnce`, `webrPreview`, `webrInstall`, `webrUninstall`, `webrListPackages`, and
`webrReset`. A same-project run (`sessionId === currentSession`) is a no-op and
emits no progress — the normal "Running…" spinner covers it.

`currentSession` starts `null` at boot; boot no longer eagerly restores a global
workspace/library. The app always runs within an active project, so the first
session-scoped op supplies the session and lazily restores it.

**Per-session snapshot files.** Today's single `webr-workspace.RData` /
`webr-library.tar.gz` become per-session:
`webr-workspace-<session>.RData`, `webr-library-<session>.tar.gz`. The workspace
and library snapshot/restore functions gain a `sessionId` parameter and pass it
through `streamOut`/`streamIn`/`streamBytesOut` to the bridge.

**Per-session library dir.** `/rmobile/library/<sessionId>` replaces the single
`/rmobile/library`. `ensureLibResident(sessionId)` untars that session's snapshot
into its dir if this process hasn't already; `ensureUserLib` and the package ops
target the current session's dir. Package ops (`webrInstall`/`webrUninstall`/
`webrListPackages`) `ensureSession` first, then operate on
`/rmobile/library/<currentSession>` and snapshot to that session's tarball.

### 2. LRU library eviction (`bridge.js`)

`residentLibs` is an ordered list of session ids whose lib dir is restored in the
VFS this process (most-recently-used last). After `ensureSession` makes the
incoming session resident, if `residentLibs.length > MAX_RESIDENT_LIBS` (constant,
**3**), evict the least-recently-used session `L` (never the current one): for each
package dir under `/rmobile/library/<L>`, `webR.FS.unmount` if mounted (try/catch —
most are plain files), then `unlink` the dir; drop `L` from `residentLibs`. `L`'s
Kotlin tarball snapshot is untouched, so it re-restores on next visit. Only
libraries accumulate (the workspace `globalenv` holds one session at a time), so no
workspace LRU is needed. All eviction steps are best-effort/swallowed.

### 3. Progress feedback (`bridge.js` → `WebRController` → UI)

A fire-and-forget bridge call `AndroidBridge.onSwapProgress(phase)` with
`phase ∈ { SAVING_WORKSPACE, LOADING_WORKSPACE, RESTORING_LIBRARY, RESTORE_FAILED,
IDLE }`. `WebRController` owns a `MutableStateFlow<SwapPhase>` exposed read-only as
`swapProgress`, surfaced app-wide via `ServiceLocator.swapProgress`. `EditorViewModel`
reflects the phase in its running indicator (e.g. "Switching project… loading
workspace"); `PreviewViewModel`/`PackagesViewModel`/`DataViewModel` collect the same
flow into their loading labels. `ensureSession`'s `finally` resets the phase to
`IDLE`. `SwapPhase` is a Kotlin enum in the `data/execution` package; the bridge
passes the enum name as a string.

### 4. Swap error recovery (`bridge.js`)

A failed restore currently boots that session to an empty workspace silently — which
the user can't distinguish from a genuinely empty project. Two surfacing paths, both
best-effort:
- `ensureSession` records a module-level `lastSwapWarning` string when
  `restoreWorkspace` (or lib restore) throws (e.g. "Couldn't load this project's
  saved workspace; starting empty."). `runOnce` **prepends `lastSwapWarning` to the
  run's stderr** and clears it — the exact `withSkippedNote` pattern already in
  `bridge.js` — so the warning appears in the output panel where the user is looking.
- The progress flow emits `SwapPhase.RESTORE_FAILED` so screens without an output
  panel (Packages/Data/Preview) can show a transient warning.

A restore failure never blocks the run — the run proceeds against the (empty)
workspace and its result is returned normally.

### 5. Kotlin: per-session `SnapshotStore` selection (`WebRController`)

`SnapshotStore` itself is unchanged (one `File`; base64 stays at the bridge
boundary). Selection becomes per `(kind, sessionId)`:

```kotlin
private val stores = java.util.concurrent.ConcurrentHashMap<String, SnapshotStore>()
private fun snapshotStore(kind: String, sessionId: String): SnapshotStore {
    val ext = when (kind) { "workspace" -> "RData"; "library" -> "tar.gz"
        else -> error("Unknown snapshot kind: $kind") }
    val safe = sanitizeSession(sessionId)   // the [A-Za-z0-9_-] filter localDataDir already uses
    return stores.getOrPut("$kind/$safe") {
        SnapshotStore(java.io.File(appContext.filesDir, "webr-$kind-$safe.$ext"))
    }
}
```

The filename builder (`webr-<kind>-<sanitizedSession>.<ext>`) and `sanitizeSession`
are extracted as pure, JVM-unit-testable functions. **All six bridge snapshot
methods gain a `sessionId` parameter**: `snapshotSize(kind, sessionId)`,
`snapshotRead(kind, sessionId, offset, length)`, `snapshotBegin(kind, sessionId)`,
`snapshotAppend(kind, sessionId, b64)`, `snapshotCommit(kind, sessionId)`,
`snapshotDelete(kind, sessionId)`. Each stays wrapped in try/catch (a bad kind →
safe no-op). `onSwapProgress(phase)` is added to the `Bridge` inner class.

`installPackage`/`uninstallPackage`/`listPackages`/`reset` on `WebRController` gain
a `sessionId` parameter, passed as a JS argument to the corresponding bridge call.

### 6. Migrating the existing shared Local state (`WebRController` / startup)

One-time, at startup, pure Kotlin file ops extracted as a testable
`LegacyLocalStateMigration` over a `filesDir` + a last-open session id:

- If `webr-workspace.RData` exists, rename → `webr-workspace-proj-<lastOpenId>.RData`
  (only if the target does not already exist).
- If `webr-library.tar.gz` exists, rename → `webr-library-proj-<lastOpenId>.tar.gz`
  (only if the target does not already exist).
- Runs only when `loadLastOpenProjectId()` returns an id; if none, the legacy files
  are left as-is (harmless — nothing reads the un-suffixed names anymore). The
  rename *is* the idempotency marker (once moved, the legacy names are gone).

Invoked once from `ServiceLocator.init` (which has `appContext` and the
`SettingsStore`) before any WebR op.

### 7. Per-project engine choice (Kotlin)

**Model.** `Project` gains `val engine: ExecutionEngineChoice? = null`. **Null means
"not yet chosen" → fall back to the Settings default.** This makes migration free:
existing persisted projects deserialize with `engine = null` and transparently keep
using the (repurposed) Settings default until the user picks one. New projects are
stamped with the current Settings default at creation (`ProjectOps.newProject` gains
an `engine` parameter; the ViewModel passes `settingsStore.executionEngine`).

**Resolution.** `fun resolvedEngine(project, settings): ExecutionEngineChoice =
project.engine ?: settings.executionEngine`.

**Selection plumbing (`ServiceLocator`).** Replace the no-arg selectors with
choice-taking ones:
- `fun engineFor(choice: ExecutionEngineChoice): ExecutionEngine`
- `fun dataStoreFor(choice: ExecutionEngineChoice): SessionDataStore`

Callers (`EditorViewModel`, `PackagesViewModel`, `DataViewModel`, `PreviewViewModel`)
already resolve the active project; they now compute its resolved engine and pass it.

**UI.** A Local/Remote control in the editor's overflow menu — a menu item showing
the current engine ("Engine: Local ▸") that flips the active project's `engine` and
persists it via `ProjectStore`. The Data/Packages screen captions already adapt to
the active engine; they now read the project's resolved engine.

**Settings toggle.** Kept, but relabelled as the **default engine for new
projects** (it writes `settingsStore.executionEngine`, which now only seeds new
projects and backs null-engine projects). The Settings screen copy is updated
accordingly.

### 8. Reset semantics

`resetSession` (Editor) and project deletion route to the resolved engine. On Local,
`webrReset(id, sessionId)` deletes that session's workspace snapshot and clears
`globalenv` only if it is the live session; `purgePackages` additionally deletes the
session's library snapshot and (best-effort) unmounts/unlinks its resident lib dir
and drops it from `residentLibs`. On Remote, unchanged (`/reset` with the session).

## Error handling & boundaries

Every swap/snapshot/restore/evict step is best-effort and swallowed, matching the
existing snapshot code:
- A failed save leaves the run result and the in-memory workspace intact.
- A failed restore boots that session to an empty workspace/library **and surfaces a
  warning** (Section 4) rather than failing silently.
- A failed eviction leaves the dir resident (memory, not correctness).

**Known edges (documented):**
- **N installs for N projects.** Per-project libraries mean installing the same
  package in N projects performs N on-device installs (accepted per scope; the
  bundled mini-repo keeps common installs offline).
- **Re-untar on revisit.** A library evicted past the LRU cap re-untars from its
  snapshot on the next visit (time, not correctness).
- **Slow swap on a huge workspace.** A multi-hundred-MB workspace save/load can take
  a couple of seconds — surfaced by the progress phase. The existing 200 MB
  workspace-snapshot cap still applies per session.
- **VFS growth within a process.** Resident libs grow within one app process
  (bounded by the LRU cap) and reset on app restart; the durable per-session Kotlin
  snapshots are the source of truth.

## Testing

**JVM unit tests (pure):**
- `LegacyLocalStateMigration`: renames both files to the last-open session names;
  is idempotent (no-op when targets exist / legacy files gone); no-ops when there is
  no last-open project.
- The `webr-<kind>-<session>` filename builder + `sanitizeSession` (valid chars,
  blank → `default`, both kinds' extensions, unknown kind → error).
- `ServiceLocator.engineFor`/`dataStoreFor` return the right engine/store per choice.
- `resolvedEngine`: `project.engine` wins; null → settings default.
- `ProjectOps.newProject` stamps the passed engine; existing `Project` JSON without
  the field deserializes to `engine = null`.

**Device-verified (WebR/WebView-bound), like the existing snapshot work — on-device
matrix:**
1. Two Local projects: `x` set in A is absent in B and vice-versa (isolation both
   directions), across a kill/relaunch.
2. Package installed in A is not visible in B; visible again on return to A.
3. Per-project engine: A=Local, B=Remote; switching projects switches engine with no
   Settings change; new project inherits the Settings default.
4. Migration: an upgrade adopts the prior shared workspace + library into the
   last-open project; other projects start empty.
5. LRU: visiting > 3 Local projects with libraries evicts the oldest resident lib
   (verified by a re-untar/log on revisit); no crash, packages still correct.
6. Progress: switching to a project with a large workspace/library surfaces the swap
   phases; a corrupted/failed restore surfaces the stderr warning + `RESTORE_FAILED`.

## Docs

- `CLAUDE.md`: update the Local-engine section and the v1-boundary paragraph —
  Local is now per-project isolated (workspace + library) and engine choice is
  per-project; remove per-project engine/isolation from "Still not built"; add the
  swap/`ensureSession` model, per-session snapshot files, LRU, progress, and
  migration.
- `app/src/main/assets/webr/README.md`: document the session-swap model, per-session
  snapshot/library files, LRU eviction, and the progress/error surfacing.

## Files touched

- `app/src/main/assets/webr/bridge.js` — `ensureSession`, per-session
  snapshot/restore/lib, `residentLibs` + LRU, `onSwapProgress`, `lastSwapWarning`
  surfacing, `sessionId` on package/reset ops, boot no longer eager-restores a global.
- `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt` —
  per-`(kind, sessionId)` `SnapshotStore` selection + pure filename/sanitizer
  helpers; `sessionId` on the six bridge snapshot methods and package/reset calls;
  `onSwapProgress` bridge method + `swapProgress` StateFlow + `SwapPhase` enum.
- `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt` —
  stop dropping `sessionId`; thread it to the controller for reset/package ops.
- `app/src/main/java/com/rmobile/console/data/execution/LegacyLocalStateMigration.kt`
  — **new**, pure, unit-tested.
- `app/src/main/java/com/rmobile/console/data/ServiceLocator.kt` — `engineFor` /
  `dataStoreFor` (choice-taking); `swapProgress` passthrough; invoke
  `LegacyLocalStateMigration` in `init`.
- `app/src/main/java/com/rmobile/console/data/project/Project.kt` — `engine`
  field (nullable) + `ProjectOps.newProject(engine)`.
- `app/src/main/java/com/rmobile/console/data/settings/SettingsStore.kt` /
  `AppSettings` — unchanged storage; `executionEngine` now semantically the
  "default for new projects" (docs/labels only).
- `EditorViewModel` / `PackagesViewModel` / `DataViewModel` / `PreviewViewModel` —
  resolve the active project's engine and pass it to `engineFor`/`dataStoreFor`;
  collect `swapProgress` into loading state; editor overflow engine toggle.
- `ui/editor/EditorScreen.kt` — overflow engine toggle item.
- `ui/settings/SettingsScreen.kt` — relabel the engine toggle as "default for new
  projects".
- Tests: `LegacyLocalStateMigrationTest`, snapshot filename/sanitizer test,
  `ServiceLocator`/resolution tests, `Project`/`ProjectOps` engine tests.
- `CLAUDE.md`, `app/src/main/assets/webr/README.md` — docs.

## Future work (seams left open, not built)

- **Per-project shared-library opt-in.** A `Project.sharedLibrary: Boolean` where
  `true` routes the *library* kind to a fixed key (e.g. `_shared`) while keeping the
  *workspace* key per-project. Because snapshots are already keyed by
  `(kind, sessionId)`, this plugs in by passing a different session key for the
  library kind — no redesign. Lets advanced users trade isolation for storage.
- **Granular per-phase progress bar / percentages.** The `onSwapProgress` choke
  point supports finer events without redesign.
