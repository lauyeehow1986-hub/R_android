test_that("session package libraries are isolated from each other", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))

  install_res <- post_install(srv, "praise", session_id = "alpha")
  skip_if_not(isTRUE(install_res$body$installed), "no network")

  # The freshly-installed package lands only in alpha's rlib, not beta's.
  expect_true("praise" %in% unlist(get_packages(srv, session_id = "alpha")$body$packages))
  expect_false("praise" %in% unlist(get_packages(srv, session_id = "beta")$body$packages))

  # alpha can load the package it just installed.
  ok <- post_execute(srv, "library(praise); cat(is.character(praise()))", session_id = "alpha")
  expect_identical(ok$body$stdout, "TRUE")

  # Isolation mechanism: each session prepends only its OWN rlib to .libPaths();
  # alpha's library dir is never on beta's path (and vice versa), so an install
  # in one session can't leak onto the other's path. We assert this directly
  # rather than expecting `library(praise)` to fail in beta, because praise is a
  # testthat dependency and is therefore already present in the CI site library,
  # where any session can load it regardless of per-session isolation.
  alpha_paths <- strsplit(
    post_execute(srv, "cat(.libPaths(), sep = '\\n')", session_id = "alpha")$body$stdout, "\n")[[1]]
  beta_paths <- strsplit(
    post_execute(srv, "cat(.libPaths(), sep = '\\n')", session_id = "beta")$body$stdout, "\n")[[1]]
  expect_match(alpha_paths[1], file.path("alpha", "rlib"), fixed = TRUE)
  expect_match(beta_paths[1], file.path("beta", "rlib"), fixed = TRUE)
  expect_false(any(grepl(file.path("alpha", "rlib"), beta_paths, fixed = TRUE)))
  expect_false(any(grepl(file.path("beta", "rlib"), alpha_paths, fixed = TRUE)))
})

test_that("installing with no sessionId targets the default session", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))

  install_res <- post_install(srv, "praise")
  skip_if_not(isTRUE(install_res$body$installed), "no network")

  expect_true("praise" %in% unlist(get_packages(srv, session_id = "default")$body$packages))
})

test_that("reset purgePackages wipes the session library, default keeps it", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))

  install_res <- post_install(srv, "praise", session_id = "purge")
  skip_if_not(isTRUE(install_res$body$installed), "no network")

  expect_true("praise" %in% unlist(get_packages(srv, session_id = "purge")$body$packages))

  post_reset(srv, session_id = "purge")
  expect_true("praise" %in% unlist(get_packages(srv, session_id = "purge")$body$packages))

  api_request(srv, "/reset", body = list(sessionId = "purge", purgePackages = TRUE))
  expect_false("praise" %in% unlist(get_packages(srv, session_id = "purge")$body$packages))
})

test_that("import-legacy is idempotent and no-ops on an empty legacy lib", {
  srv <- local_server()

  res <- api_request(srv, "/import-legacy", body = list(sessionId = "imp"))
  expect_equal(res$status, 200)
  expect_equal(res$body$imported, 0)
  expect_equal(length(res$body$packages), 0)
})

test_that("import-legacy copies packages and is idempotent", {
  legacy <- tempfile("legacy-"); dir.create(legacy)
  # Seed the legacy lib with a real installed package so installed.packages() sees it.
  file.copy(find.package("jsonlite"), legacy, recursive = TRUE)

  srv <- local_server(env = list(R_PKG_LIB = legacy))
  first <- api_request(srv, "/import-legacy", body = list(sessionId = "imp"))
  expect_true(first$body$imported >= 1)

  pkgs <- get_packages(srv, session_id = "imp")
  expect_true("jsonlite" %in% unlist(pkgs$body$packages))

  # Second import is a no-op (jsonlite already present in the session lib).
  second <- api_request(srv, "/import-legacy", body = list(sessionId = "imp"))
  expect_equal(second$body$imported, 0)
})
