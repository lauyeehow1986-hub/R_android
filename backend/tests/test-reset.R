test_that("reset clears session state", {
  srv <- local_server()
  post_execute(srv, "x <- 5")
  expect_identical(post_execute(srv, "cat(exists('x'))")$body$stdout, "TRUE")

  reset <- post_reset(srv)
  expect_equal(reset$status, 200)
  expect_true(isTRUE(reset$body$ok))

  expect_identical(post_execute(srv, "cat(exists('x'))")$body$stdout, "FALSE")
})

test_that("reset of an unused session is still ok", {
  srv <- local_server()
  reset <- post_reset(srv, session_id = "never-used")
  expect_equal(reset$status, 200)
  expect_true(isTRUE(reset$body$ok))
})
