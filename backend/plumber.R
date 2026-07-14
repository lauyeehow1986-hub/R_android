library(plumber)
library(processx)
library(base64enc)
library(jsonlite)

# See README.md for the security assumptions this service makes (and does not make).
EXECUTION_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_EXECUTION_TIMEOUT_SECONDS", "20"))
MAX_CODE_LENGTH <- as.numeric(Sys.getenv("R_MAX_CODE_LENGTH", "20000"))
# Max rows delivered per captured table (the true count is still reported).
TABLE_MAX_ROWS <- as.integer(Sys.getenv("R_TABLE_MAX_ROWS", "200"))
# Shared R source (a character vector of lines) that defines the table emitter
# used by both /execute and /preview, so both write byte-identical table*.json.
# .emit(x): coerce x to a data frame, head() to .maxrows, and write the next
# table###.json (columns/columnTypes/rows/totalRows) into the working dir.
# .tabular(v): TRUE for a data frame or a 2-D matrix/table.
TABLE_EMIT_HELPERS <- c(
  sprintf('.maxrows <- %d', TABLE_MAX_ROWS),
  '.emit <- function(x) {',
  '  df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)',
  '  n <- nrow(df); sub <- utils::head(df, .maxrows)',
  '  types <- vapply(df, function(cc) class(cc)[1], character(1))',
  '  cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))',
  '  cols <- names(df)',
  '  rn <- rownames(sub)',
  '  if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }',
  '  rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))',
  '  obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))',
  '  idx <- length(list.files(".", pattern = "^table[0-9]+\\\\.json$")) + 1L',
  '  writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))',
  '}',
  '.tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)'
)
# Max completion symbols returned by GET /symbols.
SYMBOLS_MAX <- as.integer(Sys.getenv("R_SYMBOLS_MAX", "5000"))

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
    attached = file.path(dir, "attached.txt"),
    rlib = file.path(dir, "rlib"),
    data = file.path(dir, "data")
  )
}

# Legacy shared library from before per-session libraries. Read-only source for
# POST /import-legacy; nothing is installed here anymore.
LEGACY_PKG_LIB <- Sys.getenv("R_PKG_LIB", "/data/rlib")
INSTALL_TIMEOUT_SECONDS <- as.numeric(Sys.getenv("R_INSTALL_TIMEOUT_SECONDS", "300"))
CRAN_REPO <- Sys.getenv("R_CRAN_REPO", "https://packagemanager.posit.co/cran/__linux__/jammy/latest")

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

# Only the execution endpoint is protected; /health stays open for probes.
is_protected <- function(req) req$PATH_INFO %in% c("/execute", "/reset", "/install", "/uninstall", "/import-legacy", "/symbols", "/help", "/upload", "/data", "/delete-data", "/preview")

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
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  # Expose the session's uploaded data files by symlinking them into the run dir
  # (the working directory), so user code reads them by bare filename without
  # copying potentially gigabyte-sized files on every run. The entry files are
  # already written, so file.exists() keeps a data file from shadowing them.
  if (dir.exists(paths$data)) {
    for (df in list.files(paths$data, full.names = FALSE)) {
      link <- file.path(run_dir, df)
      if (!file.exists(link)) {
        try(file.symlink(file.path(paths$data, df), link), silent = TRUE)
      }
    }
  }

  objects_path <- file.path(run_dir, "objects.txt")

  attached_literal <- paste(deparse(DEFAULT_ATTACHED), collapse = "")

  # Every script gets a PNG device so plot() calls are captured as images instead
  # of failing for lack of a display. dev.off() flushes the last page to disk.
  # Before user code: restore the saved workspace + attached packages (if any).
  # After user code: persist workspace + attached-package list back to the session dir.
  wrapped <- c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
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
    TABLE_EMIT_HELPERS,
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
    # A negative status (or >=128) means the process was killed by a signal — on
    # this hardened, memory-capped container that's almost always the OOM killer
    # (e.g. read.csv on a multi-hundred-MB file), not a code error. Say so plainly
    # instead of the cryptic "exited with status -9".
    error = if (result$status != 0) {
      if (isTRUE(result$status < 0) || isTRUE(result$status >= 128)) {
        "The run was terminated before finishing — most likely it ran out of memory (e.g. reading a very large file). Try a smaller dataset, read it in chunks, or give the backend more memory."
      } else {
        sprintf("R exited with status %d.", result$status)
      }
    } else NULL,
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
  unlink(paths$data, recursive = TRUE, force = TRUE)
  if (isTRUE(body$purgePackages)) unlink(paths$rlib, recursive = TRUE, force = TRUE)
  list(ok = TRUE)
}

#* Install a CRAN package into a session's library
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

#* Uninstall a package from a session's library
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
    ok <- tryCatch(isTRUE(file.copy(file.path(LEGACY_PKG_LIB, p), lib, recursive = TRUE)),
                   error = function(e) FALSE)
    if (isTRUE(ok)) copied <- c(copied, p)
  }
  list(imported = length(copied), packages = as.list(copied))
}

#* Health check
#* @get /health
function() {
  list(status = "ok")
}

#* List user-installed packages in a session's library
#* @get /packages
function(sessionId = "default") {
  lib <- session_paths(sanitize_session_id(sessionId))$rlib
  # noCache: installed.packages() caches per libpath, and this plumber process is
  # long-lived — without noCache a package installed since the first listing can
  # be missed, so a just-installed package appears absent.
  pkgs <- tryCatch(rownames(installed.packages(lib.loc = lib, noCache = TRUE)), error = function(e) NULL)
  if (is.null(pkgs)) pkgs <- character(0)
  list(packages = as.list(pkgs))
}

#* Completion symbols for a session: base + recommended + attached-package exports
#* @get /symbols
function(sessionId = "default") {
  session_id <- sanitize_session_id(sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("symbols-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "symbols.R")
  out_path <- file.path(run_dir, "symbols.txt")

  attached_literal <- paste(deparse(DEFAULT_ATTACHED), collapse = "")
  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    sprintf('base_pkgs <- %s', attached_literal),
    sprintf('extra <- if (file.exists(%s)) readLines(%s) else character(0)',
            shQuote(paths$attached), shQuote(paths$attached)),
    'pkgs <- unique(c(base_pkgs, extra[nzchar(extra)]))',
    'syms <- unlist(lapply(pkgs, function(p) tryCatch(getNamespaceExports(p), error = function(e) character(0))))',
    'syms <- unique(syms[grepl("^[A-Za-z.][A-Za-z0-9._]*$", syms)])',
    'syms <- sort(syms)',
    sprintf('if (length(syms) > %d) syms <- syms[seq_len(%d)]', SYMBOLS_MAX, SYMBOLS_MAX),
    sprintf('writeLines(syms, %s)', shQuote(out_path))
  ), script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )
  syms <- if (!inherits(result, "error") && file.exists(out_path)) readLines(out_path) else character(0)
  list(symbols = as.list(syms))
}

#* Render an R help topic to text for a session
#* @post /help
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  topic <- body$topic
  if (is.null(topic) || !is.character(topic) || length(topic) != 1 || !grepl("^[A-Za-z0-9._]+$", topic)) {
    res$status <- 400
    return(list(
      topic = if (is.character(topic) && length(topic) == 1) topic else "",
      packageName = NULL, text = "", found = FALSE
    ))
  }

  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("help-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "help.R")
  out_text <- file.path(run_dir, "help.txt")
  out_pkg <- file.path(run_dir, "pkg.txt")

  writeLines(c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    sprintf('topic <- %s', shQuote(topic)),
    # Resolve help for a topic held in a variable via substitute(); then fetch the
    # parsed Rd with utils:::.getHelpFile (the API the help system itself uses) and
    # render it to text with tools::Rd2txt.
    'h <- tryCatch(eval(substitute(utils::help(TT), list(TT = as.name(topic)))), error = function(e) NULL)',
    'if (!is.null(h) && length(h) >= 1) {',
    '  path <- as.character(h)[1]',
    '  tryCatch({',
    '    rd <- utils:::.getHelpFile(path)',
    sprintf('    tools::Rd2txt(rd, out = %s)', shQuote(out_text)),
    sprintf('    writeLines(basename(dirname(dirname(path))), %s)', shQuote(out_pkg)),
    '  }, error = function(e) NULL)',
    '}'
  ), script_path)

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )

  text <- if (!inherits(result, "error") && file.exists(out_text)) {
    paste(readLines(out_text, warn = FALSE), collapse = "\n")
  } else ""
  # Rd2txt renders section titles with terminal overstrike (`_<BS>` underline and
  # `X<BS>X` bold); strip those control sequences so the app gets plain text.
  if (nzchar(text)) text <- gsub(".\010", "", text)
  pkg <- if (file.exists(out_pkg)) readLines(out_pkg, warn = FALSE)[1] else NULL

  list(topic = topic, packageName = pkg, text = text, found = nzchar(text))
}

#* Preview a data file or a workspace object as a table. Strictly read-only:
#* never writes session state (no save.image, no history), so it is safe to call
#* freely. Body: {sessionId?, source: "file"|"object", name}.
#* @post /preview
function(req, res) {
  body <- tryCatch(jsonlite::fromJSON(req$postBody), error = function(e) NULL)
  source <- body$source
  name <- body$name

  if (is.null(source) || !is.character(source) || length(source) != 1 || !(source %in% c("file", "object"))) {
    res$status <- 400
    return(list(table = NULL, error = "source must be 'file' or 'object'.", truncated = FALSE))
  }
  if (identical(source, "file")) {
    safe <- sanitize_data_name(name)
    if (is.null(safe)) {
      res$status <- 400
      return(list(table = NULL, error = "Invalid file name.", truncated = FALSE))
    }
    name <- safe
  } else {
    if (is.null(name) || !is.character(name) || length(name) != 1 || !grepl("^[A-Za-z.][A-Za-z0-9._]*$", name)) {
      res$status <- 400
      return(list(table = NULL, error = "Invalid object name.", truncated = FALSE))
    }
  }

  session_id <- sanitize_session_id(body$sessionId)
  paths <- session_paths(session_id)
  dir.create(paths$rlib, recursive = TRUE, showWarnings = FALSE)

  run_dir <- file.path(tempdir(), paste0("preview-", format(Sys.time(), "%Y%m%d%H%M%OS3"), "-", sample.int(1e6, 1)))
  dir.create(run_dir, recursive = TRUE)
  on.exit(unlink(run_dir, recursive = TRUE, force = TRUE), add = TRUE)
  script_path <- file.path(run_dir, "preview.R")
  err_path <- file.path(run_dir, "error.txt")

  # Source-specific snippet that assigns the value to preview into `.df`.
  if (identical(source, "file")) {
    src <- file.path(paths$data, name)
    if (!file.exists(src)) {
      return(list(table = NULL, error = "No such data file.", truncated = FALSE))
    }
    # Read only enough rows for the preview (cap + 1, to detect "there's more").
    # A preview is a peek — reading a whole multi-hundred-MB CSV just to show 200
    # rows OOM-kills the subprocess. nrows keeps it bounded. (rds/xlsx/parquet load
    # fully; there's no partial read for those, but they're the less common case.)
    preview_read_rows <- TABLE_MAX_ROWS + 1L
    read_lines <- c(
      sprintf('fname <- %s', shQuote(name)),
      'ext <- tolower(tools::file_ext(fname))',
      'need <- function(pkg) if (!requireNamespace(pkg, quietly = TRUE)) stop(sprintf("Install \'%s\' in this project to preview .%s files.", pkg, ext))',
      sprintf('.df <- if (ext == "csv") utils::read.csv(fname, check.names = FALSE, nrows = %dL)', preview_read_rows),
      sprintf('  else if (ext %%in%% c("tsv", "tab")) utils::read.delim(fname, check.names = FALSE, nrows = %dL)', preview_read_rows),
      '  else if (ext == "rds") readRDS(fname)',
      '  else if (ext %in% c("xlsx", "xls")) { need("readxl"); as.data.frame(readxl::read_excel(fname)) }',
      '  else if (ext == "parquet") { need("arrow"); as.data.frame(arrow::read_parquet(fname)) }',
      '  else stop("Can\'t preview this file type.")'
    )
  } else {
    read_lines <- c(
      sprintf('.nm <- %s', shQuote(name)),
      sprintf('.e <- new.env(); if (file.exists(%s)) load(%s, envir = .e)', shQuote(paths$workspace), shQuote(paths$workspace)),
      'if (!exists(.nm, envir = .e, inherits = FALSE)) stop(sprintf("No object named \'%s\' in this project\'s workspace.", .nm))',
      '.df <- get(.nm, envir = .e)'
    )
  }

  script <- c(
    sprintf('.libPaths(c(%s, .libPaths()))', shQuote(paths$rlib)),
    'invisible(tryCatch({',
    read_lines,
    TABLE_EMIT_HELPERS,
    'if (!.tabular(.df)) stop("Not a table.")',
    # If more rows exist than we show, note it and trim, so the emitted totalRows
    # is the displayed count (no misleading exact total for a capped read).
    'if (is.data.frame(.df) && nrow(.df) > .maxrows) { writeLines("1", "truncated.flag"); .df <- utils::head(.df, .maxrows) }',
    '.emit(.df)',
    sprintf('}, error = function(e) writeLines(conditionMessage(e), %s)))', shQuote(err_path))
  )
  writeLines(script, script_path)

  # Symlink the data file into the run dir AFTER writing the harness, and only
  # if the name doesn't collide with a harness file (preview.R/error.txt) — a
  # data file must never shadow, or be followed-and-overwritten by, our own
  # files. Mirrors the ordering + file.exists guard used by /execute.
  if (identical(source, "file")) {
    link <- file.path(run_dir, name)
    if (!file.exists(link)) try(file.symlink(src, link), silent = TRUE)
  }

  result <- tryCatch(
    processx::run("Rscript", c("--vanilla", script_path), wd = run_dir,
                  timeout = EXECUTION_TIMEOUT_SECONDS, error_on_status = FALSE),
    error = function(e) e
  )

  if (inherits(result, "error")) {
    timed_out <- grepl("timed out", conditionMessage(result), ignore.case = TRUE)
    return(list(
      table = NULL,
      error = if (timed_out) sprintf("Preview timed out after %ss.", EXECUTION_TIMEOUT_SECONDS) else "Preview failed to start.",
      truncated = FALSE
    ))
  }

  err_msg <- if (file.exists(err_path)) paste(readLines(err_path, warn = FALSE), collapse = "\n") else ""
  table_file <- file.path(run_dir, "table001.json")

  if (nzchar(err_msg) || !file.exists(table_file)) {
    msg <- if (nzchar(err_msg)) err_msg else "Could not read a table from this source."
    return(list(table = NULL, error = msg, truncated = FALSE))
  }

  tbl <- jsonlite::fromJSON(table_file, simplifyVector = FALSE)
  total <- tryCatch(as.integer(tbl$totalRows), error = function(e) NA_integer_)
  truncated <- file.exists(file.path(run_dir, "truncated.flag"))
  list(table = tbl, error = NULL, truncated = isTRUE(truncated) || isTRUE(total > TABLE_MAX_ROWS))
}

#* Upload a data file into a session's data dir (multipart/form-data, part "file").
#* @post /upload
function(req, res, sessionId = "default") {
  session_id <- sanitize_session_id(sessionId)
  ct <- req$HTTP_CONTENT_TYPE
  if (is.null(ct) || !grepl("multipart/form-data", ct, ignore.case = TRUE)) {
    res$status <- 400
    return(list(name = "", size = 0, error = "Expected multipart/form-data."))
  }
  boundary <- sub(';.*$', '', sub('^.*boundary=', '', ct))
  boundary <- gsub('^"|"$', '', trimws(boundary))
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

#* List a session's uploaded data files.
#* @get /data
function(req, res, sessionId = "default") {
  paths <- session_paths(sanitize_session_id(sessionId))
  if (!dir.exists(paths$data)) return(list(files = list()))
  names_sorted <- sort(list.files(paths$data, full.names = FALSE))
  files <- Filter(Negate(is.null), lapply(names_sorted, function(n) {
    info <- file.info(file.path(paths$data, n))
    if (is.na(info$size)) return(NULL)
    list(name = n, size = as.numeric(info$size))
  }))
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
