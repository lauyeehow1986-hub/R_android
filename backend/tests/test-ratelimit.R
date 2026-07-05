test_that("requests over the per-minute limit get 429", {
  srv <- local_server(env = list(R_RATE_LIMIT_PER_MINUTE = "2"))
  expect_equal(post_execute(srv, "cat(1)")$status, 200)
  expect_equal(post_execute(srv, "cat(1)")$status, 200)
  expect_equal(post_execute(srv, "cat(1)")$status, 429)
})
