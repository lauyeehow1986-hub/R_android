# Ordered/interleaved output stream — Design

**Date:** 2026-07-18
**Status:** Approved, ready for planning

## Goal

Render an R run's **text (stdout), plots, and tables in the exact order R produced
them**, instead of the current fixed layout (all text, then all tables, then all
plots). Concretely, code like `plot(cars); praise::praise()` must show the plot
*before* the praise text — today the plot always renders last because plots are a
separate bucket from stdout.

Applies to **both engines** (Local/WebR and Remote/backend), which are kept in
harness parity.

## Background: why the order is lost today

Both engines run the entry's top-level expressions through a `withVisible` eval
loop (`app/src/main/assets/webr/harness.R`, mirrored in the backend `plumber.R`
wrapper). Output is captured into **three separate buckets**:

- **stdout** — one concatenated string (backend) / ordered `cap.output` stream (WebR).
- **plots** — `plot%03d.png` files (backend) / `cap.images` array (WebR), in draw order.
- **tables** — `table%03d.json` files, in emit order (`TABLE_EMIT_HELPERS` / harness `.emit`).

Each bucket is internally ordered, but there is **no cross-link** recording where a
plot or table occurred relative to the text. `OutputPanel`
(`app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`) then renders
stdout, then tables, then plots — so plots always appear after all text.

## Decisions (locked during brainstorming)

1. **Granularity:** per top-level statement is the target, but the chosen mechanism
   (stdout markers) naturally yields true per-plot-page position ordering, which is a
   superset. No extra work for that precision.
2. **Contract:** additive + fallback. Existing `stdout`/`stderr`/`plots`/`tables`
   are unchanged; ordered output is *derived* app-side. When ordering can't be
   established, the UI falls back to today's flat layout.
3. **Mechanism:** sentinel markers in stdout (Approach A), assembled by a single
   pure-Kotlin function (no second R/JS assembler).
4. **stderr stays separate** — shown as its own block after the output, not
   interleaved (matches R deferring warnings to end; keeps scope tight).
5. **Scope:** both engines. `/preview` (single table) untouched.

## Architecture

### The marker protocol (only shared-R change)

Two control-char sentinels, each emitted on its own line via `cat`:

- `PLOT_MARKER  = "\x02RMOBILE:PLOT\x03"`
- `TABLE_MARKER = "\x02RMOBILE:TABLE\x03"`

Control chars `\x02` (STX) / `\x03` (ETX) make accidental collision with real output
vanishingly unlikely.

In `harness.R` (and byte-for-byte in the backend `plumber.R` wrapper — the existing
parity coupling):

- Register plot hooks once per run, replacing any prior handler:
  ```r
  setHook("plot.new",    function(...) cat(PLOT_MARKER, "\n", sep = ""), action = "replace")
  setHook("grid.newpage", function(...) cat(PLOT_MARKER, "\n", sep = ""), action = "replace")
  ```
  Both hooks are needed: `plot.new` fires for base graphics; `grid.newpage` fires for
  grid-based graphics (ggplot2, lattice). `action = "replace"` is **required** because
  WebR's R process is long-lived and re-runs `harness.R` each execution — without
  `replace`, handlers stack and emit duplicate markers.
- The existing table `.emit` emits the TABLE marker. It must be `cat` **after** the
  value's own `print()` so the table reads *after* its printed text. Reorder `.exec`
  so, for a tabular visible value, `print(value)` runs, then `.emit(value)` (which
  ends by `cat`-ing the TABLE marker).

No other R change. Text still streams normally; markers merely tag positions in the
already-ordered stdout stream. Plots and tables continue to be captured into their
existing buckets in order.

### App-side assembly (single pure-Kotlin unit)

Both engines return, as today, the marker-bearing `stdout` plus `plots` (ordered) and
`tables` (ordered). A pure function derives the ordered view:

```kotlin
// data/execution/OutputAssembler.kt
sealed interface OutputChunk {
    data class Text(val text: String) : OutputChunk
    data class Plot(val base64Png: String) : OutputChunk
    data class Table(val table: RTable) : OutputChunk
}

data class AssembledOutput(
    val cleanStdout: String,          // stdout with all markers stripped
    val chunks: List<OutputChunk>,    // ordered text/plot/table
    val ordered: Boolean,             // true only if usable (see below)
)

object OutputAssembler {
    fun assemble(
        stdoutWithMarkers: String,
        plots: List<String>,
        tables: List<RTable>,
    ): AssembledOutput
}
```

Algorithm:

1. Split `stdoutWithMarkers` into lines. Walk them, accumulating text until a marker
   line is hit.
2. On a PLOT marker: flush accumulated text as a `Text` chunk (if non-blank), then emit
   `Plot(plots[nextPlotIndex++])`.
3. On a TABLE marker: flush text, then emit `Table(tables[nextTableIndex++])`.
4. At end, flush any remaining text.
5. `cleanStdout` = the input with every marker line removed (always, in all paths — the
   user must never see a sentinel).
6. `ordered` = `markersFound && nextPlotIndex == plots.size && nextTableIndex == tables.size`.
   If a marker references an out-of-range index (counts don't reconcile), the assembler
   still strips markers and returns `ordered = false` so the UI falls back safely.

This is the **only** assembly implementation — no R or JS assembler — so there is no new
cross-engine parity surface beyond the two marker constants. Those two literal strings
(`PLOT_MARKER`, `TABLE_MARKER`) are the parity contract shared by exactly three files:
`harness.R`, the backend `plumber.R` wrapper, and `OutputAssembler.kt`. All three must
agree byte-for-byte; document them alongside the existing `TABLE_EMIT_HELPERS` note.

### Wiring

`EditorViewModel`, when mapping an `ExecuteResponse` to state, calls
`OutputAssembler.assemble(response.stdout, response.plots, response.tables)` and stores:

- `EditorUiState.stdout` = `cleanStdout` (used by "Copy output" and the flat fallback),
- `EditorUiState.output: List<OutputChunk>` = `chunks`,
- `EditorUiState.outputOrdered: Boolean` = `ordered`.

Run-history entries store `cleanStdout` (never markers). No change to the wire
`ExecuteResponse` model — ordered output is a derived, on-device concern. Both engines
benefit because both surface marker-bearing stdout through the same `ExecuteResponse`.

### Rendering (`OutputPanel`)

- If `uiState.outputOrdered`, iterate `uiState.output` in order:
  - `Text` → monospace `Text`,
  - `Plot` → the existing `Image` with tap-to-zoom,
  - `Table` → `RTableView`.
- Else, render today's flat layout (stdout block → tables → plots), unchanged.
- The error/timeout banner stays pinned on top; the stderr block and "Copy output"
  button are unchanged and shown regardless.

## Data flow (the praise case)

1. User runs `plot(cars); praise::praise()`.
2. Harness: `plot(cars)` triggers `plot.new` → `cat(PLOT_MARKER)`; `praise()` prints its
   text. stdout stream = `PLOT_MARKER\n` then `Everything is A-OK!` (or similar).
3. Engine returns `stdout` (with marker), `plots = [<png>]`, `tables = []`.
4. `OutputAssembler`: PLOT marker first → `Plot(png)`, then remaining text → `Text("...")`.
   `ordered = true` (1 plot chunk == 1 plot, 0 tables == 0).
5. `OutputPanel` renders the plot, then the praise text. Correct order.

## Error handling / edge cases

- **No markers (old backend, or a run that printed nothing/plotted nothing):**
  `markersFound = false` → `ordered = false` → flat fallback. If there were plots but no
  markers (pre-feature backend), fallback still shows them (flat), so nothing is dropped.
- **Failed / timed-out run:** partial or no markers; `ordered` is false unless counts
  reconcile → flat fallback. Markers are still stripped from stdout.
- **Count mismatch (marker without a corresponding captured plot/table, or vice versa):**
  `ordered = false`, markers stripped, flat fallback. Never index out of range.
- **User output literally contains the sentinel:** control-char sequence makes this
  effectively impossible; if it somehow happened, worst case is a spurious chunk split →
  `ordered` likely false via count mismatch → flat fallback. No crash.
- **Hook stacking across WebR runs:** prevented by `action = "replace"`.

## Testing

### Pure / JVM unit (the bulk — `OutputAssemblerTest`)
- plot-then-text (the praise bug) → `Plot`, `Text`; `ordered = true`.
- text-then-plot → `Text`, `Plot`.
- multiple plots around text → correct interleaving.
- top-level data.frame → `Text` (its print) then `Table`.
- mixed text/plot/table sequence.
- no markers, with plots present → `ordered = false`, `cleanStdout == input`.
- count mismatch (2 PLOT markers, 1 plot) → `ordered = false`, markers stripped.
- marker stripping leaves no `\x02`/`\x03` bytes in `cleanStdout`.

### Backend integration (testthat)
- `/execute` with `plot(1); cat("after")` → response `stdout` contains `PLOT_MARKER`
  positioned before `after`.
- `/execute` with a top-level `data.frame(...)` → `stdout` contains `TABLE_MARKER` and a
  `table*.json` is returned.

### On-device acceptance (WebR behavior isn't statically verifiable)
1. `plot(cars); praise::praise()` (or `cat("done")`) → plot renders **before** the text.
2. A ggplot at top level → plot appears in order (grid.newpage hook path).
3. Two plots with text between them → text sits between the plots.
4. A top-level data.frame → its table renders inline, in order, not dumped at the end.
5. A run with no plots/tables → output identical to today (flat path).

## Components / files

- `app/src/main/assets/webr/harness.R` — add markers (hooks + `.emit`), reorder `.exec`.
- `backend/plumber.R` — mirror the same marker emission in the wrapper /
  `TABLE_EMIT_HELPERS` (byte parity).
- `app/src/main/java/com/rmobile/console/data/execution/OutputAssembler.kt` — new pure
  unit + `OutputChunk` / `AssembledOutput`.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt` — add
  `output: List<OutputChunk>`, `outputOrdered: Boolean`.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt` — call the
  assembler when mapping results; store clean stdout + chunks; history uses clean stdout.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt` — `OutputPanel`
  renders ordered chunks when `outputOrdered`, else the existing flat layout.
- Tests: `app/src/test/.../OutputAssemblerTest.kt`; backend testthat additions;
  docs (`CLAUDE.md`, `app/src/main/assets/webr/README.md`) note the marker protocol as a
  parity contract.

## Out of scope / deferred

- Within-statement fine-grained interleaving (e.g. a for-loop printing and plotting each
  iteration is ordered per plot page via hooks, but text within one statement isn't split
  around those beyond what the markers already provide).
- Interleaving **stderr** into the ordered stream (stays a separate block).
- `/preview` (single table; no ordering concern).
- Changing the wire `ExecuteResponse` schema (ordered output is derived app-side).
