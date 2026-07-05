# Durable R Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the R workspace (global-env objects + attached packages) across runs and restarts, so the app supports an incremental REPL-style workflow, while each run stays an isolated sandboxed subprocess.

**Architecture:** Approach A from the design spec (`docs/superpowers/specs/2026-07-05-persistent-r-session-design.md`). The backend restores state at the start of the per-run wrapper (`load` the workspace image + replay `library()` calls) and saves it at the end (on success only). A single `"default"` session for now, with a `sessionId` plumbed through for future multi-session use. The app gains a workspace summary and a "Reset session" action.

**Tech Stack:** R + Plumber (`backend/`), Kotlin + Jetpack Compose (`app/`), Retrofit + kotlinx.serialization, JUnit4 + kotlinx-coroutines-test.

---

## Prerequisites (test environment on this machine)

**App unit tests** (`./gradlew`) need the bundled JDK and a truststore that trusts the corporate TLS-inspection CA (plain `./gradlew` fails with `PKIX path building failed`). Set this up once per shell:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
JKS="$HOME/win-roots.jks"   # or the session scratchpad path if already generated
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JKS -Djavax.net.ssl.trustStorePassword=changeit"
```

If `$JKS` does not exist, regenerate it from the Windows root store (PowerShell):

```powershell
$jks = "$HOME\win-roots.jks"; $keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
if (Test-Path $jks) { Remove-Item $jks -Force }
$tmp = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "rootexport")
$i = 0
foreach ($c in Get-ChildItem Cert:\LocalMachine\Root, Cert:\CurrentUser\Root) {
  try { $f = Join-Path $tmp.FullName "c$i.cer"; [IO.File]::WriteAllBytes($f, $c.Export('Cert'))
        & $keytool -importcert -noprompt -keystore $jks -storepass changeit -alias "root$i" -file $f 2>$null } catch {}
  $i++
}
```

Every app test/build command below must include the trailing flags:
`-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`.

**Backend tests** need Docker. The R side has no unit harness, so backend tasks are verified as **integration checks** against a running container (`cd backend && docker compose up --build -d`, then `curl`). This is expected — the spec calls for Docker/CI verification of the backend.

**Branch:** work on `feat/persistent-r-session` (already created; it holds this plan and the spec).

---

## File Structure

**Backend (modify):**
- `backend/plumber.R` — session paths + sanitizer, wrapper prelude/epilogue, `workspaceObjects` in the `/execute` response, new `/reset` endpoint, `is_protected()` covers `/reset`.
- `backend/Dockerfile` — create `/data/sessions` owned by `rexec`.
- `backend/docker-compose.yml` — named volume + `R_SESSION_DIR`.
- `backend/README.md` — document session behavior + security.

**App (modify):**
- `app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt` — `sessionId`, `workspaceObjects`, `ResetRequest`/`ResetResponse`.
- `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt` — `@POST("reset")`.
- `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt` — `sessionId` param + `reset()`, `DEFAULT_SESSION_ID`.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt` — `workspaceObjects`.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt` — set `workspaceObjects` (non-null only), `resetSession()`.
- `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt` — workspace summary + reset overflow menu + confirm dialog.

**App (create):**
- `app/src/test/java/com/rmobile/console/data/model/ExecuteModelsTest.kt`

**Docs (modify):** `README.md`, `CLAUDE.md`.

---

## Task 1: Backend — session storage helpers + wrapper bookends in `/execute`

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Add session constants + helpers**

Insert after the existing constants block (after the `.rate_state <- new.env(...)` line):

```r
# Durable-session state lives under this writable root (a Docker volume in prod).
SESSION_DIR <- Sys.getenv("R_SESSION_DIR", file.path(tempdir(), "r-sessions"))
DEFAULT_SESSION <- "default"
# Packages attached by default in a --vanilla session; only user-added ones are persisted.
DEFAULT_ATTACHED <- c("base", "methods", "datasets", "utils", "grDevices", "graphics", "stats")

# Reduce an incoming session id to a safe directory name (path-traversal guard).
sanitize_session_id <- function(id) {
  if (is.null(id) || !is.character(id) || length(id) != 1) return(DEFAULT_SESSION)
  cleaned <- gsub("[^A-Za-z0-9_-]", "", id)
  if (nzchar(cleaned)) cleaned else DEFAULT_SESSION
}

session_paths <- function(session_id) {
  dir <- file.path(SESSION_DIR, session_id)
  list(
    dir = dir,
    workspace = file.path(dir, "workspace.RData"),
    attached = file.path(dir, "attached.txt")
  )
}
```

- [ ] **Step 2: Resolve the session + build the state-aware wrapper in `/execute`**

In the `/execute` handler, after the `code` validation blocks and after `run_dir`/`plot_pattern` are set up, replace the existing `wrapped <- c(...)` / `writeLines(wrapped, script_path)` block with:

```r
  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$dir, recursive = TRUE, showWarnings = FALSE)
  objects_path <- file.path(run_dir, "objects.txt")

  attached_literal <- paste(deparse(DEFAULT_ATTACHED), collapse = "")

  wrapped <- c(
    sprintf(
      'tryCatch(if (file.exists(%s)) load(%s, envir = globalenv()), error = function(e) try(file.rename(%s, %s), silent = TRUE))',
      shQuote(paths$workspace), shQuote(paths$workspace),
      shQuote(paths$workspace), shQuote(paste0(paths$workspace, ".bad"))
    ),
    sprintf(
      'if (file.exists(%s)) invisible(lapply(readLines(%s), function(p) if (nzchar(p)) suppressWarnings(suppressMessages(try(library(p, character.only = TRUE), silent = TRUE)))))',
      shQuote(paths$attached), shQuote(paths$attached)
    ),
    sprintf('grDevices::png(filename = %s, width = 800, height = 600)', shQuote(plot_pattern)),
    code,
    'invisible(grDevices::dev.off())',
    sprintf('.saved <- try(save.image(%s), silent = TRUE)', shQuote(paths$workspace)),
    'if (inherits(.saved, "try-error")) message("Note: some objects could not be saved; workspace state was not updated.")',
    sprintf('writeLines(setdiff(.packages(), %s), %s)', attached_literal, shQuote(paths$attached)),
    sprintf('writeLines(ls(globalenv()), %s)', shQuote(objects_path))
  )
  writeLines(wrapped, script_path)
```

- [ ] **Step 3: Return `workspaceObjects` from the success path**

In the `/execute` success return (the final `list(stdout = ..., ...)`), read the object list and add the field. Replace that final `list(...)` with:

```r
  workspace_objects <- if (file.exists(objects_path)) as.list(readLines(objects_path)) else NULL

  list(
    stdout = result$stdout,
    stderr = result$stderr,
    plots = plots,
    error = if (result$status != 0) sprintf("R exited with status %d.", result$status) else NULL,
    timedOut = FALSE,
    workspaceObjects = workspace_objects
  )
```

(`as.list` makes jsonlite emit a JSON array even for one object; a `NULL` element is dropped, so an errored run — no `objects.txt` — omits the field, which the app reads as "unchanged". The error/timeout return branches are left unchanged; their absent field also reads as null.)

- [ ] **Step 4: Verify the R still parses**

Run:
```bash
cat backend/plumber.R | docker run --rm -i r-base:latest Rscript -e 'invisible(parse(file("stdin"))); cat("PARSE_OK\n")'
```
Expected: `PARSE_OK`

- [ ] **Step 5: Commit**

```bash
git add backend/plumber.R
git commit -m "backend: restore/save workspace + packages around each run"
```

---

## Task 2: Backend — `/reset` endpoint + protect it

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Extend `is_protected` to cover `/reset`**

Replace:
```r
is_protected <- function(req) identical(req$PATH_INFO, "/execute")
```
with:
```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset")
```

- [ ] **Step 2: Add the `/reset` endpoint**

Add after the `/execute` handler (before or after `/health`):

```r
#* Reset a session's persisted workspace + attached-package state
#* @post /reset
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  unlink(c(paths$workspace, paths$attached), force = TRUE)
  list(ok = TRUE)
}
```

- [ ] **Step 3: Verify the R still parses**

Run:
```bash
cat backend/plumber.R | docker run --rm -i r-base:latest Rscript -e 'invisible(parse(file("stdin"))); cat("PARSE_OK\n")'
```
Expected: `PARSE_OK`

- [ ] **Step 4: Commit**

```bash
git add backend/plumber.R
git commit -m "backend: add POST /reset to clear session state"
```

---

## Task 3: Backend — writable session volume (Dockerfile + compose)

**Files:**
- Modify: `backend/Dockerfile`
- Modify: `backend/docker-compose.yml`

- [ ] **Step 1: Pre-create the session dir owned by the non-root user**

In `backend/Dockerfile`, after the `RUN useradd ... rexec` line and before `USER rexec`, add:

```dockerfile
RUN mkdir -p /data/sessions && chown -R rexec:rexec /data
```

- [ ] **Step 2: Mount a named volume + set the env var**

In `backend/docker-compose.yml`, add `R_SESSION_DIR` under `environment:`:

```yaml
      R_SESSION_DIR: "/data/sessions"
```

Add a `volumes:` key to the `r-execution` service (keep `read_only: true` and the `tmpfs: /tmp` entry):

```yaml
    volumes:
      - r_sessions:/data/sessions
```

And add a top-level `volumes:` block at the end of the file:

```yaml
volumes:
  r_sessions:
```

- [ ] **Step 3: Build + integration-test the whole session feature**

```bash
cd backend && docker compose up --build -d
sleep 5
# run 1: create x
curl -s -X POST http://localhost:8000/execute -H "Content-Type: application/json" -d '{"code":"x <- 41"}'
# run 2: x persists → stdout "42"; response includes "x" in workspaceObjects
curl -s -X POST http://localhost:8000/execute -H "Content-Type: application/json" -d '{"code":"cat(x + 1)"}'
# packages persist: attach jsonlite (installed in the image), then confirm next run
curl -s -X POST http://localhost:8000/execute -H "Content-Type: application/json" -d '{"code":"suppressMessages(library(jsonlite)); cat(\"attached\")"}'
curl -s -X POST http://localhost:8000/execute -H "Content-Type: application/json" -d '{"code":"cat(\"jsonlite\" %in% .packages())"}'
# reset clears state
curl -s -X POST http://localhost:8000/reset -H "Content-Type: application/json" -d '{"sessionId":"default"}'
curl -s -X POST http://localhost:8000/execute -H "Content-Type: application/json" -d '{"code":"cat(exists(\"x\"))"}'
docker compose down
```
Expected: run 2 `stdout":"42"` and `"workspaceObjects":["x"]`; 4th call `stdout":"TRUE"`; `/reset` → `{"ok":true}`; final call `stdout":"FALSE"`.

- [ ] **Step 4: Commit**

```bash
git add backend/Dockerfile backend/docker-compose.yml
git commit -m "backend: persist session state on a writable volume"
```

---

## Task 4: Backend — document session behavior

**Files:**
- Modify: `backend/README.md`

- [ ] **Step 1: Add a "Sessions" section + endpoints/security notes**

Under `## Endpoints`, add:
```markdown
- `POST /reset` — body `{"sessionId": "default"}`, clears that session's saved
  workspace and attached-package list. Returns `{"ok": true}`. Same auth /
  rate-limit rules as `/execute`.
```

In the `POST /execute` bullet, note the new field/param:
```markdown
  Optional `sessionId` (defaults to `default`); the response adds
  `workspaceObjects` (names in the session's global env after the run, or
  omitted when the run errored/timed out).
```

Add a new subsection after the endpoints:
```markdown
## Durable sessions

Each session keeps `workspace.RData` (global-env objects) and `attached.txt`
(user-attached packages) under `R_SESSION_DIR` (default `/data/sessions`, a
named volume in `docker-compose.yml`). The wrapper restores them before each
run and saves them after a **successful** run, so a failed or timed-out run
never overwrites good state. Only data/objects and attached packages persist —
connections, external pointers, and `options()` do not.
```

In the security section, add to the "not handled" list:
```markdown
- **Session state is unbounded and attacker-writable.** Persisted workspaces
  can grow without limit and hold arbitrary user data; there is no per-session
  quota or eviction yet. The `sessionId` is sanitized to `[A-Za-z0-9_-]` — keep
  that guard if you add real multi-session support.
```

- [ ] **Step 2: Commit**

```bash
git add backend/README.md
git commit -m "docs: describe durable sessions and /reset in backend README"
```

---

## Task 5: App — API models (`sessionId`, `workspaceObjects`, reset)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt`
- Test: `app/src/test/java/com/rmobile/console/data/model/ExecuteModelsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/model/ExecuteModelsTest.kt`:

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuteModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `response without workspaceObjects decodes to null`() {
        val response = json.decodeFromString<ExecuteResponse>("""{"stdout":"hi"}""")
        assertEquals("hi", response.stdout)
        assertNull(response.workspaceObjects)
    }

    @Test
    fun `response decodes a workspaceObjects array`() {
        val response = json.decodeFromString<ExecuteResponse>("""{"workspaceObjects":["x","df"]}""")
        assertEquals(listOf("x", "df"), response.workspaceObjects)
    }

    @Test
    fun `request serializes sessionId`() {
        val encoded = json.encodeToString(ExecuteRequest(code = "1", sessionId = "default"))
        assertTrue(encoded.contains("\"sessionId\":\"default\""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.ExecuteModelsTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile — `ExecuteRequest` has no `sessionId`, `ExecuteResponse` has no `workspaceObjects`.

- [ ] **Step 3: Update the models**

Replace the contents of `ExecuteModels.kt` with:

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteRequest(
    val code: String,
    val sessionId: String? = null,
)

@Serializable
data class ExecuteResponse(
    val stdout: String = "",
    val stderr: String = "",
    /** Base64-encoded PNG images, one per plot device page produced by the script. */
    val plots: List<String> = emptyList(),
    val error: String? = null,
    val timedOut: Boolean = false,
    /** Global-env object names after a successful run; null when the run errored (state unchanged). */
    val workspaceObjects: List<String>? = null,
)

@Serializable
data class ResetRequest(
    val sessionId: String? = null,
)

@Serializable
data class ResetResponse(
    val ok: Boolean = false,
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.ExecuteModelsTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/model/ExecuteModels.kt app/src/test/java/com/rmobile/console/data/model/ExecuteModelsTest.kt
git commit -m "app: add sessionId, workspaceObjects, and reset models"
```

---

## Task 6: App — API + repository (`reset`, session id)

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`

- [ ] **Step 1: Add the reset endpoint to the API interface**

Replace the body of `RExecutionApi` with:

```kotlin
package com.rmobile.console.data.network

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import retrofit2.http.Body
import retrofit2.http.POST

/** Talks to the R execution backend in /backend (see backend/README.md). */
interface RExecutionApi {
    @POST("execute")
    suspend fun execute(@Body request: ExecuteRequest): ExecuteResponse

    @POST("reset")
    suspend fun reset(@Body request: ResetRequest): ResetResponse
}
```

- [ ] **Step 2: Add `sessionId` + `reset()` to the repository**

Replace the contents of `RExecutionRepository.kt` with:

```kotlin
package com.rmobile.console.data

import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi

class RExecutionRepository(
    private val api: RExecutionApi,
) {
    suspend fun run(code: String, sessionId: String = DEFAULT_SESSION_ID): Result<ExecuteResponse> =
        runCatching { api.execute(ExecuteRequest(code, sessionId)) }

    suspend fun reset(sessionId: String = DEFAULT_SESSION_ID): Result<ResetResponse> =
        runCatching { api.reset(ResetRequest(sessionId)) }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
```

- [ ] **Step 3: Compile to verify (no new test file; exercised via the ViewModel in Task 7)**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt
git commit -m "app: add reset() and session id to the API + repository"
```

---

## Task 7: App — ViewModel workspace state + reset

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

First extend the existing `FakeApi` in `EditorViewModelTest.kt` to implement `reset`. Replace the `FakeApi` class with:

```kotlin
    private class FakeApi(
        var response: ExecuteResponse = ExecuteResponse(stdout = "ok"),
        var error: Throwable? = null,
        var resetResponse: ResetResponse = ResetResponse(ok = true),
        var resetError: Throwable? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            error?.let { throw it }
            return response
        }
        override suspend fun reset(request: ResetRequest): ResetResponse {
            resetError?.let { throw it }
            return resetResponse
        }
    }
```

Add the imports at the top of the test file:
```kotlin
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
```

Add these tests inside `EditorViewModelTest`:

```kotlin
    @Test
    fun `successful run stores workspace objects`() = runTest {
        val vm = viewModel(api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x", "df"))))

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()

        assertEquals(listOf("x", "df"), vm.uiState.value.workspaceObjects)
    }

    @Test
    fun `errored run leaves workspace objects unchanged`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x")))
        val vm = viewModel(api = api)

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()
        // Next run errors on the backend: workspaceObjects null in the response.
        api.response = ExecuteResponse(error = "boom", workspaceObjects = null)
        vm.onCodeChanged("stop('boom')")
        vm.runCode()
        advanceUntilIdle()

        assertEquals(listOf("x"), vm.uiState.value.workspaceObjects)
    }

    @Test
    fun `reset session clears workspace objects`() = runTest {
        val vm = viewModel(api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))))

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.workspaceObjects.isNotEmpty())

        vm.resetSession()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.workspaceObjects.isEmpty())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile — `EditorUiState` has no `workspaceObjects`, `EditorViewModel` has no `resetSession`.

- [ ] **Step 3: Add `workspaceObjects` to the UI state**

In `EditorUiState.kt`, add the field (after `savedScripts`):

```kotlin
    /** Names of objects in the backend session's global env; empty when unknown/cleared. */
    val workspaceObjects: List<String> = emptyList(),
```

- [ ] **Step 4: Update the ViewModel**

In `EditorViewModel.kt`, in `runCode()`'s `onSuccess` block, add to the `copy(...)`:

```kotlin
                            workspaceObjects = response.workspaceObjects ?: it.workspaceObjects,
```

Then add a `resetSession()` method (e.g. after `clearHistory()`):

```kotlin
    /** Clears the backend session's workspace. */
    fun resetSession() {
        viewModelScope.launch {
            repository.reset()
                .onSuccess { _uiState.update { it.copy(workspaceObjects = emptyList()) } }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to reset the session.")
                    }
                }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: PASS (all EditorViewModelTest cases, including the 3 new ones).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorUiState.kt app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: track workspace objects and reset the session"
```

---

## Task 8: App — workspace summary + reset UI

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt`

No unit test (Compose UI); verified by the full build in Task 9.

- [ ] **Step 1: Add imports**

Add to the import block (note: `AlertDialog` and `TextButton` are already
imported by the saved-scripts UI — do **not** re-add them):

```kotlin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
```

- [ ] **Step 2: Add overflow-menu + reset dialog state and a workspace summary**

In `EditorScreen`, add state near the other `remember { mutableStateOf(...) }` declarations:

```kotlin
    var menuOpen by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
```

In the `TopAppBar` `actions = { ... }` block, after the Settings `IconButton`, add an overflow menu:

```kotlin
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Reset session") },
                            onClick = {
                                menuOpen = false
                                showResetConfirm = true
                            },
                        )
                    }
```

Add the workspace summary in the main `Column`, immediately before `OutputPanel(...)`:

```kotlin
            if (uiState.workspaceObjects.isNotEmpty()) {
                Text(
                    text = "Workspace: ${uiState.workspaceObjects.joinToString(", ")} " +
                        "(${uiState.workspaceObjects.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
```

Add the confirm dialog at the end of `EditorScreen` (after the `if (showSaveDialog) { ... }` block):

```kotlin
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset session?") },
            text = {
                Text(
                    "This clears the R workspace on the backend — saved variables and " +
                        "attached packages are lost. Your saved scripts are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSession()
                        showResetConfirm = false
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: show workspace summary and add reset-session action"
```

---

## Task 9: Full verification + docs

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Run the full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; test report shows 0 failures; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Update README features**

In `README.md`, under `## Features`, add:
```markdown
- **Durable R session** — variables and attached packages persist between runs
  and restarts; a workspace summary shows what's in scope, and "Reset session"
  clears it.
```

- [ ] **Step 3: Update CLAUDE.md**

In `CLAUDE.md`, in the backend architecture paragraph, add a sentence:
```markdown
Durable sessions: the wrapper `load()`s a per-session `workspace.RData` and
replays recorded `library()` calls before user code, then `save.image()`s and
records attached packages after a successful run (state under `R_SESSION_DIR`,
a named volume). `POST /reset` clears a session.
```

In the "Response contract" section, note the added fields (`sessionId` on the
request; `workspaceObjects` on the response) and the new `/reset` endpoint with
`ResetRequest`/`ResetResponse`.

Update the "Current scope" section to move durable sessions from "not built" to built.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: note durable R sessions in README and CLAUDE.md"
```

---

## Done

All spec requirements implemented: durable workspace + attached packages, `/reset`, workspace summary, session-id plumbing, graceful failure handling, and the writable-volume ops. Backend verified via Docker integration checks; app verified via unit tests + lint + assemble. Open a PR from `feat/persistent-r-session` when ready (base `claude/r-app-android-version-ztmyd1`, or stack on PR #1 if that hasn't merged).
