# Backend integration test suite — design

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + CI

## Problem

The backend (`backend/plumber.R`) has **no automated tests** — `CLAUDE.md` says
"validate `plumber.R` by hitting `/execute` with curl." This gap let two runtime
bugs ship undetected until the durable-session feature forced the first real
end-to-end run: trailing commas that made every `/execute` raise "argument is
missing, with no default", and a Plumber serializer that array-wrapped scalars
(`"stdout":["42"]`) the app couldn't parse. Neither is a pure-logic bug; both
are runtime/serialization behavior. Nothing exercises the backend on push, so
regressions here are invisible.

## Goal

An **integration test suite** that starts a real Plumber instance, exercises
every endpoint over HTTP, and asserts on the actual JSON — catching exactly the
class of bug that slipped through — plus a CI job so it runs on every push/PR.

## Decisions (from brainstorming)

- **Integration, not unit.** Tests hit a live server over HTTP; they do not call
  R helpers directly. (The bugs that shipped were runtime/serialization, which
  unit tests of `sanitize_session_id`/`session_paths` would not have caught.)
- **testthat + httr2.** R-native harness; the suite self-starts the server.
- **CI job**, run with native R (`r-lib/actions`), not by building the Docker
  image.

## Architecture

### Layout
```
backend/
  tests/
    helper-server.R   # local_server()/req helpers (sourced first by test_dir)
    test-health.R
    test-execute.R
    test-reset.R
    test-session.R
    test-auth.R
    test-ratelimit.R
  run-tests.R         # entrypoint
```

### Harness (`backend/tests/helper-server.R`)

- `local_server(env = list(), .local_envir = parent.frame())`: starts the
  Plumber app in a background `processx::process` running `Rscript run.R` (from
  the `backend/` working dir), with environment overrides merged onto the
  current env:
  - `PORT` = a free port from `httpuv::randomPort()`,
  - `R_SESSION_DIR` = a fresh `tempfile()` dir (per call → session isolation),
  - plus any caller overrides (`R_API_KEY`, `R_RATE_LIMIT_PER_MINUTE`,
    `R_MAX_CODE_LENGTH`, …).
  It polls `GET /health` until 200 (timeout ~10s, else fail with the process's
  stderr), registers teardown with `withr::defer(proc$kill(), .local_envir)`,
  and returns `list(base_url = "http://127.0.0.1:<port>", proc = proc)`.
- Request helpers (thin `httr2` wrappers), e.g.
  `post_execute(server, code, key = NULL, session_id = NULL)`,
  `post_reset(server, session_id = "default", key = NULL)`,
  `get_health(server)` — each returns a list of `status` (HTTP code) and
  `body` (parsed JSON via `resp_body_json()`), and does not error on non-2xx
  (`req_error(is_error = ~FALSE)`), so tests can assert on 400/401/413/429.

`run.R` is reused as-is (it already reads `PORT` and bootstraps `plumber.R`,
which reads the other env vars). No production code changes are required for the
suite.

### Test cases

**test-health.R**
- `GET /health` → 200, body `list(status = "ok")`.

**test-execute.R** (default server)
- `cat(1 + 1)` → `stdout == "2"`, `error` is `NULL`, `timedOut == FALSE`,
  `plots` empty.
- `plot(1:3)` → `plots` length 1, each a non-empty base64 string.
- Missing `code` (empty body / no `code`) → 400 with an error message.
- `stop("boom")` → non-zero-status error: `error` non-null, `stderr` contains
  "boom".
- Oversized code: a server started with `R_MAX_CODE_LENGTH = "10"` and a longer
  snippet → 413.
- **Serializer regression guard**: after decoding, assert `stdout` is a scalar
  string of length 1 (not a list/array) and `error` is JSON `null` — pins the
  `run.R` unboxed-JSON fix so array-wrapping can't silently return.

**test-reset.R**
- `x <- 5`, then `POST /reset`, then `exists("x")` → `"FALSE"`.
- `/reset` returns `list(ok = TRUE)`.
- `/reset` on a session that was never used → still `ok`.

**test-session.R** (fresh `R_SESSION_DIR` per server)
- `x <- 41` then `cat(x + 1)` → `"42"` (objects persist across runs).
- `workspaceObjects` contains `"x"` after the assignment; is empty (`[]`) after
  a reset.
- `suppressMessages(library(jsonlite))` then next run
  `cat("jsonlite" %in% .packages())` → `"TRUE"` (packages persist).
- Errored-run-doesn't-clobber: set `x <- 1`, run a snippet that errors, then
  `cat(x)` → `"1"` (state preserved), and the errored run's `workspaceObjects`
  is `NULL`/absent.

**test-auth.R** (server with `R_API_KEY = "secret"`)
- `/execute` with no key → 401; with wrong key → 401; with `X-API-Key: secret`
  → 200.
- `/reset` with no key → 401.
- `/health` with no key → 200 (open).

**test-ratelimit.R** (server with `R_RATE_LIMIT_PER_MINUTE = "2"`)
- Two `/execute` calls → 200; the third → 429.

### Entrypoint (`backend/run-tests.R`)
```r
#!/usr/bin/env Rscript
library(testthat)
test_dir("backend/tests", stop_on_failure = TRUE)
```
`test_dir` sources `helper-server.R` before the `test-*.R` files and returns a
non-zero exit on any failure (via `stop_on_failure`), so CI fails correctly.

### CI (`.github/workflows/android.yml`)
Add a job parallel to the existing Android `build` job:
```yaml
  backend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: r-lib/actions/setup-r@v2
      - name: Install R deps
        run: Rscript -e 'install.packages(c("plumber","processx","base64enc","jsonlite","testthat","httr2","withr"))'
      - name: Run backend tests
        run: Rscript backend/run-tests.R
```
`setup-r@v2` configures the Posit Package Manager so those are binary installs
(fast). The server the tests spawn runs natively on the runner; `/execute`'s
`Rscript --vanilla` subprocesses and the temp `R_SESSION_DIR` work there without
Docker.

### Docs
- `backend/README.md`: a "Running the tests" section —
  `Rscript backend/run-tests.R` (needs `plumber, processx, base64enc, jsonlite,
  testthat, httr2, withr`).
- `CLAUDE.md`: replace "no backend automated test suite" with a pointer to
  `backend/tests/` and `backend/run-tests.R`.
- The runtime `Dockerfile` is unchanged — tests run natively, not in the image.

## Out of scope

- Unit tests of pure helpers (decided against; integration covers the risk).
- Running tests inside the Docker image / a `docker compose` test service.
- Load/performance testing, plot-image pixel validation, TLS.

## Risks / notes

- **Port/readiness flakiness**: mitigated by `httpuv::randomPort()` (avoids
  collisions) and polling `/health` before each test file's requests.
- **Process leaks**: every `local_server` registers `withr::defer` teardown, so
  a failed test still kills its server.
- **CI R-package install time**: binary packages via RSPM keep it to ~1–2 min;
  acceptable and cacheable later if needed.
- **`library()` persistence test** depends on a package that's installed in the
  test environment — use `jsonlite` (already a backend dependency), not an
  arbitrary CRAN package.
