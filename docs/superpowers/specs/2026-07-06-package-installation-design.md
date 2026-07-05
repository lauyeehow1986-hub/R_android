# Package installation — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/` + CI

## Problem

Users can run R, but can't add CRAN packages. `install.packages()` in submitted
code fails: the container root filesystem is read-only and `Rscript --vanilla`
has no writable library on `.libPaths()`. So only base + the backend's bundled
packages are usable.

## Goal

Let users install CRAN packages that persist and become usable in later runs —
via **both** a dedicated install flow (endpoint + in-app screen, with a generous
timeout and feedback) **and** inline `install.packages()` in normal code (bounded
by the exec timeout).

## Decisions (from brainstorming)

- **Both mechanisms.** A `POST /install` endpoint + a Packages screen, plus a
  writable persistent library so inline `install.packages()` also works.
- **Shared global library.** One library dir on the volume, used by every run
  (install once, available everywhere). Not per-session.

## Backend design

### Shared library
- `R_PKG_LIB` (default `/data/rlib`) is a writable library directory, created in
  the Dockerfile owned by `rexec`, persisted via a **second** named volume
  (`r_rlib:/data/rlib`) — the existing `r_sessions` volume is mounted at
  `/data/sessions`, so the library needs its own mount.
- The `/execute` wrapper prelude gains a first line that prepends the lib:
  ```r
  .libPaths(c("<R_PKG_LIB>", .libPaths()))
  ```
  (path inlined as a literal, like the session paths). This makes `library()`
  find installed packages and makes inline `install.packages()` install into the
  writable lib (it targets the first writable `.libPaths()` entry). The line is
  added even when `R_PKG_LIB` is unset by defaulting it to `/data/rlib` in
  `plumber.R`.

### `POST /install`
- Body `{ "package": "<name>", "sessionId": "<id>"? }`. `package` is validated
  against `^[A-Za-z0-9._]+$` (a single package name); invalid/missing → 400.
- Runs an isolated subprocess (like `/execute`) whose script is:
  ```r
  .libPaths(c("<R_PKG_LIB>", .libPaths()))
  install.packages("<pkg>", repos = "<repo>")
  ```
  with a hard timeout of `R_INSTALL_TIMEOUT_SECONDS` (default `300`). `<repo>` is
  `R_CRAN_REPO` (default a Posit Package Manager URL for fast binary installs on
  the image's platform).
- Response: `{ stdout, stderr, error, timedOut, installed, systemRequirements }`,
  where `installed` is the result of checking `requireNamespace("<pkg>")` in the
  shared lib after the run (so a warning-only "package not available" reports
  `installed:false` with a non-null `error`). On failure, `systemRequirements`
  is a best-effort string of the apt commands the package needs, from
  `remotes::system_requirements("ubuntu", "22.04", package = "<pkg>")` (empty
  when none/unknown) — this is the "surface the exact apt command" path for a
  package whose system lib isn't pre-baked. Unboxed JSON like the other endpoints.
- Guarded by the existing `auth` + `ratelimit` filters (via `is_protected()` —
  add `/install`): it is RCE + resource-heavy.

### `GET /packages`
- Returns `{ "packages": ["<name>", ...] }` from
  `installed.packages(lib.loc = R_PKG_LIB)[, "Package"]` (only user-installed
  packages in the shared lib, not base). Read-only; left **open** (not in
  `is_protected`), like `/health`.

### Config summary (env)
- `R_PKG_LIB` (default `/data/rlib`)
- `R_INSTALL_TIMEOUT_SECONDS` (default `300`)
- `R_CRAN_REPO` (default the Posit Package Manager **binary** repo for the image's
  distro — `https://packagemanager.posit.co/cran/__linux__/jammy/latest` for the
  `rocker/r-ver:4.4.1` (Ubuntu 22.04) base; operators on a different base image
  set this to match). Binary packages mean no compilation for most installs.

## App design

- **Models** (`ExecuteModels.kt` or a new `PackageModels.kt`):
  - `InstallRequest(package: String, sessionId: String? = null)`
  - `InstallResponse(stdout, stderr, error, timedOut, installed, systemRequirements)`
    (all defaulted; `installed: Boolean = false`,
    `systemRequirements: String? = null`). The Packages screen shows
    `systemRequirements` when present ("this package needs: …").
  - `PackagesResponse(packages: List<String> = emptyList())`
  - Note: `package` is a Kotlin soft keyword; the property is fine but the JSON
    key must be `package` — use `@SerialName("package")` on a differently-named
    Kotlin property (e.g. `packageName`) to avoid confusion.
- **API/repository**: `@POST("install")`, `@GET("packages")`;
  `RExecutionRepository.install(packageName)` and `listPackages()` returning
  `Result<…>`.
- **Packages screen** (`ui/packages/PackagesScreen` + `PackagesViewModel`):
  - Loads the installed list from `GET /packages` on open.
  - A package-name text field + **Install** button → `POST /install`; shows a
    spinner while in-flight (a dedicated `installing` state), then the result
    (installed / error + stdout/stderr log). Refreshes the list on success.
  - Reached from the editor's overflow menu ("Packages", beside "Reset
    session") via a new `PACKAGES` entry in `AppRoot`'s screen switch.
- `PackagesViewModel` depends on `RExecutionRepository` (injectable) so it's
  unit-testable with a fake, matching the existing ViewModels.

## Ops

- `docker-compose.yml`: add volume `r_rlib:/data/rlib` to the service and to the
  top-level `volumes:`; add `R_PKG_LIB`, `R_INSTALL_TIMEOUT_SECONDS`,
  `R_CRAN_REPO` env.
- `Dockerfile`:
  - `RUN mkdir -p /data/rlib && chown -R rexec:rexec /data/rlib`.
  - **Pre-bake common R-package system libraries** (build-time `apt-get`, so no
    runtime apt is ever needed — the container stays non-root + read-only-root):
    `libxml2-dev`, `libfontconfig1-dev`, `libharfbuzz-dev`, `libfribidi-dev`,
    `libfreetype6-dev`, `libpng-dev`, `libjpeg-dev`, `libtiff5-dev`,
    `libgit2-dev`, `libssh2-1-dev`, `libicu-dev`, `zlib1g-dev`
    (added to the existing `libsodium-dev`; `libcurl4-openssl-dev`/`libssl-dev`
    are already needed and included). Combined with binary installs, this covers
    the common tidyverse/devtools ecosystem.
  - Add `remotes` to the image's `install2.r` package list (used by `/install`
    to report system requirements on failure).

## Testing

- **Backend suite** (extends the testthat suite; runs in Docker + CI):
  - `GET /packages` on a fresh lib → `packages` is empty.
  - `POST /install` with an invalid name (e.g. `"../evil"`) → 400.
  - `POST /install` with a nonexistent package (e.g. `"nonexistent.pkg.xyz"`) →
    `installed:false`, `error` non-null (fast; no real download).
  - An `/execute` run confirms the shared lib is on `.libPaths()`. Because
    `.libPaths()` silently drops nonexistent dirs, each package test starts its
    server with `R_PKG_LIB` pointed at a **freshly created** temp dir (via
    `local_server(env = list(R_PKG_LIB = <existing tempdir>))`); the wrapper's
    default `/data/rlib` only exists in the real container.
  - `/install` is auth-protected (401 without key when `R_API_KEY` set).
  - **Real install happy path in CI**: `POST /install` with `praise` (a tiny,
    pure-R, zero-dependency package — installs fast from any repo, no
    compilation) → `installed:true`; then an `/execute` run of
    `library(praise); cat(is.character(praise()))` → `"TRUE"`, proving the full
    install→persist→use path. Runs in both the local Docker suite and CI (both
    have network). This test points `R_CRAN_REPO` at a source CRAN mirror
    (`https://cloud.r-project.org`) so it's independent of the runner's distro
    (pure-R packages install identically from source).
- **App**: `PackagesViewModel` unit tests — list load, install success (updates
  state + refreshes list), install failure (surfaces error) — with a fake API.
- **Docs**: `backend/README.md` (new endpoints, shared library, the
  system-deps/source-package limitation) and `CLAUDE.md` (endpoints, the
  `.libPaths` prelude line, the new screen/route).

## Out of scope (MVP)

- Uninstalling packages (no `remove.packages` endpoint/UI).
- Per-session libraries; disk quota / eviction for the shared lib.
- **Runtime** system-dependency installation. Common system libraries are
  pre-baked into the image (above), so most popular packages work; a package
  needing a system lib that wasn't pre-baked still fails, but `/install` returns
  the apt command to add it (rebuild the image). No runtime apt (keeps the
  container hardened).
- Version pinning / specific-version installs.

## Security notes (extend `backend/README.md`)

- `/install` runs arbitrary package install-time code — the same RCE surface as
  `/execute`, plus it writes to a **persistent, globally shared** library, so a
  malicious or trojaned package persists for all sessions until the lib is
  cleared. The shared lib is unbounded (no quota yet).
- `/install` is auth- and rate-limited like `/execute`. The package name is
  sanitized to a single `^[A-Za-z0-9._]+$` token (no shell/path injection into
  the install call).
