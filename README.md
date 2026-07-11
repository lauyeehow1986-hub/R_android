# R Mobile (Android)

An Android client for writing and running R code on your phone — the Android
counterpart to iOS apps like "R Programming Compiler". It runs R two ways: a
default **on-device** engine (WebR — real GNU R compiled to WebAssembly, bundled
in the app, so your code and data never leave the phone and work offline), and an
opt-in **backend** engine (a small server-side execution service) for full CRAN
package compatibility and heavier work. Either way you write R, tap Run, and the
response (console output + plots + tables) renders back in the app.

See `CLAUDE.md` for architecture, conventions, and how the pieces fit
together. See `backend/README.md` for backend-specific setup and — important
— its security caveats before deploying it anywhere public.

## Setup

The app runs R **on-device** by default (the Local/WebR engine, fully offline —
nothing else to install or run). The **backend** is only needed if you want the
Remote engine. So the minimum to try the app is just "build and run"; the Docker
step is optional.

### Prerequisites

- **Android Studio** (latest stable) with the **Android SDK** — the easiest way
  to build, and it bundles a JDK you can reuse from the command line.
- An **Android emulator** (create one in Android Studio's Device Manager, API 26+)
  or a physical device with **USB debugging** enabled.
- **Docker Desktop** — only if you want to run the backend for the Remote engine.
- **Node.js + npm** — only if you ever need to re-vendor the WebR runtime (it's
  already committed, so normally you don't).

### 1. Run the app

**Option A — Android Studio (simplest):** open the repo root (it's a standard
Gradle project), pick your emulator/device, and press Run on the `app` module.

**Option B — command line.** You need `JAVA_HOME` pointing at a JDK 17. The one
bundled with Android Studio works well.

- **Windows (PowerShell):**
  ```powershell
  cd C:\Users\<you>\path\to\R_android
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat :app:installDebug
  ```
- **macOS / Linux (bash):**
  ```bash
  cd /path/to/R_android
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # or your JDK 17
  ./gradlew :app:installDebug
  ```

`installDebug` builds and installs onto the running emulator/connected device.
Open **R Mobile** and tap Run — it works offline on the Local engine immediately.

> **Windows PowerShell gotchas** (these bite often): use `.\gradlew.bat` (not
> `./gradlew`); set env vars with `$env:NAME = "value"` on their own line (not
> `NAME=value`); PowerShell 5.1 has **no `&&`** — put each command on its own line
> or join with `;`; and use Windows paths (`C:\...`) not `/c/...`.

### 2. Run the backend (optional — only for the Remote engine)

Requires **Docker Desktop running**. From the repo root:

- **Windows (PowerShell):**
  ```powershell
  cd backend
  docker compose up --build
  ```
- **macOS / Linux (bash):**
  ```bash
  cd backend && docker compose up --build
  ```

This serves the R execution API on `:8000` in the foreground (streams logs;
`Ctrl+C` to stop, or add `-d` to detach). An emulator reaches it at the special
host alias `http://10.0.2.2:8000` — the app's default, no config needed.

### 3. Choosing an engine

- **Local (default):** on-device WebR. Offline, private, no backend. Uses WebR's
  built-in packages only.
- **Remote:** switch in the app's **Settings → Execution engine**. Sends code to
  the backend for full CRAN packages. Needs step 2 running. Debug builds permit
  plain-HTTP (cleartext) to your self-hosted backend; release builds do not.

### Pointing at a backend on your LAN (physical device)

A physical device can't use `10.0.2.2`. Point the app at your machine's LAN IP —
either at build time, or at runtime in **Settings** (no rebuild):

- **Windows (PowerShell):** `.\gradlew.bat :app:installDebug "-PrExecutionBaseUrl=http://192.168.1.23:8000/"`
- **macOS / Linux (bash):** `./gradlew :app:installDebug -PrExecutionBaseUrl=http://192.168.1.23:8000/`

### Re-vendoring the WebR runtime (rarely needed)

The WebR runtime is committed under `app/src/main/assets/webr/dist/`, so the app
ships it and works offline from install. To update or re-fetch it (needs npm +
internet), run — and read `app/src/main/assets/webr/README.md` first, especially
the AAPT `.gz` note:

```bash
bash app/src/main/assets/webr/scripts/fetch-webr.sh
```

## Features

- **On-device execution (WebR)** — run R fully offline with a bundled WebR
  runtime (real GNU R → WebAssembly); this **Local** engine is the default and
  keeps code and data on the phone. Switch to the **Remote** (backend) engine in
  Settings for full CRAN package support. Both produce the same output
  (stdout/stderr, plots, tables, workspace). *v1 limits:* the Local engine uses
  only WebR's built-in packages, can't see uploaded data files, and its workspace
  resets when the app restarts — data import and installed packages apply to the
  Remote engine.
- Single-screen editor: write R, tap Run, see stdout/stderr and plots.
- **R syntax highlighting** and a **quick-insert bar** for common operators
  (`<-`, `|>`, `%>%`, `()`, …).
- **Code assist** — an autocomplete **suggestion strip** (base R + your workspace
  objects + installed-package symbols) and in-app **R help** (`?fn`), reached from
  a completion chip or a `?` help search, rendered as text in a sheet.
- **Named saved scripts** and **run history**, both persisted — reopen, restore,
  and delete.
- **In-app Settings** — change the backend URL and API key at runtime, with a
  **Test connection** button that pings the backend's `/health`.
- **Copy output** to the clipboard and **share plots** as PNGs.
- **Rich output** — data frames (and tibbles/data.tables/matrices/`summary()`)
  render as sortable, filterable, resizable tables with tap-to-expand cells;
  plots open full-screen with pinch-zoom.
- **Durable R session** — variables and attached packages persist between runs
  and restarts; a workspace summary shows what's in scope, and "Reset session"
  clears it.
- **CRAN package management** — install packages that persist and load in
  later runs, and uninstall them again, from a Packages screen.
- **Multi-file projects** — named projects of `.R` files that `source()` each
  other; a file switcher with a pinned entry file, and a project library.
  Export/import a project as a `.zip` of its files (share-sheet / file picker).
- **Per-project R environments** — each project gets its own workspace and
  installed-package library on the backend; packages from the older shared
  library can be imported into a project on demand.
- **Data import** — upload files (CSV, RDS, xlsx, anything) from your phone into a
  project's session and read them in code by name (`read.csv("sales.csv")`), from a
  dedicated **Data** screen (upload / list / delete). Tap a file to insert its name
  into the editor.
- **Data viewer** — preview tabular data as a table without writing code: tap
  an uploaded file on the **Data** screen (CSV/TSV/RDS, plus Excel/Parquet when
  `readxl`/`arrow` are installed), or tap a data-frame variable in the editor's
  workspace strip to `View()` it. Opens full-screen, capped at 200 rows.
- Distinct handling of execution **timeouts** vs. errors.
- Backend with optional **API-key auth** and **per-IP rate limiting**.

Not built yet: on-device package installation, local-engine data import,
cross-restart local workspace persistence, per-project engine choice, iOS-app
feature parity.

## Development

- JVM unit tests (no device needed):
  - Windows (PowerShell): `.\gradlew.bat :app:testDebugUnitTest`
  - macOS / Linux (bash): `./gradlew :app:testDebugUnitTest`

  (with `JAVA_HOME` set as in Setup step 1).
- Backend integration tests (testthat + httr2): `Rscript backend/run-tests.R`.
- CI (`.github/workflows/android.yml`) runs the unit tests, Lint, and a debug
  build on every push/PR — the Android SDK isn't available in every dev
  environment, so CI is the source of truth for "does it build".
