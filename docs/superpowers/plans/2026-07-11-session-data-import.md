# Session Data Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user upload arbitrary data files from their phone into a project's R session so code like `read.csv("sales.csv")` reads real data.

**Architecture:** Each backend session gets a persistent `data/` dir (`SESSION_DIR/<id>/data`). `POST /upload` (multipart) writes files there; `GET /data`/`POST /delete-data` manage them; `/execute` symlinks them into the ephemeral run dir so code reads by bare filename. The app adds a dedicated **Data** screen (upload/list/delete) parallel to Packages, streaming uploads through a no-timeout OkHttp client so app + backend memory stay bounded.

**Tech Stack:** R/Plumber (`webutils::parse_multipart`, `file.symlink`), Kotlin/Jetpack Compose, Retrofit multipart, OkHttp/Okio streaming, kotlinx.serialization, JUnit + testthat/httr2.

**Reference spec:** `docs/superpowers/specs/2026-07-11-session-data-import-design.md`

**Environment notes for the implementer:**
- The backend testthat harness **cannot run in this Windows sandbox** (nested `Rscript` spawns segfault under processx). Backend tasks are verified by inspection + CI (`.github/workflows`). Do not block on running `backend/run-tests.R` locally; commit and let CI gate.
- App builds/tests DO run locally: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest`.
- Backend must emit **unboxed** JSON (already configured in `run.R`); length-1 vectors serialize as scalars.

---

## File Structure

**Backend (`backend/`)**
- `plumber.R` — Modify: add `$data` to `session_paths`; add `UPLOAD_MAX_BYTES`, `DATA_RESERVED`, `sanitize_data_name`; extend `is_protected`; add `POST /upload`, `GET /data`, `POST /delete-data`; symlink data in `/execute`; clear data in `/reset`.
- `docker-compose.yml`, `docker-compose.hardened.yml` — Modify: raise `mem_limit` to `2g`.
- `tests/helper-server.R` — Modify: add `post_upload`, `get_data`, `post_delete_data`.
- `tests/test-data.R` — Create: upload/list/delete/read-in-execute/cap/reserved/scoping/reset tests.
- `README.md` — Modify: document the three endpoints + the memory-vs-cap note.

**App (`app/src/main/java/com/rmobile/console/`)**
- `data/model/DataModels.kt` — Create: `DataFile`, `DataFilesResponse`, `UploadResponse`, `DeleteDataRequest`, `DeleteDataResponse`.
- `data/network/RDataApi.kt` — Create: `upload`/`dataFiles`/`deleteData` Retrofit interface.
- `data/network/UriRequestBody.kt` — Create: streaming `RequestBody` over a content `Uri`.
- `data/network/NetworkModule.kt` — Modify: add `rDataApi` on a no-callTimeout client sharing the host interceptor.
- `data/RExecutionRepository.kt` — Modify: add `uploadFile`/`listDataFiles`/`deleteDataFile` + `dataApi` ctor param.
- `ui/data/ByteSize.kt` — Create: pure `formatByteSize`.
- `ui/data/DataUiState.kt` — Create.
- `ui/data/DataViewModel.kt` — Create.
- `ui/data/DataScreen.kt` — Create.
- `ui/editor/EditorViewModel.kt` — Modify: add `insertText`.
- `MainActivity.kt` — Modify: add `DATA` screen + nav + tap-to-insert wiring.

**App tests (`app/src/test/java/com/rmobile/console/`)**
- `data/RExecutionRepositoryTest.kt` — Modify: add a `FakeDataApi` + 3 tests.
- `ui/data/ByteSizeTest.kt` — Create.
- `ui/data/DataViewModelTest.kt` — Create.

---

## Task 1: Backend — session data dir, name sanitization, and `POST /upload`

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Add `$data` to `session_paths` and the upload constants**

In `backend/plumber.R`, add `data` to the `session_paths` list (after `rlib`):

```r
session_paths <- function(session_id) {
  dir <- file.path(SESSION_DIR, session_id)
  list(
    dir = dir,
    workspace = file.path(dir, "workspace.RData"),
    attached = file.path(dir, "attached.txt"),
    rlib = file.path(dir, "rlib"),
    data = file.path(dir, "data")
  )
}
```

After the `LEGACY_PKG_LIB`/`INSTALL_TIMEOUT_SECONDS` block, add:

```r
# Max bytes accepted by POST /upload. Default 1 GiB. NOTE: plumber buffers the
# whole multipart body in memory to parse it, so the container's mem_limit must
# exceed this (see docker-compose.yml). Operators lowering this can lower mem_limit.
UPLOAD_MAX_BYTES <- as.numeric(Sys.getenv("R_UPLOAD_MAX_BYTES", "1073741824"))

# File names the run harness writes itself; a data file may not claim them.
DATA_RESERVED <- c("script.R", "objects.txt", "main.R")

# Reduce an uploaded filename to a safe basename: strip path, replace disallowed
# characters with "_", drop leading dots, and reject reserved/empty names.
sanitize_data_name <- function(fname) {
  if (is.null(fname) || !is.character(fname) || length(fname) != 1 || !nzchar(fname)) return(NULL)
  base <- basename(fname)
  base <- gsub("[^A-Za-z0-9._-]", "_", base)
  base <- sub("^\\.+", "", base)
  if (!nzchar(base)) return(NULL)
  if (base %in% DATA_RESERVED) return(NULL)
  if (grepl("^plot[0-9]+\\.png$", base)) return(NULL)
  if (grepl("^table[0-9]+\\.json$", base)) return(NULL)
  base
}
```

- [ ] **Step 2: Extend `is_protected` to cover the new endpoints**

```r
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy", "/symbols", "/help", "/upload", "/data", "/delete-data")
```

- [ ] **Step 3: Add the `POST /upload` handler**

Add after the `/help` endpoint (or any endpoint block; order among endpoints doesn't matter):

```r
#* Upload a data file into a session's data dir (multipart/form-data, part "file").
#* @post /upload
function(req, res, sessionId = "default") {
  session_id <- sanitize_session_id(sessionId)
  ct <- req$HTTP_CONTENT_TYPE
  if (is.null(ct) || !grepl("multipart/form-data", ct, ignore.case = TRUE)) {
    res$status <- 400
    return(list(name = "", size = 0, error = "Expected multipart/form-data."))
  }
  boundary <- sub('^.*boundary=', '', ct)
  parts <- tryCatch(webutils::parse_multipart(req$bodyRaw, boundary), error = function(e) NULL)
  part <- if (!is.null(parts)) parts[["file"]] else NULL
  if (is.null(part) || is.null(part$value)) {
    res$status <- 400
    return(list(name = "", size = 0, error = "Missing 'file' part."))
  }
  raw <- part$value
  size <- length(raw)
  if (size > UPLOAD_MAX_BYTES) {
    res$status <- 413
    return(list(name = "", size = 0, error = sprintf("File exceeds the %.0f-byte limit.", UPLOAD_MAX_BYTES)))
  }
  name <- sanitize_data_name(part$filename)
  if (is.null(name)) {
    res$status <- 400
    return(list(name = "", size = 0, error = "Invalid or reserved file name."))
  }
  paths <- session_paths(session_id)
  dir.create(paths$data, recursive = TRUE, showWarnings = FALSE)
  writeBin(raw, file.path(paths$data, name))
  list(name = name, size = size)
}
```

- [ ] **Step 4: Sanity-check the R parses**

Run: `JAVA_HOME=... ` is not needed here. Run:
```bash
"/c/Program Files/R/R-4.5.2/bin/Rscript" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"
```
Expected: `parse OK` (no syntax error). This only checks parsing — the endpoints are exercised by CI (Task 3).

- [ ] **Step 5: Commit**

```bash
git add backend/plumber.R
git commit -m "$(cat <<'EOF'
backend: session data dir + POST /upload endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Backend — `GET /data`, `POST /delete-data`, execute symlink-in, reset cleanup

**Files:**
- Modify: `backend/plumber.R`

- [ ] **Step 1: Add `GET /data` and `POST /delete-data`**

Add near the `/upload` handler:

```r
#* List a session's uploaded data files.
#* @get /data
function(req, res, sessionId = "default") {
  paths <- session_paths(sanitize_session_id(sessionId))
  if (!dir.exists(paths$data)) return(list(files = list()))
  names_sorted <- sort(list.files(paths$data, full.names = FALSE))
  files <- lapply(names_sorted, function(n) {
    list(name = n, size = as.numeric(file.info(file.path(paths$data, n))$size))
  })
  list(files = files)
}

#* Delete one uploaded data file from a session.
#* @post /delete-data
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  name <- sanitize_data_name(body$name)
  session_id <- sanitize_session_id(body$sessionId)
  if (is.null(name)) return(list(removed = FALSE))
  target <- file.path(session_paths(session_id)$data, name)
  removed <- file.exists(target) && unlink(target) == 0
  list(removed = isTRUE(removed))
}
```

- [ ] **Step 2: Clear `data/` on reset**

In the `POST /reset` handler, change the `unlink` line to also remove the data dir:

```r
  unlink(c(paths$workspace, paths$attached), force = TRUE)
  unlink(paths$data, recursive = TRUE, force = TRUE)
  if (isTRUE(body$purgePackages)) unlink(paths$rlib, recursive = TRUE, force = TRUE)
```

- [ ] **Step 3: Symlink data files into the run dir in `/execute`**

In the `/execute` handler, immediately after the block that creates `paths$rlib`
(the line `dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)`, and
before `objects_path <- ...`), insert:

```r
  # Expose the session's uploaded data files by symlinking them into the run dir
  # (the working directory), so user code reads them by bare filename without
  # copying potentially gigabyte-sized files on every run. Entry/harness files are
  # already written, so file.exists() guards a data file from shadowing them.
  if (dir.exists(paths$data)) {
    for (df in list.files(paths$data, full.names = FALSE)) {
      link <- file.path(run_dir, df)
      if (!file.exists(link)) {
        try(file.symlink(file.path(paths$data, df), link), silent = TRUE)
      }
    }
  }
```

(Note: the entry files are written at lines ~148–156, before `paths` is computed at
~161–164, so at this insertion point they already exist in `run_dir`; `script.R`
and `objects.txt` are written later but can never be data files — they're in
`DATA_RESERVED`.)

- [ ] **Step 4: Sanity-check the R parses**

Run:
```bash
"/c/Program Files/R/R-4.5.2/bin/Rscript" -e "invisible(parse('backend/plumber.R')); cat('parse OK\n')"
```
Expected: `parse OK`.

- [ ] **Step 5: Commit**

```bash
git add backend/plumber.R
git commit -m "$(cat <<'EOF'
backend: /data list + /delete-data + execute symlink-in + reset cleanup

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Backend — integration tests

**Files:**
- Modify: `backend/tests/helper-server.R`
- Create: `backend/tests/test-data.R`

- [ ] **Step 1: Add multipart + data helpers to `helper-server.R`**

Append to `backend/tests/helper-server.R`:

```r
post_upload <- function(server, path, session_id = NULL, key = NULL) {
  req <- request(server$base_url) |>
    req_url_path("/upload") |>
    req_error(is_error = function(resp) FALSE)
  if (!is.null(session_id)) req <- req_url_query(req, sessionId = session_id)
  if (!is.null(key)) req <- req_headers(req, "X-API-Key" = key)
  req <- req_body_multipart(req, file = curl::form_file(path, type = "application/octet-stream"))
  resp <- req_perform(req)
  list(status = resp_status(resp), body = tryCatch(resp_body_json(resp), error = function(e) NULL))
}

get_data <- function(server, session_id = NULL, key = NULL) {
  query <- if (!is.null(session_id)) list(sessionId = session_id) else NULL
  api_request(server, "/data", query = query, key = key)
}

post_delete_data <- function(server, name, session_id = NULL, key = NULL) {
  body <- list(name = name)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/delete-data", body = body, key = key)
}
```

- [ ] **Step 2: Write `backend/tests/test-data.R`**

```r
test_that("uploaded file is listed with its size and is readable from executed code", {
  server <- local_server()
  csv <- tempfile(fileext = ".csv")
  writeLines(c("x,y", "1,2", "3,4"), csv)

  up <- post_upload(server, csv, session_id = "s1")
  expect_equal(up$status, 200)
  expect_true(nchar(up$body$name) > 0)
  expect_true(up$body$size > 0)

  listing <- get_data(server, session_id = "s1")
  expect_equal(listing$status, 200)
  names_seen <- vapply(listing$body$files, function(f) f$name, character(1))
  expect_true(up$body$name %in% names_seen)

  code <- sprintf('d <- read.csv("%s"); cat(sum(d$y))', up$body$name)
  run <- post_execute(server, code, session_id = "s1")
  expect_equal(run$status, 200)
  expect_match(run$body$stdout, "6")
})

test_that("delete-data removes a file", {
  server <- local_server()
  f <- tempfile(fileext = ".txt")
  writeLines("hello", f)
  up <- post_upload(server, f, session_id = "s1")

  del <- post_delete_data(server, up$body$name, session_id = "s1")
  expect_equal(del$status, 200)
  expect_true(del$body$removed)

  listing <- get_data(server, session_id = "s1")
  names_seen <- vapply(listing$body$files, function(x) x$name, character(1))
  expect_false(up$body$name %in% names_seen)
})

test_that("a reserved file name is rejected", {
  server <- local_server()
  f <- file.path(tempdir(), "script.R")
  writeLines("x <- 1", f)
  up <- post_upload(server, f, session_id = "s1")
  expect_equal(up$status, 400)
})

test_that("an over-cap upload is rejected with 413", {
  server <- local_server(env = list(R_UPLOAD_MAX_BYTES = 8))
  f <- tempfile(fileext = ".bin")
  writeBin(as.raw(rep(1, 64)), f)
  up <- post_upload(server, f, session_id = "s1")
  expect_equal(up$status, 413)
})

test_that("data files are scoped per session", {
  server <- local_server()
  f <- tempfile(fileext = ".csv")
  writeLines("a,b\n1,2", f)
  up <- post_upload(server, f, session_id = "sA")

  other <- get_data(server, session_id = "sB")
  expect_equal(length(other$body$files), 0)
})

test_that("reset clears uploaded data", {
  server <- local_server()
  f <- tempfile(fileext = ".csv")
  writeLines("a,b\n1,2", f)
  post_upload(server, f, session_id = "s1")

  post_reset(server, session_id = "s1")
  listing <- get_data(server, session_id = "s1")
  expect_equal(length(listing$body$files), 0)
})
```

- [ ] **Step 3: Do NOT run locally (segfault) — verify by inspection, push, let CI run**

The nested-`Rscript` segfault prevents `Rscript backend/run-tests.R` here. Re-read the
test file and `helper-server.R` for correctness. CI (`.github/workflows`) runs the
backend suite on push.

- [ ] **Step 4: Commit**

```bash
git add backend/tests/helper-server.R backend/tests/test-data.R
git commit -m "$(cat <<'EOF'
backend: integration tests for data upload/list/delete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Backend — raise container memory + document endpoints

**Files:**
- Modify: `backend/docker-compose.yml`
- Modify: `backend/docker-compose.hardened.yml`
- Modify: `backend/README.md`

- [ ] **Step 1: Raise `mem_limit` in both compose files**

In `backend/docker-compose.yml`, change `mem_limit: 512m` to:

```yaml
    # Raised above the default because POST /upload buffers the whole multipart
    # body in memory (see R_UPLOAD_MAX_BYTES, default 1 GiB). Lower both together.
    mem_limit: 2g
```

Apply the same `mem_limit: 2g` change in `backend/docker-compose.hardened.yml` if it
sets `mem_limit` (search for `mem_limit`); if it uses `deploy.resources.limits.memory`,
set that to `2g` instead. If the hardened file inherits from the base via `extends`
or has no memory setting, add `mem_limit: 2g` to its service.

- [ ] **Step 2: Document the endpoints in `backend/README.md`**

Add a "Data files" subsection near the packages/endpoints docs:

```markdown
### Data files (`/upload`, `/data`, `/delete-data`)

Each session has a persistent `data/` dir (`SESSION_DIR/<id>/data`) for uploaded
files. Executed code reads them by bare filename (they're symlinked into the run
dir): `read.csv("sales.csv")`, `readRDS("model.rds")`.

- `POST /upload?sessionId=<id>` — `multipart/form-data` with one part named `file`.
  Filename is reduced to a safe basename (disallowed chars → `_`, reserved names
  rejected). Size cap `R_UPLOAD_MAX_BYTES` (default 1 GiB). Returns `{name, size}`.
- `GET /data?sessionId=<id>` → `{files: [{name, size}]}`.
- `POST /delete-data` — `{name, sessionId}` → `{removed}`.

Uploaded data is cleared by `POST /reset` and when a project is deleted.

**Memory:** plumber buffers the whole upload in memory to parse the multipart
body, so the container `mem_limit` (docker-compose) must exceed `R_UPLOAD_MAX_BYTES`.
They move together — lower the cap and you can lower the limit.

**Symlink caveat:** a data file is symlinked (not copied) into the run dir, so code
that *writes* to that filename writes through to the stored copy. Fine for read-only
data; re-upload to replace.
```

- [ ] **Step 3: Commit**

```bash
git add backend/docker-compose.yml backend/docker-compose.hardened.yml backend/README.md
git commit -m "$(cat <<'EOF'
backend: raise mem_limit for uploads + document data endpoints

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: App — models, `RDataApi`, upload client, streaming body, repository

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/model/DataModels.kt`
- Create: `app/src/main/java/com/rmobile/console/data/network/RDataApi.kt`
- Create: `app/src/main/java/com/rmobile/console/data/network/UriRequestBody.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/network/NetworkModule.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt`
- Test: `app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository tests**

Add to `RExecutionRepositoryTest.kt`. First add imports at the top:

```kotlin
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.model.DataFilesResponse
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.DeleteDataResponse
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.network.RDataApi
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
```

Add a fake `RDataApi` inside the class:

```kotlin
    private class FakeDataApi(
        var dataFilesResponse: DataFilesResponse = DataFilesResponse(listOf(DataFile("a.csv", 10))),
        var uploadResponse: UploadResponse = UploadResponse("a.csv", 10),
        var deleteResponse: DeleteDataResponse = DeleteDataResponse(removed = true),
    ) : RDataApi {
        var lastUploadSession: String? = null
        var lastDataSession: String? = null
        var lastDelete: DeleteDataRequest? = null
        override suspend fun upload(sessionId: String, file: MultipartBody.Part): UploadResponse {
            lastUploadSession = sessionId; return uploadResponse
        }
        override suspend fun dataFiles(sessionId: String): DataFilesResponse {
            lastDataSession = sessionId; return dataFilesResponse
        }
        override suspend fun deleteData(request: DeleteDataRequest): DeleteDataResponse {
            lastDelete = request; return deleteResponse
        }
    }
```

Add the tests:

```kotlin
    @Test
    fun `listDataFiles passes the session and returns files`() = runTest {
        val dataApi = FakeDataApi()
        val result = RExecutionRepository(FakeApi(), dataApi).listDataFiles("proj-9")
        assertEquals("proj-9", dataApi.lastDataSession)
        assertEquals(listOf(DataFile("a.csv", 10)), result.getOrNull())
    }

    @Test
    fun `uploadFile passes the session`() = runTest {
        val dataApi = FakeDataApi()
        val part = MultipartBody.Part.createFormData("file", "a.csv", "x,y".toRequestBody())
        val result = RExecutionRepository(FakeApi(), dataApi).uploadFile("proj-9", part)
        assertEquals("proj-9", dataApi.lastUploadSession)
        assertEquals(UploadResponse("a.csv", 10), result.getOrNull())
    }

    @Test
    fun `deleteDataFile passes name and session and returns removed`() = runTest {
        val dataApi = FakeDataApi()
        val result = RExecutionRepository(FakeApi(), dataApi).deleteDataFile("a.csv", "proj-9")
        assertEquals("a.csv", dataApi.lastDelete!!.name)
        assertEquals("proj-9", dataApi.lastDelete!!.sessionId)
        assertTrue(result.getOrNull() == true)
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile / fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.RExecutionRepositoryTest"`
Expected: compile failure — `DataModels`, `RDataApi`, and the repository methods don't exist yet.

- [ ] **Step 3: Create `DataModels.kt`**

```kotlin
package com.rmobile.console.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DataFile(val name: String, val size: Long)

@Serializable
data class DataFilesResponse(val files: List<DataFile> = emptyList())

@Serializable
data class UploadResponse(val name: String = "", val size: Long = 0)

@Serializable
data class DeleteDataRequest(val name: String, val sessionId: String? = null)

@Serializable
data class DeleteDataResponse(val removed: Boolean = false)
```

- [ ] **Step 4: Create `RDataApi.kt`**

```kotlin
package com.rmobile.console.data.network

import com.rmobile.console.data.model.DataFilesResponse
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.DeleteDataResponse
import com.rmobile.console.data.model.UploadResponse
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * Data-file endpoints. Served by a dedicated OkHttp client (no overall call
 * timeout) so large uploads aren't aborted; see [NetworkModule.rDataApi].
 */
interface RDataApi {
    @Multipart
    @POST("upload")
    suspend fun upload(
        @Query("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
    ): UploadResponse

    @GET("data")
    suspend fun dataFiles(@Query("sessionId") sessionId: String): DataFilesResponse

    @POST("delete-data")
    suspend fun deleteData(@Body request: DeleteDataRequest): DeleteDataResponse
}
```

- [ ] **Step 5: Create `UriRequestBody.kt`**

```kotlin
package com.rmobile.console.data.network

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * Streams a content [Uri]'s bytes into an OkHttp request body without buffering
 * the whole file in memory. Reopens the stream on each [writeTo], so it is safe
 * to reuse (not one-shot).
 */
class UriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentLength: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        stream.source().use { sink.writeAll(it) }
    }
}
```

- [ ] **Step 6: Add the upload client + `rDataApi` to `NetworkModule.kt`**

After the `rExecutionApi` block, add:

```kotlin
    // A client for data endpoints: no overall call timeout (a 1 GiB upload over a
    // LAN can exceed the 30s cap on the main client), but a read timeout so a hung
    // backend still fails. Shares the host-rewriting interceptor so the runtime
    // backend URL + API key still apply. No logging interceptor (don't log bodies).
    private val dataClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(hostSelectionInterceptor)
            .build()
    }

    val rDataApi: RDataApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.R_EXECUTION_BASE_URL)
            .client(dataClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RDataApi::class.java)
    }
```

- [ ] **Step 7: Add the repository methods**

In `RExecutionRepository.kt`, add imports:

```kotlin
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.model.DeleteDataRequest
import com.rmobile.console.data.model.UploadResponse
import com.rmobile.console.data.network.NetworkModule
import com.rmobile.console.data.network.RDataApi
import okhttp3.MultipartBody
```

Change the constructor and add methods:

```kotlin
class RExecutionRepository(
    private val api: RExecutionApi,
    private val dataApi: RDataApi = NetworkModule.rDataApi,
) {
    // ...existing methods unchanged...

    suspend fun uploadFile(sessionId: String, file: MultipartBody.Part): Result<UploadResponse> =
        runCatching { dataApi.upload(sessionId, file) }

    suspend fun listDataFiles(sessionId: String = DEFAULT_SESSION_ID): Result<List<DataFile>> =
        runCatching { dataApi.dataFiles(sessionId).files }

    suspend fun deleteDataFile(name: String, sessionId: String = DEFAULT_SESSION_ID): Result<Boolean> =
        runCatching { dataApi.deleteData(DeleteDataRequest(name, sessionId)).removed }
```

- [ ] **Step 8: Run the repository tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.RExecutionRepositoryTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/model/DataModels.kt \
        app/src/main/java/com/rmobile/console/data/network/RDataApi.kt \
        app/src/main/java/com/rmobile/console/data/network/UriRequestBody.kt \
        app/src/main/java/com/rmobile/console/data/network/NetworkModule.kt \
        app/src/main/java/com/rmobile/console/data/RExecutionRepository.kt \
        app/src/test/java/com/rmobile/console/data/RExecutionRepositoryTest.kt
git commit -m "$(cat <<'EOF'
app: data models, RDataApi, streaming upload client, repository methods

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: App — human-readable byte-size formatter

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/data/ByteSize.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/data/ByteSizeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rmobile.console.ui.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeTest {
    @Test fun `bytes under 1 KB`() = assertEquals("512 B", formatByteSize(512))
    @Test fun `zero bytes`() = assertEquals("0 B", formatByteSize(0))
    @Test fun `exact kilobyte has no decimal`() = assertEquals("1 KB", formatByteSize(1024))
    @Test fun `kilobytes with one decimal`() = assertEquals("1.5 KB", formatByteSize(1536))
    @Test fun `exact megabyte`() = assertEquals("1 MB", formatByteSize(1024L * 1024))
    @Test fun `exact gigabyte`() = assertEquals("1 GB", formatByteSize(1024L * 1024 * 1024))
    @Test fun `negative is clamped`() = assertEquals("0 B", formatByteSize(-5))
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.data.ByteSizeTest"`
Expected: compile failure — `formatByteSize` doesn't exist.

- [ ] **Step 3: Implement `ByteSize.kt`**

```kotlin
package com.rmobile.console.ui.data

import kotlin.math.roundToInt

/** Formats a byte count as a short human-readable string, e.g. "1.5 KB", "1 GB". */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "${if (bytes < 0) 0 else bytes} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    val rounded = (value * 10).roundToInt() / 10.0
    val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    return "$text ${units[i]}"
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.data.ByteSizeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/data/ByteSize.kt \
        app/src/test/java/com/rmobile/console/ui/data/ByteSizeTest.kt
git commit -m "$(cat <<'EOF'
app: human-readable byte-size formatter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: App — `DataUiState` + `DataViewModel`

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/data/DataUiState.kt`
- Create: `app/src/main/java/com/rmobile/console/ui/data/DataViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/data/DataViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
package com.rmobile.console.ui.data

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.DataFile
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
import com.rmobile.console.data.network.RDataApi
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class NoopExecApi : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest) = ExecuteResponse()
        override suspend fun reset(request: ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest) = InstallResponse()
        override suspend fun uninstall(request: UninstallRequest) = UninstallResponse()
        override suspend fun packages(sessionId: String) = PackagesResponse()
        override suspend fun importLegacy(request: ImportLegacyRequest) = ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = SymbolsResponse()
        override suspend fun help(request: HelpRequest) = HelpResponse()
    }

    private class FakeDataApi(
        var files: DataFilesResponse = DataFilesResponse(listOf(DataFile("a.csv", 10))),
        var upload: UploadResponse = UploadResponse("b.csv", 20),
        var delete: DeleteDataResponse = DeleteDataResponse(removed = true),
    ) : RDataApi {
        var lastDataSession: String? = null
        var lastUploadSession: String? = null
        var lastDelete: DeleteDataRequest? = null
        override suspend fun upload(sessionId: String, file: MultipartBody.Part): UploadResponse {
            lastUploadSession = sessionId; return upload
        }
        override suspend fun dataFiles(sessionId: String): DataFilesResponse {
            lastDataSession = sessionId; return files
        }
        override suspend fun deleteData(request: DeleteDataRequest): DeleteDataResponse {
            lastDelete = request; return delete
        }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored: List<Project> = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun vm(
        dataApi: FakeDataApi,
        projectStore: ProjectStore = InMemoryProjectStore(),
    ) = DataViewModel(RExecutionRepository(NoopExecApi(), dataApi), projectStore)

    private fun part() =
        MultipartBody.Part.createFormData("file", "b.csv", "x".toRequestBody())

    @Test
    fun `loads files on init`() = runTest {
        val vm = vm(FakeDataApi(files = DataFilesResponse(listOf(DataFile("a.csv", 10)))))
        advanceUntilIdle()
        assertEquals(listOf(DataFile("a.csv", 10)), vm.uiState.value.files)
    }

    @Test
    fun `upload refreshes the list`() = runTest {
        val api = FakeDataApi(files = DataFilesResponse(emptyList()))
        val vm = vm(api)
        advanceUntilIdle()
        api.files = DataFilesResponse(listOf(DataFile("b.csv", 20)))
        vm.upload(part())
        advanceUntilIdle()
        assertEquals(listOf(DataFile("b.csv", 20)), vm.uiState.value.files)
        assertTrue(!vm.uiState.value.uploading)
    }

    @Test
    fun `delete refreshes the list`() = runTest {
        val api = FakeDataApi(files = DataFilesResponse(listOf(DataFile("a.csv", 10))))
        val vm = vm(api)
        advanceUntilIdle()
        api.files = DataFilesResponse(emptyList())
        vm.delete("a.csv")
        advanceUntilIdle()
        assertEquals("a.csv", api.lastDelete!!.name)
        assertTrue(vm.uiState.value.files.isEmpty())
    }

    @Test
    fun `uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 100, name = "MyProj", now = 0)
        val store = InMemoryProjectStore(initial = listOf(project), lastId = 100)
        val api = FakeDataApi()
        vm(api, store)
        advanceUntilIdle()
        assertEquals("proj-100", api.lastDataSession)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.data.DataViewModelTest"`
Expected: compile failure — `DataViewModel`/`DataUiState` don't exist.

- [ ] **Step 3: Create `DataUiState.kt`**

```kotlin
package com.rmobile.console.ui.data

import com.rmobile.console.data.model.DataFile

data class DataUiState(
    val projectName: String = "",
    val files: List<DataFile> = emptyList(),
    val isLoading: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 4: Create `DataViewModel.kt`**

```kotlin
package com.rmobile.console.ui.data

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
import okhttp3.MultipartBody

class DataViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataUiState())
    val uiState: StateFlow<DataUiState> = _uiState.asStateFlow()

    private val session: String

    init {
        val projects = projectStore.loadProjects()
        val active = projects.firstOrNull { it.id == projectStore.loadLastOpenProjectId() }
            ?: projects.firstOrNull()
        session = active?.let { ProjectSession.of(it) } ?: RExecutionRepository.DEFAULT_SESSION_ID
        _uiState.value = _uiState.value.copy(projectName = active?.name ?: "")
        refresh()
    }

    /** Reloads the file list; leaves it unchanged on failure. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            repository.listDataFiles(session)
                .onSuccess { files -> _uiState.update { it.copy(files = files, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.message ?: "Failed to load files.") } }
        }
    }

    fun upload(part: MultipartBody.Part) {
        if (_uiState.value.uploading) return
        _uiState.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            repository.uploadFile(session, part)
                .onSuccess {
                    _uiState.update { it.copy(uploading = false) }
                    refresh()
                }
                .onFailure { t -> _uiState.update { it.copy(uploading = false, error = t.message ?: "Upload failed.") } }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            repository.deleteDataFile(name, session)
                .onSuccess { removed ->
                    if (removed) refresh()
                    else _uiState.update { it.copy(error = "$name was not deleted.") }
                }
                .onFailure { t -> _uiState.update { it.copy(error = t.message ?: "Delete failed.") } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.data.DataViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/data/DataUiState.kt \
        app/src/main/java/com/rmobile/console/ui/data/DataViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/data/DataViewModelTest.kt
git commit -m "$(cat <<'EOF'
app: DataViewModel + DataUiState with session scoping

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: App — Data screen, editor insert, and navigation

**Files:**
- Create: `app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/rmobile/console/MainActivity.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`

- [ ] **Step 1: Write the failing test for `EditorViewModel.insertText`**

Add to `EditorViewModelTest.kt`. The file already has a no-arg VM factory
`private fun viewModel(...)` (all params defaulted) and a `MainDispatcherRule`;
reuse them exactly as below.

```kotlin
    @Test
    fun `insertText appends a snippet on its own line`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCodeChanged("head(x)")
        vm.insertText("\"sales.csv\"")
        advanceUntilIdle()
        assertEquals("head(x)\n\"sales.csv\"", vm.uiState.value.code)
    }

    @Test
    fun `insertText into empty code has no leading newline`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCodeChanged("")
        vm.insertText("\"sales.csv\"")
        advanceUntilIdle()
        assertEquals("\"sales.csv\"", vm.uiState.value.code)
    }
```

(If `runTest`/`advanceUntilIdle`/`assertEquals` imports are missing, they're already
imported in this file — it uses them elsewhere.)

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: compile failure — `insertText` doesn't exist.

- [ ] **Step 3: Add `insertText` to `EditorViewModel`**

Add near `onCodeChanged`:

```kotlin
    /**
     * Appends a snippet (e.g. a quoted data filename from the Data screen) to the
     * code. Used across a screen switch, where the editor's cursor isn't available,
     * so it appends on its own line rather than inserting at a cursor. Routes through
     * [onCodeChanged] so the active project file mirrors the change.
     */
    fun insertText(snippet: String) {
        val existing = _uiState.value.code
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        onCodeChanged(existing + separator + snippet)
    }
```

- [ ] **Step 4: Run the editor test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Create `DataScreen.kt`**

This mirrors the Packages screen's structure. Read
`app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt` first to match
its `Scaffold`/`TopAppBar`/import conventions, then create:

```kotlin
package com.rmobile.console.ui.data

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.data.network.UriRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onInsertFileName: (String) -> Unit,
    viewModel: DataViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            var name = "upload.bin"
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            val body = UriRequestBody(
                context.contentResolver, uri, size, "application/octet-stream".toMediaTypeOrNull(),
            )
            viewModel.upload(MultipartBody.Part.createFormData("file", name, body))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.projectName.isBlank()) "Data" else "Data — ${state.projectName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.uploading) {
                    Text("Upload file")
                }
                if (state.uploading) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            if (state.files.isEmpty() && !state.isLoading) {
                Text(
                    "No data files yet. Upload one, then read it in code by name, e.g. read.csv(\"name\").",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.files, key = { it.name }) { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(formatByteSize(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onInsertFileName("\"${file.name}\"") }) { Text("Insert") }
                        IconButton(onClick = { viewModel.delete(file.name) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Wire the Data screen into `MainActivity.kt` + `EditorScreen`**

`MainActivity.kt` has `private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS }`
and reaches Packages via an `onOpenPackages` lambda passed to `EditorScreen`. Make
these exact edits:

1. Add the import:
```kotlin
import com.rmobile.console.ui.data.DataScreen
```
2. Add `DATA` to the enum:
```kotlin
private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS, DATA }
```
3. In the `Screen.EDITOR -> EditorScreen(...)` call, add the `onOpenData` argument
   alongside the existing `onOpenPackages`:
```kotlin
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
            onOpenProjects = { screen = Screen.PROJECTS },
            onOpenData = { screen = Screen.DATA },
            viewModel = editorViewModel,
        )
```
4. Add the `DATA` branch to the `when (screen)`:
```kotlin
        Screen.DATA -> DataScreen(
            onBack = { screen = Screen.EDITOR },
            onInsertFileName = { name ->
                editorViewModel.insertText(name)
                screen = Screen.EDITOR
            },
        )
```

Then in `EditorScreen.kt`: add an `onOpenData: () -> Unit` parameter to the
`EditorScreen` composable signature (next to `onOpenProjects`), and add a top-bar
action that invokes it, mirroring the existing **Packages** action exactly (same
`IconButton`/menu placement — read how `onOpenPackages` is surfaced in the top bar
and copy that pattern; use a folder/storage icon such as
`Icons.Default.Folder`, and `contentDescription = "Data files"`).

- [ ] **Step 7: Build the app to verify UI compiles**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/data/DataScreen.kt \
        app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/main/java/com/rmobile/console/MainActivity.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "$(cat <<'EOF'
app: Data screen, editor insertText, and navigation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Verification + documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Run the full app unit-test suite + lint**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL; all tests pass (existing + new). Fix any regressions before continuing.

- [ ] **Step 2: Update `README.md` Features list**

Add a bullet in the Features section:

```markdown
- **Data import** — upload files (CSV, RDS, xlsx, anything) from your phone into a
  project's session and read them in code by name (`read.csv("sales.csv")`), from a
  dedicated **Data** screen (upload / list / delete). Tap a file to insert its name
  into the editor.
```

- [ ] **Step 3: Update `CLAUDE.md`**

In the `app/` architecture section, add a `ui/data/` bullet describing the Data
screen + `DataViewModel` (session-scoped via `ProjectStore`, streaming upload via
`UriRequestBody` + the `rDataApi` no-timeout client). In the "Response contract"
section, document `/upload` (multipart, `sessionId` query), `/data`, `/delete-data`,
the new models in `data/model/DataModels.kt`, and that `is_protected` now includes
these three. In "Current scope / what's built", add data import. Note the backend
`session_paths$data`, execute symlink-in, and reset cleanup. Keep wording accurate:
data is symlinked (not copied) into the run dir; the container `mem_limit` was raised
to 2g because uploads buffer in memory.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "$(cat <<'EOF'
docs: session data import (Data screen + /upload contract)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Push and open a PR**

```bash
git push -u origin <branch>
gh pr create --base claude/r-app-android-version-ztmyd1 --title "Session data import" --body "$(cat <<'EOF'
## Summary
- Per-session `data/` dir + `POST /upload` (multipart), `GET /data`, `POST /delete-data`
- `/execute` symlinks uploaded files into the run dir; `/reset` clears them
- Container `mem_limit` → 2g (uploads buffer in memory); cap `R_UPLOAD_MAX_BYTES` (1 GiB)
- App: Data screen (upload/list/delete), streaming upload client, tap-to-insert filename

## Test Plan
- [ ] Backend suite green in CI (upload/list/delete/read-in-execute/cap/reserved/scoping/reset)
- [ ] App unit tests green (repository, ByteSize, DataViewModel, EditorViewModel.insertText)
- [ ] Debug build succeeds

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review notes (for the executor)

- **Contract sync:** the Kotlin models in `DataModels.kt` match the backend JSON
  field-for-field (`name`/`size`, `files`, `removed`). Backend emits unboxed scalars
  via `run.R` — no change needed.
- **Reserved-name safety:** `sanitize_data_name` rejects `script.R`/`objects.txt`/
  `main.R`/`plot###.png`/`table###.json`, so a data file can never shadow a harness
  file; the `/execute` symlink loop additionally guards with `file.exists`.
- **Memory:** app upload streams via `UriRequestBody` (flat app memory); backend
  buffers (mitigated by `mem_limit: 2g`). This asymmetry is intentional and documented.
- **`insertText` deviation from spec:** spec said "at the editor cursor"; implemented
  as append-on-own-line because the editor's cursor isn't preserved across the
  screen switch to the Data screen. Documented in code + docs.
