test_that("health check responds ok", {
  srv <- local_server()
  res <- get_health(srv)
  expect_equal(res$status, 200)
  expect_equal(res$body$status, "ok")
})
