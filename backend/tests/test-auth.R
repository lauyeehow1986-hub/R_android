test_that("api key is enforced on protected routes when configured", {
  srv <- local_server(env = list(R_API_KEY = "secret"))
  expect_equal(post_execute(srv, "cat(1)")$status, 401)                 # no key
  expect_equal(post_execute(srv, "cat(1)", key = "wrong")$status, 401)  # bad key
  expect_equal(post_execute(srv, "cat(1)", key = "secret")$status, 200) # good key
  expect_equal(post_reset(srv)$status, 401)                             # reset protected
  expect_equal(get_health(srv)$status, 200)                            # health open
})
