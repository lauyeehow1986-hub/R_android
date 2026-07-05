# Package Installation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users install CRAN packages that persist and become usable in later runs — via a `POST /install` endpoint + a Packages screen, plus a writable shared library so inline `install.packages()` works too.

**Architecture:** A shared library dir (`R_PKG_LIB`) on its own volume is prepended to `.libPaths()` in the `/execute` wrapper. `POST /install` runs `install.packages()` in an isolated subprocess with a long timeout; `GET /packages` lists installed ones. Common system libs are pre-baked into the image (no runtime apt). The app gets models/API/repository, a `PackagesViewModel`, and a `PackagesScreen`.

**Tech Stack:** R + Plumber, testthat + httr2 (backend tests), Kotlin + Compose, Retrofit + kotlinx.serialization, JUnit4.

---

## Prerequisites (verification)

**Backend tests** run in the prebuilt Docker image `r-backend-test` (already built). Verification command (repo bind-mounted; needs internet for the real-install test — Docker has it):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R
```

**App tests** need the bundled JDK + truststore (plain `./gradlew` fails with `PKIX path building failed`). Export first:
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export JKS="C:/Users/lauye/AppData/Local/Temp/claude/C--Users-lauye-Downloads-R-android/6471244e-45c9-4b58-a6cc-c90db51dfa4b/scratchpad/win-roots.jks"
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JKS -Djavax.net.ssl.trustStorePassword=changeit"
```
Append to every gradle command: `-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`. Verify `$JKS` exists first; if missing, report BLOCKED.

**Branch:** `feat/package-installation` (already created; holds the spec).

---

## File Structure

**Backend (modify):** `backend/plumber.R` (lib consts, `.libPaths` prelude, `/packages`, `/install`, `is_protected`), `backend/Dockerfile` (system libs, `remotes`, `/data/rlib`), `backend/docker-compose.yml` (volume + env), `backend/README.md`.
**Backend (create):** `backend/tests/test-packages.R`, `backend/tests/test-install.R`.
**App (create):** `data/model/PackageModels.kt`, `ui/packages/PackagesUiState.kt`, `ui/packages/PackagesViewModel.kt`, `ui/packages/PackagesScreen.kt`, `test/.../data/model/PackageModelsTest.kt`, `test/.../ui/packages/PackagesViewModelTest.kt`.
**App (modify):** `data/network/RExecutionApi.kt`, `data/RExecutionRepository.kt`, `MainActivity.kt`, `ui/editor/EditorScreen.kt`, `test/.../ui/editor/EditorViewModelTest.kt` (fake needs new methods), `CLAUDE.md`.

---

## Task 1: Backend — shared lib on `.libPaths` + `GET /packages`

**Files:** Modify `backend/plumber.R`; Create `backend/tests/test-packages.R`.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test-packages.R`:
```r
test_that("packages list is empty on a fresh library", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/packages")
  expect_equal(res$status, 200)
  expect_equal(length(res$body$packages), 0)
})

test_that("the shared library is on .libPaths during execution", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- post_execute(srv, "cat(normalizePath(Sys.getenv('R_PKG_LIB')) %in% normalizePath(.libPaths()))")
  expect_identical(res$body$stdout, "TRUE")
})
```

- [ ] **Step 2: Run the suite — verify FAIL**

Run the backend verification command. Expected: FAIL — no `/packages` route (404 → status not 200) and `.libPaths` doesn't include the lib yet.

- [ ] **Step 3: Add lib constants + `.libPaths` prelude + `/packages`**

In `backend/plumber.R`, add constants after the `session_paths` helper (near the other `Sys.getenv` constants):
```r
# Shared, persistent package library (its own volume in prod).
PKG_LIB <- Sys.getenv("R_PKG_LIB", "/data/rlib")
INSTALL_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_INSTALL_TIMEOUT_SECONDS", "300"))
CRAN_REPO <- Sys.getenv("R_CRAN_REPO", "https://packagemanager.posit.co/cran/__linux__/jammy/latest")
```

In the `/execute` handler's `wrapped <- c(...)` vector, add this as the FIRST element (before the workspace-restore line):
```r
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(PKG_LIB)),
```
(`.libPaths()` silently drops the dir if it doesn't exist, so this is safe when `R_PKG_LIB` is unset/absent.)

Add a `/packages` endpoint (near `/health`):
```r
#* List user-installed packages in the shared library
#* @get /packages
function() {
  pkgs <- tryCatch(rownames(installed.packages(lib.loc = PKG_LIB)), error = function(e) NULL)
  if (is.null(pkgs)) pkgs <- character(0)
  list(packages = as.list(pkgs))
}
```

- [ ] **Step 4: Run the suite — verify PASS**

Run the verification command. Expected: all pass (existing suite + the 2 new tests).

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/plumber.R backend/tests/test-packages.R
git commit -m "backend: shared package library on .libPaths + GET /packages"
```

---

## Task 2: Backend — `POST /install`

**Files:** Modify `backend/plumber.R`; Create `backend/tests/test-install.R`.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test-install.R`:
```r
test_that("install rejects an invalid package name", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/install", body = list(package = "../evil"))
  expect_equal(res$status, 400)
})

test_that("installing a nonexistent package reports not installed", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  res <- api_request(srv, "/install", body = list(package = "nonexistentpkgxyz"))
  expect_equal(res$status, 200)
  expect_false(res$body$installed)
  expect_false(is.null(res$body$error))
})

test_that("install is auth-protected", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_API_KEY = "secret"))
  expect_equal(api_request(srv, "/install", body = list(package = "praise"))$status, 401)
})

test_that("a real package installs and is usable in a later run", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  res <- api_request(srv, "/install", body = list(package = "praise"))
  expect_equal(res$status, 200)
  expect_true(res$body$installed)
  # persisted + usable in /execute
  expect_true("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
  run <- post_execute(srv, "library(praise); cat(is.character(praise()))")
  expect_identical(run$body$stdout, "TRUE")
})
```

- [ ] **Step 2: Run the suite — verify FAIL**

Run the verification command. Expected: FAIL — `/install` route missing (401 test may pass by accident via 404-not-401; the others fail).

- [ ] **Step 3: Implement `/install` + protect it**

In `backend/plumber.R`, change `is_protected` to include `/install`:
```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install")
```

Add the `/install` endpoint (after `/reset`):
```r
#* Install a CRAN package into the shared library
#* @post /install
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  pkg <- body$package
  if (is.null(pkg) || !is.character(pkg) || length(pkg) != 1 || !grepl("^[A-Za-z0-9._]+$", pkg)) {
    res$status <- 400
    return(list(stdout = "", stderr = "", error = "Invalid or missing 'package' name.",
                timedOut = FALSE, installed = FALSE, systemRequirements = NULL))
  }

  dir.create(PKG_LIB, recursive = TRUE, showWarnings = FALSE)
  run_dir <- file.path(tempdir(), paste0("install-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "install.R")

  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(PKG_LIB)),
    # RSPM serves binaries only when the platform User-Agent is set (rocker sets it
    # via Rprofile.site, which --vanilla skips), so set it explicitly here.
    'options(HTTPUserAgent = sprintf("R/%s R (%s)", getRversion(), paste(getRversion(), R.version["platform"], R.version["arch"], R.version["os"])))',
    sprintf('install.packages(%s, repos = %s, lib = %s)', shQuote(pkg), shQuote(CRAN_REPO), shQuote(PKG_LIB))
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

  installed <- pkg %in% rownames(installed.packages(lib.loc = PKG_LIB))

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

- [ ] **Step 4: Run the suite — verify PASS**

Run the verification command. Expected: all pass, including the real `praise` install (allow the run to take a bit longer for the download). If the `praise` test fails due to no network in the container, investigate; the container had network during image build, so it should here too.

- [ ] **Step 5: Commit**
```bash
git add backend/plumber.R backend/tests/test-install.R
git commit -m "backend: add POST /install for CRAN packages"
```

---

## Task 3: Backend — Docker image + compose (persistent lib + system libs)

**Files:** Modify `backend/Dockerfile`, `backend/docker-compose.yml`.

- [ ] **Step 1: Dockerfile — system libs, remotes, lib dir**

In `backend/Dockerfile`, expand the `apt-get install` line to add the common R-package system libraries (keep `libsodium-dev`; `libcurl4-openssl-dev`/`libssl-dev` may already be implied by packages but add them explicitly):
```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsodium-dev libcurl4-openssl-dev libssl-dev \
    libxml2-dev libfontconfig1-dev libharfbuzz-dev libfribidi-dev libfreetype6-dev \
    libpng-dev libjpeg-dev libtiff5-dev libgit2-dev libssh2-1-dev libicu-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
```
Add `remotes` to the `install2.r` list:
```dockerfile
RUN install2.r --error --skipmissing \
    plumber \
    processx \
    base64enc \
    jsonlite \
    remotes
```
After the `useradd ... rexec` line and before `USER rexec`, add the lib dir alongside the existing session-dir line:
```dockerfile
RUN mkdir -p /data/sessions /data/rlib && chown -R rexec:rexec /data
```
(If a separate `mkdir -p /data/sessions ...` line already exists from the durable-session work, replace it with this combined line.)

- [ ] **Step 2: docker-compose — volume + env**

In `backend/docker-compose.yml`, add to the service `environment:`:
```yaml
      R_PKG_LIB: "/data/rlib"
      R_INSTALL_TIMEOUT_SECONDS: "300"
      R_CRAN_REPO: "${R_CRAN_REPO:-https://packagemanager.posit.co/cran/__linux__/jammy/latest}"
```
Add to the service `volumes:` (next to `r_sessions:/data/sessions`):
```yaml
      - r_rlib:/data/rlib
```
Add to the top-level `volumes:` map:
```yaml
  r_rlib:
```

- [ ] **Step 3: Verify — compose config + image build**

```bash
cd /c/Users/lauye/Downloads/R_android/backend
docker compose config >/dev/null && echo "COMPOSE_OK"
docker compose build r-execution 2>&1 | tail -5
docker compose run --rm --no-deps --entrypoint sh r-execution -c 'ls -ld /data/rlib && Rscript -e "library(remotes); cat(\"remotes_ok\\n\"); cat(system.file(package=\"remotes\") != \"\")"'
```
Expected: `COMPOSE_OK`; the build succeeds; the last command shows `/data/rlib` exists (owned by rexec) and prints `remotes_ok` / `TRUE`. (This build is a few minutes — it compiles/installs the apt libs and `remotes`.)

- [ ] **Step 4: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/Dockerfile backend/docker-compose.yml
git commit -m "backend: pre-bake package system libs + persistent rlib volume"
```

---

## Task 4: App — models, API, repository

**Files:** Create `app/src/main/java/com/rmobile/console/data/model/PackageModels.kt`, `app/src/test/java/com/rmobile/console/data/model/PackageModelsTest.kt`; Modify `data/network/RExecutionApi.kt`, `data/RExecutionRepository.kt`, `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`.

- [ ] **Step 1: Write the failing model test**

Create `app/src/test/java/com/rmobile/console/data/model/PackageModelsTest.kt`:
```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `install request uses the package json key`() {
        val encoded = json.encodeToString(InstallRequest(packageName = "praise"))
        assertTrue(encoded.contains("\"package\":\"praise\""))
    }

    @Test
    fun `install response decodes installed and systemRequirements`() {
        val r = json.decodeFromString<InstallResponse>("""{"installed":true,"stdout":"ok"}""")
        assertTrue(r.installed)
        assertEquals("ok", r.stdout)
        assertNull(r.systemRequirements)
    }

    @Test
    fun `packages response decodes a list`() {
        val r = json.decodeFromString<PackagesResponse>("""{"packages":["praise","glue"]}""")
        assertEquals(listOf("praise", "glue"), r.packages)
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.model.PackageModelsTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (models don't exist).

- [ ] **Step 3: Create the models**

Create `app/src/main/java/com/rmobile/console/data/model/PackageModels.kt`:
```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstallRequest(
    // JSON key must be "package" (a Kotlin soft keyword), so the property is renamed.
    @SerialName("package") val packageName: String,
)

@Serializable
data class InstallResponse(
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val timedOut: Boolean = false,
    val installed: Boolean = false,
    val systemRequirements: String? = null,
)

@Serializable
data class PackagesResponse(
    val packages: List<String> = emptyList(),
)
```

- [ ] **Step 4: Add API methods**

In `data/network/RExecutionApi.kt`, add imports and the two methods:
```kotlin
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import retrofit2.http.GET
```
```kotlin
    @POST("install")
    suspend fun install(@Body request: InstallRequest): InstallResponse

    @GET("packages")
    suspend fun packages(): PackagesResponse
```

- [ ] **Step 5: Add repository methods**

In `data/RExecutionRepository.kt`, add imports (`InstallRequest`, `InstallResponse`, `PackagesResponse`) and:
```kotlin
    suspend fun install(packageName: String): Result<InstallResponse> =
        runCatching { api.install(InstallRequest(packageName)) }

    suspend fun listPackages(): Result<PackagesResponse> =
        runCatching { api.packages() }
```

- [ ] **Step 6: Keep the existing fake compiling**

Adding to `RExecutionApi` breaks `EditorViewModelTest`'s `FakeApi` (it now misses two overrides). In `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`, add imports (`InstallRequest`, `InstallResponse`, `PackagesResponse`) and add these two overrides to the `FakeApi` class body:
```kotlin
        override suspend fun install(request: InstallRequest): InstallResponse = InstallResponse(installed = true)
        override suspend fun packages(): PackagesResponse = PackagesResponse()
```

- [ ] **Step 7: Run tests — verify PASS**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures (new model tests pass; existing tests still green).

- [ ] **Step 8: Commit**
```bash
git add app/src/main/java/com/rmobile/console/data/model/PackageModels.kt \
        app/src/test/java/com/rmobile/console/data/model/PackageModelsTest.kt \
        app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt \
        app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: package models, install/packages API + repository"
```

---

## Task 5: App — PackagesViewModel

**Files:** Create `ui/packages/PackagesUiState.kt`, `ui/packages/PackagesViewModel.kt`, `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`:
```kotlin
package com.rmobile.console.ui.packages

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackagesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var packagesResponse: PackagesResponse = PackagesResponse(),
        var installResponse: InstallResponse = InstallResponse(installed = true),
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse = ExecuteResponse()
        override suspend fun reset(request: ResetRequest): ResetResponse = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest): InstallResponse = installResponse
        override suspend fun packages(): PackagesResponse = packagesResponse
    }

    private fun viewModel(api: FakeApi) = PackagesViewModel(RExecutionRepository(api))

    @Test
    fun `loads installed packages on init`() = runTest {
        val vm = viewModel(FakeApi(packagesResponse = PackagesResponse(listOf("glue", "praise"))))
        advanceUntilIdle()
        assertEquals(listOf("glue", "praise"), vm.uiState.value.installed)
    }

    @Test
    fun `successful install reports success and refreshes the list`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(emptyList()),
            installResponse = InstallResponse(installed = true, stdout = "done"),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(listOf("praise"))
        vm.onPackageNameChanged("praise")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.installing)
        assertFalse(s.isError)
        assertTrue(s.message!!.contains("praise"))
        assertEquals(listOf("praise"), s.installed)
    }

    @Test
    fun `failed install surfaces the error`() = runTest {
        val vm = viewModel(FakeApi(installResponse = InstallResponse(installed = false, error = "not available")))
        advanceUntilIdle()
        vm.onPackageNameChanged("nope")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.isError)
        assertTrue(s.message!!.contains("not available"))
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (`PackagesViewModel`/`PackagesUiState` don't exist).

- [ ] **Step 3: Create the state**

Create `app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt`:
```kotlin
package com.rmobile.console.ui.packages

data class PackagesUiState(
    val installed: List<String> = emptyList(),
    val packageName: String = "",
    val installing: Boolean = false,
    val message: String? = null,
    val log: String = "",
    val isError: Boolean = false,
)
```

- [ ] **Step 4: Create the ViewModel**

Create `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt`:
```kotlin
package com.rmobile.console.ui.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun onPackageNameChanged(value: String) {
        _uiState.update { it.copy(packageName = value) }
    }

    /** Reloads the installed-package list; leaves it unchanged on failure. */
    fun refresh() {
        viewModelScope.launch {
            repository.listPackages().onSuccess { response ->
                _uiState.update { it.copy(installed = response.packages) }
            }
        }
    }

    fun install() {
        val pkg = _uiState.value.packageName.trim()
        if (pkg.isEmpty() || _uiState.value.installing) return

        _uiState.update { it.copy(installing = true, message = null, log = "", isError = false) }
        viewModelScope.launch {
            repository.install(pkg)
                .onSuccess { response ->
                    val log = listOf(response.stdout, response.stderr)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    val message = if (response.installed) {
                        "Installed $pkg."
                    } else {
                        buildString {
                            append(response.error ?: "$pkg was not installed.")
                            response.systemRequirements?.takeIf { it.isNotBlank() }?.let {
                                append("\nNeeds system packages: ").append(it)
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(installing = false, isError = !response.installed, message = message, log = log)
                    }
                    if (response.installed) refresh()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(installing = false, isError = true, message = throwable.message ?: "Install request failed.")
                    }
                }
        }
    }
}
```

- [ ] **Step 5: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt \
        app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "app: PackagesViewModel for install + listing"
```

---

## Task 6: App — Packages screen + navigation

**Files:** Create `ui/packages/PackagesScreen.kt`; Modify `MainActivity.kt`, `ui/editor/EditorScreen.kt`. No unit test (Compose UI); verified by compile.

- [ ] **Step 1: Create the screen**

Create `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt`:
```kotlin
package com.rmobile.console.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    onBack: () -> Unit,
    viewModel: PackagesViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.packageName,
                    onValueChange = viewModel::onPackageNameChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("CRAN package") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                Button(
                    onClick = viewModel::install,
                    enabled = !uiState.installing && uiState.packageName.isNotBlank(),
                ) {
                    if (uiState.installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("Install")
                    }
                }
            }

            uiState.message?.let { message ->
                Text(
                    text = message,
                    color = if (uiState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (uiState.log.isNotBlank()) {
                Text(text = uiState.log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            Text("Installed", style = MaterialTheme.typography.titleMedium)
            if (uiState.installed.isEmpty()) {
                Text(
                    "No packages installed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.installed) { name ->
                        Text(text = name, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add a PACKAGES route in MainActivity**

In `MainActivity.kt`, add `PACKAGES` to the `Screen` enum and wire it in `AppRoot`. Replace the enum + `when` block:
```kotlin
private enum class Screen { EDITOR, SETTINGS, PACKAGES }

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }

    when (screen) {
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.EDITOR })
        Screen.PACKAGES -> PackagesScreen(onBack = { screen = Screen.EDITOR })
    }
}
```
Add the import: `import com.rmobile.console.ui.packages.PackagesScreen`.

- [ ] **Step 3: Add the "Packages" menu entry in EditorScreen**

In `ui/editor/EditorScreen.kt`, add an `onOpenPackages: () -> Unit` parameter to `EditorScreen` (next to `onOpenSettings`):
```kotlin
fun EditorScreen(
    onOpenSettings: () -> Unit,
    onOpenPackages: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
) {
```
In the overflow `DropdownMenu` (which already has the "Reset session" item), add a "Packages" item above/below it:
```kotlin
                        DropdownMenuItem(
                            text = { Text("Packages") },
                            onClick = {
                                menuOpen = false
                                onOpenPackages()
                            },
                        )
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt \
        app/src/main/java/com/rmobile/console/MainActivity.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorScreen.kt
git commit -m "app: Packages screen + overflow-menu navigation"
```

---

## Task 7: Full verification + docs

**Files:** Modify `backend/README.md`, `CLAUDE.md`.

- [ ] **Step 1: Full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; 0 test failures; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Full backend suite**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R`
Expected: all green including `test-packages.R` + `test-install.R`.

- [ ] **Step 3: backend/README.md**

Add a "Packages" subsection (after "Durable sessions"):
```markdown
## Packages

`POST /install` (body `{"package":"<name>"}`) installs a CRAN package into a
shared, persistent library (`R_PKG_LIB`, default `/data/rlib`, a named volume)
with a longer timeout (`R_INSTALL_TIMEOUT_SECONDS`, default 300s) from
`R_CRAN_REPO`. Installed packages are on `.libPaths()` for every run, so
`library()` (and inline `install.packages()`) work. `GET /packages` lists them.
Common system libraries are baked into the image; a package needing an un-baked
lib fails, and `/install` returns the apt command to add it (rebuild the image) —
there is no runtime apt (the container stays non-root + read-only-root).
```
Add to the security caveats list:
```markdown
- **`/install` runs arbitrary package code** (same RCE surface as `/execute`) and
  writes to a globally shared, unbounded library — a trojaned package persists
  for all sessions. It is auth/rate-limited; the package name is restricted to
  `^[A-Za-z0-9._]+$`.
```

- [ ] **Step 4: CLAUDE.md**

- Add `POST /install` + `GET /packages` and the `.libPaths` prelude line to the backend architecture description.
- In the "Response contract" section, note `InstallRequest`/`InstallResponse`/`PackagesResponse` (with the `@SerialName("package")` detail).
- Remove "package installation UI" from the "not built" list in "Current scope".

- [ ] **Step 5: Commit**
```bash
git add backend/README.md CLAUDE.md
git commit -m "docs: document package installation"
```

---

## Done

All spec cases covered: shared lib on `.libPaths`, `/install` (validation, timeout, `installed` check, system-req surfacing), `/packages`, pre-baked system libs + binary repo, the Packages screen, and tests (backend incl. a real `praise` install; app models + ViewModel). Open a PR from `feat/package-installation` (base `claude/r-app-android-version-ztmyd1`).
