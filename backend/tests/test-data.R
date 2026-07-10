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
