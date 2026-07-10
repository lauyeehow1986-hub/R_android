test_that("/symbols includes base R function names", {
  srv <- local_server()
  res <- get_symbols(srv, session_id = "sym")
  expect_equal(res$status, 200)
  syms <- unlist(res$body$symbols)
  expect_true("mean" %in% syms)
  expect_true("data.frame" %in% syms)
})

test_that("/symbols reflects attached packages and is session-scoped", {
  srv <- local_server(env = list(R_CRAN_REPO = "https://cloud.r-project.org"))
  ins <- post_install(srv, "praise", session_id = "withpkg")
  skip_if_not(isTRUE(ins$body$installed), "no network")

  run <- post_execute(srv, "library(praise)", session_id = "withpkg")
  expect_true(is.null(run$body$error))

  with_syms <- unlist(get_symbols(srv, session_id = "withpkg")$body$symbols)
  expect_true("praise" %in% with_syms)

  without_syms <- unlist(get_symbols(srv, session_id = "nopkg")$body$symbols)
  expect_false("praise" %in% without_syms)
})
