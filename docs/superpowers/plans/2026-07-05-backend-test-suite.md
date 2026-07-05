# Backend Integration Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A testthat + httr2 integration suite that self-starts the Plumber backend and exercises every endpoint over HTTP (execute, reset, session persistence, auth, rate limiting), plus a CI job that runs it on every push/PR.

**Architecture:** A helper (`backend/tests/helper-server.R`) starts `Rscript run.R` in a background `processx` process on a random free port with a temp session dir, polls `/health`, and tears it down. Six `test-*.R` files make real HTTP requests with `httr2` and assert on parsed JSON. Reuses `run.R`/`plumber.R` unchanged — no production code changes.

**Tech Stack:** R, Plumber, testthat, httr2, processx, withr, httpuv.

---

## Prerequisites (verification environment)

There is **no local R** on this machine, so the suite is verified in a throwaway
Docker image built on the backend's base image with the test deps added. CI (Task 7)
runs the same suite with native R instead.

**Build the local test image once** (Bash tool):
```bash
docker build -t r-backend-test - <<'DOCKERFILE'
FROM rocker/r-ver:4.4.1
RUN apt-get update && apt-get install -y --no-install-recommends \
    libcurl4-openssl-dev libssl-dev libsodium-dev \
    && rm -rf /var/lib/apt/lists/*
RUN install2.r --error --skipmissing plumber processx base64enc jsonlite testthat httr2 withr
DOCKERFILE
```

**Run the whole suite** (this is the verification command referenced by every task
below — the repo is mounted so edits are picked up without a rebuild):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R
```
`MSYS_NO_PATHCONV=1` stops Git Bash from mangling the container paths. The suite
runs the server and client inside the one container (localhost), so nothing else
needs to be running. Each test spins up its own short-lived server (~1-2s), so the
full suite takes ~30-60s.

**Branch:** work on `feat/backend-test-suite` (already created; holds this plan + the spec).

---

## File Structure

**Create:**
- `backend/tests/helper-server.R` — `local_server()` + `httr2` request helpers (sourced first by `test_dir`).
- `backend/tests/test-health.R`
- `backend/tests/test-execute.R`
- `backend/tests/test-reset.R`
- `backend/tests/test-session.R`
- `backend/tests/test-auth.R`
- `backend/tests/test-ratelimit.R`
- `backend/run-tests.R` — entrypoint (`test_dir(..., stop_on_failure = TRUE)`).

**Modify:**
- `.github/workflows/android.yml` — add a `backend-tests` job.
- `backend/README.md` — "Running the tests" section.
- `CLAUDE.md` — drop "no backend automated test suite".

No production R (`plumber.R`, `run.R`) or `Dockerfile` changes.

---

## Task 1: Test harness + health test

**Files:**
- Create: `backend/tests/helper-server.R`
- Create: `backend/tests/test-health.R`
- Create: `backend/run-tests.R`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test-health.R`:
```r
test_that("health check responds ok", {
  srv <- local_server()
  res <- get_health(srv)
  expect_equal(res$status, 200)
  expect_equal(res$body$status, "ok")
})
```

Create `backend/run-tests.R`:
```r
#!/usr/bin/env Rscript
# Runs the backend integration suite. `test_dir` sources helper-server.R before
# the test files and exits non-zero on any failure (for CI).
library(testthat)
testthat::test_dir("backend/tests", stop_on_failure = TRUE)
```

- [ ] **Step 2: Run to verify it FAILS**

Run (see Prerequisites):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R
```
Expected: FAILS — `local_server`/`get_health` not found (helper doesn't exist yet).

- [ ] **Step 3: Write the harness**

Create `backend/tests/helper-server.R`:
```r
library(processx)
library(httr2)

# Start a Plumber instance in a background process on a free port, with a fresh
# temp session dir plus any extra env overrides. Registers teardown so the server
# is killed when the calling test finishes. Returns list(base_url, proc).
local_server <- function(env = list(), .local_envir = parent.frame()) {
  port <- httpuv::randomPort()
  session_dir <- tempfile("r-sessions-")
  dir.create(session_dir, recursive = TRUE, showWarnings = FALSE)

  # Full child environment = parent env + our overrides (robust across processx
  # versions; avoids relying on the "current" sentinel).
  child_env <- Sys.getenv()
  child_env["PORT"] <- as.character(port)
  child_env["R_SESSION_DIR"] <- session_dir
  for (nm in names(env)) child_env[nm] <- as.character(env[[nm]])

  proc <- processx::process$new(
    "Rscript", c("run.R"),
    wd = "backend", env = child_env,
    stdout = "|", stderr = "|"
  )
  withr::defer(if (proc$is_alive()) proc$kill(), envir = .local_envir)

  base_url <- sprintf("http://127.0.0.1:%d", port)
  ready <- FALSE
  for (i in seq_len(100)) {
    ok <- tryCatch({
      resp <- request(base_url) |>
        req_url_path("/health") |>
        req_error(is_error = function(resp) FALSE) |>
        req_perform()
      resp_status(resp) == 200
    }, error = function(e) FALSE)
    if (isTRUE(ok)) { ready <- TRUE; break }
    Sys.sleep(0.1)
  }
  if (!ready) {
    stop("Server did not become ready. stderr:\n",
         paste(proc$read_all_error_lines(), collapse = "\n"))
  }
  list(base_url = base_url, proc = proc)
}

# Perform a request; returns list(status, body) and does NOT error on 4xx/5xx so
# tests can assert on 400/401/413/429. `body` is the JSON-decoded response.
api_request <- function(server, path, body = NULL, key = NULL) {
  req <- request(server$base_url) |>
    req_url_path(path) |>
    req_error(is_error = function(resp) FALSE)
  if (!is.null(body)) req <- req_body_json(req, body)
  if (!is.null(key)) req <- req_headers(req, "X-API-Key" = key)
  resp <- req_perform(req)
  list(
    status = resp_status(resp),
    body = tryCatch(resp_body_json(resp), error = function(e) NULL)
  )
}

post_execute <- function(server, code, key = NULL, session_id = NULL) {
  body <- list(code = code)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/execute", body = body, key = key)
}

post_reset <- function(server, session_id = "default", key = NULL) {
  api_request(server, "/reset", body = list(sessionId = session_id), key = key)
}

get_health <- function(server) api_request(server, "/health")
```

- [ ] **Step 4: Run to verify it PASSES**

Run the command from Step 2. Expected: `[ PASS ]` for the 1 test; process exits 0.

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/tests/helper-server.R backend/tests/test-health.R backend/run-tests.R
git commit -m "test(backend): add integration harness + health test"
```

---

## Task 2: Execute endpoint tests

**Files:**
- Create: `backend/tests/test-execute.R`

- [ ] **Step 1: Write the tests**

Create `backend/tests/test-execute.R`:
```r
test_that("execute runs code and returns unboxed scalars", {
  srv <- local_server()
  res <- post_execute(srv, "cat(1 + 1)")
  expect_equal(res$status, 200)
  # Scalar string (not a 1-element list) guards run.R's unboxed-JSON serializer.
  expect_true(is.character(res$body$stdout))
  expect_identical(res$body$stdout, "2")
  expect_null(res$body$error)
  expect_false(res$body$timedOut)
  expect_equal(length(res$body$plots), 0)
})

test_that("execute captures plots as base64", {
  srv <- local_server()
  res <- post_execute(srv, "plot(1:3)")
  expect_equal(res$status, 200)
  expect_equal(length(res$body$plots), 1)
  expect_true(nchar(res$body$plots[[1]]) > 100)
})

test_that("missing code is a 400", {
  srv <- local_server()
  res <- api_request(srv, "/execute", body = list(notcode = "x"))
  expect_equal(res$status, 400)
  expect_true(nchar(res$body$error) > 0)
})

test_that("code over the size limit is a 413", {
  srv <- local_server(env = list(R_MAX_CODE_LENGTH = "10"))
  res <- post_execute(srv, "cat('definitely longer than ten characters')")
  expect_equal(res$status, 413)
})

test_that("an R error yields a non-null error and stderr", {
  srv <- local_server()
  res <- post_execute(srv, "stop('boom')")
  expect_equal(res$status, 200)
  expect_false(is.null(res$body$error))
  expect_true(grepl("boom", res$body$stderr))
})
```

- [ ] **Step 2: Run the suite**

Run the Prerequisites command. Expected: PASS (these characterize existing
`/execute` behavior; the unboxing assertion also guards the `run.R` fix).

- [ ] **Step 3: Commit**
```bash
git add backend/tests/test-execute.R
git commit -m "test(backend): cover /execute output, plots, errors, limits"
```

---

## Task 3: Reset endpoint tests

**Files:**
- Create: `backend/tests/test-reset.R`

- [ ] **Step 1: Write the tests**

Create `backend/tests/test-reset.R`:
```r
test_that("reset clears session state", {
  srv <- local_server()
  post_execute(srv, "x <- 5")
  expect_identical(post_execute(srv, "cat(exists('x'))")$body$stdout, "TRUE")

  reset <- post_reset(srv)
  expect_equal(reset$status, 200)
  expect_true(isTRUE(reset$body$ok))

  expect_identical(post_execute(srv, "cat(exists('x'))")$body$stdout, "FALSE")
})

test_that("reset of an unused session is still ok", {
  srv <- local_server()
  reset <- post_reset(srv, session_id = "never-used")
  expect_equal(reset$status, 200)
  expect_true(isTRUE(reset$body$ok))
})
```

- [ ] **Step 2: Run the suite**

Run the Prerequisites command. Expected: PASS.

- [ ] **Step 3: Commit**
```bash
git add backend/tests/test-reset.R
git commit -m "test(backend): cover /reset clearing session state"
```

---

## Task 4: Durable session tests

**Files:**
- Create: `backend/tests/test-session.R`

- [ ] **Step 1: Write the tests**

Create `backend/tests/test-session.R`:
```r
test_that("objects persist across runs", {
  srv <- local_server()
  post_execute(srv, "x <- 41")
  expect_identical(post_execute(srv, "cat(x + 1)")$body$stdout, "42")
})

test_that("workspaceObjects reflects globals and clears on reset", {
  srv <- local_server()
  res <- post_execute(srv, "x <- 41")
  expect_true("x" %in% unlist(res$body$workspaceObjects))

  post_reset(srv)
  res2 <- post_execute(srv, "invisible(NULL)")
  expect_equal(length(res2$body$workspaceObjects), 0)
})

test_that("attached packages persist across runs", {
  srv <- local_server()
  post_execute(srv, "suppressMessages(library(jsonlite))")
  res <- post_execute(srv, "cat('jsonlite' %in% .packages())")
  expect_identical(res$body$stdout, "TRUE")
})

test_that("an errored run does not clobber saved state", {
  srv <- local_server()
  post_execute(srv, "x <- 1")
  err <- post_execute(srv, "stop('boom')")
  expect_null(err$body$workspaceObjects)              # epilogue never ran
  expect_identical(post_execute(srv, "cat(x)")$body$stdout, "1")  # x survived
})
```

- [ ] **Step 2: Run the suite**

Run the Prerequisites command. Expected: PASS (locks the durable-session
guarantees, including "save only on success").

- [ ] **Step 3: Commit**
```bash
git add backend/tests/test-session.R
git commit -m "test(backend): cover durable session persistence + reset"
```

---

## Task 5: Auth tests

**Files:**
- Create: `backend/tests/test-auth.R`

- [ ] **Step 1: Write the tests**

Create `backend/tests/test-auth.R`:
```r
test_that("api key is enforced on protected routes when configured", {
  srv <- local_server(env = list(R_API_KEY = "secret"))
  expect_equal(post_execute(srv, "cat(1)")$status, 401)                 # no key
  expect_equal(post_execute(srv, "cat(1)", key = "wrong")$status, 401)  # bad key
  expect_equal(post_execute(srv, "cat(1)", key = "secret")$status, 200) # good key
  expect_equal(post_reset(srv)$status, 401)                             # reset protected
  expect_equal(get_health(srv)$status, 200)                            # health open
})
```

- [ ] **Step 2: Run the suite**

Run the Prerequisites command. Expected: PASS.

- [ ] **Step 3: Commit**
```bash
git add backend/tests/test-auth.R
git commit -m "test(backend): cover X-API-Key auth on protected routes"
```

---

## Task 6: Rate-limit tests

**Files:**
- Create: `backend/tests/test-ratelimit.R`

- [ ] **Step 1: Write the tests**

Create `backend/tests/test-ratelimit.R`:
```r
test_that("requests over the per-minute limit get 429", {
  srv <- local_server(env = list(R_RATE_LIMIT_PER_MINUTE = "2"))
  expect_equal(post_execute(srv, "cat(1)")$status, 200)
  expect_equal(post_execute(srv, "cat(1)")$status, 200)
  expect_equal(post_execute(srv, "cat(1)")$status, 429)
})
```

- [ ] **Step 2: Run the suite**

Run the Prerequisites command. Expected: PASS (all 6 files green).

- [ ] **Step 3: Commit**
```bash
git add backend/tests/test-ratelimit.R
git commit -m "test(backend): cover per-IP rate limiting"
```

---

## Task 7: CI job

**Files:**
- Modify: `.github/workflows/android.yml`

- [ ] **Step 1: Add the backend-tests job**

Read `.github/workflows/android.yml`. It has a top-level `jobs:` map with a
`build:` job. Add a sibling job (same indentation as `build:`) at the end of the
`jobs:` map:
```yaml
  backend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: r-lib/actions/setup-r@v2
        with:
          use-public-rspm: true

      - uses: r-lib/actions/setup-r-dependencies@v2
        with:
          packages: |
            any::plumber
            any::processx
            any::base64enc
            any::jsonlite
            any::testthat
            any::httr2
            any::withr

      - name: Run backend integration tests
        run: Rscript backend/run-tests.R
```

- [ ] **Step 2: Validate the workflow YAML**

```bash
cd /c/Users/lauye/Downloads/R_android
python -c "import yaml; yaml.safe_load(open('.github/workflows/android.yml')); print('YAML_OK')"
```
Expected: `YAML_OK`. (The job actually runs on GitHub; it can't be executed
locally. `setup-r-dependencies` installs system libs + binary R packages, then
`run-tests.R` starts the server and exits non-zero on any failure.)

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/android.yml
git commit -m "ci: run backend integration tests on push/PR"
```

---

## Task 8: Docs

**Files:**
- Modify: `backend/README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Document how to run the tests**

In `backend/README.md`, add a section (place it after the "Running locally"
section):
```markdown
## Running the tests

Integration tests (testthat + httr2) start a Plumber instance and exercise every
endpoint over HTTP. With R installed and the packages `plumber, processx,
base64enc, jsonlite, testthat, httr2, withr`:

```bash
Rscript backend/run-tests.R
```

Tests live in `backend/tests/` (`helper-server.R` starts/stops the server;
`test-*.R` are the cases). CI runs them on every push/PR.
```

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, find the backend testing note that currently says to validate
`plumber.R` by hitting `/execute` with curl (and/or the "Current scope" line
listing "no backend automated test suite"). Update both to reflect the suite:
- In the backend commands/section, replace the "no separate backend test suite
  yet — validate with curl" guidance with: "Backend integration tests live in
  `backend/tests/` (testthat + httr2, run via `Rscript backend/run-tests.R`); they
  start a real Plumber instance and cover `/execute`, `/reset`, sessions, auth,
  and rate limiting. CI runs them."
- In "Current scope", remove "any backend automated test suite" from the
  not-built list.

- [ ] **Step 3: Commit**
```bash
git add backend/README.md CLAUDE.md
git commit -m "docs: document the backend test suite"
```

---

## Done

Full suite green in the local Docker image and in CI (native R). All spec cases
covered: health, execute (output/plots/400/413/error + serializer guard), reset,
durable session (persistence, workspaceObjects, save-only-on-success), auth, and
rate limiting. Open a PR from `feat/backend-test-suite` when ready (base
`claude/r-app-android-version-ztmyd1`).
