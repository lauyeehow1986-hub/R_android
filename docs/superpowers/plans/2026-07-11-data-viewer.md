# Data Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user preview tabular data as a table without writing code — both an uploaded data file (`sales.csv`) and an in-session workspace data frame (a `View(df)`) — rendered full-screen with the existing `RTableView`.

**Architecture:** One new **read-only** backend endpoint `POST /preview` (`{sessionId, source, name}`) runs an isolated `Rscript --vanilla` that reads a file by extension or `get()`s a workspace object, coerces to a data frame, and emits the **same `table*.json` format** `/execute` already produces (factored into a shared helper). It never calls `save.image()`. On the app side, one `PreviewViewModel` + full-screen `PreviewScreen` reuses `RTable`/`RTableView`, reached from a "View" button on the Data screen and from tappable workspace chips in the editor.

**Tech Stack:** Backend: R + Plumber, `processx::run`, testthat + httr2 (CI-gated). App: Kotlin, Jetpack Compose, ViewModel/StateFlow, kotlinx.serialization, Retrofit, JUnit.

**Design doc:** `docs/superpowers/specs/2026-07-11-data-viewer-design.md`

---

## Environment notes for the implementer

- **Backend tests run in CI only.** The local testthat harness segfaults on this machine and R is not on `PATH`. Do **not** try to run `Rscript backend/run-tests.R` locally. You *can* syntax-check R with the full path:
  ```bash
  "/c/Program Files/R/R-4.5.2/bin/Rscript.exe" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"
  ```
  Write backend tests (they gate CI), but treat CI as the source of truth for backend green.
- **App tests + build run locally** from the repo root with:
  ```bash
  JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
  JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
  ```
- **Never name a Kotlin class/object `R`** (collides with the generated resource class).
- Commit messages end with the `Co-Authored-By:` trailer used across this repo.

---

## File Structure

**Backend (modify):**
- `backend/plumber.R` — add `TABLE_EMIT_HELPERS` constant (factored from `/execute`), add `POST /preview`, add `/preview` to `is_protected()`.
- `backend/tests/helper-server.R` — add `post_preview()` helper.
- `backend/tests/test-preview.R` — new integration tests.
- `backend/README.md` — document `/preview`.

**App (create):**
- `app/src/main/java/com/rmobile/console/data/model/PreviewModels.kt` — `PreviewRequest`, `PreviewResponse`.
- `app/src/main/java/com/rmobile/console/ui/preview/PreviewUiState.kt`
- `app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt`
- `app/src/main/java/com/rmobile/console/ui/preview/PreviewScreen.kt`
- `app/src/test/java/com/rmobile/console/ui/preview/PreviewViewModelTest.kt`

**App (modify):**
- `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt` — add `preview`.
- `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt` — add `preview(...)`.
- `app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt` — add "View" button + `onPreviewFile` param.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt` — workspace line → tappable chips + `onPreviewObject` param.
- `app/src/main/java/com/rmobile/console/MainActivity.kt` — `Screen.PREVIEW`, `previewTarget` state, wiring.
- **Test fakes that implement `RExecutionApi` (add a `preview` override to each):**
  `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt`,
  `app/src/test/java/com/rmobile/console/ui/data/DataViewModelTest.kt`,
  `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`,
  `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`.
- `README.md`, `CLAUDE.md` — document the feature.

---

## Task 1: Backend — factor the table-emit helper into a shared constant

Pure refactor, no behavior change. Extracts the `.maxrows`/`.emit`/`.tabular` R source (currently inline in the `/execute` wrapper) into a top-level `TABLE_EMIT_HELPERS` vector so `/preview` (Task 2) can reuse the identical emitter. Existing `backend/tests/test-tables.R` covers the behavior (CI-gated).

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Define the shared constant.** In `backend/plumber.R`, immediately after the `TABLE_MAX_ROWS <- ...` line (around line 10), add:

```r
# Shared R source (a character vector of lines) that defines the table emitter
# used by both /execute and /preview, so both write byte-identical table*.json.
# .emit(x): coerce x to a data frame, head() to .maxrows, and write the next
# table###.json (columns/columnTypes/rows/totalRows) into the working dir.
# .tabular(v): TRUE for a data frame or a 2-D matrix/table.
TABLE_EMIT_HELPERS <- c(
  sprintf('.maxrows <- %d', TABLE_MAX_ROWS),
  '.emit <- function(x) {',
  '  df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)',
  '  n <- nrow(df); sub <- utils::head(df, .maxrows)',
  '  types <- vapply(df, function(cc) class(cc)[1], character(1))',
  '  cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))',
  '  cols <- names(df)',
  '  rn <- rownames(sub)',
  '  if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }',
  '  rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))',
  '  obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))',
  '  idx <- length(list.files(".", pattern = "^table[0-9]+\\\\.json$")) + 1L',
  '  writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))',
  '}',
  '.tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)'
)
```

- [ ] **Step 2: Use it in the `/execute` wrapper.** In the `wrapped <- c(...)` block, replace the inline helper lines (the `sprintf('  .maxrows <- %d', TABLE_MAX_ROWS)` line through the `'  .tabular <- function(v) ...'` line) with a single `TABLE_EMIT_HELPERS,` entry. The result should read:

```r
    'local({',
    TABLE_EMIT_HELPERS,
    '  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")',
    '  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if ((r$visible || pr) && .tabular(r$value)) try(.emit(r$value), silent = TRUE); if (r$visible) print(r$value) }',
    sprintf('  .exec(parse(file = %s))', shQuote(entry_rel)),
    '})',
```

(Keep the `local({ ... })` wrapper, `.is_print`, `.exec`, and the `.exec(parse(...))` call exactly as they were — only the `.maxrows`/`.emit`/`.tabular` definitions move into the constant. `c()` flattens `TABLE_EMIT_HELPERS` into the surrounding vector.)

- [ ] **Step 3: Syntax-check.**

Run: `"/c/Program Files/R/R-4.5.2/bin/Rscript.exe" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"`
Expected: `parse OK`

- [ ] **Step 4: Commit.**

```bash
git add backend/plumber.R
git commit -m "backend: extract shared TABLE_EMIT_HELPERS from /execute"
```

---

## Task 2: Backend — `POST /preview` endpoint + tests

**Files:**
- Modify: `backend/plumber.R`, `backend/tests/helper-server.R`
- Test: `backend/tests/test-preview.R`

- [ ] **Step 1: Add a `post_preview` test helper.** In `backend/tests/helper-server.R`, after the `post_delete_data` helper, add:

```r
post_preview <- function(server, source, name, session_id = NULL, key = NULL) {
  body <- list(source = source, name = name)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/preview", body = body, key = key)
}
```

- [ ] **Step 2: Write the failing tests.** Create `backend/tests/test-preview.R`:

```r
test_that("preview of an uploaded CSV returns a table", {
  server <- local_server()
  csv <- tempfile(fileext = ".csv")
  writeLines(c("x,y", "1,2", "3,4"), csv)
  up <- post_upload(server, csv, session_id = "s1")

  pv <- post_preview(server, "file", up$body$name, session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$error)
  expect_equal(unlist(pv$body$table$columns), c("x", "y"))
  expect_equal(length(pv$body$table$rows), 2)
  expect_equal(pv$body$table$totalRows, 2)
})

test_that("preview of an uploaded RDS data frame returns a table", {
  server <- local_server()
  rds <- tempfile(fileext = ".rds")
  saveRDS(data.frame(a = 1:3, b = c("p", "q", "r")), rds)
  up <- post_upload(server, rds, session_id = "s1")

  pv <- post_preview(server, "file", up$body$name, session_id = "s1")
  expect_equal(pv$status, 200)
  expect_equal(unlist(pv$body$table$columns), c("a", "b"))
  expect_equal(length(pv$body$table$rows), 3)
})

test_that("preview of a workspace object returns a table and does not change state", {
  server <- local_server()
  run <- post_execute(server, "df <- data.frame(n = 1:5)", session_id = "s1")
  expect_equal(run$status, 200)
  before <- sort(unlist(run$body$workspaceObjects))

  pv <- post_preview(server, "object", "df", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_equal(unlist(pv$body$table$columns), "n")
  expect_equal(pv$body$table$totalRows, 5)

  # Read-only: a subsequent run still sees the same workspace, unchanged.
  after <- post_execute(server, "cat(ls())", session_id = "s1")
  expect_equal(sort(unlist(after$body$workspaceObjects)), before)
})

test_that("preview of a non-tabular object reports an error, no table", {
  server <- local_server()
  post_execute(server, "v <- 1:10", session_id = "s1")

  pv <- post_preview(server, "object", "v", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview of a missing file reports an error", {
  server <- local_server()
  pv <- post_preview(server, "file", "nope.csv", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview of a missing object reports an error", {
  server <- local_server()
  pv <- post_preview(server, "object", "ghost", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview rejects a bad source with 400", {
  server <- local_server()
  pv <- post_preview(server, "bogus", "df", session_id = "s1")
  expect_equal(pv$status, 400)
})

test_that("preview rejects a bad object name with 400", {
  server <- local_server()
  pv <- post_preview(server, "object", "no spaces!", session_id = "s1")
  expect_equal(pv$status, 400)
})
```

- [ ] **Step 3: Add `/preview` to the protected set.** In `backend/plumber.R`, extend `is_protected()` to include `"/preview"`:

```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy", "/symbols", "/help", "/upload", "/data", "/delete-data", "/preview")
```

- [ ] **Step 4: Implement the endpoint.** In `backend/plumber.R`, add after the `/help` handler (before `/upload`):

```r
#* Preview a data file or a workspace object as a table. Strictly read-only:
#* never writes session state (no save.image, no history), so it is safe to call
#* freely. Body: {sessionId?, source: "file"|"object", name}.
#* @post /preview
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  source <- body$source
  name <- body$name

  if (is.null(source) || !is.character(source) || length(source) != 1 || !(source %in% c("file", "object"))) {
    res$status <- 400
    return(list(table = NULL, error = "source must be 'file' or 'object'.", truncated = FALSE))
  }
  if (identical(source, "file")) {
    safe <- sanitize_data_name(name)
    if (is.null(safe)) {
      res$status <- 400
      return(list(table = NULL, error = "Invalid file name.", truncated = FALSE))
    }
    name <- safe
  } else {
    if (is.null(name) || !is.character(name) || length(name) != 1 || !grepl("^[A-Za-z.][A-Za-z0-9._]*$", name)) {
      res$status <- 400
      return(list(table = NULL, error = "Invalid object name.", truncated = FALSE))
    }
  }

  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("preview-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "preview.R")
  err_path <- file.path(run_dir, "error.txt")

  # Source-specific snippet that assigns the value to preview into `.df`.
  if (identical(source, "file")) {
    src <- file.path(paths$data, name)
    if (!file.exists(src)) {
      return(list(table = NULL, error = "No such data file.", truncated = FALSE))
    }
    try(file.symlink(src, file.path(run_dir, name)), silent = TRUE)
    read_lines <- c(
      sprintf('fname <- %s', shQuote(name)),
      'ext <- tolower(tools::file_ext(fname))',
      'need <- function(pkg) if (!requireNamespace(pkg, quietly = TRUE)) stop(sprintf("Install \'%s\' in this project to preview .%s files.", pkg, ext))',
      '.df <- if (ext == "csv") utils::read.csv(fname, check.names = FALSE)',
      '  else if (ext %in% c("tsv", "tab")) utils::read.delim(fname, check.names = FALSE)',
      '  else if (ext == "rds") readRDS(fname)',
      '  else if (ext %in% c("xlsx", "xls")) { need("readxl"); as.data.frame(readxl::read_excel(fname)) }',
      '  else if (ext == "parquet") { need("arrow"); as.data.frame(arrow::read_parquet(fname)) }',
      '  else stop("Can\'t preview this file type.")'
    )
  } else {
    read_lines <- c(
      sprintf('.nm <- %s', shQuote(name)),
      sprintf('.e <- new.env(); if (file.exists(%s)) load(%s, envir = .e)', shQuote(paths$workspace), shQuote(paths$workspace)),
      'if (!exists(.nm, envir = .e, inherits = FALSE)) stop(sprintf("No object named \'%s\' in this project\'s workspace.", .nm))',
      '.df <- get(.nm, envir = .e)'
    )
  }

  script <- c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    'invisible(tryCatch({',
    read_lines,
    TABLE_EMIT_HELPERS,
    'if (!.tabular(.df)) stop("Not a table.")',
    '.emit(.df)',
    sprintf('}, error = function(e) writeLines(conditionMessage(e), %s)))', shQuote(err_path))
  )
  writeLines(script, script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )

  err_msg <- if (file.exists(err_path)) paste(readLines(err_path, warn = FALSE), collapse = "\n") else ""
  table_file <- file.path(run_dir, "table001.json")

  if (nzchar(err_msg) || !file.exists(table_file)) {
    msg <- if (nzchar(err_msg)) err_msg else "Could not read a table from this source."
    return(list(table = NULL, error = msg, truncated = FALSE))
  }

  tbl <- jsonlite::fromJSON(table_file, simplifyVector = FALSE)
  total <- tryCatch(as.integer(tbl$totalRows), error = function(e) NA_integer_)
  list(table = tbl, error = NULL, truncated = isTRUE(total > TABLE_MAX_ROWS))
}
```

- [ ] **Step 5: Syntax-check.**

Run: `"/c/Program Files/R/R-4.5.2/bin/Rscript.exe" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"`
Expected: `parse OK`

- [ ] **Step 6: Commit.** (Backend tests are verified in CI, not locally.)

```bash
git add backend/plumber.R backend/tests/helper-server.R backend/tests/test-preview.R
git commit -m "backend: POST /preview reads a data file or workspace object as a table"
```

---

## Task 3: App — preview models

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/model/PreviewModels.kt`
- Test: `app/src/test/java/com/rmobile/console/data/model/PreviewModelsTest.kt`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/rmobile/console/data/model/PreviewModelsTest.kt`:

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request serializes source, name, sessionId`() {
        val out = json.encodeToString(PreviewRequest.serializer(), PreviewRequest("file", "a.csv", "proj-1"))
        assertEquals("""{"source":"file","name":"a.csv","sessionId":"proj-1"}""", out)
    }

    @Test
    fun `response parses a table with truncated flag`() {
        val body = """{"table":{"columns":["x"],"columnTypes":["integer"],"rows":[["1"]],"totalRows":900},"error":null,"truncated":true}"""
        val resp = json.decodeFromString(PreviewResponse.serializer(), body)
        assertEquals(listOf("x"), resp.table!!.columns)
        assertEquals(900, resp.table!!.totalRows)
        assertEquals(true, resp.truncated)
        assertNull(resp.error)
    }

    @Test
    fun `response parses an error with null table`() {
        val resp = json.decodeFromString(PreviewResponse.serializer(), """{"table":null,"error":"Not a table.","truncated":false}""")
        assertNull(resp.table)
        assertEquals("Not a table.", resp.error)
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.PreviewModelsTest"`
Expected: FAIL — `PreviewRequest`/`PreviewResponse` unresolved (compilation error).

- [ ] **Step 3: Create the models.** Create `app/src/main/java/com/rmobile/console/data/model/PreviewModels.kt`:

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PreviewRequest(
    val source: String,          // "file" or "object"
    val name: String,
    val sessionId: String? = null,
)

@Serializable
data class PreviewResponse(
    val table: RTable? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)
```

- [ ] **Step 4: Run to verify it passes.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.PreviewModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/rmobile/console/data/model/PreviewModels.kt app/src/test/java/com/rmobile/console/data/model/PreviewModelsTest.kt
git commit -m "app: preview request/response models"
```

---

## Task 4: App — API method + repository + fake updates

Adds `preview` to `RExecutionApi`. **Because four test fakes implement `RExecutionApi`, each must gain a `preview` override or the whole test module fails to compile.**

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`
- Modify (fakes): `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt`, `.../ui/data/DataViewModelTest.kt`, `.../ui/editor/EditorViewModelTest.kt`, `.../ui/packages/PackagesViewModelTest.kt`
- Test: `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt`

- [ ] **Step 1: Add the API method.** In `RExecutionApi.kt`, add the import and method:

```kotlin
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
```
```kotlin
    @POST("preview")
    suspend fun preview(@Body request: PreviewRequest): PreviewResponse
```

- [ ] **Step 2: Add the repository method.** In `RExecutionRepository.kt`, add the imports and method (near the other single-call wrappers):

```kotlin
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
```
```kotlin
    suspend fun preview(
        source: String,
        name: String,
        sessionId: String = DEFAULT_SESSION_ID,
    ): Result<PreviewResponse> =
        runCatching { api.preview(PreviewRequest(source, name, sessionId)) }
```

- [ ] **Step 3: Update the three ViewModel-test fakes.** In `DataViewModelTest.kt`, `EditorViewModelTest.kt`, and `PackagesViewModelTest.kt`, find the class implementing `RExecutionApi` (in `DataViewModelTest` it's `NoopExecApi`; in the others find the `: RExecutionApi` class) and add this override alongside the others. If the file already imports `PreviewRequest`/`PreviewResponse`, use short names; otherwise use the fully-qualified form:

```kotlin
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) =
            com.rmobile.console.data.model.PreviewResponse()
```

(`RExecutionRepositoryTest.kt`'s `FakeApi` is handled in Step 4 with a recording override — do not add a bare one there.)

- [ ] **Step 4: Add the recording override + a test to `RExecutionRepositoryTest.kt`.** This file's fake is named `FakeApi`. Add these imports:

```kotlin
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.RTable
```

Inside `class FakeApi(...) : RExecutionApi { ... }`, add two properties and the override (next to the existing `help` override):

```kotlin
        var previewResponse: PreviewResponse = PreviewResponse(table = RTable(columns = listOf("x")))
        var lastPreview: PreviewRequest? = null
        override suspend fun preview(request: PreviewRequest): PreviewResponse {
            lastPreview = request; return previewResponse
        }
```

Add the test (mirrors the existing `help passes topic and session` test; the single-arg `RExecutionRepository(api)` is fine — `preview` never touches the data API):

```kotlin
    @Test
    fun `preview forwards source, name, session and returns the table`() = runTest {
        val api = FakeApi()
        val result = RExecutionRepository(api).preview("file", "a.csv", "proj-7")
        assertEquals("file", api.lastPreview!!.source)
        assertEquals("a.csv", api.lastPreview!!.name)
        assertEquals("proj-7", api.lastPreview!!.sessionId)
        assertEquals(listOf("x"), result.getOrNull()!!.table!!.columns)
    }
```

- [ ] **Step 5: Run the app tests.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing tests still compile + pass, new repository test passes).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt app/src/test/java/com/rmobile/console/
git commit -m "app: preview API + repository method (+ fake overrides)"
```

---

## Task 5: App — `PreviewViewModel` + state

Session-scoped exactly like `DataViewModel` (resolves the active project's session via `ProjectStore` in `init`).

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/preview/PreviewUiState.kt`
- Create: `app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/rmobile/console/ui/preview/PreviewViewModelTest.kt`:

```kotlin
package com.rmobile.console.ui.preview

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.PreviewRequest
import com.rmobile.console.data.model.PreviewResponse
import com.rmobile.console.data.model.RTable
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.model.DataFilesResponse
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.DeleteDataResponse
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.HelpRequest
import com.rmobile.console.data.model.HelpResponse
import com.rmobile.console.data.model.ImportLegacyRequest
import com.rmobile.console.data.model.ImportLegacyResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.model.SymbolsResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePreviewApi(
        var response: PreviewResponse = PreviewResponse(table = RTable(columns = listOf("x"), totalRows = 1)),
        var fail: Boolean = false,
    ) : RExecutionApi {
        var lastRequest: PreviewRequest? = null
        override suspend fun preview(request: PreviewRequest): PreviewResponse {
            lastRequest = request
            if (fail) throw RuntimeException("boom")
            return response
        }
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = SymbolsResponse()
        override suspend fun help(request: HelpRequest) = HelpResponse()
    }

    private class NoopDataApi : RDataApi {
        override suspend fun upload(sessionId: String, file: MultipartBody.Part) = UploadResponse()
        override suspend fun dataFiles(sessionId: String) = DataFilesResponse()
        override suspend fun deleteData(request: DeleteDataRequest) = DeleteDataResponse()
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored: List<Project> = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun vm(
        api: FakePreviewApi,
        store: ProjectStore = InMemoryProjectStore(),
    ) = PreviewViewModel(RExecutionRepository(api, NoopDataApi()), store)

    @Test
    fun `load populates the table and title`() = runTest {
        val api = FakePreviewApi(PreviewResponse(table = RTable(columns = listOf("a"), totalRows = 3)))
        val model = vm(api)
        model.load("file", "a.csv")
        advanceUntilIdle()
        val s = model.uiState.value
        assertEquals("a.csv", s.title)
        assertEquals(listOf("a"), s.table!!.columns)
        assertTrue(!s.isLoading)
        assertNull(s.error)
    }

    @Test
    fun `load surfaces a backend error message`() = runTest {
        val api = FakePreviewApi(PreviewResponse(table = null, error = "Not a table."))
        val model = vm(api)
        model.load("object", "v")
        advanceUntilIdle()
        assertEquals("Not a table.", model.uiState.value.error)
        assertNull(model.uiState.value.table)
    }

    @Test
    fun `load surfaces a network failure`() = runTest {
        val api = FakePreviewApi(fail = true)
        val model = vm(api)
        model.load("object", "v")
        advanceUntilIdle()
        assertTrue(!model.uiState.value.error.isNullOrEmpty())
    }

    @Test
    fun `load uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 42, name = "P", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 42)
        val api = FakePreviewApi()
        vm(api, store).load("file", "a.csv")
        advanceUntilIdle()
        assertEquals("proj-42", api.lastRequest!!.sessionId)
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.preview.PreviewViewModelTest"`
Expected: FAIL — `PreviewViewModel`/`PreviewUiState` unresolved.

- [ ] **Step 3: Create the UI state.** Create `app/src/main/java/com/rmobile/console/ui/preview/PreviewUiState.kt`:

```kotlin
package com.rmobile.console.ui.preview

import com.rmobile.console.data.model.RTable

data class PreviewUiState(
    val title: String = "",
    val table: RTable? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    val totalRows: Int = 0,
)
```

- [ ] **Step 4: Create the ViewModel.** Create `app/src/main/java/com/rmobile/console/ui/preview/PreviewViewModel.kt`:

```kotlin
package com.rmobile.console.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.ServiceLocator
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.project.ProjectSession
import com.rmobile.console.data.project.ProjectStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PreviewViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val session: String

    init {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
    }

    /** Loads a preview of [name] (a file or a workspace object per [source]). */
    fun load(source: String, name: String) {
        _uiState.value = PreviewUiState(title = name, isLoading = true)
        viewModelScope.launch {
            repository.preview(source, name, session)
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            table = resp.table,
                            error = resp.error,
                            truncated = resp.truncated,
                            totalRows = resp.table?.totalRows ?: 0,
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, error = t.message ?: "Preview failed.") }
                }
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.preview.PreviewViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/rmobile/console/ui/preview/ app/src/test/java/com/rmobile/console/ui/preview/
git commit -m "app: PreviewViewModel + state (session-scoped)"
```

---

## Task 6: App — `PreviewScreen` (Compose)

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/preview/PreviewScreen.kt`

> **Note:** `RTableView` (`ui/editor/RTableView.kt`) already renders a built-in
> "Showing X of N rows" footer when `table.totalRows > table.rows.size`, so the
> preview screen does NOT add its own truncation subtitle (that would duplicate
> it). The top bar shows only the name. `truncated`/`totalRows` in the UI state
> stay populated but are not displayed here.

- [ ] **Step 1: Create the screen.** Create `app/src/main/java/com/rmobile/console/ui/preview/PreviewScreen.kt`:

```kotlin
package com.rmobile.console.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.ui.editor.RTableView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    source: String,
    name: String,
    onBack: () -> Unit,
    viewModel: PreviewViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(source, name) { viewModel.load(source, name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.title.isBlank()) name else state.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.TopStart) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                state.table != null -> RTableView(state.table!!, Modifier.fillMaxSize())
                else -> Text("Nothing to preview.")
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles (assembleDebug).**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/rmobile/console/ui/preview/PreviewScreen.kt
git commit -m "app: full-screen PreviewScreen reusing RTableView"
```

---

## Task 7: App — navigation + entry points (Data "View" button, editor workspace chips)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/MainActivity.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`

- [ ] **Step 1: Add the `onPreviewFile` param + "View" button to DataScreen.** In `DataScreen.kt`, add `onPreviewFile: (String) -> Unit` to the signature (after `onInsertFileName`), and add a "View" button before the "Insert" button in each row:

```kotlin
fun DataScreen(
    onBack: () -> Unit,
    onInsertFileName: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    viewModel: DataViewModel = viewModel(),
) {
```
In the row `Button(onClick = { onInsertFileName("\"${file.name}\"") }) { Text("Insert") }`, add immediately before it:
```kotlin
                        Button(onClick = { onPreviewFile(file.name) }) { Text("View") }
```
(Keep the row's existing `Insert` button and `Delete` `IconButton`. Add horizontal spacing consistent with the row — wrap the two buttons in the existing `Row`; if crowding appears on narrow screens, that is acceptable for this task.)

- [ ] **Step 2: Turn the editor workspace line into tappable chips.** In `EditorScreen.kt`:
  - Add `onPreviewObject: (String) -> Unit = {}` to the `EditorScreen` signature.
  - Replace the workspace `Text(...)` block (the `if (uiState.workspaceObjects.isNotEmpty()) { Text( "Workspace: ..." ) }`) with a horizontally scrollable row of chips:

```kotlin
            if (uiState.workspaceObjects.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Workspace:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    uiState.workspaceObjects.forEach { obj ->
                        AssistChip(
                            onClick = { onPreviewObject(obj) },
                            label = { Text(obj) },
                        )
                    }
                }
            }
```
  - Add the needed imports if absent:
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.ui.Alignment
```
(Several of these may already be imported — add only the missing ones.)

- [ ] **Step 3: Wire navigation in MainActivity.** In `MainActivity.kt`:
  - Extend the enum: `private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS, DATA, PREVIEW }`.
  - In `AppRoot`, add preview target state after the `screen` declaration:
```kotlin
    var previewSource by remember { mutableStateOf("file") }
    var previewName by remember { mutableStateOf("") }
```
  - Add `onPreviewObject` to the `EditorScreen(...)` call:
```kotlin
            onPreviewObject = { name ->
                previewSource = "object"; previewName = name; screen = Screen.PREVIEW
            },
```
  - Add `onPreviewFile` to the `DataScreen(...)` call:
```kotlin
            onPreviewFile = { name ->
                previewSource = "file"; previewName = name; screen = Screen.PREVIEW
            },
```
  - Add the `PREVIEW` branch to the `when (screen)`:
```kotlin
        Screen.PREVIEW -> PreviewScreen(
            source = previewSource,
            name = previewName,
            onBack = { screen = if (previewSource == "file") Screen.DATA else Screen.EDITOR },
        )
```
  - Add the import: `import com.rmobile.console.ui.preview.PreviewScreen`.
  - Ensure `getValue`/`setValue`/`mutableStateOf`/`remember` are imported (they already are — `screen` uses them).

- [ ] **Step 4: Build + run the full app test suite.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/rmobile/console/MainActivity.kt app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: navigate to preview from Data 'View' + editor workspace chips"
```

---

## Task 8: Docs + full verification

**Files:**
- Modify: `backend/README.md`, `README.md`, `CLAUDE.md`

- [ ] **Step 1: Document `/preview` in `backend/README.md`.** Add an endpoint entry near the other endpoints (`/data`, `/help`) describing: method `POST /preview`, body `{sessionId?, source: "file"|"object", name}`, that it is **read-only** (no `save.image`, no history), reads a session data file by extension (`.csv`/`.tsv`/`.rds` base R; `.xlsx`/`.parquet` when `readxl`/`arrow` are in the session lib) or `get()`s a workspace object, returns `{table, error, truncated}` where `table` is an `RTable` capped at `R_TABLE_MAX_ROWS`, and that it is in the auth/rate-limit-protected set.

- [ ] **Step 2: Add a feature bullet to `README.md`.** After the "Data import" bullet, add:

```markdown
- **Data viewer** — preview tabular data as a table without writing code: tap
  an uploaded file on the **Data** screen (CSV/TSV/RDS, plus Excel/Parquet when
  `readxl`/`arrow` are installed), or tap a data-frame variable in the editor's
  workspace strip to `View()` it. Opens full-screen, capped at 200 rows.
```

- [ ] **Step 3: Update `CLAUDE.md`.** Add:
  - A `ui/preview/` bullet under the Android app architecture section describing `PreviewScreen`/`PreviewViewModel`/`PreviewUiState` (session-scoped like `DataViewModel`, reuses `RTableView`), reached from the Data screen "View" button and editor workspace chips, driven by `POST /preview`.
  - In the response-contract section, a `/preview` paragraph: request `source`/`name`/nullable `sessionId`; response `table` (nullable `RTable`) / `error` / `truncated`; read-only; joins `is_protected`; models in `data/model/PreviewModels.kt`; reuses `RTable` from `ExecuteModels.kt`; backend reuses the shared `TABLE_EMIT_HELPERS`.
  - A line in "Also built" noting the data viewer.

- [ ] **Step 4: Final verification.**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass.

Run: `"/c/Program Files/R/R-4.5.2/bin/Rscript.exe" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"`
Expected: `parse OK`

- [ ] **Step 5: Commit.**

```bash
git add backend/README.md README.md CLAUDE.md
git commit -m "docs: data viewer (View(df) + file preview)"
```

---

## Notes for the final reviewer

- **Read-only invariant** is the security-relevant property: `/preview` must never call `save.image()`, write `objects.txt`, or add history. The state-unchanged test in Task 2 guards it — confirm it stays.
- **Contract sync:** `PreviewModels.kt` must match `plumber.R`'s `/preview` JSON field-for-field, and the backend must emit unboxed JSON (via `run.R`, already global). `table` is a single object (not an array like `/execute`'s `tables`).
- **Backend green is CI-only** on this machine; the plumber parse-check is a smoke test, not a substitute for the testthat suite.
