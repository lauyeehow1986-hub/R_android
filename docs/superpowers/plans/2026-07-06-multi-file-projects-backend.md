# Multi-file Projects — Backend Plan (A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `POST /execute` to accept a multi-file project (`files` + `entryFile`) — writing all files into the run dir and `source()`-ing the entry within the existing session wrapper — while keeping the legacy `{code}` path unchanged.

**Architecture:** One backward-compatible endpoint. When `files` is present the handler validates it, writes each file verbatim into the per-run temp dir, and the session wrapper's body becomes `source("<entryFile>", print.eval = TRUE)` instead of inlined `code`; `source()` of siblings resolves because they share the working dir. This is Plan A of two (backend now; the app follows in Plan B).

**Tech Stack:** R + Plumber, testthat + httr2.

---

## Prerequisites

Backend tests run in the prebuilt Docker image `r-backend-test` (already built):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "C:/Users/lauye/Downloads/R_android:/work" -w /work r-backend-test Rscript backend/run-tests.R
```
**Branch:** `feat/multi-file-projects` (already created; holds the spec).

---

## File Structure

- Modify: `backend/plumber.R` — `/execute` handler: mode-aware validation (`code` vs `files`), write files, `source()` the entry.
- Create: `backend/tests/test-project.R` — multi-file integration tests.
- Modify: `backend/README.md`, `CLAUDE.md` — document the `files`/`entryFile` contract.

No app changes in this plan.

---

## Task 1: Multi-file `/execute`

**Files:** Modify `backend/plumber.R`; Create `backend/tests/test-project.R`.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test-project.R`:
```r
test_that("a multi-file project runs the entry which sources a helper", {
  srv <- local_server()
  files <- list(
    list(name = "helpers.R", content = "f <- function(x) x * 2\n"),
    list(name = "main.R", content = "source('helpers.R'); cat(f(21))\n")
  )
  res <- api_request(srv, "/execute", body = list(files = files, entryFile = "main.R"))
  expect_equal(res$status, 200)
  expect_identical(res$body$stdout, "42")
})

test_that("entryFile must be one of the files", {
  srv <- local_server()
  files <- list(list(name = "main.R", content = "cat(1)\n"))
  res <- api_request(srv, "/execute", body = list(files = files, entryFile = "nope.R"))
  expect_equal(res$status, 400)
})

test_that("invalid file names are rejected", {
  srv <- local_server()
  files <- list(list(name = "../evil.R", content = "cat(1)\n"))
  res <- api_request(srv, "/execute", body = list(files = files, entryFile = "../evil.R"))
  expect_equal(res$status, 400)
})

test_that("the legacy code path still works", {
  srv <- local_server()
  res <- post_execute(srv, "cat(2 + 2)")
  expect_identical(res$body$stdout, "4")
})

test_that("session persists across multi-file runs", {
  srv <- local_server()
  api_request(srv, "/execute",
              body = list(files = list(list(name = "main.R", content = "y <- 7\n")), entryFile = "main.R"))
  res <- api_request(srv, "/execute",
                     body = list(files = list(list(name = "main.R", content = "cat(y + 1)\n")), entryFile = "main.R"))
  expect_identical(res$body$stdout, "8")
})
```

- [ ] **Step 2: Run the suite — verify FAIL**

Run the Prerequisites command. Expected: the new multi-file tests fail (files are ignored → the entry isn't run → `stdout` empty/mismatched, and the invalid cases don't 400). Existing tests pass.

- [ ] **Step 3: Make validation mode-aware**

In `backend/plumber.R`'s `/execute` handler, **replace** the current code-validation block — the lines that read:
```r
  code <- body$code

  if (is.null(code) || !is.character(code) || !nzchar(trimws(code))) {
    res$status <- 400
    return(list(stdout = "", stderr = "", plots = list(), error = "Missing 'code' in request body.", timedOut = FALSE))
  }
  if (nchar(code) > MAX_CODE_LENGTH) {
    res$status <- 413
    return(list(stdout = "", stderr = "", plots = list(), error = "Code exceeds the maximum allowed length.", timedOut = FALSE))
  }
```
with this mode-aware version:
```r
  files <- body$files
  use_files <- !is.null(files)

  if (use_files) {
    # jsonlite parses an array of {name,content} objects as a data.frame (or a
    # list if not simplifiable); normalize both to character vectors.
    if (is.data.frame(files)) {
      file_names <- as.character(files$name)
      file_contents <- as.character(files$content)
    } else {
      file_names <- vapply(files, function(f) as.character(f$name), character(1))
      file_contents <- vapply(files, function(f) as.character(f$content), character(1))
    }
    entry <- body$entryFile
    ok <- length(file_names) > 0 &&
      all(grepl("^[A-Za-z0-9][A-Za-z0-9._-]*$", file_names)) &&
      anyDuplicated(file_names) == 0 &&
      is.character(entry) && length(entry) == 1 && entry %in% file_names
    if (!isTRUE(ok)) {
      res$status <- 400
      return(list(stdout = "", stderr = "", plots = list(), error = "Invalid 'files' or 'entryFile'.", timedOut = FALSE))
    }
    if (sum(nchar(file_contents)) > MAX_CODE_LENGTH) {
      res$status <- 413
      return(list(stdout = "", stderr = "", plots = list(), error = "Project exceeds the maximum allowed size.", timedOut = FALSE))
    }
    body_line <- sprintf('source(%s, echo = FALSE, print.eval = TRUE)', shQuote(entry))
  } else {
    code <- body$code
    if (is.null(code) || !is.character(code) || !nzchar(trimws(code))) {
      res$status <- 400
      return(list(stdout = "", stderr = "", plots = list(), error = "Missing 'code' in request body.", timedOut = FALSE))
    }
    if (nchar(code) > MAX_CODE_LENGTH) {
      res$status <- 413
      return(list(stdout = "", stderr = "", plots = list(), error = "Code exceeds the maximum allowed length.", timedOut = FALSE))
    }
    body_line <- code
  }
```

- [ ] **Step 4: Write the project files into the run dir**

Still in `/execute`, after `run_dir` is created (the `dir.create(run_dir, ...)` / `on.exit(...)` lines) and before the `wrapped <- c(...)` vector is built, add:
```r
  if (use_files) {
    for (i in seq_along(file_names)) {
      writeLines(file_contents[i], file.path(run_dir, file_names[i]))
    }
  }
```

- [ ] **Step 5: Use `body_line` as the wrapper body**

In the `wrapped <- c(...)` vector, replace the bare `code,` element (the one between the `grDevices::png(...)` line and the `'invisible(grDevices::dev.off())'` line) with:
```r
    body_line,
```

- [ ] **Step 6: Run the suite — verify PASS**

Run the Prerequisites command. Expected: all pass (existing suite + the 5 new `test-project.R` tests). The multi-file test prints `42`; the legacy `code` test still prints `4`; the session test prints `8`.

- [ ] **Step 7: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/plumber.R backend/tests/test-project.R
git commit -m "backend: /execute accepts multi-file projects (files + entryFile)"
```

---

## Task 2: Docs

**Files:** Modify `backend/README.md`, `CLAUDE.md`.

- [ ] **Step 1: backend/README.md**

In the `POST /execute` bullet under `## Endpoints`, append a note about the new fields:
```markdown
  Alternatively, send a multi-file project: `files` (a list of
  `{"name","content"}`) plus `entryFile` (one of the file names). All files are
  written to the run dir and the entry is `source()`d, so `source("helpers.R")`
  works. File names must match `^[A-Za-z0-9][A-Za-z0-9._-]*$`. `code` and `files`
  are mutually exclusive — if `files` is present it wins.
```

- [ ] **Step 2: CLAUDE.md**

In the backend architecture description (where `/execute` is discussed), add a sentence:
```markdown
`/execute` also accepts a multi-file project — `files` (`[{name,content}]`) +
`entryFile` — which the handler writes into the run dir and runs by
`source()`-ing the entry inside the same session wrapper (so `source()` between
files works); the legacy single `code` field still works.
```
In the "Response contract" section, note that `ExecuteRequest` may carry
`files`/`entryFile` (added on the app side in Plan B).

- [ ] **Step 3: Commit**
```bash
git add backend/README.md CLAUDE.md
git commit -m "docs: document multi-file /execute contract"
```

---

## Done

`/execute` runs multi-file projects (validated, `source()`-based, session-aware)
and still runs legacy single-`code` requests; covered by `test-project.R`.
Next: Plan B (app project model + editor refactor + project-library UI), which
starts sending `files`/`entryFile`.
