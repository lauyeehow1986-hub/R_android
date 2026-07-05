test_that("packages list is empty on a fresh library", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/packages")
  expect_equal(res$status, 200)
  expect_equal(length(res$body$packages), 0)
})

test_that("the shared library is on .libPaths during execution", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- post_execute(srv, "cat(normalizePath(Sys.getenv('R_PKG_LIB')) %in% normalizePath(.libPaths()))")
  expect_identical(res$body$stdout, "TRUE")
})
