# Project import/export — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plan
**Feature area:** `app/`

## Problem

Multi-file projects live only inside the app (SharedPreferences). There's no way
to back one up, move it to another device, or use its `.R` files elsewhere.

## Goal

Export a project to a `.zip` of its `.R` files (shareable anywhere) and import a
`.zip` back into the project library.

## Decisions (from brainstorming)

- **Zip of `.R` files** (not a single JSON blob) — interoperable with desktop
  R/RStudio; `java.util.zip` is built in (no new dependency).
- **Export via the Android share-sheet** (reusing the existing `FileProvider`);
  **import via the Storage Access Framework** file picker.

## Archive format & pure `ProjectArchive`

A project `.zip` contains:
- one entry per file, named exactly as the file (`main.R`, `helpers.R`, …), whose
  bytes are the file content (UTF-8);
- a metadata manifest `.rmobile-project.json` (leading `.` so it can't collide
  with a user file, which must start alphanumeric) holding
  `{ name, entryFileName, activeFileName }`.

`data/project/ProjectArchive` — pure, no Android, unit-tested:
- `export(project: Project): ByteArray` — a `ZipOutputStream` writing each
  `ProjectFile` entry + the manifest.
- `import(bytes: ByteArray, id: Long, now: Long, fallbackName: String): Project?`
  — a `ZipInputStream` that rebuilds a `Project`:
  - every non-manifest entry → `ProjectFile(entryName, contentAsUtf8)`;
  - the manifest (if present + parseable) supplies `name`/`entryFileName`/
    `activeFileName`;
  - **fallback** (no/invalid manifest — e.g. a plain zip of `.R` from elsewhere):
    `name = fallbackName`, `entryFileName` = `main.R` if present else the first
    file, `activeFileName = entryFileName`;
  - validation: every file name must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (this
    also blocks zip-slip path names like `../x`); if any entry name is invalid,
    or there are no file entries, or the bytes aren't a readable zip → return
    `null`;
  - if the manifest's `entryFileName`/`activeFileName` aren't among the files,
    fall back to the same defaults.
  - `id`/`now` are passed in (pure code doesn't generate ids/time); the caller
    passes `now()` for both.

## App wiring

- **Export** (`ui/projects`): a per-project export action → `shareProjectZip(context, project)`
  (mirrors `sharePlotPng`): writes `ProjectArchive.export(project)` to
  `File(cacheDir, "shared")/<sanitized-name>.zip`, gets a `FileProvider` URI
  (`${applicationId}.fileprovider`, the `shared/` cache-path already declared),
  and launches `Intent.ACTION_SEND` with `type = "application/zip"` +
  `FLAG_GRANT_READ_URI_PERMISSION` via a chooser. Best-effort (toast on failure).
- **Import** (`ProjectsScreen`): a top-bar import action →
  `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument)`
  filtered to `arrayOf("application/zip", "application/octet-stream")`. On a
  picked `Uri`: read `context.contentResolver.openInputStream(uri)!!.readBytes()`,
  derive a fallback name from the display name (`OpenableColumns.DISPLAY_NAME`,
  minus `.zip`; else "Imported project"), and call
  `viewModel.importProject(bytes, fallbackName)`.
- **`EditorViewModel.importProject(bytes: ByteArray, fallbackName: String)`**:
  `ProjectArchive.import(bytes, now(), now(), fallbackName)`; if non-null → upsert
  into `projects`, persist, set it current (persist last-open), update state
  (`projects` + `project` + `code`); if null → set `errorMessage`
  ("Couldn't import — not a valid project zip."). Imported projects get a **new
  id**, so import never overwrites an existing project.

## Edge cases

- Not a zip / corrupt / oversized → `import` catches and returns null → friendly
  error.
- Zip-slip / bad names → rejected by the file-name regex.
- Empty selection / no file entries → null → error.
- No `FileProvider`/manifest changes (reuses the plot-sharing setup).

## Testing

- **Pure `ProjectArchiveTest`** (JVM): export→import round-trip preserves files +
  `entryFileName` + `activeFileName` + `name`; a manifest-less zip uses the
  fallback (entry = `main.R`/first); an invalid file name → null; empty/garbage
  bytes → null.
- **`EditorViewModel.importProject` tests** (JVM): valid zip bytes (from
  `ProjectArchive.export`) → project added + opened; garbage bytes →
  `errorMessage` set and the library unchanged.
- The share/SAF plumbing (`shareProjectZip`, the launcher) is compile-verified +
  manual on device.
- **Docs**: README features + `CLAUDE.md`.

## Out of scope (MVP)

- Bulk export/import (all projects at once).
- Importing/merging into an *existing* project (import always creates a new one).
- Non-`.R` files in a project (unchanged — a project is a flat set of `.R` files).
- Cloud sync.
