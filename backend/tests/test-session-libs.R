test_that("session package libraries are isolated from each other", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))

  install_res <- post_install(srv, "praise", session_id = "alpha")
  skip_if_not(isTRUE(install_res$body$installed), "no network")

  expect_true("praise" %in% unlist(get_packages(srv, session_id = "alpha")$body$packages))
  expect_false("praise" %in% unlist(get_packages(srv, session_id = "beta")$body$packages))

  ok <- post_execute(srv, "library(praise); cat(is.character(praise()))", session_id = "alpha")
  expect_identical(ok$body$stdout, "TRUE")

  bad <- post_execute(srv, "library(praise)", session_id = "beta")
  expect_false(is.null(bad$body$error))
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
