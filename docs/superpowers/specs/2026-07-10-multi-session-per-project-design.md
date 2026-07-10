# Multi-session (per-project R environments) — design

**Date:** 2026-07-10
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/`

## Problem

The app talks to the backend as a single hardcoded session (`DEFAULT_SESSION_ID`
everywhere), and the installed-package library is one shared directory
(`R_PKG_LIB`, `/data/rlib`) prepended to `.libPaths()` for every run. So every
project shares one workspace and one package set: installing a package for one
piece of work silently affects all others, and a trojaned package persists for
everyone. The app already has a **projects** concept (named `.R` collections)
but they all execute in that one shared environment.

## Goal

Give every **project** its own isolated R environment — its own workspace
*and* its own installed-package library — so opening a project switches the
backend session automatically. No new UI axis: the existing project switcher
becomes the session switcher.

## Decisions (from brainstorming)

- **Direction:** full multi-session in the app (not backend-only hardening).
- **Session model:** **each project = its own session.** A project's stable
  `id: Long` derives the backend `sessionId = "proj-<id>"`. Opening a project
  switches the active session. No separate "environments" switcher.
- **Package library:** **per-project only (full isolation).** Runtime
  `/install` lands in that project's own library; a package installed in
  project A is *not* visible to project B. Baked/trusted image packages stay in
  R's site library and remain available to every session read-only.
- **Legacy migration:** **Option 3 — explicit import.** The old shared
  `/data/rlib` is kept mounted **read-only, never written to**, and becomes a
  "legacy source." A `POST /import-legacy` endpoint copies its packages into a
  named session's library on request; the app surfaces an "Import packages from
  legacy library" action (only when the legacy lib is non-empty). Auto-migration
  (Option 1) doesn't fit the backend's non-interactive subprocess model, and a
  read-only shared fallback path (Option 2) would reintroduce the cross-session
  leakage that isolation exists to prevent — both rejected.

## Session identity

`sessionId = "proj-<project.id>"`. `project.id` is a positive `Long` (from
`System.currentTimeMillis()`), so the id already satisfies the backend's
`sanitize_session_id` character class (`[A-Za-z0-9_-]`) and is **stable across
renames** (rename changes `name`, never `id`). A new pure helper
`ProjectSession.of(project): String` (unit-tested) is the single source of this
mapping in the app.

## Backend (`plumber.R`)

### Per-session library

- `session_paths(session_id)` gains `rlib = file.path(dir, "rlib")` alongside
  `workspace` and `attached`.
- The run wrapper prepends **that session's** rlib to `.libPaths()` instead of
  the global `PKG_LIB`:
  `.libPaths(c("<session rlib>", .libPaths()))`. Baked image packages live in
  R's site library (still on `.libPaths()`), so they stay available read-only.
- `R_PKG_LIB` (default `/data/rlib`) is **no longer an install target.** It is
  retained only as `LEGACY_PKG_LIB`, the read source for `/import-legacy`.

### Endpoints

- `POST /install` — request gains `sessionId` (sanitized, default `"default"`).
  Installs into `session_paths(sessionId)$rlib` (created if missing). Timeout,
  name validation (`^[A-Za-z0-9._]+$`), and response shape
  (`stdout`/`stderr`/`error`/`timedOut`/`installed`/`systemRequirements`)
  unchanged.
- `POST /uninstall` — request gains `sessionId`. Removes from that session's
  rlib. `removed`/`error` unchanged. Base and image packages untouched (only the
  session rlib is a `remove.packages(lib=)` target).
- `GET /packages` — gains a `?sessionId=` query param (default `"default"`).
  Lists that session's rlib. Still open (no auth) and read-only.
- `POST /reset` — unchanged default meaning (clear `workspace.RData` +
  `attached.txt`, **keep** installed packages). Gains optional
  `purgePackages: false`; when `true`, also removes the session's rlib — i.e.
  wipes the whole session dir. Used by project deletion. Response `{ok: true}`
  unchanged.
- `POST /import-legacy` — request `{sessionId}`. Copies each package directory
  from `LEGACY_PKG_LIB` into `session_paths(sessionId)$rlib`, **skipping any
  already present** in the session rlib (idempotent). Ignores base packages.
  Protected (auth + rate limit) like the other write endpoints. Response
  `{imported: <int count>, packages: [<names copied>]}`. If the legacy lib is
  absent/empty, returns `{imported: 0, packages: []}`.

`is_protected()` adds `/import-legacy` (so the set is `/execute`, `/reset`,
`/install`, `/uninstall`, `/import-legacy`).

## App

### Repository / API

`RExecutionRepository` and `RExecutionApi` thread `sessionId`:

- `run(files, entryFile, sessionId)` — already has a `sessionId` param default;
  callers now pass the project's session.
- `reset(sessionId, purgePackages = false)`.
- `install(packageName, sessionId)`, `uninstall(packageName, sessionId)`.
- `packages(sessionId)` — Retrofit `@Query("sessionId")`.
- `importLegacy(sessionId)` — new `@POST("import-legacy")`.

Model changes (`data/model/`): `InstallRequest`/`UninstallRequest` gain
`sessionId`; new `ImportLegacyRequest(sessionId)` and
`ImportLegacyResponse(imported: Int, packages: List<String>)`.

### EditorViewModel

- `runCode()` and `resetSession()` pass `ProjectSession.of(activeProject)`.
- `deleteProject(id)` fires a **best-effort, non-blocking** backend purge —
  `repository.reset(ProjectSession.of(deletedProject), purgePackages = true)` in
  `viewModelScope`, failure ignored (offline-safe) — before/alongside the
  existing local delete. Local state update is unchanged and does not depend on
  the network call succeeding.

### PackagesViewModel / screen

- `PackagesViewModel` resolves the **active project** at construction via
  `projectStore` (`loadLastOpenProjectId()` + `loadProjects()`), computes its
  `sessionId`, and threads it into `packages()` / `install()` / `uninstall()` /
  `importLegacy()`.
- The Packages screen header shows `Packages · <active project name>` so it is
  unambiguous which project's library is being managed.
- An **"Import packages from legacy library"** action calls
  `importLegacy(sessionId)` and, on success, shows `Imported N package(s).` and
  refreshes the list. (Shown always; a `0`-import result is a harmless no-op —
  keeps the UI logic simple, no extra "is legacy non-empty" probe endpoint.)

## Migration behavior (net effect)

On upgrade, existing installed packages in the old shared `/data/rlib` are not
auto-applied to any project; each project starts with an empty library (baked
packages still present). A user who wants their old packages taps **Import
packages from legacy library** inside the project that should have them. The old
`"default"` session workspace is likewise not carried into any `proj-<id>`
session — workspace state is transient and this is a one-time changeover.

## Testing

### Backend (`backend/tests/`, testthat + httr2)

- **Isolation:** `/install` `praise` into session `A`; `/packages?sessionId=A`
  contains it, `/packages?sessionId=B` does not; an `/execute` in `A` can
  `library(praise)`, the same in `B` errors.
- **Purge:** after an install in a session, `/reset` with
  `purgePackages = true` removes the package (subsequent `/packages` empty);
  `/reset` with the default `false` leaves it.
- **Import-legacy:** seed a package into `LEGACY_PKG_LIB`; `/import-legacy`
  `{sessionId}` reports `imported >= 1` and the package then appears in
  `/packages?sessionId=<id>`; a second call reports `imported: 0` (idempotent);
  empty/absent legacy lib → `imported: 0`.
- **Default session unaffected:** an install with no `sessionId` still works
  against the `"default"` session's rlib.

### App (JVM)

- `ProjectSessionTest`: `of()` returns `"proj-<id>"`; stable across a
  name change; distinct ids → distinct sessions.
- `RExecutionRepository` passes `sessionId` (and `purgePackages`) through each
  call (fake API asserts the received values).
- `EditorViewModel.deleteProject` invokes `reset(purgePackages=true)` with the
  deleted project's session (fake asserts), and local deletion still succeeds
  when that call fails.
- `PackagesViewModel` uses the active project's session for
  `packages`/`install`/`uninstall`/`importLegacy` (fake asserts), and
  `importLegacy` success surfaces the "Imported N" message + refresh.

### Docs

- `backend/README.md`: per-session libraries, the `sessionId` params on
  `/install` / `/uninstall` / `/packages`, `purgePackages` on `/reset`, the new
  `/import-legacy`, and the legacy-import migration note.
- `CLAUDE.md`: contract updates (session-per-project mapping, per-session rlib,
  new/changed request fields, `/import-legacy`), and the response-contract
  section for the new endpoint.
- Root `README.md`: a "per-project environments" feature line.

## Out of scope

- A session/environment axis independent of projects (explicitly unified away).
- Auto-migrating old workspace or shared packages without user action.
- Per-session disk quotas or eviction (the existing "session state is unbounded"
  caveat still stands and is unchanged by this feature).
- A shared read-only fallback library across projects (Option 2 — rejected;
  would defeat isolation).
- Sharing/copying a library *between* projects (only legacy → project import is
  in scope).
