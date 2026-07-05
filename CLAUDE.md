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

There's no separate backend test suite yet — validate changes to
`plumber.R` by hitting `/execute` directly with curl.

## Architecture

### Android app (`app/src/main/java/com/rmobile/console/`)

Single-activity, manual DI (no Hilt/Koin — the object graph is one
Retrofit client, not worth a framework yet):

- `MainActivity.kt` → sets Compose content, wraps everything in
  `RConsoleTheme`.
- `MainActivity.kt` also hosts `AppRoot`, a two-state (`EDITOR`/`SETTINGS`)
  in-app switch — deliberately no navigation library for two screens.
- `ui/editor/` — the main screen: `EditorScreen` (Compose UI: code input with
  R syntax highlighting, Run button, output panel with text + decoded plot
  bitmaps, plus a run-history bottom sheet) driven by `EditorViewModel`
  (`StateFlow<EditorUiState>`, standard unidirectional-data-flow — mutate state
  via `ViewModel` methods, never from the composable). Syntax highlighting is
  split into a pure tokenizer (`RSyntaxHighlighter`) and a Compose
  `VisualTransformation` (`RCodeVisualTransformation`) so the tokenizer is
  unit-testable.
- `ui/settings/` — `SettingsScreen` + `SettingsViewModel` for editing the
  backend URL and API key at runtime.
- `data/` — `RExecutionRepository` wraps `RExecutionApi` (Retrofit
  interface) in a `Result`-returning suspend call. `data/network/NetworkModule`
  is the single hand-rolled DI point: one lazily-built OkHttp/Retrofit
  instance built against a placeholder base URL, with a
  `HostSelectionInterceptor` that rewrites each request's host/scheme/port (and
  path prefix) to the **runtime-configured** URL and attaches the optional
  `X-API-Key` header — so changing the backend needs no rebuild.
- `data/settings/` — `SettingsStore` (SharedPreferences) persists the backend
  URL, API key, and run history; `BaseUrlValidator` is the pure, unit-tested
  URL normalizer. `data/history/` holds the `HistoryEntry` model and
  `RunHistory` (pure list logic). `data/ServiceLocator` is initialized once by
  `RMobileApplication` and applies persisted settings to `NetworkModule` at
  startup; the (context-less) ViewModels read it via default constructor args,
  which is also the seam unit tests inject fakes through.
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
`/execute` only (health checks stay open): optional `X-API-Key` auth when
`R_API_KEY` is set, and an in-process per-IP fixed-window rate limit when
`R_RATE_LIMIT_PER_MINUTE > 0`. Both are **off by default** for local dev.

The Dockerfile/`docker-compose.yml` run this as an unprivileged user with a
read-only root filesystem, dropped capabilities, and CPU/memory limits —
**this is still not a hardened multi-tenant sandbox** (no network egress
restriction, no per-request container/VM isolation; the rate limiter is a
single-instance in-memory counter that trusts `REMOTE_ADDR`).
Read `backend/README.md`'s security section in full before changing the
execution model or deploying this anywhere reachable from the internet —
this service is arbitrary-code-execution-as-a-feature by design, so changes
here have different stakes than changes to the Android UI.

### Response contract between the two halves

`ExecuteRequest`/`ExecuteResponse` in
`app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt`
(kotlinx.serialization) must stay in sync field-for-field with the JSON
list returned by `plumber.R`'s `/execute` handler (`stdout`, `stderr`,
`plots`, `error`, `timedOut`). There's no shared schema file — if you add a
field on one side, add it on the other by hand.

## Current scope / what's deliberately not built yet

Built so far: the editor screen (write code, run, see output) with R syntax
highlighting and a persisted run-history sheet; a Settings screen for the
backend URL + API key at runtime; JVM unit tests (`app/src/test/`) plus a
GitHub Actions CI workflow; and optional API-key auth + per-IP rate limiting
on the backend.

Still **not** built — don't assume these exist: package-installation UI,
multi-file projects, on-device execution, network-egress restriction or
per-request VM isolation on the backend, and any backend automated test suite
(validate `plumber.R` by hitting `/execute` with curl).
