# Local-engine data import — Design

**Date:** 2026-07-13
**Status:** Approved (pending spec review)

## Goal

Let the **Local (WebR) engine** use uploaded data files: pick a file on the
phone, store it on-device, and have on-device runs read it by bare name
(`read.csv("sales.csv")`), plus **preview** it as a table — with list / delete /
insert-name parity with the existing Remote Data screen, and the files
**persisting across app restarts**. Closes the second of the three documented
Local-engine v1 gaps (per-project engine choice and local *workspace*
persistence remain separate deferred items).

## Decisions (locked during brainstorming)

1. **Scope: core + local preview.** Upload / list / delete / insert-name, files
   readable by on-device runs, AND a working data viewer (table preview) for
   local files.
2. **Persistence: on-device, per-project.** Files live in Android private storage
   (`filesDir/localdata/<sessionId>/`), so they survive restarts and are scoped
   per project (`sessionId = proj-<id>`), mirroring the backend's per-session
   data dir.
3. **Size cap: 1 GB hard cap + a warning above ~200 MB.** The Local engine's
   filesystem is entirely in-memory (WASM heap in the WebView), so large files
   risk OOM. We do not artificially block below the Remote cap (1 GB), but the
   Data screen warns when a picked file exceeds ~200 MB that on-device execution
   may run out of memory and Remote is better for big data.

## Architecture

### Storage: a `SessionDataStore` abstraction

Data operations become engine-routed, mirroring how package ops route through
`ExecutionEngine`. New interface + two implementations, chosen per current engine
via `ServiceLocator.currentSessionDataStore()`:

```kotlin
interface SessionDataStore {
    suspend fun list(sessionId: String): Result<List<DataFile>>
    suspend fun save(sessionId: String, upload: DataUpload): Result<Unit>
    suspend fun delete(sessionId: String, name: String): Result<Boolean>
}

// Engine-agnostic upload source (no OkHttp / no Android types in the interface).
class DataUpload(val name: String, val size: Long, val openStream: () -> java.io.InputStream)
```

- **`RemoteSessionDataStore(repository)`** — wraps the existing repository calls
  (`/upload` via a `UriRequestBody`-backed `MultipartBody.Part`, `/data`,
  `/delete-data`). Remote behavior is unchanged; it just builds the multipart
  part from `DataUpload.openStream`.
- **`LocalSessionDataStore(appContext)`** — reads/writes
  `filesDir/localdata/<sanitized-sessionId>/<sanitized-name>`. `save` streams
  `openStream()` to the file (buffered, never fully in memory). `list` stats the
  dir. `delete` unlinks. Filenames sanitized with the **same rules as the
  backend** (basename → `[A-Za-z0-9._-]` → reject empty / reserved
  `script.R`/`objects.txt`/`main.R`/`plot###.png`/`table###.json`).

`ServiceLocator.currentSessionDataStore()` returns the Local or Remote store based
on `settingsStore.executionEngine` (like `currentExecutionEngine()`).

`DataScreen` stops constructing an OkHttp `MultipartBody.Part`. Its SAF picker
builds a `DataUpload(name, size, { contentResolver.openInputStream(uri)!! })` and
calls `viewModel.upload(dataUpload)`. The `> ~200 MB` warning is computed from the
picked file's size before upload.

### Making files visible to on-device runs

On-device runs execute in WebR's in-memory FS with cwd `/rmobile/run` (wiped each
run). Data files are made available via a **lazy incremental sync** into a
persistent VFS dir, then linked into the run dir:

- VFS layout: `/rmobile/data/<sessionId>/<name>` — persists for the app process,
  **not** wiped per run.
- New `WebRController` bridge methods expose the on-device files to JS (chunked,
  like the package-library snapshot):
  - `AndroidBridge.dataList(sessionId): String` → JSON `[{name,size}]` from
    `filesDir/localdata/<sessionId>/`.
  - `AndroidBridge.dataChunk(sessionId, name, offset, length): String` → base64.
- `bridge.js` gains `syncData(sessionId)`: lists VFS `/rmobile/data/<sessionId>/`,
  compares to `AndroidBridge.dataList(sessionId)` by name+size, deletes VFS files
  no longer present, and pulls (chunked) any missing or size-changed file. Then
  `linkData(sessionId)` symlinks each `/rmobile/data/<sessionId>/*` into
  `/rmobile/run/` (copy fallback if WASM symlinks misbehave — verified on-device).
- `runOnce` calls `syncData(req.sessionId)` + `linkData(req.sessionId)` after
  `resetRunDir()` and before sourcing the harness.

Because the VFS dir persists across runs, `syncData` is a **no-op after the first
run** (files already present) — only the first run after an upload/delete moves
bytes, so ordinary runs (e.g. `1+1`) stay fast. After an app restart the VFS is
empty but Android storage persisted, so the next run re-pulls lazily — **no
separate boot-restore step**. `req.sessionId` (= `proj-<activeId>`) scopes the
data per project even though the Local engine is otherwise app-wide.

### Local data preview

`preview` is added to the `ExecutionEngine` interface:

```kotlin
suspend fun preview(request: PreviewRequest): Result<PreviewResponse>
```

- **`RemoteExecutionEngine`** delegates to `repository.preview(source, name, sessionId)`
  (unchanged behavior).
- **`LocalExecutionEngine`** delegates to a new `WebRController.preview(json)` →
  bridge `previewData(sessionId, source, name)`. For `source = "file"`: `syncData`
  the one file, read it by extension (base R for `.csv`/`.tsv`/`.rds`; the same
  friendly "install readxl/arrow" message as Remote for `.xlsx`/`.parquet` when
  the package isn't installed in the Local library), and emit an `RTable` via the
  harness's existing table emitter (capped at 200 rows). For `source = "object"`:
  `get()` the named value from WebR's global env. Returns `PreviewResponse`
  (single `table`, `error`, `truncated`) — **read-only**, never mutates the
  workspace.

`PreviewViewModel` routes `load()` through `ServiceLocator.currentExecutionEngine().preview(...)`.

### UI

Reuse the **Data** and **Preview** screens unchanged in layout. Changes:

- `DataViewModel` routes list/upload/delete through
  `ServiceLocator.currentSessionDataStore()` and re-resolves engine+session on
  each appearance via an `onShown()` (the pattern added to `PackagesViewModel`),
  so switching engines in Settings updates the screen. Its caption note becomes
  engine-aware ("On-device (Local) data files" vs the current Remote wording).
- `PreviewViewModel` routes through the current engine and likewise re-resolves.
- The Data screen shows the `> ~200 MB` memory warning on Local before uploading.

## Data flow

- **Upload (Local):** picker → `DataUpload` → `LocalSessionDataStore.save` streams
  to `filesDir/localdata/<session>/<name>` → list refreshes.
- **Run (Local):** `runOnce` → `resetRunDir` → `syncData(session)` (pull new/
  changed, drop deleted) → `linkData(session)` (symlink into `/rmobile/run`) →
  harness runs; `read.csv("x.csv")` resolves in cwd.
- **Preview (Local, file):** `previewData` → `syncData` the file → read by
  extension → emit `RTable`.
- **Delete (Local):** `LocalSessionDataStore.delete` unlinks the Android file; the
  next run's `syncData` removes it from VFS.

## Error handling

- Over-cap upload (> 1 GB): rejected before write with a clear message.
- Local read errors (bad CSV, missing `readxl`/`arrow`): surfaced as the
  preview/response `error`, same shape as Remote.
- A file present on Android but unreadable/stream failure: `save` returns
  `Result.failure`; the Data screen shows it. Sync/link failures are best-effort
  and never crash a run (a missing data file just surfaces as R's own "cannot open
  file" at read time).

## Testing

- **JVM / CI-covered:**
  - `LocalSessionDataStore` save/list/delete against a temp dir (round-trip, size
    reported, sanitization, reserved-name rejection, delete-missing → false).
  - `DataViewModel` routes upload/list/delete through a `FakeSessionDataStore`
    (not the repository) and reflects results; `onShown()` re-resolves on engine
    change; the `> ~200 MB` warning flag.
  - `PreviewViewModel` routes through a `FakeEngine.preview`.
  - `RemoteSessionDataStore` still exercises the existing fake API.
- **Device-gated (WebR — CI can't cover):**
  - Upload a CSV on Local → `read.csv("x.csv")` in a run returns its rows.
  - Delete → the file is no longer readable in a subsequent run.
  - Files survive an app restart (re-pulled lazily on the next run).
  - Local file preview renders a table; object preview of a data frame renders.
  - A `1+1` run after uploading a file is still fast (sync no-op).
  Documented as a manual checklist in the plan's final task.

## Boundaries / non-goals

- Local data is **per-project** (scoped by session), like Remote.
- **1 GB** hard cap; the in-memory VFS makes large files risky (hence the
  ~200 MB warning) — big data remains a Remote strength.
- The one heavier mechanism (per-run symlink; WASM symlink support) is
  device-verified with a copy fallback.
- Unchanged/deferred: local *workspace* (variables) persistence, per-project
  engine choice.

## Docs to update

- `CLAUDE.md` — Local engine now supports data import (per-project on-device
  storage, lazy VFS sync + run-dir link, local preview); update the v1-boundaries
  paragraph and the `data/` + WebR sections; note `SessionDataStore` and the new
  bridge methods.
- `README.md` — Features (Local data import + preview; 1 GB cap with the on-device
  memory caveat) and the "Not built yet" list (drop local-engine data import).
- `app/src/main/assets/webr/README.md` — the `/rmobile/data/<session>` sync/link
  mechanism and the `dataList`/`dataChunk` bridge methods.
