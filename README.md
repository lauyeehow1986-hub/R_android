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

## Install (from a GitHub Release)

The easiest way to try the app — no build tools needed.

1. Open this repo's **Releases** page and download the latest `app-release.apk`.
2. Get it onto your Android phone (download it on the phone, or copy it over).
3. Tap the APK. Android asks permission to install from this source the first
   time — approve it, then install.
4. Open **R Mobile** and tap **Run**. It works **immediately and offline** on the
   default on-device (Local/WebR) engine — no account, no server, nothing to
   configure. Your code and data stay on the phone.

Requires Android 8.0 (API 26) or newer. Updates install over the top as long as
they're signed with the same release key.

> The **Remote** engine is optional and points at a backend **you** run — see
> "Self-hosting the Remote backend" below. The released app ships with **no**
> backend URL or API key baked in; there is no shared or public server.

## Self-hosting the Remote backend (optional)

You only need this if you want the **Remote** engine (full CRAN packages / heavier
work). The app runs R fully on-device by default, so this is entirely optional.
The backend is a small Dockerised R service in `backend/` that you run yourself.

### 1. Run it

Requires **Docker** on the host (Windows/macOS/Linux). From the repo:

```bash
cd backend
docker compose up -d --build   # serves the R execution API on :8000
```

### 2. Secure it (before anything but localhost can reach it)

This service runs arbitrary R code **by design** — treat the endpoint as
sensitive. Create `backend/.env` (gitignored) with an API key and a per-minute
rate cap:

```
R_API_KEY=<a long random string>
R_RATE_LIMIT_PER_MINUTE=60
```

Generate a key with `openssl rand -hex 32`, then rebuild
(`docker compose up -d --build`) so it takes effect. Every request must now send
that key.

### 3. Put HTTPS in front (required for the released app)

The **release** APK (what you install from Releases) **blocks plain-HTTP
(cleartext)** traffic, so the Remote engine must reach your backend over
**`https://`**. Two domain-free ways:

- **Tailscale (recommended).** Put the phone and the backend host on a free
  [Tailscale](https://tailscale.com) network, then run
  `tailscale serve --bg http://localhost:8000` on the host. You get a real HTTPS
  certificate on a private `*.ts.net` name — no domain, no port-forwarding, and
  the backend never touches the public internet.
- **A reverse proxy with TLS** (Caddy/nginx/Traefik) in front of `:8000`, using a
  domain + Let's Encrypt or a certificate your phone trusts.

(Building a **debug** APK yourself from source instead permits plain
`http://<lan-ip>:8000` for local testing — see the debug manifest overlay.)

### 4. Point the app at it

In the app: **Settings** → set **Backend URL** to your `https://…` address and
**API key** to your `R_API_KEY` → **Test connection** → switch **Execution
engine** to **Remote**.

Read `backend/README.md`'s security section (hardened Docker profile, per-session
isolation, what is and isn't sandboxed) before exposing the backend anywhere.

## Setup (build from source)

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

The bundled offline package repo (core tidyverse/easystats + dependencies, used
by the Local engine's package install) is vendored separately — re-vendor or
extend the set with:

```bash
bash app/src/main/assets/webr/scripts/fetch-webr-packages.sh
```

## Features

- **On-device execution (WebR)** — run R fully offline with a bundled WebR
  runtime (real GNU R → WebAssembly); this **Local** engine is the default and
  keeps code and data on the phone. Switch to the **Remote** (backend) engine in
  Settings for full CRAN package support. Both produce the same output
  (stdout/stderr, plots, tables, workspace). The Local engine can now **install
  packages** too — core tidyverse/easystats packages install fully offline from a
  bundled repo, and anything else downloads on demand; the on-device library
  **persists across app restarts** (snapshotted to the app's private storage).
  It can also **import data** — upload files on-device, read them in code by name
  (`read.csv("x.csv")`), and preview them as tables (files stored in the app's
  private storage; up to 1 GB, though large files may exhaust the on-device
  engine's memory). The Local engine's workspace **and** installed package library
  both **persist across app restarts** (per-project snapshots in private storage),
  and the **engine is chosen per project** (the Settings toggle is the default for
  new projects).
- Single-screen editor: write R, tap Run, see stdout/stderr and plots.
- **R syntax highlighting** and a **quick-insert bar** for common operators
  (`<-`, `|>`, `%>%`, `()`, …).
- **Code assist** — an autocomplete **suggestion strip** (base R + your workspace
  objects + installed-package symbols) and in-app **R help** (`?fn`), reached from
  a completion chip or a `?` help search, rendered as text in a sheet. On the Local
  engine both resolve **on-device** (real `tools::Rd2txt` help + package exports), so
  help and completions work **fully offline**; if a Local lookup finds nothing it
  falls back to the Remote backend, but only when one is configured (so a Local-only
  device answers instantly instead of waiting on the network).
- **Named saved scripts** and **run history**, both persisted — reopen, restore,
  and delete.
- **In-app Settings** — change the backend URL and API key at runtime, with a
  **Test connection** button that pings the backend's `/health`.
- **Copy output** to the clipboard and **share plots** as PNGs.
- **Rich output** — data frames (and tibbles/data.tables/matrices/`summary()`)
  render as sortable, filterable, resizable tables with tap-to-expand cells;
  plots open full-screen with pinch-zoom. Text, plots, and tables render in the
  **order R produced them** (falling back to grouped output on older backends).
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

Not built yet: iOS-app feature parity.

## Architecture

Two independent pieces (see `CLAUDE.md` for the full version and conventions):

- **`app/`** — a single-activity Jetpack Compose client (Kotlin, manual DI). R runs
  behind one `ExecutionEngine` interface with two implementations: a **Local** engine
  (bundled WebR — GNU R → WebAssembly, in an offscreen WebView) and a **Remote**
  engine (the backend). The engine is chosen **per project**. Every R-facing
  operation — run, reset, data preview, package install/list, **R help**, and the
  autocomplete **symbol index** — is routed through the active project's engine, so
  the UI is engine-agnostic and the same features work on-device or over the network.
  Local operations are **local-first**: an on-device lookup that comes up empty falls
  back to the Remote backend only when a backend URL is configured.
- **`backend/`** — a Plumber (R) HTTP service that executes R in isolated subprocesses.
  It backs the Remote engine (and the best-effort help/symbol fallbacks). This is
  arbitrary-code-execution-as-a-feature — read `backend/README.md`'s security section
  before exposing it anywhere reachable.

Both engines return the **same response contract** (stdout/stderr, plots, tables,
workspace objects), and the on-device WebR harness mirrors the backend's execution
wrapper, so output is identical across engines. Per-project workspaces and package
libraries persist across app restarts via snapshots in the app's private storage.

## Releasing (maintainers)

The `Release APK` workflow (`.github/workflows/release.yml`) publishes a **signed**
release APK to a GitHub Release when you push a `v*` tag. One-time setup:

1. **Generate a release keystore** — keep it safe and backed up; every future
   update must be signed with the *same* key:
   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
     -validity 10000 -alias rmobile
   ```
2. **Add repository secrets** (Settings → Secrets and variables → Actions):
   - `KEYSTORE_BASE64` — the keystore, base64-encoded:
     - macOS/Linux: `base64 -i release.jks | tr -d '\n'`
     - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — the values you chose above.
3. **Bump the version** in `app/build.gradle.kts` (`versionCode` +1, `versionName`)
   for each release.
4. **Tag and push:**
   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

To build a signed APK **locally** instead, put a gitignored `keystore.properties`
at the repo root:

```
storeFile=release.jks
storePassword=…
keyAlias=rmobile
keyPassword=…
```

then run `.\gradlew.bat :app:assembleRelease` (Windows) or
`./gradlew :app:assembleRelease`. The APK lands in
`app/build/outputs/apk/release/app-release.apk`.

Neither the workflow nor a local build bakes any backend URL or API key into the
APK — those live only in each user's in-app Settings, so releases ship
credential-free.

## Development

- JVM unit tests (no device needed):
  - Windows (PowerShell): `.\gradlew.bat :app:testDebugUnitTest`
  - macOS / Linux (bash): `./gradlew :app:testDebugUnitTest`

  (with `JAVA_HOME` set as in Setup step 1).
- Backend integration tests (testthat + httr2): `Rscript backend/run-tests.R`.
- CI (`.github/workflows/android.yml`) runs the unit tests, Lint, and a debug
  build on every push/PR — the Android SDK isn't available in every dev
  environment, so CI is the source of truth for "does it build".
