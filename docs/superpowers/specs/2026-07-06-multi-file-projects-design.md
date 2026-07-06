# Multi-file projects — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plans
**Feature area:** `backend/` + `app/`

## Problem

The app edits a single code buffer and `/execute` runs one `code` string. Real R
work spans multiple files that `source()` each other. There's no way to keep,
organize, or run a project of files.

## Goal

**Multiple named projects**, each a set of `.R` files that can `source()` each
other. The editor becomes project-based (edits the active file of the open
project, with a file switcher); **Run** executes the active file with all
project files present so `source("helpers.R")` works.

## Decisions (from brainstorming)

- **Multiple named projects** (a library), not a single working set.
- **Editor becomes project-based** (not a separate screen); named "saved
  scripts" remain a separate single-snippet feature.
- **Run executes the project's pinned entry file** (each project designates one),
  with all files written alongside for `source()`. You still edit/switch any
  file freely; the entry is a per-project setting, not the focused file.

## Data model & persistence

- `ProjectFile(name: String, content: String)` — one `.R` file.
- `Project(id: Long, name: String, files: List<ProjectFile>, activeFileName: String, entryFileName: String, updatedAt: Long)`.
  Invariants: `files` non-empty; `activeFileName` and `entryFileName` are each one
  of `files`; file names unique and match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (must
  start with an alphanumeric — this also excludes `.`/`..`; ending in `.R`
  recommended, not enforced). `activeFileName` is which file the editor shows;
  `entryFileName` is which file Run executes. A new project starts with a single
  `main.R` (both active and entry) seeded with the sample snippet.
- Pure, unit-tested `ProjectOps` (no Android/persistence): `addFile`,
  `renameFile` (updates `activeFileName`/`entryFileName` if they referenced the
  renamed file), `deleteFile` (rejects deleting the last file; reassigns
  `activeFileName`/`entryFileName` to a remaining file if they pointed at the
  deleted one), `setActive`, `setEntry`, `updateActiveContent`, and project-list
  `upsert`/`delete` (sorted by `updatedAt` desc). All return new immutable values.
- `ProjectStore` (interface; implemented by `SettingsStore` as JSON in
  SharedPreferences, like saved scripts): persists the project list **and** the
  last-open project id, so the app reopens the last project.

## Backend `/execute` contract (backward-compatible)

- Request (`ExecuteRequest`) gains optional `files: List<ExecFile>` and
  `entryFile: String`, where `ExecFile(name, content)`. `code` and `sessionId`
  stay.
- When `files` is present:
  - Validate: `files` non-empty; every `name` matches
    `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no `/` blocks path traversal; leading
    alphanumeric excludes `.`/`..`); `entryFile` present and among the file
    names. Otherwise 400.
  - Write each file verbatim into the per-run temp dir.
  - Build the session wrapper `script.R` as today (prelude: `.libPaths`, restore
    workspace, replay packages, open png device) but with the body being
    `source("<entryFile>", echo = FALSE, print.eval = TRUE)` instead of inlined
    code, then the epilogue (dev.off, `save.image`, write `objects.txt`).
    `print.eval = TRUE` reproduces Rscript's auto-printing of visible top-level
    results; `source()` of sibling files resolves because they share the run
    dir (which is the working dir). Run `script.R` with `Rscript --vanilla` and
    the same timeout.
- When `files` is absent, behavior is exactly as today (`code` inlined).
- Response is unchanged (`stdout`, `stderr`, `plots`, `error`, `timedOut`,
  `workspaceObjects`). Durable session, package library, auth, and rate limiting
  all apply unchanged.

## App (project-based editor)

- `EditorViewModel` becomes project-aware: its state carries the current
  `Project` (files + `activeFileName` + `entryFileName`) alongside the existing
  run/output/history/workspace fields. The editor's text is the **active file's**
  content; typing calls `onActiveContentChanged` that updates that file via
  `ProjectOps`. Run sends `files` + `entryFile = project.entryFileName` (with the
  current, possibly unsaved, content of every file) — i.e. Run executes the
  pinned entry regardless of which file is focused. Project changes persist
  through `ProjectStore`.
- **File switcher**: a horizontal scrollable row of file-name chips at the top of
  the editor (tap to switch; active highlighted, the entry file marked with a
  badge such as ▶) with a `+` to add a file (name dialog); a per-file menu offers
  "Set as entry", rename, and delete (delete disabled when it's the last file).
  The Run button labels the entry (e.g. "Run main.R") so it's clear what runs.
- **Project library**: reached from the editor overflow menu ("Projects") → a
  screen (`ProjectsScreen` + `ProjectsViewModel`) listing projects (name +
  last-updated) with open / new / rename / delete. Opening switches the editor
  and records the last-open project.
- Unchanged and still present: R syntax highlighting, quick-insert bar, output
  panel (with copy/share), run history, workspace summary, session reset,
  Packages, Settings.
- **Migration**: on first launch after the update, if no projects exist, create a
  default project "Untitled" with a seeded `main.R`. The old editor buffer was
  never persisted, so nothing else migrates. Saved scripts and run history are
  untouched.

## Testing

- **Backend** (`backend/tests/test-project.R`, testthat + httr2):
  - Multi-file run: `helpers.R` defines `f <- function(x) x * 2`; entry `main.R`
    does `source("helpers.R"); cat(f(21))` → stdout `"42"`.
  - `entryFile` not among `files` → 400; an invalid file name (e.g. `../evil.R`)
    → 400.
  - The legacy `{code}` path still works (existing tests unchanged).
  - Session persistence works with `files` (a var set in one run is visible in
    the next).
- **App** (JVM unit tests):
  - `ProjectOps` pure tests (add/rename/delete/set-active/set-entry/update-
    content, last-file-delete guard, delete/rename reassigns active+entry, list
    upsert/delete/sort).
  - `ProjectStore` round-trip via an in-memory fake / serialization.
  - `EditorViewModel` project tests: switching files changes the edited content;
    editing updates the active file; Run sends `files` + `entryFile =
    entryFileName` (proven by running while a *non-entry* file is active).
    Existing `EditorViewModel` tests are refactored to the project model.
- **Docs**: `backend/README.md` (the `files`/`entryFile` contract), `CLAUDE.md`
  (project model, editor refactor, the new contract fields and screens).

## Scope — two implementation plans

Executed as two sequenced, independently CI-green PRs:

1. **Plan A — backend**: the `/execute` `files`/`entryFile` contract + validation
   + wrapper `source()` change + `backend/tests/test-project.R` + docs. Fully
   testable on its own (the app keeps sending `code` until Plan B).
2. **Plan B — app**: `Project`/`ProjectFile` models, `ProjectOps`, `ProjectStore`,
   the `EditorViewModel` refactor, the file switcher, the project-library screen,
   sending `files`/`entryFile`, and tests.

## Out of scope (MVP)

- Folders/subdirectories within a project (flat file list only).
- Importing/exporting projects or files to the device filesystem.
- Non-`.R` files (data files, etc.).
- Converting saved scripts into project files (possible future nicety).
- Renaming that rewrites `source("old.R")` references (rename is name-only).
