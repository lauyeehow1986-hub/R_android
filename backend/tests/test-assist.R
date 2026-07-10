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

test_that("/help renders base help to text", {
  srv <- local_server()
  res <- post_help(srv, "mean")
  expect_equal(res$status, 200)
  expect_true(isTRUE(res$body$found))
  expect_equal(res$body$packageName, "base")
  expect_match(res$body$text, "Usage")
})

test_that("/help reports not-found for unknown topics", {
  srv <- local_server()
  res <- post_help(srv, "zzznotarealfn")
  expect_equal(res$status, 200)
  expect_false(isTRUE(res$body$found))
  expect_equal(res$body$text, "")
})

test_that("/help rejects an invalid topic with 400", {
  srv <- local_server()
  res <- api_request(srv, "/help", body = list(topic = "a b"))
  expect_equal(res$status, 400)
  expect_false(isTRUE(res$body$found))
})
