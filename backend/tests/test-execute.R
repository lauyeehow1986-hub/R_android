test_that("execute runs code and returns unboxed scalars", {
  srv <- local_server()
  res <- post_execute(srv, "cat(1 + 1)")
  expect_equal(res$status, 200)
  # Scalar string (not a 1-element list) guards run.R's unboxed-JSON serializer.
  expect_true(is.character(res$body$stdout))
  expect_identical(res$body$stdout, "2")
  expect_null(res$body$error)
  expect_false(res$body$timedOut)
  expect_equal(length(res$body$plots), 0)
})

test_that("execute captures plots as base64", {
  srv <- local_server()
  res <- post_execute(srv, "plot(1:3)")
  expect_equal(res$status, 200)
  expect_equal(length(res$body$plots), 1)
  expect_true(nchar(res$body$plots[[1]]) > 100)
})

test_that("missing code is a 400", {
  srv <- local_server()
  res <- api_request(srv, "/execute", body = list(notcode = "x"))
  expect_equal(res$status, 400)
  expect_true(nchar(res$body$error) > 0)
})

test_that("code over the size limit is a 413", {
  srv <- local_server(env = list(R_MAX_CODE_LENGTH = "10"))
  res <- post_execute(srv, "cat('definitely longer than ten characters')")
  expect_equal(res$status, 413)
})

test_that("an R error yields a non-null error and stderr", {
  srv <- local_server()
  res <- post_execute(srv, "stop('boom')")
  expect_equal(res$status, 200)
  expect_false(is.null(res$body$error))
  expect_true(grepl("boom", res$body$stderr))
})

test_that("execute stdout carries an ordered plot marker before later text", {
  srv <- local_server()
  res <- post_execute(srv, "plot(1); cat('AFTER')")
  expect_equal(res$status, 200)
  marker <- paste0(intToUtf8(2), "RMOBILE:PLOT", intToUtf8(3))
  expect_true(grepl(marker, res$body$stdout, fixed = TRUE))
  expect_lt(
    regexpr(marker, res$body$stdout, fixed = TRUE)[1],
    regexpr("AFTER", res$body$stdout, fixed = TRUE)[1]
  )
})

test_that("execute stdout carries a table marker for a top-level data frame", {
  srv <- local_server()
  res <- post_execute(srv, "data.frame(x = 1:2)")
  expect_equal(res$status, 200)
  marker <- paste0(intToUtf8(2), "RMOBILE:TABLE", intToUtf8(3))
  expect_true(grepl(marker, res$body$stdout, fixed = TRUE))
  expect_length(res$body$tables, 1)
})
