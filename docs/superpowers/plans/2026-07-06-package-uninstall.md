# Package Uninstall Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Uninstall a CRAN package from the shared library — a `POST /uninstall` endpoint + a delete action per installed package in the Packages screen.

**Architecture:** Mirrors install. `POST /uninstall` runs `remove.packages(..., lib = PKG_LIB)` in the Plumber process and reports whether the package is gone; the app gets models/API/repository/ViewModel and a per-row delete button with a confirm dialog.

**Tech Stack:** R + Plumber, testthat + httr2, Kotlin + Compose, JUnit4.

---

## Prerequisites

- **Backend tests** (Docker): `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R`
- **App tests**: export `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`, `JKS="C:/Users/lauye/AppData/Local/Temp/claude/C--Users-lauye-Downloads-R-android/6471244e-45c9-4b58-a6cc-c90db51dfa4b/scratchpad/win-roots.jks"`, `GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JKS -Djavax.net.ssl.trustStorePassword=changeit"`; append `-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain` to gradle commands.
- **Branch:** `feat/package-uninstall` (already created; holds the spec).

---

## File Structure

- Modify: `backend/plumber.R` (`/uninstall`, `is_protected`); Create `backend/tests/test-uninstall.R`.
- Modify: `data/model/PackageModels.kt`, `data/network/RExecutionApi.kt`, `data/RExecutionRepository.kt`, `ui/packages/PackagesViewModel.kt`, `ui/packages/PackagesScreen.kt`, and the two test fakes (`EditorViewModelTest.kt`, `PackagesViewModelTest.kt`).
- Docs: `backend/README.md`, `CLAUDE.md`.

---

## Task 1: Backend `POST /uninstall`

**Files:** Modify `backend/plumber.R`; Create `backend/tests/test-uninstall.R`.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test-uninstall.R`:
```r
test_that("installing then uninstalling a package removes it", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  expect_true(api_request(srv, "/install", body = list(package = "praise"))$body$installed)
  expect_true("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
  res <- api_request(srv, "/uninstall", body = list(package = "praise"))
  expect_equal(res$status, 200)
  expect_true(res$body$removed)
  expect_false("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
})

test_that("uninstalling a not-installed package reports removed false", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/uninstall", body = list(package = "nonexistentpkgxyz"))
  expect_equal(res$status, 200)
  expect_false(res$body$removed)
})

test_that("uninstall rejects an invalid name", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  expect_equal(api_request(srv, "/uninstall", body = list(package = "../evil"))$status, 400)
})

test_that("uninstall is auth-protected", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_API_KEY = "secret"))
  expect_equal(api_request(srv, "/uninstall", body = list(package = "praise"))$status, 401)
})
```

- [ ] **Step 2: Run the suite — verify FAIL**

Run the backend verification command. Expected: the new tests fail (no `/uninstall` route).

- [ ] **Step 3: Implement in `backend/plumber.R`**

Change `is_protected` to include `/uninstall`:
```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall")
```
Add the endpoint (after `/install`):
```r
#* Uninstall a package from the shared library
#* @post /uninstall
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  pkg <- body$package
  if (is.null(pkg) || !is.character(pkg) || length(pkg) != 1 || !grepl("^[A-Za-z0-9._]+$", pkg)) {
    res$status <- 400
    return(list(removed = FALSE, error = "Invalid or missing 'package' name."))
  }
  before <- pkg %in% rownames(installed.packages(lib.loc = PKG_LIB))
  err <- tryCatch({
    if (before) suppressWarnings(remove.packages(pkg, lib = PKG_LIB))
    NULL
  }, error = function(e) conditionMessage(e))
  after <- pkg %in% rownames(installed.packages(lib.loc = PKG_LIB))
  list(removed = before && !after, error = err)
}
```

- [ ] **Step 4: Run the suite — verify PASS**

Run the backend verification command. Expected: all pass (the install→uninstall test does a real `praise` install then removes it).

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/plumber.R backend/tests/test-uninstall.R
git commit -m "backend: POST /uninstall removes a package from the shared library"
```

---

## Task 2: App models + API + repository

**Files:** Modify `data/model/PackageModels.kt`, `data/network/RExecutionApi.kt`, `data/RExecutionRepository.kt`, `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`, `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`.

- [ ] **Step 1: Add the models**

In `data/model/PackageModels.kt`, add (the file already imports `SerialName`/`Serializable`):
```kotlin
@Serializable
data class UninstallRequest(
    @SerialName("package") val packageName: String,
)

@Serializable
data class UninstallResponse(
    val removed: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 2: Add the API method**

In `data/network/RExecutionApi.kt`, add imports for `UninstallRequest`/`UninstallResponse` and:
```kotlin
    @POST("uninstall")
    suspend fun uninstall(@Body request: UninstallRequest): UninstallResponse
```

- [ ] **Step 3: Add the repository method**

In `data/RExecutionRepository.kt`, add imports and:
```kotlin
    suspend fun uninstall(packageName: String): Result<UninstallResponse> =
        runCatching { api.uninstall(UninstallRequest(packageName)) }
```

- [ ] **Step 4: Fix the two test fakes**

Adding `uninstall` to `RExecutionApi` breaks both fakes. In BOTH
`EditorViewModelTest.kt` and `PackagesViewModelTest.kt`, add the import
`import com.rmobile.console.data.model.UninstallRequest` and
`import com.rmobile.console.data.model.UninstallResponse`, and add an override to
each `FakeApi`:
- In `EditorViewModelTest.kt`'s `FakeApi` (stub):
```kotlin
        override suspend fun uninstall(request: UninstallRequest): UninstallResponse = UninstallResponse(removed = true)
```
- In `PackagesViewModelTest.kt`'s `FakeApi`, add a configurable field + override:
```kotlin
        var uninstallResponse: UninstallResponse = UninstallResponse(removed = true),
```
(add as a constructor parameter of `FakeApi`) and
```kotlin
        override suspend fun uninstall(request: UninstallRequest): UninstallResponse = uninstallResponse
```

- [ ] **Step 5: Compile + existing tests**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures (both fakes now satisfy the interface).

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/rmobile/console/data/model/PackageModels.kt \
        app/src/main/java/com/rmobile/console/data/network/RExecutionApi.kt \
        app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt \
        app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "app: uninstall models, API, repository (+ fakes)"
```

---

## Task 3: `PackagesViewModel.uninstall`

**Files:** Modify `ui/packages/PackagesViewModel.kt`, `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`.

- [ ] **Step 1: Write the failing tests**

In `PackagesViewModelTest.kt`, add:
```kotlin
    @Test
    fun `uninstall removes the package and refreshes the list`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(listOf("praise")),
            uninstallResponse = UninstallResponse(removed = true),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(emptyList())
        vm.uninstall("praise")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.isError)
        assertTrue(s.message!!.contains("praise"))
        assertTrue(s.installed.isEmpty())
    }

    @Test
    fun `uninstall that removed nothing surfaces an error`() = runTest {
        val vm = viewModel(FakeApi(uninstallResponse = UninstallResponse(removed = false, error = "not there")))
        advanceUntilIdle()
        vm.uninstall("ghost")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isError)
        assertTrue(vm.uiState.value.message!!.contains("not there"))
    }
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (`uninstall` doesn't exist).

- [ ] **Step 3: Add `uninstall` to the ViewModel**

In `ui/packages/PackagesViewModel.kt`, add:
```kotlin
    /** Uninstalls a package from the shared library, then refreshes the list. */
    fun uninstall(packageName: String) {
        viewModelScope.launch {
            repository.uninstall(packageName)
                .onSuccess { response ->
                    if (response.removed) {
                        _uiState.update { it.copy(isError = false, message = "Removed $packageName.") }
                        refresh()
                    } else {
                        _uiState.update {
                            it.copy(isError = true, message = response.error ?: "$packageName was not removed.")
                        }
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isError = true, message = throwable.message ?: "Uninstall failed.") }
                }
        }
    }
```

- [ ] **Step 4: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "app: PackagesViewModel.uninstall"
```

---

## Task 4: Delete UI + verification + docs

**Files:** Modify `ui/packages/PackagesScreen.kt`, `README.md`, `backend/README.md`, `CLAUDE.md`.

**Read `PackagesScreen.kt` first.** The installed list is a `LazyColumn` whose `items(uiState.installed) { name -> Text(name, ...); HorizontalDivider() }`. Change each item to a `Row` with the name (weight 1) + a delete `IconButton`, and add a confirm dialog.

- [ ] **Step 1: Add delete state + confirm dialog**

Near the top of `PackagesScreen`, add:
```kotlin
    var uninstallTarget by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 2: Give each installed row a delete button**

Replace the installed-list `items(...) { name -> ... }` body with:
```kotlin
                    items(uiState.installed) { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { uninstallTarget = name }) {
                                Icon(Icons.Default.Delete, contentDescription = "Uninstall $name")
                            }
                        }
                        HorizontalDivider()
                    }
```

- [ ] **Step 3: Add the confirm dialog at the end of the composable**

```kotlin
    uninstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("Uninstall $target?") },
            text = { Text("Removes $target from the shared library. Other packages that depend on it may stop loading.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(target)
                    uninstallTarget = null
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text("Cancel") } },
        )
    }
```

- [ ] **Step 4: Add imports**

Add any missing imports (check first, no duplicates): `androidx.compose.foundation.layout.Row`, `androidx.compose.ui.Alignment`, `androidx.compose.material.icons.filled.Delete`, `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `androidx.compose.foundation.layout.weight` (weight is a `RowScope` extension — no import needed), `androidx.compose.material.icons.Icons`, `androidx.compose.material3.Icon`, `androidx.compose.material3.IconButton`, `androidx.compose.runtime.mutableStateOf`/`remember`/`getValue`/`setValue`.

- [ ] **Step 5: Full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; 0 test failures; APK exists.

- [ ] **Step 6: Docs**

- `backend/README.md`: under the "Packages" section / endpoints, add a `POST /uninstall` note (body `{"package"}`, removes from the shared lib, `{removed, error}`, same auth/rate-limit as `/install`).
- `CLAUDE.md`: in the response contract, add `UninstallRequest`/`UninstallResponse` and note `is_protected` now covers `/uninstall`.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt \
        README.md backend/README.md CLAUDE.md
git commit -m "app: uninstall button in Packages screen + docs"
```

---

## Done

Packages can be uninstalled from the shared library via `POST /uninstall` and a
per-package delete action. Open a PR from `feat/package-uninstall` (base
`claude/r-app-android-version-ztmyd1`).
