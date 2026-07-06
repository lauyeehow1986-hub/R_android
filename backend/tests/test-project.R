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
