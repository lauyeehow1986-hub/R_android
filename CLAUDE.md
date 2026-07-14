# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android client for writing and running R code on a phone — the Android
counterpart to iOS apps like "R Programming Compiler"/"Rlytic". There are two
independent pieces in this one repo:

- `app/` — native Android client (Kotlin + Jetpack Compose).
- `backend/` — a Plumber (R) HTTP service that actually executes R code.

**Why there's a backend at all**: native R can't run on-device. Its
interpreter depends on Fortran/C internals that don't build for Android or
iOS, which is why the existing iOS apps are thin clients too (confirmed:
they require internet, cap execution at ~20s, and describe themselves as
"batch compilers"). The app now **also** runs R on-device via **WebR** (real
GNU R compiled to WebAssembly, running in a bundled offscreen WebView) — this
is the **default "Local" engine**, privacy-preserving (code and data never
leave the phone) and offline-from-install; the network backend is the opt-in
"Remote" engine, chosen for full CRAN/package compatibility and heavy work. The
JVM-based Renjin interpreter remains **rejected** — WebR is actual GNU R, not a
reimplementation, so it matches CRAN semantics; Renjin would not. See
`data/execution/` below and "Execution model" before reviving that idea.

## Commands

### Android app (`app/`)

Requires the Android SDK (not available in this remote sandbox — Gradle
can't resolve `google()`/AGP here, so builds must be verified locally or in
CI, not in this environment).

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:installDebug           # build + install on connected device/emulator
./gradlew :app:testDebugUnitTest      # unit tests (JVM, no device needed)
./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.SomeTest"   # single test
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew :app:lint                   # Android Lint
```

Point a debug build at a non-default backend (e.g. a phone on the same LAN
instead of the emulator's `10.0.2.2` alias):

```bash
./gradlew :app:installDebug -PrExecutionBaseUrl=http://192.168.1.23:8000/
```

The on-device WebR runtime is **vendored** into the APK (offline-from-install).
It's not fetched by Gradle — re-vendor it (e.g. to bump WebR versions) by running
`bash app/src/main/assets/webr/scripts/fetch-webr.sh`, which `npm pack`s WebR and
copies its `dist/` into `app/src/main/assets/webr/dist/` (committed as binary). See
`app/src/main/assets/webr/README.md`.

### Backend (`backend/`)

```bash
cd backend && docker compose up --build   # run the R execution API on :8000
curl -X POST http://localhost:8000/execute -H "Content-Type: application/json" \
  -d '{"code": "summary(cars)\nplot(cars)"}'
```

Backend integration tests live in `backend/tests/` (testthat + httr2), run with
`Rscript backend/run-tests.R`; they start a real Plumber instance and cover
`/execute`, `/reset`, sessions, auth, and rate limiting. CI runs them on push/PR.

## Architecture

### Android app (`app/src/main/java/com/rmobile/console/`)

Single-activity, manual DI (no Hilt/Koin — the object graph is one
Retrofit client, not worth a framework yet):

- `MainActivity.kt` → sets Compose content, wraps everything in
  `RConsoleTheme`.
- `MainActivity.kt` also hosts `AppRoot`, a two-state (`EDITOR`/`SETTINGS`)
  in-app switch — deliberately no navigation library for two screens.
- `ui/editor/` — the main screen: `EditorScreen` (Compose UI: code input with
  R syntax highlighting and a quick-insert operator bar, Run button, output
  panel with text + decoded plot bitmaps, plus run-history and saved-scripts
  bottom sheets) driven by `EditorViewModel` (`StateFlow<EditorUiState>`,
  standard unidirectional-data-flow — mutate state via `ViewModel` methods,
  never from the composable). The editor field uses a local `TextFieldValue`
  synced from `uiState.code`, so cursor-aware inserts and history/script loads
  both work. Pure, unit-tested helpers: `RSyntaxHighlighter` (tokenizer, wrapped
  by the `RCodeVisualTransformation`), `insertAt`/`replaceRange` (`EditorTextOps`),
  and `SavedScriptLibrary` (`data/scripts/`, list ops for named scripts persisted
  via `SettingsStore`). **Code assist**: an autocomplete **suggestion strip**
  (`SuggestionStrip`, above the operator bar) shows completions for the identifier
  under the cursor — derived off `Dispatchers.Default` with a 120ms `debounce` over
  a `snapshotFlow` of the editor field, against `uiState.completionSymbols`
  (assembled in `EditorViewModel` from the pure, unit-tested `completion/CompletionOps`
  + baked `BaseRSymbols`, the session `workspaceObjects`, installed-package names,
  and a cached backend `/symbols` index refreshed on run/project-open/packages-return
  via `refreshSymbols()`, which guards against stale-session results). Tapping a chip
  inserts (`replaceRange`); long-pressing a chip, tapping the leading `? <token>`
  chip, or using the top-bar `?` help search opens **R help** — `EditorViewModel.showHelp`
  (generation-guarded so a dismissed lookup can't resurface) drives a `HelpState`
  rendered by `HelpSheet` (a `ModalBottomSheet` showing `Rd2txt` help text).
- `ui/settings/` — `SettingsScreen` + `SettingsViewModel` for editing the
  backend URL and API key at runtime, with a "Test connection" action backed by
  `NetworkModule.probeHealth` (pings `<url>/health` through a separate client
  that bypasses the host-rewriting interceptor, so an unsaved URL can be
  checked). `SettingsViewModel` depends on the `AppSettings` interface (not the
  concrete `SettingsStore`) so it's unit-testable without SharedPreferences.
- `ui/data/` — **session data import**: `DataScreen` (Compose UI: an "Upload file"
  button launching a SAF `OpenDocument` picker, an uploading spinner, and a
  `LazyColumn` of the session's files with human-readable sizes, an "Insert" button,
  and a delete affordance) driven by `DataViewModel` (`StateFlow<DataUiState>`,
  session-scoped exactly like `PackagesViewModel` — it resolves the active project's
  session via `ProjectStore` in `init`, so the screen just does `viewModel()`).
  Uploads **stream** from the picked `Uri` — `data/network/UriRequestBody` is an
  OkHttp `RequestBody` that reopens `contentResolver.openInputStream(uri)` on each
  `writeTo` (never buffering the whole file into a `ByteArray`), sent as a
  `MultipartBody.Part` over `NetworkModule.rDataApi` (a **separate** OkHttp client
  with `callTimeout(0)`/`writeTimeout(0)` so a large LAN upload isn't aborted, but
  sharing the same `HostSelectionInterceptor` so the runtime URL + API key still
  apply). `formatByteSize` (`ui/data/ByteSize.kt`) is a pure, unit-tested B/KB/MB/GB
  formatter. Tapping "Insert" calls `EditorViewModel.insertText(name)` (appends the
  quoted filename on its own line via `onCodeChanged`, so the active project file
  mirrors it) and returns to the editor. The Data screen is reached from a "Data
  files" item in the editor's overflow menu; like the Packages screen it owns its own
  `DataViewModel` for list/upload state, and only the tap-to-insert action reaches the
  hoisted `EditorViewModel` (via the `onInsertFileName` callback wired in `MainActivity`).
- `ui/preview/` — **data viewer**: `PreviewScreen` (full-screen, reuses
  `RTableView` — the same table renderer `/execute`'s `tables` use, so it
  already shows a "Showing X of N rows" footer) driven by `PreviewViewModel`
  (`StateFlow<PreviewUiState>`), session-scoped exactly like `DataViewModel` —
  it resolves the active project's session via `ProjectStore` in `init` and
  calls `POST /preview` on load. Reached two ways: the "View" button on each
  Data-screen file row (`onPreviewFile`) and tappable `AssistChip`s in the
  editor's workspace-objects strip (`onPreviewObject`). Navigation is a plain
  `Screen.PREVIEW` case in `MainActivity.AppRoot` with `previewSource`/
  `previewName` state set by whichever caller opened it; back returns to
  `Screen.DATA` for a file preview or `Screen.EDITOR` for an object preview.
- Plot sharing (`ui/editor/PlotSharing.kt`) writes a decoded PNG to
  `cacheDir/shared` and opens a share sheet via a `FileProvider` declared in the
  manifest (`res/xml/file_paths.xml`); "Copy output" uses the Compose clipboard.
- `data/` — `RExecutionRepository` wraps `RExecutionApi` (Retrofit
  interface) in a `Result`-returning suspend call. `data/network/NetworkModule`
  is the single hand-rolled DI point: one lazily-built OkHttp/Retrofit
  instance built against a placeholder base URL, with a
  `HostSelectionInterceptor` that rewrites each request's host/scheme/port (and
  path prefix) to the **runtime-configured** URL and attaches the optional
  `X-API-Key` header — so changing the backend needs no rebuild.
- `data/execution/` — **swappable execution engines**. `ExecutionEngine` is the
  interface (`execute(ExecuteRequest): Result<ExecuteResponse>`,
  `reset(sessionId): Result<Unit>`); both implementations return the **same**
  `ExecuteResponse` contract, so all downstream UI (output panel, plot decoding,
  `RTableView`, workspace chips) is engine-agnostic. `RemoteExecutionEngine` wraps
  `RExecutionRepository` (the network backend). `LocalExecutionEngine` wraps
  `WebRController` — one persistent **offscreen `WebView`** running bundled WebR
  (real GNU R → WASM); it serves `app/src/main/assets/webr/` via
  `WebViewAssetLoader` with injected **COOP/COEP** headers (needed for WebR's
  `SharedArrayBuffer` channel) and marshals a JS↔Kotlin bridge. The one live WebR
  instance **is** the local session, so the workspace persists across runs within
  an app process (but not across app restarts — see boundaries below). The engine
  is chosen per run from `SettingsStore.executionEngine` via
  `ServiceLocator.currentExecutionEngine()` (read fresh each run so a Settings
  toggle takes effect immediately); `EditorViewModel` routes `runCode`/
  `resetSession` through an `engineProvider`. **Harness parity coupling**:
  `assets/webr/harness.R` mirrors the backend `/execute` wrapper's `withVisible`
  loop and duplicates `TABLE_EMIT_HELPERS` from `plumber.R` **byte-for-byte** so
  both engines emit identical `table*.json` — change one, change the other in the
  same commit. `assets/webr/bridge.js` boots WebR, runs the harness, and posts
  `ExecuteResponse`-shaped JSON; `assets/webr/dist/` is the vendored runtime.
- `data/settings/` — `SettingsStore` (SharedPreferences) persists the backend
  URL, API key, run history, saved scripts, **projects**, and the **execution
  engine choice** (`ExecutionEngineChoice.LOCAL`/`REMOTE`, default `LOCAL`, via
  `AppSettings.executionEngine`); `BaseUrlValidator` is the pure, unit-tested URL
  normalizer. `data/history/` holds the `HistoryEntry` model and `RunHistory`
  (pure list logic). `data/ServiceLocator` is initialized once by
  `RMobileApplication` (it holds the app `Context` so it can build the
  `WebRController`), applies persisted settings to `NetworkModule` at startup, and
  exposes `currentExecutionEngine()`; the (context-less) ViewModels read it via
  default constructor args, which is also the seam unit tests inject fakes through.
- `data/project/` — **multi-file projects**: `Project`/`ProjectFile` models,
  pure unit-tested `ProjectOps` (file/project list ops), and the `ProjectStore`
  interface (implemented by `SettingsStore`). `EditorViewModel` is project-aware
  — `uiState.code` mirrors the *active* file's content (so the editor field is
  unchanged), and Run sends `files` + `entryFile = project.entryFileName` (the
  pinned entry, not the focused file). `ui/editor/EditorScreen` gained a file
  switcher (entry badge ▶, set-entry/rename/delete), and `ui/projects/ProjectsScreen`
  is the project library — it shares the one `EditorViewModel` hoisted in
  `MainActivity.AppRoot`, so opening a project updates the editor. Projects
  export/import as a `.zip` of their `.R` files + a `.rmobile-project.json`
  manifest via the pure, tested `ProjectArchive`; `ProjectsScreen` exports through
  the share-sheet (`shareProjectZip`) and imports via a SAF picker →
  `EditorViewModel.importProject` (imports create a new project).
  `data/project/ProjectSession.of(project)` is the single project→backend-session
  mapping (`sessionId = "proj-<project.id>"`); opening a project switches the
  active backend session automatically, since `EditorViewModel.runCode()`/
  `resetSession()` derive the session from the currently-active project rather
  than a fixed id. Deleting a project fires a best-effort backend
  `reset(purgePackages = true)` for that project's session, so its workspace and
  package library get purged along with the local project. `PackagesViewModel`
  resolves the active project's session (via `ProjectStore`) and scopes
  install/uninstall/list to it.
- The **build-time** default backend URL still lives in
  `app/build.gradle.kts` → `buildConfigField` (overridable with
  `-PrExecutionBaseUrl=...`); it's the fallback until the user overrides it in
  Settings.

**Naming gotcha**: Android auto-generates a resource class literally named
`R` (`com.rmobile.console.R`, holding `R.string`, `R.drawable`, etc.) in
every module. Never name a Kotlin class or object `R` — it will collide
with (or be shadowed by) that generated class. This is exactly why the
package/module is called `console`/"R Mobile" rather than naming anything
in code just `R`.

### Backend (`backend/`)

`plumber.R` defines one real endpoint, `POST /execute`: it writes the
submitted code to a temp `.R` file (prefixed with a `png()` device pointed
at the same temp dir so `plot()` calls are captured as images instead of
erroring for lack of a display), runs it via `processx::run` as an isolated
`Rscript --vanilla` subprocess with a hard timeout
(`R_EXECUTION_TIMEOUT_SECONDS`, default 20s — matching the constraint the
existing iOS apps advertise), collects stdout/stderr and any
`plot%03d.png` files (base64-encoded), and deletes the temp dir. `run.R` is
just the Plumber bootstrap (`plumb("plumber.R")$run(...)`).

`plumber.R` also has two Plumber filters (`auth`, `ratelimit`) that guard
`/execute` and `/reset` (health checks stay open): optional `X-API-Key` auth
when `R_API_KEY` is set, and an in-process per-IP fixed-window rate limit when
`R_RATE_LIMIT_PER_MINUTE > 0`. Both are **off by default** for local dev.

`/execute` also accepts a multi-file project — `files` (`[{name,content}]`) +
`entryFile` — which the handler writes into the run dir and runs by `source()`-ing
the entry inside the same session wrapper (so `source()` between files works);
the legacy single `code` field still works.

**Durable sessions**: the wrapper the handler builds around user code has two
bookends — before the code it `load()`s a per-session `workspace.RData` and
replays recorded `library()` calls; after a **successful** run it `save.image()`s
and writes the attached-package list (a failed/timed-out run never reaches the
save, so it can't corrupt state). State lives under `R_SESSION_DIR` (default
`/data/sessions`, a named volume mounted read-write into the otherwise
read-only container). `/execute` takes an optional `sessionId` (sanitized to
`[A-Za-z0-9_-]`, default `"default"`) and returns `workspaceObjects` (names in
the session's global env, or absent on error); `POST /reset` deletes a session's
files. `run.R` is the Plumber bootstrap and **also sets an unboxed-JSON
serializer** (`serializer_json(auto_unbox = TRUE, null = "null")`) so length-1
vectors serialize as scalars (matching the Kotlin models) and NULL becomes
JSON null — without this, plumber array-wraps every scalar and the app can't
parse responses.

**Packages**: package libraries are now **per-session** — the wrapper prepends
`SESSION_DIR/<id>/rlib` (a subdirectory of the same read-write session volume,
created on demand) to `.libPaths()`, so `library()` and inline
`install.packages()` see only that session's (i.e. that project's) installed
packages. `POST /install` (body `{"package", "sessionId"}`, name-validated, its
own `R_INSTALL_TIMEOUT_SECONDS`) installs into that session's lib in an isolated
subprocess and reports `installed`/`systemRequirements`; `POST /uninstall` takes
the same `sessionId`; `GET /packages` takes a `?sessionId=` query param
(default `"default"`) and lists that session's library. The old shared library
constant, `R_PKG_LIB` (default `/data/rlib`), still exists but is now
`LEGACY_PKG_LIB` — a **read-only** (mounted `:ro`) source of pre-multi-session
packages. `POST /import-legacy` (body `{"sessionId"}`) copies packages from the
legacy lib into a session's own lib, skipping ones already present, and returns
`{"imported": <int>, "packages": [...]}`. Common package system libraries are
pre-baked into the image (no runtime apt — the container stays hardened).

The Dockerfile/`docker-compose.yml` run this as an unprivileged user with a
read-only root filesystem, dropped capabilities, and CPU/memory limits. A
**hardened deploy profile** (`docker-compose.hardened.yml`: an `internal` network
+ a Caddy reverse proxy) gives the execution container **no egress** and caps
`/tmp`; since there's no egress, packages for a hardened deploy are baked at build
via `packages.txt` (the `Dockerfile` reads it) rather than runtime `/install`.
**Still not a hardened multi-tenant sandbox** — no per-request container/VM
isolation (the outstanding gap), and the rate limiter is a single-instance
in-memory counter that trusts `REMOTE_ADDR`.
Read `backend/README.md`'s security section in full before changing the
execution model or deploying this anywhere reachable from the internet —
this service is arbitrary-code-execution-as-a-feature by design, so changes
here have different stakes than changes to the Android UI.

### Response contract between the two halves

`ExecuteRequest`/`ExecuteResponse`/`ResetRequest`/`ResetResponse` in
`app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt`
(kotlinx.serialization) must stay in sync field-for-field with the JSON
returned by `plumber.R`. Every project now maps to its own backend session —
the app sends `sessionId = "proj-<project.id>"` (derived by
`ProjectSession.of(project)`) per project, so each project gets an isolated
workspace **and** package library. `/execute`: request `code` + optional
`sessionId`, OR a multi-file project `files` (`[{name,content}]`) + `entryFile`
(backend writes the files and `source()`s the entry; `files` wins over `code`);
response `stdout`, `stderr`, `plots`, `tables`, `error`, `timedOut`,
`workspaceObjects` (nullable). `tables` (`RTable(columns, columnTypes, rows,
totalRows)`) holds any data frame/tibble/data.table/matrix/2-D `table`
**printed at the entry's top level** — the wrapper runs top-level expressions
through a `withVisible` eval loop (instead of bare `source()`) and emits
`inherits(., "data.frame")`/2-D values as `table*.json` side-channel files
(rows capped at `R_TABLE_MAX_ROWS`, default 200); prints inside
functions/`source()`d files aren't captured. `/reset`: request `sessionId` +
optional `purgePackages` (also deletes that session's package library),
response `ok`. `/install` (`InstallRequest`/`InstallResponse`, in
`data/model/PackageModels.kt`): request `package` (Kotlin property
`packageName` via `@SerialName("package")`) + nullable `sessionId`, response
`stdout`/`stderr`/`error`/`timedOut`/`installed`/`systemRequirements`.
`/uninstall` (`UninstallRequest`/`UninstallResponse`): request `package` (same
`@SerialName` renaming) + nullable `sessionId`, response `removed`/`error`.
`/packages` takes a `?sessionId=` query param (default `"default"`) and lists
that session's library → `PackagesResponse(packages)`. `/import-legacy`
(`ImportLegacyRequest`/`ImportLegacyResponse`, in `data/model/PackageModels.kt`):
request `sessionId`, response `imported` (count) + `packages` (names copied
in) — copies packages from the old shared/legacy library into a session's own
library, skipping ones already present. `/symbols` (a `GET` with a `?sessionId=`
query param, default `"default"`) lists completion symbol names — base +
default-attached packages' exports plus the session's `library()`-d packages'
exports — → `SymbolsResponse(symbols)` (`data/model/AssistModels.kt`). `/help`
(`HelpRequest`/`HelpResponse`, same file): request `topic` (validated
`^[A-Za-z0-9._]+$`, else `400`) + nullable `sessionId`, response `topic` /
`packageName` (nullable) / `text` (the `tools::Rd2txt`-rendered help) / `found`.
**Session data files** (models in `data/model/DataModels.kt`): `POST /upload`
(multipart/form-data, one part named `file`; `sessionId` is a **query param** so
multipart parsing only handles the file) validates the filename
(`basename` → sanitize to `[A-Za-z0-9._-]` → reject empty/reserved
`script.R`/`objects.txt`/`main.R`/`plot###.png`/`table###.json`, `400` on reject),
caps size at `R_UPLOAD_MAX_BYTES` (default 1 GiB, `413` on over-cap), writes into
the session's `data/` dir → `UploadResponse(name, size)`; `GET /data?sessionId=`
→ `DataFilesResponse(files: [DataFile(name, size)])` sorted by name; `POST
/delete-data` (JSON `{name, sessionId?}`) → `DeleteDataResponse(removed)`.
`session_paths()` gained `$data = <dir>/data`; `/execute` **symlinks** (not
copies — a 1 GB dataset isn't re-copied per run) each data file into the ephemeral
run dir *before* writing the harness/entry files (so a run's own files always
shadow a same-named data file), making them readable by bare name
(`read.csv("sales.csv")`); `/reset` also `unlink`s `$data`. The container
`mem_limit` was raised to `2g` because plumber buffers the whole multipart body in
memory (operators lowering `R_UPLOAD_MAX_BYTES` can lower it in step).
`/preview` (`PreviewRequest`/`PreviewResponse`, in `data/model/PreviewModels.kt`,
reusing `RTable` from `ExecuteModels.kt`): request `source` (`"file"|"object"`) /
`name` / nullable `sessionId`; response `table` (nullable `RTable`) / `error` /
`truncated`. Unlike every other endpoint here, `/preview` is **strictly
read-only** — it never calls `save.image()` or otherwise mutates session
state — and `table` is a single object, not an array (`/execute`'s `tables` is
a list; a preview only ever shows one thing). The backend implementation
reuses the shared `TABLE_EMIT_HELPERS` R source (factored out of `/execute`'s
handler) so both endpoints emit byte-identical `table*.json`. A preview is a
**peek**: for `.csv`/`.tsv` it reads only the first `R_TABLE_MAX_ROWS + 1` rows
(via `read.csv(nrows=)`), so a multi-hundred-MB file doesn't get fully parsed
into memory (that OOM-killed the subprocess before this) — the on-device
`webrPreview` goes further and syncs only an **8 MB prefix** of a large CSV/TSV
into the VFS (not the whole file, which its in-memory VFS can't hold) before the
same `nrows = 201L` read, so a large CSV previews on-device too. Because a capped
read can't know
the true total, when there are more rows than the cap the handler trims to the
cap and sets `truncated = TRUE` (via a `truncated.flag` marker); `table.totalRows`
is then the **displayed** count, not the true total, and `PreviewScreen` shows a
"Showing the first N rows" note. File preview
(`source: "file"`) reads a session data file by extension — base R for
`.csv`/`.tsv`/`.rds`, `readxl`/`arrow` for `.xlsx`/`.parquet` when those
packages are installed in the session's library, else a friendly install-it
error; object preview (`source: "object"`) `get()`s the named value from a
freshly `load()`ed copy of the session's `workspace.RData` (a throwaway
environment, so the live workspace is untouched either way).

`/execute`, `/reset`, `/install`, `/uninstall`, `/import-legacy`, `/symbols`,
`/help`, `/upload`, `/data`, `/delete-data`, and `/preview` are the
auth/rate-limit-protected endpoints (`is_protected` in `plumber.R`). There's no
shared schema file — if you add a field on one side, add it on the other by
hand, and remember the backend must emit **unboxed** JSON (see `run.R`) or
scalar fields won't deserialize.

## Current scope / what's deliberately not built yet

Built so far: the editor screen (write code, run, see output) with R syntax
highlighting, a quick-insert operator bar, named saved scripts, and a persisted
run-history sheet; a Settings screen for the backend URL + API key at runtime;
clipboard/plot sharing; a **durable R session** (workspace + attached packages
persist across runs/restarts, with a workspace summary and a reset action);
**CRAN package installation and removal** (a Packages screen + `/install` /
`/uninstall` / `/packages`, into a persistent per-session library); JVM unit tests (`app/src/test/`) and backend
integration tests (`backend/tests/`, testthat) plus GitHub Actions CI; and
optional API-key auth + per-IP rate limiting on the backend.

Also built: **CRAN package installation** (Packages screen + `/install`) and
**multi-file projects** (named projects of `.R` files with a file switcher and a
pinned entry, a project library, and `files`/`entryFile` execution).

Also built: **per-project R environments** — each project maps to its own
backend session (`ProjectSession.of(project)`), giving it an isolated workspace
*and* package library (`SESSION_DIR/<id>/rlib`) instead of one shared session/
library for the whole app; packages from the old shared library can be pulled
into a project's library on demand via "Import packages from legacy library"
(`/import-legacy`).

Also built: **code assist** — editor **autocomplete** (a suggestion strip driven
by a hybrid symbol set: baked base-R names + workspace objects + installed-package
names + a cached backend `/symbols` index, filtered off the main thread) and
in-app **R help** (`/help` → `tools::Rd2txt` text in a bottom sheet, reached from
completion chips and a `?` help search).

Also built: **session data import** — upload arbitrary files (opaque bytes) from the
phone into a project's session (`POST /upload`, streamed from a SAF `Uri` via
`UriRequestBody` so app memory stays flat) and manage them from a dedicated **Data**
screen (`/data` list, `/delete-data`). Uploaded files are symlinked into each
`/execute` run dir, so code reads them by bare name; they're per-project (they live
under the project's session) and cleared on session reset / project deletion.
Tap-to-insert drops a file's quoted name into the editor.

Also built: **data viewer** — `View(df)` for a workspace object or a one-tap
preview of an uploaded data file, rendered full-screen as a read-only table
(`POST /preview`, capped at 200 rows) without writing or running any code.

Also built: **on-device execution (WebR)** — a swappable `ExecutionEngine` with a
**Local** engine (bundled WebR = real GNU R → WASM, in an offscreen WebView) as
the **default** and the network backend as an opt-in **Remote** engine, chosen by
a Settings toggle. Both engines return the identical `ExecuteResponse` contract
(stdout/stderr/plots/tables/workspaceObjects/timedOut), so the UI is
engine-agnostic; the WebR harness (`assets/webr/harness.R`) mirrors the backend's
`withVisible` loop + `TABLE_EMIT_HELPERS`. The Local engine also supports
**on-device package install** — `ExecutionEngine` carries `listPackages`/`install`/
`uninstall` (Remote delegates to the backend endpoints; Local delegates to
`WebRController` → `bridge.js` `webrInstall`/`webrUninstall`/`webrListPackages`),
and `PackagesViewModel` routes them through `ServiceLocator.currentExecutionEngine()`.
Local installs are **hybrid-sourced** — `webr::install(pkg, repos = c(<bundled repo>,
"https://repo.r-wasm.org"))` searches a bundled mini-repo (`assets/webr/repo/`,
vendored by `scripts/fetch-webr-packages.mjs`: tidyverse + easystats core + full
dependency closure, installable **offline**) before falling back to the network for
anything else. The local **PACKAGES index must carry each package's full upstream
control block** (Depends/Imports/LinkingTo/MD5sum), or WebR installs a top-level
package without its deps and it fails to load. Packages install into a user library
(`/rmobile/library`, created lazily and prepended to `.libPaths()` inside the package
ops **only** — never at boot, so the run path is untouched). WebR installs each
package as a **mounted FS image**, so uninstall must `webR.FS.unmount` the package
path first, then `chmod`+`unlink` any leftover files (an app-restart restores packages
as plain read-only files instead — the unmount is then a no-op and the unlink clears
them); removal is confirmed via `installed.packages(noCache = TRUE)` (not the
directory, which an unmounted mount leaves behind empty), and the namespace is
unloaded afterward so a loaded package stops working immediately. The library **persists across app restarts** via a
**snapshot/restore** mechanism (NOT IDBFS `FS.mount`, which destabilised the eval
channel): after each install/uninstall the library is `utils::tar`'d and streamed to
Kotlin in base64 chunks (`WebRController` stores it under `filesDir`), and at boot
it's written back and `utils::untar(..., tar = "internal")`'d before ready
(`tar="internal"` is required — the default untar shells out via `system()`, which
Emscripten forbids). `bridge.js` also **strips CR from harness.R** on load: a CRLF
(Windows autocrlf) checkout otherwise leaves a stray `\r` after `local({` that WebR's
R parser rejects (`.gitattributes` also pins `webr/*.R` to LF). The Local engine also
supports **data import** — data ops route through a `SessionDataStore` abstraction
(`data/datafiles/`: Remote delegates to the backend `/upload`/`/data`/`/delete-data`;
Local = `LocalSessionDataStore`, on-device storage under `filesDir/localdata/<session>/`),
chosen by `ServiceLocator.currentSessionDataStore()`. On-device files are made readable
by bare name via a **lazy VFS sync**: `bridge.js` `syncData()` mirrors a session's files
into `/rmobile/data/<session>` (chunked base64 via `WebRController` `dataList`/`dataChunk`,
pulling only new/changed), and `linkData()` symlinks them into each run's cwd (`copy`
fallback if WASM symlinks misbehave) — synced after `resetRunDir`, linked after the run's
own files so those shadow same-named data files. **Preview** is engine-routed too (added to
`ExecutionEngine`; Local = `bridge.js` `webrPreview`, which reads a file/object and emits an
`RTable`). The Local **workspace** (the shared WebR `globalenv()`) now **persists across
app restarts** via a `save.image`→`filesDir`→`load()`-at-boot snapshot — the same
best-effort snapshot/restore path as the package library, streamed through the shared
`SnapshotStore` (keyed by `kind`: `library` | `workspace`). It's snapshotted after each
successful run (fire-and-forget, guarded so it can't race the next run, skipped above a
200 MB blob) and cleared on reset. **v1 boundary that remains (deliberate, not built
yet)** — **engine choice is app-wide, not per-project**. The Data/Packages screens adapt
their caption to the active engine (the Data screen also warns above 200 MB on Local,
since its FS is in-memory).

Still **not** built — don't assume these exist: per-project engine choice, and
(backend) network-egress restriction in the dev profile or per-request VM isolation.
