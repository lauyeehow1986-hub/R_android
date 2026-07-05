library(plumber)
library(processx)
library(base64enc)
library(jsonlite)

# See README.md for the security assumptions this service makes (and does not make).
EXECUTION_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_EXECUTION_TIMEOUT_SECONDS", "20"))
MAX_CODE_LENGTH <- as.numeric(Sys.getenv("R_MAX_CODE_LENGTH", "20000"))

# Optional shared-secret auth. When R_API_KEY is set, /execute requires a
# matching X-API-Key header; when unset, auth is disabled (local-dev default).
API_KEY <- Sys.getenv("R_API_KEY", "")

# Simple per-IP fixed-window rate limit. 0 disables it.
RATE_LIMIT_PER_MINUTE <- as.numeric(Sys.getenv("R_RATE_LIMIT_PER_MINUTE", "0"))
.rate_state <- new.env(parent = emptyenv())

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

# Only the execution endpoint is protected; /health stays open for probes.
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset")

#* Require a valid API key on protected routes when one is configured.
#* @filter auth
function(req, res) {
  if (nzchar(API_KEY) && is_protected(req)) {
    provided <- req$HTTP_X_API_KEY
    if (is.null(provided) || !identical(provided, API_KEY)) {
      res$status <- 401
      return(list(stdout = "", stderr = "", plots = list(), error = "Unauthorized.", timedOut = FALSE))
    }
  }
  plumber::forward()
}

#* Throttle protected routes per client IP.
#* @filter ratelimit
function(req, res) {
  if (RATE_LIMIT_PER_MINUTE > 0 && is_protected(req)) {
    ip <- if (is.null(req$REMOTE_ADDR)) "unknown" else req$REMOTE_ADDR
    now <- as.numeric(Sys.time())
    entry <- if (exists(ip, envir = .rate_state, inherits = FALSE)) {
      get(ip, envir = .rate_state)
    } else {
      NULL
    }

    if (is.null(entry) || now - entry$window_start >= 60) {
      entry <- list(window_start = now, count = 0L)
    }
    entry$count <- entry$count + 1L
    assign(ip, entry, envir = .rate_state)

    if (entry$count > RATE_LIMIT_PER_MINUTE) {
      res$status <- 429
      return(list(
        stdout = "", stderr = "", plots = list(),
        error = "Rate limit exceeded. Try again shortly.", timedOut = FALSE
      ))
    }
  }
  plumber::forward()
}

#* @apiTitle R Mobile execution backend
#* @apiDescription Runs a submitted R snippet in an isolated Rscript subprocess and
#*   returns its stdout, stderr, and any plots it produced. See README.md before
#*   deploying this anywhere reachable from the public internet.

#* Execute an R script
#* @param req
#* @post /execute
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  code <- body$code

  if (is.null(code) || !is.character(code) || !nzchar(trimws(code))) {
    res$status <- 400
    return(list(stdout = "", stderr = "", plots = list(), error = "Missing 'code' in request body.", timedOut = FALSE))
  }
  if (nchar(code) > MAX_CODE_LENGTH) {
    res$status <- 413
    return(list(stdout = "", stderr = "", plots = list(), error = "Code exceeds the maximum allowed length.", timedOut = FALSE))
  }

  run_dir <- file.path(tempdir(), paste0("run-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)

  script_path <- file.path(run_dir, "script.R")
  plot_pattern <- file.path(run_dir, "plot%03d.png")

  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$dir, recursive = TRUE, showWarnings = FALSE)
  objects_path <- file.path(run_dir, "objects.txt")

  attached_literal <- paste(deparse(DEFAULT_ATTACHED), collapse = "")

  # Every script gets a PNG device so plot() calls are captured as images instead
  # of failing for lack of a display. dev.off() flushes the last page to disk.
  # Before user code: restore the saved workspace + attached packages (if any).
  # After user code: persist workspace + attached-package list back to the session dir.
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

  result <- tryCatch(
    processx::run(
      command = "Rscript",
      args = c("--vanilla", script_path),
      wd = run_dir,
      timeout = EXECUTION_TIMEOUT_SECONDS,
      error_on_status = FALSE
    ),
    error = function(e) e,
  )

  if (inherits(result, "error")) {
    timed_out <- grepl("timed out", conditionMessage(result), ignore.case = TRUE)
    return(list(
      stdout = "",
      stderr = conditionMessage(result),
      plots = list(),
      error = if (timed_out) {
        sprintf("Execution timed out after %ss.", EXECUTION_TIMEOUT_SECONDS)
      } else {
        "Execution failed to start."
      },
      timedOut = timed_out,
    ))
  }

  plot_files <- sort(list.files(run_dir, pattern = "^plot[0-9]+\\.png$", full.names = TRUE))
  plots <- lapply(plot_files, base64enc::base64encode)

  workspace_objects <- if (file.exists(objects_path)) as.list(readLines(objects_path)) else NULL

  list(
    stdout = result$stdout,
    stderr = result$stderr,
    plots = plots,
    error = if (result$status != 0) sprintf("R exited with status %d.", result$status) else NULL,
    timedOut = FALSE,
    workspaceObjects = workspace_objects
  )
}

#* Reset a session's persisted workspace + attached-package state
#* @post /reset
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  unlink(c(paths$workspace, paths$attached), force = TRUE)
  list(ok = TRUE)
}

#* Health check
#* @get /health
function() {
  list(status = "ok")
}
