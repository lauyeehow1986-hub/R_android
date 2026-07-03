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
3. On a physical device, point the app at your machine's LAN IP instead:
   ```bash
   ./gradlew :app:installDebug -PrExecutionBaseUrl=http://192.168.1.23:8000/
   ```

## Status

Early scaffold: a single-screen editor (write R, tap Run, see stdout/stderr
and plots) and a minimal Plumber backend. Feature parity with the iOS app,
package installation, snippet history, and syntax highlighting are not built
yet.
