library(plumber)
library(processx)
library(base64enc)
library(jsonlite)

# See README.md for the security assumptions this service makes (and does not make).
EXECUTION_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_EXECUTION_TIMEOUT_SECONDS", "20"))
MAX_CODE_LENGTH <- as.numeric(Sys.getenv("R_MAX_CODE_LENGTH", "20000"))
# Max rows delivered per captured table (the true count is still reported).
TABLE_MAX_ROWS <- as.integer(Sys.getenv("R_TABLE_MAX_ROWS", "200"))

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

# Shared, persistent package library (its own volume in prod).
PKG_LIB <- Sys.getenv("R_PKG_LIB", "/data/rlib")
INSTALL_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_INSTALL_TIMEOUT_SECONDS", "300"))
CRAN_REPO <- Sys.getenv("R_CRAN_REPO", "https://packagemanager.posit.co/cran/__linux__/jammy/latest")

# Only the execution endpoint is protected; /health stays open for probes.
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall")

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
  }

  run_dir <- file.path(tempdir(), paste0("run-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)

  if (use_files) {
    for (i in seq_along(file_names)) {
      writeLines(file_contents[i], file.path(run_dir, file_names[i]))
    }
    entry_rel <- entry
  } else {
    entry_rel <- "main.R"
    writeLines(code, file.path(run_dir, entry_rel))
  }

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
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(PKG_LIB)),
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
    # Run the entry's top-level expressions ourselves (instead of source()) so any
    # visible data-frame-like value is captured as a table<NN>.json side-channel
    # file. Helpers live inside local({}) so nothing leaks into globalenv (keeping
    # save.image()/workspaceObjects clean); user assignments still target globalenv.
    'local({',
    sprintf('  .maxrows <- %d', TABLE_MAX_ROWS),
    '  .emit <- function(x) {',
    '    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)',
    '    n <- nrow(df); sub <- utils::head(df, .maxrows)',
    '    types <- vapply(df, function(cc) class(cc)[1], character(1))',
    '    cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))',
    '    cols <- names(df)',
    '    rn <- rownames(sub)',
    '    if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }',
    '    rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))',
    '    obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))',
    '    idx <- length(list.files(".", pattern = "^table[0-9]+\\\\.json$")) + 1L',
    '    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))',
    '  }',
    '  .tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)',
    '  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")',
    '  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if ((r$visible || pr) && .tabular(r$value)) try(.emit(r$value), silent = TRUE); if (r$visible) print(r$value) }',
    sprintf('  .exec(parse(file = %s))', shQuote(entry_rel)),
    '})',
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
    error = function(e) e
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
      timedOut = timed_out
    ))
  }

  plot_files <- sort(list.files(run_dir, pattern = "^plot[0-9]+\\.png$", full.names = TRUE))
  plots <- lapply(plot_files, base64enc::base64encode)

  # simplifyVector = FALSE keeps columns/rows as lists so plumber's global unboxed
  # serializer can't collapse a 1-row/1-column table into scalars.
  table_files <- sort(list.files(run_dir, pattern = "^table[0-9]+\\.json$", full.names = TRUE))
  tables <- lapply(table_files, function(p) jsonlite::fromJSON(p, simplifyVector = FALSE))

  workspace_objects <- if (file.exists(objects_path)) as.list(readLines(objects_path)) else NULL

  list(
    stdout = result$stdout,
    stderr = result$stderr,
    plots = plots,
    tables = tables,
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

#* Health check
#* @get /health
function() {
  list(status = "ok")
}

#* List user-installed packages in the shared library
#* @get /packages
function() {
  pkgs <- tryCatch(rownames(installed.packages(lib.loc = PKG_LIB)), error = function(e) NULL)
  if (is.null(pkgs)) pkgs <- character(0)
  list(packages = as.list(pkgs))
}
