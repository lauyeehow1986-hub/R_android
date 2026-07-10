# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android client for writing and running R code on a phone — the Android
counterpart to iOS apps like "R Programming Compiler"/"Rlytic". There are two
independent pieces in this one repo:

- `app/` — native Android client (Kotlin + Jetpack Compose).
- `backend/` — a Plumber (R) HTTP service that actually executes R code.

**Why there's a backend at all**: real R can't run on-device. Its
interpreter depends on Fortran/C internals that don't build for Android or
iOS, which is why the existing iOS apps are thin clients too (confirmed:
they require internet, cap execution at ~20s, and describe themselves as
"batch compilers"). The alternative — embedding the JVM-based Renjin
interpreter for offline execution — was considered and explicitly rejected
for the MVP in favor of full CRAN/package compatibility; see "Execution
model" below before reviving that idea.

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
  by the `RCodeVisualTransformation`), `insertAt` (`EditorTextOps`), and
  `SavedScriptLibrary` (`data/scripts/`, list ops for named scripts persisted
  via `SettingsStore`).
- `ui/settings/` — `SettingsScreen` + `SettingsViewModel` for editing the
  backend URL and API key at runtime, with a "Test connection" action backed by
  `NetworkModule.probeHealth` (pings `<url>/health` through a separate client
  that bypasses the host-rewriting interceptor, so an unsaved URL can be
  checked). `SettingsViewModel` depends on the `AppSettings` interface (not the
  concrete `SettingsStore`) so it's unit-testable without SharedPreferences.
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
- `data/settings/` — `SettingsStore` (SharedPreferences) persists the backend
  URL, API key, run history, saved scripts, and **projects**; `BaseUrlValidator`
  is the pure, unit-tested URL normalizer. `data/history/` holds the
  `HistoryEntry` model and `RunHistory` (pure list logic). `data/ServiceLocator`
  is initialized once by `RMobileApplication` and applies persisted settings to
  `NetworkModule` at startup; the (context-less) ViewModels read it via default
  constructor args, which is also the seam unit tests inject fakes through.
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

**Packages**: the wrapper also prepends a shared library `R_PKG_LIB` (default
`/data/rlib`, its own read-write named volume) to `.libPaths()`, so `library()`
and inline `install.packages()` see installed packages. `POST /install` (body
`{"package"}`, name-validated, its own `R_INSTALL_TIMEOUT_SECONDS`) installs into
that lib in an isolated subprocess and reports `installed`/`systemRequirements`;
`GET /packages` lists them. Common package system libraries are pre-baked into
the image (no runtime apt — the container stays hardened).

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
returned by `plumber.R`. `/execute`: request `code` + optional `sessionId`, OR a
multi-file project `files` (`[{name,content}]`) + `entryFile` (backend writes the
files and `source()`s the entry; `files` wins over `code`); response `stdout`,
`stderr`, `plots`, `tables`, `error`, `timedOut`, `workspaceObjects` (nullable).
`tables` (`RTable(columns, columnTypes, rows, totalRows)`) holds any data
frame/tibble/data.table/matrix/2-D `table` **printed at the entry's top level** —
the wrapper runs top-level expressions through a `withVisible` eval loop
(instead of bare `source()`) and emits `inherits(., "data.frame")`/2-D values as
`table*.json` side-channel files (rows capped at `R_TABLE_MAX_ROWS`, default
200); prints inside functions/`source()`d files aren't captured. `/reset`:
request `sessionId`, response `ok`. `/install`
(`InstallRequest`/`InstallResponse`, in `data/model/PackageModels.kt`): request
`package` (Kotlin property `packageName` via `@SerialName("package")`), response
`stdout`/`stderr`/`error`/`timedOut`/`installed`/`systemRequirements`.
`/uninstall` (`UninstallRequest`/`UninstallResponse`): request `package` (same
`@SerialName` renaming), response `removed`/`error`. `/packages` →
`PackagesResponse(packages)`. `/execute`, `/reset`, `/install`, and `/uninstall`
are the auth/rate-limit-protected endpoints (`is_protected` in `plumber.R`).
There's no shared schema file — if
you add a field on one side, add it on the other by hand, and remember the
backend must emit **unboxed** JSON (see `run.R`) or scalar fields won't
deserialize.

## Current scope / what's deliberately not built yet

Built so far: the editor screen (write code, run, see output) with R syntax
highlighting, a quick-insert operator bar, named saved scripts, and a persisted
run-history sheet; a Settings screen for the backend URL + API key at runtime;
clipboard/plot sharing; a **durable R session** (workspace + attached packages
persist across runs/restarts, with a workspace summary and a reset action);
**CRAN package installation and removal** (a Packages screen + `/install` /
`/uninstall` / `/packages`, into a shared persistent library); JVM unit tests (`app/src/test/`) and backend
integration tests (`backend/tests/`, testthat) plus GitHub Actions CI; and
optional API-key auth + per-IP rate limiting on the backend.

Also built: **CRAN package installation** (Packages screen + `/install`) and
**multi-file projects** (named projects of `.R` files with a file switcher and a
pinned entry, a project library, and `files`/`entryFile` execution).

Still **not** built — don't assume these exist: on-device execution, and
network-egress restriction or per-request VM isolation on the backend.
