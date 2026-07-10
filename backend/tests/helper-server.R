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
    wd = "..", env = child_env,
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
api_request <- function(server, path, body = NULL, key = NULL, query = NULL) {
  req <- request(server$base_url) |>
    req_url_path(path) |>
    req_error(is_error = function(resp) FALSE)
  if (!is.null(query)) req <- req_url_query(req, !!!query)
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

post_install <- function(server, package, key = NULL, session_id = NULL) {
  body <- list(package = package)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/install", body = body, key = key)
}

get_packages <- function(server, session_id = NULL, key = NULL) {
  query <- if (!is.null(session_id)) list(sessionId = session_id) else NULL
  api_request(server, "/packages", query = query, key = key)
}

get_symbols <- function(server, session_id = NULL, key = NULL) {
  query <- if (!is.null(session_id)) list(sessionId = session_id) else NULL
  api_request(server, "/symbols", query = query, key = key)
}

post_help <- function(server, topic, session_id = NULL, key = NULL) {
  body <- list(topic = topic)
  if (!is.null(session_id)) body$sessionId <- session_id
  api_request(server, "/help", body = body, key = key)
}

get_health <- function(server) api_request(server, "/health")
