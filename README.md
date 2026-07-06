# R Mobile (Android)

An Android client for writing and running R code on your phone, backed by a
small server-side execution service — the Android counterpart to iOS apps
like "R Programming Compiler". Real R can't run on-device (its interpreter
depends on Fortran/C internals that don't build for mobile), so the app is a
thin client: you write R, the app sends it to `/backend`, and the response
(console output + plots) renders back in the app.

See `CLAUDE.md` for architecture, conventions, and how the pieces fit
together. See `backend/README.md` for backend-specific setup and — important
— its security caveats before deploying it anywhere public.

## Quick start

1. Start the execution backend:
   ```bash
   cd backend && docker compose up --build
   ```
2. Open the repo root in Android Studio (it's a standard Gradle project) and
   run the `app` module on an emulator. The emulator reaches the backend at
   `10.0.2.2:8000` by default — no config needed for local dev.
3. On a physical device, point the app at your machine's LAN IP — either at
   build time, or in the app's **Settings** screen at runtime (no rebuild):
   ```bash
   ./gradlew :app:installDebug -PrExecutionBaseUrl=http://192.168.1.23:8000/
   ```

## Features

- Single-screen editor: write R, tap Run, see stdout/stderr and plots.
- **R syntax highlighting** and a **quick-insert bar** for common operators
  (`<-`, `|>`, `%>%`, `()`, …).
- **Named saved scripts** and **run history**, both persisted — reopen, restore,
  and delete.
- **In-app Settings** — change the backend URL and API key at runtime, with a
  **Test connection** button that pings the backend's `/health`.
- **Copy output** to the clipboard and **share plots** as PNGs.
- **Durable R session** — variables and attached packages persist between runs
  and restarts; a workspace summary shows what's in scope, and "Reset session"
  clears it.
- **CRAN package installation** — install packages that persist and load in
  later runs, from a Packages screen.
- **Multi-file projects** — named projects of `.R` files that `source()` each
  other; a file switcher with a pinned entry file, and a project library.
- Distinct handling of execution **timeouts** vs. errors.
- Backend with optional **API-key auth** and **per-IP rate limiting**.

Not built yet: on-device execution, iOS-app feature parity.

## Development

- `./gradlew :app:testDebugUnitTest` — JVM unit tests (no device needed).
- CI (`.github/workflows/android.yml`) runs the unit tests, Lint, and a debug
  build on every push/PR — the Android SDK isn't available in every dev
  environment, so CI is the source of truth for "does it build".
