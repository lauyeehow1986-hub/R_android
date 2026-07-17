# Per-project shared-library opt-in (Local engine) — Design

**Date:** 2026-07-17
**Status:** Approved, ready for planning

## Goal

Let an individual project **opt into a package library shared across projects**,
trading the per-project package isolation (shipped in the per-project-local-isolation
feature) for reduced on-device storage. A project's **workspace variables and data
files stay per-project**; only its **package library** becomes shared. Applies to the
**Local (WebR) engine only** — the phone's in-memory VFS is the tight resource this
addresses; the Remote backend keeps its per-session libraries unchanged.

## Motivation

Per-project isolation gives every project its own `/rmobile/library/<session>` on the
Local engine. Advanced users who install the same heavy packages (e.g. the tidyverse
closure) into many projects pay that storage cost per project. A per-project opt-in lets
them point selected projects at one shared library instead, while projects that want
isolation keep it (the default).

## Decisions (locked during brainstorming)

1. **Engine scope:** Local (WebR) only. No backend contract change.
2. **Switch semantics:** *just switch the pointer*. Toggling only changes which library
   a project uses. The project's own isolated library is left on disk untouched and
   reappears if toggled back. No package migration/copy. Fully reversible.
3. **Toggle placement:** a switch on the **Packages screen**, shown only for Local-engine
   projects (hidden for Remote). Flipping it reloads the package list to reflect the
   now-active library.
4. **Data files stay per-project** (keyed by session id), same as workspace. Only the
   package library is shared.
5. **No shared-library management UI in v1** (no list/clear-the-shared-library screen);
   it grows by installs and shrinks by uninstalls from any opted-in project. Deferred.

## Architecture (Approach 1: explicit "library key")

The whole feature is one idea: decouple a project's **library key** from its
**session id**. Today both are `proj-<id>`. With this change:

- **session id** = `ProjectSession.of(project)` = `proj-<id>` — keys the **workspace**
  and **data files** (unchanged).
- **library key** = `ProjectSession.libraryKey(project)` = `"shared"` when the project
  opted in, else `proj-<id>` — keys the **package library** (`.libPaths`, library
  residency, LRU, and the library tar.gz snapshot).

`sessionId` keeps meaning "this project"; a separate key means "which library." No call
site is left ambiguous. The existing snapshot machinery already accepts an arbitrary key
string (`SnapshotNaming.sanitize("shared")` → `"shared"`), so cross-restart persistence
of the shared library comes for free (`webr-library-shared.tar.gz`).

### Two correctness invariants the implementation must hold

1. **Project delete / reset must never purge the shared library.** Deleting or resetting
   one shared project clears its workspace + data but must **skip** the library purge when
   the library key is `"shared"`, so it can't nuke packages other projects rely on.
2. **`resetPackagesToBase` still runs on every session swap** — even when two shared
   projects switch and the library dir stays resident, loaded namespaces must still unload
   so a package `library()`'d in one project's run doesn't leak into the next.

## Components

### 1. Data model
- `Project` gains `val sharedLibrary: Boolean = false` (last field; kotlinx default →
  JSON back-compat, no migration).
- `ProjectOps.newProject(..., sharedLibrary: Boolean = false)` threads the flag.
- `ProjectSession`:
  ```kotlin
  const val SHARED_LIBRARY_KEY = "shared"
  fun libraryKey(project: Project): String =
      if (project.sharedLibrary) SHARED_LIBRARY_KEY else of(project)
  ```
  `of(project)` (`proj-<id>`) is unchanged.

### 2. App-side threading
- `ExecutionEngine` — the Local-relevant ops (`execute`, `reset`, `install`, `uninstall`,
  `listPackages`, `preview`) gain a trailing `libraryKey: String? = null`.
  `RemoteExecutionEngine` ignores it (backend unchanged). `LocalExecutionEngine` passes it
  to `WebRController`; when `null`, the controller/bridge fall back to the session id
  (current behavior preserved, so unrelated call sites need no change).
- Callers that hold a `Project` resolve `ProjectSession.libraryKey(project)` and pass it:
  - `EditorViewModel.runCode` / `resetSession`.
  - `PackagesViewModel` install / uninstall / list.
  - `PreviewViewModel` (so a shared project's preview can load `readxl`/`arrow` from the
    shared library).
- **Delete/reset purge guard:** `EditorViewModel.deleteProject` passes `libraryKey`;
  `WebRController.reset` **skips the library purge when `libraryKey ==
  ProjectSession.SHARED_LIBRARY_KEY`** (workspace + data still cleared). Same guard for any
  user-initiated reset of a shared project.

### 3. bridge.js decoupling (the core change)
`ensureSession(sessionId, libraryKey)` splits its two responsibilities:
- **Workspace/data** (snapshot out, clear, restore in; `resetPackagesToBase`; data sync)
  → key on `sessionId`, exactly as today.
- **Library** (`ensureLibResident`, `ensureUserLib`/`.libPaths`, `touchResident`,
  `evictLibsIfOverCap`, and the post-install/uninstall `snapshotLibrary`) → key on
  `libraryKey`.
- Track `currentLibraryKey` alongside `currentSession`. The early-return guard becomes
  `if (sessionId === currentSession && libraryKey === currentLibraryKey) return;` — so a
  toggle **on the already-active project** (same `sessionId`, changed `libraryKey`, the
  Packages-screen reload path) still re-points the library. Workspace swaps only when
  `sessionId` changes; the library dir re-restores/re-points only when `libraryKey`
  changes (two shared projects switching keep the resident shared lib — cheap).
  `resetPackagesToBase` runs whenever `sessionId` changes (invariant #2); on a
  same-session library-only change it also runs, so packages loaded from the old library
  don't linger.
- Every remaining `ensureSession(x)` call site (execute, install, uninstall, list,
  preview, reset) passes both ids. `libDir(libraryKey)` and the library snapshot use
  `webr-library-shared.tar.gz` for the shared case.
- **LRU note:** when an isolated project is active, the shared lib is eviction-eligible
  and simply re-restores next time an opted-in project runs. Correctness preserved; no
  special pinning in v1.

### 4. UI
- **Packages screen** (Local projects only): a `Switch` row at the top — "Share package
  library across projects" with a one-line caption ("Installs go to a library shared by
  all projects that opt in"). Hidden entirely for Remote projects. Flipping it calls
  `PackagesViewModel.setSharedLibrary(Boolean)`, which persists the flag on the `Project`
  via `ProjectStore` and **reloads the list** to reflect the now-active library.
- No editor `⋮` change.

## Data flow (shared project run)

1. User runs code in project B (opted in). `EditorViewModel` resolves
   `sessionId = proj-B`, `libraryKey = "shared"`, calls the Local engine.
2. `WebRController` → bridge `ensureSession("proj-B", "shared")`: workspace swaps to
   proj-B's snapshot; `.libPaths` points at `/rmobile/library/shared`; `resetPackagesToBase`
   clears any leaked namespaces.
3. Run executes; a package installed earlier by project A (also shared) is present and
   loadable via `library()`.
4. After the run, the workspace snapshot is keyed `proj-B`; a subsequent install keys the
   library snapshot `shared`.

## Error handling

- All bridge library/workspace ops remain **best-effort** (current behavior): a failed
  library restore records a swap warning surfaced into stderr, never throws.
- `libraryKey` defaulting to `null` (→ session id) means any missed call site degrades to
  the current per-project behavior rather than crashing.
- The delete/reset guard is a pure string check; if it were ever bypassed, the worst case
  is a shared library purge — hence it's covered by a unit test on the VM path.

## Testing

### Pure / JVM unit
- `ProjectSession.libraryKey`: `"shared"` when `sharedLibrary`, else `proj-<id>`.
- `ProjectOps.newProject` carries the flag.
- `Project` JSON round-trips with and without the field (back-compat: an old JSON with no
  `sharedLibrary` deserializes to `false`).
- `PackagesViewModel.setSharedLibrary` flips the flag, persists via a fake `ProjectStore`,
  and triggers a list reload; list ops are invoked with the resolved library key.
- Delete-purge guard: with a fake engine, deleting a shared project requests `reset` with
  the shared library key and **no** library purge; deleting an isolated project purges as
  before.

### On-device (manual acceptance — WebR behavior isn't statically verifiable)
1. Projects A + B both opted into shared: install a package in A → visible and loadable in
   B.
2. A and B still have **separate workspace variables**.
3. Toggle B back to isolated → the package disappears from B but survives in A / shared.
4. Delete a shared project → the shared library survives for the other.
5. Restart the app → the shared library persists (restored from
   `webr-library-shared.tar.gz`).

## Out of scope / deferred

- Remote (backend) shared libraries.
- Shared-library management UI (list/clear).
- Package migration/copy on toggle (the "copy into shared" option was considered and
  rejected for v1 in favor of just-switch-the-pointer).
- Multiple named shared library groups (single global `"shared"` only).
