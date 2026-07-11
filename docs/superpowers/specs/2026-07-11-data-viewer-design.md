# Data Viewer (`View(df)` + file preview) — Design

**Date:** 2026-07-11
**Status:** Approved (design), pending spec review

## Goal

Let a user look at tabular data as a table *without writing code* — both an
uploaded data file (e.g. `sales.csv`) and an in-session data-frame variable
(a true `View(df)`) — rendered full-screen with the existing `RTableView`.

## Motivation

The app can already upload data files (Session Data Import) and render data
frames that a run *prints* (Richer Output). What's missing is the connective
tissue: to look at an uploaded file you must write `read.csv("x.csv")` and run
it; to look at a variable you must `print` it in a run. This feature closes
both gaps with a dedicated, read-only preview.

## Scope decisions (from brainstorming)

- **Targets:** both **uploaded files** and **in-session workspace data frames**.
- **File formats:** `.csv`/`.tsv` (delimited, base R), `.rds` (base R), **plus**
  `.xlsx` (via `readxl`) and `.parquet` (via `arrow`) when those packages are
  installed in the project session's library; a friendly "install X" message
  otherwise.
- **Surface:** a single **full-screen** preview screen for both targets (best
  real estate for wide/tall tables; reuses `RTableView` scroll/resize/sort).
- **Read-only, hard rule:** a preview MUST NOT mutate session state (no
  `save.image()`, no run-history entry, no workspace-object changes).

## Non-goals (YAGNI)

- Pagination or infinite scroll — reuse the existing head-`R_TABLE_MAX_ROWS`
  (200) cap and report the true total, exactly like printed-table capture.
- Cell editing, CSV dialect UI (separator auto-detected), charting/plots,
  multi-sheet Excel selection (first sheet only), and previewing non-tabular
  objects (lists, models) — those show a friendly "can't preview" message.

## Architecture

One new backend endpoint and one new app screen, both thin because the table
format and renderer already exist.

```
Data screen ─tap file──┐                     ┌─► reads file by extension
                       ├─► PreviewViewModel ──┤     (session .libPaths)
Editor workspace chip ─┘   POST /preview      └─► get() object from
   (tap variable)          {source,name}            loaded workspace.RData
                                │                         │
                                ▼                         ▼
                         PreviewScreen  ◄── RTable ── shared emit helper
                          (RTableView)      (table001.json, same format
                                             /execute already produces)
```

### Backend — `POST /preview`

Protected by the existing `auth` + `ratelimit` filters (added to
`is_protected()` in `plumber.R`).

**Request** (`PreviewRequest`):

```json
{ "sessionId": "proj-abc", "source": "file", "name": "sales.csv" }
```

- `source`: `"file"` or `"object"` (any other value → 400).
- `name`:
  - `source="file"`: validated with the existing `sanitize_data_name`
    (basename → `[A-Za-z0-9._-]` → reject empty/reserved). Reject → 400.
  - `source="object"`: must match `^[A-Za-z.][A-Za-z0-9._]*$` (R symbol).
    Reject → 400.
- `sessionId`: sanitized with `sanitize_session_id` (default `"default"`).

**Handler** runs an isolated `Rscript --vanilla` in an ephemeral run dir with
the session `rlib` prepended to `.libPaths()` (same construction as
`/execute`), building a small harness that:

- `source="file"`: symlinks the session data file into the run dir, then reads
  it by lowercased extension:
  - `.csv` → `utils::read.csv`, `.tsv`/`.tab` → `utils::read.delim`
    (tab-separated) — both base R, no optional package,
  - `.rds` → `readRDS`,
  - `.xlsx`/`.xls` → `readxl::read_excel` (first sheet),
  - `.parquet` → `arrow::read_parquet`.
  - Unknown extension → error `"Can't preview this file type."`.
  - A read that needs a missing package → error
    `"Install '<pkg>' in this project to preview .<ext> files."` (detected by
    `requireNamespace` before the read).
- `source="object"`: `load()`s the session `workspace.RData` into a fresh
  environment; `get(name)` there. Not present → error
  `"No object named '<name>' in this project's workspace."`.
- Tabular check (reuse `/execute`'s `.tabular`: data frame, or 2-D
  matrix/`table`). Non-tabular → error `"'<name>' is not a table."`.
- On success: coerce to data frame and call the **shared emit helper**
  (factored out of the `/execute` wrapper into a reusable R string constant so
  both sites emit byte-identical JSON) → writes one `table001.json` capped at
  `R_TABLE_MAX_ROWS` with the true `totalRows`.
- The harness **never** calls `save.image()` and the handler writes no
  history/objects files.

**Response** (`PreviewResponse`):

```json
{ "table": { "columns": [...], "columnTypes": [...], "rows": [...], "totalRows": 999 },
  "error": null, "truncated": true }
```

- `table`: an `RTable` (same shape as `/execute` `tables[]`), or `null` on error.
- `error`: user-facing message, or `null` on success.
- `truncated`: `totalRows > R_TABLE_MAX_ROWS`.
- Read the emitted `table001.json` back with `simplifyVector = FALSE` (so the
  unboxed serializer can't collapse a 1×1 table), matching `/execute`.

### App — models

`data/model/PreviewModels.kt` (kotlinx.serialization):

```kotlin
@Serializable
data class PreviewRequest(
    val source: String,           // "file" | "object"
    val name: String,
    val sessionId: String? = null,
)

@Serializable
data class PreviewResponse(
    val table: RTable? = null,    // reuse RTable from ExecuteModels
    val error: String? = null,
    val truncated: Boolean = false,
)
```

`RExecutionApi.preview(@Body req: PreviewRequest): PreviewResponse` (`@POST("preview")`),
and `RExecutionRepository.preview(source, name, sessionId): Result<PreviewResponse>`.

### App — preview screen

- `ui/preview/PreviewUiState`:
  `data class PreviewUiState(title = "", table: RTable? = null, isLoading = false, error: String? = null, truncated = false, totalRows = 0)`.
- `ui/preview/PreviewViewModel` (session-scoped exactly like `DataViewModel` —
  resolves the active project's session via `ProjectStore` in `init`), with
  `load(source: String, name: String)` that sets `title = name`, flips
  `isLoading`, calls the repo, and maps success→`table`/error→`error`.
- `ui/preview/PreviewScreen`: a `Scaffold` with a top app bar (title = `name`,
  subtitle "showing 200 of N rows" when `truncated`) and a body that shows a
  spinner while loading, `RTableView(table)` on success, or the error text.

### App — navigation & entry points

- Add `PREVIEW` to the `Screen` enum in `MainActivity`.
- `AppRoot` holds `var previewTarget by remember { mutableStateOf<Pair<String,String>?>(null) }`
  (source, name). Navigating to `PREVIEW` constructs `PreviewScreen`, which
  `LaunchedEffect(previewTarget)`-calls `viewModel.load(source, name)`.
- **Data screen:** each file row gets a dedicated **"View" button** (sibling to
  the existing "Insert" button, leaving the row's Delete affordance untouched)
  → `onPreviewFile(name)` sets `previewTarget = "file" to name` and switches to
  `PREVIEW`. Wired in `MainActivity` alongside the existing `onInsertFileName`.
- **Editor workspace line:** the current single text line
  (`Workspace: a, b, df (3)`) becomes a small **row of tappable chips**, one per
  object name. Tapping a chip → `onPreviewObject(name)` → `previewTarget =
  "object" to name`, switch to `PREVIEW`. The count stays as a label. This is
  the only editor UI change.

## Data flow

1. User taps a file (Data screen) or a workspace chip (editor).
2. `AppRoot` records `previewTarget` and switches to `Screen.PREVIEW`.
3. `PreviewViewModel.load(source, name)` posts `/preview` with the active
   session id.
4. Backend reads the file / object read-only, emits `table001.json`, returns
   `PreviewResponse`.
5. `PreviewScreen` renders it with `RTableView`; back returns to the origin.

## Error handling

- Backend validation failures → HTTP 400 with a message; the app surfaces the
  message as `error`.
- In-band errors (missing package, non-tabular, unknown type, object not
  found) → 200 with `table = null`, `error = "<message>"`; shown as body text.
- Network/timeout → repository `Result.failure`; `PreviewViewModel` shows
  `t.message`.
- Large tables → capped at 200 rows with the "showing 200 of N rows" subtitle.

## Testing

**Backend (testthat + httr2, CI-gated):**
- `/preview` `source="file"` on a seeded `.csv` → table with expected
  columns/rows.
- `/preview` `source="file"` on a seeded `.rds` data frame → table.
- `/preview` `source="object"` after a run that creates `df` → table.
- Non-tabular object (e.g. a numeric vector) → `error` set, `table` null.
- Unknown/missing file → `error` set.
- Missing-package extension (e.g. `.xlsx` with no `readxl`) → install message.
- **State-unchanged assertion:** run something that sets a variable, preview
  it, then confirm `workspaceObjects` and installed packages are unchanged
  (proves read-only).
- 400 on bad `source` / bad `name`.

**App (JVM unit tests):**
- `PreviewViewModel`: loading→table on success, loading→error on failure,
  title set to the target name, `truncated`/subtitle mapping — using a fake
  repository injected via the default-arg seam.

## Contract sync note

`PreviewRequest`/`PreviewResponse` must stay field-for-field in sync with
`plumber.R`'s `/preview` JSON, and the backend must emit **unboxed** JSON
(via `run.R`) as with every other endpoint. `/preview` joins the
auth/rate-limit-protected set in `is_protected()`. Reuses the `RTable` model
from `ExecuteModels.kt` (no new table type).
