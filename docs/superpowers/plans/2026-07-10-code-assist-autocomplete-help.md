# Code Assist (autocomplete + R help) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add app-side autocomplete (a suggestion strip over the keyboard) and on-demand R help (rendered text in a bottom sheet) to the editor, backed by two new read-only backend endpoints.

**Architecture:** Autocomplete is computed entirely in the app by a pure `CompletionOps` over a symbol set (baked base-R names ∪ session workspace objects ∪ installed-package names ∪ a cached `/symbols` index); suggestions are derived off the main thread with a debounced `Flow`. Help is a `POST /help` call rendered with `tools::Rd2txt`, shown in a bottom sheet reached from completion chips, the token under the cursor, and a help-search field. Both new endpoints spawn an isolated `Rscript --vanilla` (like `/execute`) and are auth/rate-limit protected.

**Tech Stack:** R + Plumber (`processx::run`), testthat + httr2 (backend tests); Kotlin, Jetpack Compose, kotlinx.serialization, Retrofit, coroutines/Flow (app); JUnit (app unit tests).

**Reference:** Spec at `docs/superpowers/specs/2026-07-10-code-assist-autocomplete-help-design.md`. Read the response-contract section of `CLAUDE.md` before touching models.

---

## File Structure

**Backend (`backend/`):**
- `plumber.R` — add `SYMBOLS_MAX` config, `GET /symbols`, `POST /help`, and add both paths to `is_protected()`.
- `tests/helper-server.R` — add `get_symbols()` and `post_help()` helpers.
- `tests/test-assist.R` (new) — endpoint tests.
- `README.md` — document the two endpoints.

**App (`app/src/main/java/com/rmobile/console/`):**
- `data/model/AssistModels.kt` (new) — `SymbolsResponse`, `HelpRequest`, `HelpResponse`.
- `data/network/RExecutionApi.kt` — add `symbols()` + `help()`.
- `data/RExecutionRepository.kt` — add `listSymbols()` + `help()`.
- `ui/editor/completion/CompletionOps.kt` (new) — `CompletionContext`, `CompletionOps`, `BaseRSymbols`.
- `ui/editor/EditorTextOps.kt` — add `replaceRange()`.
- `ui/editor/HelpState.kt` (new) — `HelpState` sealed interface.
- `ui/editor/EditorUiState.kt` — add `completionSymbols`, `help`.
- `ui/editor/EditorViewModel.kt` — symbol assembly, `refreshSymbols()`, `showHelp()`, `dismissHelp()`.
- `ui/editor/EditorScreen.kt` — suggestion strip, help sheet, help search, `?` action, refresh triggers.

**App tests (`app/src/test/java/com/rmobile/console/`):**
- `ui/editor/completion/CompletionOpsTest.kt` (new)
- `ui/editor/EditorTextOpsTest.kt` (extend or new)
- `data/RExecutionRepositoryTest.kt` (extend or new)
- `ui/editor/EditorViewModelTest.kt` (extend — update `FakeApi`)
- `ui/packages/PackagesViewModelTest.kt` (update `FakeApi` only — new interface methods)

**Docs:** `CLAUDE.md`, root `README.md`.

---

## Task 1: Backend `GET /symbols`

**Files:**
- Modify: `backend/plumber.R` (config near line 10; `is_protected` line 50; new endpoint after `/packages` ~line 384)
- Modify: `backend/tests/helper-server.R`
- Test: `backend/tests/test-assist.R` (new)

- [ ] **Step 1: Add the test helper**

In `backend/tests/helper-server.R`, add after `get_packages` (near line 81):

```r
get_symbols <- function(server, session_id = NULL, key = NULL) {
  query <- if (!is.null(session_id)) list(sessionId = session_id) else NULL
  api_request(server, "/symbols", query = query, key = key)
}
```

- [ ] **Step 2: Write the failing test**

Create `backend/tests/test-assist.R`:

```r
test_that("/symbols includes base R function names", {
  srv <- local_server()
  res <- get_symbols(srv, session_id = "sym")
  expect_equal(res$status, 200)
  syms <- unlist(res$body$symbols)
  expect_true("mean" %in% syms)
  expect_true("data.frame" %in% syms)
})

test_that("/symbols reflects attached packages and is session-scoped", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))
  ins <- post_install(srv, "praise", session_id = "withpkg")
  skip_if_not(isTRUE(ins$body$installed), "no network")

  run <- post_execute(srv, "library(praise)", session_id = "withpkg")
  expect_true(is.null(run$body$error))

  with_syms <- unlist(get_symbols(srv, session_id = "withpkg")$body$symbols)
  expect_true("praise" %in% with_syms)

  without_syms <- unlist(get_symbols(srv, session_id = "nopkg")$body$symbols)
  expect_false("praise" %in% without_syms)
})
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `Rscript backend/run-tests.R`
Expected: FAIL — `/symbols` returns 404 (endpoint missing), so `res$status` is not 200.

- [ ] **Step 4: Add the config constant**

In `backend/plumber.R`, after `TABLE_MAX_ROWS` (line 10), add:

```r
# Max completion symbols returned by GET /symbols.
SYMBOLS_MAX <- as.integer(Sys.getenv("R_SYMBOLS_MAX", "5000"))
```

- [ ] **Step 5: Protect the route**

In `backend/plumber.R`, change `is_protected` (line 50) to include `/symbols`:

```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy", "/symbols")
```

- [ ] **Step 6: Implement the endpoint**

In `backend/plumber.R`, add after the `/packages` endpoint (after line ~384, before `#* @get /health` if it is later, otherwise at end of file — place it directly after the `/packages` function):

```r
#* Completion symbols for a session: base + recommended + attached-package exports
#* @get /symbols
function(sessionId = "default") {
  session_id <- sanitize_session_id(sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("symbols-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "symbols.R")
  out_path <- file.path(run_dir, "symbols.txt")

  attached_literal <- paste(deparse(DEFAULT_ATTACHED), collapse = "")
  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    sprintf('base_pkgs <- %s', attached_literal),
    sprintf('extra <- if (file.exists(%s)) readLines(%s) else character(0)',
            shQuote(paths$attached), shQuote(paths$attached)),
    'pkgs <- unique(c(base_pkgs, extra[nzchar(extra)]))',
    'syms <- unlist(lapply(pkgs, function(p) tryCatch(getNamespaceExports(p), error = function(e) character(0))))',
    'syms <- unique(syms[grepl("^[A-Za-z.][A-Za-z0-9._]*$", syms)])',
    'syms <- sort(syms)',
    sprintf('if (length(syms) > %d) syms <- syms[seq_len(%d)]', SYMBOLS_MAX, SYMBOLS_MAX),
    sprintf('writeLines(syms, %s)', shQuote(out_path))
  ), script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )
  syms <- if (!inherits(result, "error") && file.exists(out_path)) readLines(out_path) else character(0)
  list(symbols = as.list(syms))
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `Rscript backend/run-tests.R`
Expected: PASS for the two `/symbols` tests (the attached-package test may `skip` if the sandbox has no network — that is acceptable, not a failure).

- [ ] **Step 8: Commit**

```bash
git add backend/plumber.R backend/tests/helper-server.R backend/tests/test-assist.R
git commit -m "backend: GET /symbols completion index (base + attached exports)"
```

---

## Task 2: Backend `POST /help`

**Files:**
- Modify: `backend/plumber.R` (`is_protected` line 50; new endpoint after `/symbols`)
- Modify: `backend/tests/helper-server.R`
- Test: `backend/tests/test-assist.R`

- [ ] **Step 1: Add the test helper**

In `backend/tests/helper-server.R`, add after `get_symbols`:

```r
post_help <- function(server, topic, session_id = NULL, key = NULL) {
  body <- list(topic = topic)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/help", body = body, key = key)
}
```

- [ ] **Step 2: Write the failing tests**

Append to `backend/tests/test-assist.R`:

```r
test_that("/help renders base help to text", {
  srv <- local_server()
  res <- post_help(srv, "mean")
  expect_equal(res$status, 200)
  expect_true(isTRUE(res$body$found))
  expect_equal(res$body$packageName, "base")
  expect_match(res$body$text, "Usage")
})

test_that("/help reports not-found for unknown topics", {
  srv <- local_server()
  res <- post_help(srv, "zzznotarealfn")
  expect_equal(res$status, 200)
  expect_false(isTRUE(res$body$found))
  expect_equal(res$body$text, "")
})

test_that("/help rejects an invalid topic with 400", {
  srv <- local_server()
  res <- api_request(srv, "/help", body = list(topic = "a b"))
  expect_equal(res$status, 400)
  expect_false(isTRUE(res$body$found))
})
```

- [ ] **Step 3: Run to verify failure**

Run: `Rscript backend/run-tests.R`
Expected: FAIL — `/help` returns 404, so `res$status` is not 200/400 as asserted.

- [ ] **Step 4: Protect the route**

In `backend/plumber.R`, update `is_protected` (line 50) to also include `/help`:

```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy", "/symbols", "/help")
```

- [ ] **Step 5: Implement the endpoint**

In `backend/plumber.R`, add directly after the `/symbols` endpoint:

```r
#* Render an R help topic to text for a session
#* @post /help
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  topic <- body$topic
  if (is.null(topic) || !is.character(topic) || length(topic) != 1 || !grepl("^[A-Za-z0-9._]+$", topic)) {
    res$status <- 400
    return(list(
      topic = if (is.character(topic) && length(topic) == 1) topic else "",
      packageName = NULL, text = "", found = FALSE
    ))
  }

  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("help-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "help.R")
  out_text <- file.path(run_dir, "help.txt")
  out_pkg <- file.path(run_dir, "pkg.txt")

  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    sprintf('topic <- %s', shQuote(topic)),
    # Resolve help for a topic held in a variable via substitute(); then fetch the
    # parsed Rd with utils:::.getHelpFile (the API the help system itself uses) and
    # render it to text with tools::Rd2txt.
    'h <- tryCatch(eval(substitute(utils::help(TT), list(TT = as.name(topic)))), error = function(e) NULL)',
    'if (!is.null(h) && length(h) >= 1) {',
    '  path <- as.character(h)[1]',
    '  tryCatch({',
    '    rd <- utils:::.getHelpFile(path)',
    sprintf('    tools::Rd2txt(rd, out = %s)', shQuote(out_text)),
    sprintf('    writeLines(basename(dirname(dirname(path))), %s)', shQuote(out_pkg)),
    '  }, error = function(e) NULL)',
    '}'
  ), script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )

  text <- if (!inherits(result, "error") && file.exists(out_text)) {
    paste(readLines(out_text, warn = FALSE), collapse = "\n")
  } else ""
  pkg <- if (file.exists(out_pkg)) readLines(out_pkg, warn = FALSE)[1] else NULL

  list(topic = topic, packageName = pkg, text = text, found = nzchar(text))
}
```

- [ ] **Step 6: Run to verify pass**

Run: `Rscript backend/run-tests.R`
Expected: PASS for all three `/help` tests (`mean` renders with `packageName == "base"` and text containing `Usage`; unknown → `found == FALSE`; `"a b"` → 400).

- [ ] **Step 7: Commit**

```bash
git add backend/plumber.R backend/tests/helper-server.R backend/tests/test-assist.R
git commit -m "backend: POST /help renders Rd help to text"
```

---

## Task 3: Backend docs

**Files:**
- Modify: `backend/README.md`

- [ ] **Step 1: Document the endpoints**

In `backend/README.md`, in the endpoints section (near where `/packages` / `/install` are documented), add:

```markdown
### `GET /symbols?sessionId=`

Returns completion symbol names for a session — the exported names of base +
recommended packages plus every package the session has `library()`-d (read from
the session's recorded attached list). Runs in an isolated `Rscript --vanilla`
with the session's library on `.libPaths()`. Response:

```json
{ "symbols": ["abbreviate", "abline", "abs", "..."] }
```

Sorted, de-duplicated, capped at `R_SYMBOLS_MAX` (default 5000). Read-only.
Protected (auth + rate limit when configured).

### `POST /help`

Renders an R help topic to plain text with `tools::Rd2txt`. Request:

```json
{ "topic": "mean", "sessionId": "proj-123" }
```

`topic` must match `^[A-Za-z0-9._]+$` (else HTTP 400). Resolves across base + the
session's installed/attached packages. Response:

```json
{ "topic": "mean", "packageName": "base", "text": "mean {base}\n...", "found": true }
```

`found` is `false` with empty `text` when no topic matches. Read-only. Protected.
```

Also add `/symbols` and `/help` to the list of protected endpoints in the
security section.

- [ ] **Step 2: Commit**

```bash
git add backend/README.md
git commit -m "docs: document /symbols and /help endpoints"
```

---

## Task 4: App models, API, repository

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/model/AssistModels.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`
- Modify (fakes): `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`, `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`, and any other `RExecutionApi` implementation (find with grep).
- Test: `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt` (create if absent, else extend)

- [ ] **Step 1: Create the models**

Create `app/src/main/java/com/rmobile/console/data/model/AssistModels.kt`:

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SymbolsResponse(
    val symbols: List<String> = emptyList(),
)

@Serializable
data class HelpRequest(
    val topic: String,
    val sessionId: String? = null,
)

@Serializable
data class HelpResponse(
    val topic: String = "",
    val packageName: String? = null,
    val text: String = "",
    val found: Boolean = false,
)
```

- [ ] **Step 2: Add API methods**

In `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt`, add imports for `HelpRequest`, `HelpResponse`, `SymbolsResponse`, and these methods inside the interface:

```kotlin
    @GET("symbols")
    suspend fun symbols(@Query("sessionId") sessionId: String): SymbolsResponse

    @POST("help")
    suspend fun help(@Body request: HelpRequest): HelpResponse
```

- [ ] **Step 3: Write the failing repository test**

Create (or extend) `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt`. If creating new, include a minimal fake implementing the whole interface; if the file exists, add these two tests and extend its fake with `symbols`/`help`. New-file version:

```kotlin
package com.rmobile.console.data

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
import com.rmobile.console.data.network.RExecutionApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RExecutionRepositoryTest {

    private class FakeApi(
        var symbolsResponse: SymbolsResponse = SymbolsResponse(listOf("mean", "median")),
        var helpResponse: HelpResponse = HelpResponse(topic = "mean", found = true),
        var lastSymbolsSession: String? = null,
        var lastHelp: HelpRequest? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String): SymbolsResponse {
            lastSymbolsSession = sessionId; return symbolsResponse
        }
        override suspend fun help(request: HelpRequest): HelpResponse {
            lastHelp = request; return helpResponse
        }
    }

    @Test
    fun `listSymbols passes the session and returns names`() = runTest {
        val api = FakeApi()
        val result = RExecutionRepository(api).listSymbols("proj-1")
        assertEquals("proj-1", api.lastSymbolsSession)
        assertEquals(listOf("mean", "median"), result.getOrNull())
    }

    @Test
    fun `help passes topic and session`() = runTest {
        val api = FakeApi()
        RExecutionRepository(api).help("lm", "proj-2")
        assertEquals("lm", api.lastHelp!!.topic)
        assertEquals("proj-2", api.lastHelp!!.sessionId)
        assertTrue(true)
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.RExecutionRepositoryTest"`
Expected: COMPILE FAIL — `listSymbols`/`help` do not exist on the repository yet.

- [ ] **Step 5: Add repository methods**

In `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`, add imports for `HelpRequest`, `HelpResponse`, `SymbolsResponse` and these methods (after `importLegacy`):

```kotlin
    suspend fun listSymbols(sessionId: String = DEFAULT_SESSION_ID): Result<List<String>> =
        runCatching { api.symbols(sessionId).symbols }

    suspend fun help(topic: String, sessionId: String = DEFAULT_SESSION_ID): Result<HelpResponse> =
        runCatching { api.help(HelpRequest(topic, sessionId)) }
```

- [ ] **Step 6: Update all other fakes so the app compiles**

Add the two methods to every other `RExecutionApi` implementation. Find them:

Run: `./gradlew :app:testDebugUnitTest` (it will fail to compile and name the fakes), or grep: search for `: RExecutionApi` under `app/src/test`.

Known fakes to update — add to each:

```kotlin
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) =
            com.rmobile.console.data.model.HelpResponse()
```

- `EditorViewModelTest.FakeApi` (will be extended further in Task 7 — this minimal stub is fine for now).
- `PackagesViewModelTest.FakeApi`.

- [ ] **Step 7: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all modules compile; `RExecutionRepositoryTest` green).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/model/AssistModels.kt \
        app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt \
        app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt \
        app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt \
        app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "app: assist models, API, repository (symbols + help)"
```

---

## Task 5: Completion engine (pure)

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/editor/completion/CompletionOps.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/completion/CompletionOpsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/ui/editor/completion/CompletionOpsTest.kt`:

```kotlin
package com.rmobile.console.ui.editor.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionOpsTest {

    @Test
    fun `tokenRange finds the identifier ending at the cursor`() {
        val ctx = CompletionContext("x <- mea", 8) // cursor at end of "mea"
        assertEquals(5 until 8, CompletionOps.tokenRange(ctx))
        assertEquals("mea", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `tokenRange handles dotted names`() {
        val ctx = CompletionContext("read.cs", 7)
        assertEquals("read.cs", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `tokenRange is null at a boundary`() {
        assertNull(CompletionOps.tokenRange(CompletionContext("mean(", 5)))
        assertNull(CompletionOps.tokenRange(CompletionContext("a + ", 4)))
    }

    @Test
    fun `tokenRange ignores text after the cursor`() {
        val ctx = CompletionContext("means", 3) // cursor after "mea"
        assertEquals("mea", CompletionOps.currentPrefix(ctx))
    }

    @Test
    fun `suggest ranks exact-prefix before alphabetical and is case-insensitive`() {
        val symbols = listOf("median", "Mean", "mean", "meanX", "sum")
        val out = CompletionOps.suggest("mea", symbols, limit = 10)
        // exact case-prefix ("mean","meanX","median") ranked before "Mean"; alon within groups
        assertEquals("mean", out[0])
        assertTrue(out.contains("Mean"))
        assertTrue(out.indexOf("mean") < out.indexOf("Mean"))
    }

    @Test
    fun `suggest caps, dedups, and empty prefix yields nothing`() {
        val symbols = listOf("aa", "aa", "ab", "ac", "ad")
        assertEquals(listOf("aa", "ab"), CompletionOps.suggest("a", symbols, limit = 2))
        assertTrue(CompletionOps.suggest("", symbols, limit = 5).isEmpty())
        assertTrue(CompletionOps.suggest("   ", symbols, limit = 5).isEmpty())
    }

    @Test
    fun `BaseRSymbols contains common names`() {
        assertTrue(BaseRSymbols.NAMES.contains("mean"))
        assertTrue(BaseRSymbols.NAMES.contains("data.frame"))
        assertTrue(BaseRSymbols.NAMES.contains("library"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.completion.CompletionOpsTest"`
Expected: COMPILE FAIL — `CompletionContext`/`CompletionOps`/`BaseRSymbols` do not exist.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/rmobile/console/ui/editor/completion/CompletionOps.kt`:

```kotlin
package com.rmobile.console.ui.editor.completion

/**
 * Editor state a completion is computed against. The MVP only reads the identifier
 * token ending at [cursor], but shaping the input as a context (not a bare prefix)
 * is the seam a future call-stack analyzer extends without changing call sites.
 */
data class CompletionContext(val text: String, val cursor: Int)

/** Pure completion logic — no Compose/Android types, so it is unit-testable. */
object CompletionOps {

    /** The R identifier token ([A-Za-z.][A-Za-z0-9._]*) ending at the cursor, or null. */
    fun tokenRange(ctx: CompletionContext): IntRange? {
        val c = ctx.cursor.coerceIn(0, ctx.text.length)
        var start = c
        while (start > 0 && isIdentChar(ctx.text[start - 1])) start--
        if (start == c) return null
        if (!isIdentStart(ctx.text[start])) return null
        return start until c
    }

    fun currentPrefix(ctx: CompletionContext): String {
        val r = tokenRange(ctx) ?: return ""
        return ctx.text.substring(r.first, r.last + 1)
    }

    /**
     * Case-insensitive prefix matches, ranked case-sensitive-exact-prefix first then
     * alphabetical, de-duplicated and capped at [limit]. Blank prefix → empty.
     */
    fun suggest(prefix: String, symbols: List<String>, limit: Int): List<String> {
        if (prefix.isBlank()) return emptyList()
        val lower = prefix.lowercase()
        return symbols.asSequence()
            .filter { it.lowercase().startsWith(lower) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.startsWith(prefix) }.thenBy { it })
            .take(limit)
            .toList()
    }

    private fun isIdentStart(ch: Char) = ch.isLetter() || ch == '.'
    private fun isIdentChar(ch: Char) = ch.isLetterOrDigit() || ch == '.' || ch == '_'
}

/** A curated (non-exhaustive) list of common base-R names; GET /symbols enriches it. */
object BaseRSymbols {
    val NAMES: List<String> = listOf(
        "abs", "all", "any", "apply", "as.character", "as.data.frame", "as.factor",
        "as.integer", "as.numeric", "as.vector", "attr", "attributes", "c", "cat",
        "cbind", "ceiling", "class", "colnames", "colSums", "colMeans", "cor", "cumsum",
        "data.frame", "diff", "dim", "dimnames", "do.call", "exp", "factor", "file",
        "filter", "floor", "for", "function", "gsub", "head", "identical", "if", "ifelse",
        "is.na", "is.null", "lapply", "length", "levels", "library", "list", "lm", "log",
        "log10", "ls", "map", "match", "matrix", "max", "mean", "median", "merge", "min",
        "mode", "names", "nchar", "ncol", "nrow", "order", "paste", "paste0", "plot",
        "print", "prod", "quantile", "range", "rbind", "read.csv", "readLines", "readRDS",
        "rep", "require", "return", "rev", "rnorm", "round", "rowSums", "rowMeans",
        "rownames", "sapply", "sd", "seq", "seq_along", "seq_len", "setNames", "setdiff",
        "sort", "split", "sprintf", "sqrt", "str", "strsplit", "sub", "subset", "substr",
        "sum", "summary", "t", "table", "tail", "tapply", "tolower", "toupper", "trimws",
        "unique", "unlist", "vapply", "var", "vector", "which", "while", "write.csv",
        "writeLines", "TRUE", "FALSE", "NULL", "NA", "Inf", "NaN",
    )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.completion.CompletionOpsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/completion/CompletionOps.kt \
        app/src/test/java/com/rmobile/console/ui/editor/completion/CompletionOpsTest.kt
git commit -m "app: pure CompletionOps + BaseRSymbols"
```

---

## Task 6: `EditorTextOps.replaceRange`

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorTextOps.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorTextOpsTest.kt` (create if absent, else extend)

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/rmobile/console/ui/editor/EditorTextOpsTest.kt` (create the file with this content if it does not exist):

```kotlin
package com.rmobile.console.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorTextOpsReplaceRangeTest {

    @Test
    fun `replaceRange swaps the token and places the cursor after it`() {
        val result = replaceRange("x <- mea", 5 until 8, "mean")
        assertEquals("x <- mean", result.text)
        assertEquals(9, result.cursor) // just after "mean"
    }

    @Test
    fun `replaceRange works mid-string`() {
        val result = replaceRange("a + su + b", 4 until 6, "sum")
        assertEquals("a + sum + b", result.text)
        assertEquals(7, result.cursor)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorTextOpsReplaceRangeTest"`
Expected: COMPILE FAIL — `replaceRange` does not exist.

- [ ] **Step 3: Implement**

Add to `app/src/main/java/com/rmobile/console/ui/editor/EditorTextOps.kt`:

```kotlin
/**
 * Replaces the half-open token [range] in [text] with [replacement], returning the
 * new text and the caret position just after the inserted text. Pure.
 */
fun replaceRange(text: String, range: IntRange, replacement: String): InsertResult {
    val start = range.first.coerceIn(0, text.length)
    val end = (range.last + 1).coerceIn(start, text.length)
    val newText = text.replaceRange(start, end, replacement)
    return InsertResult(newText, start + replacement.length)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorTextOpsReplaceRangeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorTextOps.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorTextOpsTest.kt
git commit -m "app: EditorTextOps.replaceRange for completion inserts"
```

---

## Task 7: ViewModel — symbol set, help state, refresh

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/editor/HelpState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Create HelpState**

Create `app/src/main/java/com/rmobile/console/ui/editor/HelpState.kt`:

```kotlin
package com.rmobile.console.ui.editor

import com.rmobile.console.data.model.HelpResponse

/** UI state for the R-help bottom sheet. A single frame (see spec's back-stack seam). */
sealed interface HelpState {
    data class Loading(val topic: String) : HelpState
    data class Loaded(val response: HelpResponse) : HelpState
    data class NotFound(val topic: String) : HelpState
    data class Error(val topic: String, val message: String) : HelpState
}
```

- [ ] **Step 2: Extend EditorUiState**

In `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt`, add two fields to the `data class EditorUiState`:

```kotlin
    val completionSymbols: List<String> = emptyList(),
    val help: HelpState? = null,
```

- [ ] **Step 3: Write the failing ViewModel tests**

In `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`, first extend `FakeApi` to record and serve symbols/help. Replace the two stub overrides added in Task 4 with:

```kotlin
        var symbolsResponse: com.rmobile.console.data.model.SymbolsResponse =
            com.rmobile.console.data.model.SymbolsResponse(listOf("mean", "median"))
        var helpResponse: com.rmobile.console.data.model.HelpResponse =
            com.rmobile.console.data.model.HelpResponse(topic = "mean", packageName = "base", text = "Usage", found = true)
        var lastHelp: com.rmobile.console.data.model.HelpRequest? = null

        override suspend fun symbols(sessionId: String) = symbolsResponse
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest): com.rmobile.console.data.model.HelpResponse {
            lastHelp = request; return helpResponse
        }
```

(Declare `symbolsResponse`/`helpResponse`/`lastHelp` as properties of `FakeApi`.)

Then add these tests:

```kotlin
    @Test
    fun `symbol set includes base names and refreshed index`() = runTest {
        val api = FakeApi()
        api.symbolsResponse = com.rmobile.console.data.model.SymbolsResponse(listOf("dplyr_fn"))
        val vm = viewModel(api = api)
        advanceUntilIdle() // init calls refreshSymbols()
        val syms = vm.uiState.value.completionSymbols
        assertTrue(syms.contains("mean"))       // baked base
        assertTrue(syms.contains("dplyr_fn"))   // refreshed index
    }

    @Test
    fun `run adds workspace objects to the symbol set`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("my_df")))
        val vm = viewModel(api = api)
        vm.onCodeChanged("my_df <- 1")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completionSymbols.contains("my_df"))
    }

    @Test
    fun `showHelp transitions Loading to Loaded`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("mean")
        advanceUntilIdle()
        val help = vm.uiState.value.help
        assertTrue(help is HelpState.Loaded)
        assertEquals("mean", (help as HelpState.Loaded).response.topic)
    }

    @Test
    fun `showHelp maps not-found result`() = runTest {
        val api = FakeApi()
        api.helpResponse = com.rmobile.console.data.model.HelpResponse(topic = "zzz", found = false)
        val vm = viewModel(api = api)
        vm.showHelp("zzz")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.help is HelpState.NotFound)
    }

    @Test
    fun `dismissHelp clears the sheet`() = runTest {
        val vm = viewModel(api = FakeApi())
        vm.showHelp("mean")
        advanceUntilIdle()
        vm.dismissHelp()
        assertEquals(null, vm.uiState.value.help)
    }
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: COMPILE FAIL — `showHelp`/`dismissHelp`/`completionSymbols` symbol-assembly not implemented.

- [ ] **Step 5: Implement in EditorViewModel**

In `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`:

Add imports:

```kotlin
import com.rmobile.console.ui.editor.completion.BaseRSymbols
```

Add private fields (after the `_uiState`/`uiState` declarations, inside the class):

```kotlin
    private var symbolIndex: List<String> = emptyList()
    private var packageNames: List<String> = emptyList()
```

At the end of `init { ... }` (after `uiState = _uiState.asStateFlow()`), add:

```kotlin
        refreshSymbols()
```

Add these methods (e.g. in a new `// --- code assist ---` section):

```kotlin
    private fun recomputeSymbols() {
        val assembled = (BaseRSymbols.NAMES + _uiState.value.workspaceObjects + packageNames + symbolIndex)
            .distinct()
        _uiState.update { it.copy(completionSymbols = assembled) }
    }

    /** Refresh the cached symbol index + package names for the active project's session. */
    fun refreshSymbols() {
        val session = ProjectSession.of(_uiState.value.project)
        viewModelScope.launch {
            symbolIndex = repository.listSymbols(session).getOrNull().orEmpty()
            packageNames = repository.listPackages(session).getOrNull()?.packages.orEmpty()
            recomputeSymbols()
        }
    }

    fun showHelp(topic: String) {
        val t = topic.trim()
        if (t.isEmpty()) return
        val session = ProjectSession.of(_uiState.value.project)
        _uiState.update { it.copy(help = HelpState.Loading(t)) }
        viewModelScope.launch {
            repository.help(t, session)
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(help = if (resp.found) HelpState.Loaded(resp) else HelpState.NotFound(t))
                    }
                }
                .onFailure { th ->
                    _uiState.update { it.copy(help = HelpState.Error(t, th.message ?: "Failed to load help.")) }
                }
        }
    }

    fun dismissHelp() = _uiState.update { it.copy(help = null) }
```

In `runCode()`, in the `.onSuccess` block, after the existing `_uiState.update { ... }` that sets `workspaceObjects`, add a recompute + refresh so new workspace objects appear immediately and attached-package exports refresh:

```kotlin
                    recomputeSymbols()
                    refreshSymbols()
```

In `openProject(id)`, after the `_uiState.update { ... }` call, add:

```kotlin
        refreshSymbols()
```

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: PASS (all existing + new tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/HelpState.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: EditorViewModel symbol set + help state + refresh"
```

---

## Task 8: Editor UI — suggestion strip

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`

This task is Compose UI — verified by compilation + Lint + manual device check (no unit test; the meaty logic is already tested in `CompletionOps`).

- [ ] **Step 1: Add imports**

In `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`, add:

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.snapshotFlow
import com.rmobile.console.ui.editor.completion.CompletionContext
import com.rmobile.console.ui.editor.completion.CompletionOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
```

- [ ] **Step 2: Derive suggestions off the main thread**

Inside `EditorScreen`, after the `field`/`LaunchedEffect(uiState.code)` block (near line 126), add:

```kotlin
    // Suggestions computed off the UI thread, debounced, from the token under the
    // cursor against the current symbol set. Re-derives when the symbol set changes.
    var strip by remember { mutableStateOf(StripState(emptyList(), "")) }
    val symbols = uiState.completionSymbols
    @OptIn(FlowPreview::class)
    LaunchedEffect(symbols) {
        snapshotFlow { field }
            .debounce(120)
            .map { f ->
                if (!f.selection.collapsed) StripState(emptyList(), "")
                else {
                    val ctx = CompletionContext(f.text, f.selection.end)
                    val prefix = CompletionOps.currentPrefix(ctx)
                    StripState(CompletionOps.suggest(prefix, symbols, limit = 30), prefix)
                }
            }
            .flowOn(Dispatchers.Default)
            .collect { strip = it }
    }

    val applyCompletion: (String) -> Unit = { symbol ->
        val ctx = CompletionContext(field.text, field.selection.end)
        CompletionOps.tokenRange(ctx)?.let { range ->
            val result = replaceRange(field.text, range, symbol)
            field = TextFieldValue(result.text, TextRange(result.cursor))
            viewModel.onCodeChanged(result.text)
        }
    }
```

- [ ] **Step 3: Add the StripState holder and the SuggestionStrip composable**

At the bottom of the file (near the other private composables), add:

```kotlin
private data class StripState(val suggestions: List<String>, val token: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionStrip(
    strip: StripState,
    onPick: (String) -> Unit,
    onHelp: (String) -> Unit,
) {
    if (strip.suggestions.isEmpty() && strip.token.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (strip.token.isNotEmpty()) {
            item {
                AssistChip(
                    onClick = { onHelp(strip.token) },
                    label = { Text("? ${strip.token}") },
                )
            }
        }
        items(strip.suggestions) { symbol ->
            AssistChip(
                onClick = { onPick(symbol) },
                modifier = Modifier.combinedClickable(
                    onClick = { onPick(symbol) },
                    onLongClick = { onHelp(symbol) },
                ),
                label = { Text(symbol) },
            )
        }
    }
}
```

Add imports for `AssistChip`, `items` (`androidx.compose.foundation.lazy.items`), and confirm `LazyRow`, `Arrangement`, `dp`, `Text`, `Modifier`, `fillMaxWidth` are already imported (they are, per existing usage).

> Note: `AssistChip`'s own `onClick` plus a `combinedClickable` modifier both fire tap → `onPick`; long-press → `onHelp`. This is intentional so tap works even where the modifier's onClick is consumed.

- [ ] **Step 4: Render the strip above the operator bar**

Locate the quick-insert operator bar row in the editor `Column` (the `LazyRow` of `quickInsertTokens`, around line 346 / the `LazyRow` at 693). Immediately **before** that operator bar, insert:

```kotlin
            SuggestionStrip(
                strip = strip,
                onPick = applyCompletion,
                onHelp = viewModel::showHelp,
            )
```

- [ ] **Step 5: Build to verify compilation + lint**

Run: `./gradlew :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL (SDK required — run locally/CI, not in the remote sandbox).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: editor suggestion strip (debounced, off-main-thread)"
```

---

## Task 9: Editor UI — help sheet, help search, refresh wiring

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
```

(Confirm `AlertDialog`, `OutlinedTextField`, `TextButton`, `Icons`, `IconButton` are imported; add any missing.)

- [ ] **Step 2: Add a help-search dialog state and a `?` top-bar action**

Add near the other `remember { mutableStateOf }` flags (line ~114):

```kotlin
    var showHelpSearch by remember { mutableStateOf(false) }
```

In the `TopAppBar` `actions` block (before the `MoreVert` IconButton, ~line 162), add:

```kotlin
                    IconButton(onClick = { showHelpSearch = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "R help")
                    }
```

If `Icons.AutoMirrored.Filled.HelpOutline` is unavailable, use `Icons.Default.Info` with the same `contentDescription`.

- [ ] **Step 3: Render the help search dialog and help sheet**

At the end of `EditorScreen`'s content (alongside the other dialogs/sheets like `showHistory`/`showSaved`), add:

```kotlin
        if (showHelpSearch) {
            var query by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showHelpSearch = false },
                title = { Text("R help") },
                text = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Function or topic") },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showHelpSearch = false; viewModel.showHelp(query) },
                        enabled = query.isNotBlank(),
                    ) { Text("Open") }
                },
                dismissButton = {
                    TextButton(onClick = { showHelpSearch = false }) { Text("Cancel") }
                },
            )
        }

        uiState.help?.let { help ->
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = viewModel::dismissHelp,
                sheetState = sheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (help) {
                        is HelpState.Loading -> {
                            Text("Loading help for ${help.topic}…", style = MaterialTheme.typography.titleMedium)
                            CircularProgressIndicator()
                        }
                        is HelpState.NotFound ->
                            Text("No help found for '${help.topic}'.", style = MaterialTheme.typography.bodyLarge)
                        is HelpState.Error ->
                            Text(help.message, color = MaterialTheme.colorScheme.error)
                        is HelpState.Loaded -> {
                            val title = help.response.packageName
                                ?.let { "${help.response.topic} {$it}" } ?: help.response.topic
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            SelectionContainer {
                                Text(
                                    text = help.response.text,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 480.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
        }
```

Add imports for `heightIn` (`androidx.compose.foundation.layout.heightIn`) if missing.

- [ ] **Step 4: Refresh symbols when returning from Packages**

The Packages screen can change installed packages (install/uninstall), which
changes both package names and attached exports. `MainActivity.AppRoot` hoists the
shared `editorViewModel` and switches back from Packages via the `PackagesScreen`
`onBack` lambda (`app/src/main/java/com/rmobile/console/MainActivity.kt:53`). Change
that lambda to refresh on return:

```kotlin
        Screen.PACKAGES -> PackagesScreen(onBack = {
            screen = Screen.EDITOR
            editorViewModel.refreshSymbols()
        })
```

(`editorViewModel` is already in scope in `AppRoot`.)

- [ ] **Step 5: Build to verify compilation + lint**

Run: `./gradlew :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt \
        app/src/main/java/com/rmobile/console/MainActivity.kt
git commit -m "app: R help sheet + help search + refresh on packages return"
```

---

## Task 10: Docs + full verification

**Files:**
- Modify: `CLAUDE.md`, root `README.md`

- [ ] **Step 1: Update CLAUDE.md**

In `CLAUDE.md`:
- In the `ui/editor/` description, note the suggestion strip + `CompletionOps`/`BaseRSymbols` (pure, tested) and the R-help bottom sheet + help search.
- In the "Response contract" section, add: `GET /symbols?sessionId=` → `SymbolsResponse { symbols }`; `POST /help` → `HelpRequest { topic, sessionId? }` / `HelpResponse { topic, packageName?, text, found }`; note both are in `is_protected()` and must emit unboxed JSON.
- In "Current scope / what's built", add a line for code assist (autocomplete + R help).

- [ ] **Step 2: Update root README**

Add a feature line: "Code assist — autocomplete (suggestion strip) and R help (`?fn`) rendered in-app."

- [ ] **Step 3: Run the full app unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all modules).

- [ ] **Step 4: Run the backend test suite**

Run: `Rscript backend/run-tests.R`
Expected: PASS (network-gated assertions may `skip`).

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: code assist (autocomplete + R help)"
```

---

## Notes for the implementer

- **DRY/YAGNI:** Do not add auto-`()`, fuzzy matching, argument hints, or a persisted index — they are out of scope with documented seams in the spec. Insert the plain identifier.
- **Unboxed JSON:** `run.R` sets an unboxed serializer, so `SymbolsResponse.symbols` uses `as.list()` on the backend to stay an array; `HelpResponse` scalar fields deserialize as-is. Don't wrap scalars.
- **Protected endpoints:** `/symbols` and `/help` are in `is_protected()`, so the app's `HostSelectionInterceptor` (which already attaches `X-API-Key` to every request) covers them; no app-side auth change is needed.
- **Backend internal API:** `utils:::.getHelpFile` is the internal the R help system itself uses to fetch a parsed Rd for a help path; it has been stable for years. If a future R version changes it, that shows up as `/help` returning `found=false` (the tests catch it).
- **Do not** run `/code-review ultra` — it is user-triggered/billed.
```
