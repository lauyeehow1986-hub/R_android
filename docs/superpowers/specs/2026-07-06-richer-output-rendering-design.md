# Richer output rendering — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/`

## Problem

Output is text + plot images only. Data frames appear as pre-formatted monospace
text in `stdout`, and plots can't be zoomed. Tabular results deserve real tables,
and plots deserve a full-screen view.

## Goal

1. **Data-frame tables** — data frames (and tibbles, data.tables, matrices,
   `table` objects) printed at a script's top level render as interactive,
   scrollable tables with sort / filter / column-resize / tap-to-expand.
2. **Plot zoom** — tap a plot for a full-screen, pinch-zoom/pan view.

## Decisions (from brainstorming)

- **Scope:** both parts.
- **Capture:** auto-capture *printed* values, **top-level only** — via an
  `inherits(x, "data.frame")` (and 2-D matrix/`table`) check in a small
  evaluation loop that replaces bare `source()`. Chosen over per-class S3
  print-hooks, which miss un-enumerated subclasses and get clobbered when user
  code runs `library(tibble)`/`library(data.table)` mid-run. Trade-off: data
  frames printed *inside* a function or a `source()`d helper are not captured.
- **All the "nice to have" table features are in:** cell type styling,
  sort, filter, column resize, list-column/cell expansion, and
  `summary()`/`table()`/matrix rendering. `str()` stays textual (its output is a
  tree, not tabular).

## Response contract

`ExecuteResponse` gains `tables: List<RTable> = emptyList()` (unboxed JSON,
emitted as `[]` when none):

```
RTable(
  columns:     List<String>,        // header labels
  columnTypes: List<String>,        // parallel to columns: "numeric","integer",
                                    // "character","factor","logical","complex", etc.
  rows:        List<List<String>>,  // up to R_TABLE_MAX_ROWS rows of formatted cells
  totalRows:   Int,                 // true row count (rows may be truncated)
)
```

Kotlin model `RTable` in `data/model/ExecuteModels.kt` mirrors this. All existing
fields are unchanged; `tables` is additive and backward-compatible.

## Backend — top-level capture

The `/execute` wrapper stops running the entry via bare
`source(entry, echo = FALSE, print.eval = TRUE)`. Instead:

- For the single-`code` path, the code is written to `main.R` in the run dir
  (multi-file already writes its files); an `entry` path is always available.
- The wrapper runs the entry through a `local({...})` evaluator so its helpers
  never touch `globalenv` (keeps `save.image()`/`workspaceObjects` clean):

```r
local({
  MAXROWS <- <R_TABLE_MAX_ROWS>          # default 200
  emit <- function(x) {
    # as.data.frame.matrix keeps a 2-D matrix/`table`'s grid layout; plain
    # as.data.frame() would melt a `table` to long Var1/Var2/Freq form.
    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)
    n  <- nrow(df)
    sub <- utils::head(df, MAXROWS)
    types <- vapply(df, function(c) class(c)[1], character(1))
    # format each column to its full display text; list-columns -> joined element text
    cells <- lapply(sub, function(col)
      if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1))
      else format(col, trim = TRUE))
    cols  <- names(df)
    # non-default row names become a leading column
    rn <- rownames(sub)
    if (!identical(rn, as.character(seq_len(nrow(sub))))) {
      cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types)
    }
    rowsOut <- lapply(seq_len(nrow(sub)), function(i)
      vapply(cells, function(cc) as.character(cc[i]), character(1)))
    obj <- list(columns = I(as.character(cols)), columnTypes = I(as.character(types)),
                rows = lapply(rowsOut, I), totalRows = jsonlite::unbox(as.integer(n)))
    idx <- length(list.files(".", pattern = "^table[0-9]+\\.json$")) + 1L
    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))
  }
  tabular <- function(v)
    is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)
  exec <- function(exprs) for (e in exprs) {
    r <- withVisible(eval(e, globalenv()))
    if (r$visible) {
      if (tabular(r$value)) try(emit(r$value), silent = TRUE)
      print(r$value)                       # native printing — tibble prints as a tibble
    }
  }
  exec(parse(file = "<entry>"))
})
```

Notes:
- `withVisible` + `print` reproduces top-level auto-printing (invisible values —
  e.g. assignments, `source()` — are not printed and not captured).
- `emit` writes JSON with `auto_unbox = FALSE` + `unbox(totalRows)`; the handler
  reads each file with `jsonlite::fromJSON(path, simplifyVector = FALSE)` so
  plumber's global unboxed serializer can't collapse a 1×1 table into scalars.
- Handler collects tables after plots:
  `table_files <- sort(list.files(run_dir, pattern = "^table[0-9]+\\.json$", full.names = TRUE))`,
  `tables <- lapply(table_files, jsonlite::fromJSON, simplifyVector = FALSE)`, and
  adds `tables = tables` to the success response. Early error/timeout returns
  omit it (Kotlin defaults to `[]`).
- `R_TABLE_MAX_ROWS` is read once near the other `Sys.getenv` config.
- A run that errors aborts before `emit`'s siblings and before `save.image()`,
  exactly as today — a failed run captures no partial state.

## App — table rendering

- **State:** `EditorUiState.tables: List<RTable> = emptyList()`; `EditorViewModel`
  sets it from `response.tables` on success and clears it on error/timeout
  (parallel to `plotsBase64`).
- **`RTableView`** (`ui/editor/RTableView.kt`) renders one `RTable`:
  - A **filter** `OutlinedTextField`; a horizontally-scrollable grid with a
    sticky-styled header row and up to `rows.size` data rows.
  - **Header tap** cycles sort on that column (asc → desc), shown with ▲/▼.
  - **Type-aware sort:** numeric/integer/double/complex columns compared as
    numbers (`toDoubleOrNull`), else string compare; `"NA"`/blank sort last.
  - **Cell styling:** numeric columns right-aligned, others left-aligned;
    monospace throughout; a subtle type tint on the header.
  - **Column resize:** each header cell has a draggable right-edge handle
    (`pointerInput`/`detectDragGestures`) mutating a per-column
    `SnapshotStateList<Dp>` of widths (seeded from content on first layout,
    min width clamp).
  - **Cell tap → dialog** showing the full untruncated cell value (selectable),
    covering list-columns and long cells.
  - **Footer:** "Showing N of M rows" only when `totalRows > rows.size`.
- **`RTableViewOps`** (pure, `ui/editor/RTableViewOps.kt`, unit-tested) holds the
  meaty logic so it's testable without Compose:
  `fun display(rows: List<List<String>>, columnTypes: List<String>, filter: String, sortColumn: Int?, ascending: Boolean): List<List<String>>`
  — filter (case-insensitive substring across cells) then type-aware stable sort.
- **`OutputPanel`** renders each `uiState.tables` entry via `RTableView` after
  stdout/stderr and the plots. Empty `tables` → nothing (backward-compatible).

## App — plot zoom

- Each plot `Image` becomes `clickable`, opening a full-screen `Dialog`
  (`usePlatformDefaultWidth = false`): the PNG in a `Box` with pinch-zoom + pan
  via `rememberTransformableState` (scale clamped 1×–5×, translation bounded), a
  close button, tap-scrim-to-dismiss. The existing inline image + share button
  stay. Decoding reuses `decodeBase64Png`.

## Testing

- **Backend** (`backend/tests/test-tables.R`, testthat + httr2):
  - `print(head(iris))` → one table, correct `columns`, `columnTypes`
    (`Sepal.Length` numeric, `Species` factor), first-row values, `totalRows == 6`.
  - `data.frame(x = 1)` (1×1) round-trips as `columns:["x"]`, `rows:[["1"]]`
    (the unboxing guard).
  - A `> R_TABLE_MAX_ROWS` frame (run with a small `R_TABLE_MAX_ROWS`) →
    `rows` length == cap, `totalRows` the true count.
  - `print(table(c("a","a","b")))` and `summary(cars)` → a table (matrix/`table`
    path).
  - A run with no printed tabular value → `tables` empty; `stdout`/`plots`
    unchanged. Assignment (`x <- 1`) prints/captures nothing.
  - A tibble via `as_tibble(iris)` **only if tibble is installed** (else
    `skip`) → captured, and `stdout` still shows tibble formatting.
- **App** (JVM):
  - `RTableViewOpsTest`: numeric column sorts numerically (not lexically),
    string column sorts lexically, descending reverses, filter narrows rows
    case-insensitively, `"NA"` sorts last.
  - `EditorViewModel` maps `response.tables` into `uiState.tables` on success and
    clears it on failure (FakeApi returns a table; extend the existing fakes).
  - `RTableView` and the zoom dialog are compile-verified + manual on device.
- **Docs:** `backend/README.md` (auto-captured tables + top-level limitation +
  `R_TABLE_MAX_ROWS`), `CLAUDE.md` (contract: `tables`/`RTable`, capture
  mechanism), root `README.md` (feature line).

## Out of scope

- Server-side sort/filter over the *full* dataset (sort/filter act on the
  delivered ≤ `R_TABLE_MAX_ROWS` rows).
- Capturing data frames printed inside functions or `source()`d files.
- `str()`-as-table, >2-D arrays, nested table-in-cell rendering (a cell's full
  value shows as text in the tap dialog).
- Editable cells, CSV export of a table, column reordering.
