test_that("packages list is empty on a fresh session library", {
  srv <- local_server()
  res <- api_request(srv, "/packages")
  expect_equal(res$status, 200)
  expect_equal(length(res$body$packages), 0)
})

test_that("the session library is on .libPaths during execution", {
  # /execute prepends the active session's rlib (SESSION_DIR/<id>/rlib) to
  # .libPaths(); with no sessionId that's the "default" session.
  srv <- local_server()
  res <- post_execute(srv, "cat(basename(.libPaths()[1]), basename(dirname(.libPaths()[1])))")
  expect_identical(res$body$stdout, "rlib default")
})
