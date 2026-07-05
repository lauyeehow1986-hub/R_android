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
