# Multi-session (per-project R environments) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every project its own isolated R backend session — its own workspace and its own installed-package library — keyed by a stable `sessionId = "proj-<id>"`, with an explicit legacy-package import.

**Architecture:** The backend's per-session directory (`/data/sessions/<id>/`) gains an `rlib` subdir that becomes that session's package library, prepended to `.libPaths()` for runs and targeted by `/install`/`/uninstall`/`/packages` (now session-scoped). The old shared `/data/rlib` is kept mounted read-only as a "legacy source" that `POST /import-legacy` copies into a session on request. The app derives each project's session from its stable id and threads it through every backend call; the Packages screen operates on the active project's session and offers the legacy import.

**Tech Stack:** R + Plumber (backend), testthat + httr2 (backend tests), Kotlin + Jetpack Compose + Retrofit + kotlinx.serialization (app), JUnit (app tests).

---

## File Structure

**Backend:**
- Modify `backend/plumber.R` — per-session rlib, session-scoped install/uninstall/packages, `/reset` purge flag, new `/import-legacy`, `LEGACY_PKG_LIB`.
- Modify `backend/docker-compose.yml` — mount `r_rlib` read-only.
- Create `backend/tests/test-session-libs.R` — isolation, purge, import-legacy.
- Modify `backend/README.md` — document the new session-scoped package model.

**App:**
- Create `app/src/main/java/com/rmobile/console/data/project/ProjectSession.kt` — pure `sessionId` derivation.
- Create `app/src/test/java/com/rmobile/console/data/project/ProjectSessionTest.kt`.
- Modify `app/src/main/java/com/rmobile/console/data/model/PackageModels.kt` — `sessionId` on install/uninstall requests, `ImportLegacyRequest`/`ImportLegacyResponse`.
- Modify `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt` — `@Query` on packages, `importLegacy`.
- Modify `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt` — thread `sessionId`/`purgePackages`.
- Modify `app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt` — `purgePackages` on `ResetRequest`.
- Modify `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt` — run/reset/deleteProject session wiring.
- Modify `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt` + `PackagesUiState.kt` — active session + import.
- Modify `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt` — header + import button.
- Modify the test fakes in `app/src/test/...` that implement `RExecutionApi`.
- Modify `CLAUDE.md`, `README.md`.

---

## Task 1: Backend — per-session package library

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Add per-session `rlib` and rename the shared lib to legacy**

In `backend/plumber.R`, change `session_paths` to include `rlib`:

```r
session_paths <- function(session_id) {
  dir <- file.path(SESSION_DIR, session_id)
  list(
    dir = dir,
    workspace = file.path(dir, "workspace.RData"),
    attached = file.path(dir, "attached.txt"),
    rlib = file.path(dir, "rlib")
  )
}
```

Change the shared-library declaration (near line 43) so the old path is a
read-only *legacy source*, not an install target:

```r
# Legacy shared library from before per-session libraries. Read-only source for
# POST /import-legacy; nothing is installed here anymore.
LEGACY_PKG_LIB <- Sys.getenv("R_PKG_LIB", "/data/rlib")
INSTALL_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_INSTALL_TIMEOUT_SECONDS", "300"))
CRAN_REPO <- Sys.getenv("R_CRAN_REPO", "https://packagemanager.posit.co/cran/__linux__/jammy/latest")
```

Add `/import-legacy` to the protected set:

```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy")
```

- [ ] **Step 2: Point the run wrapper at the session rlib**

In the `/execute` handler, after `dir.create(paths$dir, ...)` (around line 159), also create the session rlib:

```r
  paths <- session_paths(session_id)
  dir.create(paths$dir, recursive = TRUE, showWarnings = FALSE)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)
```

Change the first wrapped line (currently `shQuote(PKG_LIB)`) to use the session rlib:

```r
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
```

- [ ] **Step 3: Session-scope `/install`**

Replace the `/install` handler body's library references. Read `sessionId` from
the body and target that session's rlib:

```r
#* Install a CRAN package into the active session's library
#* @post /install
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  pkg <- body$package
  if (is.null(pkg) || !is.character(pkg) || length(pkg) != 1 || !grepl("^[A-Za-z0-9._]+$", pkg)) {
    res$status <- 400
    return(list(stdout = "", stderr = "", error = "Invalid or missing 'package' name.",
                timedOut = FALSE, installed = FALSE, systemRequirements = NULL))
  }
  session_id <- sanitize_session_id(body$sessionId)
  lib <- session_paths(session_id)$rlib
  dir.create(lib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("install-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "install.R")

  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(lib)),
    'options(HTTPUserAgent = sprintf("R/%s R (%s)", getRversion(), paste(getRversion(), R.version["platform"], R.version["arch"], R.version["os"])))',
    sprintf('install.packages(%s, repos = %s, lib = %s)', shQuote(pkg), shQuote(CRAN_REPO), shQuote(lib))
  ), script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = INSTALL_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )
  if (inherits(result, "error")) {
    timed_out <- grepl("timed out", conditionMessage(result), ignore.case = TRUE)
    return(list(stdout = "", stderr = conditionMessage(result),
                error = if (timed_out) sprintf("Install timed out after %ss.", INSTALL_TIMEOUT_SECONDS) else "Install failed to start.",
                timedOut = timed_out, installed = FALSE, systemRequirements = NULL))
  }

  installed <- pkg %in% rownames(installed.packages(lib.loc = lib))

  sysreqs <- NULL
  if (!installed && requireNamespace("remotes", quietly = TRUE)) {
    sysreqs <- tryCatch({
      reqs <- remotes::system_requirements("ubuntu", "22.04", package = pkg)
      if (length(reqs)) paste(reqs, collapse = "\n") else NULL
    }, error = function(e) NULL)
  }

  list(
    stdout = result$stdout,
    stderr = result$stderr,
    error = if (!installed) sprintf("Package '%s' was not installed.", pkg) else NULL,
    timedOut = FALSE,
    installed = installed,
    systemRequirements = sysreqs
  )
}
```

- [ ] **Step 4: Session-scope `/uninstall`**

```r
#* Uninstall a package from the active session's library
#* @post /uninstall
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  pkg <- body$package
  if (is.null(pkg) || !is.character(pkg) || length(pkg) != 1 || !grepl("^[A-Za-z0-9._]+$", pkg)) {
    res$status <- 400
    return(list(removed = FALSE, error = "Invalid or missing 'package' name."))
  }
  lib <- session_paths(sanitize_session_id(body$sessionId))$rlib
  before <- pkg %in% rownames(installed.packages(lib.loc = lib))
  err <- tryCatch({
    if (before) suppressWarnings(remove.packages(pkg, lib = lib))
    NULL
  }, error = function(e) conditionMessage(e))
  after <- pkg %in% rownames(installed.packages(lib.loc = lib))
  list(removed = before && !after, error = err)
}
```

- [ ] **Step 5: Session-scope `GET /packages`**

Plumber maps query params to named function args. Give `/packages` a
`sessionId` arg (default `"default"`):

```r
#* List user-installed packages in a session's library
#* @get /packages
function(sessionId = "default") {
  lib <- session_paths(sanitize_session_id(sessionId))$rlib
  pkgs <- tryCatch(rownames(installed.packages(lib.loc = lib)), error = function(e) NULL)
  if (is.null(pkgs)) pkgs <- character(0)
  list(packages = as.list(pkgs))
}
```

- [ ] **Step 6: Verify the file still parses**

Run: `Rscript -e "plumber::plumb('backend/plumber.R')"` (from repo root, R installed).
Expected: no error (a Plumber router object is created). If R is unavailable in
this environment, defer to Step 8's test run / CI.

- [ ] **Step 7: Write the isolation + default-session tests**

Create `backend/tests/test-session-libs.R`:

```r
test_that("installed packages are isolated per session", {
  srv <- local_server()
  ins <- post_json(srv, "/install", list(package = "praise", sessionId = "alpha"))
  skip_if_not(isTRUE(ins$body$installed), "praise did not install (no network?)")

  a <- post_json(srv, "/packages?sessionId=alpha", NULL, method = "GET")
  b <- post_json(srv, "/packages?sessionId=beta", NULL, method = "GET")
  expect_true("praise" %in% unlist(a$body$packages))
  expect_false("praise" %in% unlist(b$body$packages))

  run_a <- post_execute(srv, "library(praise); cat('ok')", sessionId = "alpha")
  run_b <- post_execute(srv, "library(praise); cat('ok')", sessionId = "beta")
  expect_equal(run_a$body$stdout, "ok")
  expect_true(nzchar(run_b$body$stderr) || !is.null(run_b$body$error))
})

test_that("install with no sessionId targets the default session", {
  srv <- local_server()
  ins <- post_json(srv, "/install", list(package = "praise"))
  skip_if_not(isTRUE(ins$body$installed), "praise did not install (no network?)")
  d <- post_json(srv, "/packages?sessionId=default", NULL, method = "GET")
  expect_true("praise" %in% unlist(d$body$packages))
})
```

This assumes helpers `post_json(srv, path, body, method = "POST")` and
`post_execute(srv, code, sessionId = "default")`. Check `backend/tests/helper-server.R`
for the exact helper names/signatures already in use; if `post_json` or the
`sessionId` arg on `post_execute` don't exist, add thin wrappers in
`helper-server.R` mirroring the existing `post_execute` implementation (build the
request with `httr2::request()` + `req_body_json()` / `req_url_query()`).

- [ ] **Step 8: Run the backend suite**

Run: `Rscript backend/run-tests.R`
Expected: all tests pass (new isolation/default tests included; network-gated
tests `skip` cleanly if CRAN is unreachable).

- [ ] **Step 9: Commit**

```bash
git add backend/plumber.R backend/tests/test-session-libs.R
git commit -m "Session-scope package library (install/uninstall/packages)"
```

---

## Task 2: Backend — `/reset` purge flag + `/import-legacy`

**Files:**
- Modify: `backend/plumber.R`
- Modify: `backend/tests/test-session-libs.R`

- [ ] **Step 1: Add `purgePackages` to `/reset`**

```r
#* Reset a session's persisted workspace + attached-package state
#* @post /reset
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  unlink(c(paths$workspace, paths$attached), force = TRUE)
  if (isTRUE(body$purgePackages)) unlink(paths$rlib, recursive = TRUE, force = TRUE)
  list(ok = TRUE)
}
```

- [ ] **Step 2: Add `/import-legacy`**

Add after the `/uninstall` handler:

```r
#* Copy packages from the legacy shared library into a session's library
#* @post /import-legacy
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  lib <- session_paths(sanitize_session_id(body$sessionId))$rlib
  dir.create(lib, recursive = TRUE, showWarnings = FALSE)

  legacy <- if (dir.exists(LEGACY_PKG_LIB)) {
    tryCatch(rownames(installed.packages(lib.loc = LEGACY_PKG_LIB)), error = function(e) NULL)
  } else NULL
  if (is.null(legacy)) legacy <- character(0)

  present <- tryCatch(rownames(installed.packages(lib.loc = lib)), error = function(e) NULL)
  if (is.null(present)) present <- character(0)

  copied <- character(0)
  for (p in setdiff(legacy, present)) {
    ok <- tryCatch({ file.copy(file.path(LEGACY_PKG_LIB, p), lib, recursive = TRUE); TRUE },
                   error = function(e) FALSE)
    if (isTRUE(ok)) copied <- c(copied, p)
  }
  list(imported = length(copied), packages = as.list(copied))
}
```

- [ ] **Step 3: Write purge + import tests**

Append to `backend/tests/test-session-libs.R`:

```r
test_that("reset purgePackages wipes the session library, default keeps it", {
  srv <- local_server()
  ins <- post_json(srv, "/install", list(package = "praise", sessionId = "purge"))
  skip_if_not(isTRUE(ins$body$installed), "praise did not install (no network?)")

  post_json(srv, "/reset", list(sessionId = "purge"))  # default: keep packages
  kept <- post_json(srv, "/packages?sessionId=purge", NULL, method = "GET")
  expect_true("praise" %in% unlist(kept$body$packages))

  post_json(srv, "/reset", list(sessionId = "purge", purgePackages = TRUE))
  gone <- post_json(srv, "/packages?sessionId=purge", NULL, method = "GET")
  expect_false("praise" %in% unlist(gone$body$packages))
})

test_that("import-legacy is idempotent and no-ops on an empty legacy lib", {
  srv <- local_server()
  # Fresh server has no legacy lib populated -> 0 imported.
  res <- post_json(srv, "/import-legacy", list(sessionId = "imp"))
  expect_equal(res$body$imported, 0)
  expect_length(res$body$packages, 0)
})
```

(A positive-import test would require seeding `LEGACY_PKG_LIB` inside the server's
temp dir; the empty/idempotent path is the deterministic, network-free case. If
`helper-server.R` exposes the server's `R_PKG_LIB` path, optionally add a seeded
case that installs into that dir first and asserts `imported >= 1`.)

- [ ] **Step 4: Run the suite**

Run: `Rscript backend/run-tests.R`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/plumber.R backend/tests/test-session-libs.R
git commit -m "Add /reset purgePackages and /import-legacy"
```

---

## Task 3: Backend — compose read-only legacy mount + docs

**Files:**
- Modify: `backend/docker-compose.yml`
- Modify: `backend/README.md`

- [ ] **Step 1: Mount the legacy volume read-only**

In `backend/docker-compose.yml`, change the `r_rlib` mount:

```yaml
    volumes:
      - r_sessions:/data/sessions
      - r_rlib:/data/rlib:ro
```

(Per-session libraries live under `/data/sessions/<id>/rlib`, already covered by
the writable `r_sessions` volume; `/data/rlib` is now only the read-only legacy
source.)

- [ ] **Step 2: Update `backend/README.md`**

Under "Endpoints", change the `/install`, `/uninstall`, `/packages` bullets to
note they act on the session named by `sessionId` (default `default`), add
`purgePackages` to `/reset`, and add an `/import-legacy` bullet. Replace the
"Packages" section's "shared, persistent library" wording with per-session
libraries under `R_SESSION_DIR/<id>/rlib`, and describe `/import-legacy` copying
from the legacy `R_PKG_LIB` on request. Concretely, add:

```markdown
- `POST /import-legacy` — body `{"sessionId":"default"}`, copies packages from
  the legacy shared library (`R_PKG_LIB`, now read-only) into that session's
  library, skipping any already present. Returns `{"imported":<int>,"packages":[...]}`.
  Same auth / rate-limit rules as `/execute`.
```

and note on `/reset`: `optional "purgePackages": true also deletes the session's
installed-package library (used when a project is deleted).`

- [ ] **Step 3: Commit**

```bash
git add backend/docker-compose.yml backend/README.md
git commit -m "Mount legacy lib read-only; document per-session packages"
```

---

## Task 4: App — `ProjectSession` pure helper

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/project/ProjectSession.kt`
- Test: `app/src/test/java/com/rmobile/console/data/project/ProjectSessionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProjectSessionTest {
    private fun project(id: Long, name: String) =
        Project(id = id, name = name, files = emptyList(), activeFileName = "", entryFileName = "", updatedAt = 0)

    @Test fun `derives proj-prefixed id`() {
        assertEquals("proj-42", ProjectSession.of(project(42, "Anything")))
    }

    @Test fun `is stable across a rename`() {
        assertEquals(ProjectSession.of(project(7, "Old")), ProjectSession.of(project(7, "New")))
    }

    @Test fun `distinct ids yield distinct sessions`() {
        assertNotEquals(ProjectSession.of(project(1, "A")), ProjectSession.of(project(2, "A")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectSessionTest"`
Expected: FAIL — `ProjectSession` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.rmobile.console.data.project

/**
 * Maps a project to its backend session id. A project's [Project.id] is a
 * positive Long, so the derived id already satisfies the backend's
 * `[A-Za-z0-9_-]` session-id sanitizer and is stable across renames.
 */
object ProjectSession {
    fun of(project: Project): String = "proj-${project.id}"
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectSessionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/project/ProjectSession.kt \
        app/src/test/java/com/rmobile/console/data/project/ProjectSessionTest.kt
git commit -m "Add ProjectSession id derivation"
```

---

## Task 5: App — models, API, repository threading

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/model/PackageModels.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`

- [ ] **Step 1: Extend the models**

In `PackageModels.kt`, add `sessionId` to both requests and add the import models:

```kotlin
@Serializable
data class InstallRequest(
    @SerialName("package") val packageName: String,
    val sessionId: String? = null,
)

@Serializable
data class UninstallRequest(
    @SerialName("package") val packageName: String,
    val sessionId: String? = null,
)

@Serializable
data class ImportLegacyRequest(
    val sessionId: String? = null,
)

@Serializable
data class ImportLegacyResponse(
    val imported: Int = 0,
    val packages: List<String> = emptyList(),
)
```

In `ExecuteModels.kt`, add `purgePackages` to `ResetRequest` (leave `sessionId`
as-is):

```kotlin
@Serializable
data class ResetRequest(
    val sessionId: String? = null,
    val purgePackages: Boolean = false,
)
```

(If `ResetRequest` currently has a positional single field, keep the existing
field and simply append `val purgePackages: Boolean = false`. Verify the exact
current definition and match it.)

- [ ] **Step 2: Extend the API interface**

In `RExecutionApi.kt`:

```kotlin
import com.rmobile.console.data.model.ImportLegacyRequest
import com.rmobile.console.data.model.ImportLegacyResponse
import retrofit2.http.Query
```

```kotlin
    @GET("packages")
    suspend fun packages(@Query("sessionId") sessionId: String): PackagesResponse

    @POST("import-legacy")
    suspend fun importLegacy(@Body request: ImportLegacyRequest): ImportLegacyResponse
```

- [ ] **Step 3: Thread through the repository**

In `RExecutionRepository.kt`:

```kotlin
    suspend fun reset(
        sessionId: String = DEFAULT_SESSION_ID,
        purgePackages: Boolean = false,
    ): Result<ResetResponse> =
        runCatching { api.reset(ResetRequest(sessionId, purgePackages)) }

    suspend fun install(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<InstallResponse> =
        runCatching { api.install(InstallRequest(packageName, sessionId)) }

    suspend fun uninstall(packageName: String, sessionId: String = DEFAULT_SESSION_ID): Result<UninstallResponse> =
        runCatching { api.uninstall(UninstallRequest(packageName, sessionId)) }

    suspend fun listPackages(sessionId: String = DEFAULT_SESSION_ID): Result<PackagesResponse> =
        runCatching { api.packages(sessionId) }

    suspend fun importLegacy(sessionId: String = DEFAULT_SESSION_ID): Result<ImportLegacyResponse> =
        runCatching { api.importLegacy(ImportLegacyRequest(sessionId)) }
```

Add the imports for `ImportLegacyRequest`/`ImportLegacyResponse`.

- [ ] **Step 4: Update every test fake implementing `RExecutionApi`**

Find them: `grep -rl "RExecutionApi" app/src/test`. Each fake must add the new
`importLegacy` method and update `packages` / `install` / `uninstall` signatures.
Minimal stubs (adapt to each fake's existing style/recording fields):

```kotlin
override suspend fun packages(sessionId: String): PackagesResponse = PackagesResponse()
override suspend fun importLegacy(request: ImportLegacyRequest): ImportLegacyResponse = ImportLegacyResponse()
```

For fakes that already override `install`/`uninstall`, add `sessionId` to
`InstallRequest`/`UninstallRequest` construction sites if they build requests;
constructing via the data class still compiles because `sessionId` defaults to
null.

- [ ] **Step 5: Compile the unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: compiles; existing tests pass (fakes updated).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data app/src/test
git commit -m "Thread sessionId through package/reset API + repository"
```

---

## Task 6: App — EditorViewModel session wiring

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test: the existing `EditorViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `EditorViewModelTest.kt` (adapt to the existing FakeApi's recording style;
the FakeApi should capture the last `ExecuteRequest` and `ResetRequest`):

```kotlin
@Test fun `run sends the active project's session`() {
    val vm = newViewModel()               // opens/creates a project
    val expected = ProjectSession.of(vm.uiState.value.project)
    vm.runCode()
    advanceUntilIdle()
    assertEquals(expected, fakeApi.lastExecute?.sessionId)
}

@Test fun `deleting a project purges its backend session`() {
    val vm = newViewModel()
    vm.newProject("Second")               // now two projects; "Second" active
    val victim = vm.uiState.value.project
    vm.deleteProject(victim.id)
    advanceUntilIdle()
    assertEquals(ProjectSession.of(victim), fakeApi.lastReset?.sessionId)
    assertEquals(true, fakeApi.lastReset?.purgePackages)
}
```

If the existing test infra doesn't expose `lastExecute`/`lastReset`, extend the
FakeApi to record them (store the `@Body` request on each call).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: FAIL (session not threaded / delete doesn't purge).

- [ ] **Step 3: Implement the wiring**

In `EditorViewModel.kt`, add the import `com.rmobile.console.data.project.ProjectSession`.

`runCode()` — pass the session:

```kotlin
        val session = ProjectSession.of(project)
        viewModelScope.launch {
            repository.run(files, project.entryFile.let { project.entryFileName }, session)
```

(Use the existing `project.entryFileName`; the line above only illustrates where
`session` is added — the actual call is `repository.run(files, project.entryFileName, session)`.)

`resetSession()` — pass the active project's session:

```kotlin
    fun resetSession() {
        val session = ProjectSession.of(_uiState.value.project)
        viewModelScope.launch {
            repository.reset(session)
                .onSuccess { _uiState.update { it.copy(workspaceObjects = emptyList()) } }
                .onFailure { t -> _uiState.update { it.copy(errorMessage = t.message ?: "Failed to reset the session.") } }
        }
    }
```

`deleteProject(id)` — fire a best-effort purge for the deleted project before
mutating local state. At the top of the method, capture the victim and launch the
purge:

```kotlin
    fun deleteProject(id: Long) {
        _uiState.value.projects.firstOrNull { it.id == id }?.let { victim ->
            viewModelScope.launch { repository.reset(ProjectSession.of(victim), purgePackages = true) }
        }
        var remaining = ProjectOps.delete(_uiState.value.projects, id)
        // ... existing body unchanged ...
    }
```

The purge is fire-and-forget (its `Result` is ignored), so local deletion still
succeeds when the backend is unreachable.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt app/src/test
git commit -m "Wire EditorViewModel run/reset/delete to per-project sessions"
```

---

## Task 7: App — PackagesViewModel active session + import

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt`
- Test: the existing `PackagesViewModelTest.kt`

- [ ] **Step 1: Add active-session/name to state**

In `PackagesUiState.kt`:

```kotlin
data class PackagesUiState(
    val installed: List<String> = emptyList(),
    val packageName: String = "",
    val installing: Boolean = false,
    val message: String? = null,
    val log: String = "",
    val isError: Boolean = false,
    val projectName: String = "",
)
```

- [ ] **Step 2: Write failing tests**

Add to `PackagesViewModelTest.kt` (the FakeApi should record the `sessionId`
passed to `packages`/`install`/`uninstall` and the `importLegacy` request):

```kotlin
@Test fun `uses the active project's session for listing and install`() {
    // Seed a project in the ProjectStore fake so the VM resolves its session.
    val vm = PackagesViewModel(RExecutionRepository(fakeApi), projectStore = fakeProjectStore)
    advanceUntilIdle()
    assertEquals("proj-100", fakeApi.lastPackagesSessionId)   // fake project id = 100
    vm.onPackageNameChanged("praise"); vm.install(); advanceUntilIdle()
    assertEquals("proj-100", fakeApi.lastInstallSessionId)
}

@Test fun `import legacy reports count and refreshes`() {
    fakeApi.importResult = ImportLegacyResponse(imported = 3, packages = listOf("a", "b", "c"))
    val vm = PackagesViewModel(RExecutionRepository(fakeApi), projectStore = fakeProjectStore)
    vm.importLegacy(); advanceUntilIdle()
    assertEquals("Imported 3 package(s).", vm.uiState.value.message)
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: FAIL (constructor arg / session / importLegacy missing).

- [ ] **Step 4: Implement**

In `PackagesViewModel.kt`, resolve the active project at construction and thread
its session everywhere. Add a `projectStore` constructor arg defaulting to the
ServiceLocator (mirroring `EditorViewModel`):

```kotlin
class PackagesViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {

    private val session: String
    init {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        _uiState.value = _uiState.value.copy(projectName = active?.name ?: "")
        refresh()
    }
```

Update the calls: `repository.listPackages(session)`, `repository.install(pkg, session)`,
`repository.uninstall(packageName, session)`. Add `importLegacy`:

```kotlin
    fun importLegacy() {
        viewModelScope.launch {
            repository.importLegacy(session)
                .onSuccess { r ->
                    _uiState.update { it.copy(isError = false, message = "Imported ${r.imported} package(s).") }
                    refresh()
                }
                .onFailure { t -> _uiState.update { it.copy(isError = true, message = t.message ?: "Import failed.") } }
        }
    }
```

Add imports: `ProjectSession`, `ProjectStore`, `ServiceLocator`,
`ImportLegacyResponse` (in the test). Move the `init { refresh() }` logic into the
new `init` block (don't call `refresh()` twice).

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages app/src/test
git commit -m "PackagesViewModel: per-project session + legacy import"
```

---

## Task 8: App — Packages screen header + import button

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt`

- [ ] **Step 1: Show the active project in the title**

Change the `TopAppBar` title to include the project name when present:

```kotlin
                title = {
                    Text(
                        if (uiState.projectName.isNotBlank()) "Packages · ${uiState.projectName}" else "Packages"
                    )
                },
```

- [ ] **Step 2: Add the import button**

Below the install `Row` (before the installed-list `LazyColumn`), add a
`TextButton` wired to the VM:

```kotlin
            TextButton(onClick = viewModel::importLegacy) {
                Text("Import packages from legacy library")
            }
```

- [ ] **Step 3: Build the debug APK to confirm UI compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Requires Android SDK — run locally / in CI if not
available here.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt
git commit -m "Packages screen: project-scoped header + legacy import action"
```

---

## Task 9: Verify + docs

**Files:**
- Modify: `CLAUDE.md`, `README.md`

- [ ] **Step 1: Full app verification**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug`
Expected: all green.

- [ ] **Step 2: Full backend verification**

Run: `Rscript backend/run-tests.R`
Expected: all pass.

- [ ] **Step 3: Update `CLAUDE.md`**

In the response-contract section, document: `sessionId` is now
`"proj-<project.id>"` (each project = one backend session); `/install`,
`/uninstall`, `/packages` are session-scoped (`packages` takes a `?sessionId=`
query param); `/reset` gains `purgePackages`; new `/import-legacy`
(`ImportLegacyRequest`/`ImportLegacyResponse`) is protected. In the app
architecture section, note `ProjectSession.of(project)` as the single mapping and
that deleting a project purges its backend session.

- [ ] **Step 4: Update root `README.md`**

Add a feature line: per-project R environments (each project has its own
workspace and installed-package library; optional one-tap import of packages from
the pre-multi-session shared library).

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Document per-project sessions + package libraries"
```

- [ ] **Step 6: Finish the branch**

Use superpowers:finishing-a-development-branch (push + PR, watch CI, merge to
`claude/r-app-android-version-ztmyd1`).

---

## Self-Review Notes

- **Spec coverage:** session identity (T4), per-session lib + session-scoped
  install/uninstall/packages (T1), purge + import-legacy (T2), compose read-only
  legacy mount (T3), app models/API/repo (T5), editor wiring incl. delete-purge
  (T6), packages screen session + import (T7, T8), docs (T3, T9). All spec
  sections map to a task.
- **Type consistency:** `ProjectSession.of` used identically in T4/T6/T7;
  `importLegacy(sessionId)` / `ImportLegacyResponse(imported, packages)` and
  `reset(sessionId, purgePackages)` names match across repository, API, VM, and
  tests.
- **Known verify-against-reality points (confirm during execution):** the exact
  `helper-server.R` helper names/signatures (T1 S7); the current `ResetRequest`
  definition (T5 S1); each test FakeApi's recording fields (T5 S4, T6 S1, T7 S2);
  `EditorViewModel.deleteProject` body (T6 S3). These are existing-code shapes to
  match, not new decisions.
