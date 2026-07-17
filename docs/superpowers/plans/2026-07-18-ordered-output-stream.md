# Ordered/Interleaved Output Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render an R run's text, plots, and tables in the exact order R produced them, across both the Local (WebR) and Remote (backend) engines.

**Architecture:** Both engines' shared R harness emits two control-char sentinel markers into stdout — one when a plot page begins, one when a top-level table is produced. A single pure-Kotlin `OutputAssembler` walks the marker-bearing stdout plus the ordered `plots`/`tables` lists and produces an ordered list of `OutputChunk`s (Text/Plot/Table), plus a marker-stripped clean stdout. The editor renders that ordered list when assembly succeeds, and falls back to today's flat layout otherwise.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, kotlinx.serialization; R (`harness.R`, Plumber `plumber.R`); backend testthat.

**Spec:** `docs/superpowers/specs/2026-07-18-ordered-output-stream-design.md`

---

### Task 1: `OutputAssembler` (pure Kotlin) + models

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/OutputAssembler.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/OutputAssemblerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/execution/OutputAssemblerTest.kt`:

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.model.RTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAssemblerTest {
    private val P = OutputAssembler.PLOT_MARKER
    private val T = OutputAssembler.TABLE_MARKER
    private fun table(n: Int) = RTable(columns = listOf("c"), columnTypes = listOf("int"), rows = emptyList(), totalRows = n)

    @Test
    fun `plot then text (praise case) orders plot first`() {
        val r = OutputAssembler.assemble("${P}Everything is A-OK!\n", listOf("PNG"), emptyList())
        assertTrue(r.ordered)
        assertEquals("Everything is A-OK!\n", r.cleanStdout)
        assertEquals(2, r.chunks.size)
        assertEquals(OutputChunk.Plot("PNG"), r.chunks[0])
        assertEquals(OutputChunk.Text("Everything is A-OK!\n"), r.chunks[1])
    }

    @Test
    fun `text then plot keeps text first`() {
        val r = OutputAssembler.assemble("hello\n$P", listOf("PNG"), emptyList())
        assertTrue(r.ordered)
        assertEquals(listOf(OutputChunk.Text("hello\n"), OutputChunk.Plot("PNG")), r.chunks)
    }

    @Test
    fun `table interleaves between text`() {
        val tbl = table(3)
        val r = OutputAssembler.assemble("${P}mid$T", listOf("PNG"), listOf(tbl))
        assertTrue(r.ordered)
        assertEquals(listOf(OutputChunk.Plot("PNG"), OutputChunk.Text("mid"), OutputChunk.Table(tbl)), r.chunks)
    }

    @Test
    fun `no markers falls back and preserves stdout`() {
        val r = OutputAssembler.assemble("just text", listOf("PNG"), emptyList())
        assertFalse(r.ordered)
        assertEquals("just text", r.cleanStdout)
        assertTrue(r.chunks.isEmpty())
    }

    @Test
    fun `count mismatch falls back but still strips markers`() {
        val r = OutputAssembler.assemble("$P$P", listOf("PNG"), emptyList())
        assertFalse(r.ordered)
        assertEquals("", r.cleanStdout)
    }

    @Test
    fun `clean stdout has no sentinel bytes`() {
        val r = OutputAssembler.assemble("a${P}b${T}c", listOf("PNG"), listOf(table(1)))
        assertFalse(r.cleanStdout.contains('\u0002'))
        assertFalse(r.cleanStdout.contains('\u0003'))
        assertEquals("abc", r.cleanStdout)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.OutputAssemblerTest"`
Expected: FAIL — `OutputAssembler` / `OutputChunk` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rmobile/console/data/execution/OutputAssembler.kt`:

```kotlin
package com.rmobile.console.data.execution

import com.rmobile.console.data.model.RTable

/** One ordered piece of a run's output. */
sealed interface OutputChunk {
    data class Text(val text: String) : OutputChunk
    data class Plot(val base64Png: String) : OutputChunk
    data class Table(val table: RTable) : OutputChunk
}

/** Result of [OutputAssembler.assemble]. */
data class AssembledOutput(
    val cleanStdout: String,
    val chunks: List<OutputChunk>,
    val ordered: Boolean,
)

/**
 * Builds an ordered [OutputChunk] list from a marker-bearing stdout plus the run's
 * ordered plots and tables.
 *
 * PARITY CONTRACT: [PLOT_MARKER] / [TABLE_MARKER] must match, byte-for-byte, the markers
 * emitted by `app/src/main/assets/webr/harness.R` and the backend `plumber.R` wrapper.
 * Change all three together.
 */
object OutputAssembler {
    const val PLOT_MARKER = "\u0002RMOBILE:PLOT\u0003"
    const val TABLE_MARKER = "\u0002RMOBILE:TABLE\u0003"

    fun assemble(
        stdoutWithMarkers: String,
        plots: List<String>,
        tables: List<RTable>,
    ): AssembledOutput {
        val cleanStdout = stdoutWithMarkers
            .replace(PLOT_MARKER, "")
            .replace(TABLE_MARKER, "")
        val markersFound = stdoutWithMarkers.contains(PLOT_MARKER) ||
            stdoutWithMarkers.contains(TABLE_MARKER)
        if (!markersFound) return AssembledOutput(cleanStdout, emptyList(), ordered = false)

        val chunks = mutableListOf<OutputChunk>()
        val textBuf = StringBuilder()
        var plotIdx = 0
        var tableIdx = 0
        var overflow = false
        var rest = stdoutWithMarkers

        fun flushText() {
            val t = textBuf.toString()
            if (t.isNotBlank()) chunks.add(OutputChunk.Text(t))
            textBuf.setLength(0)
        }

        while (rest.isNotEmpty()) {
            val pAt = rest.indexOf(PLOT_MARKER)
            val tAt = rest.indexOf(TABLE_MARKER)
            if (pAt < 0 && tAt < 0) { textBuf.append(rest); break }
            val plotFirst = tAt < 0 || (pAt in 0 until tAt)
            if (plotFirst) {
                textBuf.append(rest.substring(0, pAt)); flushText()
                if (plotIdx < plots.size) chunks.add(OutputChunk.Plot(plots[plotIdx])) else overflow = true
                plotIdx++
                rest = rest.substring(pAt + PLOT_MARKER.length)
            } else {
                textBuf.append(rest.substring(0, tAt)); flushText()
                if (tableIdx < tables.size) chunks.add(OutputChunk.Table(tables[tableIdx])) else overflow = true
                tableIdx++
                rest = rest.substring(tAt + TABLE_MARKER.length)
            }
        }
        flushText()

        val ordered = !overflow && plotIdx == plots.size && tableIdx == tables.size
        return AssembledOutput(cleanStdout, chunks, ordered)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.OutputAssemblerTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/OutputAssembler.kt app/src/test/java/com/rmobile/console/data/execution/OutputAssemblerTest.kt
git commit -m "feat: OutputAssembler — ordered output chunks from stdout markers"
```

---

### Task 2: Wire the assembler into `EditorViewModel` + `EditorUiState`

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt:295-315`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Add the failing test**

Add to `EditorViewModelTest.kt` (use the same coroutine-advancing idiom as the adjacent `runCode` tests in this file — e.g. the existing "workspaceObjects" test around line 144):

```kotlin
@Test
fun `runCode assembles ordered output from stdout markers`() {
    val md = "\u0002RMOBILE:PLOT\u0003done\n"
    val engine = FakeEngine(ExecuteResponse(stdout = md, plots = listOf("PNG"), tables = emptyList()))
    val vm = viewModel(engineProvider = { _ -> engine })
    vm.onCodeChanged("plot(cars); cat('done')")
    vm.runCode()
    val s = vm.uiState.value
    assertTrue(s.outputOrdered)
    assertEquals("done\n", s.stdout)
    assertEquals(2, s.output.size)
    assertTrue(s.output[0] is com.rmobile.console.data.execution.OutputChunk.Plot)
    assertTrue(s.output[1] is com.rmobile.console.data.execution.OutputChunk.Text)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: FAIL — `output` / `outputOrdered` are not members of `EditorUiState`.

- [ ] **Step 3: Add the state fields**

In `EditorUiState.kt`, add the import and two fields:

```kotlin
import com.rmobile.console.data.execution.OutputChunk
```

Add these fields to the `EditorUiState` data class, immediately after `val tables: ...` (line 21):

```kotlin
    /** Ordered run output (text/plot/table). Rendered only when [outputOrdered]. */
    val output: List<OutputChunk> = emptyList(),
    /** True when [output] fully accounts for the run's plots/tables and should be used. */
    val outputOrdered: Boolean = false,
```

- [ ] **Step 4: Wire the assembler in `runCode`**

In `EditorViewModel.kt`, add the import:

```kotlin
import com.rmobile.console.data.execution.OutputAssembler
```

Replace the `onSuccess` block (lines 295-310) with:

```kotlin
                .onSuccess { response ->
                    val assembled = OutputAssembler.assemble(response.stdout, response.plots, response.tables)
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            stdout = assembled.cleanStdout,
                            stderr = response.stderr,
                            plotsBase64 = response.plots,
                            tables = response.tables,
                            output = assembled.chunks,
                            outputOrdered = assembled.ordered,
                            errorMessage = response.error,
                            timedOut = response.timedOut,
                            workspaceObjects = response.workspaceObjects ?: it.workspaceObjects,
                        )
                    }
                    recomputeSymbols()
                    refreshSymbols()
                }
```

Replace the `onFailure` block (lines 311-315) so stale ordered output is cleared:

```kotlin
                .onFailure { t ->
                    _uiState.update {
                        it.copy(isRunning = false, errorMessage = t.message ?: "Failed to reach the R execution backend.", timedOut = false, tables = emptyList(), output = emptyList(), outputOrdered = false)
                    }
                }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: PASS (existing tests + the new one).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "feat: assemble ordered output in EditorViewModel"
```

---

### Task 3: Render ordered output in `OutputPanel` (with fallback)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt:803-819` (inside `OutputPanel`)

There is no Compose unit-test harness in this project; this task is verified by build + on-device acceptance (Task 6). Keep the existing plot/table item composables reused.

- [ ] **Step 1: Add imports**

Ensure these imports exist at the top of `EditorScreen.kt` (add any missing):

```kotlin
import com.rmobile.console.data.execution.OutputChunk
```

- [ ] **Step 2: Branch the panel body on `outputOrdered`**

In `OutputPanel`, replace the stdout/tables/plots items (the block currently at lines 803-819, i.e. the `if (uiState.stdout.isNotBlank()) { item { Text(...) } }`, the `if (uiState.stderr...)` stays, `items(uiState.tables)`, and `items(uiState.plotsBase64)`) with an ordered branch plus the existing flat branch as fallback.

Replace lines 803-819 with:

```kotlin
        if (uiState.outputOrdered) {
            itemsIndexed(uiState.output) { _, chunk ->
                when (chunk) {
                    is OutputChunk.Text -> Text(text = chunk.text, fontFamily = FontFamily.Monospace)
                    is OutputChunk.Table -> RTableView(chunk.table)
                    is OutputChunk.Plot -> {
                        val bitmap = remember(chunk.base64Png) { decodeBase64Png(chunk.base64Png) }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "R plot output",
                                modifier = Modifier.clickable { zoomedPlot = it.asImageBitmap() },
                            )
                        }
                    }
                }
            }
        } else {
            if (uiState.stdout.isNotBlank()) {
                item { Text(text = uiState.stdout, fontFamily = FontFamily.Monospace) }
            }
            items(uiState.tables) { table -> RTableView(table) }
            items(uiState.plotsBase64) { base64Png ->
                val bitmap = remember(base64Png) { decodeBase64Png(base64Png) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "R plot output",
                        modifier = Modifier.clickable { zoomedPlot = it.asImageBitmap() },
                    )
                }
            }
        }
```

Note: the `stderr` item (currently lines 808-816) stays where it is — move it to render **after** this whole block so stderr always appears at the end in both branches. If it currently sits between stdout and tables, relocate the `if (uiState.stderr.isNotBlank()) { item { ... } }` to just after the `else { ... }` closes.

Keep `import androidx.compose.foundation.lazy.itemsIndexed` — add it if missing.

- [ ] **Step 3: Build to verify it compiles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "feat: OutputPanel renders ordered output with flat fallback"
```

---

### Task 4: Emit markers in the WebR harness

**Files:**
- Modify: `app/src/main/assets/webr/harness.R`

- [ ] **Step 1: Rewrite `harness.R` to emit markers**

Replace the entire contents of `app/src/main/assets/webr/harness.R` with:

```r
local({
  .maxrows <- 200
  .PLOT_MARKER <- paste0(intToUtf8(2), "RMOBILE:PLOT", intToUtf8(3))
  .TABLE_MARKER <- paste0(intToUtf8(2), "RMOBILE:TABLE", intToUtf8(3))
  setHook("plot.new", function(...) cat(.PLOT_MARKER), action = "replace")
  setHook("grid.newpage", function(...) cat(.PLOT_MARKER), action = "replace")
  .emit <- function(x) {
    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)
    n <- nrow(df); sub <- utils::head(df, .maxrows)
    types <- vapply(df, function(cc) class(cc)[1], character(1))
    cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))
    cols <- names(df)
    rn <- rownames(sub)
    if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }
    rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))
    obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))
    idx <- length(list.files(".", pattern = "^table[0-9]+\\.json$")) + 1L
    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))
  }
  .tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)
  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")
  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if (r$visible) print(r$value); if ((r$visible || pr) && .tabular(r$value)) { try(.emit(r$value), silent = TRUE); cat(.TABLE_MARKER) } }
  .exec(parse(file = .RMOBILE_ENTRY))
})
```

Key changes from the original: added the two marker constants, the two `setHook` calls (with `action = "replace"` so hooks don't stack across WebR runs), and reordered `.exec` so `print(r$value)` runs *before* the table `.emit`, with `cat(.TABLE_MARKER)` after the emit.

- [ ] **Step 2: Verify the R parses (syntax check)**

There is no R runtime in every dev environment; sanity-check that the braces/parens balance by eye, and confirm the file still starts with `local({` and ends with `})`. (`bridge.js` strips CR on load, so keep LF endings — `.gitattributes` already pins `webr/*.R` to LF.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/webr/harness.R
git commit -m "feat: WebR harness emits plot/table order markers"
```

---

### Task 5: Mirror markers in the backend + integration test

**Files:**
- Modify: `backend/plumber.R:243-252` (the `/execute` wrapper vector)
- Test: `backend/tests/` (add assertions to the existing execute test file)

- [ ] **Step 1: Add marker emission to the wrapper**

In `backend/plumber.R`, the `/execute` handler builds the run wrapper as a character vector around lines 243-252. Modify it so it defines the marker constants, registers the plot hooks after the `png()` device line, and reorders `.exec` to match `harness.R`.

Replace the wrapper lines that currently read:

```r
    sprintf('grDevices::png(filename = %s, width = 800, height = 600)', shQuote(plot_pattern)),
    ...
    TABLE_EMIT_HELPERS,
    '  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")',
    '  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if ((r$visible || pr) && .tabular(r$value)) try(.emit(r$value), silent = TRUE); if (r$visible) print(r$value) }',
    sprintf('  .exec(parse(file = %s))', shQuote(entry_rel)),
```

so that immediately after the `grDevices::png(...)` line these two hook lines are added, and the `.exec` line is replaced (keep every other line in between exactly as-is):

```r
    sprintf('grDevices::png(filename = %s, width = 800, height = 600)', shQuote(plot_pattern)),
    '.RMOBILE_PLOT_MARKER <- paste0(intToUtf8(2), "RMOBILE:PLOT", intToUtf8(3))',
    '.RMOBILE_TABLE_MARKER <- paste0(intToUtf8(2), "RMOBILE:TABLE", intToUtf8(3))',
    'setHook("plot.new", function(...) cat(.RMOBILE_PLOT_MARKER), action = "replace")',
    'setHook("grid.newpage", function(...) cat(.RMOBILE_PLOT_MARKER), action = "replace")',
    ...
    TABLE_EMIT_HELPERS,
    '  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")',
    '  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if (r$visible) print(r$value); if ((r$visible || pr) && .tabular(r$value)) { try(.emit(r$value), silent = TRUE); cat(.RMOBILE_TABLE_MARKER) } }',
    sprintf('  .exec(parse(file = %s))', shQuote(entry_rel)),
```

(The `...` stands for the unchanged lines already between the `png(...)` line and `TABLE_EMIT_HELPERS` — do not delete them. `TABLE_EMIT_HELPERS` itself is unchanged, so `/preview`, which reuses it, is unaffected.)

- [ ] **Step 2: Add a failing integration assertion**

In the backend execute test file under `backend/tests/` (the one that POSTs to `/execute`), add a test that a plot marker precedes later text. Using the existing testthat + httr2 helpers in that directory:

```r
test_that("/execute stdout carries an ordered plot marker before later text", {
  res <- post_execute(list(code = "plot(1); cat('AFTER')"))
  body <- httr2::resp_body_json(res)
  marker <- paste0(intToUtf8(2), "RMOBILE:PLOT", intToUtf8(3))
  expect_true(grepl(marker, body$stdout, fixed = TRUE))
  expect_lt(regexpr(marker, body$stdout, fixed = TRUE)[1], regexpr("AFTER", body$stdout, fixed = TRUE)[1])
})

test_that("/execute stdout carries a table marker for a top-level data frame", {
  res <- post_execute(list(code = "data.frame(x = 1:2)"))
  body <- httr2::resp_body_json(res)
  marker <- paste0(intToUtf8(2), "RMOBILE:TABLE", intToUtf8(3))
  expect_true(grepl(marker, body$stdout, fixed = TRUE))
  expect_length(body$tables, 1)
})
```

(Use the same request helper the neighboring tests use — if it is named differently than `post_execute`, match that name and calling convention.)

- [ ] **Step 3: Run the backend tests**

Run: `Rscript backend/run-tests.R`
Expected: PASS, including the two new assertions. (Requires R + the packages listed in `.github/workflows/android.yml`'s `backend-tests` job; if R isn't available locally, this is validated in CI.)

- [ ] **Step 4: Commit**

```bash
git add backend/plumber.R backend/tests
git commit -m "feat: backend emits plot/table order markers (harness parity) + tests"
```

---

### Task 6: Docs + full verification

**Files:**
- Modify: `CLAUDE.md`
- Modify: `app/src/main/assets/webr/README.md`
- Modify: `README.md`

- [ ] **Step 1: Document the marker parity contract**

In `CLAUDE.md`, in the paragraph describing the `assets/webr/harness.R` ↔ `plumber.R` parity coupling (the `TABLE_EMIT_HELPERS` note), append a sentence:

```
Both harnesses also emit two stdout order-markers — `\x02RMOBILE:PLOT\x03` (on each
plot page, via `setHook("plot.new"/"grid.newpage")`) and `\x02RMOBILE:TABLE\x03` (after
each top-level table `.emit`) — so the app's pure `OutputAssembler` can interleave text,
plots, and tables in execution order. Those two marker strings are a parity contract
shared by `harness.R`, `plumber.R`, and `OutputAssembler.kt`; change all three together.
The app strips the markers from `stdout` and falls back to the flat layout when they're
absent (e.g. an older backend) or don't reconcile.
```

In `app/src/main/assets/webr/README.md`, add a short "Output order markers" note describing the same two sentinels and that `harness.R` must stay byte-parity with `plumber.R`.

In `README.md`, under Features, update the "Rich output" bullet to mention that text, plots, and tables now render in execution order.

- [ ] **Step 2: Run the full app unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Assemble debug to confirm the app builds**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Syntax-check the bridge/harness assets**

Run: `node --check app/src/main/assets/webr/bridge.js`
Expected: no output (valid). (`bridge.js` itself is unchanged in this feature, but confirm nothing regressed.)

- [ ] **Step 5: On-device acceptance (manual)**

Install a debug build (`.\gradlew.bat :app:installDebug`) and confirm, on the Local engine:
1. `plot(cars); cat("done")` → the **plot renders before** the "done" text.
2. `library(ggplot2); print(ggplot(mtcars, aes(mpg, wt)) + geom_point()); cat("x")` → plot appears, then `x` (grid.newpage path).
3. `plot(1); cat("mid"); plot(2)` → plot, "mid", plot in that order.
4. A top-level `data.frame(x = 1:3)` → its table renders inline (ordered), not dumped at the end.
5. A run with no plots/tables (`cat("hello")`) → output identical to before (flat path).

Then repeat #1 and #4 on the Remote engine (a rebuilt backend) to confirm parity.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md app/src/main/assets/webr/README.md README.md
git commit -m "docs: document output order markers + ordered rendering"
```

---

## Notes for the implementer

- **Marker constants are sacred:** `\u0002RMOBILE:PLOT\u0003` and `\u0002RMOBILE:TABLE\u0003` must be identical in `OutputAssembler.kt`, `harness.R`, and `plumber.R`. The R side builds them with `paste0(intToUtf8(2), ..., intToUtf8(3))` to avoid escape ambiguity; Kotlin uses `\u0002`/`\u0003`.
- **`action = "replace"` on the hooks is not optional** — WebR's R process is long-lived, so re-running `harness.R` each execution would otherwise stack duplicate plot markers and break the count reconciliation (→ silent fallback).
- **Don't touch `TABLE_EMIT_HELPERS`** — the table marker is emitted in the `.exec` loop, not inside `.emit`, so `/preview` (which reuses `.emit`) is unaffected.
- **stderr stays a separate block** shown after the output in both branches.
