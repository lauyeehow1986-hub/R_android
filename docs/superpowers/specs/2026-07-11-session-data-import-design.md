# Session Data Import — Design

**Status:** Approved design (pending user review)
**Date:** 2026-07-11
**Feature:** Upload arbitrary data files from the phone into a project's R session so
code like `read.csv("sales.csv")` works against real data.

## Goal

Let a user upload files (CSV, RDS, xlsx, images, anything — treated as opaque
bytes) from their phone into the **active project's** backend session, so that R
code run in that session can read them by bare filename. Files persist across
runs and restarts, are managed from a dedicated **Data** screen (upload / list /
delete), and are cleared when the session is reset or the project is deleted.

## Why

Every existing feature operates on synthetic or hand-typed data. Real R work
starts from a data file. The app already gives each project an isolated,
durable backend session (`ProjectSession.of(project)` → `SESSION_DIR/<id>/`);
this feature adds a persistent `data/` area to that session and the plumbing to
fill it, list it, and expose its contents to executed code.

## Architecture

### Storage
- `session_paths(id)` gains `$data = file.path(dir, "data")` —
  `SESSION_DIR/<id>/data`, on the same read-write session volume as
  `workspace.RData` and `rlib/`. Created on demand.
- Because sessions are per-project, uploaded data is **per-project**
  automatically. No new session concept.

### Making files visible to executed code
- User code runs in an **ephemeral** `run_dir` (fresh temp dir per `/execute`,
  `wd = run_dir`). To expose data files without duplicating potentially
  gigabyte-sized files on every run, the `/execute` handler **symlinks** each
  file from `paths$data` into `run_dir` **before** writing the entry/`script.R`.
  - Symlink (not copy): a 1 GB dataset is not re-copied per run; reads resolve
    through the link at native speed.
  - Copy-in order: data files are linked in *first*, then the entry and harness
    files (`script.R`, `main.R`, `objects.txt`) are written, so a data file
    that happens to share a harness/entry name is shadowed by the run's own
    files (safe — the run always wins).
  - Trade-off (documented): code that *writes* to a linked filename writes
    through to the persistent stored copy. Acceptable for read-oriented data;
    noted in the backend README.
- Result: `read.csv("sales.csv")`, `readRDS("model.rds")`,
  `readxl::read_excel("book.xlsx")` all work by bare filename.

### Lifecycle
- `POST /reset` currently deletes only `workspace` + `attached` (and `rlib` when
  `purgePackages`). It will **also** delete `paths$data` (recursive), so a
  session reset returns the session to empty — data included.
- Deleting a project already triggers a best-effort `reset(purgePackages = true)`
  for its session; with the above change, that also clears its data. No app-side
  change needed for project deletion.

## Backend contract (new endpoints)

All three are added to `is_protected()` (auth + rate-limit gated like every
other stateful endpoint). `sessionId` is passed as a **query param** on every
one, so multipart parsing only ever handles the file part.

### `POST /upload?sessionId=<id>` (multipart/form-data)
- One form part named `file` (the raw bytes; `filename` is the part's filename).
- Filename handling: take `basename()` only; sanitize to `[A-Za-z0-9._-]`;
  reject empty, reject `..`, reject the reserved runtime names the harness owns
  (`script.R`, `objects.txt`, `main.R`, and anything matching `^plot[0-9]+\.png$`
  or `^table[0-9]+\.json$`). On reject → `400` with an `error` message.
- Size cap: `R_UPLOAD_MAX_BYTES` (**default `1073741824`** = 1 GiB). Enforced
  after the body is parsed (plumber buffers multipart in memory — see Infra);
  over-cap uploads are rejected with `413` and nothing is written.
- On success: write bytes to `file.path(paths$data, name)` (overwriting a
  same-named file), return `UploadResponse(name, size)` where `size` is the
  byte count written.

### `GET /data?sessionId=<id>`
- Lists `paths$data`: `DataFilesResponse(files = [DataFile(name, size)])`,
  sorted by name, `size` in bytes. Missing dir → empty list.

### `POST /delete-data` (JSON `{name, sessionId}`)
- Same name sanitization as upload (basename + charset); resolve inside
  `paths$data` and refuse anything that escapes it. Delete the file.
- `DeleteDataResponse(removed)` — `removed = TRUE` iff a file was deleted.

### Infra changes
- `docker-compose.yml`: raise `mem_limit` from `512m` to **`2g`** — plumber's
  multipart parser buffers the whole request body in memory, so the container
  must have headroom above the 1 GiB cap. Documented in the backend README
  security/limits section, with a note that operators who lower
  `R_UPLOAD_MAX_BYTES` can lower `mem_limit` in step (they move together).
- The hardened compose profile inherits the same `mem_limit` bump.

## App

### Models — `data/model/DataModels.kt` (new)
```kotlin
@Serializable data class DataFile(val name: String, val size: Long)
@Serializable data class DataFilesResponse(val files: List<DataFile> = emptyList())
@Serializable data class UploadResponse(val name: String = "", val size: Long = 0)
@Serializable data class DeleteDataRequest(val name: String, val sessionId: String? = null)
@Serializable data class DeleteDataResponse(val removed: Boolean = false)
```

### API — `data/network/RExecutionApi.kt`
```kotlin
@Multipart
@POST("upload")
suspend fun upload(
    @Query("sessionId") sessionId: String,
    @Part file: MultipartBody.Part,
): UploadResponse

@GET("data")
suspend fun dataFiles(@Query("sessionId") sessionId: String): DataFilesResponse

@POST("delete-data")
suspend fun deleteData(@Body request: DeleteDataRequest): DeleteDataResponse
```

### Networking — `data/network/NetworkModule.kt`
- Add a dedicated **upload client/retrofit**: a second `OkHttpClient` that
  **shares the same `HostSelectionInterceptor` instance** (so runtime backend
  URL + `X-API-Key` still apply) but with `callTimeout(0)` and a disabled
  `writeTimeout` — the default 30s `callTimeout` on the main client would abort
  a large LAN upload. `RExecutionApi.upload` is served by this retrofit; the
  other two calls stay on the main client.

### Repository — `data/RExecutionRepository.kt`
- `uploadFile(sessionId, part): Result<UploadResponse>`
- `listDataFiles(sessionId = DEFAULT_SESSION_ID): Result<List<DataFile>>`
- `deleteDataFile(name, sessionId = DEFAULT_SESSION_ID): Result<Boolean>`
  (each wrapping the API call in `runCatching`, mirroring existing methods).

### UI — `ui/data/` (new, parallel to `ui/packages/`)
- `DataViewModel`: resolves the active project's session via `ProjectStore`
  (exactly like `PackagesViewModel`), exposes `StateFlow<DataUiState>`
  (`files`, `isLoading`, `error`, `uploading`), and methods `refresh()`,
  `upload(uri)`, `delete(name)`. `upload(uri)` streams bytes from the SAF `Uri`
  via `contentResolver.openInputStream` into a `MultipartBody.Part` backed by a
  streaming `RequestBody` (does **not** read the whole file into a ByteArray —
  keeps app memory flat for large files); the displayed filename comes from the
  content resolver (`OpenableColumns.DISPLAY_NAME`).
- `DataScreen`: top app bar with back; an "Upload" button launching
  `ActivityResultContracts.OpenDocument` (any MIME); a `LazyColumn` of files
  showing name + human-readable size and a delete affordance; empty/loading/error
  states. Reached from the editor top-bar/menu, same entry pattern as the
  Packages screen.
- Human-readable size: a **pure** `formatByteSize(Long): String` helper
  (`ui/data/ByteSize.kt`) — unit-tested (B/KB/MB/GB rounding).

### Tap-to-insert filename
- Tapping a file row inserts its quoted bare filename (`"sales.csv"`) at the
  editor's cursor. The Data screen shares the one hoisted `EditorViewModel` (as
  the Packages/Projects screens do); a new `EditorViewModel.insertText(String)`
  reuses `EditorTextOps.insertAt` at the current cursor, then navigates back to
  the editor. Format-agnostic — the user wraps it with whatever reader they want.

## Testing

### Backend — `backend/tests/test-data.R` (testthat + httr2; CI-gated)
New helpers in `helper-server.R`: `post_upload(server, path, session_id, key)`
(multipart), `get_data(server, session_id, key)`, `post_delete_data(server,
name, session_id, key)`.
- Upload → `GET /data` shows it with correct size.
- Uploaded file is **readable from executed code**: upload a tiny CSV, then
  `POST /execute` code that `read.csv()`s it and prints — assert the value.
- `delete-data` removes it (`removed = TRUE`; gone from listing).
- Reserved-name upload (`script.R`) → 400.
- Over-cap upload (set `R_UPLOAD_MAX_BYTES` low for the test) → 413.
- Session scoping: file uploaded to session A is absent in session B's listing.
- `POST /reset` clears the data listing.

### App (JVM unit tests)
- `ByteSizeTest` — the pure formatter across B/KB/MB/GB boundaries.
- `DataViewModelTest` — against a fake repository: `refresh()` populates files;
  `upload` success refreshes the list; `delete` success removes the row; error
  paths surface `error`; session resolution uses the active project.
- `RExecutionRepositoryTest` — the three new methods map success/failure to
  `Result` (fake API).

## Out of scope (YAGNI)
- Format detection / preview / parsing (chose "opaque bytes").
- Download / export of a data file back out of the session.
- Progress bar / resumable upload (a simple uploading spinner is enough for MVP).
- Per-file rename in place.

## Contract sync checklist (the two halves)
Add on both sides, by hand (no shared schema), and remember the backend must
emit **unboxed** JSON (`run.R`):
- `/upload` → `UploadResponse(name, size)`
- `/data` → `DataFilesResponse(files: [DataFile(name, size)])`
- `/delete-data` → `DeleteDataResponse(removed)`
- `is_protected()` gains `/upload`, `/data`, `/delete-data`.
- `session_paths()` gains `$data`; `/execute` symlinks data in; `/reset` clears it.
