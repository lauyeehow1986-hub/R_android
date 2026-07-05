test_that("install rejects an invalid package name", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/install", body = list(package = "../evil"))
  expect_equal(res$status, 400)
})

test_that("installing a nonexistent package reports not installed", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  res <- api_request(srv, "/install", body = list(package = "nonexistentpkgxyz"))
  expect_equal(res$status, 200)
  expect_false(res$body$installed)
  expect_false(is.null(res$body$error))
})

test_that("install is auth-protected", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_API_KEY = "secret"))
  expect_equal(api_request(srv, "/install", body = list(package = "praise"))$status, 401)
})

test_that("a real package installs and is usable in a later run", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  res <- api_request(srv, "/install", body = list(package = "praise"))
  expect_equal(res$status, 200)
  expect_true(res$body$installed)
  expect_true("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
  run <- post_execute(srv, "library(praise); cat(is.character(praise()))")
  expect_identical(run$body$stdout, "TRUE")
})
