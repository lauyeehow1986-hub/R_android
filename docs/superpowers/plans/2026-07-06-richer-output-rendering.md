# Richer Output Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render printed data frames (and tibbles/data.tables/matrices/`table`s) as interactive, sortable/filterable/resizable tables, and add full-screen pinch-zoom for plots.

**Architecture:** The `/execute` wrapper runs the entry's top-level expressions through a `local({})` evaluator that emits any visible data-frame-like value as a `table*.json` side-channel file (like plot PNGs); `/execute` returns them as `tables`. The app renders each via a pure-logic-backed `RTableView` and gains a zoomable plot dialog.

**Tech Stack:** R + Plumber, testthat + httr2, Kotlin + Compose, JUnit4.

---

## Prerequisites

- **Backend tests** (Docker): `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R`
- **App tests**: export `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`, `JKS="C:/Users/lauye/AppData/Local/Temp/claude/C--Users-lauye-Downloads-R-android/6471244e-45c9-4b58-a6cc-c90db51dfa4b/scratchpad/win-roots.jks"`; append `-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain` to gradle commands.
- **Branch:** `feat/richer-output` (already created; holds the spec).

---

## File Structure

- Modify `backend/plumber.R` (config const, `/execute` wrapper + handler); Create `backend/tests/test-tables.R`.
- Modify `app/.../data/model/ExecuteModels.kt` (RTable + `tables`); `ui/editor/EditorUiState.kt`; `ui/editor/EditorViewModel.kt`; the two test fakes.
- Create `app/.../ui/editor/RTableViewOps.kt` (+ test), `ui/editor/RTableView.kt`.
- Modify `ui/editor/EditorScreen.kt` (OutputPanel: tables + plot zoom).
- Docs: `backend/README.md`, `CLAUDE.md`, `README.md`.

---

## Task 1: Backend — top-level table capture

**Files:** Modify `backend/plumber.R`; Create `backend/tests/test-tables.R`.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test-tables.R`:
```r
test_that("printing a data frame emits a table", {
  srv <- local_server()
  res <- post_execute(srv, "print(head(iris))")
  tbls <- res$body$tables
  expect_length(tbls, 1)
  expect_equal(unlist(tbls[[1]]$columns),
               c("Sepal.Length", "Sepal.Width", "Petal.Length", "Petal.Width", "Species"))
  expect_equal(unlist(tbls[[1]]$columnTypes)[[5]], "factor")
  expect_equal(unlist(tbls[[1]]$columnTypes)[[1]], "numeric")
  expect_equal(tbls[[1]]$totalRows, 6)
  expect_length(tbls[[1]]$rows, 6)
})

test_that("a 1x1 data frame round-trips as arrays (unboxing guard)", {
  srv <- local_server()
  tbls <- post_execute(srv, "data.frame(x = 1)")$body$tables
  expect_equal(unlist(tbls[[1]]$columns), "x")
  expect_equal(unlist(tbls[[1]]$rows[[1]]), "1")
})

test_that("rows are capped at R_TABLE_MAX_ROWS but totalRows is exact", {
  srv <- local_server(env = list(R_TABLE_MAX_ROWS = "3"))
  tbls <- post_execute(srv, "data.frame(n = 1:10)")$body$tables
  expect_length(tbls[[1]]$rows, 3)
  expect_equal(tbls[[1]]$totalRows, 10)
})

test_that("a 2-D table object (summary) renders as a grid", {
  srv <- local_server()
  # summary(cars) is a 2-D `table`; as.data.frame.matrix keeps its grid layout.
  tbls <- post_execute(srv, "summary(cars)")$body$tables
  expect_length(tbls, 1)
  expect_true(length(tbls[[1]]$columns) >= 2)
})

test_that("no printed data frame means no tables", {
  srv <- local_server()
  res <- post_execute(srv, "x <- 1; cat('hi')")
  expect_length(res$body$tables, 0)
  expect_equal(res$body$stdout, "hi")
})
```

- [ ] **Step 2: Run the suite — verify FAIL**

Run the backend command. Expected: `tables` is null/absent → new tests fail.

- [ ] **Step 3: Add the config constant**

In `backend/plumber.R`, after line 8 (`MAX_CODE_LENGTH <- ...`):
```r
TABLE_MAX_ROWS <- as.integer(Sys.getenv("R_TABLE_MAX_ROWS", "200"))
```

- [ ] **Step 4: Compute a single entry path for both modes**

In `/execute`, the multi-file branch currently ends with
`body_line <- sprintf('source(%s, echo = FALSE, print.eval = TRUE)', shQuote(entry))`
and the single-`code` branch with `body_line <- code`. **Delete both `body_line`
assignments.** Keep `entry` (multi-file) and `code` (single) as-is.

Then, right after the multi-file file-writing block (the `if (use_files) { for (i ...) writeLines(...) }` near line 148), add:
```r
  if (use_files) {
    entry_rel <- entry
  } else {
    entry_rel <- "main.R"
    writeLines(code, file.path(run_dir, entry_rel))
  }
```

- [ ] **Step 5: Replace `body_line` in the wrapper with the capture evaluator**

In the `wrapped <- c(...)` vector, replace the single `body_line,` element with these lines (same position — after the `png(...)` line, before `dev.off()`):
```r
    'local({',
    sprintf('  .maxrows <- %d', TABLE_MAX_ROWS),
    '  .emit <- function(x) {',
    '    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)',
    '    n <- nrow(df); sub <- utils::head(df, .maxrows)',
    '    types <- vapply(df, function(cc) class(cc)[1], character(1))',
    '    cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))',
    '    cols <- names(df)',
    '    rn <- rownames(sub)',
    '    if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }',
    '    rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))',
    '    obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))',
    '    idx <- length(list.files(".", pattern = "^table[0-9]+\\\\.json$")) + 1L',
    '    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))',
    '  }',
    '  .tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)',
    '  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")',
    '  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if ((r$visible || pr) && .tabular(r$value)) try(.emit(r$value), silent = TRUE); if (r$visible) print(r$value) }',
    sprintf('  .exec(parse(file = %s))', shQuote(entry_rel)),
    '})',
```
(Note: in the plumber.R source the regex is written `"^table[0-9]+\\.json$"`; inside the `'...'` R string above that is `\\\\.` so the *written* script gets `\\.` → the regex `\.`.)

- [ ] **Step 6: Collect the tables in the handler**

After the plots are collected (`plots <- lapply(plot_files, base64enc::base64encode)`), add:
```r
  table_files <- sort(list.files(run_dir, pattern = "^table[0-9]+\\.json$", full.names = TRUE))
  tables <- lapply(table_files, function(p) jsonlite::fromJSON(p, simplifyVector = FALSE))
```
Add `tables = tables` to the success-response `list(...)` (alongside `plots = plots`).

- [ ] **Step 7: Run the suite — verify PASS**

Run the backend command. Expected: all pass (incl. the 5 new table tests).

- [ ] **Step 8: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/plumber.R backend/tests/test-tables.R
git commit -m "backend: capture printed data frames/matrices as tables"
```

---

## Task 2: App — RTable model, response field, ViewModel mapping

**Files:** Modify `data/model/ExecuteModels.kt`, `ui/editor/EditorUiState.kt`, `ui/editor/EditorViewModel.kt`, `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`, `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`.

- [ ] **Step 1: Add the model + response field**

In `data/model/ExecuteModels.kt`, add above `ExecuteResponse`:
```kotlin
@Serializable
data class RTable(
    val columns: List<String> = emptyList(),
    val columnTypes: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val totalRows: Int = 0,
)
```
And add to `ExecuteResponse` (after `plots`):
```kotlin
    /** Data frames / matrices printed at the script's top level, as tables. */
    val tables: List<RTable> = emptyList(),
```

- [ ] **Step 2: Add UI state field**

In `ui/editor/EditorUiState.kt`, add after `plotsBase64`:
```kotlin
    val tables: List<com.rmobile.console.data.model.RTable> = emptyList(),
```

- [ ] **Step 3: Write the failing ViewModel test**

In `EditorViewModelTest.kt`, add (the file already imports `ExecuteResponse`):
```kotlin
    @Test
    fun `run maps tables into state and clears them on failure`() = runTest {
        val table = com.rmobile.console.data.model.RTable(
            columns = listOf("x"), columnTypes = listOf("numeric"),
            rows = listOf(listOf("1")), totalRows = 1,
        )
        val api = FakeApi(ExecuteResponse(stdout = "ok", tables = listOf(table)))
        val vm = viewModel(api = api)
        vm.onCodeChanged("data.frame(x=1)")
        vm.runCode()
        advanceUntilIdle()
        assertEquals(listOf(table), vm.uiState.value.tables)

        api.error = RuntimeException("boom")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tables.isEmpty())
    }
```

- [ ] **Step 4: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL (`tables` unresolved / not mapped).

- [ ] **Step 5: Map tables in `runCode`**

In `EditorViewModel.kt` `runCode()` success `copy(...)`, add after `plotsBase64 = response.plots,`:
```kotlin
                            tables = response.tables,
```
In the same method's `onFailure` block, extend the `copy(...)` to clear tables:
```kotlin
                        it.copy(isRunning = false, errorMessage = t.message ?: "Failed to reach the R execution backend.", timedOut = false, tables = emptyList())
```

- [ ] **Step 6: Keep the fakes compiling**

Both `FakeApi`s already return an `ExecuteResponse` for `execute` and won't break
(the new field has a default). No fake change needed. Run:
`./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: RTable model + tables in ExecuteResponse/state/ViewModel"
```

---

## Task 3: App — pure sort/filter logic (`RTableViewOps`)

**Files:** Create `ui/editor/RTableViewOps.kt`, `app/src/test/java/com/rmobile/console/ui/editor/RTableViewOpsTest.kt`.

- [ ] **Step 1: Write the failing tests**

Create `RTableViewOpsTest.kt`:
```kotlin
package com.rmobile.console.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class RTableViewOpsTest {
    private val rows = listOf(listOf("10", "b"), listOf("9", "a"), listOf("100", "c"))
    private val types = listOf("numeric", "character")

    @Test fun `numeric column sorts numerically not lexically`() {
        val out = RTableViewOps.display(rows, types, "", 0, true)
        assertEquals(listOf("9", "10", "100"), out.map { it[0] })
    }

    @Test fun `string column sorts lexically`() {
        val out = RTableViewOps.display(rows, types, "", 1, true)
        assertEquals(listOf("a", "b", "c"), out.map { it[1] })
    }

    @Test fun `descending reverses order`() {
        val out = RTableViewOps.display(rows, types, "", 0, false)
        assertEquals(listOf("100", "10", "9"), out.map { it[0] })
    }

    @Test fun `filter narrows rows case-insensitively`() {
        val out = RTableViewOps.display(rows, types, "B", null, true)
        assertEquals(1, out.size)
        assertEquals("b", out[0][1])
    }

    @Test fun `NA sorts last ascending`() {
        val withNa = rows + listOf(listOf("NA", "z"))
        val out = RTableViewOps.display(withNa, types, "", 0, true)
        assertEquals("NA", out.last()[0])
    }

    @Test fun `null sort column returns filtered order unchanged`() {
        val out = RTableViewOps.display(rows, types, "", null, true)
        assertEquals(rows, out)
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.RTableViewOpsTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL (unresolved `RTableViewOps`).

- [ ] **Step 3: Implement `RTableViewOps`**

Create `ui/editor/RTableViewOps.kt`:
```kotlin
package com.rmobile.console.ui.editor

/** Pure filter + type-aware sort over a table's delivered rows. */
object RTableViewOps {
    val NUMERIC_TYPES = setOf("numeric", "integer", "double", "complex")

    fun display(
        rows: List<List<String>>,
        columnTypes: List<String>,
        filter: String,
        sortColumn: Int?,
        ascending: Boolean,
    ): List<List<String>> {
        val filtered =
            if (filter.isBlank()) rows
            else rows.filter { row -> row.any { it.contains(filter, ignoreCase = true) } }

        if (sortColumn == null || sortColumn !in columnTypes.indices) return filtered

        val numeric = columnTypes[sortColumn] in NUMERIC_TYPES
        val sorted = filtered.sortedWith { a, b ->
            compareCells(a.getOrElse(sortColumn) { "" }, b.getOrElse(sortColumn) { "" }, numeric)
        }
        return if (ascending) sorted else sorted.reversed()
    }

    private fun compareCells(a: String, b: String, numeric: Boolean): Int {
        val aEmpty = a.isBlank() || a == "NA"
        val bEmpty = b.isBlank() || b == "NA"
        if (aEmpty && bEmpty) return 0
        if (aEmpty) return 1   // NA / blank sort last (ascending)
        if (bEmpty) return -1
        if (numeric) {
            val an = a.toDoubleOrNull(); val bn = b.toDoubleOrNull()
            if (an != null && bn != null) return an.compareTo(bn)
        }
        return a.compareTo(b, ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run — verify PASS**

Run the same `--tests RTableViewOpsTest` command. Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/RTableViewOps.kt \
        app/src/test/java/com/rmobile/console/ui/editor/RTableViewOpsTest.kt
git commit -m "app: pure type-aware sort/filter for tables"
```

---

## Task 4: App — `RTableView` composable + OutputPanel wiring

**Files:** Create `ui/editor/RTableView.kt`; Modify `ui/editor/EditorScreen.kt`.

- [ ] **Step 1: Create `RTableView.kt`**
```kotlin
package com.rmobile.console.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rmobile.console.data.model.RTable

@Composable
fun RTableView(table: RTable, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }
    var sortColumn by remember { mutableStateOf<Int?>(null) }
    var ascending by remember { mutableStateOf(true) }
    var expandedCell by remember { mutableStateOf<String?>(null) }

    val widths = remember(table) {
        mutableStateListOf<Dp>().apply {
            for (c in table.columns.indices) {
                val maxLen = (listOf(table.columns.getOrElse(c) { "" }) +
                    table.rows.map { it.getOrElse(c) { "" } }).maxOf { it.length }
                add((maxLen * 8).coerceIn(56, 240).dp)
            }
        }
    }

    val displayed = remember(table, filter, sortColumn, ascending) {
        RTableViewOps.display(table.rows, table.columnTypes, filter, sortColumn, ascending)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                table.columns.forEachIndexed { c, name ->
                    val numeric = table.columnTypes.getOrElse(c) { "" } in RTableViewOps.NUMERIC_TYPES
                    val indicator = if (sortColumn == c) (if (ascending) " ▲" else " ▼") else ""
                    Box(
                        modifier = Modifier
                            .width(widths.getOrElse(c) { 120.dp })
                            .clickable {
                                if (sortColumn == c) ascending = !ascending
                                else { sortColumn = c; ascending = true }
                            }
                            .padding(6.dp),
                    ) {
                        Text(
                            text = name + indicator,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(10.dp)
                                .fillMaxHeight()
                                .pointerInput(c) {
                                    detectDragGestures { _, drag ->
                                        widths[c] = (widths[c] + drag.x.toDp()).coerceAtLeast(40.dp)
                                    }
                                },
                        )
                    }
                }
            }
            HorizontalDivider()
            displayed.forEach { row ->
                Row {
                    row.forEachIndexed { c, cell ->
                        val numeric = table.columnTypes.getOrElse(c) { "" } in RTableViewOps.NUMERIC_TYPES
                        Text(
                            text = cell,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier
                                .width(widths.getOrElse(c) { 120.dp })
                                .clickable { expandedCell = cell }
                                .padding(6.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        if (table.totalRows > table.rows.size) {
            Text(
                "Showing ${table.rows.size} of ${table.totalRows} rows",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    expandedCell?.let { value ->
        AlertDialog(
            onDismissRequest = { expandedCell = null },
            confirmButton = { TextButton(onClick = { expandedCell = null }) { Text("Close") } },
            title = { Text("Cell value") },
            text = { SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) } },
        )
    }
}
```

- [ ] **Step 2: Render tables in `OutputPanel`**

In `EditorScreen.kt`'s `OutputPanel`, add after the `stderr` `item { ... }` block
and before `items(uiState.plotsBase64) { ... }`:
```kotlin
        items(uiState.tables) { table ->
            RTableView(table)
        }
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/RTableView.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: render tables in the output panel"
```

---

## Task 5: App — full-screen zoomable plots

**Files:** Modify `ui/editor/EditorScreen.kt`.

- [ ] **Step 1: Add imports**

Add to `EditorScreen.kt` (skip any already present):
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

- [ ] **Step 2: Add zoom state + make plots clickable**

At the top of `OutputPanel` (near the other `remember`s), add:
```kotlin
    var zoomedPlot by remember { mutableStateOf<ImageBitmap?>(null) }
```
In the `items(uiState.plotsBase64) { base64Png -> ... }` block, change the inline
`Image(...)` to be clickable:
```kotlin
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "R plot output",
                        modifier = Modifier.clickable { zoomedPlot = it.asImageBitmap() },
                    )
```
After the `LazyColumn { ... }` closes (still inside `OutputPanel`), add:
```kotlin
    zoomedPlot?.let { bmp -> ZoomablePlotDialog(bmp) { zoomedPlot = null } }
```

- [ ] **Step 3: Add the dialog composable**

Add to `EditorScreen.kt`:
```kotlin
@Composable
private fun ZoomablePlotDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset += panChange
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Zoomed plot",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(state),
            )
        }
    }
}
```
(If `Alignment` / `Box` / `Image` / `fillMaxWidth` aren't imported yet, add them.)

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: full-screen pinch-zoom for plots"
```

---

## Task 6: Full verification + docs

**Files:** Modify `backend/README.md`, `CLAUDE.md`, `README.md`.

- [ ] **Step 1: Backend suite**

Run the backend Docker command. Expected: all pass (incl. `test-tables.R`).

- [ ] **Step 2: Full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; 0 test failures; 0 lint errors; APK built.

- [ ] **Step 3: Docs**

- `backend/README.md` (Packages/output section): note that data frames, tibbles,
  data.tables, matrices, and `table` objects **printed at the script's top level**
  are returned as structured `tables` (`R_TABLE_MAX_ROWS`, default 200); prints
  inside functions/`source()`d files aren't captured.
- `CLAUDE.md` (response contract): add `tables: List<RTable>` (`RTable(columns,
  columnTypes, rows, totalRows)`) to the `/execute` response, and note the
  top-level capture mechanism.
- `README.md` (features): add a "rich output — data frames as sortable/filterable
  tables, pinch-zoom plots" line.

- [ ] **Step 4: Commit**
```bash
git add backend/README.md CLAUDE.md README.md
git commit -m "docs: richer output rendering (tables + plot zoom)"
```

---

## Done

Printed data frames render as interactive tables and plots pinch-zoom. Open a PR
from `feat/richer-output` (base `claude/r-app-android-version-ztmyd1`).
