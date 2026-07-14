# Cross-Restart Persistence of the Local (WebR) Workspace — Design

**Date:** 2026-07-14
**Status:** Approved, ready for implementation planning

## Goal

Make the Local (WebR) engine's workspace **variables** survive an app restart,
the same way the Local package library already does. Today the one live WebR
`globalenv()` **is** the entire Local session, so workspace objects persist
across runs within an app process but are lost when the process dies. This
closes that gap.

## Background / current state

- On the Local engine, `runOnce` (in `app/src/main/assets/webr/bridge.js`)
  always evaluates the harness against `webR.objs.globalEnv`, regardless of the
  active project/session. So Local workspace variables are **app-wide, shared
  across all projects** — Local behaves as a single global session. (This is
  unlike the Remote engine, where each project maps to its own isolated backend
  session.)
- The Local **package library** is already the same shape: one shared
  `USER_LIB` (`/rmobile/library`), and it **already persists across restarts**
  via a proven best-effort snapshot/restore path: `utils::tar` the library →
  stream the bytes to Kotlin in base64 chunks → store under `filesDir` →
  `utils::untar(tar = "internal")` back into the VFS at boot. This deliberately
  avoids WebR's IDBFS `FS.mount`, which destabilised the eval channel.
- The workspace is the one piece of the Local session that does **not** persist
  yet — it is purely in-process.

## Scope

**In scope:** persist and restore a **single, shared** Local workspace (the
whole `globalenv()`), mirroring the Local package library.

**Out of scope (explicitly):**
- Per-project Local workspace isolation. Local remains one global session;
  per-project Local isolation is a separate, larger change.
- Any change to the Remote engine (its per-project workspaces already persist
  server-side).

## Approach

Reuse the **exact** mechanism the package library uses: `save.image()` the
global environment to a file in the WebR VFS, stream the bytes to `filesDir`,
and `load()` them back at boot.

Alternatives considered and rejected:
- **IDBFS `FS.mount`** — already tried for the library; it destabilised the
  WebR eval channel. Not revisiting.
- **Per-object JSON serialization** — lossy; cannot round-trip R objects
  (factors, S4, closures, environments). `save`/`load` is the correct
  serializer.

## Design

### 1. Persisted artifact

One new file under `filesDir`: **`webr-workspace.RData`** — a single
`save.image()` blob of the shared Local `globalenv()`.

### 2. Kotlin: extract a testable `SnapshotStore`

The chunked-transfer bridge methods (`snapshotSize/Read/Begin/Append/Commit`)
are currently hardcoded to the library file and inlined in
`WebRController.Bridge`. Extract a pure **`SnapshotStore`** that:

- deals in **raw bytes + a target `File`** (base64 encode/decode stays at the
  Android bridge boundary, where `android.util.Base64` lives, so the store
  itself is plain `java.io` and JVM-unit-testable);
- provides `size()`, `read(offset, length): ByteArray`, `begin()`,
  `append(bytes)`, `commit()` (atomic via the existing `.tmp` + rename), and a
  new `delete()`;
- is instantiated twice in `WebRController`: `library` →
  `webr-library.tar.gz`, `workspace` → `webr-workspace.RData`.

The `Bridge` snapshot methods gain a `kind: String` parameter
(`"library"` | `"workspace"`) and delegate to the matching store. A
`snapshotDelete(kind)` bridge method is added. `bridge.js`'s existing library
calls pass `"library"`.

### 3. bridge.js: snapshot / restore / clear

- **`snapshotWorkspace()`** — best-effort, size-guarded:
  - If `ls(globalenv())` is **empty** → `snapshotDelete('workspace')`, so an
    `rm()`-emptied (or reset) workspace truly clears across restarts.
  - Else `save.image('/rmobile/workspace.RData')` inside a `try`; read the
    bytes. If the blob is **> 200 MB** (consistent with the Local engine's
    existing in-memory-data warning threshold) → **skip** persisting, **keep**
    the prior snapshot, record a note. Otherwise stream via
    `snapshot{Begin,Append,Commit}('workspace')`, exactly like the library.
  - Any `save.image` failure (e.g. an unserializable object such as an open
    connection) → catch, skip, record a note. Never breaks the run.
- **`restoreWorkspace()`** — at boot, in `boot()` after `restoreLibrary()` and
  before `ready = true`: pull the chunks, write to `/rmobile/workspace.RData`,
  `load(..., envir = globalenv())`. Best-effort.
- **Trigger — after each successful run, with no added latency:** after a
  successful `runOnce`, start `snapshotWorkspace()` **without awaiting** it, so
  run latency is unchanged. A module-level `pendingWorkspaceSnapshot` promise is
  awaited at the **top** of `runOnce`, so a fast next run can never overlap or
  race the background save. Only the **success** path snapshots — an errored run
  returns early (as it does today) and does not snapshot.
- **`webrReset`** also calls `snapshotDelete('workspace')` after clearing
  `globalenv()`, so a reset persists as a cleared workspace.

### 4. Error handling & boundaries

Every step is best-effort and swallowed:
- A snapshot failure leaves the in-memory workspace and the run result intact.
- A restore failure boots to an empty workspace (today's behavior).

**Known v1 rough edge (documented):** a workspace whose `save.image` blob
exceeds the 200 MB cap will not persist, and the prior (smaller) snapshot is
what restores on next launch.

### 5. Testing

- **JVM unit tests** for the newly-extracted `SnapshotStore`: offset/length
  reads (including reads past end), `begin`/`append`/`commit` atomicity (partial
  writes don't clobber the committed file until `commit`), and `delete`.
- The `bridge.js` save/restore path is **device-verified**, the same as the
  existing library snapshot (which has no JVM test — it is WebR/WebView-bound).

### 6. Docs

- Update `CLAUDE.md`: the "v1 boundaries" note currently says the local
  workspace resets on app restart — it now persists (leave per-project engine
  choice listed as still-deferred).
- Update `app/src/main/assets/webr/README.md` to document the workspace
  snapshot/restore alongside the library one.

## Files touched

- `app/src/main/java/com/rmobile/console/data/execution/SnapshotStore.kt`
  — **new**, pure, unit-tested.
- `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
  — use two `SnapshotStore`s; `Bridge` methods take `kind`; add
  `snapshotDelete`.
- `app/src/main/assets/webr/bridge.js` — `snapshotWorkspace`,
  `restoreWorkspace`, `pendingWorkspaceSnapshot` guard, `runOnce` +
  `boot` + `webrReset` wiring, `kind` args on library calls.
- `app/src/test/java/com/rmobile/console/data/execution/SnapshotStoreTest.kt`
  — **new**.
- `CLAUDE.md`, `app/src/main/assets/webr/README.md` — docs.
