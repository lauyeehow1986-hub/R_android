test_that("uninstall rejects an invalid package name", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  expect_equal(api_request(srv, "/uninstall", body = list(package = "../evil"))$status, 400)
})

test_that("uninstalling a not-installed package reports removed false", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib))
  res <- api_request(srv, "/uninstall", body = list(package = "nonexistentpkgxyz"))
  expect_equal(res$status, 200)
  expect_false(res$body$removed)
})

test_that("uninstall is auth-protected", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_API_KEY = "secret"))
  expect_equal(api_request(srv, "/uninstall", body = list(package = "praise"))$status, 401)
})

test_that("installing then uninstalling a package removes it", {
  lib <- tempfile("rlib-"); dir.create(lib)
  srv <- local_server(env = list(R_PKG_LIB = lib, R_CRAN_REPO = "https://cloud.r-project.org"))
  expect_true(api_request(srv, "/install", body = list(package = "praise"))$body$installed)
  expect_true("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
  res <- api_request(srv, "/uninstall", body = list(package = "praise"))
  expect_equal(res$status, 200)
  expect_true(res$body$removed)
  expect_false("praise" %in% unlist(api_request(srv, "/packages")$body$packages))
})
